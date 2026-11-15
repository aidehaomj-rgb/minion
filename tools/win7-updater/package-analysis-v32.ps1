$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name='minion-win7-analysis-document-all-small-20261014-v32'
$pkg=Join-Path $root "dist/$name-package"
$previous=Join-Path $root 'dist/minion-win7-image-context-all-small-20261014-v31-package'
$out=Join-Path $root "dist/$name.zip"
if((Test-Path $pkg)-or(Test-Path $out)){throw 'Package exists'}
New-Item -ItemType Directory -Path "$pkg/payload" | Out-Null
Copy-Item "$previous/payload/JarPatchApplier.class" "$pkg/payload/"
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$previous/*说明.md" "$pkg/"
Copy-Item "$root/docs/releases/V32-数据材料与视觉文档更新.md" "$pkg/V32-说明.md"
Copy-Item "$root/docs/数据分析与材料工作流.md" "$pkg/工作流说明.md"
$script=[IO.File]::ReadAllText("$previous/Update-Minion-V31.cmd").Replace('V31','V32').Replace('v31','v32').Replace('Includes V30 plus local image context for main and child agents.','Includes V31 plus PDF, OCR, analysis and document workflows.').Replace('V27, V28, V29 or V30','V27, V28, V29, V30 or V31')
[IO.File]::WriteAllText("$pkg/Update-Minion-V32.cmd",$script,[Text.Encoding]::ASCII)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$patch="$pkg/payload/classes-patch.zip"
$zip=[IO.Compression.ZipFile]::Open($patch,'Create')
try {
 $classesRoot=(Resolve-Path "$root/target/classes").Path
 $names=New-Object 'System.Collections.Generic.HashSet[string]'
 foreach($file in (Get-ChildItem "$classesRoot/com/minion" -Recurse -Filter '*.class')) {
  $bytes=[IO.File]::ReadAllBytes($file.FullName)
  if($bytes[6]*256+$bytes[7]-gt52){throw 'Not Java8'}
  $entry=$file.FullName.Substring($classesRoot.Length+1).Replace('\','/')
  [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$entry)|Out-Null
  $names.Add($entry)|Out-Null
 }
 foreach($required in @('com/minion/core/tools/data/PdfViews.class','com/minion/core/tools/data/LocalOcr.class','com/minion/core/tools/data/ResourcePython.class','com/minion/core/tools/ViewImageTool.class','com/minion/core/agent/StopGate.class')) {
  if(-not $names.Contains($required)){throw "Missing $required"}
 }
 foreach($resource in @('theme/theme.css','config.properties','workflows/excel_workflow.py','workflows/office_workflow.py')) {
  if(-not(Test-Path "$classesRoot/$resource")){throw "Missing resource: $resource"}
  [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$classesRoot/$resource",$resource)|Out-Null
 }
} finally {$zip.Dispose()}
[IO.File]::WriteAllText("$patch.sha256",(Get-FileHash $patch -Algorithm SHA256).Hash+"`r`n",[Text.Encoding]::ASCII)
[IO.Compression.ZipFile]::CreateFromDirectory($pkg,$out)
Get-Item $out
Get-FileHash $out -Algorithm SHA256
