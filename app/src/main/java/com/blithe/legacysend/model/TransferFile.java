package com.blithe.legacysend.model;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

public final class TransferFile {
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private final String id;
    private final String fileName;
    private final long size;
    private final String fileType;
    private final Uri uri;

    public TransferFile(String id, String fileName, long size, String fileType, Uri uri) {
        this.id = id;
        this.fileName = fileName;
        this.size = size;
        this.fileType = (fileType == null || fileType.length() == 0) ? DEFAULT_MIME_TYPE : fileType;
        this.uri = uri;
    }

    public static TransferFile fromJson(JSONObject json) throws JSONException {
        if (json == null) {
            throw new JSONException("JSONObject cannot be null");
        }
        
        String id = json.getString("id");
        String fileName = json.getString("fileName");
        long size = json.getLong("size");
        String fileType = json.optString("fileType", DEFAULT_MIME_TYPE);

        return new TransferFile(id, fileName, size, fileType, null);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("fileName", fileName);
        json.put("size", size);
        json.put("fileType", fileType);
        return json;
    }

    public String getId() { return id; }
    public String getFileName() { return fileName; }
    public long getSize() { return size; }
    public String getFileType() { return fileType; }
    public Uri getUri() { return uri; }

    @Override
    public String toString() {
        return "TransferFile{" +
                "id='" + id + '\'' +
                ", fileName='" + fileName + '\'' +
                ", size=" + size +
                ", fileType='" + fileType + '\'' +
                '}';
    }
}
