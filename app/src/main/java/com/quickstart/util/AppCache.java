package com.quickstart.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.quickstart.model.AppEntry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用列表磁盘缓存。
 * 把可序列化的元数据（label / packageName / activityName / fingerprints / recentlyUpdated）
 * 用 JSON 存进 SharedPreferences，启动时先读缓存瞬时出图，再在后台做全量扫描刷新。
 *
 * 注意：icon 与 launchIntent 不入库（不可序列化），缓存命中后用占位图标 +
 * getLaunchIntentForPackage 轻量补齐，全量扫描后再替换为真实图标。
 */
public final class AppCache {

    private static final String PREFS_NAME = "app_cache";
    private static final String KEY_APPS = "apps_json";
    private static final String KEY_TIMESTAMP = "cache_timestamp";

    private AppCache() {}

    /** 是否存在有效缓存 */
    public static boolean hasCache(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_APPS);
    }

    /** 缓存写入时间（毫秒），0 表示无缓存 */
    public static long getTimestamp(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_TIMESTAMP, 0);
    }

    /** 读取缓存：返回无 icon / 无 launchIntent 的轻量条目列表 */
    public static List<AppEntry> load(Context ctx) {
        List<AppEntry> out = new ArrayList<>();
        String json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APPS, null);
        if (json == null || json.isEmpty()) return out;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String label = o.optString("label", "");
                String pkg   = o.optString("pkg", "");
                String act   = o.optString("act", "");

                JSONArray fpArr = o.optJSONArray("fp");
                List<String> fp = new ArrayList<>();
                if (fpArr != null) {
                    for (int j = 0; j < fpArr.length(); j++) fp.add(fpArr.getString(j));
                }

                AppEntry e = new AppEntry(label, pkg, act, fp);
                e.recentlyUpdated = o.optBoolean("recent", false);
                out.add(e);
            }
        } catch (JSONException ignored) {
            // 解析失败直接返回空，触发全量扫描
        }
        return out;
    }

    /** 把全量扫描结果写入缓存（仅持久化可序列化字段） */
    public static void save(Context ctx, List<AppEntry> apps) {
        JSONArray arr = new JSONArray();
        for (AppEntry e : apps) {
            try {
                JSONObject o = new JSONObject();
                o.put("label", e.label == null ? "" : e.label);
                o.put("pkg", e.packageName == null ? "" : e.packageName);
                o.put("act", e.activityName == null ? "" : e.activityName);

                JSONArray fp = new JSONArray();
                if (e.fingerprints != null) {
                    for (String s : e.fingerprints) fp.put(s == null ? "" : s);
                }
                o.put("fp", fp);
                o.put("recent", e.recentlyUpdated);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_APPS, arr.toString())
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply();
    }
}
