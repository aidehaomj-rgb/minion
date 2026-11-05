$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-v37-small-from-v36-20261023'
$package = Join-Path $root "dist/$name-package"
$archive = Join-Path $root "dist/$name.zip"
$base = Join-Path $root 'dist/minion-win7-v36-cumulative-20261022-package/payload/minion-0.1.0.jar'
$target = Join-Path $root 'target/minion-0.1.0.jar'
$jdk = Join-Path $root 'target/build-tools-v21/jdk/jdk8u462-full'
if ((Test-Path -LiteralPath $package) -or (Test-Path -LiteralPath $archive)) {
    throw 'Small update package already exists. Choose a new package name instead of overwriting it.'
}
foreach ($required in @($base, $target, "$jdk/bin/javac.exe", "$jdk/bin/java.exe")) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required input missing: $required" }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-EntryHash([System.IO.Compression.ZipArchiveEntry]$entry) {
    $stream = $entry.Open()
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToBase64String($algorithm.ComputeHash($stream)) }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}

$baseZip = [System.IO.Compression.ZipFile]::OpenRead($base)
$targetZip = [System.IO.Compression.ZipFile]::OpenRead($target)
$changed = New-Object 'System.Collections.Generic.List[System.IO.Compression.ZipArchiveEntry]'
try {
    $baseEntries = New-Object 'System.Collections.Generic.Dictionary[string,System.IO.Compression.ZipArchiveEntry]' ([System.StringComparer]::Ordinal)
    $targetEntries = New-Object 'System.Collections.Generic.Dictionary[string,System.IO.Compression.ZipArchiveEntry]' ([System.StringComparer]::Ordinal)
    foreach ($entry in $baseZip.Entries) { $baseEntries[$entry.FullName] = $entry }
    foreach ($entry in $targetZip.Entries) { $targetEntries[$entry.FullName] = $entry }
    foreach ($nameInBase in $baseEntries.Keys) {
        if (-not $targetEntries.ContainsKey($nameInBase)) {
            throw "Small patch cannot remove JAR entry: $nameInBase"
        }
    }
    foreach ($entry in $targetZip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        $old = $baseEntries[$entry.FullName]
        if (($null -eq $old) -or ($old.Length -ne $entry.Length) -or
                ((Get-EntryHash $old) -cne (Get-EntryHash $entry))) {
            if ($entry.FullName -eq 'META-INF/LICENSE.txt') { continue } # Rebuild metadata, not a functional patch.
            if (-not $entry.FullName.EndsWith('.class')) {
                throw "Unexpected non-class change: $($entry.FullName)"
            }
            $changed.Add($entry)
        }
    }
    if ($changed.Count -ne 1 -or $changed[0].FullName -ne 'com/minion/core/agent/ToolExecutionGuard.class') { throw "Expected only ToolExecutionGuard.class, found $($changed.Count) changes." }

    $payload = Join-Path $package 'payload'
    New-Item -ItemType Directory -Path $payload | Out-Null
    $patch = Join-Path $payload 'classes-patch.zip'
    $patchZip = [System.IO.Compression.ZipFile]::Open($patch, 'Create')
    try {
        foreach ($entry in $changed) {
            $classStream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                try { $classStream.CopyTo($memory); $bytes = $memory.ToArray() }
                finally { $memory.Dispose() }
                if ($bytes.Length -lt 8 -or $bytes[0] -ne 202 -or
                        $bytes[1] -ne 254 -or $bytes[2] -ne 186 -or $bytes[3] -ne 190 -or
                        ($bytes[6] * 256 + $bytes[7]) -gt 52) {
                    throw "Class is not Java 8 compatible: $($entry.FullName)"
                }
                $newEntry = $patchZip.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
                $newStream = $newEntry.Open()
                try { $newStream.Write($bytes, 0, $bytes.Length) }
                finally { $newStream.Dispose() }
            } finally { $classStream.Dispose() }
        }
    } finally { $patchZip.Dispose() }
} finally { $baseZip.Dispose(); $targetZip.Dispose() }

