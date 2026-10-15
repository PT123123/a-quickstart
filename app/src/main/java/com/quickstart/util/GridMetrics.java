package com.quickstart.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用网格尺寸计算：列格子高度、图标边长、名称字号。
 *
 * 为什么单独抽出来：
 *  这套公式被三处独立使用，彼此必须完全一致，否则会出现
 *  「图标被格子裁掉」「避让区域对不上」「编辑器的预览和主界面不一样」：
 *   1. {@link com.quickstart.adapter.AppListAdapter#updateItemSquareSize} —— 设置 item 的实际尺寸；
 *   2. {@link AvoidGridLayoutManager#onLayoutChildren} —— 决定每行占多高（EXACTLY 测量，会裁内容）；
 *   3. {@link com.quickstart.ZoneEditorActivity} 的画布预览 —— 让用户看到保存后的真实排布。
 *  任何一处单独改公式都会让另外两处对不上，所以统一放这里。
 *
 * 尺寸来源（cellW = 可用宽度 / 列数）：
 *  - 图标边长 = cellW * ICON_RATIO *缩放系数
 *  - 格子高度 = cellW * CELL_HEIGHT_RATIO * 缩放系数，并保证不小于「图标 + 名称」所需高度，
 *    否则名称会被EXACTLY 测量裁掉。
 */
public final class GridMetrics {

    /** 设置项 key：格子整体缩放百分比（50~150，100 = 原始大小） */
    public static final String PREF_CELL_SCALE = "cell_scale";
    /** 未设置时的默认值，与旧版本表现一致 */
    public static final int DEFAULT_CELL_SCALE = 100;
    public static final int MIN_CELL_SCALE = 50;
    public static final int MAX_CELL_SCALE = 150;

    /** 图标边长占格宽的比例 */
    public static final float ICON_RATIO = 0.65f;
    /** 格子高度占格宽的比例 */
    public static final float CELL_HEIGHT_RATIO = 1.3f;

    /** item_app.xml 里纵向内边距 6dp * 2，加名称上方间距 4dp */
    private static final float TEXT_RESERVE_DP = 16f;
    /** 名称行高相对字号的倍数（sp 行高约 1.2，留一点余量） */
    private static final float TEXT_LINE_FACTOR = 1.25f;

    private GridMetrics() {
    }

    /** 读取格子缩放百分比并夹到合法区间 */
    public static int readCellScale(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        int v = sp.getInt(PREF_CELL_SCALE, DEFAULT_CELL_SCALE);
        return Math.max(MIN_CELL_SCALE, Math.min(MAX_CELL_SCALE, v));
    }

    /** 百分比转缩放系数（100 → 1.0f） */
    public static float scaleFactor(int percent) {
        return percent / 100f;
    }

    /** 图标边长（像素）。cellW <= 0 时返回 0，调用方据此跳过设置。 */
    public static int iconSizePx(int cellWidthPx, int scalePercent) {
        if (cellWidthPx <= 0) return 0;
        return Math.max(1, Math.round(cellWidthPx * ICON_RATIO * scaleFactor(scalePercent)));
    }

    /**
     * 格子高度（像素），unitScale = 1（真实屏幕坐标）。
     *
     * 除了按比例缩放，还要给「图标 + 名称」留够空间：LayoutManager 用
     * EXACTLY 高度测量 item，内容超出就会被裁。缩到 50% 时按比例算出的高度
     * 装不下固定高度的名称，所以这里取二者较大值。
     */
    public static int cellHeightPx(int cellWidthPx, int scalePercent,
                                   float density, float scaledDensity, int columns) {
        return cellHeightPx(cellWidthPx, scalePercent, density, scaledDensity, columns, 1f);
    }

    /**
     * 格子高度（像素）。
     *
     * @param unitScale 真实像素 → 目标坐标的换算系数。
     *   真实屏幕传 1f；避让区编辑器的预览画布被previewScale 缩小过，
     *   那里传 1/previewScale，否则密度相关的留白会被放大而与主界面不符。
     */
    public static int cellHeightPx(int cellWidthPx, int scalePercent,
                                   float density, float scaledDensity, int columns,
                                   float unitScale) {
        if (cellWidthPx <= 0) return 0;
        int icon = iconSizePx(cellWidthPx, scalePercent);
        int scaled = Math.max(1, Math.round(cellWidthPx * CELL_HEIGHT_RATIO * scaleFactor(scalePercent)));
        float reserve = TEXT_RESERVE_DP * density
                + textSizeSp(columns) * TEXT_LINE_FACTOR * scaledDensity;
        int need = Math.round(icon + reserve * unitScale);
        return Math.max(scaled, need);
    }

    /** 名称字号（sp）：随列数递减，列数越多格子越窄，字要跟着小才放得下 */
    public static float textSizeSp(int columns) {
        switch (columns) {
            case 2: return 14f;
            case 3: return 12f;
            case 4: return 11f;
            case 5: return 10f;
            default: return 12f;
        }
    }
}