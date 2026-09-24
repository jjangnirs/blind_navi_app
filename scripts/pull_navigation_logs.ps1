# SafeCross KR - Pull Navigation Flight Logs from Connected Device (ADB or MTP)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
param(
    [string]$OutputDir = "scratch"
)

Write-Host "=========================================================="
Write-Host " Safe Cross KR - Pull Navigation Flight Logs"
Write-Host "=========================================================="

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

# 1. Try ADB first
$adbDevices = adb devices 2>$null | Select-String "device$"
if ($adbDevices) {
    Write-Host "[ADB] Device detected via ADB! Pulling logs..." -ForegroundColor Green
    $remotePath = "/sdcard/Android/data/kr.safecross.mobile/files/logs/navigation_flight.log"
    $localFile = Join-Path $OutputDir "navigation_flight.log"
    
    adb pull $remotePath $localFile
    if (Test-Path $localFile) {
        Write-Host "[SUCCESS] Pulled $localFile ($(Get-Item $localFile).Length bytes)" -ForegroundColor Green
        exit 0
    }
}

# 2. Fallback to Windows Shell MTP
Write-Host "[MTP] Checking device via Windows MTP..." -ForegroundColor Yellow
$shell = New-Object -ComObject Shell.Application
$drives = $shell.Namespace(17).Items()
$found = $false

foreach ($item in $drives) {
    if ($item.Name -like "*S25*" -or $item.Name -like "*Galaxy*" -or $item.Name -like "*진원*") {
        Write-Host "Found device: $($item.Name)" -ForegroundColor Cyan
        $phoneFolder = $item.GetFolder
        foreach ($sub in $phoneFolder.Items()) {
            $storageFolder = $sub.GetFolder
            foreach ($android in $storageFolder.Items()) {
                if ($android.Name -eq "Android") {
                    $androidFolder = $android.GetFolder
                    foreach ($data in $androidFolder.Items()) {
                        if ($data.Name -eq "data") {
                            $dataFolder = $data.GetFolder
                            foreach ($app in $dataFolder.Items()) {
                                if ($app.Name -like "*safecross*") {
                                    $appFolder = $app.GetFolder
                                    foreach ($f in $appFolder.Items()) {
                                        if ($f.Name -eq "files") {
                                            foreach ($lf in $f.GetFolder.Items()) {
                                                if ($lf.Name -eq "logs") {
                                                    $logsFolder = $lf.GetFolder
                                                    $destFolder = $shell.Namespace((Resolve-Path $OutputDir).Path)
                                                    foreach ($logFile in $logsFolder.Items()) {
                                                        if ($logFile.Name -like "*nav*") {
                                                            Write-Host "Copying $($logFile.Name)..." -ForegroundColor Green
                                                            $destFolder.CopyHere($logFile, 16)
                                                            $found = $true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

if ($found) {
    Start-Sleep -Seconds 2
    Write-Host "[SUCCESS] Logs successfully copied to $OutputDir/" -ForegroundColor Green
} else {
    Write-Host "[NOTICE] Could not find navigation_flight.log automatically." -ForegroundColor Yellow
    Write-Host "Tip: In the app's navigation screen, tap [경로 분석 진단 로그 공유/저장] to share via KakaoTalk or clipboard." -ForegroundColor Cyan
}
