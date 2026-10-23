param([string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")
$ErrorActionPreference = 'Stop'
$devices = @(& $Adb devices | Select-String '^\S+\s+device$')
if ($devices.Count -ne 1) { throw 'Connect and authorize exactly one QA device before running this matrix.' }
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$outDir = Join-Path $repoRoot 'artifacts/t13/device-process'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
# APKs must be built first. Test fixture is isolated inside the debug app; no production records are used.
& $Adb install -r (Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk') | Out-File (Join-Path $outDir 'install-app.log')
if ($LASTEXITCODE -ne 0) { throw 'App installation failed' }
& $Adb install -r (Join-Path $repoRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') | Out-File (Join-Path $outDir 'install-test.log')
if ($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
$points = 'ROLLBACK_START','ROLLBACK_READY','GENERATION_START','PHOTO_PLACED','MEDIA_READY','BEFORE_DATABASE','IN_TRANSACTION','AFTER_COMMIT','CLEANUP'
foreach ($point in $points) {
    $common = @('shell','am','instrument','-w','-e','class','com.personal.lifelog.data.backup.RestoreProcessDeviceTest#processBoundary','-e','restorePoint',$point)
    $interrupt = & $Adb @common -e restoreAction interrupt com.personal.lifelog.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $interrupt | Out-File (Join-Path $outDir "$point-interrupt.log")
    if (($interrupt -join "`n") -notmatch 'Process crashed|shortMsg=Process|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') {
        throw "Expected process termination at $point; inspect interrupt log"
    }
    $recovery = & $Adb @common -e restoreAction recover com.personal.lifelog.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $recovery | Out-File (Join-Path $outDir "$point-recover.log")
    if (($recovery -join "`n") -notmatch 'OK \(1 test\)' -or $LASTEXITCODE -ne 0) {
        throw "Recovery verification failed at $point"
    }
}
Write-Output 'All nine actual process-termination boundaries verified.'
