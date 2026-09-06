package com.quickstart;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 支持下拉悬停功能的 RecyclerView。
 * 当用户在列表顶部快速向下滑动时，触发悬停回调。
 */
public class PullDownRecyclerView extends RecyclerView {

    /** 下拉悬停监听器 */
    public interface OnPullDownListener {
        void onPullDown();
    }

    private OnPullDownListener pullDownListener;
    private float startY = 0;
    private boolean isTracking = false;
    private static final int PULL_DOWN_THRESHOLD = 100;

    public PullDownRecyclerView(Context context) {
        super(context);
    }

    public PullDownRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PullDownRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setOnPullDownListener(OnPullDownListener listener) {
        this.pullDownListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = e.getY();
                isTracking = true;
                break;
            case MotionEvent.ACTION_UP:
                if (isTracking) {
                    float dy = e.getY() - startY;
                    // 向下拉动超过阈值且列表已在顶部（无法继续上滚）
                    if (dy > PULL_DOWN_THRESHOLD && !canScrollVertically(-1)) {
                        if (pullDownListener != null) {
                            pullDownListener.onPullDown();
                        }
                    }
                    isTracking = false;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                isTracking = false;
                break;
        }
        return super.dispatchTouchEvent(e);
    }
}
