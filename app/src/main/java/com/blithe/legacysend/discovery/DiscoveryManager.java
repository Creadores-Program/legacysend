package com.blithe.legacysend.discovery;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.blithe.legacysend.R;
import com.blithe.legacysend.model.DeviceInfo;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscoveryManager {
    public interface Listener {
        void onDevice(DeviceInfo device, boolean announced);
        void onDiscoveryError(String message);
    }

    public static final int PORT = 53317;
    public static final String GROUP = "224.0.0.167";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Context context;
    private final DeviceInfo self;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private MulticastSocket socket;
    private WifiManager.MulticastLock multicastLock;
    private Thread listenThread;

    public DiscoveryManager(Context context, DeviceInfo self, Listener listener) {
        this.context = context.getApplicationContext();
        this.self = self;
        this.listener = listener;
    }

    public synchronized void start() throws Exception {
        if (running.get()) return;
        MulticastSocket newSocket = null;
        WifiManager.MulticastLock newMulticastLock = null;
        boolean joined = false;
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                newMulticastLock = wifi.createMulticastLock("legacysend-discovery");
                newMulticastLock.setReferenceCounted(false);
                newMulticastLock.acquire();
            }
            
            newSocket = new MulticastSocket(null);
            newSocket.setReuseAddress(true);
            newSocket.bind(new InetSocketAddress(PORT));
            newSocket.setSoTimeout(1500);
            newSocket.joinGroup(InetAddress.getByName(GROUP));
            joined = true;
            
            socket = newSocket;
            multicastLock = newMulticastLock;
            running.set(true);
            
            listenThread = new Thread(new Runnable() {
                @Override public void run() { listenLoop(); }
            }, "LegacySend-discovery");
            listenThread.start();
            announce();
        } catch (Exception error) {
            running.set(false);
            socket = null;
            listenThread = null;
            if (newSocket != null) {
                if (joined) {
                    try { newSocket.leaveGroup(safeGroup()); } catch (Exception ignored) {}
                }
                newSocket.close();
            }
            if (newMulticastLock != null && newMulticastLock.isHeld()) {
                newMulticastLock.release();
            }
            multicastLock = null;
            throw error;
        }
    }

    public void announce() {
        send(self, true);
    }

    public synchronized void stop() {
        running.set(false);
        MulticastSocket current = socket;
        socket = null;
        if (current != null) {
            try { current.leaveGroup(safeGroup()); } catch (Exception ignored) {}
            current.close();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
    }

    private void listenLoop() {
        byte[] buffer = new byte[64 * 1024];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                MulticastSocket current = socket;
                if (current == null) break;
                current.receive(packet);
                
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), UTF8);
                JSONObject json = new JSONObject(text);
                DeviceInfo device = DeviceInfo.fromJson(json, packet.getAddress());
                
                if (self.getFingerprint().equals(device.getFingerprint())) continue;
                
                boolean announce = json.optBoolean("announce", false);
                listener.onDevice(device, announce);
                if (announce) send(self, false);
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception error) {
                if (running.get()) {
                    String msg = context.getString(R.string.error_discovery_failed, readable(error));
                    listener.onDiscoveryError(msg);
                }
            }
        }
    }

    private void send(DeviceInfo device, boolean announce) {
        try {
            byte[] data = device.toJson(announce, true).toString().getBytes(UTF8);
            DatagramPacket packet = new DatagramPacket(data, data.length, safeGroup(), PORT);
            MulticastSocket current = socket;
            if (current != null && running.get()) {
                current.send(packet);
            }
        } catch (Exception error) {
            if (running.get()) {
                String msg = context.getString(R.string.error_announce_failed, readable(error));
                listener.onDiscoveryError(msg);
            }
        }
    }

    private static InetAddress safeGroup() throws java.net.UnknownHostException {
        return InetAddress.getByName(GROUP);
    }

    private static String readable(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
