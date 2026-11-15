package com.minion.core.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Read-only evidence adapter. No PID probing, provider calls, SQL or submission API.
 * Authorized roots are supplied by the host AFTER its permission checks, never by tool args.
 * File hashes prove bytes at observation time, not producer identity or successful execution.
 * Roots must not be concurrently writable by hostile users: portable Java 8 cannot provide
 * race-free directory-handle traversal on all platforms. No long-term stability is claimed.
 */
public final class RuntimeJobRecovery {
    private static final long MAX_BYTES=64L*1024*1024;
    private final RuntimeJobRegistry jobs;
    private final Path relativeBase;
    private final List<Path> roots=new ArrayList<Path>();
    public RuntimeJobRecovery(RuntimeJobRegistry jobs,Collection<Path> authorizedRoots) throws IOException {
        this(jobs,authorizedRoots,null);
    }
    /** relativeBase must be the original submission workspace, explicitly chosen by host. */
    public RuntimeJobRecovery(RuntimeJobRegistry jobs,Collection<Path> authorizedRoots,Path relativeBase) throws IOException {
        this.jobs=Objects.requireNonNull(jobs,"jobs");
        this.relativeBase=relativeBase==null?null:relativeBase.toRealPath();
        for(Path p:authorizedRoots)roots.add(p.toRealPath());
    }
    public static final class Evidence {
        public String state="not_checked", sha256, previousSha256, fileKey;
        public long size, modifiedAt, observedAt;
        public boolean stableDuringRead, sameAsPrevious;
    }
    public static final class Report {
        public RuntimeJobRegistry.JobRecord job;
        public Evidence file=new Evidence();
        public String boundary, externalIdCheck="unverified_no_provider_contact";
        public boolean executionReattachAllowed=false, completionProven=false;
        public boolean humanReviewEligible;
        public String warning="UNKNOWN remains UNKNOWN. Stable bytes do not prove completion or producer identity.";
    }
    public List<RuntimeJobRegistry.JobRecord> list(){return jobs.list();}
    public Report inspect(String id){
        Report r=new Report();r.job=jobs.get(id);
        r.humanReviewEligible=jobs.canReviewRecovery(r.job);
        r.boundary=r.job.cancelRequested?"not_recoverable_cancelled":
            r.job.status==RuntimeJobRegistry.Status.UNKNOWN?(r.humanReviewEligible?
                "waiting_user_execution_unverifiable":"not_recoverable_superseded_or_unowned"):
                "status_only_no_recovery_needed";
        return r;
    }
    /** previousSha256 is a caller-held prior observation, not a gold label or ledger mutation. */
    public Report reconcile(String id,String previousSha256,String expectedExternalId) {
        if(previousSha256!=null&&!previousSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid previousSha256");
        Report r=inspect(id);r.file.previousSha256=previousSha256;
        if(expectedExternalId!=null)r.externalIdCheck=Objects.equals(expectedExternalId,r.job.externalInstanceId)
                ?"matches_ledger_only_not_provider_verified":"mismatch";
        if(r.job.resultPath==null||r.job.resultPath.trim().isEmpty()){r.file.state="no_result_path";return r;}
        try {
            Path p=Paths.get(r.job.resultPath);
            // Relative paths need an explicit host base; never guess from JVM working directory.
            if(!p.isAbsolute()){
                if(relativeBase==null){r.file.state="relative_path_requires_host_resolution";return r;}
                p=relativeBase.resolve(p);
            }
            p=p.normalize();
            boolean allowed=false;
            for(Path root:roots)if(p.startsWith(root)&&!p.equals(root))allowed=true;
            if(!allowed){r.file.state="not_authorized";return r;}
            // Reject symlinks, including directory components. Re-check after reading as well.
            rejectLinks(p);
            BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isRegularFile()){r.file.state="not_regular_file";return r;}
            if(a.size()>MAX_BYTES){r.file.state="too_large";return r;}
            String first=hash(p);
            BasicFileAttributes b=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            String second=hash(p);
            BasicFileAttributes c=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            rejectLinks(p);
            r.file.observedAt=System.currentTimeMillis();r.file.size=c.size();r.file.modifiedAt=c.lastModifiedTime().toMillis();
            r.file.fileKey=String.valueOf(c.fileKey());
            r.file.stableDuringRead=same(a,b)&&same(b,c)&&first.equals(second);
            r.file.state=r.file.stableDuringRead?"stable_observation":"changed_during_read";
            if(r.file.stableDuringRead){r.file.sha256=second;r.file.sameAsPrevious=second.equals(previousSha256);
                if(r.humanReviewEligible)r.boundary="evidence_available_waiting_user_not_execution_recoverable";}
            if(jobs.get(id).version!=r.job.version){r.file.state="job_changed_retry_inspection";r.boundary="waiting_user_stale_snapshot";}
        } catch(NoSuchFileException e){r.file.state="missing";}
          catch(IOException|RuntimeException e){r.file.state="unavailable_or_unsafe";}
        return r;
    }
    /** Trusted authenticated UI only. Rechecks bytes and binding before recording an attestation.
     * Even an accepted review NEVER clears UNKNOWN/fence or marks the job complete.
     */
    public RuntimeJobRegistry.JobRecord acknowledge(Report reviewed,String reviewer,String note) throws IOException {
        Objects.requireNonNull(reviewed,"reviewed");
        RuntimeJobRegistry.JobRecord old=Objects.requireNonNull(reviewed.job,"job");
        Report fresh=reconcile(old.jobId,reviewed.file.sha256,old.externalInstanceId);
        if(fresh.job.version!=old.version||!Objects.equals(old.taskId,fresh.job.taskId)
                ||old.revision!=fresh.job.revision||old.attempt!=fresh.job.attempt
                ||!Objects.equals(old.ownerEpoch,fresh.job.ownerEpoch)||!fresh.humanReviewEligible)
            throw new IllegalArgumentException("stale or mismatched recovery binding");
        if(reviewed.file.sha256!=null&&(!fresh.file.stableDuringRead||!fresh.file.sameAsPrevious))
            throw new IllegalArgumentException("result evidence changed or unavailable");
        return jobs.acknowledgeRecovery(old.jobId,old.taskId,old.revision,old.attempt,old.ownerEpoch,
                old.version,reviewer,note,reviewed.file.sha256,old.externalInstanceId,fresh.file);
    }
    private static void rejectLinks(Path p) throws IOException {
        for(Path q=p;q!=null;q=q.getParent())if(Files.isSymbolicLink(q))throw new IOException("symlink not allowed");
        if(!p.toRealPath().equals(p))throw new IOException("path alias not allowed");
    }
    private static boolean same(BasicFileAttributes a,BasicFileAttributes b){
        return a.isRegularFile()&&b.isRegularFile()&&a.size()==b.size()&&a.lastModifiedTime().equals(b.lastModifiedTime())
                &&a.creationTime().equals(b.creationTime())&&Objects.equals(a.fileKey(),b.fileKey());
    }
    private static String hash(Path p) throws IOException {
        try {
            MessageDigest d=MessageDigest.getInstance("SHA-256");long total=0;
            try(InputStream in=Files.newInputStream(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){total+=n;if(total>MAX_BYTES)throw new IOException("size limit");d.update(buf,0,n);}
            }
            StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
