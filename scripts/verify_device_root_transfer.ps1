param(
    [string]$Serial = $env:ISAVER_ADB_SERIAL,
    [string]$JavaHome = "D:\compiler\java\jdk-21",
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$AutoGrantKernelSU,
    [int]$RootGrantTimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$appApk = Join-Path $repo "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $repo "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$packageName = "com.iamxpp.isaver"
$testPackageName = "com.iamxpp.isaver.test"
$runner = "$testPackageName/androidx.test.runner.AndroidJUnitRunner"
$rootStreamTest = "com.iamxpp.isaver.transfer.RootStreamTransferInstrumentedTest"

if (-not $Serial) {
    $devices = @(& adb devices -l)
    $online = @($devices | Where-Object { $_ -match "^\S+\s+device(?:\s|$)" })
    if ($online.Count -ne 1) {
        throw "Pass -Serial or set ISAVER_ADB_SERIAL. Online devices:`n$($devices -join "`n")"
    }
    $Serial = ($online[0] -split "\s+")[0]
}

function Invoke-Adb([string[]]$Arguments, [int]$ExpectedExit = 0) {
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        $output = @(& adb -s $Serial @Arguments 2>&1)
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    if ($exit -ne $ExpectedExit) {
        throw "adb exit $exit, expected $ExpectedExit`: $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Invoke-Root([string]$Command, [int]$ExpectedExit = 0) {
    $escaped = $Command.Replace("'", "'\''")
    Invoke-Adb -Arguments @("shell", "su -c '$escaped'") -ExpectedExit $ExpectedExit
}

function Install-ApkViaRoot([string]$LocalApk, [string]$RemoteApk) {
    if (-not (Test-Path -LiteralPath $LocalApk)) {
        throw "APK not found: $LocalApk"
    }
    Invoke-Adb @("push", $LocalApk, $RemoteApk) | Out-Host
    Invoke-Root "pm install -r -t $RemoteApk" | Out-Host
}

function Get-UiDumpText {
    $remote = "/sdcard/isaver-root-check-$PID.xml"
    $local = Join-Path ([IO.Path]::GetTempPath()) "isaver-root-check-$PID.xml"
    Invoke-Adb @("shell", "uiautomator", "dump", $remote) | Out-Null
    Invoke-Root "chmod 0644 $remote" | Out-Null
    Invoke-Adb @("pull", $remote, $local) | Out-Null
    Get-Content -Raw -LiteralPath $local
}

function Test-AppHasRootUi {
    $xml = Get-UiDumpText
    if ($xml -notmatch [regex]::Escape($packageName)) { return $false }
    if ($xml -match "Root") { return $false }
    return $true
}

Write-Host "Device:"
Invoke-Adb @("devices", "-l") | Out-Host
Write-Host "Root shell:"
Invoke-Root "id" | Out-Host

if (-not $SkipBuild) {
    if (-not (Test-Path -LiteralPath $JavaHome)) {
        throw "JavaHome not found: $JavaHome"
    }
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Push-Location $repo
    try {
        & .\gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest
        if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed" }
    } finally {
        Pop-Location
    }
}

if (-not $SkipInstall) {
    Install-ApkViaRoot $appApk "/data/local/tmp/isaver-debug.apk"
    Install-ApkViaRoot $testApk "/data/local/tmp/isaver-debug-androidTest.apk"
}

if ($AutoGrantKernelSU) {
    $grantScript = Join-Path $PSScriptRoot "grant_isaver_root_ksu.ps1"
    if (-not (Test-Path -LiteralPath $grantScript)) {
        throw "KernelSU grant script not found: $grantScript"
    }
    & powershell -ExecutionPolicy Bypass -File $grantScript -Serial $Serial -PackageName $packageName
    if ($LASTEXITCODE -ne 0) {
        throw "KernelSU auto grant failed"
    }
}

Write-Host "Launching iSaver and checking app root grant..."
Invoke-Adb @("shell", "am", "force-stop", $packageName) | Out-Null
Invoke-Adb @("shell", "am", "start", "-n", "$packageName/.MainActivity") | Out-Null

$deadline = [DateTime]::UtcNow.AddSeconds($RootGrantTimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    if (Test-AppHasRootUi) {
        Write-Host "PASS: iSaver root gate is granted"
        break
    }
    Write-Host "Waiting for iSaver root grant. If KernelSU prompts, approve it on the device/scrcpy window..."
} while ([DateTime]::UtcNow -lt $deadline)

if (-not (Test-AppHasRootUi)) {
    throw "iSaver did not pass the root gate within $RootGrantTimeoutSeconds seconds"
}

Write-Host "Running root stream transfer instrumentation..."
$instrumentationOutput = Invoke-Adb @(
    "shell", "am", "instrument", "-w",
    "-e", "class", $rootStreamTest,
    $runner
)
$instrumentationOutput | Out-Host
$instrumentationText = $instrumentationOutput -join "`n"
if (
    $instrumentationText -match "FAILURES!!!" -or
    $instrumentationText -match "Failures:\s*[1-9]" -or
    $instrumentationText -match "INSTRUMENTATION_FAILED" -or
    $instrumentationText -match "shortMsg="
) {
    throw "Root stream transfer instrumentation failed"
}
if ($instrumentationText -notmatch "OK \(\d+ tests?\)") {
    throw "Root stream transfer instrumentation did not report a successful JUnit result"
}

Write-Host "PASS: device root transfer verification completed"
