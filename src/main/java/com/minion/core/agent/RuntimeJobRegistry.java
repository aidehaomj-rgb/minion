package com.minion.core.agent;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.minion.core.tools.AtomicFiles;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Durable control-plane ledger. Only data is persisted, never Process/Future/callbacks.
 * A ledger has one live owner (also across JVMs). Reopening fences every unfinished job
 * as UNKNOWN; it never probes a saved PID, invokes a provider, or retries a submission.
 * COMPLETED is a provider exit/result, not acceptance of the parent task's deliverable.
 */
public final class RuntimeJobRegistry implements AutoCloseable {
    public static final long MAX_WAIT_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final int MAX_JOBS = 4096;
    private static final long MAX_LEDGER_BYTES = 16 * 1024 * 1024;

    public enum Status {
        @SerializedName("queued") QUEUED,
        @SerializedName("running") RUNNING,
        @SerializedName("waiting_external") WAITING_EXTERNAL,
        @SerializedName("waiting_user") WAITING_USER,
        @SerializedName("verifying") VERIFYING,
        @SerializedName("completed") COMPLETED,
        @SerializedName("failed") FAILED,
        @SerializedName("cancelled") CANCELLED,
        @SerializedName("unknown") UNKNOWN;
        /** UNKNOWN ends automatic waiting, but is expressly not success. */
        public boolean settled() { return this == COMPLETED || this == FAILED || this == CANCELLED || this == UNKNOWN; }
    }

    /** Detached data snapshot. Mutating it cannot change the ledger. */
    public static final class JobRecord {
        public String jobId, taskId, kind, description, externalInstanceId, resultPath, ownerEpoch;
        public long revision, createdAt, updatedAt, finishedAt, version;
        public int attempt;
        public Status status;
        /** Explicit one-shot opt-in; background services default to false. */
        public boolean waitForCompletion;
        public boolean cancelRequested, fenced, recovered, lateResult;
        public String cancelReason, fenceReason, detail;
        public long cancelRequestedAt;
        public Integer exitCode;
        /** Late observations are archived separately and cannot promote canonical status. */
        public Status observedStatus;
        public String observedResultPath, observedExternalInstanceId, observedDetail;
        public Integer observedExitCode;
        public long observedAt;
        public List<AuditEntry> history=new ArrayList<AuditEntry>();
        public List<RecoveryReview> recoveryReviews=new ArrayList<RecoveryReview>();
    }

    /** Prior canonical state, captured before every durable transition (including restart). */
    public static final class AuditEntry {
        public long version, at;
        public Status status;
        public boolean fenced, cancelRequested;
        public String ownerEpoch, detail, resultPath, externalInstanceId, fenceReason;
        /** Full prior data except recursive history, including late observations and reviews. */
        public String stateJson;
    }
    /** Human attestation, not proof of completion and not authority to resume. */
    public static final class RecoveryReview {
        public String reviewer, note, sha256, externalInstanceId;
        public long at, evidenceVersion;
        public String evidenceSource="human_attestation_not_provider_verified";
        public RuntimeJobRecovery.Evidence fileEvidence;
    }

    /** Submission identity; cannot be constructed from just a PID/jobId. No retry API. */
    public static final class Handle {
        public final String jobId, taskId, ownerEpoch;
        public final long revision;
        public final int attempt;
        private Handle(JobRecord r) { jobId=r.jobId;taskId=r.taskId;ownerEpoch=r.ownerEpoch;revision=r.revision;attempt=r.attempt; }
    }

    public enum WaitReason { SETTLED, CHANGED, TIMED_OUT, WAIT_CANCELLED, FENCED, CLOSED, STORAGE_ERROR }
    public static final class WaitResult {
        public final WaitReason reason;
        public final JobRecord job;
        public final String error;
        /** A wakeup may belong to an old revision; only this flag authorizes resuming its task. */
        public final boolean canResumeTask;
        private WaitResult(WaitReason reason, JobRecord job, String error, boolean resume) {
            this.reason=reason;this.job=job;this.error=error;this.canResumeTask=resume;
        }
    }

    private static final class Ledger {
        int schemaVersion=1;
        long sequence;
        Map<String,Long> revisions=new LinkedHashMap<String,Long>();
        Map<String,Long> cancelledRevisions=new LinkedHashMap<String,Long>();
        Map<String,JobRecord> jobs=new LinkedHashMap<String,JobRecord>();
    }

