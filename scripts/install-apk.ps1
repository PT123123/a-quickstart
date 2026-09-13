# 快开启 - 安装 APK 到指定设备（供 justfile 调用）
#
# 用法：
#   install-apk.ps1 -Target phone            安装到手机
#   install-apk.ps1 -Target tablet           安装到平板
#   install-apk.ps1 -Target <adb序列号>       安装到指定序列号设备
#   install-apk.ps1 -List                    仅列出已连接设备及其识别结果
#
# 设备识别规则：
#   1. Target 直接匹配 adb 序列号时优先使用
#   2. 只有一台在线设备时直接安装到该设备
#   3. 多台设备时按特征识别：ro.build.characteristics 含 tablet，
#      或屏幕短边 >= 600dp（sw600dp 平板标准）判为平板，否则判为手机
param(
    [string]$Target = "phone",
    [string]$Apk = "app/build/outputs/apk/debug/app-debug.apk",
    [switch]$List
)

$ErrorActionPreference = "Stop"

# 在线（状态为 device）的序列号列表
function Get-OnlineSerials {
    @((adb devices) |
        Select-Object -Skip 1 |
        Where-Object { $_ -match "\tdevice\s*$" } |
        ForEach-Object { ($_ -split "\t")[0] })
}

# 读取设备型号并识别手机 / 平板
function Get-DeviceInfo([string]$Serial) {
    $chars = ((adb -s $Serial shell getprop ro.build.characteristics) -join " ")
    $sizeOut = ((adb -s $Serial shell wm size) -join " ")
    $densityOut = ((adb -s $Serial shell wm density) -join " ")
    $model = ((adb -s $Serial shell getprop ro.product.model) -join " ").Trim()

    $shortPx = 0
    $density = 160
    if ($sizeOut -match "(\d+)x(\d+)") {
        $shortPx = [Math]::Min([int]$Matches[1], [int]$Matches[2])
    }
    if ($densityOut -match "(\d+)") {
        $density = [int]$Matches[1]
    }
    $shortDp = if ($density -gt 0) { $shortPx * 160.0 / $density } else { 0 }

    $isTablet = ($chars -match "tablet") -or ($shortDp -ge 600)

    [pscustomobject]@{
        Serial = $Serial
        Model  = $model
        Kind   = $(if ($isTablet) { "tablet" } else { "phone" })
    }
}

$serials = Get-OnlineSerials
$infos = @($serials | ForEach-Object { Get-DeviceInfo $_ })

# -List：仅打印设备清单
if ($List) {
    if ($infos.Count -eq 0) {
        Write-Host "没有已连接的设备"
        exit 0
    }
    $infos | ForEach-Object {
        Write-Host ("{0}  [{1}]  {2}" -f $_.Serial, $_.Kind, $_.Model)
    }
    exit 0
}

if (-not (Test-Path $Apk)) {
    Write-Host "未找到 APK：$Apk （请先运行 just build）" -ForegroundColor Red
    exit 1
}

if ($infos.Count -eq 0) {
    Write-Host "没有已连接的设备（请检查 adb devices / USB 调试）" -ForegroundColor Red
    exit 1
}

# 解析目标设备：序列号 > 唯一设备 > phone/tablet 语义匹配
$dev = $infos | Where-Object { $_.Serial -eq $Target } | Select-Object -First 1
if (-not $dev -and $infos.Count -eq 1) { $dev = $infos[0] }
if (-not $dev) {
    $matched = @($infos | Where-Object { $_.Kind -eq $Target })
    if ($matched.Count -eq 1) { $dev = $matched[0] }
}

if ($dev) {
    Write-Host ("安装到 {0}  [{1}]  {2}" -f $dev.Serial, $dev.Kind, $dev.Model) -ForegroundColor Cyan
    adb -s $dev.Serial install -r $Apk
    exit $LASTEXITCODE
}

# 未匹配：打印设备清单并提示
$kindCount = @($infos | Where-Object { $_.Kind -eq $Target }).Count
if ($kindCount -gt 1) {
    Write-Host "找到多台 '$Target'，请用序列号指定：" -ForegroundColor Red
} else {
    Write-Host "未找到匹配 '$Target' 的设备。当前在线设备：" -ForegroundColor Red
}
$infos | ForEach-Object {
    Write-Host ("  {0}  [{1}]  {2}" -f $_.Serial, $_.Kind, $_.Model) -ForegroundColor Yellow
}
Write-Host "按序列号安装：just install <序列号>"
exit 1
