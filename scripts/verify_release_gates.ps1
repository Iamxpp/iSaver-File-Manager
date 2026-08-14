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
        "shell", "timeout", "180s", "am", "instrument", "-w", "-r",
        "-e", "class", $ClassName,
        "com.isaver.filemanager.test/com.isaver.filemanager.ISaverTestRunner"
    )
    $text = $output -join "`n"
    if ($text -notmatch 'OK \(\d+ tests?\)') {
        throw "Instrumentation did not report an OK summary: $ClassName`n$text"
    }
    if ($text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2') {
        throw "Instrumentation reported failure text: $ClassName`n$text"
    }
    $fatal = (Invoke-Adb -Arguments @("logcat", "-d", "-v", "brief")) -join "`n"
    if ($fatal -match 'FATAL EXCEPTION|ANR in com\.isaver\.filemanager') {
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
        Invoke-Checked -FilePath ".\gradlew.bat" -Arguments @(
            ":app:assembleDebug",
            ":app:assembleDebugAndroidTest",
            ":app:assembleRelease"
        ) | Out-Null
        & pwsh -NoProfile -File (Join-Path $PSScriptRoot "verify_apk_size.ps1")
        if ($LASTEXITCODE -ne 0) {
            throw "APK size and dependency gate failed with exit $LASTEXITCODE"
        }
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
        "com.isaver.filemanager.archive.ArchiveRootInstrumentedTest",
        "com.isaver.filemanager.bookmarks.RootBookmarkInstrumentedTest",
        "com.isaver.filemanager.data.local.BrowserSessionInstrumentedTest",
        "com.isaver.filemanager.data.local.ISaverDatabaseMigrationTest",
        "com.isaver.filemanager.export.ExternalFileProviderInstrumentedTest",
        "com.isaver.filemanager.export.RootFileOpenInstrumentedTest",
        "com.isaver.filemanager.export.RootFileShareInstrumentedTest",
        "com.isaver.filemanager.fileops.RootChecksumInstrumentedTest",
        "com.isaver.filemanager.fileops.RootDirectoryCopyMoveInstrumentedTest",
        "com.isaver.filemanager.fileops.RootFileCopyInstrumentedTest",
        "com.isaver.filemanager.fileops.RootFileCreateInstrumentedTest",
        "com.isaver.filemanager.fileops.RootFileMoveInstrumentedTest",
        "com.isaver.filemanager.fileops.RootFilePermissionInstrumentedTest",
        "com.isaver.filemanager.filetools.RootFileToolsInstrumentedTest",
        "com.isaver.filemanager.LauncherIconInstrumentedTest",
        "com.isaver.filemanager.release.LocalStabilityInstrumentedTest",
        "com.isaver.filemanager.search.LocalSearchInstrumentedTest",
        "com.isaver.filemanager.share.ShareIntentResolutionTest",
        "com.isaver.filemanager.texteditor.RootTextEditorInstrumentedTest",
        "com.isaver.filemanager.transfer.IncomingStreamProviderInstrumentedTest",
        "com.isaver.filemanager.transfer.RootStreamTransferInstrumentedTest",
        "com.isaver.filemanager.ui.theme.ThemeConfigurationInstrumentedTest",
        "com.isaver.filemanager.MainActivitySmokeTest"
    )
    foreach ($group in $groups) {
        Assert-Instrumentation -ClassName $group
    }

    & pwsh -NoProfile -File (Join-Path $PSScriptRoot "verify_local_file_workflow.ps1") -Serial $Serial
    if ($LASTEXITCODE -ne 0) {
        throw "Local file workflow gate failed with exit $LASTEXITCODE"
    }

    if (-not $SkipPerformance) {
        & pwsh -NoProfile -File (Join-Path $PSScriptRoot "benchmark_root_listing.ps1") `
            -Serial $Serial -AppApk $appApk -TestApk $testApk
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
