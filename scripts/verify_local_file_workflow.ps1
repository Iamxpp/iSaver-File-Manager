param(
    [string]$Serial = "d51f42ac"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$fixtureRoot = "/storage/emulated/0/Download/iSaver-M9-ui-flow"
$remoteDump = "/data/local/tmp/isaver-ui-flow.xml"
$zipBase64 = "UEsDBBQAAAAIAOwO81zh8cyyEgAAAAoAAAANAAAAZG9jcy9maWxlLnR4dCrNVEgsSs7ILEsFAAAA//8DAFBLAQIUABQAAAAIAOwO81zh8cyyEgAAAAoAAAANAAAAAAAAAAAAAAAAAAAAAABkb2NzL2ZpbGUudHh0UEsFBgAAAAABAAEAOwAAAD0AAAAAAA=="
$localDump = Join-Path ([System.IO.Path]::GetTempPath()) "isaver-ui-flow-$PID.xml"
$localNote = Join-Path ([System.IO.Path]::GetTempPath()) "isaver-ui-flow-$PID-note.txt"
$localZip = Join-Path ([System.IO.Path]::GetTempPath()) "isaver-ui-flow-$PID-sample.zip"
$remoteNote = "/data/local/tmp/isaver-ui-flow-note.txt"
$remoteZip = "/data/local/tmp/isaver-ui-flow-sample.zip"

if ($Serial -cne "d51f42ac") {
    throw "Local workflow verification is pinned to Xiaomi 9 serial d51f42ac; got $Serial"
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $output = @(& adb -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') exited with $LASTEXITCODE`n$($output -join "`n")"
    }
    return $output
}

function Invoke-Root {
    param([string]$Command)
    return Invoke-Adb -Arguments @("shell", "su", "-c", $Command)
}

function Get-UiDocument {
    Invoke-Adb -Arguments @("shell", "uiautomator", "dump", $remoteDump) | Out-Null
    Invoke-Adb -Arguments @("pull", $remoteDump, $localDump) | Out-Null
    return [xml](Get-Content -LiteralPath $localDump -Raw)
}

function Get-Attribute {
    param(
        [System.Xml.XmlElement]$Node,
        [string]$Name
    )
    return $Node.GetAttribute($Name)
}

function Get-Center {
    param([System.Xml.XmlElement]$Node)
    $bounds = Get-Attribute -Node $Node -Name "bounds"
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Invalid UI bounds: $bounds"
    }
    return [pscustomobject]@{
        X = [int](([int]$matches[1] + [int]$matches[3]) / 2)
        Y = [int](([int]$matches[2] + [int]$matches[4]) / 2)
    }
}

function Get-ActionNode {
    param([System.Xml.XmlElement]$Node)
    $current = $Node
    while ($null -ne $current -and $current.Name -eq "node") {
        if ((Get-Attribute -Node $current -Name "clickable") -eq "true") {
            return $current
        }
        $current = $current.ParentNode
    }
    return $Node
}

function Assert-RecentActivityRow {
    param(
        [string]$Title,
        [string]$ActivityPrefix
    )
    $document = Get-UiDocument
    $titleNode = @($document.SelectNodes("//node") | Where-Object {
        (Get-Attribute -Node $_ -Name "text") -ceq $Title
    }) | Select-Object -Last 1
    if ($null -eq $titleNode) {
        throw "Recent item '$Title' was not found"
    }
    $row = Get-ActionNode -Node $titleNode
    $activity = @($row.SelectNodes(".//node") | Where-Object {
        (Get-Attribute -Node $_ -Name "text").StartsWith($ActivityPrefix)
    })
    if ($activity.Count -eq 0) {
        throw "Recent item '$Title' does not contain activity '$ActivityPrefix' in the same row"
    }
}

