package com.blithe.legacysend.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.blithe.legacysend.R;
import com.blithe.legacysend.model.TransferFile;

import java.io.File;
import java.util.Locale;
import java.util.UUID;

public final class StorageUtils {
    private StorageUtils() {}

    public static TransferFile describe(Context context, ContentResolver resolver, Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return describe(context, new File(uri.getPath()), uri);
        }

        String defaultName = context != null ? context.getString(R.string.unnamed_file) : "Unnamed_File";
        String name = defaultName;
        long size = -1L;
        Cursor cursor = resolver.query(uri, new String[] {
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
        }, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
                }
            } finally {
                cursor.close();
            }
        }
        if (size < 0) {
            try {
                AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r");
                if (descriptor != null) {
                    size = descriptor.getLength();
                    descriptor.close();
                }
            } catch (Exception ignored) {}
        }
        String type = resolver.getType(uri);
        return new TransferFile(UUID.randomUUID().toString(), sanitizeFileName(context, name),
                Math.max(0L, size), type, uri);
    }

    public static TransferFile describe(Context context, File file) {
        return describe(context, file, Uri.fromFile(file));
    }

    private static TransferFile describe(Context context, File file, Uri uri) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString());
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                extension == null ? "" : extension.toLowerCase(Locale.US));
        return new TransferFile(UUID.randomUUID().toString(), sanitizeFileName(context, file.getName()),
                file.length(), type, uri);
    }

    public static File receiveDirectory(Context context) {
        File downloads;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (downloads == null) downloads = context.getFilesDir();
        } else {
            downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        }
        return new File(downloads, "LegacySend");
    }

    public static File uniqueFile(Context context, File directory, String requestedName) {
        String safe = sanitizeFileName(context, requestedName);
        File candidate = new File(directory, safe);
        if (!candidate.exists()) return candidate;
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    public static String sanitizeFileName(Context context, String name) {
        String defaultName = context != null ? context.getString(R.string.unnamed_file) : "Unnamed_File";
        if (name == null || name.trim().length() == 0) return defaultName;
        String safe = name.replace('/', '_').replace('\\', '_').replace('\u0000', '_');
        while (safe.startsWith(".")) safe = safe.substring(1);
        if (safe.length() == 0) return defaultName;
        return safe;
    }
}
