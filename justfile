# 构建 debug APK（不安装）
build:
    ./gradlew assembleDebug

# 构建并安装 debug APK 到连接的设备
install:
    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

# 清理构建产物
clean:
    ./gradlew clean
