# =====================================================================
#  QRScan —— 无 Gradle 手工构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
#  用法:  pwsh -File .\build.ps1
# =====================================================================
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
# 构建环境：优先读环境变量，否则按 Windows 默认安装位置推断
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Java\jdk-17" }

# build-tools / platform 版本在 CI 机器上与本地不同，这里自动挑最新的
$btDirs = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^\d+(\.\d+)+$' } | Sort-Object { [version]$_.Name } -Descending
if (-not $btDirs) { throw "no build-tools found under $sdk\build-tools" }
$bt = $btDirs[0].FullName

$platDirs = Get-ChildItem (Join-Path $sdk "platforms") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^android-\d+(\.\d+)?$' -and (Test-Path (Join-Path $_.FullName "android.jar")) } |
    Sort-Object { [double]($_.Name -replace '^android-', '') } -Descending
if (-not $platDirs) { throw "no android platform found under $sdk\platforms" }
$platform = $platDirs[0].FullName
$androidJar = Join-Path $platform "android.jar"

$appName = "QRScan"
$pkg = "com.qrscan.app"
$minSdk = "23"
$targetSdk = "34"
# 版本号可用环境变量覆盖（CI 里用 run_number / tag 名）
$versionCode = if ($env:QRSCAN_VERSION_CODE) { $env:QRSCAN_VERSION_CODE } else { "1" }
$versionName = if ($env:QRSCAN_VERSION_NAME) { ($env:QRSCAN_VERSION_NAME -replace '^v', '') } else { "1.0" }

$out = Join-Path $root "out"
$gen = Join-Path $out "gen"
$obj = Join-Path $out "obj"
$dexDir = Join-Path $out "dex"
$resZip = Join-Path $out "res.zip"
$rawApk = Join-Path $out "app-raw.apk"
$alignedApk = Join-Path $out "app-aligned.apk"
$finalApk = Join-Path $out "$appName-v$versionName.apk"
$classesJar = Join-Path $out "classes.jar"
$coreJar = Join-Path $root "libs\core-3.3.3.jar"
$ks = Join-Path $root "qrscan.keystore"
# 本地自签名证书口令：默认 android，可用环境变量 QRSCAN_STORE_PASS 覆盖
$ksPass = if ($env:QRSCAN_STORE_PASS) { $env:QRSCAN_STORE_PASS } else { "android" }

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

function Step($msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}
function Check($code, $what) {
    if ($code -ne 0) {
        throw "$what failed with exit code $code"
    }
}

foreach ($tool in @("aapt2.exe", "zipalign.exe", "d8.bat", "apksigner.bat")) {
    $p = Join-Path $bt $tool
    if (-not (Test-Path $p)) { throw "missing build tool: $p" }
}
if (-not (Test-Path $androidJar)) { throw "missing android.jar: $androidJar" }
if (-not (Test-Path $coreJar)) { throw "missing zxing jar: $coreJar" }

if ($Clean) {
    Step "clean"
    foreach ($d in @($gen, $obj, $dexDir)) {
        if (Test-Path $d) { Remove-Item -Recurse -Force $d }
    }
    foreach ($f in @($resZip, $rawApk, $alignedApk, $classesJar, $finalApk)) {
        if (Test-Path $f) { Remove-Item -Force $f }
    }
}

Write-Host "sdk    : $sdk"
Write-Host "bt     : $(Split-Path $bt -Leaf)"
Write-Host "platform: $(Split-Path $platform -Leaf)"
Write-Host "jdk    : $jdk"
Write-Host ""

