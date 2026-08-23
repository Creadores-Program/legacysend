package com.blithe.legacysend.server;

import com.blithe.legacysend.model.DeviceInfo;
import com.blithe.legacysend.model.TransferFile;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class IncomingSession {
    public enum Decision { PENDING, ACCEPTED, REJECTED, CANCELLED }

    private final String sessionId;
    private final DeviceInfo sender;
    private final InetAddress senderAddress;
    private final List<TransferFile> files;
    private final Map<String, String> tokens;
    private final Map<String, Long> fileProgress;
    private final CountDownLatch decisionLatch;
    private final AtomicLong receivedBytes;
    private volatile Decision decision;

    public IncomingSession(DeviceInfo sender, InetAddress senderAddress, List<TransferFile> files) {
        this.sessionId = UUID.randomUUID().toString();
        this.sender = sender;
        this.senderAddress = senderAddress;
        
        List<TransferFile> safeFiles = (files != null) ? new ArrayList<TransferFile>(files) : new ArrayList<TransferFile>();
        this.files = Collections.unmodifiableList(safeFiles);
        
        this.tokens = new LinkedHashMap<String, String>();
        this.fileProgress = new LinkedHashMap<String, Long>();
        this.decisionLatch = new CountDownLatch(1);
        this.receivedBytes = new AtomicLong(0L);
        this.decision = Decision.PENDING;

        for (TransferFile file : safeFiles) {
            if (file != null && file.getId() != null) {
                this.tokens.put(file.getId(), UUID.randomUUID().toString());
                this.fileProgress.put(file.getId(), 0L);
            }
        }
    }

    public synchronized void accept() {
        if (decision == Decision.PENDING) {
            decision = Decision.ACCEPTED;
            decisionLatch.countDown();
        }
    }

    public synchronized void reject() {
        if (decision == Decision.PENDING) {
            decision = Decision.REJECTED;
            decisionLatch.countDown();
        }
    }

    public synchronized void cancel() {
        if (decision == Decision.PENDING) {
            decision = Decision.CANCELLED;
            decisionLatch.countDown();
        }
    }

    public Decision awaitDecision(long timeout, TimeUnit unit) throws InterruptedException {
        if (!decisionLatch.await(timeout, unit)) {
            reject();
        }
        return decision;
    }

    public TransferFile findFile(String id) {
        if (id == null) return null;
        for (TransferFile file : files) {
            if (id.equals(file.getId())) {
                return file;
            }
        }
        return null;
    }

    public long getTotalBytes() {
        long total = 0L;
        for (TransferFile file : files) {
            if (file != null) {
                total += file.getSize();
            }
        }
        return total;
    }

    public synchronized long updateFileProgress(String fileId, long bytes) {
        if (fileId != null && fileProgress.containsKey(fileId)) {
            fileProgress.put(fileId, bytes);
        }
        
        long total = 0L;
        for (Long value : fileProgress.values()) {
            if (value != null) {
                total += value;
            }
        }
        receivedBytes.set(total);
        return total;
    }

    public String getSessionId() { return sessionId; }
    public DeviceInfo getSender() { return sender; }
    public InetAddress getSenderAddress() { return senderAddress; }
    public List<TransferFile> getFiles() { return files; }
    public Map<String, String> getTokens() { return Collections.unmodifiableMap(tokens); }
    public String getToken(String fileId) { return fileId == null ? null : tokens.get(fileId); }
    public Decision getDecision() { return decision; }
    public AtomicLong getReceivedBytes() { return receivedBytes; }
}
