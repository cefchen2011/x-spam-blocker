param([int]$Wait = 25, [int]$Scrolls = 4)
$ErrorActionPreference = 'Continue'
$env:ANDROID_SERIAL = '2029b7f5'
$apk = 'C:\workspace\x-spam-blocker\build\xspamblock.apk'

Write-Host '== install =='
adb install -r $apk | Select-Object -Last 1

adb shell su -c "input keyevent 224" | Out-Null
Start-Sleep -Milliseconds 700
adb shell su -c "wm dismiss-keyguard" | Out-Null
Start-Sleep -Milliseconds 700

Write-Host '== restart X =='
adb logcat -c
adb shell am force-stop com.twitter.android
Start-Sleep -Seconds 2
adb shell monkey -p com.twitter.android -c android.intent.category.LAUNCHER 1 2>&1 | Select-Object -Last 1
Start-Sleep -Seconds $Wait

for ($i = 0; $i -lt $Scrolls; $i++) {
    adb shell su -c "input swipe 640 1900 640 500 250" | Out-Null
    Start-Sleep -Seconds 4
}
Start-Sleep -Seconds 6

Write-Host ''
Write-Host '== logcat lines containing XSBlock =='
adb logcat -d | Select-String -Pattern 'XSBlock' | Select-Object -First 120 | ForEach-Object { ($_.Line -replace '^.*?XSBlock','XSBlock') } | Out-String

Write-Host '== dumps =='
adb shell su -c "ls -la /sdcard/Android/data/com.twitter.android/files/ 2>/dev/null"
