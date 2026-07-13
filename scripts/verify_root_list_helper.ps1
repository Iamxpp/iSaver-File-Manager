param(
    [string]$Serial = $env:ISAVER_ADB_SERIAL,
    [string]$HelperPath = "",
    [string]$RemoteStrace = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$testRoot = "/data/local/tmp/isaver-list-helper"
$remoteHelper = "/data/local/tmp/isaver_fs_helper_list_verify"
$tracePath = "$testRoot/list.trace"
$traceOutputPath = "$testRoot/list.trace.output"
$limitOutputPath = "$testRoot/list.limit.output"
$maxRecordCount = 100000
$maxFieldBytes = 1048576
$maxProtocolBytes = 67108864L
$limitExitCode = 57
$strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)

if ($testRoot -cne "/data/local/tmp/isaver-list-helper") {
    throw "Refusing to use an unexpected test root: $testRoot"
}
if (-not $Serial) {
    throw "Pass -Serial or set ISAVER_ADB_SERIAL"
}

$deviceOutput = @(& adb devices -l)
$targetLines = @($deviceOutput | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+" })
if ($targetLines.Count -ne 1 -or $targetLines[0] -notmatch "\sdevice(?:\s|$)") {
    throw "Target serial is not uniquely online: $Serial`n$($deviceOutput -join "`n")"
}

if (-not $HelperPath) {
    $HelperPath = Get-ChildItem -LiteralPath "$repo/app/build/intermediates/cxx/Debug" -Recurse -Filter libisaver_fs_helper.so |
        Where-Object { $_.FullName -match 'obj[\\/]arm64-v8a[\\/]libisaver_fs_helper\.so$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $HelperPath -or -not (Test-Path -LiteralPath $HelperPath)) {
    throw "arm64-v8a helper not found; run assembleDebug or pass -HelperPath"
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

    return Invoke-Adb -Arguments @(
        "shell",
        "su",
        "-c",
        (Quote-ShellArgument $Command)
    ) -ExpectedExit $ExpectedExit
}

function Quote-ShellArgument([string]$Value) {
    if ($Value.Contains([char]0)) {
        throw "Shell arguments cannot contain NUL"
    }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Assert-Equal {
    param(
        $Expected,
        $Actual,
        [string]$Message
    )

    if ($Actual -cne $Expected) {
        throw "$Message; expected=[$Expected], actual=[$Actual]"
    }
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

function Decode-Base64Utf8([string]$Encoded) {
    try {
        $bytes = [Convert]::FromBase64String($Encoded)
        if ([Convert]::ToBase64String($bytes) -cne $Encoded) {
            throw "Non-canonical Base64"
        }
        return $strictUtf8.GetString($bytes)
    }
    catch {
        throw "Invalid Base64 UTF-8 field: $($_.Exception.Message)"
    }
}

function Read-Listing([string]$Directory) {
    $command = "$(Quote-ShellArgument $remoteHelper) 'list-dir' $(Quote-ShellArgument $Directory)"
    $lines = @(Invoke-Root $command)
    Assert-True ($lines.Count -ge 1) "list-dir returned no protocol header"

    $protocolBytes = 0L
    foreach ($line in $lines) {
        $protocolBytes += $strictUtf8.GetByteCount([string]$line) + 1L
    }
    Assert-True ($protocolBytes -le $maxProtocolBytes) "Successful protocol exceeded 64 MiB"

    $headerFields = @(([string]$lines[0]) -split "`t")
    Assert-Equal 5 $headerFields.Count "Header field count"
    Assert-Equal "ISAVER_LIST_V1" $headerFields[0] "Protocol version"
    $parentDevice = [long]::Parse($headerFields[1], [Globalization.CultureInfo]::InvariantCulture)
    $parentInode = [long]::Parse($headerFields[2], [Globalization.CultureInfo]::InvariantCulture)
    Assert-True ($parentDevice -ge 0) "Parent device identity was negative"
    Assert-True ($parentInode -ge 0) "Parent inode identity was negative"
    Assert-True ($headerFields[3] -in @("0", "1")) "Parent readable flag is not binary"
    Assert-True ($headerFields[4] -in @("0", "1")) "Parent writable flag is not binary"

    $records = @()
    for ($lineIndex = 1; $lineIndex -lt $lines.Count; $lineIndex++) {
        $fields = @(([string]$lines[$lineIndex]) -split "`t")
        Assert-Equal 8 $fields.Count "Record field count at index $($lineIndex - 1)"
        foreach ($field in $fields) {
            Assert-True ($strictUtf8.GetByteCount($field) -le $maxFieldBytes) "Record field exceeded 1 MiB"
        }
        Assert-True ($fields[2] -cin @("directory", "file", "other")) "Unknown record type"
        Assert-True ($fields[5] -in @("0", "1")) "Record readable flag is not binary"
        Assert-True ($fields[6] -in @("0", "1")) "Record writable flag is not binary"
        Assert-True ($fields[7] -in @("0", "1")) "Record symlink flag is not binary"
        $records += [pscustomobject]@{
            Name = Decode-Base64Utf8 $fields[0]
            Path = Decode-Base64Utf8 $fields[1]
            Type = $fields[2]
            Size = $fields[3]
            Modified = $fields[4]
            Readable = $fields[5]
            Writable = $fields[6]
            Symlink = $fields[7]
        }
    }

    return [pscustomobject]@{
        Header = $headerFields
        Records = @($records)
        ProtocolBytes = $protocolBytes
    }
}

function New-NumberedFiles {
    param(
        [string]$Directory,
        [int]$Count,
        [string]$Prefix
    )

    $command = 'dir={0}; prefix={1}; i=0; while [ "$i" -lt {2} ]; do : > "$dir/$prefix$i"; i=$((i + 1)); done' -f @(
        (Quote-ShellArgument $Directory),
        (Quote-ShellArgument $Prefix),
        $Count
    )
    Invoke-Root $command | Out-Null
}

function New-SpecialFile {
    param(
        [string]$Directory,
        [string]$Name
    )

    $encodedName = [Convert]::ToBase64String($strictUtf8.GetBytes($Name))
    $command = 'dir={0}; name=$(printf %s {1} | base64 -d); : > "$dir/$name"' -f @(
        (Quote-ShellArgument $Directory),
        (Quote-ShellArgument $encodedName)
    )
    Invoke-Root $command | Out-Null
}

function Assert-ListingCount {
    param(
        [string]$Directory,
        [int]$ExpectedCount
    )

    $listing = Read-Listing $Directory
    Assert-Equal $ExpectedCount $listing.Records.Count "Entry count for $Directory"
    Write-Host "PASS: list-dir returned $ExpectedCount entries"
}

function Assert-LimitFailure {
    param(
        [string]$Directory,
        [string]$Message
    )

    $command = "$(Quote-ShellArgument $remoteHelper) 'list-dir' $(Quote-ShellArgument $Directory) > $(Quote-ShellArgument $limitOutputPath)"
    Invoke-Root $command $limitExitCode | Out-Null
    $capturedSize = [long]::Parse(
        ((Invoke-Root "stat -c %s -- $(Quote-ShellArgument $limitOutputPath)") -join "").Trim(),
        [Globalization.CultureInfo]::InvariantCulture
    )
    Assert-Equal 0L $capturedSize "$Message emitted a partial protocol before failing"
    Write-Host "PASS: $Message returned typed limit exit $limitExitCode"
}

$deviceOutput | Out-Host
$rootIdentity = (Invoke-Root "id") -join "`n"
Assert-True ($rootIdentity -match '(?:^|\s)uid=0\(root\)(?:\s|$)') "su did not return a Root identity"
$rootIdentity | Out-Host

try {
    Invoke-Adb -Arguments @("push", $HelperPath, $remoteHelper) | Out-Host
    Invoke-Root "chmod 0755 $(Quote-ShellArgument $remoteHelper)" | Out-Null
    Invoke-Root "rm -rf -- $(Quote-ShellArgument $testRoot)" | Out-Null
    Invoke-Root "mkdir -p -- $(Quote-ShellArgument $testRoot)" | Out-Null

    $emptyDirectory = "$testRoot/empty"
    $fiftyDirectory = "$testRoot/fifty"
    $twoHundredDirectory = "$testRoot/two-hundred"
    $specialDirectory = "$testRoot/special"
    $countLimitDirectory = "$testRoot/count-limit"
    $byteLimitDirectory = "$testRoot/byte-limit"
    Invoke-Root "mkdir -p -- $(Quote-ShellArgument $emptyDirectory) $(Quote-ShellArgument $fiftyDirectory) $(Quote-ShellArgument $twoHundredDirectory) $(Quote-ShellArgument $specialDirectory)" | Out-Null

    New-NumberedFiles $fiftyDirectory 50 "item-"
    New-NumberedFiles $twoHundredDirectory 200 "item-"
    $emptyListing = Read-Listing $emptyDirectory
    Assert-Equal 0 $emptyListing.Records.Count "Empty directory entry count"
    Assert-Equal "1" $emptyListing.Header[3] "Opened parent readable capability"
    Assert-Equal "1" $emptyListing.Header[4] "Writable fixture parent capability"
    Write-Host "PASS: list-dir returned 0 entries with live parent capabilities"
    Assert-ListingCount "$emptyDirectory////" 0
    Assert-ListingCount $fiftyDirectory 50
    Assert-ListingCount $twoHundredDirectory 200

    $specialNames = @(
        "with space",
        "中文文件",
        "'single' and `"double`"",
        "line one`nline two",
        "-leading"
    )
    foreach ($name in $specialNames) {
        New-SpecialFile $specialDirectory $name
    }
    New-SpecialFile $specialDirectory "target"
    Invoke-Root "ln -s -- 'target' $(Quote-ShellArgument "$specialDirectory/link-to-target")" | Out-Null

    $specialListing = Read-Listing $specialDirectory
    foreach ($name in $specialNames) {
        $matches = @($specialListing.Records | Where-Object { $_.Name -ceq $name })
        Assert-Equal 1 $matches.Count "Special filename round trip: $name"
        Assert-Equal "$specialDirectory/$name" $matches[0].Path "Special path round trip: $name"
        Assert-Equal "1" $matches[0].Readable "Regular fixture readable capability: $name"
        Assert-Equal "1" $matches[0].Writable "Regular fixture writable capability: $name"
    }
    $linkRecord = @($specialListing.Records | Where-Object { $_.Name -ceq "link-to-target" })
    Assert-Equal 1 $linkRecord.Count "Symlink record count"
    Assert-Equal "other" $linkRecord[0].Type "Symlink type"
    Assert-Equal "-" $linkRecord[0].Size "Symlink size"
    Assert-Equal "1" $linkRecord[0].Symlink "Symlink flag"
    Assert-Equal "0" $linkRecord[0].Readable "Symlink readable hint"
    Assert-Equal "0" $linkRecord[0].Writable "Symlink writable hint"
    $trailingSlashListing = Read-Listing "$specialDirectory/"
    $trailingTarget = @($trailingSlashListing.Records | Where-Object { $_.Name -ceq "target" })
    Assert-Equal 1 $trailingTarget.Count "Trailing-slash target record count"
    Assert-Equal "$specialDirectory/target" $trailingTarget[0].Path "Trailing-slash child path"
    Write-Host "PASS: spaces, quotes, Chinese, embedded newline, leading dash, and symlink metadata round trip"

    Invoke-Root "ln -s -- $(Quote-ShellArgument $specialDirectory) $(Quote-ShellArgument "$testRoot/special-link")" | Out-Null
    Invoke-Root "$(Quote-ShellArgument $remoteHelper) 'list-dir' $(Quote-ShellArgument "$testRoot/special-link")" 45 | Out-Null
    Invoke-Root "$(Quote-ShellArgument $remoteHelper) 'list-dir' $(Quote-ShellArgument "$testRoot/special-link/")" 45 | Out-Null
    Invoke-Root "$(Quote-ShellArgument $remoteHelper) 'list-dir' ''" 44 | Out-Null
    Invoke-Root "$(Quote-ShellArgument $remoteHelper) 'list-dir' $(Quote-ShellArgument $specialDirectory) 'extra'" 64 | Out-Null
    Invoke-Root "$(Quote-ShellArgument $remoteHelper) 'run' 'id'" 64 | Out-Null
    Write-Host "PASS: list-dir rejects symlink parents with or without trailing slashes, empty paths, extra arguments, and generic commands"

    if (-not $RemoteStrace) {
        $RemoteStrace = ((Invoke-Root 'for candidate in /data/local/tmp/strace /system/bin/strace /system/xbin/strace; do if [ -x "$candidate" ]; then printf "%s\n" "$candidate"; break; fi; done') -join "").Trim()
    }
    if (-not $RemoteStrace) {
        throw "strace is required to prove list-dir starts no per-entry child process"
    }
    $traceCommand = "$(Quote-ShellArgument $RemoteStrace) -f -e trace=process,%file,write,writev,pwrite64,pwritev,pwritev2,fallocate -o $(Quote-ShellArgument $tracePath) -- $(Quote-ShellArgument $remoteHelper) list-dir $(Quote-ShellArgument $twoHundredDirectory) > $(Quote-ShellArgument $traceOutputPath)"
    Invoke-Root $traceCommand | Out-Null
    $traceText = (Invoke-Root "cat -- $(Quote-ShellArgument $tracePath)") -join "`n"
    Assert-True (-not [string]::IsNullOrWhiteSpace($traceText)) "strace output was empty"
    Assert-True ($traceText -match '\+\+\+ exited with 0 \+\+\+') "strace did not observe a clean helper exit"
    $traceProtocolLines = [long]::Parse(
        ((Invoke-Root "wc -l < $(Quote-ShellArgument $traceOutputPath)") -join "").Trim(),
        [Globalization.CultureInfo]::InvariantCulture
    )
    Assert-Equal 201 $traceProtocolLines "Traced 200-entry protocol line count"
    Assert-Equal 1 ([regex]::Matches($traceText, '\bexecve\(').Count) "list-dir execve count"
    Assert-Equal 0 ([regex]::Matches($traceText, '\b(?:clone|clone3|fork|vfork)\(').Count) "list-dir child process count"
    $mutatingCallPattern = '\b(?:creat|mkdir|mkdirat|rmdir|unlink|unlinkat|rename|renameat|renameat2|truncate|ftruncate|fallocate|link|linkat|symlink|symlinkat|mknod|mknodat|chmod|fchmod|fchmodat|chown|lchown|fchown|fchownat|utime|utimes|utimensat|futimesat|setxattr|lsetxattr|fsetxattr|removexattr|lremovexattr|fremovexattr|pwrite64|pwritev|pwritev2)\('
    Assert-Equal 0 ([regex]::Matches($traceText, $mutatingCallPattern).Count) "list-dir mutating syscall count"
    $writableOpenPattern = '\bopen(?:at|at2)?\([^\r\n]*(?:O_(?:WRONLY|RDWR|CREAT|TRUNC|APPEND|TMPFILE)|0x[0-9a-fA-F]+\s*/\*[^*]*(?:WRONLY|RDWR|CREAT|TRUNC|APPEND|TMPFILE))'
    $writableOpenLines = @(($traceText -split "`n") | Where-Object { $_ -match $writableOpenPattern })
    $unexpectedWritableOpens = @($writableOpenLines | Where-Object {
        $_ -notmatch '\bopenat\(AT_FDCWD, "/dev/null", O_RDWR(?:\|O_CLOEXEC)?\)\s+=\s+\d+\s*$'
    })
    Assert-Equal 0 $unexpectedWritableOpens.Count "list-dir writable open count: $($unexpectedWritableOpens -join '; ')"
    foreach ($writeMatch in [regex]::Matches($traceText, '\bwritev?\((\d+),')) {
        Assert-Equal "1" $writeMatch.Groups[1].Value "list-dir may write only its stdout protocol"
    }
    Assert-True ($traceText -notmatch '/system/bin/(?:stat|base64|printf)') "list-dir spawned a legacy per-entry utility"
    Write-Host "PASS: one helper process enumerated 200 entries without filesystem mutation"

    Invoke-Root "mkdir -p -- $(Quote-ShellArgument $countLimitDirectory)" | Out-Null
    New-NumberedFiles $countLimitDirectory $maxRecordCount "i"
    $atLimitCommand = "$(Quote-ShellArgument $remoteHelper) 'list-dir' $(Quote-ShellArgument $countLimitDirectory) > $(Quote-ShellArgument $limitOutputPath)"
    Invoke-Root $atLimitCommand | Out-Null
    $lineCount = [long]::Parse(
        ((Invoke-Root "wc -l < $(Quote-ShellArgument $limitOutputPath)") -join "").Trim(),
        [Globalization.CultureInfo]::InvariantCulture
    )
    Assert-Equal ($maxRecordCount + 1) $lineCount "Protocol line count at exact item limit"
    Invoke-Root ": > $(Quote-ShellArgument "$countLimitDirectory/i$maxRecordCount")" | Out-Null
    Assert-LimitFailure $countLimitDirectory "100,001-entry directory"

    Invoke-Root "rm -rf -- $(Quote-ShellArgument $countLimitDirectory); mkdir -p -- $(Quote-ShellArgument $byteLimitDirectory)" | Out-Null
    $longPrefix = ("x" * 245) -join ""
    New-NumberedFiles $byteLimitDirectory 90000 $longPrefix
    Assert-LimitFailure $byteLimitDirectory "sub-100,000-entry protocol larger than 64 MiB"

    Write-Host "All Root list helper checks passed."
}
finally {
    try {
        Invoke-Root "rm -rf -- $(Quote-ShellArgument $testRoot)" | Out-Null
    }
    catch {
        Write-Warning "Could not clean list helper fixture: $($_.Exception.Message)"
    }
    try {
        Invoke-Root "rm -f -- $(Quote-ShellArgument $remoteHelper)" | Out-Null
    }
    catch {
        Write-Warning "Could not remove deployed list helper: $($_.Exception.Message)"
    }
}
