package com.minion.core.agent;

import java.nio.file.*;
import java.security.MessageDigest;
import java.io.InputStream;

/** Actual filesystem evidence; presence/hash is not a claim about semantic correctness. */
public final class FileEvidence {
    public String path,sha256,status;
    public long size,modified;
    public static FileEvidence capture(Path path) throws Exception {
        FileEvidence e=new FileEvidence(); e.path=path.toAbsolutePath().normalize().toString();
        if(!Files.isRegularFile(path)) { e.status="missing"; return e; }
        e.path=path.toRealPath().toString(); e.size=Files.size(path);
        e.modified=Files.getLastModifiedTime(path).toMillis(); e.sha256=digest(path); e.status="present";
        return e;
    }
    public static String digest(Path path) throws Exception {
        if(!Files.isRegularFile(path)) return "<missing>";
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)) { byte[] b=new byte[32768]; int n; while((n=in.read(b))!=-1) md.update(b,0,n); }
        StringBuilder s=new StringBuilder(); for(byte b:md.digest()) s.append(String.format("%02x",b&255)); return s.toString();
    }
    public String verify() {
        try {
            Path p=Paths.get(path);
            if(!Files.isRegularFile(p)) return "missing";
            return sha256!=null&&sha256.equals(digest(p))?"unchanged":"changed";
        } catch(Exception e) { return "unreadable"; }
    }
}
