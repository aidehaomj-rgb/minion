$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-v39-small-from-v38-20261026'
$package = Join-Path $root "dist/$name-package"
$archive = Join-Path $root "dist/$name.zip"
$baseV36 = Join-Path $root 'dist/minion-win7-v36-cumulative-20261022-package/payload/minion-0.1.0.jar'
$v37Package = Join-Path $root 'dist/minion-win7-v37-small-from-v36-20261023-package/payload'
$v37Patch = Join-Path $v37Package 'classes-patch.zip'
$v37BaseHash = Join-Path $v37Package 'base-jar.sha256'
$v38Package = Join-Path $root 'dist/minion-win7-v38-small-from-v37-20261026-package/payload'
$v38Patch = Join-Path $v38Package 'entries-patch.zip'
$v38BaseManifest = Join-Path $v38Package 'v37-entries.tsv'
$v38TargetManifest = Join-Path $v38Package 'v38-entries.tsv'
$v37PatchHash = "$v37Patch.sha256"
$v38PatchHash = "$v38Patch.sha256"
$target = Join-Path $root 'target/minion-0.1.0.jar'
$jdk = (Get-ChildItem -LiteralPath (Join-Path $root 'target/build-tools-memory') -Directory | Select-Object -First 1).FullName
$java = Join-Path $jdk 'bin/java.exe'
$javac = Join-Path $jdk 'bin/javac.exe'
$work = Join-Path $root ("target/v39-package-work-" + [guid]::NewGuid().ToString('N'))
if ((Test-Path -LiteralPath $package) -or (Test-Path -LiteralPath $archive)) {
    throw 'V39 package already exists; do not overwrite a delivered package.'
}
foreach ($required in @($baseV36, $v37Patch, $v37BaseHash, $v37PatchHash,
                        $v38Patch, $v38PatchHash, $v38BaseManifest, "$v38BaseManifest.sha256",
                        $v38TargetManifest, "$v38TargetManifest.sha256", $target, $java, $javac)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required input missing: $required" }
}
if ((Get-FileHash -LiteralPath $baseV36 -Algorithm SHA256).Hash -ne
        [IO.File]::ReadAllText($v37BaseHash).Trim()) { throw 'V36 base hash does not match V37 package.' }
if ((Get-FileHash -LiteralPath $v37Patch -Algorithm SHA256).Hash -ne
        [IO.File]::ReadAllText($v37PatchHash).Trim()) { throw 'V37 patch hash mismatch.' }
if ((Get-FileHash -LiteralPath $v38Patch -Algorithm SHA256).Hash -ne
        [IO.File]::ReadAllText($v38PatchHash).Trim()) { throw 'V38 patch hash mismatch.' }
foreach ($manifest in @($v38BaseManifest, $v38TargetManifest)) {
    if ((Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash -ne
            [IO.File]::ReadAllText("$manifest.sha256").Trim()) { throw "V38 manifest hash mismatch: $manifest" }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-EntryHash([IO.Compression.ZipArchiveEntry]$entry) {
    if ($null -eq $entry) { return '' }
    $stream = $entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToBase64String($sha.ComputeHash($stream)) }
    finally { $stream.Dispose(); $sha.Dispose() }
}
function Get-EntryMap([IO.Compression.ZipArchive]$zip) {
    $map = New-Object 'System.Collections.Generic.Dictionary[string,System.IO.Compression.ZipArchiveEntry]' ([StringComparer]::Ordinal)
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        if ($map.ContainsKey($entry.FullName)) { throw "Duplicate JAR entry: $($entry.FullName)" }
        $map.Add($entry.FullName, $entry)
    }
    return $map
}

