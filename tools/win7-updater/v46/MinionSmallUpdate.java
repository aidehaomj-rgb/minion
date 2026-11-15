import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

/** V46 离线增量更新：识别内容基线，不修改配置/会话/项目/运行库。仅依赖 Java8。 */
public final class MinionSmallUpdate {
    private static final String[] PAYLOAD={"JarPatchApplier.class","JarEntryVerifier.class","MinionSmallUpdate.class",
        "entries-patch.zip","v40-entries.tsv","v41-entries.tsv","v42-entries.tsv","v43-entries.tsv","v44-entries.tsv","v45-entries.tsv","v46-entries.tsv","Minion.exe","Create-Minion-Shortcuts.cmd"};
    private static final char[] HEX="0123456789abcdef".toCharArray();
    public static void main(String[] args) {
        try {
            if(args.length<2||!Arrays.asList("verify","check","apply").contains(args[0]))throw new IOException("Usage: verify <payload> | check/apply <payload> <installation>");
            Path payload=Paths.get(args[1]).toRealPath();verifyPayload(payload);
            if("verify".equals(args[0])){System.out.println("Package SHA-256 checks passed; no installed files changed.");return;}
            if(args.length!=3)throw new IOException("Installation directory is required.");
            Path root=Paths.get(args[2]).toRealPath(),jar=root.resolve("minion-0.1.0.jar");
            if(!Files.isRegularFile(jar)||!jar.toRealPath().getParent().equals(root))throw new IOException("No regular Minion JAR in the selected directory.");
            Map<String,String> actual=entries(jar),target=manifest(payload.resolve("v46-entries.tsv"));
            if(actual.equals(target)&&assetsMatch(payload,root)){System.out.println("Already V46. No files changed.");return;}
            String version=actual.equals(target)?"v46":null;
            for(String v:new String[]{"v40","v41","v42","v43","v44","v45"})if(actual.equals(manifest(payload.resolve(v+"-entries.tsv")))){version=v;break;}
            if(version==null)throw new IOException("Installed contents are not a supported V40/V41/V42/V43/V44/V45 baseline. Nothing changed; do not force this update.");
            System.out.println("Detected supported baseline: "+version.toUpperCase(Locale.ROOT));
            if("check".equals(args[0]))return;
            for(String name:ASSETS) {
                Path file=root.resolve(name);
                if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().getParent().equals(root)))
                    throw new IOException("Invalid launcher target: "+name);
            }
            Path lockPath=root.resolve(".minion-update.lock");
            boolean ownedLock=false;
            try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
                FileLock lock=channel.tryLock();if(lock==null)throw new IOException("Another update is running.");
                ownedLock=true;
                try {apply(payload,root,jar,target);}finally{lock.release();}
            } finally {if(ownedLock)Files.deleteIfExists(lockPath);}
        } catch(Exception e) {System.err.println("UPDATE FAILED: "+e.getMessage());System.exit(1);}
    }
    private static void apply(Path payload,Path root,Path jar,Map<String,String> target)throws Exception {
        String originalHash=hash(jar);Path backupDir=root.resolve("backup");Files.createDirectories(backupDir);
        if(!backupDir.toRealPath().startsWith(root))throw new IOException("Backup directory resolves outside the installation.");
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path backup=backupDir.resolve("minion-0.1.0.jar.pre-v46-"+stamp+"-"+UUID.randomUUID().toString().substring(0,8)+".bak");
        Files.copy(jar,backup);
        if(!originalHash.equals(hash(backup)))throw new IOException("Backup check failed; original JAR unchanged.");
        Path assetBackup=backupDir.resolve(backup.getFileName().toString()+".desktop");
        Files.createDirectory(assetBackup);
        Set<String> existed=new HashSet<String>();
        for(String name:ASSETS)if(Files.exists(root.resolve(name))) {
            existed.add(name);Files.copy(root.resolve(name),assetBackup.resolve(name));
            if(!hash(root.resolve(name)).equals(hash(assetBackup.resolve(name))))throw new IOException("Desktop backup failed: "+name);
        }
        Files.write(assetBackup.resolve("previous-files.txt"),existed,StandardCharsets.UTF_8);
        Path staged=Files.createTempFile(root,".minion-v46-",".new");boolean replacementAttempted=false;
        try {
            JarPatchApplier.main(new String[]{jar.toString(),payload.resolve("entries-patch.zip").toString(),staged.toString()});
            if(!entries(staged).equals(target))throw new IOException("Patched content validation failed; original JAR unchanged.");
            if(!originalHash.equals(hash(jar)))throw new IOException("Installed JAR changed during update; original was not replaced.");
            replacementAttempted=true;
            move(staged,jar);
            for(String name:ASSETS) {
                Path temp=Files.createTempFile(root,".minion-desktop-",".new");
                try {Files.copy(payload.resolve(name),temp,StandardCopyOption.REPLACE_EXISTING);move(temp,root.resolve(name));}
                finally {Files.deleteIfExists(temp);}
            }
            if(!assetsMatch(payload,root))throw new IOException("Desktop content validation failed.");
            if(!entries(jar).equals(target))throw new IOException("Final content validation failed.");
            System.out.println("V46 update completed successfully.");
            System.out.println("Previous JAR backup: "+backup);
            System.out.println("Model settings, sessions, knowledge, projects, Java and Python were not modified.");
        } catch(Exception failure) {
            if(replacementAttempted) {
                Path restore=Files.createTempFile(root,".minion-restore-",".new");
                try {Files.copy(backup,restore,StandardCopyOption.REPLACE_EXISTING);move(restore,jar);
                    if(!originalHash.equals(hash(jar)))throw new IOException("Restored JAR hash differs");
                    for(String name:ASSETS) {
                        if(existed.contains(name))Files.copy(assetBackup.resolve(name),root.resolve(name),StandardCopyOption.REPLACE_EXISTING);
                        else Files.deleteIfExists(root.resolve(name));
                    }
                    System.err.println("Previous JAR and desktop files restored from backup.");
                } catch(Exception rollback){throw new IOException(failure.getMessage()+"; automatic restore failed. Backup: "+backup+"; "+rollback.getMessage(),failure);}
                finally {Files.deleteIfExists(restore);}
            }
            throw failure;
        } finally {Files.deleteIfExists(staged);}
    }
    private static final String[] ASSETS={"Minion.exe","Create-Minion-Shortcuts.cmd"};
    private static boolean assetsMatch(Path payload,Path root)throws Exception {
        for(String name:ASSETS)if(!Files.isRegularFile(root.resolve(name))||!hash(payload.resolve(name)).equals(hash(root.resolve(name))))return false;
        return true;
    }
    private static void move(Path source,Path target)throws IOException {
        try {Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void verifyPayload(Path root)throws Exception {
        for(String name:PAYLOAD) {
            Path file=root.resolve(name),sidecar=root.resolve(name+".sha256");
            if(!Files.isRegularFile(file)||!Files.isRegularFile(sidecar))throw new IOException("Incomplete payload: "+name);
            String expected=new String(Files.readAllBytes(sidecar),StandardCharsets.US_ASCII).trim();
            if(!expected.matches("[0-9a-fA-F]{64}")||!expected.equalsIgnoreCase(hash(file)))throw new IOException("Package hash mismatch: "+name);
        }
    }
    private static Map<String,String> manifest(Path p)throws IOException {
        Map<String,String> result=new TreeMap<String,String>();
        for(String line:Files.readAllLines(p,StandardCharsets.UTF_8)) {
            if(line.length()<66||line.charAt(64)!='\t'||!line.substring(0,64).matches("[0-9a-f]{64}"))throw new IOException("Invalid manifest: "+p.getFileName());
            if(result.put(line.substring(65),line.substring(0,64))!=null)throw new IOException("Duplicate manifest entry");
        }
        if(result.isEmpty())throw new IOException("Empty manifest");return result;
    }
    private static Map<String,String> entries(Path p)throws Exception {
        Map<String,String> result=new TreeMap<String,String>();
        try(ZipFile zip=new ZipFile(p.toFile())) {
            Enumeration<? extends ZipEntry> all=zip.entries();
            while(all.hasMoreElements()) {
                ZipEntry entry=all.nextElement();if(entry.isDirectory())continue;
                try(InputStream in=zip.getInputStream(entry)){if(result.put(entry.getName(),hash(in))!=null)throw new IOException("Duplicate JAR entry");}
            }
        }
        return result;
    }
    private static String hash(Path p)throws Exception {try(InputStream in=Files.newInputStream(p)){return hash(in);}}
    private static String hash(InputStream in)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;
        while((n=in.read(buffer))!=-1)if(n>0)md.update(buffer,0,n);
        byte[] digest=md.digest();char[] hex=new char[digest.length*2];
        for(int i=0;i<digest.length;i++){hex[i*2]=HEX[(digest[i]&255)>>>4];hex[i*2+1]=HEX[digest[i]&15];}return new String(hex);
    }
}
