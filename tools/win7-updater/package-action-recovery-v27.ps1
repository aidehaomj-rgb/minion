$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-action-recovery-all-small-20261011-v27'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-task-continuation-all-small-20261010-v26-package'
if (Test-Path $pkg) { throw 'Package already exists; use a fresh version.' }
New-Item -ItemType Directory -Force "$pkg/payload" | Out-Null
Copy-Item "$previous/payload/JarPatchApplier.class" "$pkg/payload/"
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$previous/V23-V24-功能说明.md" "$pkg/"
Copy-Item "$previous/V25-功能说明.md" "$pkg/"
Copy-Item "$previous/V26-说明.md" "$pkg/"
Copy-Item "$root/docs/releases/V27-中断误触与工具动作恢复.md" "$pkg/V27-说明.md"
$script = [IO.File]::ReadAllText("$previous/Update-Minion-V26.cmd")
$script = $script.Replace('V26','V27').Replace('v26','v27')
$script = $script.Replace('Task Continuation Combined Update','Action Recovery Combined Update')
$script = $script.Replace('task continuation combined update','action recovery combined update')
$script = $script.Replace('Task continuation combined update','Action recovery combined update')
$script = $script.Replace('Combined V23 + V24 + V25 + V27. Upgrade directly from V22-V25.',
    'Combined V23 + V24 + V25 + V26 + V27. Upgrade directly from V22-V26.')
$script = $script.Replace('Fix premature task stops after normal model replies; preserves model settings and local history.',
    'Fix repeat Enter stopping tasks and recover repeated tool plans; preserves settings and history.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V27.cmd",$script,[Text.Encoding]::ASCII)
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
Get-Item $out | Format-List FullName,Length
Get-FileHash $out -Algorithm SHA256 | Format-List Hash
