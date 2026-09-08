package com.quickstart.model;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import java.util.List;

/** 单个可启动应用的所有信息 */
public class AppEntry {
    public String label;                 // 显示名
    public String packageName;           // 包名
    public String activityName;          // 启动 Activity 全限定名
    public Drawable icon;                // 图标
    public List<String> fingerprints;     // 预计算的 T9 指纹列表
    public Intent launchIntent;          // 缓存的启动 Intent（由 PackageManager 构造）
    public boolean recentlyUpdated;      // 是否是最近更新的应用

    // 新增：预计算的增强指纹，用于混合输入匹配
    public List<String> enhancedPatterns;

    public AppEntry(String label, String packageName, String activityName, List<String> fingerprints) {
        this.label = label;
        this.packageName = packageName;
        this.activityName = activityName;
        this.fingerprints = fingerprints;
    }
}
