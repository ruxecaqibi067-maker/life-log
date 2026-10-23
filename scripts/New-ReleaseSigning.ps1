param(
    [string]$PrivateDirectory = (Join-Path $env:LOCALAPPDATA 'LifeLogAndroid/signing'),
    [string]$Keytool = (Join-Path $env:JAVA_HOME 'bin/keytool.exe')
)
$ErrorActionPreference = 'Stop'
$privatePath = [IO.Path]::GetFullPath($PrivateDirectory)
$repoPath = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent)).TrimEnd('\')
if ($privatePath.Equals($repoPath, [StringComparison]::OrdinalIgnoreCase) -or
    $privatePath.StartsWith($repoPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signing material must be stored outside this repository.'
}
if (-not (Test-Path -LiteralPath $Keytool)) { throw 'Set JAVA_HOME to JDK 17 or supply -Keytool.' }
New-Item -ItemType Directory -Force -Path $privatePath | Out-Null
$store = Join-Path $privatePath 'lifelog-release.p12'
$properties = Join-Path $privatePath 'signing.properties'
if ((Test-Path -LiteralPath $store) -or (Test-Path -LiteralPath $properties)) {
    throw 'Signing material already exists. Reuse it; this script never replaces an existing key.'
}
# Apply a private ACL before writing secrets. Do not change any parent directory ACL.
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.SetOwner($owner)
foreach ($sid in @($owner, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl',
        'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $privatePath -AclObject $acl
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$password = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$oldPassword = $env:LIFELOG_KEYTOOL_PASSWORD
try {
    $env:LIFELOG_KEYTOOL_PASSWORD = $password
    & $Keytool -genkeypair -keystore $store -storetype PKCS12 -alias lifelog-release `
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Life Log Local Release' `
        -storepass:env LIFELOG_KEYTOOL_PASSWORD -keypass:env LIFELOG_KEYTOOL_PASSWORD -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed; inspect the private directory before retrying.' }
    $content = "storeFile=$($store.Replace('\','/'))`nstorePassword=$password`nkeyAlias=lifelog-release`nkeyPassword=$password`n"
    [IO.File]::WriteAllText($properties, $content, [Text.UTF8Encoding]::new($false))
} finally { $env:LIFELOG_KEYTOOL_PASSWORD = $oldPassword; $password = $null; $content = $null }
Write-Output "Signing material created outside Git: $privatePath"
Write-Output 'Back up the entire private directory to a second private location. Future updates must reuse this key.'
