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

        // 根据列数自适应图标和文字大小
        updateItemSize(h);
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

    /** 根据列数动态调整图标和文字大小，让内容填满方格 */
    private void updateItemSize(VH h) {
        // 列数越多 → 图标越小；列数越少 → 图标越大
        // 2列: 56dp, 3列: 44dp, 4列: 36dp, 5列: 30dp
        int iconSize;
        float textSize;
        switch (columnCount) {
            case 2: iconSize = 56; textSize = 14f; break;
            case 3: iconSize = 44; textSize = 12f; break;
            case 4: iconSize = 36; textSize = 11f; break;
            case 5: iconSize = 30; textSize = 10f; break;
            default: iconSize = 44; textSize = 12f; break;
        }
        // 图标
        ViewGroup.LayoutParams iconLp = h.icon.getLayoutParams();
        int sizePx = (int) (iconSize * h.itemView.getResources().getDisplayMetrics().density);
        iconLp.width = sizePx;
        iconLp.height = sizePx;
        h.icon.setLayoutParams(iconLp);
        // 文字
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
