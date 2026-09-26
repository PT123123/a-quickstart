package com.quickstart.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 图标避让区域的存取：背景图片上由用户划定的若干「圆形 / 方形」区域（归一化坐标）。
 *
 * 坐标约定（全部相对背景显示区，即主界面 bg_container 的可见范围）：
 *  - 方形：x/y = 左上角，w/h = 宽高（x、w 以宽度为基准，y、h 以高度为基准，取值 0~1）；
 *  - 圆形：x/y = 圆心，w = 半径（以宽度为基准），h 不用。
 *
 * 存储为 "settings" 里的 JSON 字符串（key 见 {@link #PREF_KEY}），
 * ConfigTransfer 全量导出/导入会自动带上，无需单独处理。
 */
public final class ZoneStore {

    public static final String PREF_KEY = "bg_avoid_zones";

    /** 区域数量上限（防止无限添加；用户约定最多 5 个） */
    public static final int MAX_ZONES = 5;

    /** 单个避让区域 */
    public static final class Zone {
        public static final int TYPE_CIRCLE = 0;
        public static final int TYPE_RECT = 1;

        public int type;
        public float x, y, w, h;

        public Zone() {
        }

        public Zone(int type, float x, float y, float w, float h) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public Zone copy() {
            return new Zone(type, x, y, w, h);
        }
    }

    private ZoneStore() {
    }

    /** 读取全部避让区域；无数据或解析失败返回空列表 */
    public static List<Zone> load(Context ctx) {
        List<Zone> out = new ArrayList<>();
        if (ctx == null) return out;
        SharedPreferences sp = ctx.getSharedPreferences(BackgroundManager.PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(PREF_KEY, "");
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length() && out.size() < MAX_ZONES; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int type = "c".equals(o.optString("t")) ? Zone.TYPE_CIRCLE : Zone.TYPE_RECT;
                float x = (float) o.optDouble("x", Double.NaN);
                float y = (float) o.optDouble("y", Double.NaN);
                float w = (float) o.optDouble("w", Double.NaN);
                float h = (float) o.optDouble("h", 0);
                if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(w)) continue;
                out.add(new Zone(type, clamp01(x), clamp01(y), clamp01(w), clamp01(h)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 保存全部避让区域（空列表 = 清除） */
    public static void save(Context ctx, List<Zone> zones) {
        SharedPreferences sp = ctx.getSharedPreferences(BackgroundManager.PREFS, Context.MODE_PRIVATE);
        if (zones == null || zones.isEmpty()) {
            sp.edit().remove(PREF_KEY).apply();
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (Zone z : zones) {
                JSONObject o = new JSONObject();
                o.put("t", z.type == Zone.TYPE_CIRCLE ? "c" : "r");
                o.put("x", round(z.x));
                o.put("y", round(z.y));
                o.put("w", round(z.w));
                o.put("h", round(z.h));
                arr.put(o);
            }
            sp.edit().putString(PREF_KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 清除避让区域（清除图片背景时联动调用） */
    public static void clear(Context ctx) {
        ctx.getSharedPreferences(BackgroundManager.PREFS, Context.MODE_PRIVATE)
                .edit().remove(PREF_KEY).apply();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static double round(float v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
