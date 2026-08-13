package com.blithe.legacysend.server;

import android.content.Context;

import com.blithe.legacysend.R;
import com.blithe.legacysend.model.DeviceInfo;
import com.blithe.legacysend.model.TransferFile;
import com.blithe.legacysend.protocol.ProtocolJson;
import com.blithe.legacysend.security.TlsIdentity;
import com.blithe.legacysend.storage.StorageUtils;
import com.blithe.legacysend.util.IoUtils;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;

public final class TransferServer {
    public interface Listener {
        void onRegistered(DeviceInfo device);
        void onIncoming(IncomingSession session);
        void onReceiveProgress(IncomingSession session, String fileName, int percent, String savePath);
        void onReceiveFinished(IncomingSession session, String savePath);
        void onReceiveFailed(IncomingSession session, String message);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final TlsIdentity identity;
    private final Context context;
    private final DeviceInfo self;
    private final Listener listener;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final Map<String, IncomingSession> sessions = new ConcurrentHashMap<String, IncomingSession>();
    private final Map<String, Set<String>> completedFiles = new ConcurrentHashMap<String, Set<String>>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile IncomingSession pendingSession;

    public TransferServer(Context context, TlsIdentity identity, DeviceInfo self, Listener listener) {
        this.context = context.getApplicationContext();
        this.identity = identity;
        this.self = self;
        this.listener = listener;
    }

    public synchronized void start() throws Exception {
        if (running.get()) return;
        if ("https".equalsIgnoreCase(self.getProtocol())) {
            serverSocket = identity.createServerSocket(self.getPort());
        } else {
            ServerSocket plainSocket = new ServerSocket();
            plainSocket.setReuseAddress(true);
            plainSocket.bind(new InetSocketAddress(self.getPort()));
            serverSocket = plainSocket;
        }
        running.set(true);
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "LegacySend-server");
        acceptThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        if (pendingSession != null) pendingSession.cancel();
        for (IncomingSession session : sessions.values()) session.cancel();
        sessions.clear();
        completedFiles.clear();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    public boolean isRunning() { return running.get(); }

