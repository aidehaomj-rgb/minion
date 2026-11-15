package com.minion.core.context;

import com.minion.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only projection: never rewrites archived skill events or reads changed files as old snapshots. */
public final class SkillResidency {
    public static final int MAX_RESIDENT_SKILLS = 2;
    public static final int MAX_RESIDENT_CHARS = 24000;
    public static final int MAX_USER_TURNS = 8;
    /** Original load time is never renewed by compact; visible-turn counting alone would reset after compact. */
    public static final long MAX_AGE_MILLIS = 30L * 60L * 1000L;
    public static final int MAX_RETIRED_REFERENCES = 8;

    private SkillResidency() { }

    public static boolean isSkill(Message m) {
        return m != null && m.role == Message.Role.USER && !m.summary
                && (m.origin == Message.Origin.SKILL || (m.origin == null && m.pinned));
    }

    static boolean isReference(Message m) {
        return m != null && m.role == Message.Role.USER && m.origin == Message.Origin.HISTORY_RECALL
                && m.skillName != null;
    }

    /** Tags are a compatibility fallback ONLY for already-typed/pinned skill material, never user input. */
    public static String nameOf(Message m) {
        if (!isSkill(m)) return null;
        if (m.skillName != null && !m.skillName.trim().isEmpty()) return m.skillName.trim();
        if (m.content == null) return null;
        String prefix = "<skill name=\"";
        if (!m.content.startsWith(prefix)) return null;
        int end = m.content.indexOf("\">", prefix.length());
        return end > prefix.length() && end - prefix.length() <= 200
                ? m.content.substring(prefix.length(), end) : null;
    }

    public static boolean isResident(List<Message> messages, String name, String taskId, long revision) {
        return select(messages, taskId, revision, null).containsName(messages, name);
    }

    public static String contentVersion(String instructions) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((instructions == null ? "" : instructions).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return "sha256:" + out;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String versionOf(Message m) {
        if (m.sourceVersion != null) return m.sourceVersion;
        // Legacy pinned bodies were written using this exact envelope. Unknown envelopes force reload.
        if (nameOf(m) == null || m.content == null) return null;
        int start = m.content.indexOf("\">\n"), end = m.content.indexOf("\n</skill>", start + 3);
        return start >= 0 && end >= start + 3 ? contentVersion(m.content.substring(start + 3, end)) : null;
    }

    /** Unscoped entry point for callers without a ContextManager; use latest explicit task scope if present. */
    public static boolean isResident(List<Message> messages, String name) {
        return isResident(messages, name, null);
    }

    public static boolean isResident(List<Message> messages, String name, String version) {
        String task = null; long revision = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.isUserInstruction() && m.taskId != null) { task = m.taskId; revision = m.revision(); break; }
        }
        return select(messages, task, revision, null).containsName(messages, name, version);
    }

    static Selection select(List<Message> messages, String taskId, long revision, Set<String> activeNames) {
        Set<Integer> resident = new HashSet<Integer>();
        Set<Integer> references = new HashSet<Integer>();
        Set<String> seenNames = new HashSet<String>();
        int turns = 0, chars = 0;
        long now = System.currentTimeMillis();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (isReference(m)) {
                if (references.size() < MAX_RETIRED_REFERENCES) references.add(i);
                continue;
            }
            if (!isSkill(m)) { if (m.isUserInstruction()) turns++; continue; }
            String name = nameOf(m);
            String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
            boolean scope = taskId == null || (taskId.equals(m.taskId) && m.revision() == revision);
            boolean newest = scope && seenNames.add(key);
            boolean leased = m.ts > 0 && m.ts <= now && now - m.ts <= MAX_AGE_MILLIS;
            boolean wanted = turns <= MAX_USER_TURNS && leased && (activeNames == null || activeNames.contains(key));
            int length = bodyChars(m);
            if (newest && name != null && scope && wanted && length > 0
                    && resident.size() < MAX_RESIDENT_SKILLS && length <= MAX_RESIDENT_CHARS - chars) {
                resident.add(i); chars += length;
            } else if (references.size() < MAX_RETIRED_REFERENCES) {
                references.add(i);
            }
        }
        return new Selection(resident, references);
    }

    static Set<String> activeNames(List<String> names) {
        if (names == null) return null;
        Set<String> out = new LinkedHashSet<String>();
        for (String name : names) {
            if (name == null || name.trim().isEmpty() || name.length() > 200)
                throw new IllegalArgumentException("技能名须为1-200字符");
            out.add(name.trim().toLowerCase(Locale.ROOT));
        }
        if (out.size() > MAX_RESIDENT_SKILLS) throw new IllegalArgumentException("当前步骤最多常驻2个技能");
        return Collections.unmodifiableSet(out);
    }

    static Message reference(Message source) {
        if (isReference(source)) return source;
        String name = nameOf(source);
        Message ref = Message.historyRecall("[origin=history_recall]\n[技能正文已卸载，不是当前指令；如当前步骤需要，请重新调用 Skill 加载。"
                + " name=" + safe(name, 200) + "；" + sourceReference(source)
                + "；可用 Memory.search_history 按技能名检索并核对 event，取得消息ID后用 Memory.read_history 读原文。]");
        ref.sourceEventId = source.eventId;
        ref.skillName = name == null ? "" : name;
        ref.sourcePath = source.sourcePath;
        ref.sourceVersion = source.sourceVersion;
        ref.taskId = source.taskId;
        ref.taskRevision = source.taskRevision;
        return ref;
    }

    static String sourceReference(Message m) {
        return "event=" + safe(m.eventId, 200)
                + (m.sourcePath == null ? "" : "；path=" + safe(m.sourcePath, 1000))
                + (m.sourceVersion == null ? "" : "；version=" + safe(m.sourceVersion, 200))
                + (m.toolCallId == null ? "" : "；tool_call=" + safe(m.toolCallId, 200))
                + "；原文在本地不可变会话事件/压缩前历史中，非当前磁盘快照";
    }

    static String bodyLabel(Message m) { return "[origin=skill]\n[" + sourceReference(m) + "]\n"; }

    private static int bodyChars(Message m) {
        if (m.content == null) return 0;
        String label = bodyLabel(m);
        return m.content.length() - (m.content.startsWith(label) ? label.length() : 0);
    }

    private static String safe(String text, int max) {
        if (text == null) return "unknown";
        return text.substring(0, Math.min(text.length(), max)).replace('\n', ' ').replace('\r', ' ');
    }

    static final class Selection {
        final Set<Integer> resident, references;
        Selection(Set<Integer> resident, Set<Integer> references) {
            this.resident = resident; this.references = references;
        }
        boolean containsName(List<Message> messages, String name) {
            return containsName(messages, name, null);
        }
        boolean containsName(List<Message> messages, String name, String version) {
            if (name == null) return false;
            for (int i : resident) if (name.equalsIgnoreCase(nameOf(messages.get(i)))
                    && (version == null || version.equals(versionOf(messages.get(i))))) return true;
            return false;
        }
        List<Message> retained(List<Message> messages) {
            List<Message> out = new ArrayList<Message>();
            for (int i = 0; i < messages.size(); i++) {
                if (resident.contains(i)) out.add(messages.get(i));
                else if (references.contains(i)) out.add(reference(messages.get(i)));
            }
            return out;
        }
    }
}