function Find-Node {
    param(
        [scriptblock]$Predicate,
        [int]$TimeoutSeconds = 20,
        [string]$Description = "UI node"
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $document = Get-UiDocument
        $matches = @($document.SelectNodes("//node") | Where-Object $Predicate)
        if ($matches.Count -gt 0) {
            return $matches[-1]
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Description"
}

function Find-TextNode {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = 20
    )
    return Find-Node -TimeoutSeconds $TimeoutSeconds -Description "text '$Text'" -Predicate {
        (Get-Attribute -Node $_ -Name "text") -ceq $Text
    }
}

function Find-DescriptionNode {
    param(
        [string]$Description,
        [int]$TimeoutSeconds = 20
    )
    $expectedDescription = $Description
    return Find-Node -TimeoutSeconds $TimeoutSeconds -Description "description '$Description'" -Predicate {
        (Get-Attribute -Node $_ -Name "content-desc") -ceq $expectedDescription
    }
}

function Invoke-NodeClick {
    param([System.Xml.XmlElement]$Node)
    $target = Get-ActionNode -Node $Node
    $center = Get-Center -Node $target
    Invoke-Root -Command "input tap $($center.X) $($center.Y)" | Out-Null
}

function Click-Text {
    param([string]$Text)
    Invoke-NodeClick -Node (Find-TextNode -Text $Text)
}

function Click-Description {
    param([string]$Description)
    Invoke-NodeClick -Node (Find-DescriptionNode -Description $Description)
}

function Open-Directory {
    param([string]$Name)
    $node = $null
    for ($attempt = 0; $attempt -lt 20 -and $null -eq $node; $attempt++) {
        try {
            $node = Find-TextNode -Text $Name -TimeoutSeconds 2
        }
        catch {
            if ($attempt -eq 19) {
                throw
            }
            Invoke-Root -Command "input swipe 540 1800 540 600 300" | Out-Null
            Start-Sleep -Milliseconds 250
        }
    }
    Invoke-NodeClick -Node $node
    Find-DescriptionNode -Description "页面标题：$Name" | Out-Null
}

$devices = @(& adb devices -l)
if (@($devices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device(?:\s|$)" }).Count -ne 1) {
    throw "Target serial is not uniquely online: $Serial`n$($devices -join "`n")"
}

try {
    $identity = (Invoke-Root -Command "id") -join "`n"
    if ($identity -notmatch '(?:^|\s)uid=0\(root\)(?:\s|$)') {
        throw "su did not return uid=0: $identity"
    }

    [System.IO.File]::WriteAllText($localNote, "note")
    [System.IO.File]::WriteAllBytes($localZip, [Convert]::FromBase64String($zipBase64))
    Invoke-Adb -Arguments @("push", $localNote, $remoteNote) | Out-Null
    Invoke-Adb -Arguments @("push", $localZip, $remoteZip) | Out-Null
    Invoke-Root -Command "rm -rf -- $fixtureRoot" | Out-Null
    Invoke-Root -Command "mkdir -p -- $fixtureRoot" | Out-Null
    Invoke-Root -Command "mv -- $remoteNote $fixtureRoot/note.txt" | Out-Null
    Invoke-Root -Command "mv -- $remoteZip $fixtureRoot/sample.zip" | Out-Null
    Invoke-Adb -Arguments @("shell", "am", "force-stop", "com.iamxpp.isaver") | Out-Null
    $launch = (Invoke-Adb -Arguments @(
        "shell", "am", "start", "-W",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-n", "com.iamxpp.isaver/.MainActivity"
    )) -join "`n"
    if ($launch -notmatch 'Status:\s+ok') {
        throw "Activity launch failed`n$launch"
    }

    Find-DescriptionNode -Description "页面标题：视图" | Out-Null
    Click-Text -Text "下载"
    Open-Directory -Name "iSaver-M9-ui-flow"

    Click-Text -Text "note.txt"
    Find-TextNode -Text "note" | Out-Null
    Find-TextNode -Text "编辑" | Out-Null
    Click-Text -Text "关闭"
    Click-Text -Text "sample.zip"
    Find-TextNode -Text "ZIP" | Out-Null
    Find-TextNode -Text "docs" | Out-Null
    Click-Description -Description "更多操作"
    Click-Text -Text "解压"
    Find-DescriptionNode -Description "目标不可用：虚拟视图文件夹只用于分组，不能作为文件操作目标。请选择一个真实文件夹。" | Out-Null

    Click-Text -Text "下载"
    Open-Directory -Name "iSaver-M9-ui-flow"
    Find-DescriptionNode -Description "解压到此处" | Out-Null
    Click-Description -Description "解压到此处"
    Find-TextNode -Text "docs" -TimeoutSeconds 30 | Out-Null
    Open-Directory -Name "docs"
    Find-TextNode -Text "file.txt" | Out-Null
    Click-Text -Text "最近项目"
    Find-TextNode -Text "sample" -TimeoutSeconds 30 | Out-Null
    Assert-RecentActivityRow -Title "sample" -ActivityPrefix "已解压"

    Write-Host "Local file, archive extraction, and recent activity workflow passed."
}
finally {
    try {
        Invoke-Root -Command "rm -rf -- $fixtureRoot $remoteDump $remoteNote $remoteZip" | Out-Null
    }
    catch {
        Write-Warning "Could not clean local workflow fixtures: $($_.Exception.Message)"
    }
    Remove-Item -LiteralPath $localDump -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $localNote -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $localZip -Force -ErrorAction SilentlyContinue
}
