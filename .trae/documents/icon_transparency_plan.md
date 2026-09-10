# 图标透明度设置 实现计划

## Repository Research（调研结论）

- **图标渲染入口唯一**：主界面所有应用网格图标（搜索结果页 + 各分类页）都由 [AppListAdapter.java](file:///c:/Users/ted/Desktop/a-start/kuaishouqi-pro/app/src/main/java/com/quickstart/adapter/AppListAdapter.java) 渲染，对应 [item_app.xml](file:///c:/Users/ted/Desktop/a-start/kuaishouqi-pro/app/src/main/res/layout/item_app.xml) 中的 `ImageView @id/app_icon`。搜索结果用 `adapter`，每个分类页用 `pageAdapters` 中的同类型实例，Fragment 通过 [CategoryPageFragment.java](file:///c:/Users/ted/Desktop/a-start/kuaishouqi-pro/app/src/main/java/com/quickstart/CategoryPageFragment.java) 持有共享 adapter。
- **设置体系**：所有设置存于 SharedPreferences `"settings"`；设置页 [SettingsActivity.java](file:///c:/Users/ted/Desktop/a-start/kuaishouqi-pro/app/src/main/java/com/quickstart/SettingsActivity.java) 用 `PreferenceFragmentCompat` 加载 `res/xml/prefs_*.xml`，「显示」Tab 对应 [prefs_display.xml](file:///c:/Users/ted/Desktop/a-start/kuaishouqi-pro/app/src/main/res/xml/prefs_display.xml)。
- **设置传播模式**（以字体颜色为参照）：MainActivity 读取 SP → 调用 adapter 的 setter（如 `setFontColor`，内部 `notifyDataSetChanged()`）→ 经 `updateAllFragments(...)` 同步到已创建 Fragment。adapter 初始化点有三处：`onCreate`（主 adapter + pageAdapters）、`rebuildTabsIfChanged()`（分类变化时重建 pageAdapters）、`CategoryPagerAdapter.createFragment()`（注入共享 adapter）。
- **设置生效时机**：`onResume()` 调 `rebuildTabsIfChanged()`，分类签名未变时提前 return，因此字体颜色等外观设置实际下次启动才生效。新设置将在 onResume 增加带变化检测的主动应用，做到从设置页返回即时生效。
- **图标缓存无影响**：[IconCache.java](file:///c:/Users/ted/Desktop/a-start/kuaishouqi-pro/app/src/main/java/com/quickstart/util/IconCache.java) / AppLoader 只缓存 Drawable；透明度作用于 `ImageView`（`View.setAlpha`），不修改 Drawable 本身，缓存与异步加载（payload `"icon"` 局部刷新只调 `setImageDrawable`，View alpha 不会被重置）均不受影响。
- **配置迁移无需改动**：[ConfigTransfer.java](file:///c:/Users/ted/Desktop/a-start/kuaishouqi-pro/app/src/main/java/com/quickstart/util/ConfigTransfer.java) 全量遍历 SP 导出/导入，新键自动纳入。
- **Preference 库版本**：`androidx.preference:preference:1.2.1`，支持 `SeekBarPreference` 及其 `app:min` / `app:showSeekBarValue` 属性。
- **不在范围内的图标**：数字键上的绑定小图标（`loadKeyBindingIcons`）、隐藏应用管理页、按键绑定管理页、长按菜单图标——属管理/指示用途，保持完全不透明。

## 已确认的决策

- 语义：**透明度 60% = alpha 0.4**（图标淡化到 40% 浓度）。设置项存「透明度百分比」：0 = 完全不透明（alpha 1.0），值越大图标越淡。
- 默认值：60（即 alpha 0.4）。
- 范围：**0–100**（用户确认允许调到 100% 全透明；此时图标不可见、仅应用名文字保留，文字不受透明度影响）。
- 控件：`SeekBarPreference` 滑块连续调节，滑块右侧实时显示百分比数值。
- SP 键名：`icon_transparency`（int）。

## Files and Modules（改动文件）

- `app/src/main/res/xml/prefs_display.xml`：新增「图标透明度」SeekBarPreference（放在「列表列数」之后）。
- `app/src/main/java/com/quickstart/adapter/AppListAdapter.java`：新增 `iconAlpha` 字段、`setIconAlpha(float)` setter；绑定时对 `h.icon` 应用 alpha。
- `app/src/main/java/com/quickstart/CategoryPageFragment.java`：新增 `setIconAlpha(float)` 委托方法（与 `setFontColor` 同模式）。
- `app/src/main/java/com/quickstart/MainActivity.java`：新增 `applyIconTransparency()`，在 `onCreate`、`rebuildTabsIfChanged()`、`onResume()` 三处接入；新增 `lastIconTransparency` 字段做变化检测。

## Implementation Steps（按依赖顺序）

1. **prefs_display.xml** 新增：
   ```xml
   <SeekBarPreference
       android:key="icon_transparency"
       android:title="图标透明度"
       android:summary="数值越大图标越淡（0 为不透明，100 为全透明），默认 60%"
       android:defaultValue="60"
       android:max="100"
       app:min="0"
       app:showSeekBarValue="true" />
   ```
   （标题/摘要沿用该文件现有硬编码中文风格，无需改 strings.xml。）

2. **AppListAdapter.java**：
   - 新增字段 `private float iconAlpha = 1f;`
   - 新增 setter（与 `setFontColor` 同模式）：
     ```java
     public void setIconAlpha(float alpha) { this.iconAlpha = alpha; notifyDataSetChanged(); }
     ```
   - 在 `onBindViewHolder(VH, int)` 全量绑定中，图标设置之后加 `h.icon.setAlpha(iconAlpha);`（payload 局部刷新路径不需重复设置——View alpha 是视图属性，`setImageDrawable` 不重置它；holder 复用时都会经过全量绑定，无遗漏）。

3. **CategoryPageFragment.java**：新增
   ```java
   /** 设置图标透明度（0f-1f） */
   public void setIconAlpha(float alpha) {
       if (adapter != null) adapter.setIconAlpha(alpha);
   }
   ```

4. **MainActivity.java**：
   - 新增字段 `private int lastIconTransparency = -1;`
   - 新增方法：
     ```java
     /** 应用图标透明度设置（存的是透明度百分比 0-100，alpha = (100-v)/100） */
    private void applyIconTransparency() {
        int v = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("icon_transparency", 60);
        v = Math.max(0, Math.min(100, v));
        if (v == lastIconTransparency) return;
        lastIconTransparency = v;
        float alpha = (100 - v) / 100f;
        adapter.setIconAlpha(alpha);
        for (AppListAdapter a : pageAdapters) a.setIconAlpha(alpha);
        updateAllFragments(f -> f.setIconAlpha(alpha));
    }
    ```
   - `onCreate`：在 `applyFontColor();`（约 192 行）旁加 `applyIconTransparency();`
   - `rebuildTabsIfChanged()`：在 `applyFontColor();`（约 1352 行）旁加 `applyIconTransparency();`（注意：重建后需让缓存失效以强制重应——重建后 adapter 是新实例，可在重建处把 `lastIconTransparency = -1;` 复位，或直接在重建循环里对新 adapter 调用 setter；采用复位字段方式，与 applyFontColor 调用并列即可）。
   - `onResume()`：在 `rebuildTabsIfChanged();` 之后加 `applyIconTransparency();`——从设置页返回即时生效；值未变时靠 `lastIconTransparency` 跳过，不会无谓刷新列表。

## Dependencies and Considerations（依赖与注意事项）

- 无需新增依赖库；无需改 arrays.xml / strings.xml / ConfigTransfer。
- `View.setAlpha` 只影响图标本身，不影响 itemView 的点击波纹（`?selectableItemBackground` 在 item 根布局上）、角标、红点。
- 透明度对所有分类页 + 搜索结果页一致生效（全部走 AppListAdapter）。
- 图标异步加载完成后的 payload 刷新不复位 alpha（视图属性保持）。
- 应用名文字颜色、背景等其他设置不受影响。

## Validation（验证）

1. 编译通过（`./gradlew assembleDebug` 或 IDE 诊断无错误）。
2. 清数据新装首启：主界面图标默认为 60% 透明（alpha 0.4）视觉效果，文字正常。
3. 设置 → 显示 →「图标透明度」：滑块显示当前值 60；拖到 0 返回主界面图标完全不透明；拖到 100 图标完全不可见、仅应用名文字清晰可点。
4. 从设置页返回主界面**即时生效**（无需重启）；杀进程重启后设置保持。
5. 搜索结果列表与各分类页图标透明度一致；滑动复用无个别图标透明度错乱。
6. 导出配置到剪贴板的 JSON 中包含 `icon_transparency` 键（自动）。

## Risks（风险与应对）

- **调到 100% 后图标完全不可见**：用户已确认允许该范围；整个 item 仍可点击、应用名文字不受透明度影响仍正常显示，不影响启动应用；摘要文案已说明「100 为全透明」，用户可自行拖回。
- **滑块数值语义被误解**（60 是透明度而非不透明度）：标题用「图标透明度」，摘要注明「0 为不透明，数值越大越淡」，与已确认决策一致。
- **onResume 每次回调触发列表刷新**：`lastIconTransparency` 变化检测确保值未变时直接跳过。
- **rebuildTabsIfChanged 重建 adapter 后 alpha 丢失**：重建路径中复位 `lastIconTransparency = -1` 并重新调用 `applyIconTransparency()`，新 adapter 实例会被正确设置。
