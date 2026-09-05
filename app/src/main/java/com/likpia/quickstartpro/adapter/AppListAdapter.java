package com.likpia.quickstartpro.adapter;

import android.graphics.Color;
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

import com.likpia.quickstartpro.R;
import com.likpia.quickstartpro.model.AppEntry;

import java.util.ArrayList;
import java.util.List;

/** 应用列表 RecyclerView Adapter，支持长按回调 + 匹配文字高亮 */
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.VH> {

    public interface OnAppClickListener {
        void onAppClick(AppEntry entry);
    }
    public interface OnAppLongClickListener {
        boolean onAppLongClick(AppEntry entry, View anchor);
    }

    private final List<AppEntry> items = new ArrayList<>();
    private OnAppClickListener clickListener;
    private OnAppLongClickListener longClickListener;

    /** 当前查询串（用于高亮），可为空 */
    private String highlightQuery = "";

    /** 用户自定义字体颜色，0 表示使用默认 */
    private int fontColor = 0;

    /** 是否显示最近更新圆点 */
    private boolean showRecentDot = true;

    /** 当前列数，用于自适应图标/文字大小 */
    private int columnCount = 3;

    public void setOnAppClickListener(OnAppClickListener l) { this.clickListener = l; }
    public void setOnAppLongClickListener(OnAppLongClickListener l) { this.longClickListener = l; }

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
        if (e.icon != null) h.icon.setImageDrawable(e.icon);

        // 让每个方格呈正方形：高度 = 单元格宽度
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
    }

    /**
     * 让每个方格填满且比例协调：
     * - 宽度由 GridLayoutManager 决定（屏幕宽 / 列数）
     * - 高度设为宽度的 1.3 倍（竖屏下方格略高，放得下图标+文字）
     * - 图标大小 = 单元格宽度的 65%
     */
    private void updateItemSquareSize(VH h) {
        // 用屏幕宽度计算单元格宽度（parent.getWidth() 在首次绑定时可能为 0）
        int screenWidth = h.itemView.getResources().getDisplayMetrics().widthPixels;
        int cellWidth = screenWidth / columnCount;
        if (cellWidth > 0) {
            // 高度 = 宽度 × 1.3，竖屏下方格更舒展
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            lp.height = (int) (cellWidth * 1.3);
            h.itemView.setLayoutParams(lp);

            // 图标占单元格宽度的 65%
            int iconSize = (int) (cellWidth * 0.65);
            ViewGroup.LayoutParams iconLp = h.icon.getLayoutParams();
            iconLp.width = iconSize;
            iconLp.height = iconSize;
            h.icon.setLayoutParams(iconLp);
        }
        // 文字大小根据列数调整
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

    /**
     * 当用户输入的是字母查询（非 T9 数字）时，对匹配到的子串做高亮；
     * 数字 T9 查询下不做高亮（因为匹配的是拼音，而非字面）。
     */
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
        VH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            name = itemView.findViewById(R.id.app_name);
            recentDot = itemView.findViewById(R.id.recent_dot);
        }
    }
}
