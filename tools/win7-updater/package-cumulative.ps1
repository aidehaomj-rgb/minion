param(
    [string]$BaseRef = "c67008c",
    [string]$WorkRoot = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ProjectBase = Split-Path -Parent $ProjectRoot
$Tools = Join-Path $ProjectRoot "tools\win7-updater"
$JdkHome = Join-Path $ProjectBase ".build-tools\jdk8-full-v43"
$Maven = Join-Path $ProjectBase ".build-tools\apache-maven-3.9.9\bin\mvn.cmd"
$Java = Join-Path $JdkHome "bin\java.exe"
$Javac = Join-Path $JdkHome "bin\javac.exe"
$Version = ([IO.File]::ReadAllText((Join-Path $ProjectRoot "docs\update-package-version.txt"))).Trim()
if ($Version -notmatch "^\d+\.\d+\.\d+$") { throw "Invalid update version: $Version" }
$Compact = $Version.Replace(".", "")
$Display = "V$Version"
$Stamp = Get-Date -Format "yyyyMMdd"
if ([string]::IsNullOrWhiteSpace($WorkRoot)) {
    $WorkRoot = Join-Path $ProjectRoot ("target\v" + $Compact + "-package-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
}
$PackageDir = Join-Path $WorkRoot "package"
$PayloadDir = Join-Path $PackageDir "payload"
$SourceDir = Join-Path $WorkRoot "sources"
$VerifyDir = Join-Path $WorkRoot "verify"
$CurrentJar = Join-Path $ProjectRoot "target\minion-0.1.0.jar"
$PackageName = "minion-win7-v$Version-small-cumulative-$Stamp.zip"
$PackageZip = Join-Path $WorkRoot $PackageName

function Sha256([string]$Path) {
    $stream=[IO.File]::OpenRead($Path)
    try { $sha=[Security.Cryptography.SHA256]::Create(); try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace("-","").ToLowerInvariant() } finally { $sha.Dispose() } }
    finally { $stream.Dispose() }
}
function Sidecar([string]$Path) { [IO.File]::WriteAllText("$Path.sha256",(Sha256 $Path)+[Environment]::NewLine,[Text.Encoding]::ASCII) }

foreach($required in @($Java,$Javac,$Maven,$CurrentJar)){if(-not(Test-Path -LiteralPath $required)){throw "Missing required file: $required"}}
New-Item -ItemType Directory -Force -Path $PackageDir,$PayloadDir,$SourceDir,$VerifyDir | Out-Null

# Rebuild the original V2.4 baseline on demand.
$BaseSource = Join-Path $ProjectRoot ("target\baseline-" + $BaseRef + "-" + [guid]::NewGuid().ToString("N").Substring(0,8))
$Archive = "$BaseSource.zip"
git archive --format=zip -o $Archive $BaseRef
if($LASTEXITCODE -ne 0){throw "git archive failed for $BaseRef"}
Expand-Archive -LiteralPath $Archive -DestinationPath $BaseSource -Force
$oldJavaHome=$env:JAVA_HOME;$oldMavenOpts=$env:MAVEN_OPTS
try {
    $env:JAVA_HOME=$JdkHome
    $env:MAVEN_OPTS='-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890'
    Push-Location $BaseSource
    try { & $Maven -q '-DskipTests' package; if($LASTEXITCODE -ne 0){throw "baseline build failed"} }
    finally { Pop-Location }
} finally { $env:JAVA_HOME=$oldJavaHome; $env:MAVEN_OPTS=$oldMavenOpts }
$BaseJar=Join-Path $BaseSource "target\minion-0.1.0.jar"
if(-not(Test-Path -LiteralPath $BaseJar)){throw "baseline JAR missing"}

Copy-Item -LiteralPath (Join-Path $Tools "JarEntryVerifier.java") -Destination $SourceDir
$patchApplier=@'
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.*;
public final class JarPatchApplier {
 public static void main(String[] args)throws Exception{
  if(args.length!=4)throw new IllegalArgumentException("source patch output targetManifest");
  File source=new File(args[0]),patch=new File(args[1]),output=new File(args[2]);Set<String> allowed=new HashSet<String>();
  for(String line:java.nio.file.Files.readAllLines(java.nio.file.Paths.get(args[3]),StandardCharsets.UTF_8))allowed.add(line.substring(65));
  Map<String,byte[]> repl=read(patch);ZipFile in=new ZipFile(source);ZipOutputStream out=new ZipOutputStream(new FileOutputStream(output));byte[] b=new byte[32768];
  try{Enumeration<? extends ZipEntry> es=in.entries();while(es.hasMoreElements()){ZipEntry e=es.nextElement();if((!e.isDirectory()&&!allowed.contains(e.getName()))||repl.containsKey(e.getName()))continue;ZipEntry c=new ZipEntry(e.getName());c.setTime(e.getTime());out.putNextEntry(c);if(!e.isDirectory())copy(in.getInputStream(e),out,b);out.closeEntry();}for(Map.Entry<String,byte[]> e:repl.entrySet()){out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}}
  finally{try{out.close();}finally{in.close();}}
  ZipFile z=new ZipFile(output);try{ZipEntry e=z.getEntry("META-INF/MANIFEST.MF");if(e==null)throw new IllegalStateException("no manifest");InputStream s=z.getInputStream(e);try{Manifest m=new Manifest(s);if(!"com.minion.Boot".equals(m.getMainAttributes().getValue("Main-Class")))throw new IllegalStateException("bad Main-Class");}finally{s.close();}}finally{z.close();}
 }
 private static Map<String,byte[]> read(File f)throws Exception{Map<String,byte[]> o=new LinkedHashMap<String,byte[]>();ZipInputStream in=new ZipInputStream(new FileInputStream(f));byte[] b=new byte[32768];try{ZipEntry e;while((e=in.getNextEntry())!=null){if(e.isDirectory())continue;ByteArrayOutputStream x=new ByteArrayOutputStream();copy(in,x,b);o.put(e.getName(),x.toByteArray());}}finally{in.close();}return o;}
 private static void copy(InputStream i,OutputStream o,byte[] b)throws Exception{int n;while((n=i.read(b))>=0)if(n>0)o.write(b,0,n);}
}
'@
[IO.File]::WriteAllText((Join-Path $SourceDir "JarPatchApplier.java"),$patchApplier,[Text.UTF8Encoding]::new($false))

$updater=@"
import java.io.*;import java.nio.channels.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.security.MessageDigest;import java.text.SimpleDateFormat;import java.util.*;import java.util.zip.*;
public final class MinionSmallUpdate{
 private static final String[] PAYLOAD={"JarPatchApplier.class","JarEntryVerifier.class","MinionSmallUpdate.class","entries-patch.zip","v240-entries.tsv","changed-entries.tsv","v$Compact-entries.tsv"};private static final char[] HEX="0123456789abcdef".toCharArray();
 public static void main(String[] a){try{if(a.length<2||!Arrays.asList("verify","check","apply").contains(a[0]))throw new IOException("verify <payload> | check/apply <payload> <installation>");Path p=Paths.get(a[1]).toRealPath();verify(p);if("verify".equals(a[0])){System.out.println("Package SHA-256 checks passed; no installed files changed.");return;}if(a.length!=3)throw new IOException("installation required");Path r=Paths.get(a[2]).toRealPath(),j=r.resolve("minion-0.1.0.jar");if(!Files.isRegularFile(j))throw new IOException("No minion-0.1.0.jar");Map<String,String> actual=entries(j),target=manifest(p.resolve("v$Compact-entries.tsv")),changed=manifest(p.resolve("changed-entries.tsv"));if(actual.equals(target)){System.out.println("Already $Display. No files changed.");return;}System.out.println("Applying cumulative V2.4.x -> $Display patch; updated entries will be verified.");if("check".equals(a[0]))return;Path lock=r.resolve(".minion-update.lock");FileChannel ch=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);try{FileLock l=ch.tryLock();if(l==null)throw new IOException("Another update is running.");try{apply(p,r,j,target,changed);}finally{l.release();}}finally{ch.close();Files.deleteIfExists(lock);}}catch(Exception e){System.err.println("UPDATE FAILED: "+e.getMessage());System.exit(1);}}
 private static void apply(Path p,Path r,Path j,Map<String,String> target,Map<String,String> changed)throws Exception{String before=hash(j);Path bd=r.resolve("backup");Files.createDirectories(bd);String st=new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());Path backup=bd.resolve("minion-0.1.0.jar.pre-v$Version-"+st+"-"+UUID.randomUUID().toString().substring(0,8)+".bak");Files.copy(j,backup);if(!before.equals(hash(backup)))throw new IOException("Backup validation failed.");Path staged=Files.createTempFile(r,".minion-v$Compact-",".new");boolean replaced=false;try{JarPatchApplier.main(new String[]{j.toString(),p.resolve("entries-patch.zip").toString(),staged.toString(),p.resolve("v$Compact-entries.tsv").toString()});verifySubset(staged,changed);if(!before.equals(hash(j)))throw new IOException("Installed JAR changed during update.");move(staged,j);replaced=true;verifySubset(j,changed);System.out.println("$Display update completed successfully.");System.out.println("Previous JAR backup: "+backup);}catch(Exception e){boolean intact=false;try{intact=before.equals(hash(j));}catch(Exception x){}if(!replaced&&intact)throw new IOException("Could not replace minion-0.1.0.jar. Close every Minion process and retry; original JAR unchanged. Cause: "+e.getMessage(),e);if(replaced||!intact){Path restore=Files.createTempFile(r,".minion-restore-",".new");try{Files.copy(backup,restore,StandardCopyOption.REPLACE_EXISTING);move(restore,j);if(!before.equals(hash(j)))throw new IOException("restored hash differs");System.err.println("Previous JAR restored from backup.");}catch(Exception x){throw new IOException(e.getMessage()+"; automatic restore failed. Backup: "+backup,x);}finally{Files.deleteIfExists(restore);}}throw e;}finally{Files.deleteIfExists(staged);}}
 private static void move(Path s,Path t)throws IOException{try{Files.move(s,t,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(s,t,StandardCopyOption.REPLACE_EXISTING);}}
 private static void verify(Path r)throws Exception{for(String n:PAYLOAD){Path f=r.resolve(n),s=r.resolve(n+".sha256");if(!Files.isRegularFile(f)||!Files.isRegularFile(s))throw new IOException("incomplete payload: "+n);String e=new String(Files.readAllBytes(s),StandardCharsets.US_ASCII).trim();if(!e.matches("[0-9a-fA-F]{64}")||!e.equalsIgnoreCase(hash(f)))throw new IOException("hash mismatch: "+n);}}
 private static void verifySubset(Path j,Map<String,String> c)throws Exception{Map<String,String> a=entries(j);for(Map.Entry<String,String> e:c.entrySet())if(!e.getValue().equals(a.get(e.getKey())))throw new IOException("Updated content validation failed: "+e.getKey());}
 private static Map<String,String> manifest(Path p)throws IOException{Map<String,String> o=new TreeMap<String,String>();for(String s:Files.readAllLines(p,StandardCharsets.UTF_8)){if(s.length()<66||s.charAt(64)!='\t')throw new IOException("invalid manifest");o.put(s.substring(65),s.substring(0,64));}return o;}
 private static Map<String,String> entries(Path p)throws Exception{Map<String,String> o=new TreeMap<String,String>();try(ZipFile z=new ZipFile(p.toFile())){Enumeration<? extends ZipEntry> es=z.entries();while(es.hasMoreElements()){ZipEntry e=es.nextElement();if(e.isDirectory())continue;InputStream in=z.getInputStream(e);try{if(o.put(e.getName(),hash(in))!=null)throw new IOException("duplicate entry");}finally{in.close();}}}return o;}
 private static String hash(Path p)throws Exception{InputStream in=Files.newInputStream(p);try{return hash(in);}finally{in.close();}}
 private static String hash(InputStream in)throws Exception{MessageDigest m=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)if(n>0)m.update(b,0,n);byte[] d=m.digest();char[] h=new char[d.length*2];for(int i=0;i<d.length;i++){h[i*2]=HEX[(d[i]&255)>>>4];h[i*2+1]=HEX[d[i]&15];}return new String(h);}
}
"@
[IO.File]::WriteAllText((Join-Path $SourceDir "MinionSmallUpdate.java"),$updater,[Text.UTF8Encoding]::new($false))

