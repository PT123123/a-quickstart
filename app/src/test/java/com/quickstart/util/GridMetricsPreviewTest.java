package com.quickstart.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 避让区编辑器预览 vs 主界面 AvoidGridLayoutManager 的几何一致性门。
 *
 * 背景：旧版预览拿画布尺寸直接套格高公式，密度留白按 1/previewScale 换算（方向反了），
 * 高密度窄屏（OPPO 1080p/density3）上预览行高比主界面每行高出几十像素，
 * 避让命中整片错位；平板上比例项恰好压过留白项，缺陷被掩盖。
 * 修复后预览走 {@link GridMetrics#previewGeometry}：真实坐标整数几何 × previewScale。
 *
 * 本测试用与两侧源码相同的公式在 JVM 上复算：
 *  - 主界面臂：复刻 AvoidGridLayoutManager.onLayoutChildren/buildFreeCells/hitAnyZone；
 *  - 预览臂：直接调被测源码 GridMetrics.previewGeometry + 复刻 ZoneCanvas.hitAnyZone；
 *  - control 臂：复刻旧版预览公式，断言它在 OPPO 参数下必然偏离（证明门看得见原缺陷）。
 */
public class GridMetricsPreviewTest {

    /** 设备参数：{areaW, areaH, density, scaledDensity}，含 OPPO 两种分辨率与主力平板 */
    private static final float[][] DEVICES = {
            {1080, 2174, 3f, 3f},      // OPPO 1080p，默认字体
            {1080, 2174, 3f, 3.6f},    // OPPO 1080p，系统字体放大 1.2
            {1264, 2400, 3f, 3f},      // OPPO 1.5K
            {1600, 2300, 2f, 2f},      // 联想平板（旧公式在这里恰好不偏）
    };
    private static final int[] SCALES = {50, 75, 100, 125, 150};
    /** 归一化区域（圆/方混合），坐标刻意不贴网格边界（也避开精确相切：切点处浮点乘法顺序会翻转判定） */
    private static final float[][] ZONES = {
            {0, 0.5f, 0.37f, 0.18f, 0f},   // 圆：type,x,y,w(半径),h
            {1, 0.1f, 0.61f, 0.35f, 0.2f}, // 方
            {0, 0.86f, 0.83f, 0.1f, 0f},
    };
    private static final int ROWS = 8;

    @Test
    public void previewGeometryMatchesLayoutManager() {
        for (float[] dev : DEVICES) {
            int areaW = (int) dev[0];
            float aspect = dev[0] / dev[1];
            // 画布尺寸与 ZoneEditorActivity.measureCanvasWidth 同一公式
            int canvasW = Math.max(200, Math.min(areaW, Math.round(dev[1] * 0.55f * aspect)));
            float ps = canvasW / (float) areaW;
            for (int columns = 2; columns <= 5; columns++) {
                for (int scale : SCALES) {
                    String tag = "dev=" + areaW + "x" + dev[1] + " d=" + dev[2]
                            + " cols=" + columns + " scale=" + scale;

                    // ---- 主界面臂（复刻 AvoidGridLayoutManager）----
                    int cellW = Math.max(1, areaW / columns);
                    int lastColW = cellW + (areaW - cellW * columns);
                    int cellH = Math.max(1, GridMetrics.cellHeightPx(
                            cellW, scale, dev[2], dev[3], columns));
                    int topInset = GridMetrics.topInsetPx(dev[2]);

                    // ---- 预览臂（被测源码）----
                    GridMetrics.PreviewGeometry g = GridMetrics.previewGeometry(
                            areaW, columns, scale, dev[2], dev[3], ps);

                    // 几何逐像素成比例
                    assertEquals(tag + " cellW", cellW * ps, g.cellW, 0.01f);
                    assertEquals(tag + " lastColW", lastColW * ps, g.lastColW, 0.01f);
                    assertEquals(tag + " cellH", cellH * ps, g.cellH, 0.01f);
                    assertEquals(tag + " topInset", topInset * ps, g.topInset, 0.01f);
                    assertEquals(tag + " iconSize",
                            GridMetrics.iconSizePx(cellW, scale) * ps, g.iconSize, 0.01f);

                    // 逐格避让命中必须一致
                    for (int row = 0; row < ROWS; row++) {
                        for (int col = 0; col < columns; col++) {
                            int cw = (col == columns - 1) ? lastColW : cellW;
                            boolean realHit = hitReal(col * cellW, topInset + row * cellH,
                                    cw, cellH, areaW, (int) dev[1]);
                            float pcw = (col == columns - 1) ? g.lastColW : g.cellW;
                            // 预览臂的纵向基准是「背景区高度 × previewScale」，不是画布取整高度
                            boolean previewHit = hitCanvas(col * g.cellW, g.topInset + row * g.cellH,
                                    pcw, g.cellH, canvasW, ps * dev[1]);
                            assertEquals(tag + " hit row=" + row + " col=" + col,
                                    realHit, previewHit);
                        }
                    }
                }
            }
        }
    }

    /**
     * control 臂：旧版预览公式（画布 cellW 直接套公式 + 留白 × 1/ps）必须在 OPPO 参数下
     * 显著偏离主界面——若本断言失败，说明上面的等价断言对原缺陷不敏感（门失效）。
     * 同时断言平板参数下旧公式偏差 < 2px，解释缺陷为何只在 OPPO 暴露。
     */
    @Test
    public void oldFormulaDriftsOnOppo_control() {
        float oppoDrift = oldFormulaRowDrift(1080, 2174, 3f, 3f, 3, 100);
        assertTrue("OPPO 参数下旧公式应明显偏离（实际 " + oppoDrift + "px）", oppoDrift > 10f);

        float tabletDrift = oldFormulaRowDrift(1600, 2300, 2f, 2f, 3, 100);
        assertTrue("平板参数下旧公式恰好不偏（实际 " + tabletDrift + "px）",
                Math.abs(tabletDrift) < 2f);
    }

    /** 旧公式第 5 行行顶相对主界面的偏差（真实像素） */
    private float oldFormulaRowDrift(int areaW, int areaH, float d, float sd,
                                     int columns, int scale) {
        float aspect = areaW / (float) areaH;
        int canvasW = Math.max(200, Math.min(areaW, Math.round(areaH * 0.55f * aspect)));
        float ps = canvasW / (float) areaW;
        int realCellW = Math.max(1, areaW / columns);
        int realCellH = Math.max(1, GridMetrics.cellHeightPx(realCellW, scale, d, sd, columns));
        int realTopInset = GridMetrics.topInsetPx(d);

        // 旧版 ZoneEditorActivity.drawPreview 的公式（unitScale = 1/ps，画布 cellW 取整）
        int canvasCellW = Math.round(canvasW / (float) columns);
        float s = scale / 100f;
        int icon = Math.max(1, Math.round(canvasCellW * 0.65f * s));
        int scaled = Math.max(1, Math.round(canvasCellW * 1.3f * s));
        float reserve = 16f * d + GridMetrics.textSizeSp(columns) * 1.25f * sd;
        int oldCellH = Math.max(scaled, Math.round(icon + reserve / ps));
        float oldTopInset = 28f * d * ps;

        int row = 5;
        return (oldTopInset + row * oldCellH) / ps - (realTopInset + row * realCellH);
    }

    /** 复刻 AvoidGridLayoutManager.hitAnyZone（真实坐标，区域按参考宽高换算） */
    private static boolean hitReal(float left, float top, float cw, float ch,
                                   int refW, int refH) {
        for (float[] z : ZONES) {
            if (z[0] == 0) {
                float cx = z[1] * refW, cy = z[2] * refH, r = z[3] * refW;
                float nx = clamp(cx, left, left + cw);
                float ny = clamp(cy, top, top + ch);
                float dx = cx - nx, dy = cy - ny;
                if (dx * dx + dy * dy <= r * r) return true;
            } else {
                float l = z[1] * refW, t = z[2] * refH;
                float r = l + z[3] * refW, b = t + z[4] * refH;
                if (left + cw > l && left < r && top + ch > t && top < b) return true;
            }
        }
        return false;
    }

    /** 复刻 ZoneEditorActivity.ZoneCanvas.hitAnyZone（画布坐标，区域按画布宽高换算） */
    private static boolean hitCanvas(float left, float top, float cw, float ch,
                                     int canvasW, float canvasH) {
        for (float[] z : ZONES) {
            if (z[0] == 0) {
                float cx = z[1] * canvasW, cy = z[2] * canvasH, r = z[3] * canvasW;
                float nx = clamp(cx, left, left + cw);
                float ny = clamp(cy, top, top + ch);
                float dx = cx - nx, dy = cy - ny;
                if (dx * dx + dy * dy <= r * r) return true;
            } else {
                float l = z[1] * canvasW, t = z[2] * canvasH;
                float r = l + z[3] * canvasW, b = t + z[4] * canvasH;
                if (left + cw > l && left < r && top + ch > t && top < b) return true;
            }
        }
        return false;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}