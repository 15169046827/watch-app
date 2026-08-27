package com.canace.x3voiceledger;

import com.canace.x3voiceledger.protocol.SyncProtocol;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

final class LedgerPayloadCodec {
    static byte[] ledger(LedgerEntry entry) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("type", SyncProtocol.TYPE_LEDGER);
        object.put("schema", SyncProtocol.SCHEMA_VERSION);
        object.put("id", entry.id);
        object.put("createdAt", entry.createdAt);
        object.put("updatedAt", entry.updatedAt);
        object.put("transactionTime", entry.transactionTime);
        object.put("amountCents", entry.amountCents);
        object.put("note", entry.note);
        object.put("category", entry.category);
        object.put("categoryParent", entry.categoryParent);
        object.put("categoryChild", entry.categoryChild);
        object.put("account", entry.account);
        object.put("bookName", entry.bookName);
        object.put("tags", entry.tags);
        object.put("rawText", entry.rawText);
        object.put("source", entry.source);
        object.put("locationLabel", entry.locationLabel);
        object.put("latitude", entry.latitude == null ? JSONObject.NULL : entry.latitude);
        object.put("longitude", entry.longitude == null ? JSONObject.NULL : entry.longitude);
        object.put("locationAccuracy", entry.locationAccuracy == null ? JSONObject.NULL : entry.locationAccuracy);
        object.put("locationTime", entry.locationTime == null ? JSONObject.NULL : entry.locationTime);
        return object.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] empty() {
        return ("{\"type\":\"" + SyncProtocol.TYPE_EMPTY + "\",\"schema\":"
                + SyncProtocol.SCHEMA_VERSION + "}").getBytes(StandardCharsets.UTF_8);
    }

    private LedgerPayloadCodec() { }
}
