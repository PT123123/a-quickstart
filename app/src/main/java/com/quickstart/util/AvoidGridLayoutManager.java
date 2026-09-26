package com.quickstart.util;

import android.content.Context;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 支持「避让区域」的网格 LayoutManager：按列数从上往下铺格子，
 * 落在避让区域里的格子直接跳过，应用依次排到后面的空格子上。
 *
 * 替换 GridLayoutManager 的原因：GLM 的 spanSizeLookup 只能改变某项占几列，
 * 无法「跳过」某个格子，而避让区域是任意形状，必须自己控制每个格子去留。
 *
 * 坐标系约定：
 *  - 内容坐标 = 滚动偏移为 0 时的格子坐标（y 轴从 topInsetPx 开始）；
 *  - 避让区域由外部传入（归一化 + 参考宽高，通常取背景显示区的实测尺寸，
 *    存于 BackgroundManager.PREF_AREA_W/H），在此处换算为内容坐标像素后做命中判断。
 *
 * 实现说明：
 *  - 每格高度固定为 cellW * 1.3，与 AppListAdapter.updateItemSquareSize 的尺寸公式一致；
 *  - 只把视口内的子 View 挂上去（滚动时 scrap 复用，不做预布局动画，
 *    supportsPredictiveItemAnimations 默认 false）；
 *  - 空态（itemCount=0）直接返回，交由外部空态提示展示。
 */
public class AvoidGridLayoutManager extends RecyclerView.LayoutManager {

    /** 与 AppListAdapter.updateItemSquareSize 保持一致的格高比例 */
    private static final float CELL_HEIGHT_FACTOR = 1.3f;
    /** 兜底行数上限：避免异常数据（如超高避让区域）导致死循环 */
    private static final int MAX_ROWS = 2000;

    private int columnCount;
    private List<ZoneStore.Zone> zones = new ArrayList<>();
    /** 避让区域归一化坐标的参考尺寸（背景显示区实测像素） */
    private float zoneRefW, zoneRefH;
    /** 内容顶部留白（像素）：给主界面顶部的收缩/展开拉手让位 */
    private int topInsetPx;

    private int pendingScrollPos = -1;
    private int scrollY;

    // 每次 onLayoutChildren 重算并缓存，供滚动复用
    private int cellW, cellH, lastColW, rowsUsed;
    private final List<Integer> freeCells = new ArrayList<>();
    /** 预换算的像素避让区域：[0] 是方形 RectF，[1] 是圆形 {cx, cy, r} */
    private final List<RectF> zoneRects = new ArrayList<>();
    private final List<float[]> zoneCircles = new ArrayList<>();

    public AvoidGridLayoutManager(Context context, int columnCount) {
        this.columnCount = Math.max(1, columnCount);
        // 主界面 RecyclerView 尺寸固定（ViewPager2 页面），走自动测量即可，
        // onLayoutChildren 里按最终宽高铺格子
        setAutoMeasureEnabled(true);
    }

    /** 设置列数（列数变化后格子重排） */
    public void setColumnCount(int n) {
        n = Math.max(1, n);
        if (n == columnCount) return;
        columnCount = n;
        requestLayout();
    }

    /**
     * 应用避让区域。zones 为归一化坐标（见 ZoneStore），
     * refW/refH 为参考尺寸（背景显示区像素，通常取 BackgroundManager.PREF_AREA_W/H）。
     */
    public void applyAvoidZones(List<ZoneStore.Zone> zones, float refW, float refH) {
        this.zones = zones != null ? new ArrayList<>(zones) : new ArrayList<>();
        this.zoneRefW = Math.max(1f, refW);
        this.zoneRefH = Math.max(1f, refH);
        requestLayout();
    }

