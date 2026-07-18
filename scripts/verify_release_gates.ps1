param(
    [string]$Serial = "d51f42ac",
    [switch]$SkipPerformance
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$appApk = Join-Path $repo "app/build/outputs/apk/debug/app-debug.apk"
$testApk = Join-Path $repo "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$remoteAppApk = "/data/local/tmp/isaver-release-app.apk"
$remoteTestApk = "/data/local/tmp/isaver-release-test.apk"
$knownTestPaths = @(
    "/data/local/tmp/isaver-archive-test",
    "/data/local/tmp/isaver-test",
    "/data/local/tmp/isaver-perf",
    $remoteAppApk,
    $remoteTestApk
)

if ($Serial -cne "d51f42ac") {
    throw "Release gates are pinned to Xiaomi 9 serial d51f42ac; got $Serial"
}

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments)
    Write-Host "> $FilePath $($Arguments -join ' ')"
    $output = @(& $FilePath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath exited with $LASTEXITCODE`n$($output -join "`n")"
    }
    $output | Out-Host
    return $output
}

function Invoke-Adb {
    param([string[]]$Arguments)
    return Invoke-Checked -FilePath "adb" -Arguments (@("-s", $Serial) + $Arguments)
}

function Invoke-Root {
    param([string]$Command)
    return Invoke-Adb -Arguments @("shell", "su", "-c", $Command)
}

function Assert-Instrumentation {
    param([string]$ClassName)
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    $output = Invoke-Adb -Arguments @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $ClassName,
        "com.iamxpp.isaver.test/androidx.test.runner.AndroidJUnitRunner"
    )
    $text = $output -join "`n"
    if ($text -notmatch 'OK \(\d+ tests?\)') {
        throw "Instrumentation did not report an OK summary: $ClassName`n$text"
    }
    if ($text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2') {
        throw "Instrumentation reported failure text: $ClassName`n$text"
    }
    $fatal = (Invoke-Adb -Arguments @("logcat", "-d", "-v", "brief")) -join "`n"
    if ($fatal -match 'FATAL EXCEPTION|ANR in com\.iamxpp\.isaver') {
        throw "Fatal/ANR detected after $ClassName`n$fatal"
    }
}

$devices = @(& adb devices -l)
$matching = @($devices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device(?:\s|$)" })
if ($matching.Count -ne 1) {
    throw "Target serial is not uniquely online: $Serial`n$($devices -join "`n")"
}

try {
    $rootIdentity = (Invoke-Root "id") -join "`n"
    if ($rootIdentity -notmatch '(?:^|\s)uid=0\(root\)(?:\s|$)') {
        throw "su did not return uid=0: $rootIdentity"
    }

    Push-Location $repo
    try {
        Invoke-Checked -FilePath ".\gradlew.bat" -Arguments @("testDebugUnitTest") | Out-Null
        Invoke-Checked -FilePath ".\gradlew.bat" -Arguments @("lintDebug") | Out-Null
        Invoke-Checked -FilePath ".\gradlew.bat" -Arguments @("assembleDebug", "assembleDebugAndroidTest") | Out-Null
    }
    finally {
        Pop-Location
    }

    foreach ($artifact in @($appApk, $testApk)) {
        if (-not (Test-Path -LiteralPath $artifact)) {
            throw "Missing build artifact: $artifact"
        }
    }

    Invoke-Adb -Arguments @("push", $appApk, $remoteAppApk) | Out-Null
    Invoke-Adb -Arguments @("push", $testApk, $remoteTestApk) | Out-Null
    Invoke-Root "pm install -r -t $remoteAppApk" | Out-Null
    Invoke-Root "pm install -r -t $remoteTestApk" | Out-Null

    $groups = @(
        "com.iamxpp.isaver.archive.ArchiveRootInstrumentedTest",
        "com.iamxpp.isaver.transfer.RootStreamTransferInstrumentedTest",
        "com.iamxpp.isaver.transfer.IncomingStreamProviderInstrumentedTest",
        "com.iamxpp.isaver.LauncherIconInstrumentedTest",
        "com.iamxpp.isaver.ui.theme.ThemeConfigurationInstrumentedTest",
        "com.iamxpp.isaver.MainActivitySmokeTest"
    )
    foreach ($group in $groups) {
        Assert-Instrumentation -ClassName $group
    }

    & (Join-Path $PSScriptRoot "verify_local_file_workflow.ps1") -Serial $Serial
    if ($LASTEXITCODE -ne 0) {
        throw "Local file workflow gate failed with exit $LASTEXITCODE"
    }

    if (-not $SkipPerformance) {
        & (Join-Path $PSScriptRoot "benchmark_root_listing.ps1") -Serial $Serial -AppApk $appApk -TestApk $testApk
        if ($LASTEXITCODE -ne 0) {
            throw "Root listing performance gate failed with exit $LASTEXITCODE"
        }
    }

    Write-Host "All iSaver release gates passed."
}
finally {
    try {
        Invoke-Root ("rm -rf -- " + ($knownTestPaths -join " ")) | Out-Null
    }
    catch {
        Write-Warning "Could not clean all known release fixtures: $($_.Exception.Message)"
    }
}
