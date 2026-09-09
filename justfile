# 构建 release APK（不安装）
build-release:
    ./gradlew assembleRelease

# 安装已构建的 release APK 到连接的设备（不重新构建）
install-release:
    adb install -r app/build/outputs/apk/release/app-release.apk

# 构建 debug APK（不安装）
build:
    ./gradlew assembleDebug

# 安装已构建的 debug APK 到连接的设备（不重新构建）
install:
    adb install -r app/build/outputs/apk/debug/app-debug.apk

# 构建并安装 debug APK
build-install:
    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

# 清理构建产物
clean:
    ./gradlew clean

# 运行单元测试（T9Matcher 等 JVM 测试）
test:
    ./gradlew testDebugUnitTest
