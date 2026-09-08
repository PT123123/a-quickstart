package com.quickstart;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.quickstart.adapter.AppListAdapter;
import com.quickstart.model.AppEntry;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 单个分类页面：内部持有一个 RecyclerView + AppListAdapter。
 *
 * 设计要点：
 *  1. 必须放在 Fragment 里供 ViewPager2 使用 FragmentStateAdapter，
 *     否则 ViewPager2 内部嵌套 RecyclerView 时 RV 会拦截横向滑动事件，
 *     导致左右滑动切页失败（这是 ViewPager2 连续滑动过渡的硬性前提）。
 *  2. 数据由外部 MainActivity 通过 {@link #setData(List)} 推送，避免 Fragment 自己
 *     持有全局状态。Activity 重建时新 Fragment 会重新订阅。
 *  3. 高亮 query / 字体颜色 / 列表动画等设置通过 Adapter 实例直接写入，
 *     Adapter 本身是无状态的，可以安全地在多页间共享相同 Adapter 实例。
 */
public class CategoryPageFragment extends Fragment {

    private static final String ARG_PAGE_INDEX = "page_index";

    /** 外部回调接口：点击 / 长按应用 */
    public interface Callbacks {
        void onAppClicked(AppEntry entry);
        boolean onAppLongClicked(AppEntry entry, View anchor);
    }

    private RecyclerView recycler;
    private AppListAdapter adapter;
    private int columnCount = 3;
    private String pendingAnimatorValue = "off";

    /** Activity 的弱引用，避免内存泄漏 */
    private WeakReference<Callbacks> callbacksRef;

    public static CategoryPageFragment newInstance(int pageIndex) {
        CategoryPageFragment f = new CategoryPageFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_PAGE_INDEX, pageIndex);
        f.setArguments(b);
        return f;
    }

    /** 注入外部回调（点击/长按）。一般在 MainActivity.onCreate 内创建 Fragment 后调用。 */
    public void setCallbacks(Callbacks callbacks) {
        this.callbacksRef = new WeakReference<>(callbacks);
    }

    /** 注入共享的 AppListAdapter 实例，使每页的内容可以由外部统一控制。 */
    public void setAdapter(AppListAdapter sharedAdapter) {
        this.adapter = sharedAdapter;
        if (recycler != null) {
            recycler.setAdapter(sharedAdapter);
            applyColumnCount();
        }
    }

    /** 推送当前分类筛选后的应用列表（null 表示清空） */
    public void setData(List<AppEntry> data) {
        if (adapter != null) {
            adapter.submit(data);
        }
    }

    /** 设置高亮关键字（T9 搜索词） */
    public void setHighlightQuery(String q) {
        if (adapter != null) adapter.setHighlightQuery(q);
    }

    /** 设置列数 */
    public void setColumnCount(int n) {
        this.columnCount = Math.max(1, n);
        applyColumnCount();
    }

    /** 设置字体颜色 */
    public void setFontColor(int color) {
        if (adapter != null) adapter.setFontColor(color);
    }

    /** 设置最近应用红点显示 */
    public void setShowRecentDot(boolean show) {
        if (adapter != null) adapter.setShowRecentDot(show);
    }

    /** 应用列表动画时长（"off" 或毫秒数） */
    public void applyListAnimation(String value) {
        pendingAnimatorValue = value;
        if (recycler == null) return;
        applyAnimatorToRecycler(value);
    }

    private void applyAnimatorToRecycler(String value) {
        if ("off".equals(value)) {
            recycler.setItemAnimator(null);
        } else {
            try {
                long duration = Long.parseLong(value);
                androidx.recyclerview.widget.DefaultItemAnimator animator =
                        new androidx.recyclerview.widget.DefaultItemAnimator();
                animator.setMoveDuration(duration);
                animator.setAddDuration(duration);
                animator.setRemoveDuration(duration);
                animator.setChangeDuration(duration);
                recycler.setItemAnimator(animator);
            } catch (NumberFormatException e) {
                recycler.setItemAnimator(null);
            }
        }
    }

    /** 触发列表角标手势（外部按下某个数字键时调用） */
    public void notifyBadgeGesture(int position) {
        if (adapter != null && position < adapter.getItemCount()) {
            // 角标点击逻辑由外部根据 filtered 列表自行处理；
            // 这里保留空实现以便未来扩展。
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.item_category_page, container, false);
        recycler = root.findViewById(R.id.page_recycler);
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), columnCount));
        applyAnimatorToRecycler(pendingAnimatorValue);
        if (adapter != null) {
            recycler.setAdapter(adapter);
            adapter.setOnAppClickListener(entry -> {
                Callbacks cb = callbacksRef != null ? callbacksRef.get() : null;
                if (cb != null) cb.onAppClicked(entry);
            });
            adapter.setOnAppLongClickListener((entry, anchor) -> {
                Callbacks cb = callbacksRef != null ? callbacksRef.get() : null;
                return cb != null && cb.onAppLongClicked(entry, anchor);
            });
        }
        return root;
    }

    private void applyColumnCount() {
        if (recycler == null) return;
        RecyclerView.LayoutManager lm = recycler.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            ((GridLayoutManager) lm).setSpanCount(columnCount);
        } else {
            recycler.setLayoutManager(new GridLayoutManager(requireContext(), columnCount));
        }
    }

    /** 列表瞬间滚回顶部（回到启动器时调用，保证第一行可见） */
    public void scrollToTop() {
        if (recycler != null) {
            recycler.scrollToPosition(0);
        }
    }

    /** 提供外部查询 RecyclerView（用于下拉悬停等跨页手势） */
    @Nullable
    public RecyclerView getRecyclerView() {
        return recycler;
    }

    /** 当前页索引 */
    public int getPageIndex() {
        Bundle b = getArguments();
        return b == null ? 0 : b.getInt(ARG_PAGE_INDEX, 0);
    }
}
