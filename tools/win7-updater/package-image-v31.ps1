$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name='minion-win7-image-context-all-small-20261014-v31'
$pkg=Join-Path $root "dist/$name-package"
$previous=Join-Path $root 'dist/minion-win7-unified-stop-all-small-20261013-v30-package'
$out=Join-Path $root "dist/$name.zip"
if((Test-Path $pkg)-or(Test-Path $out)){throw 'Package exists'}
New-Item -ItemType Directory -Path "$pkg/payload" | Out-Null
Copy-Item "$previous/payload/JarPatchApplier.class" "$pkg/payload/"
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$previous/*说明.md" "$pkg/"
Copy-Item "$root/docs/releases/V31-图片上下文更新.md" "$pkg/V31-说明.md"
$script=[IO.File]::ReadAllText("$previous/Update-Minion-V30.cmd").Replace('V30','V31').Replace('v30','v31').Replace('Includes V29 plus unified read-only completion review for main and child agents.','Includes V30 plus local image context for main and child agents.').Replace('V27, V28 or V29','V27, V28, V29 or V30')
[IO.File]::WriteAllText("$pkg/Update-Minion-V31.cmd",$script,[Text.Encoding]::ASCII)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$patch="$pkg/payload/classes-patch.zip"
$zip=[IO.Compression.ZipFile]::Open($patch,'Create')
try {
 $classesRoot=(Resolve-Path "$root/target/classes").Path
 foreach($file in (Get-ChildItem "$classesRoot/com/minion" -Recurse -Filter '*.class')) {
  $bytes=[IO.File]::ReadAllBytes($file.FullName)
  if($bytes[6]*256+$bytes[7]-gt52){throw 'Not Java8'}
  $entry=$file.FullName.Substring($classesRoot.Length+1).Replace('\','/')
  [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$entry)|Out-Null
 }
 [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$classesRoot/theme/theme.css",'theme/theme.css')|Out-Null
} finally {$zip.Dispose()}
[IO.File]::WriteAllText("$patch.sha256",(Get-FileHash $patch -Algorithm SHA256).Hash+"`r`n",[Text.Encoding]::ASCII)
[IO.Compression.ZipFile]::CreateFromDirectory($pkg,$out)
Get-Item $out
Get-FileHash $out -Algorithm SHA256
