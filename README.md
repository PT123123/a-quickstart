# 快开启 (kuaishouqi-pro)

一款基于 T9 九键键盘的 Android 应用启动器，支持拼音搜索、应用管理、快捷手势等功能。

## 功能特性

### T9 九键搜索
- 使用数字键 2-9 输入，支持拼音首字母和全拼搜索
- 例：输入 `577` 搜索"计算器"（jì suàn qì → jsq → 578）、`99` 搜索"微信"（wēi xīn → wx → 99）
- 支持英文应用名直接匹配

### 快捷手势
- **上滑数字键**：启动搜索列表对应位置的应用
- **长按数字键**：启动该键绑定的应用
- **可调节灵敏度**：上滑距离和长按时长均可在设置中调节

### 应用管理
- **长按应用**：打开 / 应用信息 / 卸载 / 隐藏
- **隐藏应用**：隐藏后不在列表显示，可在设置中恢复
- **数字键绑定**：为每个数字键绑定常用应用，一键启动

### 智能排序
- 综合使用频率、最近使用时间、匹配精确度进行权重排序
- 支持字母顺序、最近安装、使用频率等多种排序方式

### 个性化设置
- 主题模式（深色 / 浅色 / 跟随系统）
- 窗口大小（全屏 / 小窗口 / 中窗口）
- 列表列数（2-7 列）
- 背景颜色 / 字体颜色
- 最近更新圆点提示

### 其他
- 定时刷新应用列表（30 分钟间隔）
- 分类筛选（最近搜索 / 最近使用 / 最近安装 / 社交 / 影音 / 交通出行 / 实用工具 / 游戏 / 购物 / 理财）
  - **最近搜索**：按时间倒序列出通过 T9 搜索启动过的应用，长按该 chip 可清空历史
  - **最近使用**：启动过的应用按最近启动时间倒序
  - **最近安装**：在「最近更新范围」设置的时间范围内安装的应用
- 无障碍服务：自动跳过开屏广告

## 技术栈

- **语言**：Java
- **最低 SDK**：Android 5.0 (API 21)
- **目标 SDK**：Android 14 (API 34)
- **依赖**：
  - AndroidX AppCompat / RecyclerView / Preference
  - Material Components
  - [TinyPinyin](https://github.com/promeG/TinyPinyin) — 轻量汉字转拼音库

## 参考项目

本项目基于以下开源项目/资源参考开发：

- **[TinyPinyin](https://github.com/promeG/TinyPinyin)** — 汉字转拼音库，用于实现中文应用的 T9 搜索匹配
- AndroidX 官方组件（AppCompat、RecyclerView、Preference）
- Android 原生 T9 输入法逻辑（数字键到字母的映射：2=ABC, 3=DEF, 4=GHI, 5=JKL, 6=MNO, 7=PQRS, 8=TUV, 9=WXYZ）

## 构建

```bash
./gradlew assembleDebug
```

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/quickstart/
│   ├── MainActivity.java          # 主界面（T9键盘、搜索、列表）
│   ├── SettingsActivity.java      # 设置页面
│   ├── HiddenAppsActivity.java    # 已隐藏应用管理
│   ├── KeyBindingActivity.java    # 数字键绑定管理
│   ├── adapter/
│   │   └── AppListAdapter.java    # 应用列表适配器
│   ├── model/
│   │   └── AppEntry.java          # 应用数据模型
│   ├── util/
│   │   ├── T9Matcher.java         # T9 拼音匹配引擎
│   │   ├── AppLoader.java         # 应用加载器
│   │   ├── AppCache.java          # JSON 缓存
│   │   ├── FastCache.java         # 二进制缓存
│   │   ├── IconCache.java         # 图标缓存
│   │   └── KeyBindingHelper.java  # 按键绑定工具
│   └── service/
│       └── AdSkipAccessibilityService.java  # 广告跳过服务
└── res/
    ├── layout/                    # 界面布局
    ├── values/                    # 颜色、字符串、数组
    ├── xml/                       # 偏好设置、无障碍配置
    └── drawable/                  # 背景、图标
```
