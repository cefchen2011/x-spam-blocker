# Build the X spam-blocker LSPosed module without Gradle/AGP.
# Toolchain: JDK 17 + Android SDK build-tools (aapt2 / d8 / zipalign / apksigner).
$ErrorActionPreference = 'Stop'

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path

# Toolchain comes from the environment when it is set, so the script is portable.
$JDK = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\tools\java_home\jdk17' }
$SDK = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

if (-not (Test-Path $SDK)) { throw "Android SDK not found. Set ANDROID_SDK_ROOT (looked in $SDK)." }

# Newest installed build-tools wins, so a newer SDK does not need a script edit.
$BT = Get-ChildItem (Join-Path $SDK 'build-tools') -Directory |
      Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending |
      Select-Object -First 1 -ExpandProperty FullName
if (-not $BT) { throw "No build-tools under $SDK\build-tools" }

$PLATFORM = Get-ChildItem (Join-Path $SDK 'platforms') -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'android.jar' } |
            Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $PLATFORM) { throw "No android.jar under $SDK\platforms" }

$OUT      = Join-Path $ROOT 'build'
$KEYSTORE = if ($env:XSBLOCK_KEYSTORE) { $env:XSBLOCK_KEYSTORE } else { Join-Path $env:USERPROFILE '.android\debug.keystore' }

Write-Host "SDK      : $SDK"
Write-Host "build-tools: $BT"
Write-Host "platform : $PLATFORM"
Write-Host "keystore : $KEYSTORE"

$env:JAVA_HOME = $JDK

Write-Host '== clean =='
if (Test-Path $OUT) { Remove-Item -Recurse -Force $OUT }
New-Item -ItemType Directory -Force -Path "$OUT\res", "$OUT\classes", "$OUT\dex", "$OUT\gen" | Out-Null

Write-Host '== aapt2 compile =='
& "$BT\aapt2.exe" compile --dir "$ROOT\res" -o "$OUT\res"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

$flat = Get-ChildItem "$OUT\res" -Recurse -Filter *.flat | ForEach-Object { $_.FullName }
Write-Host "   flat files: $($flat.Count)"

Write-Host '== aapt2 link =='
& "$BT\aapt2.exe" link -o "$OUT\base.apk" -I "$PLATFORM" `
    --manifest "$ROOT\AndroidManifest.xml" `
    -A "$ROOT\assets" `
    --java "$OUT\gen" `
    --min-sdk-version 29 --target-sdk-version 36 `
    --version-code 1 --version-name '1.0' `
    $flat
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# The Xposed API is compile-only: if de.robv.android.xposed.* ends up inside the
# module APK, LSPosed refuses to load the module ("The Xposed API classes are
# compiled into the module's APK"). Compile the stubs to a separate directory that
# is used purely as a classpath and never dexed.
Write-Host '== javac stubs =='
New-Item -ItemType Directory -Force -Path "$OUT\stubclasses" | Out-Null
$stubSources = Get-ChildItem "$ROOT\stubs" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$JDK\bin\javac.exe" -encoding UTF-8 -nowarn -source 8 -target 8 `
    -bootclasspath "$PLATFORM" -d "$OUT\stubclasses" $stubSources
if ($LASTEXITCODE -ne 0) { throw 'javac (stubs) failed' }

Write-Host '== javac module =='
$sources = @()
$sources += (Get-ChildItem "$ROOT\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem "$OUT\gen" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
Write-Host "   java sources: $($sources.Count)"
& "$JDK\bin\javac.exe" -encoding UTF-8 -nowarn -source 8 -target 8 `
    -bootclasspath "$PLATFORM" -cp "$OUT\stubclasses" -d "$OUT\classes" $sources
if ($LASTEXITCODE -ne 0) { throw 'javac (module) failed' }

Write-Host '== d8 =='
$classes = Get-ChildItem "$OUT\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --release --min-api 29 --lib "$PLATFORM" --output "$OUT\dex" $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '== guard: no xposed api in dex =='
# d8 keeps the *type references* in the string pool, so a raw string search is not a
# valid check. What matters is whether the module dex *defines* the API classes.
$defined = Get-ChildItem "$OUT\classes" -Recurse -Filter *.class |
    Where-Object { $_.FullName -match '\\de\\robv\\android\\xposed\\' }
if ($defined) {
    $defined | ForEach-Object { Write-Host "   LEAK: $($_.FullName)" }
    throw 'Xposed API classes were compiled into the module'
}
Write-Host '   ok: no de.robv.android.xposed class definitions in module classes'

Write-Host '== package =='
& "$JDK\bin\jar.exe" uf "$OUT\base.apk" -C "$OUT\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw 'jar update failed' }

Write-Host '== zipalign =='
& "$BT\zipalign.exe" -f -p 4 "$OUT\base.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '== sign =='
& "$BT\apksigner.bat" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out "$OUT\xspamblock.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& "$BT\apksigner.bat" verify --print-certs "$OUT\xspamblock.apk" | Select-Object -First 3

$apk = Get-Item "$OUT\xspamblock.apk"
Write-Host ""
Write-Host "BUILT: $($apk.FullName)  ($($apk.Length) bytes)"
