param(
    [string]$Serial = $env:ISAVER_ADB_SERIAL,
    [string]$HelperPath = "",
    [int]$RaceSizeMiB = 256
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$testRoot = "/data/local/tmp/isaver-test/root-transfer-helper"
$remoteHelper = "/data/local/tmp/isaver_fs_helper_verify"
$enospcMount = "$testRoot/enospc-target"
$enospcMounted = $false
$raceJob = $null
$mutationJob = $null

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

function Invoke-Adb([string[]]$Arguments, [int]$ExpectedExit = 0) {
    $output = & adb -s $Serial @Arguments 2>&1
    $exit = $LASTEXITCODE
    if ($exit -ne $ExpectedExit) {
        throw "adb exit $exit, expected ${ExpectedExit}: $Arguments`n$output"
    }
    return $output
}

function Invoke-Root([string]$Command, [int]$ExpectedExit = 0) {
    $escaped = $Command.Replace("'", "'\''")
    return Invoke-Adb -Arguments @("shell", "su -c '$escaped'") -ExpectedExit $ExpectedExit
}

function Assert-Root([string]$Command, [string]$Message) {
    Invoke-Root $Command | Out-Null
    Write-Host "PASS: $Message"
}

function Prepare-Stage([string]$Parent, [string]$Stage, [string]$ParentArgs) {
    return (Invoke-Root "$remoteHelper prepare-stage $Parent $Parent $Stage $ParentArgs").Trim()
}

function Prepare-ExtractionStage([string]$Parent, [string]$Stage, [string]$ParentArgs) {
    return (Invoke-Root "$remoteHelper prepare-extract-stage $Parent $Parent $Stage $ParentArgs").Trim()
}

$deviceOutput | Out-Host
Invoke-Root "id" | Out-Host

try {
    Invoke-Adb @("push", $HelperPath, $remoteHelper) | Out-Host
    Invoke-Root "chmod 755 $remoteHelper" | Out-Null
    Invoke-Root "rm -rf $testRoot" | Out-Null
    Invoke-Root "mkdir -p $testRoot" | Out-Null
    Invoke-Root "chmod 1777 $testRoot" | Out-Null

    $invalidStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174087"
    Invoke-Root "$remoteHelper prepare-stage $testRoot $testRoot $invalidStage +1 2" 64 | Out-Null
    Invoke-Root "$remoteHelper prepare-stage $testRoot $testRoot $invalidStage -1 2" 64 | Out-Null
    Invoke-Root "$remoteHelper prepare-stage $testRoot $testRoot $invalidStage 18446744073709551616 2" 64 | Out-Null
    Invoke-Root "$remoteHelper prepare-stage $testRoot $testRoot $invalidStage '' 2" 64 | Out-Null
    Write-Host "PASS: numeric arguments reject empty, signs, and overflow"

    # The parent remains writable to shell, while sticky ownership and stage 0700 protect the stage.
    Invoke-Adb @("shell", "touch", "$testRoot/shell-control") | Out-Null
    Invoke-Adb @("shell", "rm", "$testRoot/shell-control") | Out-Null
    $permissionParentIdentity = (Invoke-Root "stat -c '%d:%i' $testRoot").Trim()
    $permissionParentArgs = $permissionParentIdentity -replace ':', ' '
    $permissionStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174089"
    $permissionStageId = Prepare-Stage $testRoot $permissionStage $permissionParentArgs
    Invoke-Adb @("shell", "ls", "$testRoot/$permissionStage") 1 | Out-Null
    Invoke-Adb @("shell", "touch", "$testRoot/$permissionStage/payload") 1 | Out-Null
    Invoke-Adb @("shell", "mv", "$testRoot/$permissionStage", "$testRoot/stage-replaced") 1 | Out-Null
    Invoke-Adb @("shell", "rmdir", "$testRoot/$permissionStage") 1 | Out-Null
    $permissionStageAfter = (Invoke-Root "stat -c '%d:%i:%u:%a' $testRoot/$permissionStage").Trim()
    if ($permissionStageAfter -ne "$($permissionStageId -replace ':',':'):0:700") {
        throw "shell changed protected stage: $permissionStageAfter"
    }
    $permissionContents = @(Invoke-Root "ls -A $testRoot/$permissionStage")
    if ($permissionContents.Count -gt 0) {
        throw "shell created content inside protected stage"
    }
    Write-Host "PASS: shell can write parent but cannot read, populate, rename, or remove protected stage"
    Invoke-Root "$remoteHelper remove-stage $testRoot $testRoot $permissionStage $permissionParentArgs $($permissionStageId -replace ':',' ')" | Out-Null

    # Isolated 64 KiB tmpfs creates a repeatable ENOSPC without affecting any existing filesystem.
    Invoke-Root "mkdir -p $enospcMount" | Out-Null
    $mountOutput = & adb -s $Serial shell su -c "mount -t tmpfs -o size=65536 tmpfs $enospcMount" 2>&1
    $mountExit = $LASTEXITCODE
    if ($mountExit -ne 0) {
        Write-Warning "SKIP ENOSPC: isolated tmpfs mount failed exit=$mountExit output=$mountOutput"
    } else {
        $enospcMounted = $true
        try {
            Invoke-Root "chmod 0777 $enospcMount" | Out-Null
            Invoke-Root "dd if=/dev/zero of=$testRoot/enospc-source.bin bs=1048576 count=1" | Out-Null
            Invoke-Root "df -k $enospcMount" | Out-Host
            $enospcParentIdentity = (Invoke-Root "stat -c '%d:%i' $enospcMount").Trim()
            $enospcParentArgs = $enospcParentIdentity -replace ':', ' '
            $enospcSource = (Invoke-Root "stat -c '%d:%i:%s' $testRoot/enospc-source.bin").Trim().Split(':')
            $enospcStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174088"
            $enospcStageId = Prepare-Stage $enospcMount $enospcStage $enospcParentArgs
            $enospcCommand = "cat $testRoot/enospc-source.bin | $remoteHelper copy-publish-stdin $enospcMount $enospcMount $enospcStage too-large.bin $enospcParentArgs $($enospcStageId -replace ':',' ') $($enospcSource[2])"
            $enospcOutput = & adb -s $Serial shell su -c $enospcCommand 2>&1
            $enospcExit = $LASTEXITCODE
            if ($enospcExit -eq 50) {
                Assert-Root "test ! -e $enospcMount/too-large.bin" "ENOSPC did not publish a final file"
                Assert-Root "test ! -e $enospcMount/$enospcStage" "ENOSPC cleaned stage and payload"
            } else {
                Write-Warning "SKIP ENOSPC stdin fixture: helper exit=$enospcExit output=$enospcOutput"
                Invoke-Root "$remoteHelper remove-stage $enospcMount $enospcMount $enospcStage $enospcParentArgs $($enospcStageId -replace ':',' ')" | Out-Null
            }
        }
        finally {
            Invoke-Root "umount $enospcMount" | Out-Null
            $enospcMounted = $false
        }
    }

    Invoke-Root "chmod 0777 $testRoot" | Out-Null
    Invoke-Root "dd if=/dev/zero of=$testRoot/source.bin bs=1048576 count=$RaceSizeMiB" | Out-Null

    $parentIdentity = (Invoke-Root "stat -c '%d:%i' $testRoot").Trim()
    $parentArgs = $parentIdentity -replace ':', ' '
    $source = (Invoke-Root "stat -c '%d:%i:%s' $testRoot/source.bin").Trim().Split(':')

    $timeoutStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174085"
    $timeoutId = Prepare-Stage $testRoot $timeoutStage $parentArgs
    Invoke-Root "cat $testRoot/source.bin | /system/bin/timeout -s KILL 0.001 $remoteHelper copy-publish-stdin $testRoot $testRoot $timeoutStage timed-out.bin $parentArgs $($timeoutId -replace ':',' ') $($source[2])" 137 | Out-Null
    Assert-Root "test ! -e $testRoot/timed-out.bin" "toybox timeout did not claim a completed final"
    Invoke-Root "$remoteHelper remove-stage $testRoot $testRoot $timeoutStage $parentArgs $($timeoutId -replace ':',' ')" | Out-Null
    Assert-Root "test ! -e $testRoot/$timeoutStage" "toybox timeout stage reconciled by identity"

    Write-Warning "SKIP publication replacement race: the stdin copy window is shorter than one ADB polling round-trip"

    # An extra byte after the declared stream length must fail and clean the stage.
    $mutationStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174086"
    $mutationId = Prepare-Stage $testRoot $mutationStage $parentArgs
    Invoke-Root "cp $testRoot/source.bin $testRoot/source-extra.bin && printf x >> $testRoot/source-extra.bin" | Out-Null
    Invoke-Root "cat $testRoot/source-extra.bin | $remoteHelper copy-publish-stdin $testRoot $testRoot $mutationStage modified.bin $parentArgs $($mutationId -replace ':',' ') $($source[2])" 54 | Out-Null
    Assert-Root "test ! -e $testRoot/modified.bin" "extra source byte did not publish final"
    Assert-Root "test ! -e $testRoot/$mutationStage" "extra source byte cleaned stage"

    Invoke-Root "rm -rf $testRoot" | Out-Null
    Invoke-Root "mkdir -p $testRoot" | Out-Null
    Invoke-Root "chmod 0777 $testRoot" | Out-Null
    Invoke-Root "cp /system/etc/hosts $testRoot/source.bin" | Out-Null
    $parentIdentity = (Invoke-Root "stat -c '%d:%i' $testRoot").Trim()
    $parentArgs = $parentIdentity -replace ':', ' '
    $sourceMeta = (Invoke-Root "stat -c '%d:%i:%s' $testRoot/source.bin").Trim()
    $source = $sourceMeta.Split(':')

    $wrongStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174091"
    $wrongId = (Prepare-Stage $testRoot $wrongStage $parentArgs).Split(':')
    Invoke-Root "$remoteHelper remove-stage $testRoot $testRoot $wrongStage $parentArgs $($wrongId[0]) $([long]$wrongId[1] + 1)" 53 | Out-Null
    Assert-Root "test -d $testRoot/$wrongStage" "wrong inode is preserved"
    Invoke-Root "$remoteHelper remove-stage $testRoot $testRoot $wrongStage $parentArgs $($wrongId[0]) $($wrongId[1])" | Out-Null

    $linkStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174092"
    $linkId = Prepare-Stage $testRoot $linkStage $parentArgs
    Invoke-Adb @("shell", "mv", "$testRoot/$linkStage", "$testRoot/stage-real") | Out-Null
    Invoke-Adb @("shell", "ln", "-s", "$testRoot/stage-real", "$testRoot/$linkStage") | Out-Null
    Invoke-Root "$remoteHelper remove-stage $testRoot $testRoot $linkStage $parentArgs $($linkId -replace ':',' ')" 53 | Out-Null
    Assert-Root "test -L $testRoot/$linkStage" "symlink replacement is preserved"

    $ownerStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174093"
    $ownerId = Prepare-Stage $testRoot $ownerStage $parentArgs
    Invoke-Adb @("shell", "rmdir", "$testRoot/$ownerStage") | Out-Null
    Invoke-Adb @("shell", "mkdir", "-m", "700", "$testRoot/$ownerStage") | Out-Null
    Invoke-Root "$remoteHelper remove-stage $testRoot $testRoot $ownerStage $parentArgs $($ownerId -replace ':',' ')" 53 | Out-Null
    Assert-Root "test -d $testRoot/$ownerStage" "non-root replacement is preserved"

    $modeStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174094"
    $modeId = Prepare-Stage $testRoot $modeStage $parentArgs
    Invoke-Root "chmod 755 $testRoot/$modeStage" | Out-Null
    Invoke-Root "$remoteHelper remove-stage $testRoot $testRoot $modeStage $parentArgs $($modeId -replace ':',' ')" 53 | Out-Null
    Assert-Root "test -d $testRoot/$modeStage" "non-0700 stage is preserved"

    $sourceStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174095"
    $sourceId = Prepare-Stage $testRoot $sourceStage $parentArgs
    Invoke-Root "mv $testRoot/source.bin $testRoot/source.old" | Out-Null
    Invoke-Root "cp /system/etc/hosts $testRoot/source.bin" | Out-Null
    $changedExpectedSize = [long]$source[2] + 1
    Invoke-Root "cat $testRoot/source.bin | $remoteHelper copy-publish-stdin $testRoot $testRoot $sourceStage changed.bin $parentArgs $($sourceId -replace ':',' ') $changedExpectedSize" 54 | Out-Null
    Assert-Root "test ! -e $testRoot/$sourceStage" "source-changed failure cleaned its stage"

    $unreadableStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174097"
    $unreadableId = Prepare-Stage $testRoot $unreadableStage $parentArgs
    Invoke-Root "cat $testRoot/missing-source.bin 2>/dev/null | $remoteHelper copy-publish-stdin $testRoot $testRoot $unreadableStage unreadable.bin $parentArgs $($unreadableId -replace ':',' ') 1" 54 | Out-Null
    Assert-Root "test ! -e $testRoot/$unreadableStage" "source-unreadable failure cleaned its stage"

    $currentSource = (Invoke-Root "stat -c '%d:%i:%s' $testRoot/source.bin").Trim().Split(':')
    $existsStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174096"
    $existsId = Prepare-Stage $testRoot $existsStage $parentArgs
    Invoke-Root "cp $testRoot/source.bin $testRoot/existing.bin" | Out-Null
    Invoke-Root "cat $testRoot/source.bin | $remoteHelper copy-publish-stdin $testRoot $testRoot $existsStage existing.bin $parentArgs $($existsId -replace ':',' ') $($currentSource[2])" 49 | Out-Null
    Assert-Root "test ! -e $testRoot/$existsStage" "already-exists failure cleaned its stage"

    $extractStage = ".isaver-extract-123e4567-e89b-12d3-a456-426614174081"
    $extractId = Prepare-ExtractionStage $testRoot $extractStage $parentArgs
    Invoke-Root "$remoteHelper mkdir-extract $testRoot $testRoot $extractStage docs/sub $parentArgs $($extractId -replace ':',' ')" | Out-Null
    Invoke-Root "printf extraction-payload > $testRoot/extraction-expected.bin" | Out-Null
    Invoke-Root "printf extraction-payload | $remoteHelper copy-extract-stdin $testRoot $testRoot $extractStage docs/sub report.txt $parentArgs $($extractId -replace ':',' ') 18" | Out-Null
    Assert-Root "cmp $testRoot/extraction-expected.bin $testRoot/$extractStage/docs/sub/report.txt" "extraction stage wrote exact nested payload"
    Invoke-Root "$remoteHelper commit-extract-stage $testRoot $testRoot $extractStage backup $parentArgs $($extractId -replace ':',' ')" | Out-Null
    Assert-Root "test -f $testRoot/backup/docs/sub/report.txt" "extraction stage committed one visible directory"

    $collisionStage = ".isaver-extract-123e4567-e89b-12d3-a456-426614174082"
    $collisionId = Prepare-ExtractionStage $testRoot $collisionStage $parentArgs
    Invoke-Root "$remoteHelper commit-extract-stage $testRoot $testRoot $collisionStage backup $parentArgs $($collisionId -replace ':',' ')" 49 | Out-Null
    Assert-Root "test -d $testRoot/$collisionStage" "commit collision preserved the identity-bound stage"
    Invoke-Root "$remoteHelper remove-extract-stage $testRoot $testRoot $collisionStage $parentArgs $($collisionId -replace ':',' ')" | Out-Null

    $linkExtractStage = ".isaver-extract-123e4567-e89b-12d3-a456-426614174083"
    $linkExtractId = Prepare-ExtractionStage $testRoot $linkExtractStage $parentArgs
    Invoke-Root "ln -s /data/local/tmp $testRoot/$linkExtractStage/link" | Out-Null
    Invoke-Root "$remoteHelper mkdir-extract $testRoot $testRoot $linkExtractStage link/child $parentArgs $($linkExtractId -replace ':',' ')" 53 | Out-Null
    Invoke-Root "$remoteHelper remove-extract-stage $testRoot $testRoot $linkExtractStage $parentArgs $($linkExtractId -replace ':',' ')" | Out-Null
    Assert-Root "test ! -e $testRoot/$linkExtractStage" "recursive extraction cleanup unlinked symlink without following it"

    $swapExtractStage = ".isaver-extract-123e4567-e89b-12d3-a456-426614174084"
    $swapExtractId = Prepare-ExtractionStage $testRoot $swapExtractStage $parentArgs
    Invoke-Root "mv $testRoot/$swapExtractStage $testRoot/extract-original && mkdir -m 700 $testRoot/$swapExtractStage" | Out-Null
    Invoke-Root "$remoteHelper mkdir-extract $testRoot $testRoot $swapExtractStage child $parentArgs $($swapExtractId -replace ':',' ')" 53 | Out-Null
    Assert-Root "test -d $testRoot/extract-original -a -d $testRoot/$swapExtractStage" "extraction identity swap preserved both directories"

    $cancelExtractStage = ".isaver-extract-123e4567-e89b-12d3-a456-426614174085"
    $cancelExtractId = Prepare-ExtractionStage $testRoot $cancelExtractStage $parentArgs
    Invoke-Root "dd if=/dev/zero bs=1048576 count=64 2>/dev/null | /system/bin/timeout -s KILL 0.001 $remoteHelper copy-extract-stdin $testRoot $testRoot $cancelExtractStage '' large.bin $parentArgs $($cancelExtractId -replace ':',' ') 67108864" 137 | Out-Null
    Invoke-Root "$remoteHelper remove-extract-stage $testRoot $testRoot $cancelExtractStage $parentArgs $($cancelExtractId -replace ':',' ')" | Out-Null
    Assert-Root "test ! -e $testRoot/$cancelExtractStage" "cancelled extraction stage was cleaned by identity"

    Write-Host "All Root transfer and extraction helper checks passed."
}
finally {
    foreach ($backgroundJob in @($raceJob, $mutationJob)) {
        if ($null -ne $backgroundJob) {
            Stop-Job $backgroundJob -ErrorAction SilentlyContinue
            Wait-Job $backgroundJob -ErrorAction SilentlyContinue | Out-Null
            Remove-Job $backgroundJob -Force -ErrorAction SilentlyContinue
        }
    }
    if ($enospcMounted) {
        & adb -s $Serial shell su -c "umount $enospcMount" 2>$null | Out-Null
    }
    & adb -s $Serial shell su -c "rm -rf $testRoot" 2>$null | Out-Null
    & adb -s $Serial shell su -c "rm -f $remoteHelper" 2>$null | Out-Null
}
