package com.quickstart.util;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 背景亮度采样：把当前背景图压成一张很小的亮度网格，
 * 主界面按每个图标/文字在屏幕上的位置查亮度，据此决定「黑字还是白字」、底衬用深色还是浅色。
 *
 * 设计要点：
 *  1. 网格只在背景图解码时生成一次（64 行内，几千次采样），滚动时只是数组取值，零开销；
 *  2. 亮度用 WCAG 相对亮度（线性化后的 0.2126R+0.7152G+0.0722B），
 *     黑白文字的换色阈值取 0.179（WCAG 中黑/白对比度相等的点）；
 *  3. 没有图片背景时退回纯色亮度；纯色是透明（跟随主题）时由调用方按深/浅色主题估一个值。
 */
public final class Contrast {

    /** 网格纵向格数；横向按图片宽高比推算，保证每个格子近似正方形 */
    private static final int GRID_ROWS = 64;

    /** WCAG 中白色文字开始优于黑色文字的相对亮度阈值 */
    public static final float BLACK_WHITE_THRESHOLD = 0.179f;

    private static volatile float[] grid;
    private static volatile int gridW;
    private static volatile int gridH;
    /** 纯色/无图背景的亮度；-1 表示未知 */
    private static volatile float solidLuminance = -1f;

    private Contrast() {
    }

    /** 用当前显示的背景位图重建亮度网格（可在后台线程调用） */
    public static void updateFromBitmap(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            grid = null;
            return;
        }
        int gw = GRID_ROWS;
        int gh = Math.max(1, Math.round(GRID_ROWS * bmp.getHeight() / (float) bmp.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(bmp, gw, gh, true);
        if (small == null) return;
        int[] px = new int[gw * gh];
        small.getPixels(px, 0, gw, 0, 0, gw, gh);
        if (small != bmp) small.recycle();

        float[] lum = new float[px.length];
        for (int i = 0; i < px.length; i++) lum[i] = luminance(px[i]);
        gridW = gw;
        gridH = gh;
        grid = lum;
    }

    /** 纯色背景亮度；-1 表示未知（深色主题/浅色主题由调用方估算） */
    public static void setSolidLuminance(float value) {
        solidLuminance = value;
        if (value >= 0) grid = null;
    }

    public static float getSolidLuminance() {
        return solidLuminance;
    }

    /**
     * 按归一化坐标（0~1，相对背景图显示区域）取亮度。
     * 没有网格时返回纯色亮度；再没有就返回 -1（未知）。
     */
    public static float sample(float fx, float fy) {
        float[] g = grid;
        if (g == null || gridW <= 0 || gridH <= 0) return solidLuminance;
        int x = (int) (fx * gridW);
        int y = (int) (fy * gridH);
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x >= gridW) x = gridW - 1;
        if (y >= gridH) y = gridH - 1;
        return g[y * gridW + x];
    }

    /** 该亮度下白字是否比黑字更清楚（WCAG 黑/白等对比点 0.179） */
    public static boolean prefersWhiteText(float luminance) {
        return luminance < BLACK_WHITE_THRESHOLD;
    }

    /** 该亮度是否算「浅色背景」（用于图标底衬选深/浅） */
    public static boolean isLight(float luminance) {
        return luminance > 0.45f;
    }

    /** WCAG 相对亮度：先把 sRGB 通道线性化，再加权 */
    public static float luminance(int color) {
        float r = linear((color >> 16) & 0xff);
        float g = linear((color >> 8) & 0xff);
        float b = linear(color & 0xff);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float linear(int channel) {
        float s = channel / 255f;
        return s <= 0.04045f ? s / 12.92f : (float) Math.pow((s + 0.055f) / 1.055f, 2.4f);
    }

    /** 颜色是否属于「浅色」（白字放上去会看不清） */
    public static boolean isLightColor(int color) {
        if (Color.alpha(color) < 40) return true; // 近乎透明按背景处理
        return luminance(color) > 0.4f;
    }
}
