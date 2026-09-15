# Build the APK manually (no Gradle, no AndroidX).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -SdkRoot "C:\Android\sdk" -JavaHome "C:\Program Files\Java\jdk-17"
#
# Requirements: JDK 11+ (with javac/keytool) and Android SDK (build-tools + one platform).
# A signing keystore is created automatically on first run (keystore/mockloc.jks).

param(
    [string]$SdkRoot,
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function NewestDir($path) {
    if (-not (Test-Path $path)) { return $null }
    return (Get-ChildItem $path -Directory | Sort-Object Name -Descending | Select-Object -First 1).Name
}

# ---------- 定位 JDK ----------
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $JavaHome = $env:JAVA_HOME
    } else {
        $jc = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($jc) {
            $JavaHome = Split-Path (Split-Path $jc.Source -Parent) -Parent
        }
    }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw "javac not found. Pass -JavaHome <JDK folder> or set JAVA_HOME."
}
$env:JAVA_HOME = $JavaHome
$java = Join-Path $JavaHome 'bin'

# ---------- 定位 Android SDK ----------
if (-not $SdkRoot) {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        $SdkRoot = $env:ANDROID_SDK_ROOT
    } elseif ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        $SdkRoot = $env:ANDROID_HOME
    }
}
if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) {
    throw "Android SDK not found. Pass -SdkRoot <sdk folder> or set ANDROID_SDK_ROOT."
}
$btVer   = if ($env:BT_VERSION) { $env:BT_VERSION } else { NewestDir (Join-Path $SdkRoot 'build-tools') }
$platVer = if ($env:PLATFORM_VERSION) { $env:PLATFORM_VERSION } else { NewestDir (Join-Path $SdkRoot 'platforms') }
if (-not $btVer)   { throw "No build-tools found under $SdkRoot\build-tools" }
if (-not $platVer) { throw "No platform found under $SdkRoot\platforms" }

$bt       = Join-Path $SdkRoot "build-tools\$btVer"
$platform = Join-Path $SdkRoot "platforms\$platVer\android.jar"

$root  = $PSScriptRoot
$app   = Join-Path $root 'app'
$build = Join-Path $root 'build'

$minSdk    = 21
$targetSdk = 34
$verCode   = 5
$verName   = '1.5'

$keystore = Join-Path $root 'keystore\mockloc.jks'
$ksPass   = 'mockloc123'
$ksAlias  = 'mockloc'

Step "toolchain: JDK=$JavaHome  build-tools=$btVer  platform=$platVer"

Step 'clean build dir'
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, (Join-Path $build 'gen'), (Join-Path $build 'classes'), (Join-Path $build 'dex') | Out-Null

Step 'aapt2 compile (resources)'
& (Join-Path $bt 'aapt2.exe') compile --no-crunch --dir (Join-Path $app 'res') -o (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Step 'aapt2 link (R.java + unsigned apk)'
& (Join-Path $bt 'aapt2.exe') link `
    -o (Join-Path $build 'app-unsigned.apk') `
    -I $platform `
    --manifest (Join-Path $app 'AndroidManifest.xml') `
    --java (Join-Path $build 'gen') `
    --min-sdk-version $minSdk `
    --target-sdk-version $targetSdk `
    --version-code $verCode `
    --version-name $verName `
    --auto-add-overlay `
    (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Step 'javac'
$sources = @()
$sources += (Get-ChildItem (Join-Path $app 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& (Join-Path $java 'javac.exe') -encoding UTF-8 -source 11 -target 11 -nowarn `
    -classpath $platform -d (Join-Path $build 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Step 'd8 (classes.dex)'
$classes = (Get-ChildItem (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& (Join-Path $bt 'd8.bat') --min-api $minSdk --lib $platform --output (Join-Path $build 'dex') $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Step 'pack classes.dex into apk'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = Join-Path $build 'app-unsigned.apk'
$zip = [System.IO.Compression.ZipFile]::Open($apkPath, 'Update')
try {
    $old = $zip.GetEntry('classes.dex')
    if ($old -ne $null) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $build 'dex\classes.dex'), 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }

Step 'zipalign'
& (Join-Path $bt 'zipalign.exe') -f -p 4 $apkPath (Join-Path $build 'app-aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

if (-not (Test-Path $keystore)) {
    Step 'create signing keystore (first run only)'
    New-Item -ItemType Directory -Force -Path (Split-Path $keystore) | Out-Null
    & (Join-Path $java 'keytool.exe') -genkeypair -v `
        -keystore $keystore -alias $ksAlias -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass $ksPass -keypass $ksPass `
        -dname "CN=Mock Location, OU=Self Build, O=OpenSource, L=Zhengzhou, C=CN"
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

Step 'apksigner'
$out = Join-Path $build ("MockLocation-{0}.apk" -f $verName)
& (Join-Path $bt 'apksigner.bat') sign `
    --ks $keystore --ks-pass ("pass:" + $ksPass) --key-pass ("pass:" + $ksPass) `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $out (Join-Path $build 'app-aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Step 'verify signature'
& (Join-Path $bt 'apksigner.bat') verify --verbose $out

Step 'apk info'
& (Join-Path $bt 'aapt2.exe') dump badging $out | Select-String -Pattern "^package|sdkVersion|launchable-activity|application-label" | ForEach-Object { $_.Line }
Get-Item $out | Select-Object FullName, Length | Format-List
Write-Host "APK: $out" -ForegroundColor Green
