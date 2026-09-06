package com.quickstart.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * T9 搜索历史：记录「搜索词 → 启动的应用」。
 * JSON 数组存进 SharedPreferences，最新在前，按 包名+Activity 去重（重复搜索移到最前）。
 * 供「最近搜索」chip 展示最近搜到并启动过的应用。
 */
public final class SearchHistory {

    private static final String PREFS_NAME = "search_history";
    private static final String KEY_HISTORY = "history_json";
    /** 最多保留的记录条数 */
    private static final int MAX_ENTRIES = 20;

    /** 一条搜索历史 */
    public static final class Entry {
        public final String query;          // 搜索用的 T9 数字串，如 "99"
        public final String packageName;    // 命中的应用包名
        public final String activityName;   // 命中的 Activity（可能与包名同条目多入口）
        public final long time;             // 记录时间戳（毫秒）

        Entry(String query, String packageName, String activityName, long time) {
            this.query = query;
            this.packageName = packageName;
            this.activityName = activityName;
            this.time = time;
        }
    }

    private SearchHistory() {}

    /** 记录一次「搜索后启动」；同一应用移到最前，超出上限裁剪尾部 */
    public static void record(Context ctx, String query, String packageName, String activityName) {
        if (query == null || query.isEmpty()
                || packageName == null || packageName.isEmpty()) {
            return;
        }
        List<Entry> list = getAll(ctx);
        List<Entry> out = new ArrayList<>(MAX_ENTRIES + 1);
        out.add(new Entry(query, packageName, activityName == null ? "" : activityName,
                System.currentTimeMillis()));
        for (Entry e : list) {
            if (out.size() >= MAX_ENTRIES) break;
            // 跳过被去重的同应用同入口记录
            if (e.packageName.equals(packageName) && e.activityName.equals(activityName)) continue;
            out.add(e);
        }
        save(ctx, out);
    }

    /** 读取全部历史，最新在前；解析失败返回空列表 */
    public static List<Entry> getAll(Context ctx) {
        List<Entry> out = new ArrayList<>();
        String json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_HISTORY, null);
        if (json == null || json.isEmpty()) return out;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optString("q", ""),
                        o.optString("pkg", ""),
                        o.optString("act", ""),
                        o.optLong("t", 0L)));
            }
        } catch (Exception ignored) {
            // 坏数据按无历史处理
        }
        return out;
    }

    /** 清空全部历史 */
    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_HISTORY).apply();
    }

    private static void save(Context ctx, List<Entry> list) {
        JSONArray arr = new JSONArray();
        for (Entry e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("q", e.query);
                o.put("pkg", e.packageName);
                o.put("act", e.activityName);
                o.put("t", e.time);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_HISTORY, arr.toString()).apply();
    }
}
