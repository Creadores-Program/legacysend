package com.blithe.legacysend.storage;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import com.blithe.legacysend.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

public final class ReceiveDirectory {
    private final Context context;
    private final File fileDirectory;
    private final Uri treeUri;
    private final String displayPath;

    public static ReceiveDirectory forFile(Context context, File directory) {
        return new ReceiveDirectory(context, directory, null, directory.getAbsolutePath());
    }

    public static ReceiveDirectory forTree(Context context, Uri treeUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw new IllegalStateException(context.getString(R.string.error_saf_not_supported));
        }
        String name = queryName(context.getContentResolver(), documentUri(treeUri));
        String label = name == null 
                ? context.getString(R.string.saf_default_folder_name) 
                : name + context.getString(R.string.saf_folder_suffix);
        return new ReceiveDirectory(context, null, treeUri, label);
    }

    private ReceiveDirectory(Context context, File fileDirectory, Uri treeUri, String displayPath) {
        this.context = context.getApplicationContext();
        this.fileDirectory = fileDirectory;
        this.treeUri = treeUri;
        this.displayPath = displayPath;
    }

    public String getDisplayPath() {
        return displayPath;
    }

    public PendingFile createFile(String requestedName, String mimeType, String sessionId)
            throws IOException {
        if (fileDirectory != null) return createRegularFile(requestedName, sessionId);
        return createDocumentFile(requestedName, mimeType);
    }

    private PendingFile createRegularFile(String requestedName, String sessionId) throws IOException {
        synchronized (StorageUtils.class) {
            if (!fileDirectory.exists() && !fileDirectory.mkdirs()) {
                throw new IOException(context.getString(R.string.error_create_directory_failed));
            }
            File target = StorageUtils.uniqueFile(context, fileDirectory, requestedName);
            if (!target.createNewFile()) {
                throw new IOException(context.getString(R.string.error_reserve_file_failed));
            }
            File temporary = new File(fileDirectory,
                    "." + target.getName() + "." + sessionId + ".part");
            return new PendingFile(context, target, temporary);
        }
    }

    private PendingFile createDocumentFile(String requestedName, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw new IOException(context.getString(R.string.error_saf_not_supported));
        }
        
        ContentResolver resolver = context.getContentResolver();
        synchronized (StorageUtils.class) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));
            Set<String> existingNames = new HashSet<String>();
            Cursor cursor = resolver.query(children,
                    new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                    null, null, null);
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        if (!cursor.isNull(0)) existingNames.add(cursor.getString(0));
                    }
                } finally {
                    cursor.close();
                }
            }
            String name = StorageUtils.uniqueName(context, existingNames, requestedName);
            String safeMime = (mimeType == null || mimeType.length() == 0)
                    ? "application/octet-stream" : mimeType;
            
            Uri document = DocumentsContract.createDocument(resolver, documentUri(treeUri),
                    safeMime, name);
            if (document == null) {
                throw new IOException(context.getString(R.string.error_create_directory_failed));
            }
            String actualName = queryName(resolver, document);
            return new PendingFile(context, resolver, document, displayPath + "/"
                    + (actualName == null ? name : actualName));
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static Uri documentUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri));
    }

    private static String queryName(ContentResolver resolver, Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri,
                    new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                    null, null, null);
            return cursor != null && cursor.moveToFirst() && !cursor.isNull(0)
                    ? cursor.getString(0) : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    public static final class PendingFile {
        private final Context context;
        private final File target;
        private final File temporary;
        private final ContentResolver resolver;
        private final Uri document;
        private final String displayPath;
        private boolean committed;

        private PendingFile(Context context, File target, File temporary) {
            this.context = context;
            this.target = target;
            this.temporary = temporary;
            this.resolver = null;
            this.document = null;
            this.displayPath = target.getAbsolutePath();
        }

        private PendingFile(Context context, ContentResolver resolver, Uri document, String displayPath) {
            this.context = context;
            this.target = null;
            this.temporary = null;
            this.resolver = resolver;
            this.document = document;
            this.displayPath = displayPath;
        }

        public OutputStream openOutputStream() throws IOException {
            if (temporary != null) return new FileOutputStream(temporary);
            if (resolver != null && document != null) {
                OutputStream output = resolver.openOutputStream(document, "w");
                if (output == null) {
                    throw new IOException(context.getString(R.string.error_create_directory_failed));
                }
                return output;
            }
            throw new IOException(context.getString(R.string.error_create_directory_failed));
        }

        public String getDisplayPath() {
            return displayPath;
        }

        public void commit() throws IOException {
            if (temporary != null) {
                synchronized (StorageUtils.class) {
                    if (!target.delete() || !temporary.renameTo(target)) {
                        throw new IOException(context.getString(R.string.error_save_file_failed));
                    }
                }
            }
            committed = true;
        }

        public void discard() {
            if (committed) return;
            if (temporary != null && temporary.exists()) temporary.delete();
            if (target != null && target.exists() && target.length() == 0) target.delete();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && document != null && resolver != null) {
                try {
                    DocumentsContract.deleteDocument(resolver, document);
                } catch (Exception ignored) {}
            }
        }
    }
}
