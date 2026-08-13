package com.blithe.legacysend.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class IoUtils {

    public interface ProgressListener {
        void onBytes(long copied) throws IOException;
    }

    private IoUtils() {}

    public static long copy(InputStream input, OutputStream output, long expected,
                            ProgressListener listener) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long copied = 0L;
        while (expected < 0 || copied < expected) {
            int wanted = buffer.length;
            if (expected >= 0) {
                wanted = (int) Math.min((long) wanted, expected - copied);
            }
            if (wanted == 0) {
                break;
            }
            int read = input.read(buffer, 0, wanted);
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            copied += read;
            if (listener != null) {
                listener.onBytes(copied);
            }
        }
        output.flush();
        if (expected >= 0 && copied != expected) {
            throw new IOException(String.format(Locale.US,
                    "Premature stream end: expected %d bytes, read %d bytes", expected, copied));
        }
        return copied;
    }

    public static int percent(long completed, long total) {
        if (total <= 0) {
            return completed > 0 ? 100 : 0;
        }
        return (int) Math.min(100L, (completed * 100L) / total);
    }
}