    private final Gson gson=new Gson();
    private final Path ledgerPath;
    private final String epoch=UUID.randomUUID().toString();
    private final FileChannel lockChannel;
    private final FileLock ownerLock;
    private Ledger ledger;
    private boolean closed;
    private String storageError;

    public RuntimeJobRegistry(Path path) throws IOException {
        ledgerPath=Objects.requireNonNull(path,"path").toAbsolutePath().normalize();
        if(ledgerPath.getParent()==null)throw new IOException("ledger needs a parent directory");
        Files.createDirectories(ledgerPath.getParent());
        lockChannel=FileChannel.open(ledgerPath.resolveSibling(ledgerPath.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired=null;
        try {
            try { acquired=lockChannel.tryLock(); }
            catch(OverlappingFileLockException e){throw new IOException("runtime ledger already owned: "+ledgerPath,e);}
            if(acquired==null)throw new IOException("runtime ledger already owned: "+ledgerPath);
            ownerLock=acquired;
            if(Files.exists(ledgerPath)) {
                if(Files.size(ledgerPath)>MAX_LEDGER_BYTES)throw new IOException("runtime ledger too large");
                try { ledger=gson.fromJson(new String(Files.readAllBytes(ledgerPath),StandardCharsets.UTF_8),Ledger.class); }
                catch(RuntimeException e){throw new IOException("invalid runtime ledger (not overwritten): "+ledgerPath,e);}
                validateLoaded();
                Ledger recovered=copy(ledger);boolean changed=false;
                for(JobRecord r:recovered.jobs.values())if(!r.status.settled()) {
                    r.status=Status.UNKNOWN;r.recovered=true;r.fenced=true;
                    r.fenceReason="restart: execution identity cannot be safely reattached";
                    r.detail="No PID replay or automatic resubmission";
                    stamp(recovered,r);changed=true;
                }
                if(changed)commit(recovered);
            } else { ledger=new Ledger();commit(copy(ledger)); }
        } catch(IOException|RuntimeException|Error e) {
            if(acquired!=null)try{acquired.release();}catch(IOException ignored){}
            try{lockChannel.close();}catch(IOException ignored){}
            throw e;
        }
    }

    public Path ledgerPath(){return ledgerPath;}
    public synchronized String storageError(){return storageError;}

    /** Monotonic per task. Revision changes fence old results; they do NOT kill/restart work. */
    public synchronized void bindTask(String taskId,long revision) throws IOException {
        ensureWritable();required(taskId,"taskId");if(revision<1)throw new IllegalArgumentException("revision must be >=1");
        Long old=ledger.revisions.get(taskId);
        if(old!=null&&revision<old)throw new IllegalArgumentException("stale task revision");
        if(old!=null&&revision==old)return;
        Ledger next=copy(ledger);next.revisions.put(taskId,revision);
        for(JobRecord r:next.jobs.values())if(taskId.equals(r.taskId)&&r.revision!=revision) {
            r.fenced=true;r.fenceReason="task revision superseded";
            if(!r.status.settled())r.status=Status.UNKNOWN;
            stamp(next,r);
        }
        commit(next);
    }

    /** Must commit before any external side effect. Every explicit submission gets a new ID. */
    public synchronized Handle register(String taskId,long revision,int attempt,String kind,String description,
                                        String resultPath,boolean waitForCompletion) throws IOException {
        ensureWritable();required(taskId,"taskId");required(kind,"kind");
        if(revision<1||attempt<1)throw new IllegalArgumentException("revision/attempt must be >=1");
        if(ledger.jobs.size()>=MAX_JOBS)throw new IOException("runtime ledger full; choose a new explicit ledger (no history is deleted)");
        bindTask(taskId,revision);
        Long cancelled=ledger.cancelledRevisions.get(taskId);
        if(cancelled!=null&&revision<=cancelled)throw new IOException("task revision was cancelled; explicit new revision required");
        Ledger next=copy(ledger);JobRecord r=new JobRecord();
        r.jobId=UUID.randomUUID().toString();r.taskId=taskId;r.revision=revision;r.attempt=attempt;
        r.kind=kind;r.description=description;r.resultPath=resultPath;r.waitForCompletion=waitForCompletion;
        r.status=Status.QUEUED;r.ownerEpoch=epoch;r.createdAt=System.currentTimeMillis();
        next.jobs.put(r.jobId,r);stamp(next,r);commit(next);return new Handle(r);
    }

    /**
     * Provider event. Terminal/cancelled/old-owner/old-revision observations are archival only.
     * A saved externalInstanceId is evidence, never authority to reconnect/kill a local PID.
     */
    public synchronized JobRecord publish(Handle handle,Status status,String externalInstanceId,
                                           String resultPath,Integer exitCode,String detail) throws IOException {
        ensureWritable();Objects.requireNonNull(status,"status");JobRecord old=identity(handle);
        if(status==Status.QUEUED)throw new IllegalArgumentException("cannot requeue an existing submission");
        boolean stale=old.fenced||old.cancelRequested||!epoch.equals(handle.ownerEpoch)||!current(old);
        if(old.status.settled()&&!stale&&old.status==status&&Objects.equals(old.exitCode,exitCode)
                &&(resultPath==null||Objects.equals(old.resultPath,resultPath)))return snapshot(old);
        Ledger next=copy(ledger);JobRecord r=next.jobs.get(handle.jobId);
        if(stale||old.status.settled()) {
            r.lateResult=true;r.observedStatus=status;r.observedAt=System.currentTimeMillis();
            r.observedExternalInstanceId=externalInstanceId;r.observedResultPath=resultPath;
            r.observedExitCode=exitCode;r.observedDetail=detail;
        } else {
            r.status=status;if(externalInstanceId!=null)r.externalInstanceId=externalInstanceId;
            if(resultPath!=null)r.resultPath=resultPath;r.exitCode=exitCode;r.detail=detail;
            if(status.settled())r.finishedAt=System.currentTimeMillis();
        }
        stamp(next,r);commit(next);return snapshot(r);
    }

    /** Cancellation is a durable control decision, NOT proof the external process has exited. */
    public synchronized JobRecord cancel(String jobId,String reason) throws IOException {
        ensureWritable();JobRecord old=record(jobId);
        if(old.cancelRequested||old.status==Status.COMPLETED||old.status==Status.FAILED)return snapshot(old);
        Ledger next=copy(ledger);JobRecord r=next.jobs.get(jobId);
        r.cancelRequested=true;r.cancelRequestedAt=System.currentTimeMillis();r.cancelReason=reason;
        r.fenced=true;r.fenceReason="cancellation requested";
        // UNKNOWN must remain unknown: cancelling an unowned external execution is unconfirmed.
        if(r.status!=Status.UNKNOWN)r.status=Status.CANCELLED;
        stamp(next,r);commit(next);return snapshot(r);
    }

    /** Durable task-stop fence. Adapters must separately stop their live handles; no PID replay. */
    public synchronized void cancelTask(String taskId,long revision,String reason) throws IOException {
        ensureWritable();required(taskId,"taskId");
        if(revision<1)throw new IllegalArgumentException("revision must be >=1");
        Long current=ledger.revisions.get(taskId);
        if(current==null||current!=revision)throw new IllegalArgumentException("cancel requires current task revision");
        Long cancelled=ledger.cancelledRevisions.get(taskId);if(cancelled!=null&&cancelled>=revision)return;
        Ledger next=copy(ledger);next.cancelledRevisions.put(taskId,revision);
        for(JobRecord r:next.jobs.values())if(taskId.equals(r.taskId)&&revision==r.revision&&!r.status.settled()) {
            r.cancelRequested=true;r.cancelRequestedAt=System.currentTimeMillis();r.cancelReason=reason;
            r.fenced=true;r.fenceReason="task cancellation requested";r.status=Status.CANCELLED;stamp(next,r);
        }
        commit(next);
    }

    public synchronized JobRecord get(String jobId){return snapshot(record(jobId));}
    public synchronized List<JobRecord> list(){return select(null,0,false);}
    public synchronized List<JobRecord> jobsFor(String taskId,long revision){return select(taskId,revision,false);}
    /** All opt-in foreground records (including failed/UNKNOWN). Caller must inspect status. */
    public synchronized List<JobRecord> foregroundJobs(String taskId,long revision){return select(taskId,revision,true);}
    public synchronized boolean isCurrent(JobRecord r){return r!=null&&!r.fenced&&!r.cancelRequested&&current(r);}
    public synchronized boolean canReviewRecovery(JobRecord snapshot){
        if(snapshot==null)return false;
        JobRecord r=ledger.jobs.get(snapshot.jobId);
        return r!=null&&r.version==snapshot.version&&current(r)&&!r.cancelRequested
                &&r.recovered&&r.fenced&&r.status==Status.UNKNOWN;
    }

    /** Trusted UI/API only. Records a review without changing UNKNOWN/fence/owner.
     * Caller authenticates reviewer; no model tool exposes this method.
     */
    public synchronized JobRecord acknowledgeRecovery(String jobId,String taskId,long revision,int attempt,
            String ownerEpoch,long expectedVersion,String reviewer,String note,
            String sha256,String externalInstanceId) throws IOException {
        return acknowledgeRecovery(jobId,taskId,revision,attempt,ownerEpoch,expectedVersion,
                reviewer,note,sha256,externalInstanceId,null);
    }
    synchronized JobRecord acknowledgeRecovery(String jobId,String taskId,long revision,int attempt,
            String ownerEpoch,long expectedVersion,String reviewer,String note,
            String sha256,String externalInstanceId,RuntimeJobRecovery.Evidence evidence) throws IOException {
        ensureWritable();required(reviewer,"reviewer");required(note,"note");
        JobRecord old=record(jobId);
        if(!Objects.equals(taskId,old.taskId)||revision!=old.revision||attempt!=old.attempt
                ||!Objects.equals(ownerEpoch,old.ownerEpoch)||expectedVersion!=old.version)
            throw new IllegalArgumentException("recovery identity/version mismatch");
        if(!current(old)||old.cancelRequested||!old.recovered||!old.fenced||old.status!=Status.UNKNOWN)
            throw new IllegalArgumentException("not an eligible current recovered UNKNOWN attempt");
        if(sha256!=null&&!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid sha256");
        if(!Objects.equals(externalInstanceId,old.externalInstanceId))throw new IllegalArgumentException("external ID mismatch");
        Ledger next=copy(ledger);JobRecord r=next.jobs.get(jobId);
        if(r.recoveryReviews==null)r.recoveryReviews=new ArrayList<RecoveryReview>();
        RecoveryReview review=new RecoveryReview();review.reviewer=reviewer;review.note=note;
        review.sha256=sha256;review.externalInstanceId=externalInstanceId;
        if(evidence!=null){
            review.fileEvidence=gson.fromJson(gson.toJson(evidence),RuntimeJobRecovery.Evidence.class);
            review.evidenceSource="read_only_file_observation_and_human_attestation_not_provider_verified";
        }
        review.at=System.currentTimeMillis();review.evidenceVersion=expectedVersion;r.recoveryReviews.add(review);
        stamp(next,r);commit(next);return snapshot(r);
    }

    /** Zero polling threads/model turns: provider commits and cancellation notify this monitor. */
    public WaitResult awaitTerminal(String jobId,long timeoutMillis,RuntimeJobCancellation cancellation) throws InterruptedException {
        return await(jobId,-1,timeoutMillis,cancellation);
    }

    /** Wait for one durable event after a detached record's version, or a settled/fenced state. */
    public WaitResult awaitChange(String jobId,long afterVersion,long timeoutMillis,RuntimeJobCancellation cancellation) throws InterruptedException {
        if(afterVersion<0)throw new IllegalArgumentException("afterVersion must be >=0");
        return await(jobId,afterVersion,timeoutMillis,cancellation);
    }

    private WaitResult await(String jobId,long afterVersion,long timeoutMillis,RuntimeJobCancellation cancellation) throws InterruptedException {
        if(timeoutMillis<0||timeoutMillis>MAX_WAIT_MILLIS)throw new IllegalArgumentException("wait timeout must be 0..86400000 ms");
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        RuntimeJobCancellation.Registration registration=cancellation==null?null:cancellation.onCancel(()->{synchronized(this){notifyAll();}});
        try { synchronized(this) { for(;;) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("runtime job wait interrupted");
            JobRecord r=record(jobId);WaitReason reason=null;
            if(cancellation!=null&&cancellation.isCancelled())reason=WaitReason.WAIT_CANCELLED;
            else if(closed)reason=WaitReason.CLOSED;
            else if(storageError!=null)reason=WaitReason.STORAGE_ERROR;
            else if(r.fenced||r.cancelRequested||!current(r))reason=WaitReason.FENCED;
            else if(r.status.settled())reason=WaitReason.SETTLED;
            else if(afterVersion>=0&&r.version>afterVersion)reason=WaitReason.CHANGED;
            long remaining=end-System.nanoTime();
            if(reason==null&&remaining<=0)reason=WaitReason.TIMED_OUT;
            if(reason!=null)return new WaitResult(reason,snapshot(r),storageError,
                    reason==WaitReason.SETTLED&&r.status!=Status.UNKNOWN&&!r.fenced&&!r.cancelRequested&&current(r));
            TimeUnit.NANOSECONDS.timedWait(this,remaining);
        } } } finally { if(registration!=null)registration.close(); }
    }

    private List<JobRecord> select(String taskId,long revision,boolean foreground) {
        List<JobRecord> out=new ArrayList<JobRecord>();
        for(JobRecord r:ledger.jobs.values())if((taskId==null||taskId.equals(r.taskId)&&revision==r.revision)&&(!foreground||r.waitForCompletion))out.add(snapshot(r));
        return out;
    }
    private boolean current(JobRecord r){
        Long revision=ledger.revisions.get(r.taskId),cancelled=ledger.cancelledRevisions.get(r.taskId);
        return revision!=null&&revision==r.revision&&(cancelled==null||r.revision>cancelled);
    }
    private JobRecord record(String id){JobRecord r=ledger.jobs.get(id);if(r==null)throw new IllegalArgumentException("unknown job: "+id);return r;}
    private JobRecord identity(Handle h){
        Objects.requireNonNull(h,"handle");JobRecord r=record(h.jobId);
        if(!r.taskId.equals(h.taskId)||r.revision!=h.revision||r.attempt!=h.attempt||!r.ownerEpoch.equals(h.ownerEpoch))throw new IllegalArgumentException("job identity/attempt mismatch");
        return r;
    }
    private void stamp(Ledger next,JobRecord r){
        JobRecord old=ledger.jobs.get(r.jobId);
        if(old!=null){
            if(r.history==null)r.history=new ArrayList<AuditEntry>();
            AuditEntry a=new AuditEntry();a.version=old.version;a.at=old.updatedAt;a.status=old.status;
            a.fenced=old.fenced;a.cancelRequested=old.cancelRequested;a.ownerEpoch=old.ownerEpoch;
            a.detail=old.detail;a.resultPath=old.resultPath;a.externalInstanceId=old.externalInstanceId;
            com.google.gson.JsonObject state=gson.toJsonTree(old).getAsJsonObject();
            state.remove("history");a.stateJson=gson.toJson(state);
            a.fenceReason=old.fenceReason;r.history.add(a);
        }
        r.version=++next.sequence;r.updatedAt=System.currentTimeMillis();
    }
    private JobRecord snapshot(JobRecord r){return gson.fromJson(gson.toJson(r),JobRecord.class);}
    private Ledger copy(Ledger r){return gson.fromJson(gson.toJson(r),Ledger.class);}
    private synchronized void commit(Ledger next) throws IOException {
        try {
            byte[] bytes=gson.toJson(next).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_LEDGER_BYTES)throw new IOException("runtime ledger exceeds size limit");
            AtomicFiles.writeBytes(ledgerPath,bytes);ledger=next;notifyAll();
        } catch(IOException e){storageError=e.toString();notifyAll();throw e;}
    }
    private void ensureWritable() throws IOException {
        if(closed)throw new IOException("runtime registry closed");
        if(storageError!=null)throw new IOException("runtime ledger unavailable (no submission allowed): "+storageError);
    }
    private static void required(String text,String field){if(text==null||text.trim().isEmpty()||text.length()>4096)throw new IllegalArgumentException("invalid "+field);}
    private void validateLoaded() throws IOException {
        if(ledger==null||ledger.schemaVersion!=1||ledger.jobs==null||ledger.revisions==null||ledger.cancelledRevisions==null||ledger.jobs.size()>MAX_JOBS)throw new IOException("invalid runtime ledger schema (not overwritten)");
        for(Map.Entry<String,JobRecord> entry:ledger.jobs.entrySet()) {
            JobRecord r=entry.getValue();
            if(r==null||!entry.getKey().equals(r.jobId)||r.taskId==null||r.ownerEpoch==null||r.kind==null||r.status==null||r.attempt<1||r.revision<1
                    ||!ledger.revisions.containsKey(r.taskId)||ledger.revisions.get(r.taskId)==null||ledger.revisions.get(r.taskId)<r.revision||r.version>ledger.sequence)
                throw new IOException("invalid runtime job identity/state (not overwritten)");
        }
    }

    /** Does not cancel, kill, reconnect, or resubmit any external execution. */
    @Override public synchronized void close() throws IOException {
        if(closed)return;closed=true;notifyAll();
        try{ownerLock.release();}finally{lockChannel.close();}
    }
}