New-Item -ItemType Directory -Path $work | Out-Null
& $javac -encoding UTF-8 -d $work (Join-Path $PSScriptRoot 'JarPatchApplier.java') (Join-Path $PSScriptRoot 'JarEntryVerifier.java')
if ($LASTEXITCODE -ne 0) { throw 'Java 8 update helper compilation failed.' }
$v37Jar = Join-Path $work 'v37-base.jar'
& $java -cp $work JarPatchApplier $baseV36 $v37Patch $v37Jar
if ($LASTEXITCODE -ne 0) { throw 'Cannot reconstruct V37 base.' }
& $java -cp $work JarEntryVerifier verify $v37Jar $v38BaseManifest
if ($LASTEXITCODE -ne 0) { throw 'Reconstructed V37 does not match V38 package base.' }
$v38Jar = Join-Path $work 'v38-base.jar'
& $java -cp $work JarPatchApplier $v37Jar $v38Patch $v38Jar
if ($LASTEXITCODE -ne 0) { throw 'Cannot reconstruct V38 base.' }
& $java -cp $work JarEntryVerifier verify $v38Jar $v38TargetManifest
if ($LASTEXITCODE -ne 0) { throw 'Reconstructed V38 does not match delivered V38 package.' }

$baseZip = [IO.Compression.ZipFile]::OpenRead($v38Jar)
$targetZip = [IO.Compression.ZipFile]::OpenRead($target)
$changedNames = New-Object 'System.Collections.Generic.List[string]'
try {
    $baseEntries = Get-EntryMap $baseZip
    $targetEntries = Get-EntryMap $targetZip
    foreach ($entryName in $baseEntries.Keys) {
        if (-not $targetEntries.ContainsKey($entryName)) { throw "Patch cannot remove JAR entry: $entryName" }
    }
    foreach ($entry in $targetZip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        $old = if ($baseEntries.ContainsKey($entry.FullName)) { $baseEntries[$entry.FullName] } else { $null }
        if (($null -eq $old) -or ($old.Length -ne $entry.Length) -or
                ((Get-EntryHash $old) -cne (Get-EntryHash $entry))) {
            if ($entry.FullName -eq 'META-INF/LICENSE.txt') { continue } # Shade metadata only.
            if (-not ($entry.FullName.StartsWith('com/minion/') -and $entry.FullName.EndsWith('.class')) -and
                    $entry.FullName -ne 'theme/theme.css') {
                throw "Unexpected non-Minion change: $($entry.FullName)"
            }
            if ($entry.FullName.EndsWith('.class')) {
                $stream = $entry.Open(); $bytes = New-Object byte[] 8
                try { $count = $stream.Read($bytes, 0, 8) } finally { $stream.Dispose() }
                if ($count -ne 8 -or $bytes[0] -ne 202 -or $bytes[1] -ne 254 -or
                        $bytes[2] -ne 186 -or $bytes[3] -ne 190 -or
                        ($bytes[6] * 256 + $bytes[7]) -gt 52) {
                    throw "Class is not Java 8 compatible: $($entry.FullName)"
                }
            }
            $changedNames.Add($entry.FullName)
        }
    }
    if ($changedNames.Count -lt 10 -or $changedNames.Count -gt 200) {
        throw "Unexpected V38-to-V39 patch scope: $($changedNames.Count) entries"
    }
    $patchZipPath = Join-Path $work 'entries-patch.zip'
    $patchZip = [IO.Compression.ZipFile]::Open($patchZipPath, 'Create')
    try {
        foreach ($entryName in $changedNames) {
            $source = $targetEntries[$entryName].Open()
            $destination = $patchZip.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal).Open()
            try { $source.CopyTo($destination) }
            finally { $source.Dispose(); $destination.Dispose() }
        }
    } finally { $patchZip.Dispose() }
} finally { $baseZip.Dispose(); $targetZip.Dispose() }

$patchedJar = Join-Path $work 'v39-patched-test.jar'
& $java -cp $work JarPatchApplier $v38Jar $patchZipPath $patchedJar
if ($LASTEXITCODE -ne 0) { throw 'V39 patch application test failed.' }
$actualZip = [IO.Compression.ZipFile]::OpenRead($patchedJar)
$expectedZip = [IO.Compression.ZipFile]::OpenRead($target)
try {
    $actualEntries = Get-EntryMap $actualZip
    $expectedEntries = Get-EntryMap $expectedZip
    if ($actualEntries.Count -ne $expectedEntries.Count) { throw 'Patched JAR entry count differs from current build.' }
    foreach ($entryName in $expectedEntries.Keys) {
        if ($entryName -eq 'META-INF/LICENSE.txt') { continue }
        $actual = $actualEntries[$entryName]
        $expected = $expectedEntries[$entryName]
        if (($null -eq $actual) -or ($actual.Length -ne $expected.Length) -or
                ((Get-EntryHash $actual) -cne (Get-EntryHash $expected))) {
            throw "Patched JAR differs from current build: $entryName"
        }
    }
} finally { $actualZip.Dispose(); $expectedZip.Dispose() }

