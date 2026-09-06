package com.quickstart.adapter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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
    private boolean showRecentDot = true;
    private int columnCount = 3;
    /** 角标手势模式，每次 submit 时重新读取一次，避免每个 item 重绑都查 SharedPreferences */
    private String badgeGestureMode;

    public void setOnAppClickListener(OnAppClickListener l) { this.clickListener = l; }
    public void setOnAppLongClickListener(OnAppLongClickListener l) { this.longClickListener = l; }
    public void setOnBadgeGestureListener(OnBadgeGestureListener l) { this.badgeGestureListener = l; }

    public void setHighlightQuery(String q) { this.highlightQuery = q == null ? "" : q; }
    public void setFontColor(int color) { this.fontColor = color; notifyDataSetChanged(); }
    public void setShowRecentDot(boolean show) { this.showRecentDot = show; notifyDataSetChanged(); }
    public void setColumnCount(int count) { this.columnCount = count; notifyDataSetChanged(); }

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
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppEntry e = items.get(position);
        h.name.setText(buildHighlightedLabel(e));
        if (fontColor != 0) h.name.setTextColor(fontColor);

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

        // 尺寸只在列数变化或首次绑定时应用，避免每次重绑都触发 requestLayout
        if (h.appliedColumnCount != columnCount) {
            updateItemSquareSize(h);
            h.appliedColumnCount = columnCount;
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

    private void updateItemSquareSize(VH h) {
        int screenWidth = h.itemView.getResources().getDisplayMetrics().widthPixels;
        int cellWidth = screenWidth / columnCount;
        if (cellWidth > 0) {
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            lp.height = (int) (cellWidth * 1.3);
            h.itemView.setLayoutParams(lp);

            int iconSize = (int) (cellWidth * 0.65);
            ViewGroup.LayoutParams iconLp = h.icon.getLayoutParams();
            iconLp.width = iconSize;
            iconLp.height = iconSize;
            h.icon.setLayoutParams(iconLp);
        }
        float textSize;
        switch (columnCount) {
            case 2: textSize = 14f; break;
            case 3: textSize = 12f; break;
            case 4: textSize = 11f; break;
            case 5: textSize = 10f; break;
            default: textSize = 12f; break;
        }
        h.name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize);
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

        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            name = itemView.findViewById(R.id.app_name);
            recentDot = itemView.findViewById(R.id.recent_dot);
            keyBadge = itemView.findViewById(R.id.key_badge);
        }
    }
}
