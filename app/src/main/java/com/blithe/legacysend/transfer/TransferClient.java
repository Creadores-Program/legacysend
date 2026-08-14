package com.blithe.legacysend.transfer;

import android.content.ContentResolver;
import android.content.Context;

import com.blithe.legacysend.R;
import com.blithe.legacysend.model.DeviceInfo;
import com.blithe.legacysend.model.TransferFile;
import com.blithe.legacysend.protocol.ProtocolJson;
import com.blithe.legacysend.security.TlsIdentity;
import com.blithe.legacysend.util.IoUtils;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class TransferClient {
    public interface Listener {
        void onProgress(String currentFile, int fileIndex, int fileCount, int percent);
        void onFinished(String message);
        void onFailed(String message);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final Context context;
    private final ContentResolver resolver;
    private final TlsIdentity identity;
    private final DeviceInfo self;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Socket activeSocket;
    private volatile String activeSession;
    private volatile DeviceInfo activeDevice;

    public TransferClient(Context context, ContentResolver resolver, TlsIdentity identity, DeviceInfo self) {
        this.context = context.getApplicationContext();
        this.resolver = resolver;
        this.identity = identity;
        this.self = self;
    }

    public DeviceInfo register(DeviceInfo remote) throws Exception {
        JSONObject registration = self.toJson(false, true);
        registration.remove("announce");
        
        HttpResponse response = executeRequest(remote, "/api/localsend/v2/register", "POST", 
                registration.toString().getBytes(UTF8), "application/json; charset=utf-8", 10000, null);

        if (response.statusCode / 100 != 2) throw statusError(response);
        JSONObject jsonResponse = new JSONObject(new String(response.body, UTF8));
        return DeviceInfo.fromJson(context, jsonResponse, remote.getAddress());
    }

    public void send(DeviceInfo remote, List<TransferFile> files, Listener listener) {
        cancelled.set(false);
        activeDevice = remote;
        activeSession = null;
        try {
            if (files == null || files.isEmpty()) {
                throw new IOException(context.getString(R.string.error_no_files_selected));
            }

            byte[] prepareBody = ProtocolJson.prepareUpload(self, files).toString().getBytes(UTF8);
            HttpResponse prepareResponse = executeRequest(remote, "/api/localsend/v2/prepare-upload", "POST",
                    prepareBody, "application/json; charset=utf-8", 300000, null);

            int prepareStatus = prepareResponse.statusCode;
            if (prepareStatus == 204) {
                listener.onFinished(context.getString(R.string.msg_no_files_needed));
                return;
            }
            if (prepareStatus == 403) {
                throw new IOException(context.getString(R.string.error_recipient_rejected));
            }
            if (prepareStatus / 100 != 2) throw statusError(prepareResponse);

            JSONObject response = new JSONObject(new String(prepareResponse.body, UTF8));
            String sessionId = response.getString("sessionId");
            JSONObject tokens = response.getJSONObject("files");
            activeSession = sessionId;

            long totalBytes = 0L;
            for (int i = 0; i < files.size(); i++) {
                totalBytes += files.get(i).getSize();
            }
            long completedBefore = 0L;

            for (int index = 0; index < files.size(); index++) {
                checkCancelled();
                final TransferFile file = files.get(index);
                String token = tokens.getString(file.getId());
                String path = "/api/localsend/v2/upload?sessionId=" + encode(sessionId)
                        + "&fileId=" + encode(file.getId()) + "&token=" + encode(token);

                final long prior = completedBefore;
                final long total = totalBytes;
                final int fileNumber = index + 1;

                InputStream input = resolver.openInputStream(file.getUri());
                if (input == null) {
                    throw new IOException(context.getString(R.string.error_cannot_read_file, file.getFileName()));
                }

                HttpResponse uploadResponse;
                try {
                    uploadResponse = executeStreamRequest(remote, path, "POST", input, file.getSize(), 
                            "application/octet-stream", 300000, new IoUtils.ProgressListener() {
                                @Override public void onBytes(long copied) throws IOException {
                                    checkCancelled();
                                    listener.onProgress(file.getFileName(), fileNumber, files.size(),
                                            IoUtils.percent(prior + copied, total));
                                }
                            });
                } finally {
                    input.close();
                }

                if (uploadResponse.statusCode / 100 != 2) throw statusError(uploadResponse);
                completedBefore += file.getSize();
            }

            listener.onProgress(files.get(files.size() - 1).getFileName(), files.size(), files.size(), 100);
            listener.onFinished(context.getString(R.string.msg_files_sent_successfully));
        } catch (Exception error) {
            String message = readable(error);
            if (error instanceof FileNotFoundException) {
                message = context.getString(R.string.error_file_not_found_or_permission);
            }
            listener.onFailed(cancelled.get() ? context.getString(R.string.msg_send_cancelled) : message);
        } finally {
            closeActiveSocket();
            activeSession = null;
            activeDevice = null;
        }
    }

    public void cancel() {
        cancelled.set(true);
        closeActiveSocket();
        final String session = activeSession;
        final DeviceInfo device = activeDevice;
        if (session != null && device != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        executeRequest(device, "/api/localsend/v2/cancel?sessionId=" + encode(session),
                                "POST", new byte[0], "text/plain", 5000, null);
                    } catch (Exception ignored) {}
                }
            }, "LegacySend-cancel").start();
        }
    }

    private HttpResponse executeRequest(DeviceInfo remote, String path, String method, byte[] body,
                                       String contentType, int timeout, IoUtils.ProgressListener listener) throws Exception {
        return executeStreamRequest(remote, path, method, body != null ? new java.io.ByteArrayInputStream(body) : null,
                body != null ? body.length : 0, contentType, timeout, listener);
    }

    private HttpResponse executeStreamRequest(DeviceInfo remote, String path, String method, InputStream bodyInput,
                                              long bodyLength, String contentType, int timeout,
                                              IoUtils.ProgressListener listener) throws Exception {
        boolean isHttps = !"http".equalsIgnoreCase(remote.getProtocol());
        Socket socket;

        if (isHttps) {
            SSLSocketFactory factory = identity.createPinnedClientFactory(context, remote.getFingerprint());
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(remote.getAddress(), remote.getPort());
            sslSocket.setSoTimeout(timeout);
            sslSocket.startHandshake();
            socket = sslSocket;
        } else {
            socket = new Socket();
            socket.connect(new InetSocketAddress(remote.getAddress(), remote.getPort()), 10000);
            socket.setSoTimeout(timeout);
        }

        activeSocket = socket;

        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);

        boolean useChunked = isVersionGreaterOrEqual(remote.getVersion(), "2.2");

        StringBuilder headersBuilder = new StringBuilder();
        headersBuilder.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        headersBuilder.append("Host: ").append(remote.getAddress().getHostAddress()).append(":").append(remote.getPort()).append("\r\n");
        headersBuilder.append("User-Agent: LegacySend\r\n");
        headersBuilder.append("Accept: application/json\r\n");
        headersBuilder.append("Content-Type: ").append(contentType).append("\r\n");

        if (useChunked) {
            headersBuilder.append("Transfer-Encoding: chunked\r\n");
        } else {
            headersBuilder.append("Content-Length: ").append(bodyLength).append("\r\n");
        }
        
        headersBuilder.append("Connection: close\r\n\r\n");

        out.write(headersBuilder.toString().getBytes(UTF8));

        if (bodyInput != null && bodyLength > 0) {
            if (useChunked) {
                writeChunkedBody(bodyInput, out, listener);
            } else {
                IoUtils.copy(bodyInput, out, bodyLength, listener);
            }
        } else {
            if (useChunked) {
                out.write("0\r\n\r\n".getBytes(UTF8));
            }
            out.flush();
        }

        HttpResponse response = parseResponse(in);
        socket.close();
        return response;
    }

    private void writeChunkedBody(InputStream input, OutputStream output, IoUtils.ProgressListener listener) throws IOException {
        byte[] buffer = new byte[8192];
        long totalCopied = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > 0) {
                String chunkHeader = Integer.toHexString(read) + "\r\n";
                output.write(chunkHeader.getBytes(UTF8));
                output.write(buffer, 0, read);
                output.write("\r\n".getBytes(UTF8));
                
                totalCopied += read;
                if (listener != null) {
                    listener.onBytes(totalCopied);
                }
            }
        }
        output.write("0\r\n\r\n".getBytes(UTF8));
        output.flush();
    }

    private boolean isVersionGreaterOrEqual(String remoteVersion, String targetVersion) {
        if (remoteVersion == null || remoteVersion.trim().length() == 0) {
            return false;
        }
        try {
            String[] remoteParts = remoteVersion.split("\\.");
            String[] targetParts = targetVersion.split("\\.");
            
            int length = Math.max(remoteParts.length, targetParts.length);
            for (int i = 0; i < length; i++) {
                int rVal = i < remoteParts.length ? parseVersionPart(remoteParts[i]) : 0;
                int tVal = i < targetParts.length ? parseVersionPart(targetParts[i]) : 0;
                if (rVal > tVal) return true;
                if (rVal < tVal) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int parseVersionPart(String part) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        return sb.length() > 0 ? Integer.parseInt(sb.toString()) : 0;
    }

    private HttpResponse parseResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        String[] parts = statusLine.split(" ");
        int statusCode = 500;
        if (parts.length >= 2) {
            try { statusCode = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }

        Map<String, String> headers = new HashMap<String, String>();
        long contentLength = -1;
        boolean chunked = false;

        while (true) {
            String line = readLine(in);
            if (line.length() == 0) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String val = line.substring(colon + 1).trim();
                headers.put(key, val);
                if ("content-length".equals(key)) {
                    try { contentLength = Long.parseLong(val); } catch (Exception ignored) {}
                } else if ("transfer-encoding".equals(key) && val.toLowerCase(Locale.US).contains("chunked")) {
                    chunked = true;
                }
            }
        }

        ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
        if (chunked) {
            while (true) {
                String chunkSizeHex = readLine(in);
                int semicolon = chunkSizeHex.indexOf(';');
                if (semicolon >= 0) chunkSizeHex = chunkSizeHex.substring(0, semicolon);
                int size = Integer.parseInt(chunkSizeHex.trim(), 16);
                if (size == 0) {
                    readLine(in);
                    break;
                }
                byte[] chunk = new byte[size];
                int readTotal = 0;
                while (readTotal < size) {
                    int r = in.read(chunk, readTotal, size - readTotal);
                    if (r < 0) break;
                    readTotal += r;
                }
                bodyStream.write(chunk, 0, readTotal);
                readLine(in);
            }
        } else if (contentLength >= 0) {
            IoUtils.copy(in, bodyStream, contentLength, null);
        } else {
            IoUtils.copy(in, bodyStream, -1, null);
        }

        return new HttpResponse(statusCode, bodyStream.toByteArray());
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (output.size() <= 8192) {
            int current = input.read();
            if (current < 0) throw new IOException("HTTP response ended prematurely");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = output.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), UTF8);
            }
            output.write(current);
            previous = current;
        }
        throw new IOException("HTTP response line too long");
    }

    private void closeActiveSocket() {
        Socket socket = activeSocket;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            activeSocket = null;
        }
    }

    private IOException statusError(HttpResponse response) {
        String message = new String(response.body, UTF8);
        if (message.length() > 160) message = message.substring(0, 160);
        String formatted = context.getString(R.string.error_remote_returned_status, response.statusCode,
                message.length() == 0 ? "" : ": " + message);
        return new IOException(formatted);
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get()) {
            throw new IOException(context.getString(R.string.msg_send_cancelled));
        }
    }

    private static String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }

    private static final class HttpResponse {
        final int statusCode;
        final byte[] body;

        HttpResponse(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
