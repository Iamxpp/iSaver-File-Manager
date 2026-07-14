param(
    [string]$Serial = $env:ISAVER_ADB_SERIAL,
    [string]$HelperPath = "",
    [string]$AppApk = "",
    [string]$TestApk = "",
    [int]$Samples = 20
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$fixtureRoot = "/data/local/tmp/isaver-perf"
$remoteHelper = "/data/local/tmp/isaver_fs_helper_perf"
$tracePath = "$fixtureRoot/process.trace"

if ($fixtureRoot -cne "/data/local/tmp/isaver-perf") {
    throw "Refusing to use unexpected fixture root: $fixtureRoot"
}
if (-not $Serial) {
    throw "Pass -Serial or set ISAVER_ADB_SERIAL"
}
if ($Samples -ne 20) {
    throw "Performance acceptance requires exactly 20 samples"
}

if (-not $HelperPath) {
    $HelperPath = Get-ChildItem -LiteralPath "$repo/app/build/intermediates/cxx/Debug" -Recurse -Filter libisaver_fs_helper.so |
        Where-Object { $_.FullName -match 'obj[\\/]arm64-v8a[\\/]libisaver_fs_helper\.so$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $AppApk) {
    $AppApk = "$repo/app/build/outputs/apk/debug/app-debug.apk"
}
if (-not $TestApk) {
    $TestApk = "$repo/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
}
foreach ($requiredFile in @($HelperPath, $AppApk, $TestApk)) {
    if (-not $requiredFile -or -not (Test-Path -LiteralPath $requiredFile)) {
        throw "Required build artifact not found: $requiredFile"
    }
}

function Quote-ShellArgument([string]$Value) {
    if ($Value.Contains([char]0)) {
        throw "Shell arguments cannot contain NUL"
    }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Invoke-Adb {
    param(
        [string[]]$Arguments,
        [int]$ExpectedExit = 0
    )

    $output = @(& adb -s $Serial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne $ExpectedExit) {
        throw "adb exit $exitCode, expected ${ExpectedExit}: $Arguments`n$($output -join "`n")"
    }
    return $output
}

function Invoke-Root {
    param(
        [string]$Command,
        [int]$ExpectedExit = 0
    )

    return Invoke-Adb -Arguments @("shell", "su", "-c", (Quote-ShellArgument $Command)) -ExpectedExit $ExpectedExit
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function New-NumberedFiles {
    param(
        [string]$Directory,
        [int]$Count
    )

    $command = 'dir={0}; mkdir -p -- "$dir"; i=0; while [ "$i" -lt {1} ]; do : > "$dir/item-$i"; i=$((i + 1)); done' -f @(
        (Quote-ShellArgument $Directory),
        $Count
    )
    Invoke-Root $command | Out-Null
}

function Measure-ListDirectoryMillis {
    param([string]$Directory)

    $command = 'start=$(date +%s%N); {0} list-dir {1} >/dev/null; code=$?; end=$(date +%s%N); [ "$code" -eq 0 ] || exit "$code"; printf "%s\n" "$(((end-start)/1000000))"' -f @(
        (Quote-ShellArgument $remoteHelper),
        (Quote-ShellArgument $Directory)
    )
    $output = @((Invoke-Root $command) | Where-Object { "$_" -match '^\d+$' })
    if ($output.Count -ne 1) {
        throw "Expected one device duration for $Directory, got: $($output -join ', ')"
    }
    return [double]::Parse($output[0], [Globalization.CultureInfo]::InvariantCulture)
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [int]$Percentile
    )

    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($sorted.Count * $Percentile / 100.0) - 1
    return [double]$sorted[[Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))]
}

function Write-Summary {
    param(
        [string]$Label,
        [double[]]$Values
    )

    $p50 = Get-Percentile $Values 50
    $p95 = Get-Percentile $Values 95
    Write-Host ("{0}: samples={1} p50={2:N2}ms p95={3:N2}ms" -f $Label, $Values.Count, $p50, $p95)
    return [pscustomobject]@{ Label = $Label; P50 = $p50; P95 = $p95 }
}

$devices = @(& adb devices -l)
$targetLines = @($devices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+" })
if ($targetLines.Count -ne 1 -or $targetLines[0] -notmatch '\sdevice(?:\s|$)') {
    throw "Target serial is not uniquely online: $Serial`n$($devices -join "`n")"
}
$rootIdentity = (Invoke-Root "id") -join "`n"
Assert-True ($rootIdentity -match '(?:^|\s)uid=0\(root\)(?:\s|$)') "su did not return Root"
$devices | Out-Host
$rootIdentity | Out-Host

try {
    Invoke-Adb -Arguments @("push", $HelperPath, $remoteHelper) | Out-Host
    Invoke-Root "chmod 0755 $(Quote-ShellArgument $remoteHelper)" | Out-Null
    Invoke-Root "rm -rf -- $(Quote-ShellArgument $fixtureRoot); mkdir -p -- $(Quote-ShellArgument $fixtureRoot)" | Out-Null

    New-NumberedFiles "$fixtureRoot/empty" 0
    New-NumberedFiles "$fixtureRoot/fifty" 50
    New-NumberedFiles "$fixtureRoot/warm-200" 200
    for ($index = 0; $index -lt $Samples; $index++) {
        New-NumberedFiles "$fixtureRoot/helper-cold-200-$index" 200
        New-NumberedFiles "$fixtureRoot/app-cold-200-$index" 200
        New-NumberedFiles "$fixtureRoot/helper-1000-$index" 1000
        New-NumberedFiles "$fixtureRoot/visible-1000-$index" 1000
    }

    $emptyLines = [int](((Invoke-Root "$(Quote-ShellArgument $remoteHelper) list-dir $(Quote-ShellArgument "$fixtureRoot/empty") | wc -l") -join "").Trim())
    $fiftyLines = [int](((Invoke-Root "$(Quote-ShellArgument $remoteHelper) list-dir $(Quote-ShellArgument "$fixtureRoot/fifty") | wc -l") -join "").Trim())
    Assert-True ($emptyLines -eq 1) "Empty fixture protocol line count was $emptyLines"
    Assert-True ($fiftyLines -eq 51) "50-entry fixture protocol line count was $fiftyLines"

    $coldTimes = for ($index = 0; $index -lt $Samples; $index++) {
        Measure-ListDirectoryMillis "$fixtureRoot/helper-cold-200-$index"
    }
    Measure-ListDirectoryMillis "$fixtureRoot/warm-200" | Out-Null
    $warmTimes = for ($index = 0; $index -lt $Samples; $index++) {
        Measure-ListDirectoryMillis "$fixtureRoot/warm-200"
    }
    $largeTimes = for ($index = 0; $index -lt $Samples; $index++) {
        Measure-ListDirectoryMillis "$fixtureRoot/helper-1000-$index"
    }

    $coldSummary = Write-Summary "helper-cold-200" $coldTimes
    $warmSummary = Write-Summary "helper-warm-200" $warmTimes
    $largeSummary = Write-Summary "helper-first-1000" $largeTimes
    Assert-True ($coldSummary.P95 -lt 500.0) "200-entry helper cold P95 exceeded 500ms"
    Assert-True ($warmSummary.P95 -lt 500.0) "200-entry helper warm P95 exceeded 500ms"
    Assert-True ($largeSummary.P95 -lt 500.0) "1000-entry helper P95 exceeded 500ms"

    $remoteStrace = ((Invoke-Root 'command -v strace') -join "").Trim()
    Assert-True (-not [string]::IsNullOrWhiteSpace($remoteStrace)) "strace is required for process-count proof"
    $traceCommand = "$(Quote-ShellArgument $remoteStrace) -f -e trace=process -o $(Quote-ShellArgument $tracePath) -- $(Quote-ShellArgument $remoteHelper) list-dir $(Quote-ShellArgument "$fixtureRoot/warm-200") >/dev/null"
    Invoke-Root $traceCommand | Out-Null
    $trace = (Invoke-Root "cat -- $(Quote-ShellArgument $tracePath)") -join "`n"
    $execCount = [regex]::Matches($trace, '\bexecve\(').Count
    $childCount = [regex]::Matches($trace, '\b(?:clone|clone3|fork|vfork)\(').Count
    Assert-True ($execCount -eq 1) "Expected one helper execve, got $execCount"
    Assert-True ($childCount -eq 0) "Expected no child process, got $childCount"
    Write-Host "helper-processes: execve=$execCount children=$childCount"

    Invoke-Adb -Arguments @("push", $AppApk, "/data/local/tmp/app-debug.apk") | Out-Host
    Invoke-Adb -Arguments @("push", $TestApk, "/data/local/tmp/app-debug-androidTest.apk") | Out-Host
    Invoke-Root "pm install -r -t /data/local/tmp/app-debug.apk" | Out-Host
    Invoke-Root "pm install -r -t /data/local/tmp/app-debug-androidTest.apk" | Out-Host
    Invoke-Root "cmd appops set com.iamxpp.isaver 10021 allow" | Out-Null
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    $instrumentation = Invoke-Adb -Arguments @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "com.iamxpp.isaver.ui.RootBrowserPerformanceTest",
        "com.iamxpp.isaver.test/androidx.test.runner.AndroidJUnitRunner"
    )
    $instrumentation | Out-Host
    Assert-True (($instrumentation -join "`n") -match 'OK \(1 test\)') "Performance instrumentation did not pass"
    $metrics = Invoke-Adb -Arguments @("logcat", "-d", "-s", "ISaverPerf:I", "*:S")
    $metrics | Out-Host
    Assert-True (($metrics -join "`n") -match 'cold200=.*visible1000=') "Instrumentation metrics were not emitted"

    Write-Host "All fast Root browser performance gates passed."
}
finally {
    try {
        Invoke-Root "rm -rf -- $(Quote-ShellArgument $fixtureRoot)" | Out-Null
    }
    catch {
        Write-Warning "Could not clean performance fixtures: $($_.Exception.Message)"
    }
    try {
        Invoke-Root "rm -f -- $(Quote-ShellArgument $remoteHelper) /data/local/tmp/app-debug.apk /data/local/tmp/app-debug-androidTest.apk" | Out-Null
    }
    catch {
        Write-Warning "Could not remove performance helper: $($_.Exception.Message)"
    }
}
