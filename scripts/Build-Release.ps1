param(
    [Parameter(Mandatory=$true)][string]$SigningProperties,
    [int]$VersionCode = 3,
    [string]$VersionName = '0.1.2',
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if ($VersionCode -lt 1 -or $VersionName -notmatch '^\d+\.\d+\.\d+([-.][A-Za-z0-9.-]+)?$') { throw 'Invalid version.' }
if (-not (Test-Path -LiteralPath $SigningProperties)) { throw 'Signing properties file is missing.' }
$status = @(& git -C $repo status --porcelain)
if ($LASTEXITCODE -ne 0 -or $status.Count -ne 0) { throw 'Commit source changes before producing a traceable release.' }
$revision = (& git -C $repo rev-parse HEAD).Trim()
$out = Join-Path $repo "artifacts/t16/v$VersionName-$VersionCode"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$oldConfig = $env:LIFELOG_SIGNING_PROPERTIES
Push-Location $repo
try {
    # Only the private file path is passed. Passwords are never command-line arguments.
    $env:LIFELOG_SIGNING_PROPERTIES = [IO.Path]::GetFullPath($SigningProperties)
    & .\gradlew.bat :app:assembleRelease :app:lintRelease "-PreleaseVersionCode=$VersionCode" `
        "-PreleaseVersionName=$VersionName" --console=plain --max-workers=2 *> (Join-Path $out 'build.log')
    if ($LASTEXITCODE -ne 0) { throw "Release build failed. Inspect $out/build.log" }
    # Keep the script readable by Windows PowerShell while producing the Chinese download name.
    $applicationName = "$([char]0x968F)$([char]0x624B)$([char]0x8BB0)"
    $downloadName = "$applicationName-$VersionName.apk"
    $apk = Join-Path $out $downloadName
    Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $apk
    $signer = Join-Path $Sdk 'build-tools/35.0.0/apksigner.bat'
    $signature = & $signer verify --verbose --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $signature | Out-File (Join-Path $out 'signature.txt') -Encoding utf8
    $badging = & (Join-Path $Sdk 'build-tools/35.0.0/aapt.exe') dump badging $apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
    $manifest = $badging -join "`n"
    if ($manifest -notmatch "package: name='com.personal.lifelog' versionCode='$VersionCode' versionName='$([regex]::Escape($VersionName))'" -or
        $manifest -match 'application-debuggable') { throw 'Unexpected package, version or debuggable flag.' }
    $badging | Out-File (Join-Path $out 'badging.txt') -Encoding utf8
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $out 'SHA256SUMS.txt'), "$hash  $downloadName`n", [Text.UTF8Encoding]::new($false))
    $fingerprint = @($signature | Select-String '^Signer #1 certificate SHA-256 digest:')
    if ($fingerprint.Count -ne 1) { throw 'Cannot identify signing certificate.' }
    [ordered]@{
        sourceRevision = $revision; workingTreeClean = $true; buildType = 'release'
        applicationId = 'com.personal.lifelog'; applicationName = $applicationName
        versionName = $VersionName; versionCode = $VersionCode
        sha256 = $hash; certificate = $fingerprint[0].Line
        apk = [IO.Path]::GetFileName($apk); deviceAcceptance = 'PENDING_PHYSICAL_DEVICE_MATRIX'
    } | ConvertTo-Json | Out-File (Join-Path $out 'release.json') -Encoding utf8
    Write-Output "Verified signed APK: $apk"
    Write-Output "SHA-256: $hash"
} finally { Pop-Location; $env:LIFELOG_SIGNING_PROPERTIES = $oldConfig }
