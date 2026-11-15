$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-task-continuation-all-small-20261010-v26'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-context-threshold-all-small-20261010-v25-package'
if (Test-Path $pkg) { throw 'Package already exists; use a fresh version.' }
New-Item -ItemType Directory -Force "$pkg/payload" | Out-Null
Copy-Item "$previous/payload/JarPatchApplier.class" "$pkg/payload/"
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$root/docs/releases/V26-主任务续接修复.md" "$pkg/V26-说明.md"
Copy-Item "$previous/V23-V24-功能说明.md" "$pkg/"
Copy-Item "$root/docs/releases/V25-可配置自动压缩合并更新.md" "$pkg/V25-功能说明.md"
$script = [IO.File]::ReadAllText("$previous/Update-Minion-V25.cmd")
$script = $script.Replace('V25','V26').Replace('v25','v26')
$script = $script.Replace('Context Threshold Combined Update','Task Continuation Combined Update')
$script = $script.Replace('context threshold combined update','task continuation combined update')
$script = $script.Replace('Context threshold combined update','Task continuation combined update')
$script = $script.Replace('combined update update v26','combined update v26')
$script = $script.Replace('Combined V23 + V24 + V26. Upgrade directly from V22; earlier patches are not required.',
    'Combined V23 + V24 + V25 + V26. Upgrade directly from V22-V25.')
$script = $script.Replace('Configurable compression percentage and trigger preview; keeps user configuration and history.',
    'Fix premature task stops after normal model replies; preserves model settings and local history.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V26.cmd",$script,[Text.Encoding]::ASCII)
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