    public void cancel(IncomingSession session) {
        if (session != null) session.cancel();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setSoTimeout(300000);
                workers.execute(new Runnable() {
                    @Override public void run() { handle(socket); }
                });
            } catch (IOException error) {
                if (running.get()) {
                    String msg = context.getString(R.string.error_server_accept_failed, readable(error));
                    listener.onReceiveFailed(null, msg);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            if (socket instanceof SSLSocket) ((SSLSocket) socket).startHandshake();
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream(), 32 * 1024);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            Request request = Request.read(input);
            route(socket, input, output, request);
        } catch (Exception error) {
            try {
                OutputStream output = socket.getOutputStream();
                respond(output, 500, "text/plain; charset=utf-8", readable(error).getBytes(UTF8));
            } catch (Exception ignored) {}
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void route(Socket socket, BufferedInputStream input, OutputStream output, Request request)
            throws Exception {
        String path = request.path;
        if ("GET".equals(request.method) && "/api/localsend/v2/info".equals(path)) {
            respondJson(output, 200, cleanDeviceJson(self));
            return;
        }
        if ("POST".equals(request.method) && "/api/localsend/v2/register".equals(path)) {
            JSONObject body = readJson(input, request.contentLength);
            DeviceInfo remote = DeviceInfo.fromJson(context, body, socket.getInetAddress());
            if (!certificateMatches(socket, remote)) {
                respond(output, 403, "text/plain", new byte[0]);
                return;
            }
            listener.onRegistered(remote);
            respondJson(output, 200, cleanDeviceJson(self));
            return;
        }
        if ("POST".equals(request.method) && "/api/localsend/v2/prepare-upload".equals(path)) {
            prepareUpload(socket, input, output, request);
            return;
        }
        if ("POST".equals(request.method) && "/api/localsend/v2/upload".equals(path)) {
            upload(socket, input, output, request);
            return;
        }
        if ("POST".equals(request.method) && "/api/localsend/v2/cancel".equals(path)) {
            IncomingSession session = sessions.remove(request.query.get("sessionId"));
            if (session != null) session.cancel();
            respond(output, 200, "text/plain", new byte[0]);
            return;
        }
        respond(output, 404, "text/plain; charset=utf-8", context.getString(R.string.error_endpoint_not_found).getBytes(UTF8));
    }

    private void prepareUpload(Socket socket, BufferedInputStream input, OutputStream output, Request request)
            throws Exception {
        synchronized (this) {
            if (pendingSession != null && pendingSession.getDecision() == IncomingSession.Decision.PENDING) {
                respond(output, 409, "text/plain", new byte[0]);
                return;
            }
        }
        JSONObject body = readJson(input, request.contentLength);
        DeviceInfo sender = DeviceInfo.fromJson(context, body.getJSONObject("info"), socket.getInetAddress());
        if (!certificateMatches(socket, sender)) {
            respond(output, 403, "text/plain", new byte[0]);
            return;
        }
        List<TransferFile> files = ProtocolJson.parseFiles(body.getJSONObject("files"));
        if (files.isEmpty()) {
            respond(output, 204, "text/plain", new byte[0]);
            return;
        }
        final IncomingSession session = new IncomingSession(sender, socket.getInetAddress(), files);
        synchronized (this) { pendingSession = session; }
        listener.onIncoming(session);
        IncomingSession.Decision decision = session.awaitDecision(5, TimeUnit.MINUTES);
        synchronized (this) { if (pendingSession == session) pendingSession = null; }
        if (decision != IncomingSession.Decision.ACCEPTED) {
            respond(output, 403, "text/plain", new byte[0]);
            return;
        }
        sessions.put(session.getSessionId(), session);
        completedFiles.put(session.getSessionId(), Collections.synchronizedSet(new HashSet<String>()));
        respondJson(output, 200, ProtocolJson.prepareResponse(session.getSessionId(), session.getTokens()));
    }

    private void upload(Socket socket, BufferedInputStream input, OutputStream output, Request request)
            throws Exception {
        String sessionId = request.query.get("sessionId");
        String fileId = request.query.get("fileId");
        String token = request.query.get("token");
        IncomingSession session = sessions.get(sessionId);
        final TransferFile metadata = session == null ? null : session.findFile(fileId);
        if (session == null || metadata == null || token == null || !token.equals(session.getToken(fileId))
                || !sameAddress(session.getSenderAddress(), socket.getInetAddress())) {
            respond(output, 403, "text/plain", new byte[0]);
            return;
        }
        if (session.getDecision() == IncomingSession.Decision.CANCELLED) {
            respond(output, 409, "text/plain", new byte[0]);
            return;
        }
        if (!request.isChucked && (request.contentLength < 0 || request.contentLength != metadata.getSize())) {
            respond(output, 400, "text/plain; charset=utf-8", context.getString(R.string.error_file_size_mismatch).getBytes(UTF8));
            return;
        }
        long expectedBytes = metadata.getSize();
        long copiedBytes = IoUtils.copy(input, output, expectedBytes, new IoUtils.ProgressListener() {
            @Override
            public void onBytes(long copied) throws IOException {}
        });

        if (copiedBytes != expectedBytes) {
            respond(output, 400, "text/plain; charset=utf-8", context.getString(R.string.error_file_size_mismatch).getBytes(UTF8));
            return;
        }
        File directory = StorageUtils.receiveDirectory(context);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException(context.getString(R.string.error_create_directory_failed));
        }
        final File target;
        synchronized (StorageUtils.class) {
            target = StorageUtils.uniqueFile(context, directory, metadata.getFileName());
            if (!target.createNewFile()) {
                throw new IOException(context.getString(R.string.error_reserve_file_failed));
            }
        }
        final File temporary = new File(directory, "." + target.getName() + "." + sessionId + ".part");
        try {
            FileOutputStream fileOutput = new FileOutputStream(temporary);
            try {
                final IncomingSession currentSession = session;
                IoUtils.copy(input, fileOutput, request.contentLength, new IoUtils.ProgressListener() {
                    @Override public void onBytes(long copied) throws IOException {
                        if (currentSession.getDecision() == IncomingSession.Decision.CANCELLED) {
                            throw new IOException(context.getString(R.string.error_reception_cancelled));
                        }
                        long overall = currentSession.updateFileProgress(metadata.getId(), copied);
                        listener.onReceiveProgress(currentSession, metadata.getFileName(),
                                IoUtils.percent(overall, currentSession.getTotalBytes()), target.getAbsolutePath());
                    }
                });
            } finally {
                fileOutput.close();
            }
            synchronized (StorageUtils.class) {
                if (!target.delete() || !temporary.renameTo(target)) {
                    throw new IOException(context.getString(R.string.error_save_file_failed));
                }
            }
            session.getReceivedBytes().addAndGet(metadata.getSize());
            Set<String> done = completedFiles.get(sessionId);
            if (done != null) done.add(fileId);
            respond(output, 200, "text/plain", new byte[0]);
            if (done != null && done.size() == session.getFiles().size()) {
                sessions.remove(sessionId);
                completedFiles.remove(sessionId);
                listener.onReceiveFinished(session, directory.getAbsolutePath());
            }
        } catch (Exception error) {
            if (temporary.exists()) temporary.delete();
            if (target.exists() && target.length() == 0) target.delete();
            listener.onReceiveFailed(session, readable(error));
            throw error;
        }
    }

