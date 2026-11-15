$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-unified-stop-all-small-20261013-v30'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-completion-all-small-20261012-v29-package'
$out = Join-Path $root "dist/$name.zip"
if ((Test-Path -LiteralPath $pkg) -or (Test-Path -LiteralPath $out)) { throw 'Package already exists; use a fresh version.' }
New-Item -ItemType Directory -Path "$pkg/payload" | Out-Null
$jdk = (Resolve-Path "$root/target/build-tools-v21/jdk/jdk8u462-full").Path
& "$jdk/bin/javac.exe" -encoding UTF-8 -d "$pkg/payload" "$PSScriptRoot/JarPatchApplier.java"
if ($LASTEXITCODE -ne 0) { throw 'Patcher compilation failed.' }
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$previous/V28-说明.md" "$pkg/"
Copy-Item "$previous/V29-说明.md" "$pkg/"
Copy-Item "$root/docs/releases/V30-统一结束门禁更新.md" "$pkg/V30-说明.md"
$script = [IO.File]::ReadAllText("$previous/Update-Minion-V29.cmd")
$script = $script.Replace('V29','V30').Replace('v29','v30')
$script = $script.Replace('Completion Combined Update','Unified Stop Gate Combined Update')
$script = $script.Replace('completion combined update','unified stop gate combined update')
$script = $script.Replace('Completion combined update','Unified stop gate combined update')
$script = $script.Replace('Includes V28 plus completion checkpoint recovery and real completion timestamps.',
    'Includes V29 plus unified read-only completion review for main and child agents.')
$script = $script.Replace('Upgrade from V27 or V28; preserves settings, context thresholds, projects and history.',
    'Upgrade from V27, V28 or V29; preserves settings, context thresholds, projects and history.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V30.cmd",$script,[Text.Encoding]::ASCII)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$patch = "$pkg/payload/classes-patch.zip"
$zip = [IO.Compression.ZipFile]::Open($patch,'Create')
$count = 0
$entryNames = New-Object 'System.Collections.Generic.HashSet[string]'
try {
    $classesRoot = (Resolve-Path "$root/target/classes").Path
    foreach ($file in (Get-ChildItem "$classesRoot/com/minion" -Recurse -Filter '*.class')) {
        $bytes = [IO.File]::ReadAllBytes($file.FullName)
        if ($bytes[6]*256+$bytes[7] -gt 52) { throw "Not Java 8 compatible: $($file.Name)" }
        $entry = $file.FullName.Substring($classesRoot.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$entry) | Out-Null
        $entryNames.Add($entry) | Out-Null
        $count++
    }
    if ($count -lt 300) { throw "Application class set incomplete: $count" }
    foreach ($required in @('com/minion/core/agent/StopGate.class','com/minion/core/llm/StopReviewResponse.class','com/minion/gui/TaskDashboard.class')) {
        if (-not $entryNames.Contains($required)) { throw "Required V30 class missing: $required" }
    }
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$classesRoot/theme/theme.css",'theme/theme.css') | Out-Null
} finally { $zip.Dispose() }
[IO.File]::WriteAllText("$patch.sha256",(Get-FileHash $patch -Algorithm SHA256).Hash+"`r`n",[Text.Encoding]::ASCII)
[IO.Compression.ZipFile]::CreateFromDirectory($pkg,$out)
"Shipped application classes: $count"
Get-Item $out | Format-List FullName,Length
Get-FileHash $out -Algorithm SHA256 | Format-List Hash