$baseManifest = Join-Path $work 'v38-entries.tsv'
$targetManifest = Join-Path $work 'v39-entries.tsv'
& $java -cp $work JarEntryVerifier write $v38Jar $baseManifest
if ($LASTEXITCODE -ne 0) { throw 'V38 entry manifest generation failed.' }
& $java -cp $work JarEntryVerifier write $patchedJar $targetManifest
if ($LASTEXITCODE -ne 0) { throw 'V39 entry manifest generation failed.' }
& $java -cp $work JarEntryVerifier verify $v38Jar $baseManifest
if ($LASTEXITCODE -ne 0) { throw 'V38 entry manifest verification failed.' }
& $java -cp $work JarEntryVerifier verify $patchedJar $targetManifest
if ($LASTEXITCODE -ne 0) { throw 'V39 entry manifest verification failed.' }

$payload = Join-Path $package 'payload'
New-Item -ItemType Directory -Path $payload | Out-Null
foreach ($file in @('JarPatchApplier.class', 'JarEntryVerifier.class', 'entries-patch.zip',
                   'v38-entries.tsv', 'v39-entries.tsv')) {
    $source = if ($file -eq 'entries-patch.zip') { $patchZipPath }
              elseif ($file -eq 'v38-entries.tsv') { $baseManifest }
              elseif ($file -eq 'v39-entries.tsv') { $targetManifest }
              else { Join-Path $work $file }
    $destination = Join-Path $payload $file
    Copy-Item -LiteralPath $source -Destination $destination
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    [IO.File]::WriteAllText("$destination.sha256", "$hash`r`n", [Text.Encoding]::ASCII)
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Update-Minion-V39-Small.cmd') -Destination $package
$readme = @'
Minion V39 小更新包（仅适用于 V38 小更新后的 JAR）

本包包含 V38 后新增的任务子 Agent 与主会话模型锁定，以及自动压缩默认触发点 150K。
DeepSeek / Qwen3.8-Flash 旧配置中的 80K/120K 稳定工作区首次启动时会一次性迁移到 150K。
仅修补有差异的应用 class/资源，不包含完整 JAR 或依赖库。

安装：先退出 Minion，把整个 ZIP 解压到普通目录，双击 Update-Minion-V39-Small.cmd，
按提示输入包含 minion-0.1.0.jar 的安装目录，确认后自动备份并更新。
脚本逐项校验安装 JAR 的文件内容，不依赖整体 JAR 哈希，因此可适配不同时间执行的 V38 补丁。
若内网 JAR 不是 V38、已自行修改或另装了补丁，安装器会拒绝，不要绕过校验。
项目、聊天历史和知识库数据不会被清理；model.json 中符合旧值的稳定工作区会按上述规则迁移。
可先运行 Update-Minion-V39-Small.cmd /verify 仅校验包完整性。

注意：已通过本机 Java 8 编译、相关回归测试、补丁回放和逐项哈希校验；
尚未在你的 Win7 内网模型环境实际运行。请先备份工作目录并在内网试用。
'@
[IO.File]::WriteAllText((Join-Path $package '安装说明.txt'), $readme, [Text.Encoding]::UTF8)
[IO.Compression.ZipFile]::CreateFromDirectory($package, $archive)
$packed = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    foreach ($entry in $packed.Entries) {
        if ($entry.FullName.EndsWith('.sha256')) { continue }
        if ($entry.FullName.StartsWith('payload/') -and $null -eq $packed.GetEntry($entry.FullName + '.sha256')) {
            throw "Missing ZIP payload hash: $($entry.FullName)"
        }
    }
} finally { $packed.Dispose() }
Write-Output "Changed entries: $($changedNames.Count)"
Get-Item -LiteralPath $archive | Select-Object FullName, Length
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Hash