    private static boolean certificateMatches(Socket socket, DeviceInfo device) {
        if (!(socket instanceof SSLSocket)) return true;
        String peer = TlsIdentity.peerFingerprint(((SSLSocket) socket).getSession());
        return peer == null || peer.equalsIgnoreCase(device.getFingerprint().replace(":", ""));
    }

    private static JSONObject cleanDeviceJson(DeviceInfo device) throws Exception {
        JSONObject json = device.toJson(false, false);
        json.remove("announce");
        return json;
    }

    private static JSONObject readJson(InputStream input, long length) throws Exception {
        if (length < 0 || length > 2L * 1024L * 1024L) throw new IOException("Invalid JSON request size");
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) (length > 0 ? length : 1024));
        IoUtils.copy(input, output, length, null);
        return new JSONObject(new String(output.toByteArray(), UTF8));
    }

    private static boolean sameAddress(InetAddress first, InetAddress second) {
        return first != null && first.equals(second);
    }

    private static void respondJson(OutputStream output, int status, JSONObject json) throws IOException {
        respond(output, status, "application/json; charset=utf-8", json.toString().getBytes(UTF8));
    }

    private static void respond(OutputStream output, int status, String contentType, byte[] body)
            throws IOException {
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 204: reason = "No Content"; break;
            case 400: reason = "Bad Request"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 409: reason = "Conflict"; break;
            default: reason = "Internal Server Error";
        }
        byte[] headers = ("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(UTF8);
        output.write(headers);
        output.write(body);
        output.flush();
    }

    private static String readable(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> query;
        final long contentLength;
        final boolean isChunked;

        Request(String method, String path, Map<String, String> query, long contentLength, boolean isChucked) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.contentLength = contentLength;
            this.isChunked = isChunked;
        }

        static Request read(InputStream input) throws Exception {
            String first = readLine(input);
            String[] parts = first.split(" ");
            if (parts.length < 2) throw new IOException("Invalid HTTP request line");
            Map<String, String> headers = new HashMap<String, String>();
            while (true) {
                String line = readLine(input);
                if (line.length() == 0) break;
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
            String rawTarget = parts[1];
            int question = rawTarget.indexOf('?');
            String path = question < 0 ? rawTarget : rawTarget.substring(0, question);
            Map<String, String> query = question < 0 ? new HashMap<String, String>()
                    : parseQuery(rawTarget.substring(question + 1));
            String transferEncoding = headers.get("transfer-encoding");
            boolean isChunked = transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked");
            long length = headers.containsKey("content-length")
                    ? Long.parseLong(headers.get("content-length")) : -1L;
            return new Request(parts[0], path, query, length);
        }

        private static Map<String, String> parseQuery(String value) throws Exception {
            Map<String, String> result = new HashMap<String, String>();
            for (String item : value.split("&")) {
                int equals = item.indexOf('=');
                if (equals >= 0) result.put(URLDecoder.decode(item.substring(0, equals), "UTF-8"),
                        URLDecoder.decode(item.substring(equals + 1), "UTF-8"));
            }
            return result;
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int previous = -1;
            while (output.size() <= 8192) {
                int current = input.read();
                if (current < 0) throw new IOException("HTTP request ended prematurely");
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = output.toByteArray();
                    return new String(bytes, 0, Math.max(0, bytes.length - 1), UTF8);
                }
                output.write(current);
                previous = current;
            }
            throw new IOException("HTTP header too long");
        }
    }
}
