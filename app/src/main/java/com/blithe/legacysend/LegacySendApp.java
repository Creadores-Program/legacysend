package com.blithe.legacysend;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.blithe.legacysend.discovery.DiscoveryManager;
import com.blithe.legacysend.model.DeviceInfo;
import com.blithe.legacysend.model.TransferFile;
import com.blithe.legacysend.security.TlsIdentity;
import com.blithe.legacysend.server.IncomingSession;
import com.blithe.legacysend.server.TransferServer;
import com.blithe.legacysend.transfer.TransferClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LegacySendApp extends Application implements DiscoveryManager.Listener,
        TransferServer.Listener {
    private static final String TAG = "LegacySend";
    private static final int NETWORK_WAIT_ATTEMPTS = 100;
    private static final long NETWORK_WAIT_DELAY_MS = 100L;

    public interface UiListener {
        void onReady(DeviceInfo self);
        void onServiceChanged(boolean running, String detail);
        void onDevicesChanged(List<DeviceInfo> devices);
        void onIncoming(IncomingSession session);
        void onTransferProgress(boolean sending, String title, String currentFile, int percent, String path);
        void onTransferResult(boolean sending, boolean success, String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newCachedThreadPool();
    private final Map<String, DeviceInfo> devices = new LinkedHashMap<String, DeviceInfo>();
    private volatile UiListener uiListener;
    private volatile DeviceInfo self;
    private volatile TlsIdentity identity;
    private volatile TransferServer server;
    private volatile DiscoveryManager discovery;
    private volatile TransferClient transferClient;
    private volatile boolean starting;
    private volatile IncomingSession activeIncoming;

    @Override public void onCreate() {
        super.onCreate();
        background.execute(new Runnable() {
            @Override public void run() {
                try {
                    identity = TlsIdentity.loadOrCreate(LegacySendApp.this);
                    String model = Build.MANUFACTURER + " " + Build.MODEL;
                    String defaultAlias = getString(R.string.default_device_alias);
                    String alias = (Build.MODEL == null || Build.MODEL.length() == 0) ? defaultAlias : Build.MODEL;
                    
                    String receiveProtocol = Build.VERSION.SDK_INT <= 20 ? "http" : "https";
                    
                    self = new DeviceInfo(alias, DeviceInfo.PROTOCOL_VERSION, model.trim(), "mobile",
                            identity.getFingerprint(), DiscoveryManager.PORT, receiveProtocol, false, null);
                    transferClient = new TransferClient(LegacySendApp.this, getContentResolver(), identity, self);
                    postReady();
                } catch (Exception error) {
                    postService(false, getString(R.string.error_init_cert) + readable(error));
                }
            }
        });
    }

    public void setUiListener(UiListener listener) {
        uiListener = listener;
        if (listener != null) {
            if (self != null) listener.onReady(self);
            boolean running = server != null && server.isRunning();
            String detail = running && self != null
                    ? getString(R.string.service_running, self.getProtocol().toUpperCase(java.util.Locale.US), self.getPort())
                    : getString(R.string.service_stopped);
            listener.onServiceChanged(running, detail);
            listener.onDevicesChanged(deviceSnapshot());
            if (activeIncoming != null && activeIncoming.getDecision() == IncomingSession.Decision.PENDING) {
                listener.onIncoming(activeIncoming);
            }
        }
    }

    public void startReceiving() {
        if (starting || (server != null && server.isRunning())) return;
        starting = true;
        postService(false, getString(R.string.service_starting));
        background.execute(new Runnable() {
            @Override public void run() {
                TransferServer newServer = null;
                DiscoveryManager newDiscovery = null;
                try {
                    int attempts = 0;
                    while ((identity == null || self == null) && attempts++ < 200) Thread.sleep(50L);
                    if (identity == null || self == null) throw new IllegalStateException(getString(R.string.error_identity_timeout));
                    waitForConnectedNetwork();
                    newServer = new TransferServer(LegacySendApp.this, identity, self, LegacySendApp.this);
                    newServer.start();
                    newDiscovery = new DiscoveryManager(LegacySendApp.this, self, LegacySendApp.this);
                    newDiscovery.start();
                    server = newServer;
                    discovery = newDiscovery;
                    startKeepAliveService();
                    postService(true, getString(R.string.service_running, self.getProtocol().toUpperCase(java.util.Locale.US), DiscoveryManager.PORT));
                } catch (Exception error) {
                    Log.e(TAG, "Failed to start receiving", error);
                    if (newDiscovery != null) newDiscovery.stop();
                    if (newServer != null) newServer.stop();
                    if (server == newServer) server = null;
                    if (discovery == newDiscovery) discovery = null;
                    postService(false, getString(R.string.error_start_failed) + readable(error));
                } finally {
                    starting = false;
                }
            }
        });
    }

    private void waitForConnectedNetwork() throws InterruptedException {
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return;
        
        for (int attempt = 0; attempt < NETWORK_WAIT_ATTEMPTS; attempt++) {
            NetworkInfo active = null;
            
            if (Build.VERSION.SDK_INT >= 14) {
                active = connectivity.getActiveNetworkInfo();
            } else {
                NetworkInfo[] info = connectivity.getAllNetworkInfo();
                if (info != null) {
                    for (NetworkInfo ni : info) {
                        if (ni != null && ni.getState() == NetworkInfo.State.CONNECTED) {
                            active = ni;
                            break;
                        }
                    }
                }
            }
            
            if (active != null && active.isConnected()) return;
            Thread.sleep(NETWORK_WAIT_DELAY_MS);
        }
        throw new IllegalStateException(getString(R.string.error_no_network));
    }

    public void stopReceiving() {
        DiscoveryManager currentDiscovery = discovery;
        TransferServer currentServer = server;
        discovery = null;
        server = null;
        if (currentDiscovery != null) currentDiscovery.stop();
        if (currentServer != null) currentServer.stop();
        stopService(new Intent(this, ReceiveService.class));
        postService(false, getString(R.string.service_stopped));
    }

    public void refreshDiscovery() {
        synchronized (devices) { devices.clear(); }
        postDevices();
        final DiscoveryManager current = discovery;
        if (current != null) {
            background.execute(new Runnable() {
                @Override public void run() { current.announce(); }
            });
        }
    }

    public void sendFiles(final DeviceInfo target, final List<TransferFile> files) {
        if (transferClient == null) {
            postResult(true, false, getString(R.string.error_not_initialized));
            return;
        }
        background.execute(new Runnable() {
            @Override public void run() {
                transferClient.send(target, files, new TransferClient.Listener() {
                    @Override public void onProgress(String file, int index, int count, int percent) {
                        String title = getString(R.string.sending_to, target.getAlias(), index, count);
                        postProgress(true, title, file, percent, "");
                    }
                    @Override public void onFinished(String message) { postResult(true, true, message); }
                    @Override public void onFailed(String message) { postResult(true, false, message); }
                });
            }
        });
    }

    public void cancelSending() {
        if (transferClient != null) transferClient.cancel();
    }

    public void decideIncoming(IncomingSession session, boolean accept) {
        if (session == null) return;
        if (accept) session.accept(); else session.reject();
        if (!accept && activeIncoming == session) activeIncoming = null;
    }

    public void cancelIncoming() {
        IncomingSession current = activeIncoming;
        if (current != null) {
            TransferServer currentServer = server;
            if (currentServer != null) currentServer.cancel(current);
            current.cancel();
        }
    }

    @Override public void onDevice(final DeviceInfo device, boolean announced) {
        addDevice(device);
        if (announced && transferClient != null) {
            background.execute(new Runnable() {
                @Override public void run() {
                    try { addDevice(transferClient.register(device)); } catch (Exception ignored) {}
                }
            });
        }
    }

    @Override public void onDiscoveryError(String message) { postResult(false, false, message); }

    @Override public void onRegistered(DeviceInfo device) { addDevice(device); }

    @Override public void onIncoming(final IncomingSession session) {
        activeIncoming = session;
        main.post(new Runnable() {
            @Override public void run() {
                UiListener listener = uiListener;
                if (listener != null) listener.onIncoming(session); else session.reject();
            }
        });
    }

    @Override public void onReceiveProgress(IncomingSession session, String fileName, int percent, String savePath) {
        String title = getString(R.string.receiving_from, session.getSender().getAlias());
        postProgress(false, title, fileName, percent, savePath);
    }

    @Override public void onReceiveFinished(IncomingSession session, String savePath) {
        if (activeIncoming == session) activeIncoming = null;
        postResult(false, true, getString(R.string.receive_finished, savePath));
    }

    @Override public void onReceiveFailed(IncomingSession session, String message) {
        if (session != null && activeIncoming == session) activeIncoming = null;
        postResult(false, false, message);
    }

    public DeviceInfo getSelf() { return self; }

    private void addDevice(DeviceInfo device) {
        if (device == null || device.getAddress() == null) return;
        synchronized (devices) { devices.put(device.key(), device); }
        postDevices();
    }

    private List<DeviceInfo> deviceSnapshot() {
        synchronized (devices) { return new ArrayList<DeviceInfo>(devices.values()); }
    }

    private void postReady() {
        main.post(new Runnable() {
            @Override public void run() {
                UiListener listener = uiListener;
                if (listener != null) listener.onReady(self);
            }
        });
    }

    private void postService(final boolean running, final String detail) {
        main.post(new Runnable() {
            @Override public void run() {
                UiListener listener = uiListener;
                if (listener != null) listener.onServiceChanged(running, detail);
            }
        });
    }

    private void postDevices() {
        final List<DeviceInfo> snapshot = deviceSnapshot();
        main.post(new Runnable() {
            @Override public void run() {
                UiListener listener = uiListener;
                if (listener != null) listener.onDevicesChanged(snapshot);
            }
        });
    }

    private void postProgress(final boolean sending, final String title, final String file,
                              final int percent, final String path) {
        main.post(new Runnable() {
            @Override public void run() {
                UiListener listener = uiListener;
                if (listener != null) listener.onTransferProgress(sending, title, file, percent, path);
            }
        });
    }

    private void postResult(final boolean sending, final boolean success, final String message) {
        main.post(new Runnable() {
            @Override public void run() {
                UiListener listener = uiListener;
                if (listener != null) listener.onTransferResult(sending, success, message);
            }
        });
    }

    private void startKeepAliveService() {
        Intent intent = new Intent(this, ReceiveService.class);
        startService(intent);
    }

    private static String readable(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
