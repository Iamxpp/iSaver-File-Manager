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
    return Invoke-Adb -Arguments @("shell", "su", "-c", $Command) -ExpectedExit $ExpectedExit
}

function Assert-Root([string]$Command, [string]$Message) {
    Invoke-Root $Command | Out-Null
    Write-Host "PASS: $Message"
}

function Prepare-Stage([string]$Parent, [string]$Stage, [string]$ParentArgs) {
    return (Invoke-Root "$remoteHelper prepare-stage $Parent $Parent $Stage $ParentArgs").Trim()
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
            Invoke-Root "$remoteHelper copy-publish $enospcMount $enospcMount $enospcStage too-large.bin $testRoot/enospc-source.bin $enospcParentArgs $($enospcStageId -replace ':',' ') $($enospcSource[0]) $($enospcSource[1]) $($enospcSource[2])" 50 | Out-Null
            Assert-Root "test ! -e $enospcMount/too-large.bin" "ENOSPC did not publish a final file"
            Assert-Root "test ! -e $enospcMount/$enospcStage" "ENOSPC cleaned stage and payload"
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
    Invoke-Root "/system/bin/timeout -s KILL 0.001 $remoteHelper copy-publish $testRoot $testRoot $timeoutStage timed-out.bin $testRoot/source.bin $parentArgs $($timeoutId -replace ':',' ') $($source[0]) $($source[1]) $($source[2])" 137 | Out-Null
    Assert-Root "test ! -e $testRoot/timed-out.bin" "toybox timeout did not claim a completed final"
    Invoke-Root "$remoteHelper remove-stage $testRoot $testRoot $timeoutStage $parentArgs $($timeoutId -replace ':',' ')" | Out-Null
    Assert-Root "test ! -e $testRoot/$timeoutStage" "toybox timeout stage reconciled by identity"

    # Replacement during a long copy: replacement and renamed original must survive; final may exist.
    $raceStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174090"
    $raceId = Prepare-Stage $testRoot $raceStage $parentArgs
    $raceCommand = "$remoteHelper copy-publish $testRoot $testRoot $raceStage final.bin $testRoot/source.bin $parentArgs $($raceId -replace ':',' ') $($source[0]) $($source[1]) $($source[2])"
    $raceJob = Start-Job -ScriptBlock {
        param($DeviceSerial, $Command)
        & adb -s $DeviceSerial shell su -c $Command 2>&1 | Out-String
        $LASTEXITCODE
    } -ArgumentList $Serial, $raceCommand
    $payloadSeen = $false
    for ($i = 0; $i -lt 1000; $i++) {
        & adb -s $Serial shell su -c "test -e $testRoot/$raceStage/payload" 2>$null
        if ($LASTEXITCODE -eq 0) { $payloadSeen = $true; break }
        Start-Sleep -Milliseconds 5
    }
    if (-not $payloadSeen) { throw "copy finished before replacement window was observed" }
    Invoke-Adb @("shell", "mv", "$testRoot/$raceStage", "$testRoot/stage-original") | Out-Null
    Invoke-Adb @("shell", "mkdir", "-m", "700", "$testRoot/$raceStage") | Out-Null
    Wait-Job $raceJob | Out-Null
    $jobOutput = @(Receive-Job $raceJob)
    Remove-Job $raceJob
    $raceJob = $null
    if ([int]$jobOutput[-1] -ne 55) { throw "replacement race expected exit 55: $jobOutput" }
    Assert-Root "test -d $testRoot/$raceStage" "replacement stage was not deleted"
    Assert-Root "test -d $testRoot/stage-original" "renamed original stage was not deleted"
    Assert-Root "test -f $testRoot/final.bin" "published final was not rolled back"

    # Modify the same source inode without changing its size while copy is active.
    $mutationStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174086"
    $mutationId = Prepare-Stage $testRoot $mutationStage $parentArgs
    $mutationCommand = "$remoteHelper copy-publish $testRoot $testRoot $mutationStage modified.bin $testRoot/source.bin $parentArgs $($mutationId -replace ':',' ') $($source[0]) $($source[1]) $($source[2])"
    $mutationJob = Start-Job -ScriptBlock {
        param($DeviceSerial, $Command)
        & adb -s $DeviceSerial shell su -c $Command 2>&1 | Out-String
        $LASTEXITCODE
    } -ArgumentList $Serial, $mutationCommand
    $mutationPayloadSeen = $false
    for ($i = 0; $i -lt 1000; $i++) {
        & adb -s $Serial shell su -c "test -e $testRoot/$mutationStage/payload" 2>$null
        if ($LASTEXITCODE -eq 0) { $mutationPayloadSeen = $true; break }
        Start-Sleep -Milliseconds 5
    }
    if (-not $mutationPayloadSeen) { throw "copy finished before source mutation window was observed" }
    Invoke-Root "dd if=/system/etc/hosts of=$testRoot/source.bin bs=1 count=1 conv=notrunc" | Out-Null
    Wait-Job $mutationJob | Out-Null
    $mutationOutput = @(Receive-Job $mutationJob)
    Remove-Job $mutationJob
    $mutationJob = $null
    if ([int]$mutationOutput[-1] -ne 54) { throw "in-place source mutation expected exit 54: $mutationOutput" }
    Assert-Root "test ! -e $testRoot/modified.bin" "in-place source mutation did not publish final"
    Assert-Root "test ! -e $testRoot/$mutationStage" "in-place source mutation cleaned stage"

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
    Invoke-Root "$remoteHelper copy-publish $testRoot $testRoot $sourceStage changed.bin $testRoot/source.bin $parentArgs $($sourceId -replace ':',' ') $($source[0]) $($source[1]) $($source[2])" 54 | Out-Null
    Assert-Root "test ! -e $testRoot/$sourceStage" "source-changed failure cleaned its stage"

    $unreadableStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174097"
    $unreadableId = Prepare-Stage $testRoot $unreadableStage $parentArgs
    Invoke-Root "$remoteHelper copy-publish $testRoot $testRoot $unreadableStage unreadable.bin $testRoot/missing-source.bin $parentArgs $($unreadableId -replace ':',' ') 1 2 1" 56 | Out-Null
    Assert-Root "test ! -e $testRoot/$unreadableStage" "source-unreadable failure cleaned its stage"

    $currentSource = (Invoke-Root "stat -c '%d:%i:%s' $testRoot/source.bin").Trim().Split(':')
    $existsStage = ".isaver-stage-123e4567-e89b-12d3-a456-426614174096"
    $existsId = Prepare-Stage $testRoot $existsStage $parentArgs
    Invoke-Root "cp $testRoot/source.bin $testRoot/existing.bin" | Out-Null
    Invoke-Root "$remoteHelper copy-publish $testRoot $testRoot $existsStage existing.bin $testRoot/source.bin $parentArgs $($existsId -replace ':',' ') $($currentSource[0]) $($currentSource[1]) $($currentSource[2])" 49 | Out-Null
    Assert-Root "test ! -e $testRoot/$existsStage" "already-exists failure cleaned its stage"

    Write-Host "All Root transfer helper checks passed."
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