$javac = Join-Path $jdk 'bin/javac.exe'
$java = Join-Path $jdk 'bin/java.exe'
& $javac -encoding UTF-8 -d $payload (Join-Path $PSScriptRoot 'JarPatchApplier.java')
if ($LASTEXITCODE -ne 0) { throw 'Java 8 patcher compilation failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Update-Minion-V37-Small.cmd') -Destination (Join-Path $package 'Update-Minion-V37-Small.cmd')
$baseHash = (Get-FileHash -LiteralPath $base -Algorithm SHA256).Hash
$patchHash = (Get-FileHash -LiteralPath $patch -Algorithm SHA256).Hash
$patcher = Join-Path $payload 'JarPatchApplier.class'
$patcherHash = (Get-FileHash -LiteralPath $patcher -Algorithm SHA256).Hash
$ascii = [System.Text.Encoding]::ASCII
[System.IO.File]::WriteAllText((Join-Path $payload 'base-jar.sha256'), "$baseHash`r`n", $ascii)
[System.IO.File]::WriteAllText("$patch.sha256", "$patchHash`r`n", $ascii)
[System.IO.File]::WriteAllText("$patcher.sha256", "$patcherHash`r`n", $ascii)

$testJar = Join-Path $env:TEMP "minion-v37-small-test-$([guid]::NewGuid().ToString('N')).jar"
try {
    & $java -cp $payload JarPatchApplier $base $patch $testJar
    if ($LASTEXITCODE -ne 0) { throw 'Patch application test failed.' }
    $actualZip = [System.IO.Compression.ZipFile]::OpenRead($testJar)
    $expectedZip = [System.IO.Compression.ZipFile]::OpenRead($target)
    try {
        $actualEntries = New-Object 'System.Collections.Generic.Dictionary[string,System.IO.Compression.ZipArchiveEntry]' ([System.StringComparer]::Ordinal)
        foreach ($entry in $actualZip.Entries) { $actualEntries[$entry.FullName] = $entry }
        if ($actualEntries.Count -ne $expectedZip.Entries.Count) { throw 'Patched JAR entry count differs from V37.' }
        foreach ($entry in $expectedZip.Entries) {
            if ($entry.FullName -eq 'META-INF/LICENSE.txt') { continue }
            $actual = $actualEntries[$entry.FullName]
            if (($null -eq $actual) -or ($actual.Length -ne $entry.Length) -or
                    ((Get-EntryHash $actual) -cne (Get-EntryHash $entry))) {
                throw "Patched JAR content differs from V37: $($entry.FullName)"
            }
        }
    } finally { $actualZip.Dispose(); $expectedZip.Dispose() }
} finally {
    if (Test-Path -LiteralPath $testJar) { Remove-Item -LiteralPath $testJar }
}

$readme = @'
Minion V37 小更新包（仅适用于 V36 完整包原始 JAR）

本包只替换浏览器会话阻塞修复对应的 1 个 Java class，不包含完整 JAR。
先退出 Minion，解压整个 ZIP，双击 Update-Minion-V37-Small.cmd，按提示输入安装目录。
脚本会校验补丁和安装目录中的 V36 JAR SHA-256；不匹配时不会安装。
安装前备份原 JAR 到 backup 文件夹；项目、配置、历史不清理。
如已自行修改 JAR 或安装了其他小补丁，本包会拒绝，请不要强行套用。
可运行 Update-Minion-V37-Small.cmd /verify 仅校验压缩包内容。
'@
[System.IO.File]::WriteAllText((Join-Path $package '安装说明.txt'), $readme, [System.Text.Encoding]::UTF8)
[System.IO.Compression.ZipFile]::CreateFromDirectory($package, $archive)
Write-Output "Changed classes: $($changed.Count)"
Get-Item -LiteralPath $archive | Select-Object FullName, Length
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Hash
