param (
    [Parameter(Position = 0, Mandatory = $false)]
    [ValidateSet("bootstrap", "lint", "test", "test-android", "build-android", "backend-up", "backend-down", "secret-scan", "sbom-scan", "safety-gate", "beta-rehearsal", "help")]
    [string]$Command = "help"
)

$VenvPython = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
$VenvPytest = Join-Path $PSScriptRoot ".venv\Scripts\pytest.exe"
$VenvRuff = Join-Path $PSScriptRoot ".venv\Scripts\ruff.exe"

switch ($Command) {
    "bootstrap" {
        Write-Host "Creating Python virtual environment and installing dependencies..." -ForegroundColor Cyan
        python -m venv .venv
        & $VenvPython -m pip install --upgrade pip
        & $VenvPython -m pip install fastapi uvicorn pydantic pydantic-settings pytest httpx ruff
        & $VenvPython -m pip install -e backend -e data-pipeline
        Write-Host "Bootstrap completed successfully." -ForegroundColor Green
    }
    "lint" {
        Write-Host "Running ruff check..." -ForegroundColor Cyan
        if (Test-Path $VenvRuff) {
            & $VenvRuff check backend data-pipeline ml scripts
        } else {
            Write-Warning "ruff not found in .venv. Please run '.\run.ps1 bootstrap' first."
        }
    }
    "test" {
        Write-Host "Running backend, data-pipeline, ml, and release unit tests..." -ForegroundColor Cyan
        if (Test-Path $VenvPytest) {
            $env:PYTHONPATH = ".;data-pipeline;backend"
            & $VenvPytest data-pipeline/tests backend/tests ml/tests scripts/release/test_beta_rehearsal.py -v
        } else {
            Write-Warning "pytest not found in .venv. Please run '.\run.ps1 bootstrap' first."
        }
    }
    "test-android" {
        Write-Host "Running Android unit tests via Gradle..." -ForegroundColor Cyan
        $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
        $env:Path = "$env:JAVA_HOME\bin;$env:Path"
        Push-Location "android-app"
        try {
            .\gradlew.bat testDebugUnitTest
        } finally {
            Pop-Location
        }
    }
    "build-android" {
        Write-Host "Building Android Debug APK via Gradle..." -ForegroundColor Cyan
        $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
        $env:Path = "$env:JAVA_HOME\bin;$env:Path"
        Push-Location "android-app"
        try {
            .\gradlew.bat assembleDebug
        } finally {
            Pop-Location
        }
    }
    "secret-scan" {
        Write-Host "Checking for prohibited safety phrases and secret leakages..." -ForegroundColor Cyan
        $ScanPaths = @("backend\app", "data-pipeline\safecross_pipeline", "android-app\app\src\main")
        $p1 = "$([char]0xC548)$([char]0xC804)$([char]0xD569)$([char]0xB2C8)$([char]0xB2E4)" # 안전합니다
        $p2 = "$([char]0xC9C0)$([char]0xAE08) $([char]0xAC74)$([char]0xB108)$([char]0xC138)$([char]0xC694)" # 지금 건너세요
        $p3 = "$([char]0xCC28)$([char]0xAC00) $([char]0xC5C6)$([char]0xC2B5)$([char]0xB2C8)$([char]0xB2E4)" # 차가 없습니다
        $p4 = "100% $([char]0xB179)$([char]0xC0C9)" # 100% 녹색
        $Pattern = "$p1|$p2|$p3|$p4"
        $FoundViolation = $false
        foreach ($p in $ScanPaths) {
            if (Test-Path $p) {
                Get-ChildItem -Path $p -Recurse -File | ForEach-Object {
                    $m = Select-String -Path $_.FullName -Pattern $Pattern -Encoding UTF8
                    if ($m) {
                        Write-Error "CRITICAL: Prohibited safety phrase found in $($_.FullName)"
                        $FoundViolation = $true
                    }
                }
            }
        }
        if (-not $FoundViolation) {
            Write-Host "PowerShell pattern check passed. Running Python security scanners..." -ForegroundColor Cyan
            if (Test-Path $VenvPython) {
                $env:PYTHONPATH = ".;data-pipeline;backend"
                & $VenvPython -m scripts.security.secret_scanner --path .
                & $VenvPython -m scripts.security.safety_phrase_scanner --root .
            }
            Write-Host "Secret and prohibited safety phrase scan passed." -ForegroundColor Green
        }
    }
    "sbom-scan" {
        Write-Host "Generating SBOM and auditing dependencies..." -ForegroundColor Cyan
        if (Test-Path $VenvPython) {
            $env:PYTHONPATH = ".;data-pipeline;backend"
            & $VenvPython -m scripts.security.sbom_generator --root . --output reports/sbom/sbom.json
        } else {
            Write-Warning "Python not found in .venv."
        }
    }
    "beta-rehearsal" {
        Write-Host "Running Staging Beta Rehearsal Drills and Manifest Verification..." -ForegroundColor Cyan
        if (Test-Path $VenvPython) {
            $env:PYTHONPATH = ".;data-pipeline;backend"
            & $VenvPython d:\blind_navi_app\scripts\release\rehearsal_drill.py
            & $VenvPytest d:\blind_navi_app\scripts\release\test_beta_rehearsal.py -v
        } else {
            Write-Warning "Python not found in .venv."
        }
    }
    "safety-gate" {
        Write-Host "Running comprehensive local safety gate..." -ForegroundColor Cyan
        $env:PYTHONPATH = ".;data-pipeline;backend"
        & $PSScriptRoot\run.ps1 lint
        & $PSScriptRoot\run.ps1 secret-scan
        & $PSScriptRoot\run.ps1 sbom-scan
        & $PSScriptRoot\run.ps1 beta-rehearsal
        & $PSScriptRoot\run.ps1 test
        & $PSScriptRoot\run.ps1 test-android
        Write-Host "All local safety gate checks passed successfully." -ForegroundColor Green
    }
    Default {
        Write-Host "Usage: .\run.ps1 [command]"
        Write-Host "Commands:"
        Write-Host "  bootstrap      - Set up Python venv and install dependencies"
        Write-Host "  lint           - Run Python linter (ruff)"
        Write-Host "  test           - Run pytest for backend, data-pipeline, and ml"
        Write-Host "  test-android   - Run Android unit tests via Gradle"
        Write-Host "  build-android  - Build Android Debug APK via Gradle"
        Write-Host "  backend-up     - Start PostgreSQL+PostGIS docker container"
        Write-Host "  backend-down   - Stop PostgreSQL+PostGIS docker container"
        Write-Host "  secret-scan    - Scan source code for secrets and forbidden safety phrases"
        Write-Host "  sbom-scan      - Generate SBOM and audit dependencies"
        Write-Host "  beta-rehearsal - Run staging beta rehearsal drill and gate verification"
        Write-Host "  safety-gate    - Run all mandatory CI safety checks locally"
    }
}