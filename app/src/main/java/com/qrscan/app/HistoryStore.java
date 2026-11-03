package com.qrscan.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 扫码历史，落在 SharedPreferences 里，最多保留 80 条。 */
public class HistoryStore {

    private static final String PREF = "qr_history";
    private static final String KEY = "items";
    private static final int MAX = 80;

    public static class Item {
        public final String text;
        public final String label;
        public final long time;

        Item(String text, String label, long time) {
            this.text = text;
            this.label = label;
            this.time = time;
        }
    }

    private final SharedPreferences sp;

    public HistoryStore(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Item> list() {
        List<Item> out = new ArrayList<Item>();
        String raw = sp.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                out.add(new Item(o.optString("t", ""), o.optString("l", ""), o.optLong("ts", 0L)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public void add(String text, String label) {
        if (text == null || text.length() == 0) {
            return;
        }
        List<Item> items = list();
        for (int i = 0; i < items.size(); i++) {
            if (text.equals(items.get(i).text)) {
                items.remove(i);
                break;
            }
        }
        items.add(0, new Item(text, label, System.currentTimeMillis()));
        while (items.size() > MAX) {
            items.remove(items.size() - 1);
        }
        persist(items);
    }

    public void clear() {
        sp.edit().remove(KEY).apply();
    }

    private void persist(List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", it.text);
                o.put("l", it.label);
                o.put("ts", it.time);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }
}