& $Javac -encoding UTF-8 -source 8 -target 8 -d $PayloadDir (Join-Path $SourceDir "JarEntryVerifier.java") (Join-Path $SourceDir "JarPatchApplier.java") (Join-Path $SourceDir "MinionSmallUpdate.java")
if($LASTEXITCODE -ne 0){throw "payload compile failed"}
& $Java -cp $PayloadDir JarEntryVerifier write $BaseJar (Join-Path $PayloadDir "v240-entries.tsv")
if($LASTEXITCODE -ne 0){throw "base manifest failed"}
& $Java -cp $PayloadDir JarEntryVerifier write $CurrentJar (Join-Path $PayloadDir "v$Compact-entries.tsv")
if($LASTEXITCODE -ne 0){throw "target manifest failed"}

function Manifest([string]$Path){$o=New-Object 'System.Collections.Generic.Dictionary[string,string]' ([StringComparer]::Ordinal);foreach($l in [IO.File]::ReadAllLines($Path,[Text.Encoding]::UTF8)){$o[$l.Substring(65)]=$l.Substring(0,64)};return $o}
$base=Manifest (Join-Path $PayloadDir "v240-entries.tsv");$target=Manifest (Join-Path $PayloadDir "v$Compact-entries.tsv");$names=New-Object 'System.Collections.Generic.List[string]'
foreach($n in $target.Keys){if(-not $base.ContainsKey($n)-or $base[$n]-ne $target[$n]){$names.Add($n)}};$names=@($names|Sort-Object)
$changed=Join-Path $PayloadDir "changed-entries.tsv";$w=New-Object IO.StreamWriter($changed,$false,[Text.UTF8Encoding]::new($false));try{foreach($n in $names){$w.Write($target[$n]);$w.Write([char]9);$w.Write($n);$w.WriteLine()}}finally{$w.Dispose()}
$patch=Join-Path $PayloadDir "entries-patch.zip";$src=[IO.Compression.ZipFile]::OpenRead($CurrentJar);$fs=[IO.File]::Open($patch,[IO.FileMode]::Create);$arc=New-Object IO.Compression.ZipArchive($fs,[IO.Compression.ZipArchiveMode]::Create,$false)
try{foreach($n in $names){$e=$src.GetEntry($n);if($null-eq$e){throw "missing $n"};$z=$arc.CreateEntry($n,[IO.Compression.CompressionLevel]::Optimal);$i=$e.Open();$o=$z.Open();try{$i.CopyTo($o)}finally{$o.Dispose();$i.Dispose()}}}finally{$arc.Dispose();$fs.Dispose();$src.Dispose()}
foreach($f in Get-ChildItem -LiteralPath $PayloadDir -File|Where-Object{$_.Name-notlike'*.sha256'}){Sidecar $f.FullName}

