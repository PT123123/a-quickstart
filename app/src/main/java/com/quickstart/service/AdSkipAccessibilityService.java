package com.quickstart.service;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 广告跳过无障碍服务。
 *
 * 工作原理：
 *  - 监听 TYPE_WINDOW_CONTENT_CHANGED / TYPE_WINDOW_STATE_CHANGED 事件
 *  - 用迭代方式遍历当前窗口节点树，查找文本含"跳过"、"关闭"等关键词的可点击节点
 *  - 对第一个匹配节点执行 ACTION_CLICK
 *
 * 需要在 系统设置 → 无障碍 → 快启动Pro-广告跳过 中手动开启。
 */
public class AdSkipAccessibilityService extends AccessibilityService {

    private static final String[] SKIP_KEYWORDS = {
        "跳过", "跳过广告", "关闭", "关闭广告", "我知道了", "稍后再说",
        "不感兴趣", "Skip", "skip"
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 迭代 DFS，避免递归层数过深 + 节点回收泄漏
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            if (continueSearch(node)) {
                // 找到并点击后立刻结束
                try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } finally {
                    safeRecycleSubtree(stack, root);
                }
                return;
            }
            // 把子节点压栈
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        // 没找到 → 回收整棵树（root 已经在栈中处理完）
        root.recycle();
    }

    /** 判断节点是否命中关键词且可点击 */
    private boolean continueSearch(AccessibilityNodeInfo node) {
        if (!node.isClickable()) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && matches(text.toString())) return true;
        return desc != null && matches(desc.toString());
    }

    private boolean matches(String s) {
        for (String kw : SKIP_KEYWORDS) {
            if (s.contains(kw)) return true;
        }
        return false;
    }

    /** 回收栈中剩余节点 + root 自身 */
    private void safeRecycleSubtree(Deque<AccessibilityNodeInfo> stack, AccessibilityNodeInfo root) {
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.pop();
            if (n != root) n.recycle();
        }
        root.recycle();
    }

    @Override
    public void onInterrupt() {}
}