    /** 内容顶部留白（像素），如主界面顶部拉手高度 */
    public void setTopInsetPx(int px) {
        if (px == topInsetPx) return;
        topInsetPx = Math.max(0, px);
        requestLayout();
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public boolean canScrollVertically() {
        return contentHeight() > getHeight();
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        detachAndScrapAttachedViews(recycler);
        int itemCount = state.getItemCount();
        if (itemCount == 0) {
            freeCells.clear();
            rowsUsed = 0;
            scrollY = 0;
            return;
        }
        int availW = getWidth() - getPaddingLeft() - getPaddingRight();
        int viewportH = getHeight() - getPaddingTop() - getPaddingBottom();
        if (availW <= 0 || viewportH <= 0) return;

        cellW = Math.max(1, availW / columnCount);
        lastColW = cellW + (availW - cellW * columnCount); // 最后一列吃掉整除余数
        cellH = Math.max(1, Math.round(cellW * CELL_HEIGHT_FACTOR));

        buildFreeCells(itemCount);

        int maxScroll = Math.max(0, contentHeight() - viewportH);
        if (pendingScrollPos >= 0) {
            scrollY = scrollForPosition(pendingScrollPos, viewportH, maxScroll);
            pendingScrollPos = -1;
        }
        if (scrollY > maxScroll) scrollY = maxScroll;
        if (scrollY < 0) scrollY = 0;

        fill(recycler, state, viewportH);
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
        if (getChildCount() == 0 || cellH == 0) return 0;
        int viewportH = getHeight() - getPaddingTop() - getPaddingBottom();
        int maxScroll = Math.max(0, contentHeight() - viewportH);
        int target = scrollY + dy;
        if (target < 0) target = 0;
        if (target > maxScroll) target = maxScroll;
        int consumed = target - scrollY;
        if (consumed == 0) return 0;
        scrollY = target;
        offsetChildrenVertical(-consumed);
        fill(recycler, state, viewportH);
        return consumed;
    }

    @Override
    public void scrollToPosition(int position) {
        // 只记下目标位置；RecyclerView.scrollToPosition 随后会触发 requestLayout，
        // onLayoutChildren 时把该位置滚到可见范围
        pendingScrollPos = position;
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        scrollToPosition(position); // 简化处理：直接跳转（调用方只用 scrollToTop）
    }

    /** 总内容高度（含顶部留白） */
    private int contentHeight() {
        return topInsetPx + rowsUsed * cellH;
    }

    /**
     * 生成「第 i 个应用 → 第几个格子」的映射：逐行扫描格子，
     * 与避让区域相交的格子跳过，直到排完所有应用。
     */
    private void buildFreeCells(int itemCount) {
        convertZonesToPx();
        freeCells.clear();
        int rows = 0;
        while (freeCells.size() < itemCount && rows < MAX_ROWS) {
            int top = topInsetPx + rows * cellH;
            for (int c = 0; c < columnCount && freeCells.size() < itemCount; c++) {
                int left = c * cellW;
                int cw = (c == columnCount - 1) ? lastColW : cellW;
                if (!hitAnyZone(left, top, cw, cellH)) {
                    freeCells.add(rows * columnCount + c);
                }
            }
            rows++;
        }
        rowsUsed = rows;
    }

    /** 把归一化避让区域换算为内容坐标（滚动 0 时）的像素矩形/圆 */
    private void convertZonesToPx() {
        zoneRects.clear();
        zoneCircles.clear();
        for (ZoneStore.Zone z : zones) {
            if (z.type == ZoneStore.Zone.TYPE_CIRCLE) {
                float r = z.w * zoneRefW; // 半径以宽度为基准
                if (r <= 0) continue;
                zoneCircles.add(new float[]{z.x * zoneRefW, z.y * zoneRefH, r});
            } else {
                float wpx = z.w * zoneRefW;
                float hpx = z.h * zoneRefH;
                if (wpx <= 0 || hpx <= 0) continue;
                zoneRects.add(new RectF(z.x * zoneRefW, z.y * zoneRefH,
                        z.x * zoneRefW + wpx, z.y * zoneRefH + hpx));
            }
        }
    }

    /** 格子矩形是否与任一避让区域相交 */
    private boolean hitAnyZone(int left, int top, int cw, int ch) {
        if (zoneRects.isEmpty() && zoneCircles.isEmpty()) return false;
        float rl = left, rt = top, rr = left + cw, rb = top + ch;
        for (int i = 0; i < zoneRects.size(); i++) {
            RectF z = zoneRects.get(i);
            if (rr > z.left && rl < z.right && rb > z.top && rt < z.bottom) return true;
        }
        for (int i = 0; i < zoneCircles.size(); i++) {
            float[] c = zoneCircles.get(i);
            // 圆与矩形相交：矩形上离圆心最近的点落在圆内
            float nx = clampf(c[0], rl, rr);
            float ny = clampf(c[1], rt, rb);
            float dx = c[0] - nx, dy = c[1] - ny;
            if (dx * dx + dy * dy <= c[2] * c[2]) return true;
        }
        return false;
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 第 position 个应用所在格子的行号 */
    private int rowOf(int position) {
        int cell = freeCells.get(Math.max(0, Math.min(position, freeCells.size() - 1)));
        return cell / columnCount;
    }

    /** 计算让 position 所在格子完整可见的滚动偏移（尽量不动，优先顶格） */
    private int scrollForPosition(int position, int viewportH, int maxScroll) {
        if (position < 0 || position >= freeCells.size()) return scrollY;
        int y = topInsetPx + rowOf(position) * cellH;
        if (y < scrollY) {
            // 目标格在视口上方：滚到它上面，保住顶部留白
            return clamp(y - topInsetPx, 0, maxScroll);
        }
        if (y + cellH > scrollY + viewportH) {
            // 目标格在视口下方：让它的底边贴住视口底
            return clamp(y + cellH - viewportH, 0, maxScroll);
        }
        return scrollY;
    }

    /** 只把视口内的子 View 挂上（进入/离开视口的复用交给 scrap） */
    private void fill(RecyclerView.Recycler recycler, RecyclerView.State state, int viewportH) {
        detachAndScrapAttachedViews(recycler);
        int itemCount = state.getItemCount();
        int visibleCount = Math.min(itemCount, freeCells.size());
        for (int i = 0; i < visibleCount; i++) {
            int cell = freeCells.get(i);
            int r = cell / columnCount;
            int c = cell % columnCount;
            int top = topInsetPx + r * cellH - scrollY;
            if (top + cellH < 0 || top > viewportH) continue; // 视口外不挂载
            int cw = (c == columnCount - 1) ? lastColW : cellW;
            int left = c * cellW + getPaddingLeft();
            View v = recycler.getViewForPosition(i);
            addView(v);
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(cw, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(cellH, android.view.View.MeasureSpec.EXACTLY));
            v.layout(left, top + getPaddingTop(), left + cw, top + getPaddingTop() + cellH);
        }
    }
}
