param(
    [string]$Apk = "",
    [long]$MaximumBytes = 8MB
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
if (-not $Apk) {
    $Apk = Join-Path $repo "app/build/outputs/apk/release/app-release-unsigned.apk"
}
$Apk = [IO.Path]::GetFullPath($Apk)
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "APK not found: $Apk"
}

$sdkRoot = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    $sdkProperty = Get-Content -LiteralPath (Join-Path $repo "local.properties") -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^sdk\.dir=' } |
        Select-Object -First 1
    if ($sdkProperty) {
        $sdkProperty.Substring("sdk.dir=".Length).Replace('\:', ':').Replace('\\', '\')
    } else {
        Join-Path $env:LOCALAPPDATA "Android/Sdk"
    }
}
$analyzer = Join-Path $sdkRoot "cmdline-tools/latest/bin/apkanalyzer.bat"
if (-not (Test-Path -LiteralPath $analyzer -PathType Leaf)) {
    throw "apkanalyzer not found under Android SDK: $sdkRoot"
}

$size = (Get-Item -LiteralPath $Apk).Length
if ($size -gt $MaximumBytes) {
    throw "APK size $size bytes exceeds limit $MaximumBytes bytes: $Apk"
}

$packages = @(& $analyzer dex packages --defined-only $Apk 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "apkanalyzer failed with exit $LASTEXITCODE`n$($packages -join "`n")"
}
$forbidden = @(
    'org.apache.commons.net',
    'com.jcraft.jsch',
    'com.iamxpp.isaver.remote'
)
foreach ($package in $forbidden) {
    if ($packages -match [regex]::Escape($package)) {
        throw "Frozen remote package is present in local APK: $package"
    }
}

Write-Host "PASS: APK size $size bytes (limit $MaximumBytes); frozen remote packages absent."
