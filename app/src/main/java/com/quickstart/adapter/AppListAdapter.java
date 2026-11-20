package com.quickstart.adapter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.quickstart.R;
import com.quickstart.model.AppEntry;
import com.quickstart.util.AppLoader;
import com.quickstart.util.Contrast;
import com.quickstart.util.GridMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 应用列表 RecyclerView Adapter，支持长按回调 + 匹配文字高亮 + 位置角标快速启动 */
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.VH> {

    public interface OnAppClickListener {
        void onAppClick(AppEntry entry);
    }
    public interface OnAppLongClickListener {
        boolean onAppLongClick(AppEntry entry, View anchor);
    }
    /** 角标手势触发回调：position 是列表中的位置 */
    public interface OnBadgeGestureListener {
        void onBadgeGesture(int position);
    }

    /** 背景亮度提供者：返回某个 View 中心处背景的相对亮度（0~1），未知返回 -1 */
    public interface LuminanceProvider {
        float luminanceFor(View target);
    }

    private static final int HIGHLIGHT_COLOR = Color.parseColor("#1565C0");
    /** 图标异步加载线程池：静态共享，避免每次创建 Adapter 都新开 4 个线程 */
    private static final ExecutorService ICON_EXECUTOR = Executors.newFixedThreadPool(4);

    private final List<AppEntry> items = new ArrayList<>();
    private OnAppClickListener clickListener;
    private OnAppLongClickListener longClickListener;
    private OnBadgeGestureListener badgeGestureListener;
    private Drawable placeholderIcon;

    private String highlightQuery = "";
    private String lastHighlight = "";
    private int fontColor = 0;
    /** 出厂文字颜色（ColorStateList），fontColor 为 0 时恢复用 */
    private android.content.res.ColorStateList defaultFontColors;
    private boolean showRecentDot = true;
    private int columnCount = 3;
    /** 格子缩放百分比（GridMetrics.MIN_CELL_SCALE~MAX），100 = 原始大小 */
    private int cellScale = GridMetrics.DEFAULT_CELL_SCALE;
    /** 图标透明度对应的 View alpha（1f 完全不透明，0f 完全不可见） */
    private float iconAlpha = 1f;
    /** 角标手势模式，每次 submit 时重新读取一次，避免每个 item 重绑都查 SharedPreferences */
    private String badgeGestureMode;
    /**
     * 网格实际宽度（RecyclerView 实测宽），0 表示还没测量到、暂用屏幕宽兜底。
     * 必须与 LayoutManager 铺格子用的宽度一致：小窗/分屏下屏幕宽大于列表宽，
     * 用屏幕宽算图标会把图标撑大、与避让网格错位。
     */
    private int gridWidthPx;

    /** 文字自动反色：按背后背景明暗自动用黑字/白字 */
    private boolean autoContrast;
    /** 文字虚化底衬：文字后加柔化半透明底 */
    private boolean textScrim;
    /** 图标柔化底衬：给（透明底）图标加一圈柔化光晕 */
    private boolean iconBackdrop;
    private LuminanceProvider luminanceProvider;

    /** 底衬 Drawable 缓存，避免每个格子/每次滚动都新建 */
    private Drawable scrimLight, scrimDark, discLight, discDark;

    public void setOnAppClickListener(OnAppClickListener l) { this.clickListener = l; }
    public void setOnAppLongClickListener(OnAppLongClickListener l) { this.longClickListener = l; }
    public void setOnBadgeGestureListener(OnBadgeGestureListener l) { this.badgeGestureListener = l; }

    public void setHighlightQuery(String q) { this.highlightQuery = q == null ? "" : q; }
    public void setFontColor(int color) { this.fontColor = color; notifyDataSetChanged(); }
    /** 设置图标 alpha（View 属性，不影响 Drawable 缓存与文字/角标/红点） */
    public void setIconAlpha(float alpha) {
        this.iconAlpha = alpha;
        notifyDataSetChanged();
    }
    public void setShowRecentDot(boolean show) { this.showRecentDot = show; notifyDataSetChanged(); }
    public void setColumnCount(int count) { this.columnCount = count; notifyDataSetChanged(); }

    /** 背景亮度提供者：由宿主注入（按目标 View 的屏幕位置查背景亮度） */
    public void setLuminanceProvider(LuminanceProvider p) { this.luminanceProvider = p; }

    /** 可读性开关：文字自动反色 / 文字虚化底衬 / 图标柔化底衬 */
    public void setReadability(boolean autoContrast, boolean textScrim, boolean iconBackdrop) {
        if (this.autoContrast == autoContrast && this.textScrim == textScrim
                && this.iconBackdrop == iconBackdrop) {
            return;
        }
        this.autoContrast = autoContrast;
        this.textScrim = textScrim;
        this.iconBackdrop = iconBackdrop;
        notifyDataSetChanged();
    }

    /** 背景图换了：亮度网格已更新，让已显示的格子按新背景重新取色 */
    public void invalidateContrast() {
        notifyDataSetChanged();
    }

    /**
     * 设置格子缩放百分比（图标与格子同比放大/ 缩小）。
     * 与 setColumnCount 一样会 notifyDataSetChanged，让已显示的格子立刻换尺寸。
     */
    public void setCellScale(int percent) {
        int v = Math.max(GridMetrics.MIN_CELL_SCALE,
                Math.min(GridMetrics.MAX_CELL_SCALE, percent));
        if (v == cellScale) return;
        this.cellScale = v;
        notifyDataSetChanged();
    }

    /** 角标手势设置变化后调用：清除缓存并整体重绑，已显示的角标立即换用新手势 */
    public void invalidateBadgeGesture() {
        badgeGestureMode = null;
        notifyDataSetChanged();
    }

    public void submit(List<AppEntry> list) {
        List<AppEntry> newList = list != null ? list : new ArrayList<>();
        badgeGestureMode = null; // 下次绑定角标时按最新设置重新读取

        // 高亮词变化会影响已绑定 cell 的文字渲染；T9 数字输入不产生高亮，
        // 此时走 DiffUtil 局部刷新，否则整体刷新保证高亮正确
        boolean highlightAffectsRender = !isDigitsOnly(highlightQuery)
                || !isDigitsOnly(lastHighlight);
        boolean highlightChanged = !highlightQuery.equals(lastHighlight) && highlightAffectsRender;
        lastHighlight = highlightQuery;

        if (highlightChanged) {
            items.clear();
            items.addAll(newList);
            notifyDataSetChanged();
            return;
        }

        DiffUtil.DiffResult result = DiffUtil.calculateDiff(
                new DiffCallback(new ArrayList<>(items), newList));
        items.clear();
        items.addAll(newList);
        result.dispatchUpdatesTo(this);

        // 前 10 个位置的角标编号随结构变化更新（payload 局部刷新，不重绑整行）
        if (!items.isEmpty()) {
            notifyItemRangeChanged(0, Math.min(10, items.size()), "badge");
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (placeholderIcon == null) {
            // 只在创建第一个 holder 时取一次默认图标（binder 调用），不占用每次绑定
            placeholderIcon = parent.getContext().getPackageManager().getDefaultActivityIcon();
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        VH holder = new VH(v);
        if (defaultFontColors == null) {
            // 记录出厂文字颜色，字体颜色被清空时（fontColor=0）恢复用
            defaultFontColors = holder.name.getTextColors();
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppEntry e = items.get(position);
        h.name.setText(buildHighlightedLabel(e));
        if (fontColor != 0) {
            h.name.setTextColor(fontColor);
        } else if (defaultFontColors != null) {
            h.name.setTextColor(defaultFontColors); // 恢复默认配色（清除自定义字体颜色后）
        }
        // 记下本次绑定的基础文字颜色：自动反色关闭时以它为准
        h.baseTextColor = h.name.getCurrentTextColor();

        // 图标延迟加载
        if (e.icon != null) {
            h.icon.setImageDrawable(e.icon);
        } else {
            h.icon.setImageDrawable(placeholderIcon);
            ICON_EXECUTOR.execute(() -> {
                AppLoader.loadIconForEntry(h.itemView.getContext(), e);
                h.itemView.post(() -> {
                    if (e.icon != null) {
                        // 重新查位置，DiffUtil 增删后捕获的 position 可能已失效
                        int pos = items.indexOf(e);
                        if (pos >= 0) notifyItemChanged(pos, "icon");
                    }
                });
            });
        }

        // 图标透明度（View alpha 是视图属性，payload "icon" 局部刷新不会重置，无需重复设置）
        h.icon.setAlpha(iconAlpha);

        // 尺寸只在列数/缩放变化或首次绑定时应用，避免每次重绑都触发 requestLayout
        if (h.appliedColumnCount != columnCount || h.appliedCellScale != cellScale) {
            updateItemSquareSize(h);
            h.appliedColumnCount = columnCount;
            h.appliedCellScale = cellScale;
        }

        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onAppClick(e);
        });
        h.itemView.setOnLongClickListener(v -> {
            return longClickListener != null && longClickListener.onAppLongClick(e, v);
        });

        // 最近更新圆点
        if (h.recentDot != null) {
            h.recentDot.setVisibility(showRecentDot && e.recentlyUpdated ? View.VISIBLE : View.GONE);
        }

        bindKeyBadge(h, position);

        // 可读性处理（自动反色/底衬）：绑定这一刻格子可能还没布局完，
        // 先按当前状态刷一次，布局完成后再按真实位置取一次背景亮度
        h.lastReadState = Integer.MIN_VALUE;
        applyReadability(h);
        h.itemView.post(() -> {
            if (h.getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                applyReadability(h);
            }
        });
    }

    /**
     * 应用「可读性」外观：自动反色 / 文字虚化底衬 / 图标柔化底衬。
     *
     * 文字颜色按文字背后背景的亮度决定（WCAG 黑/白等对比点），
     * 底衬颜色则跟着文字颜色走（浅色字配深底、深色字配浅底），保证任何组合下都协调。
     * 状态没变时直接返回，滚动时每帧调用也不会产生多余的重绘。
     */
    private void applyReadability(VH h) {
        if (!autoContrast && !textScrim && !iconBackdrop) {
            if (h.lastReadState == 0) return;
            h.lastReadState = 0;
            h.name.setBackground(null);
            h.name.setPadding(0, 0, 0, 0);
            h.name.setShadowLayer(0, 0, 0, 0);
            h.icon.setBackground(null);
            h.icon.setPadding(0, 0, 0, 0);
            return;
        }

        float textLum = luminanceProvider != null ? luminanceProvider.luminanceFor(h.name) : -1f;
        boolean lightText;
        if (autoContrast) {
            lightText = textLum >= 0
                    ? Contrast.prefersWhiteText(textLum)
                    : Contrast.isLightColor(h.baseTextColor);
        } else {
            lightText = Contrast.isLightColor(h.baseTextColor);
        }

        float iconLum = iconBackdrop && luminanceProvider != null
                ? luminanceProvider.luminanceFor(h.icon) : -1f;
        // 图标底下是浅色还是深色：取不到亮度时按文字色的反面试（浅色字一般意味着深底）
        boolean iconOnLight = iconLum >= 0 ? Contrast.isLight(iconLum) : !lightText;

        int state = 0;
        if (autoContrast && textLum >= 0) {
            state |= Contrast.prefersWhiteText(textLum) ? 1 : 2;
        }
        if (textScrim) state |= 4;
        if (iconBackdrop) state |= 8 | (iconOnLight ? 16 : 0);
        if (state == h.lastReadState) return;
        h.lastReadState = state;

        if (autoContrast && textLum >= 0) {
            h.name.setTextColor(lightText ? 0xFFFFFFFF : 0xFF111111);
        }

        if (textScrim) {
            h.name.setBackground(lightText ? darkScrim(h) : lightScrim(h));
            int ph = dp(h, 6);
            int pv = dp(h, 1);
            h.name.setPadding(ph, pv, ph, pv);
            // 极轻的同色阴影向外柔化，让底衬与文字更像「糊在一起」而不是硬边
            h.name.setShadowLayer(dp(h, 2), 0, 0,
                    lightText ? 0x66000000 : 0x66FFFFFF);
        } else {
            h.name.setBackground(null);
            h.name.setPadding(0, 0, 0, 0);
            h.name.setShadowLayer(0, 0, 0, 0);
        }

        if (iconBackdrop) {
            h.icon.setBackground(iconOnLight ? darkDisc() : lightDisc());
            int ip = dp(h, 3);
            h.icon.setPadding(ip, ip, ip, ip);
        } else {
            h.icon.setBackground(null);
            h.icon.setPadding(0, 0, 0, 0);
        }
    }

    /** 滚动时刷新可见格子的取色（背景不动、格子动了，文字背后的明暗会变） */
    public void refreshVisibleContrast(RecyclerView rv) {
        if (!autoContrast && !textScrim && !iconBackdrop) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
            if (vh instanceof VH) applyReadability((VH) vh);
        }
    }

    private static int dp(VH h, int value) {
        return Math.round(value * h.itemView.getResources().getDisplayMetrics().density);
    }

    /** 文字底衬：浅色文字配深色柔化底 */
    private Drawable darkScrim(VH h) {
        if (scrimDark == null) scrimDark = new SoftScrimDrawable(0xC9000000, dp(h, 10));
        return scrimDark;
    }

    /** 文字底衬：深色文字配浅色柔化底 */
    private Drawable lightScrim(VH h) {
        if (scrimLight == null) scrimLight = new SoftScrimDrawable(0xD9FFFFFF, dp(h, 10));
        return scrimLight;
    }

    /** 图标柔化光晕：浅色（深色背景上用） */
    private Drawable lightDisc() {
        if (discLight == null) discLight = softDisc(0x59FFFFFF);
        return discLight;
    }

    /** 图标柔化光晕：深色（浅色背景上用） */
    private Drawable darkDisc() {
        if (discDark == null) discDark = softDisc(0x33000000);
        return discDark;
    }

    /** 圆形径向渐变光晕：中心不透明、向外柔和淡出 */
    private static Drawable softDisc(final int color) {
        ShapeDrawable sd = new ShapeDrawable(new OvalShape());
        sd.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override
            public Shader resize(int width, int height) {
                float radius = Math.min(width, height) / 2f;
                return new RadialGradient(width / 2f, height / 2f, Math.max(1f, radius),
                        new int[]{color, color, color & 0x00FFFFFF},
                        new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
            }
        });
        return sd;
    }

    /** 柔化圆角底衬：多层同心圆角矩形叠加，视觉上接近虚化边缘（比逐帧真模糊便宜几个数量级） */
    private static final class SoftScrimDrawable extends Drawable {
        private static final float[] GROW = {1.0f, 0.7f, 0.35f, 0f};
        private static final float[] ALPHA = {0.10f, 0.22f, 0.45f, 1f};

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final float radius;

        SoftScrimDrawable(int color, float radius) {
            this.color = color;
            this.radius = radius;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            int baseAlpha = Color.alpha(color);
            int rgb = color & 0x00FFFFFF;
            for (int i = 0; i < GROW.length; i++) {
                float grow = radius * GROW[i] * 0.45f;
                paint.setColor((Math.round(baseAlpha * ALPHA[i]) << 24) | rgb);
                float r = radius + grow;
                canvas.drawRoundRect(b.left - grow, b.top - grow,
                        b.right + grow, b.bottom + grow, r, r, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            // 颜色里已带透明度，无需再叠加
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** 位置数字角标：前10个结果显示 1-9, 0 */
    private void bindKeyBadge(VH h, int position) {
        if (h.keyBadge == null) return;
        if (position < 10) {
            h.keyBadge.setText(position == 9 ? "0" : String.valueOf(position + 1));
            h.keyBadge.setVisibility(View.VISIBLE);
            setupBadgeGesture(h);
        } else {
            h.keyBadge.setVisibility(View.GONE);
        }
    }

    /**
     * 为角标设置手势：使用与数字键相反的手势触发回调。
     * - 全局设为"长按"时，数字键用长按，角标用上滑
     * - 全局设为"上滑"时，数字键用上滑，角标用长按
     * 触发时通过 getBindingAdapterPosition 实时取位置，列表增删后不会拿错应用。
     */
    private void setupBadgeGesture(VH h) {
        TextView badge = h.keyBadge;
        if (badgeGestureMode == null) {
            badgeGestureMode = readBadgeGesture(badge.getContext());
        }
        final boolean[] gestureFired = {false};
        final float[] startY = {0};

        if ("swipe_up".equals(badgeGestureMode)) {
            // 角标用上滑触发
            badge.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        gestureFired[0] = false;
                        startY[0] = event.getY();
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dy = startY[0] - event.getY();
                        if (dy > 30 && !gestureFired[0]) {
                            gestureFired[0] = true;
                            fireBadgeGesture(h);
                        }
                        break;
                }
                return gestureFired[0];
            });
            badge.setOnLongClickListener(null);
        } else {
            // 角标用长按触发
            badge.setOnTouchListener(null);
            badge.setOnLongClickListener(v -> {
                fireBadgeGesture(h);
                return true;
            });
        }
    }

    private void fireBadgeGesture(VH h) {
        int position = h.getBindingAdapterPosition();
        if (position >= 0 && badgeGestureListener != null) {
            badgeGestureListener.onBadgeGesture(position);
        }
    }

    /** 读取角标应使用的手势（与数字键全局设置相反） */
    private String readBadgeGesture(Context ctx) {
        android.content.SharedPreferences sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String keyGesture = sp.getString("key_gesture", "long_press");
        return "long_press".equals(keyGesture) ? "swipe_up" : "long_press";
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        if (payloads.contains("icon") || payloads.contains("badge")) {
            AppEntry e = items.get(position);
            if (payloads.contains("icon") && e.icon != null) {
                h.icon.setImageDrawable(e.icon);
            }
            if (payloads.contains("badge")) {
                bindKeyBadge(h, position);
            }
        } else {
            onBindViewHolder(h, position);
        }
    }

    /** 设置网格实际宽度（页面实测 RecyclerView 宽后调用），尺寸变化时重绑已显示格子 */
    public void setGridWidthPx(int widthPx) {
        if (widthPx <= 0 || widthPx == gridWidthPx) return;
        gridWidthPx = widthPx;
        notifyDataSetChanged();
    }

    private void updateItemSquareSize(VH h) {
        android.util.DisplayMetrics dm = h.itemView.getResources().getDisplayMetrics();
        int baseWidth = gridWidthPx > 0 ? gridWidthPx : dm.widthPixels;
        int cellWidth = baseWidth / Math.max(1, columnCount);
        // 尺寸公式集中在 GridMetrics，避免与 LayoutManager / 避让区编辑器不一致
        int cellHeight = GridMetrics.cellHeightPx(cellWidth, cellScale,
                dm.density, dm.scaledDensity, columnCount);
        if (cellHeight > 0) {
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            lp.height = cellHeight;
            h.itemView.setLayoutParams(lp);

            int iconSize = GridMetrics.iconSizePx(cellWidth, cellScale);
            if (iconSize > 0) {
                ViewGroup.LayoutParams iconLp = h.icon.getLayoutParams();
                iconLp.width = iconSize;
                iconLp.height = iconSize;
                h.icon.setLayoutParams(iconLp);
            }
        }
        h.name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                GridMetrics.textSizeSp(columnCount));
        // 文字宽度跟随格子：底衬只包住文字本身，而不是铺满整列
        int maxName = Math.max(0, cellWidth - Math.round(dm.density * 8));
        if (maxName != h.appliedMaxNameWidth) {
            h.appliedMaxNameWidth = maxName;
            h.name.setMaxWidth(maxName);
        }
    }

    private CharSequence buildHighlightedLabel(AppEntry e) {
        String label = e.label;
        if (highlightQuery.isEmpty() || isDigitsOnly(highlightQuery)) {
            return label;
        }
        String lower = label.toLowerCase();
        String q = highlightQuery.toLowerCase();
        int idx = lower.indexOf(q);
        if (idx < 0) return label;
        SpannableString span = new SpannableString(label);
        span.setSpan(new ForegroundColorSpan(HIGHLIGHT_COLOR),
                idx, idx + q.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    /** 等价于原来的 matches("\\d+")，但不会每次绑定都编译一次正则 */
    private static boolean isDigitsOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    @Override
    public int getItemCount() { return items.size(); }

    /** DiffUtil 回调：以包名+Activity 为唯一标识，按键过滤时只刷新真正变化的 cell */
    private static final class DiffCallback extends DiffUtil.Callback {
        private final List<AppEntry> oldList;
        private final List<AppEntry> newList;

        DiffCallback(List<AppEntry> oldList, List<AppEntry> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }

        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            AppEntry a = oldList.get(oldPos);
            AppEntry b = newList.get(newPos);
            return a.packageName.equals(b.packageName)
                    && a.activityName.equals(b.activityName);
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            AppEntry a = oldList.get(oldPos);
            AppEntry b = newList.get(newPos);
            return a.label.equals(b.label)
                    && a.recentlyUpdated == b.recentlyUpdated
                    && (a.icon != null) == (b.icon != null);
        }
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final View recentDot;
        final TextView keyBadge;
        /** 记录当前 holder 已应用的列数，避免每次重绑都重设尺寸触发 requestLayout */
        int appliedColumnCount = -1;
        /** 记录当前 holder 已应用的格子缩放百分比，缩放变化时同样需要重设尺寸 */
        int appliedCellScale = -1;
        /** 已应用的文字最大宽度（px），避免重复 requestLayout */
        int appliedMaxNameWidth = -1;
        /** 本次绑定的基础文字颜色（未开自动反色时用它，同时用于判断底衬深浅） */
        int baseTextColor = Color.BLACK;
        /** 上次应用的可读性状态位，没变就跳过（滚动时高频调用） */
        int lastReadState = Integer.MIN_VALUE;

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            name = itemView.findViewById(R.id.app_name);
            recentDot = itemView.findViewById(R.id.recent_dot);
            keyBadge = itemView.findViewById(R.id.key_badge);
        }
    }
}
