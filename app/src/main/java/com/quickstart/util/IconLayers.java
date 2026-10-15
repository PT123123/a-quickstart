package com.quickstart.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * 应用图标背景层剥离。
 *
 * ## 为什么普通应用也能做
 *很多人以为「图标底色」是系统强制的、只有 launcher 能改，其实不对：
 * Android 8.0 起应用图标是 {@link AdaptiveIconDrawable}，本身就是**两层**结构
 * ——background 层（纯色/渐变/位图）+ foreground 层（主体图形）。
 * 桌面启动器把两层叠起来显示，所以看起来有底色；
 * 而 {@code getForeground()} / {@code getBackground()} 都是 public API，
 * **任何应用都能单独取前景层**，不需要 launcher 身份或任何权限。
 *
 * ## 三种模式
 *  - {@link #MODE_KEEP}    原样（保留系统底色）
 *  - {@link #MODE_FOREGROUND} 只取前景层：底色真透明，主体放大填满整格
 *  - {@link #MODE_TINT}    前景层 + 自动采样主色补圆角底：给前景太稀疏、
 *    单独显示几乎认不出来的图标兜底
 *
 * ## 为什么必须放大 1.5 倍（真机实测结论，别再改成1:1）
 * adaptive icon 画布是 108dp，设计规范只要求主体落在中央 72dp 安全区内
 * （四周 18dp bleed 给启动器裁切），即主体只占画布的 72/108 = 67%。
 * 带底色时撑满整格的是**底色方块**而不是主体，所以底色一拿掉，
 * 1:1 绘制会让主体只占半格，看起来明显小一圈（真机实测仅 47%~56%）。
 * 因此要把前景层放大 {@code size * (108/72)} 让主体填满整格。
 *
 * 曾在这处翻车三次：{@code size / BLEED}（缩小）、1:1（仍小）、
 * 最终 {@code size * BLEED}（正确）。根因都是**基准选错** ——
 * 该比的是「主体占格子的比例」，不是「画布尺寸」。
 */
public final class IconLayers {

    /** 保持系统原样 */
    public static final String MODE_KEEP = "keep";
    /** 只取前景层，底色真透明 */
    public static final String MODE_FOREGROUND = "foreground";
    /** 前景层 + 品牌色圆角底（前景太稀疏时用） */
    public static final String MODE_TINT = "tint";

    /** 前景层相对安全区的放大系数（108dp 画布 / 72dp 安全区）。
     *  当前按 1:1 绘制用不到它，保留作为「填满格子」模式的参数依据。 */
    private static final float BLEED = 108f / 72f;

    private IconLayers() {
    }

    /**
     * 按模式把系统图标转换为「目标观感」的位图。
     * 无法剥离时（老应用非自适应图标 / 取不到前景层）回退到原样绘制。
     *
     * @param icon    原始图标
     * @param mode    见 MODE_*
     * @param size    输出边长（px）
     * @param tintArgb 仅 MODE_TINT 使用：补底色；传0 表示由主色采样得到
     */
    public static Bitmap toBitmap(Drawable icon, String mode, int size, int tintArgb) {
        if (icon == null || size <= 0) return null;
        if (MODE_KEEP.equals(mode)) {
            return IconCache.drawableToScaledBitmap(icon, size);
        }
        Drawable fg = foregroundOf(icon);
        if (fg == null) {
            // 非自适应图标（Android 8.0 以前的老应用）：只有一张合成好的 PNG，
            // 底色已经烘焙进像素里，没有「层」可拆，只能原样显示
            return IconCache.drawableToScaledBitmap(icon, size);
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        if (MODE_TINT.equals(mode)) {
            int color = tintArgb != 0 ? tintArgb : dominantColor(fg);
            drawRoundRect(canvas, size, color);
        }
        drawForeground(canvas, fg, size);
        return out;
    }

    /** 取出自适应图标的前景层；不是自适应图标返回 null。
     *
     *  注意：**只有刚从 PackageManager 取出来的原始 Drawable 才可能是
     *  AdaptiveIconDrawable**。一旦渲染成 Bitmap 再回读（缓存命中路径），
     *  类型就变成 BitmapDrawable，两层已被压平、无法再拆 —— 这就是为什么
     *  外观模式必须在<b>落盘前</b>生效，改设置必须清缓存重建。
     *  （也有 ROM 会额外包一层，此时同样拆不开，只能按合成图处理。） */
    public static Drawable foregroundOf(Drawable icon) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        if (icon instanceof AdaptiveIconDrawable) {
            try {
                return ((AdaptiveIconDrawable) icon).getForeground();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 绘制前景层：**放大 BLEED(1.5) 倍**让主体填满整个显示框。
     *
     * 【实测数据】从真机缓存里量出来的（256px 画布，foreground 模式）：
     *   主体包围盒仅占画布 47%~56%（WPS 48%、唯品会 56%、设置 50%、微信 47%）。
     *   原因：{@code setBounds(0,0,size,size)}缩放的是**整个 108dp 画布**，
     *   而主体只占画布中央 72dp= 67%，两者相乘 ≈ 0.67。
     *
     * 【为什么必须放大】带底色时撑满整格的是**底色方块**，不是主体；
     * 底色拿掉后主体就显小。要让用户看到的主体尺寸和原来那个方块一样，
     * 就得把前景层放大 108/72 = 1.5 倍，让主体正好填满格子。
     *
     * 【两次踩坑的教训】
     *   1. 写成 {@code size / BLEED}（除）= 缩小 1.5 倍 → 主体掉到 44%，明显变小；
     *   2. 改成 1:1（不缩放）→ 主体 67%，**仍然偏小**（我误把「画布」当成了「主体」，
     *      还以为 1:1 就等于主体一样大）。
     * 两次都错，是因为基准搞错了：**该比的是主体，不是画布**。
     */
    private static void drawForeground(Canvas canvas, Drawable fg, int size) {
        // 放大到size * 1.5，再让中央 72dp 安全区正好覆盖 size —— 主体即填满格子
        int bleed = Math.round(size * BLEED);
        int left = (size - bleed) / 2;
        int top = (size - bleed) / 2;
        fg.setBounds(left, top, left + bleed, top + bleed);
        try {
            fg.draw(canvas);
        } catch (Throwable ignored) {
        }
    }

    /** 画圆角底板（与系统图标的圆角比例接近） */
    private static void drawRoundRect(Canvas canvas, int size, int argb) {
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(argb);
        float r = size * 0.22f;
        // drawRoundRect 的圆角是 float，必须用 RectF（Rect 重载会把圆角强转成 int 而失真）
        canvas.drawRoundRect(new RectF(0, 0, size, size), r, r, p);
    }

    /**
     * 从前景层采样主色（取不透明像素里出现最多的颜色）。
     * 只统计 alpha 足够高的像素，避免把抗锯齿边缘的浅色当成主色。
     */
    private static int dominantColor(Drawable fg) {
        try {
            int w = fg.getIntrinsicWidth();
            int h = fg.getIntrinsicHeight();
            if (w <= 0 || h <= 0) return 0xFF3D7EFF;
            // 降采样到 32px 足够代表主色，且开销极小
            int s = 32;
            Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            fg.setBounds(0, 0, s, s);
            fg.draw(c);

            java.util.HashMap<Integer, Integer> hist = new java.util.HashMap<>();
            int[] px = new int[s * s];
            bmp.getPixels(px, 0, s, 0, 0, s, s);
            for (int p : px) {
                if (((p >>> 24) & 0xFF) < 200) continue;
                // 量化到 5bit 通道，容忍压缩噪声
                int key = Color.rgb((p >> 16) & 0xE0, (p >> 8) & 0xE0, p & 0xE0);
                Integer prev = hist.get(key);
                hist.put(key, prev == null ? 1 : prev + 1);
            }
            int best = 0;
            int bestN = 0;
            for (java.util.Map.Entry<Integer, Integer> e : hist.entrySet()) {
                if (e.getValue() > bestN) {
                    bestN = e.getValue();
                    best = e.getKey();
                }
            }
            if (bestN == 0) return 0xFF3D7EFF;
            // 量化回 8bit
            return Color.rgb(((best >> 16) & 0xE0) | (((best >> 16) & 0xE0) >> 5),
                    ((best >> 8) & 0xE0) | (((best >> 8) & 0xE0) >> 5),
                    (best & 0xE0) | ((best & 0xE0) >> 5));
        } catch (Throwable t) {
            return 0xFF3D7EFF;
        }
    }

    /** 该图标是否支持剥离背景（用于设置项提示） */
    public static boolean isStrippable(Drawable icon) {
        return foregroundOf(icon) != null;
    }
}