foreach ($d in @($out, $gen, $obj, $dexDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
}

# ---------------------------------------------------------------- 1. 资源
Step "aapt2 compile (res -> res.zip)"
& (Join-Path $bt "aapt2.exe") compile --dir (Join-Path $root "app\src\main\res") -o $resZip
Check $LASTEXITCODE "aapt2 compile"

# ---------------------------------------------------------------- 2. 链接清单
Step "aapt2 link (manifest + res -> $rawApk + R.java)"
& (Join-Path $bt "aapt2.exe") link `
    -o $rawApk `
    -I $androidJar `
    --manifest (Join-Path $root "app\src\main\AndroidManifest.xml") `
    -R $resZip `
    --java $gen `
    --min-sdk-version $minSdk `
    --target-sdk-version $targetSdk `
    --version-code $versionCode `
    --version-name $versionName `
    --auto-add-overlay
Check $LASTEXITCODE "aapt2 link"

# ---------------------------------------------------------------- 3. 编译 Java
Step "javac"
$rJava = Get-ChildItem -Path $gen -Recurse -Filter "R.java" | ForEach-Object { $_.FullName }
if (-not $rJava) { throw "R.java not generated" }
$sources = Get-ChildItem -Path (Join-Path $root "app\src\main\java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }
if (-not $sources) { throw "no java sources" }

$javacArgs = @(
    '-J-Duser.language=en',
    '-J-Duser.country=US',
    '-encoding', 'UTF-8',
    '--release', '11',
    '-nowarn',
    '-d', $obj,
    '-cp', "$androidJar;$coreJar"
) + $sources + $rJava

& (Join-Path $jdk "bin\javac.exe") @javacArgs
Check $LASTEXITCODE "javac"

# ---------------------------------------------------------------- 4. dex
Step "jar + d8 (-> classes.dex)"
if (Test-Path $classesJar) { Remove-Item -Force $classesJar }
& (Join-Path $jdk "bin\jar.exe") cf $classesJar -C $obj .
Check $LASTEXITCODE "jar"

Get-ChildItem -Path $dexDir -Filter "*.dex" -ErrorAction SilentlyContinue | Remove-Item -Force
# 用 R8（d8 + shrink）而不是裸 d8：裁掉 ZXing 里没用到的码制，dex 从 470KB 降到 257KB
$r8Jar = Join-Path $bt "lib\d8.jar"
& (Join-Path $jdk "bin\java.exe") -cp $r8Jar com.android.tools.r8.R8 `
    --release --min-api $minSdk --lib $androidJar `
    --pg-conf (Join-Path $root "app\proguard-rules.pro") `
    --output $dexDir $classesJar $coreJar
Check $LASTEXITCODE "R8"

$dexFile = Join-Path $dexDir "classes.dex"
if (-not (Test-Path $dexFile)) { throw "classes.dex not produced" }
Write-Host ("    classes.dex {0:N1} KB" -f ((Get-Item $dexFile).Length / 1KB))

# ---------------------------------------------------------------- 5. 打包 dex
Step "inject classes.dex into apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($rawApk, 'Update')
try {
    $old = $zip.GetEntry("classes.dex")
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $dexFile, "classes.dex",
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
}
finally {
    $zip.Dispose()
}

# ---------------------------------------------------------------- 6. 对齐
Step "zipalign"
if (Test-Path $alignedApk) { Remove-Item -Force $alignedApk }
& (Join-Path $bt "zipalign.exe") -p -f 4 $rawApk $alignedApk
Check $LASTEXITCODE "zipalign"

# ---------------------------------------------------------------- 7. 签名
Step "apksigner"
if (-not (Test-Path $ks)) {
    Write-Host "    creating signing key"
    & (Join-Path $jdk "bin\keytool.exe") -genkeypair `
        -keystore $ks -storepass $ksPass -keypass $ksPass `
        -alias qrscan -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=QRScan, OU=App, O=QRScan, C=CN"
    Check $LASTEXITCODE "keytool"
}
if (Test-Path $finalApk) { Remove-Item -Force $finalApk }
& (Join-Path $bt "apksigner.bat") sign `
    --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" --ks-key-alias qrscan `
    --min-sdk-version $minSdk `
    --out $finalApk $alignedApk
Check $LASTEXITCODE "apksigner sign"

Step "verify"
& (Join-Path $bt "apksigner.bat") verify --print-certs $finalApk
Check $LASTEXITCODE "apksigner verify"

$size = (Get-Item $finalApk).Length
Write-Host ""
Write-Host ("BUILD OK  ->  {0}  ({1:N1} KB)" -f $finalApk, ($size / 1KB)) -ForegroundColor Green
