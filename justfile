# 构建 debug APK（不安装）
build:
    ./gradlew assembleDebug

# 安装已构建的 debug APK 到连接的设备（不重新构建）
install:
    adb install -r app/build/outputs/apk/debug/app-debug.apk

# 清理构建产物
clean:
    ./gradlew clean
