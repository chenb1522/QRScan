# 自动安装 APK：MIUI 的 "USB 安装提示" 对话框用 uiautomator 定位后点击。
# 路径全部相对脚本位置推导，可用 ANDROID_HOME 覆盖 SDK 位置。
$ErrorActionPreference = 'Continue'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$root = Split-Path -Parent $scriptDir
$apk = Join-Path $root "out\QRScan-v1.0.apk"

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"

$outDir = Join-Path $root "out"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
$log = Join-Path $outDir "install.log"
$errLog = Join-Path $outDir "install.err"
$dlg = Join-Path $outDir "dlg.xml"

if (-not (Test-Path $apk)) { throw "apk not found: $apk" }
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }
Remove-Item $log -ErrorAction SilentlyContinue

$argList = @('install', '-r', '-g', $apk, '--no-streaming')
$p = Start-Process -FilePath $adb -ArgumentList $argList -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $errLog -WindowStyle Hidden

$clicked = $false
for ($i = 0; $i -lt 25; $i++) {
    Start-Sleep -Milliseconds 600
    & $adb shell uiautomator dump /sdcard/dlg.xml 2>&1 | Out-Null
    & $adb pull /sdcard/dlg.xml $dlg 2>&1 | Out-Null
    if (-not (Test-Path $dlg)) { continue }
    $x = Get-Content $dlg -Raw -Encoding UTF8
    $m = [regex]::Match($x, '<node[^>]*text="继续安装"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
        $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
        & $adb shell input tap $cx $cy | Out-Null
        Write-Host "clicked 继续安装 at $cx,$cy"
        $clicked = $true
        break
    }
    if ($p.HasExited) { break }
}

for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    if ($p.HasExited) { break }
}
Start-Sleep -Milliseconds 500
Get-Content $log -ErrorAction SilentlyContinue
$path = (& $adb shell "pm path com.qrscan.app" 2>&1) -join ""
Write-Host "pm path: $path"
Write-Host ("clicked={0}" -f $clicked)