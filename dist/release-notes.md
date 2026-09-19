# 快开启 (kuaishouqi) v3.0.1

Android 启动器，release 包，用于 Obtainium 自更新。

## 下载（Obtainium 永久直链）
- 最新版（固定名，推荐填这个）：`https://github.com/PT123123/a-quickstart/releases/latest/download/kuaishouqi.apk`
- 本版本存档：`kuaishouqi-3.0.1.apk`

> 签名与 v3.0.0 相同（`debug.keystore`），可直接覆盖安装，无需卸载。

## 本版更新
- **修复多音字搜索**：拼音引擎接入约 70 个常见多音字读音表（壳、长、行、乐、都、便、藏、什、散、率、旋、重、弹、率…），同一字按多个读音生成匹配指纹。最典型：**「贝壳找房」现在用 `ke`（23453）或 `qiao`（74246）都能搜到**，此前只能按默认读音 `qiao` 搜到。
- **新增 9 个数字键默认绑定**（仅当前位无绑定时写入，随时可覆盖，未安装的应用会在下次启动时补绑）：长按 1 = Clash、长按 7 = 微信、长按 8 = Twitter、长按 9 = 知乎。
- 首次启动会自动重建一次 T9 缓存（缓存格式升级），加载比平时略慢一次，之后恢复正常。

## 安装说明
- Android 9+ 需在「设置 → 安装未知应用」给浏览器/文件管理器授权。
- 覆盖安装 v3.0.0 及以后版本无需卸载（同一密钥）。

## 构建信息
- package: `com.quickstart`
- versionCode: `301`，versionName: `3.0.1`
- 签名 SHA256: `E0:BB:84:3A:91:92:A9:57:97:27:BE:09:08:35:40:A2:77:B1:5B:AC:96:7D:49:CF:26:A8:F3:0D:75:0A:64:00`
- APK sha256: `31df92e1f623a4082c2f490b8b900eeee4a8fb3ad5474ebdb82ed358dc9f278a`
