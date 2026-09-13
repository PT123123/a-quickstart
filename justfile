# 快开启 - 构建 / 安装脚本（Windows PowerShell）
#
# 常用命令：
#   just build              构建 debug APK
#   just build-release      构建 release APK
#   just install phone      安装 debug APK 到手机
#   just install tablet     安装 debug APK 到平板
#   just build-install      tablet   构建并安装到平板
#   just install-release    phone    安装 release APK 到手机
#   just devices            查看已连接设备（含手机/平板识别结果）
#   just clean / just test  清理 / 单元测试
#
# install 支持的设备参数：phone | tablet | adb 序列号（adb devices 查看）；
# 只连了一台设备时不分手机平板直接装到它。
set shell := ["powershell.exe", "-NoLogo", "-NoProfile", "-Command"]

debug_apk := "app/build/outputs/apk/debug/app-debug.apk"
release_apk := "app/build/outputs/apk/release/app-release.apk"
installer := "scripts/install-apk.ps1"

# 默认命令：列出所有可用命令
default:
    @just --list

# ===== 构建 =====

# 构建 debug APK（不安装）
build:
    .\gradlew.bat assembleDebug

# 构建 release APK（不安装）
build-release:
    .\gradlew.bat assembleRelease

# 清理构建产物
clean:
    .\gradlew.bat clean

# 运行单元测试（T9Matcher 等 JVM 测试）
test:
    .\gradlew.bat testDebugUnitTest

# ===== 设备 =====

# 查看已连接设备（含手机/平板识别结果）
devices:
    powershell -NoProfile -ExecutionPolicy Bypass -File {{installer}} -List

# ===== 安装 =====

# 安装 debug APK 到指定设备：just install phone / just install tablet
install device="phone":
    powershell -NoProfile -ExecutionPolicy Bypass -File {{installer}} -Target {{device}} -Apk {{debug_apk}}

# 构建并安装 debug APK：just build-install tablet
build-install device="phone":
    .\gradlew.bat assembleDebug
    powershell -NoProfile -ExecutionPolicy Bypass -File {{installer}} -Target {{device}} -Apk {{debug_apk}}

# 安装已构建的 release APK：just install-release phone
install-release device="phone":
    powershell -NoProfile -ExecutionPolicy Bypass -File {{installer}} -Target {{device}} -Apk {{release_apk}}
