# Safe Cross KR - Realtime Perception Flight Log Monitor
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Write-Host "=========================================================="
Write-Host " Safe Cross KR - Perception Flight Log Monitor"
Write-Host "=========================================================="

$device = adb devices | Select-String "device$"
if (-not $device) {
    Write-Host "[WAITING] Android device not connected via USB." -ForegroundColor Yellow
    Write-Host " 1. Connect Galaxy S25 Ultra to PC via USB cable." -ForegroundColor Yellow
    Write-Host " 2. Ensure Developer Options -> USB Debugging is turned ON." -ForegroundColor Yellow
    Write-Host " 3. Tap [Allow] on the phone when prompted for USB Debugging." -ForegroundColor Yellow
    Write-Host "Waiting for device to connect..." -ForegroundColor Green
}

adb wait-for-device
Write-Host "[CONNECTED] Device detected! Streaming traffic light logs..." -ForegroundColor Green
Write-Host "Press Ctrl+C to stop." -ForegroundColor Gray
Write-Host "----------------------------------------------------------"

adb logcat -v time -s SafeCrossFlight:V SafeCrossNav:V
