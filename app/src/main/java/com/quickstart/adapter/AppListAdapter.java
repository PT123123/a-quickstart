package com.quickstart.adapter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

    private final List<AppEntry> items = new ArrayList<>();
    private OnAppClickListener clickListener;
    private OnAppLongClickListener longClickListener;
    private OnBadgeGestureListener badgeGestureListener;
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(4);
    private Drawable placeholderIcon;

    private String highlightQuery = "";
    private int fontColor = 0;
    private boolean showRecentDot = true;
    private int columnCount = 3;

    public void setOnAppClickListener(OnAppClickListener l) { this.clickListener = l; }
    public void setOnAppLongClickListener(OnAppLongClickListener l) { this.longClickListener = l; }
    public void setOnBadgeGestureListener(OnBadgeGestureListener l) { this.badgeGestureListener = l; }

    public void setHighlightQuery(String q) { this.highlightQuery = q == null ? "" : q; }
    public void setFontColor(int color) { this.fontColor = color; notifyDataSetChanged(); }
    public void setShowRecentDot(boolean show) { this.showRecentDot = show; notifyDataSetChanged(); }
    public void setColumnCount(int count) { this.columnCount = count; notifyDataSetChanged(); }

    public void submit(List<AppEntry> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
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
            if (placeholderIcon == null) {
                placeholderIcon = h.itemView.getContext().getPackageManager().getDefaultActivityIcon();
            }
            h.icon.setImageDrawable(placeholderIcon);
            final int pos = position;
            final View itemView = h.itemView;
            iconExecutor.execute(() -> {
                AppLoader.loadIconForEntry(itemView.getContext(), e);
                itemView.post(() -> {
                    if (pos < items.size() && items.get(pos) == e) {
                        notifyItemChanged(pos, "icon");
                    }
                });
            });
        }

        updateItemSquareSize(h);
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

        // 位置数字角标：前10个结果显示 1-9, 0
        if (h.keyBadge != null) {
            if (position < 10) {
                String badgeText = position == 9 ? "0" : String.valueOf(position + 1);
                h.keyBadge.setText(badgeText);
                h.keyBadge.setVisibility(View.VISIBLE);
                setupBadgeGesture(h.keyBadge, position);
            } else {
                h.keyBadge.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 为角标设置手势：使用与数字键相反的手势触发回调。
     * - 全局设为"长按"时，数字键用长按，角标用上滑
     * - 全局设为"上滑"时，数字键用上滑，角标用长按
     */
    private void setupBadgeGesture(TextView badge, int position) {
        String gesture = readBadgeGesture(badge.getContext());
        final Handler[] handler = {null};
        final boolean[] gestureFired = {false};
        final float[] startY = {0};
        final int[] posCopy = {position};

        if ("swipe_up".equals(gesture)) {
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
                            fireBadgeGesture(posCopy[0]);
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
                fireBadgeGesture(posCopy[0]);
                return true;
            });
        }
    }

    private void fireBadgeGesture(int position) {
        if (badgeGestureListener != null) {
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
        if (payloads.contains("icon")) {
            AppEntry e = items.get(position);
            if (e.icon != null) h.icon.setImageDrawable(e.icon);
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
        if (highlightQuery.isEmpty() || highlightQuery.matches("\\d+")) {
            return label;
        }
        String lower = label.toLowerCase();
        String q = highlightQuery.toLowerCase();
        int idx = lower.indexOf(q);
        if (idx < 0) return label;
        SpannableString span = new SpannableString(label);
        span.setSpan(new ForegroundColorSpan(Color.parseColor("#1565C0")),
                idx, idx + q.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final View recentDot;
        final TextView keyBadge;
        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            name = itemView.findViewById(R.id.app_name);
            recentDot = itemView.findViewById(R.id.recent_dot);
            keyBadge = itemView.findViewById(R.id.key_badge);
        }
    }
}
