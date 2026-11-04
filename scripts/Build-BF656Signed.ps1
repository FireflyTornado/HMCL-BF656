#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$PrivateKey,
    [string]$JavaHome,
    [string]$ServiceConfig,
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspace = Split-Path -Parent $repository
if (!$PrivateKey) {
    $PrivateKey = if ($env:HMCL_SIGNATURE_KEY) { $env:HMCL_SIGNATURE_KEY } else {
        Join-Path $workspace '.build-tools/bf656-signing/bf656-update-private.pk8'
    }
}
$PrivateKey = [IO.Path]::GetFullPath($PrivateKey)
if (!(Test-Path -LiteralPath $PrivateKey -PathType Leaf)) { throw 'BF656 signing private key was not found.' }
if ($PrivateKey.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'BF656 update private keys must be stored outside the source repository.'
}
if (!$JavaHome) {
    $JavaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
        Split-Path -Parent (Split-Path -Parent (Get-Command java.exe -ErrorAction Stop).Source)
    }
}
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe') -PathType Leaf)) {
    throw 'A JDK installation is required to build a signed BF656 release.'
}

$previousJavaHome = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
$previousSigningKey = $env:HMCL_SIGNATURE_KEY
$previousMicrosoftAuthId = $env:MICROSOFT_AUTH_ID
$previousCurseForgeApiKey = $env:CURSEFORGE_API_KEY
Push-Location -LiteralPath $repository
try {
    $env:JAVA_HOME = $JavaHome
    $env:HMCL_SIGNATURE_KEY = $PrivateKey
    if (!$ServiceConfig) {
        $ServiceConfig = Join-Path $workspace '.build-tools/bf656-service.properties'
    }
    $ServiceConfig = [IO.Path]::GetFullPath($ServiceConfig)
    if ($ServiceConfig.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'BF656 service settings must be stored outside the source repository.'
    }
    if (Test-Path -LiteralPath $ServiceConfig -PathType Leaf) {
        $serviceSettings = ConvertFrom-StringData ([IO.File]::ReadAllText($ServiceConfig, [Text.Encoding]::UTF8))
        foreach ($serviceName in @('MICROSOFT_AUTH_ID', 'CURSEFORGE_API_KEY')) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($serviceName, 'Process')) -and $serviceSettings.ContainsKey($serviceName)) {
                [Environment]::SetEnvironmentVariable($serviceName, $serviceSettings[$serviceName], 'Process')
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:MICROSOFT_AUTH_ID)) {
        throw 'Microsoft sign-in configuration is missing. Set MICROSOFT_AUTH_ID or provide an external service properties file with -ServiceConfig.'
    }
    $localGradleHome = Join-Path $workspace '.build-tools/gradle-home'
    if (!$env:GRADLE_USER_HOME -and (Test-Path -LiteralPath $localGradleHome -PathType Container)) {
        $env:GRADLE_USER_HOME = $localGradleHome
    }
    $arguments = @(':HMCL:bf656SignedRelease', '--no-daemon', '--console=plain')
    if ($Offline) { $arguments += '--offline' }
    & (Join-Path $repository 'gradlew.bat') @arguments
    if ($LASTEXITCODE -ne 0) { throw 'BF656 signed release failed. Do not distribute the build output.' }
    Write-Output 'BF656 signed release verified. Artifacts: HMCL/build/libs; report: HMCL/build/reports/bf656-signing/verification.json'
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:HMCL_SIGNATURE_KEY = $previousSigningKey
    $env:MICROSOFT_AUTH_ID = $previousMicrosoftAuthId
    $env:CURSEFORGE_API_KEY = $previousCurseForgeApiKey
    Pop-Location
}