$cmd=@'
@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
title Minion __DISPLAY__ small update
set "PAYLOAD=%~dp0payload"
echo Minion __DISPLAY__ cumulative small update from V2.4.x
set "TARGET_DIR=%~1"
:select
if defined TARGET_DIR if exist "%TARGET_DIR%\minion-0.1.0.jar" goto :target
echo Enter Minion installation directory or leave empty to cancel.
set /p "TARGET_DIR=Installation directory: "
if not defined TARGET_DIR goto :cancelled
set "TARGET_DIR=%TARGET_DIR:"=%"
goto :select
:target
for %%I in ("%TARGET_DIR%") do set "TARGET_DIR=%%~fI"
set "JAVA_EXE=%TARGET_DIR%\jdk8\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=%TARGET_DIR%\jre\bin\java.exe"
if not exist "%JAVA_EXE%" if defined MINION_JAVA if exist "%MINION_JAVA%" set "JAVA_EXE=%MINION_JAVA%"
if not exist "%JAVA_EXE%" if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java.exe"
set "RUNNING="
for /f "usebackq delims=" %%P in (`powershell.exe -NoProfile -Command "Get-WmiObject Win32_Process | Where-Object { (@('java.exe','javaw.exe') -contains $_.Name) -and ($_.CommandLine -like '*minion-0.1.0.jar*') } | ForEach-Object { $_.ProcessId }" 2^>nul`) do set "RUNNING=%%P"
if defined RUNNING goto :running
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%PAYLOAD%" MinionSmallUpdate check "%PAYLOAD%" "%TARGET_DIR%"
if errorlevel 1 goto :failed
choice /C YN /N /M "Start update? [Y/N]: "
if errorlevel 2 goto :cancelled
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%PAYLOAD%" MinionSmallUpdate apply "%PAYLOAD%" "%TARGET_DIR%"
if errorlevel 1 goto :failed
echo __DISPLAY__ update completed successfully.
pause
exit /b 0
:running
echo ERROR: Minion is still running. Close every Minion window and retry.
pause
exit /b 1
:failed
echo ERROR: Update failed. Read the message above.
pause
exit /b 1
:cancelled
echo Cancelled. No files changed.
pause
exit /b 2
'@
$cmd=$cmd.Replace('__DISPLAY__',$Display)
[IO.File]::WriteAllText((Join-Path $PackageDir "Update-Minion-$Display-Small.cmd"),$cmd,[Text.Encoding]::ASCII)

