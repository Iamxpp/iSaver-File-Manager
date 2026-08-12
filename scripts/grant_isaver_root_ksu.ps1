param(
    [string]$Serial = $env:ISAVER_ADB_SERIAL,
    [string]$PackageName = "com.iamxpp.isaver",
    [string]$KernelSuManagerPackage = "me.weishu.kernelsu",
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [switch]$VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$helperSource = Join-Path $PSScriptRoot "ksu_profile_grant_v081.c"
$remoteHelper = "/data/local/tmp/isaver-ksu-profile-grant-v081"

if (-not $Serial) {
    $devices = @(& adb devices -l)
    $online = @($devices | Where-Object { $_ -match "^\S+\s+device(?:\s|$)" })
    if ($online.Count -ne 1) {
        throw "Pass -Serial or set ISAVER_ADB_SERIAL. Online devices:`n$($devices -join "`n")"
    }
    $Serial = ($online[0] -split "\s+")[0]
}

function Invoke-AdbRaw([string[]]$Arguments) {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        $output = @(& adb -s $Serial @Arguments 2>&1)
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    return [pscustomobject]@{
        ExitCode = $exit
        Output = $output
    }
}

function Invoke-Adb([string[]]$Arguments, [int]$ExpectedExit = 0) {
    $result = Invoke-AdbRaw $Arguments
    if ($result.ExitCode -ne $ExpectedExit) {
        throw "adb exit $($result.ExitCode), expected $ExpectedExit`: $($Arguments -join ' ')`n$($result.Output -join "`n")"
    }
    return $result.Output
}

function Invoke-Root([string]$Command, [int]$ExpectedExit = 0) {
    $escaped = $Command.Replace("'", "'\''")
    Invoke-Adb -Arguments @("shell", "su -c '$escaped'") -ExpectedExit $ExpectedExit
}

function Get-InstalledUid([string]$Package) {
    $output = Invoke-Adb @("shell", "cmd", "package", "list", "packages", "-U", $Package)
    $line = @($output | Where-Object { $_ -match "^package:$([regex]::Escape($Package))\s+uid:(\d+)$" })[0]
    if (-not $line) {
        throw "Package is not installed: $Package"
    }
    return [int]([regex]::Match($line, "uid:(\d+)").Groups[1].Value)
}

function Convert-UidToAndroidUserName([int]$Uid) {
    $userId = [math]::Floor($Uid / 100000)
    $appId = $Uid % 100000
    if ($appId -ge 10000 -and $appId -le 19999) {
        return "u$($userId)_a$($appId - 10000)"
    }
    return "$Uid"
}

function Get-AndroidSdkFromLocalProperties {
    $localProperties = Join-Path $repo "local.properties"
    if (-not (Test-Path -LiteralPath $localProperties)) {
        return $null
    }
    $line = @(Get-Content -LiteralPath $localProperties | Where-Object { $_ -match "^sdk\.dir=" } | Select-Object -First 1)
    if ($line.Count -eq 0) {
        return $null
    }
    $path = $line[0].Substring("sdk.dir=".Length).Replace("\:", ":").Replace("\\", "\")
    return $path
}

function Resolve-AndroidSdk {
    if ($AndroidSdk -and (Test-Path -LiteralPath $AndroidSdk)) {
        return $AndroidSdk
    }
    $fromLocal = Get-AndroidSdkFromLocalProperties
    if ($fromLocal -and (Test-Path -LiteralPath $fromLocal)) {
        return $fromLocal
    }
    $fallback = "D:\Android\android-sdk"
    if (Test-Path -LiteralPath $fallback) {
        return $fallback
    }
    throw "Android SDK not found. Pass -AndroidSdk or set ANDROID_SDK_ROOT."
}

function Resolve-Clang([string]$Sdk) {
    $ndkRoot = Join-Path $Sdk "ndk"
    if (-not (Test-Path -LiteralPath $ndkRoot)) {
        throw "Android NDK not found under $Sdk"
    }
    $ndk = Get-ChildItem -LiteralPath $ndkRoot -Directory |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $ndk) {
        throw "Android NDK not found under $ndkRoot"
    }
    $clang = Join-Path $ndk.FullName "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android23-clang.cmd"
    if (-not (Test-Path -LiteralPath $clang)) {
        throw "Android NDK clang not found: $clang"
    }
    return $clang
}

function Build-Helper {
    if (-not (Test-Path -LiteralPath $helperSource)) {
        throw "KernelSU helper source not found: $helperSource"
    }
    $abi = (Invoke-Adb @("shell", "getprop", "ro.product.cpu.abi") | Select-Object -First 1).Trim()
    if ($abi -notmatch "arm64") {
        throw "Unsupported device ABI for KernelSU helper: $abi"
    }

    $workDir = Join-Path ([IO.Path]::GetTempPath()) "isaver-ksu-helper-$PID"
    New-Item -ItemType Directory -Force -Path $workDir | Out-Null
    $helperBinary = Join-Path $workDir "isaver-ksu-profile-grant-v081"
    $clang = Resolve-Clang (Resolve-AndroidSdk)
    & $clang -O2 -Wall -Wextra -o $helperBinary $helperSource
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to compile KernelSU helper"
    }
    return [pscustomobject]@{
        WorkDir = $workDir
        Binary = $helperBinary
    }
}

function Invoke-ManagerHelper([string]$ManagerUser, [string]$Command, [int]$ExpectedExit) {
    Invoke-Adb -Arguments @("shell", "su", $ManagerUser, "-c", "$remoteHelper $Command") -ExpectedExit $ExpectedExit
}

Write-Host "Device:"
Invoke-Adb @("devices", "-l") | Out-Host

Write-Host "Root shell:"
Invoke-Root "id" | Out-Host

$packageUid = Get-InstalledUid $PackageName
$managerUid = Get-InstalledUid $KernelSuManagerPackage
$managerUser = Convert-UidToAndroidUserName $managerUid
Write-Host "Package: $PackageName uid=$packageUid"
Write-Host "KernelSU manager: $KernelSuManagerPackage uid=$managerUid user=$managerUser"

$helper = Build-Helper
try {
    Invoke-Adb @("push", $helper.Binary, $remoteHelper) | Out-Host
    Invoke-Root "chmod 0755 $remoteHelper" | Out-Null

    $checkResult = Invoke-AdbRaw @("shell", "su", $managerUser, "-c", "$remoteHelper check $packageUid")
    $checkResult.Output | Out-Host
    if ($checkResult.ExitCode -eq 0) {
        Write-Host "PASS: KernelSU already grants root to $PackageName"
        exit 0
    }

    if ($VerifyOnly) {
        throw "KernelSU does not grant root to $PackageName uid=$packageUid"
    }

    Invoke-Root "if [ -f /data/adb/ksu/.allowlist ]; then backup=/data/adb/ksu/.allowlist.bak-isaver-`$(date +%Y%m%d-%H%M%S); cp -p /data/adb/ksu/.allowlist `$backup; echo backup=`$backup; fi" | Out-Host

    Write-Host "Granting KernelSU root profile through manager uid..."
    Invoke-ManagerHelper $managerUser "grant $PackageName $packageUid" 0 | Out-Host
    Invoke-ManagerHelper $managerUser "check $packageUid" 0 | Out-Host
    Write-Host "PASS: KernelSU grants root to $PackageName"
} finally {
    Invoke-Adb @("shell", "rm", "-f", $remoteHelper) | Out-Null
    Remove-Item -LiteralPath $helper.WorkDir -Recurse -Force -ErrorAction SilentlyContinue
}
