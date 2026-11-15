package com.minion.core.agent;

import java.util.ArrayList;
import java.util.List;

/** Local file receipt, not proof of semantic correctness or task completion. */
public class GeneratedArtifact {
    public static final String DELIVERABLE = "deliverable";
    public static final String EXECUTION_RECORD = "execution_record";
    public static final String REFERENCE = "reference";

    public String path, title, kind, taskId, sourceTool;
    public Long revision;
    public long updatedAt, size;
    /** Immutable copy of the just-registered revision, used only for internal version history. */
    public String snapshotPath;
    public List<Version> history = new ArrayList<Version>();

    public static class Version {
        public String snapshotPath, sourceTool;
        public Long revision;
        public long updatedAt, size;

        public Version copy() {
            Version v = new Version();
            v.snapshotPath = snapshotPath; v.sourceTool = sourceTool; v.revision = revision;
            v.updatedAt = updatedAt; v.size = size;
            return v;
        }
    }

    public GeneratedArtifact copy() {
        GeneratedArtifact a = new GeneratedArtifact();
        a.path = path; a.title = title; a.kind = kind; a.taskId = taskId;
        a.sourceTool = sourceTool; a.revision = revision; a.updatedAt = updatedAt; a.size = size;
        a.snapshotPath = snapshotPath;
        if (history != null) for (Version version : history) if (version != null) a.history.add(version.copy());
        return a;
    }
}
