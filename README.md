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

### 快跳过（开屏广告自动跳过）
设置 →「跳过」Tab，借鉴 [gkd](https://github.com/gkd-kit/gkd)、[Android-Touch-Helper](https://github.com/zfdang/Android-Touch-Helper)、[SKIP](https://github.com/GuoXiCheng/SKIP) 三种思路的本地规则引擎，**总开关可随时关闭**（关闭后即使无障碍服务开启也不动作）：

- **方法一 · 关键字匹配**：点击文本/描述含关键字的按钮，内置「跳过 / Skip / 我知道了」等，支持自定义关键字增删
- **方法二 · 控件匹配**：「跳过」文字与按钮分离时点击其最近的可点击父控件；同时点击控件 ID 含 `skip` 的按钮
- **方法三 · 坐标点击兜底**：前两种没命中时模拟点击屏幕指定百分比位置（默认右上角，Android 7.0+）
- **生效时间窗**：默认仅应用打开后 10 秒内生效，避免误触普通界面的「跳过」按钮；单次应用打开最多点击 4 次，点击后进入冷却
- **跳过提示**：每次成功跳过时在屏幕下方显示蓝色提示气泡（应用名 + 命中方式，可关闭）。使用无障碍服务专属悬浮窗（`TYPE_ACCESSIBILITY_OVERLAY`），无需悬浮窗权限、不受系统后台限制
- **永不点击自身与系统界面**：以当前活动窗口所属应用判断，本应用设置页和系统设置、通知栏等系统界面（会显示「快跳过」等服务字样）不会触发
- 首次使用需在系统设置中开启无障碍服务（设置页内有状态显示与直达入口）

### 其他
- **二维码配置传送**：设置 → 导入/导出配置 → 扫码传送/扫码接收。一台手机分帧轮播二维码，另一台相机连续扫码，收齐校验后一键导入，全程无需联网；剪贴板导出/导入与二维码共用同一份全量配置（含界面设置、数字键绑定、隐藏应用）
- 定时刷新应用列表（30 分钟间隔）
- 分类筛选（最近搜索 / 最近使用 / 最近安装 / 社交 / 影音 / 交通出行 / 实用工具 / 游戏 / 购物 / 理财）
  - **最近搜索**：按时间倒序列出通过 T9 搜索启动过的应用，长按该 chip 可清空历史
  - **最近使用**：启动过的应用按最近启动时间倒序
  - **最近安装**：在「最近更新范围」设置的时间范围内安装的应用
- 无障碍服务：快跳过（详见上方「快跳过」一节）

## 技术栈

- **语言**：Java
- **最低 SDK**：Android 5.0 (API 21)
- **目标 SDK**：Android 14 (API 34)
- **依赖**：
  - AndroidX AppCompat / RecyclerView / Preference
  - Material Components
  - [TinyPinyin](https://github.com/promeG/TinyPinyin) — 轻量汉字转拼音库
  - [ZXing core](https://github.com/zxing/zxing) — 二维码生成与解码（配置扫码传送）

## 参考项目

本项目基于以下开源项目/资源参考开发：

- **[TinyPinyin](https://github.com/promeG/TinyPinyin)** — 汉字转拼音库，用于实现中文应用的 T9 搜索匹配
- **[gkd](https://github.com/gkd-kit/gkd) / [Android-Touch-Helper](https://github.com/zfdang/Android-Touch-Helper) / [SKIP](https://github.com/GuoXiCheng/SKIP)** — 快跳过功能参考：关键字/控件/坐标三种跳过方式、开屏时间窗防误触
- AndroidX 官方组件（AppCompat、RecyclerView、Preference）
- Android 原生 T9 输入法逻辑（数字键到字母的映射：2=ABC, 3=DEF, 4=GHI, 5=JKL, 6=MNO, 7=PQRS, 8=TUV, 9=WXYZ）

## 构建

```bash
./gradlew assembleDebug
```

Windows 下推荐用 [just](https://github.com/casey/just)（基于 PowerShell）：

```bash
just build              # 构建 debug APK
just install phone      # 安装到手机
just install tablet     # 安装到平板
just devices            # 查看已连接设备
just build-install tablet  # 构建并安装到平板
just install-release phone # 安装 release APK 到手机
```

`install` 支持 `phone` / `tablet`（自动按设备特征和屏幕短边 ≥600dp 识别），也可直接传 adb 序列号；只连接一台设备时直接装到它。

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/quickstart/
│   ├── MainActivity.java          # 主界面（T9键盘、搜索、列表）
│   ├── SettingsActivity.java      # 设置页面
│   ├── QrTransferActivity.java    # 二维码配置传送（发送/接收）
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
│   │   ├── KeyBindingHelper.java  # 按键绑定工具
│   │   └── ConfigTransfer.java    # 配置全量导出/导入 + 二维码分帧协议
│   └── service/
│       └── AdSkipAccessibilityService.java  # 广告跳过服务
└── res/
    ├── layout/                    # 界面布局
    ├── values/                    # 颜色、字符串、数组
    ├── xml/                       # 偏好设置、无障碍配置
    └── drawable/                  # 背景、图标
```
