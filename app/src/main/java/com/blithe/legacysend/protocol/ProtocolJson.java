package com.blithe.legacysend.protocol;

import com.blithe.legacysend.model.DeviceInfo;
import com.blithe.legacysend.model.TransferFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ProtocolJson {

    private ProtocolJson() {}

    public static JSONObject prepareUpload(DeviceInfo self, List<TransferFile> files) throws JSONException {
        if (self == null || files == null) {
            throw new JSONException("DeviceInfo and files list cannot be null");
        }

        JSONObject root = new JSONObject();
        JSONObject info = self.toJson(false, true);
        info.remove("announce");
        root.put("info", info);

        JSONObject fileObject = new JSONObject();
        for (TransferFile file : files) {
            if (file != null) {
                fileObject.put(file.getId(), file.toJson());
            }
        }
        root.put("files", fileObject);
        return root;
    }

    @SuppressWarnings("unchecked")
    public static List<TransferFile> parseFiles(JSONObject object) throws JSONException {
        List<TransferFile> result = new ArrayList<TransferFile>();
        if (object == null) {
            return result;
        }

        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject fileJson = object.getJSONObject(key);
            result.add(TransferFile.fromJson(fileJson));
        }
        return result;
    }

    public static JSONObject prepareResponse(String sessionId, Map<String, String> tokens)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("sessionId", sessionId == null ? "" : sessionId);

        JSONObject files = new JSONObject();
        if (tokens != null) {
            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    files.put(entry.getKey(), entry.getValue());
                }
            }
        }
        root.put("files", files);
        return root;
    }
}
