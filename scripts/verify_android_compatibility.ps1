param(
    [Parameter(Mandatory = $true)][ValidateSet(29, 33, 35)][int]$Api,
    [string]$SdkRoot = "D:\Android\android-sdk",
    [int]$Port = 5554
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$serial = "emulator-$Port"
$avd = "isaver_api$Api"
$emulator = Join-Path $SdkRoot "emulator\emulator.exe"
$adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$appApk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$dump = "/sdcard/isaver-compat.xml"
$localDump = Join-Path ([IO.Path]::GetTempPath()) "isaver-compat-$Api-$PID.xml"

function Invoke-Adb([string[]]$Arguments) {
    $output = @(& $adb -s $serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')`n$($output -join "`n")" }
    $output
}

function Get-UiXml {
    Invoke-Adb @("shell", "uiautomator", "dump", $dump) | Out-Null
    Invoke-Adb @("pull", $dump, $localDump) | Out-Null
    [xml](Get-Content -Raw -LiteralPath $localDump)
}

function Find-Text([string]$Text, [int]$TimeoutSeconds = 25) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $node = @(Get-UiXml | ForEach-Object { $_.SelectNodes("//node[@text='$Text']") }) | Select-Object -Last 1
        if ($null -ne $node) { return $node }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for '$Text'"
}

function Click-Text([string]$Text) {
    $node = Find-Text $Text
    while ($null -ne $node -and $node.Name -eq "node" -and $node.GetAttribute("clickable") -ne "true") {
        $node = $node.ParentNode
    }
    if ($null -eq $node -or $node.GetAttribute("bounds") -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "No clickable bounds for '$Text'"
    }
    $x = ([int]$matches[1] + [int]$matches[3]) / 2
    $y = ([int]$matches[2] + [int]$matches[4]) / 2
    Invoke-Adb @("shell", "input", "tap", "$x", "$y") | Out-Null
}

$process = $null
try {
    $process = Start-Process -FilePath $emulator -ArgumentList @(
        "-avd", $avd, "-port", "$Port", "-no-window", "-no-audio", "-no-boot-anim",
        "-gpu", "swiftshader_indirect", "-wipe-data"
    ) -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    do {
        $booted = @(& $adb -s $serial shell getprop sys.boot_completed 2>$null) -contains "1"
        if ($booted) { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $booted) { throw "API $Api emulator did not boot" }

    $reportedApi = @(Invoke-Adb @("shell", "getprop", "ro.build.version.sdk"))[-1].Trim()
    if ($reportedApi -ne "$Api") {
        throw "Emulator API does not match requested API $Api"
    }
    Invoke-Adb @("install", "-r", $appApk) | Out-Null
    Invoke-Adb @("install", "-r", "-t", $testApk) | Out-Null
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "com.iamxpp.isaver/.MainActivity") | Out-Null
    Find-Text "请以 Root 权限运行 iSaver" | Out-Null
    Find-Text "重新检测" | Out-Null
    Find-Text "退出应用" | Out-Null

    Click-Text "重新检测"
    Find-Text "请以 Root 权限运行 iSaver" | Out-Null
    Invoke-Adb @("shell", "am", "force-stop", "com.iamxpp.isaver") | Out-Null
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "com.iamxpp.isaver/.MainActivity") | Out-Null
    Find-Text "请以 Root 权限运行 iSaver" | Out-Null

    $instrumentation = Invoke-Adb @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "com.iamxpp.isaver.ui.RootGateScreenTest,com.iamxpp.isaver.ui.files.FilesComponentsTest",
        "com.iamxpp.isaver.test/com.iamxpp.isaver.ISaverTestRunner"
    )
    if (($instrumentation -join "`n") -notmatch 'OK \(') { throw "Compatibility instrumentation failed" }

    Invoke-Adb @("shell", "am", "start", "-W", "-n", "com.iamxpp.isaver/.MainActivity") | Out-Null
    Click-Text "退出应用"
    Start-Sleep -Milliseconds 500
    $resumed = (Invoke-Adb @("shell", "dumpsys", "activity", "activities")) -join "`n"
    if ($resumed -match 'mResumedActivity.*com\.iamxpp\.isaver') { throw "Exit action did not close iSaver" }
    Write-Host "PASS API ${Api}: install, cold start, non-Root gate, retry, recreation, UI tests, and exit."
}
finally {
    Remove-Item -LiteralPath $localDump -Force -ErrorAction SilentlyContinue
    & $adb -s $serial emu kill 2>$null | Out-Null
    if ($null -ne $process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
}