$readme=@"
Minion $Display 累积小更新（$Stamp）

适用：任意 V2.4.x 安装。
本包仅包含变更 JAR 条目，不包含完整 JAR。

本次更新：
- 任务条卡片化：进度条、彩色图标、中文状态徽标、hover 行。
- 任务面板子 Agent 表格卡片化、运行中置顶、台账等宽显示。
- task.assignments 批量并行派发与并行流式验收。
- agent.childConcurrency 默认 4，可配置 1..16。
- 派发引导改为批量并行优先。
- 设置→关于显示应用版本：$Version。
- 累积包含此前 V2.4.4 到 V2.4.9 的全部更新。

安装：
1. 完全退出 Minion。
2. 双击 Update-Minion-$Display-Small.cmd。
3. 选择含 minion-0.1.0.jar 的安装目录并确认。

更新只替换程序 JAR，不覆盖配置、会话、项目、知识库、Java 或 Python。
"@
[IO.File]::WriteAllText((Join-Path $PackageDir "安装说明.txt"),$readme,[Text.UTF8Encoding]::new($true))

# Simulate baseline install and target-manifest validation.
$test=Join-Path $VerifyDir "install";New-Item -ItemType Directory -Force -Path $test|Out-Null;Copy-Item -LiteralPath $BaseJar -Destination (Join-Path $test "minion-0.1.0.jar")
& $Java "-Dfile.encoding=UTF-8" "-cp" $PayloadDir MinionSmallUpdate apply $PayloadDir $test
if($LASTEXITCODE -ne 0){throw "simulated apply failed"}
& $Java -cp $PayloadDir JarEntryVerifier verify (Join-Path $test "minion-0.1.0.jar") (Join-Path $PayloadDir "v$Compact-entries.tsv")
if($LASTEXITCODE -ne 0){throw "target verification failed"}

[IO.Compression.ZipFile]::CreateFromDirectory($PackageDir,$PackageZip,[IO.Compression.CompressionLevel]::Optimal,$false)
[IO.File]::WriteAllText("$PackageZip.sha256",(Sha256 $PackageZip)+[Environment]::NewLine,[Text.Encoding]::ASCII)
$outDir=Join-Path $ProjectRoot "target\updates\v$Compact";New-Item -ItemType Directory -Force -Path $outDir|Out-Null;Copy-Item -LiteralPath $PackageZip -Destination $outDir -Force;Copy-Item -LiteralPath "$PackageZip.sha256" -Destination $outDir -Force
[pscustomobject]@{Package=(Join-Path $outDir (Split-Path $PackageZip -Leaf));Patches=$names.Count;SizeMiB=[math]::Round((Get-Item $PackageZip).Length/1MB,2);Sha256=(Get-Content -Raw "$PackageZip.sha256").Trim()}|Format-List
