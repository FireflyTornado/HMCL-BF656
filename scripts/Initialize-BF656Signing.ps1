#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$KeyDirectory,
    [string]$BackupDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspace = Split-Path -Parent $repository
if (!$KeyDirectory) { $KeyDirectory = Join-Path $workspace '.build-tools/bf656-signing' }
if (!$BackupDirectory) { $BackupDirectory = Join-Path $workspace '.build-tools/bf656-signing-backup' }
$KeyDirectory = [IO.Path]::GetFullPath($KeyDirectory)
$BackupDirectory = [IO.Path]::GetFullPath($BackupDirectory)
foreach ($directory in @($KeyDirectory, $BackupDirectory)) {
    if ($directory.Equals($repository, [StringComparison]::OrdinalIgnoreCase) -or
        $directory.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Signing keys and recovery files must be stored outside the source repository.'
    }
}
if ($KeyDirectory.Equals($BackupDirectory, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Choose separate key and backup directories.'
}

$privatePath = Join-Path $KeyDirectory 'bf656-update-private.pk8'
$publicPath = Join-Path $KeyDirectory 'bf656-update-public.der'
$backupPath = Join-Path $BackupDirectory 'bf656-update-private.pk8.dpapi'
$backupPublicPath = Join-Path $BackupDirectory 'bf656-update-public.der'
foreach ($path in @($privatePath, $publicPath, $backupPath, $backupPublicPath)) {
    if (Test-Path -LiteralPath $path) { throw 'Signing material already exists. Reuse it; initialization never overwrites a key.' }
}

# Restrict both directories before any plaintext private key is created.
$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
foreach ($directory in @($KeyDirectory, $BackupDirectory)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    & icacls.exe $directory /inheritance:r /grant:r "*${currentSid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict the signing directory permissions.' }
}

$rsa = [Security.Cryptography.RSA]::Create()
$privateBytes = $null
$recoveredBytes = $null
try {
    $rsa.KeySize = 3072
    $privateBytes = $rsa.ExportPkcs8PrivateKey()
    $publicBytes = $rsa.ExportSubjectPublicKeyInfo()
    $protectedBytes = [Security.Cryptography.ProtectedData]::Protect(
        $privateBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    $recoveredBytes = [Security.Cryptography.ProtectedData]::Unprotect(
        $protectedBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    if ($recoveredBytes.Length -ne $privateBytes.Length) { throw 'Encrypted backup verification failed.' }
    for ($index = 0; $index -lt $privateBytes.Length; $index++) {
        if ($recoveredBytes[$index] -ne $privateBytes[$index]) { throw 'Encrypted backup verification failed.' }
    }

    # CreateNew protects against accidental replacement, including another simultaneous initialization.
    $outputs = @(
        @{ Path = $privatePath; Bytes = $privateBytes },
        @{ Path = $publicPath; Bytes = $publicBytes },
        @{ Path = $backupPath; Bytes = $protectedBytes },
        @{ Path = $backupPublicPath; Bytes = $publicBytes }
    )
    foreach ($output in $outputs) {
        $stream = [IO.File]::Open($output.Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try { $stream.Write($output.Bytes, 0, $output.Bytes.Length) } finally { $stream.Dispose() }
    }

    $publicResource = Join-Path $repository 'HMCL/src/main/resources/assets/hmcl_signature_publickey.der'
    Copy-Item -LiteralPath $publicPath -Destination $publicResource
    $fingerprint = (Get-FileHash -LiteralPath $publicPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata = [ordered]@{
        algorithm = 'RSA-3072 / SHA512withRSA'
        publicKeySha256 = $fingerprint
        createdUtc = [DateTimeOffset]::UtcNow.ToString('o')
        backupProtection = 'Windows DPAPI CurrentUser; recovery requires this Windows account and its DPAPI keys'
        backupRoundTripVerified = $true
    }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $KeyDirectory 'signing-metadata.json') -Encoding utf8
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $BackupDirectory 'signing-metadata.json') -Encoding utf8
    Write-Output "BF656 key initialized. Public key SHA-256: $fingerprint"
    Write-Output "Private key: $privatePath"
    Write-Output "Verified local encrypted recovery copy: $backupPath"
} finally {
    if ($null -ne $privateBytes) { [Array]::Clear($privateBytes, 0, $privateBytes.Length) }
    if ($null -ne $recoveredBytes) { [Array]::Clear($recoveredBytes, 0, $recoveredBytes.Length) }
    $rsa.Dispose()
}
