$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-context-threshold-all-small-20261010-v25'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-context-memory-small-20261008-v22-package'
if (Test-Path $pkg) { throw 'Package already exists; use a fresh version.' }
New-Item -ItemType Directory -Force "$pkg/payload" | Out-Null
Copy-Item "$previous/payload/JarPatchApplier.class" "$pkg/payload/"
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$root/docs/releases/V25-可配置自动压缩合并更新.md" "$pkg/V25-说明.md"
$features=[IO.File]::ReadAllText("$root/docs/releases/V24-合并任务工作流更新.md")
$installIndex=$features.IndexOf('## 安装与回退')
if($installIndex -ge 0) {$features=$features.Substring(0,$installIndex)}
[IO.File]::WriteAllText("$pkg/V23-V24-功能说明.md",$features,[Text.UTF8Encoding]::new($false))
$script = [IO.File]::ReadAllText("$previous/Update-Minion-V22.cmd")
$script = $script.Replace('V22','V25').Replace('v22','v25')
$script = $script.Replace('Context Memory Repair','Context Threshold Combined Update')
$script = $script.Replace('context memory repair','context threshold combined update')
$script = $script.Replace('Context memory repair','Context threshold combined update')
$script = $script.Replace('This update keeps the active task across compact and reduces old context payloads.',
    'Combined V23 + V24 + V25. Upgrade directly from V22; earlier patches are not required.')
$script = $script.Replace('Qwen3.8 sessions use a safer adaptive context window; local history stays complete.',
    'Configurable compression percentage and trigger preview; keeps user configuration and history.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V25.cmd",$script,[Text.Encoding]::ASCII)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$patch = "$pkg/payload/classes-patch.zip"
$zip = [IO.Compression.ZipFile]::Open($patch,'Create')
$count=0
try {
    $classesRoot=(Resolve-Path "$root/target/classes").Path
    foreach($file in (Get-ChildItem "$classesRoot/com/minion" -Recurse -Filter '*.class')) {
        $bytes=[IO.File]::ReadAllBytes($file.FullName)
        if($bytes[6]*256+$bytes[7] -gt 52) {throw "Not Java 8 compatible: $($file.Name)"}
        $entry=$file.FullName.Substring($classesRoot.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$entry)|Out-Null
        $count++
    }
    if($count -lt 300) {throw "Application class set incomplete: $count"}
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$classesRoot/theme/theme.css",'theme/theme.css')|Out-Null
} finally {$zip.Dispose()}
[IO.File]::WriteAllText("$patch.sha256",(Get-FileHash $patch -Algorithm SHA256).Hash+"`r`n",[Text.Encoding]::ASCII)
$out="$root/dist/$name.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($pkg,$out)
"Shipped application classes: $count"
Get-Item $out | Select-Object FullName,Length
Get-FileHash $out -Algorithm SHA256
