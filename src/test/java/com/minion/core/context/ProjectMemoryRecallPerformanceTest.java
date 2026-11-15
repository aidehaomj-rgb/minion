package com.minion.core.context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class ProjectMemoryRecallPerformanceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void longRequirementRecallsFinalChineseConstraintInsteadOfOnlyFirst180Chars() throws Exception {
        ProjectMemoryStore store = new ProjectMemoryStore(tmp.getRoot().toPath());
        ProjectMemoryStore.Entry entry = verified(store, "禁止上传客户资料，数据只能保留在内网", null);
        StringBuilder request = new StringBuilder("请审查38个分块；");
        for (int i = 0; i < 1000; i++) request.append("分析字段映射以及来源，");
        request.append("禁止上传客户资料");
        assertTrue(store.search(request.substring(0, 180), 3).isEmpty());
        assertEquals(entry.id, store.recall(MemoryRecallQuery.from(request.toString()), 3).get(0).id);
        assertEquals(entry.id, store.search(request.toString(), 3).get(0).id);
    }

    @Test public void latestCorrectionWithoutSpacesOutranksRepeatedGoalAndActiveStepIsAlsoRecalled() throws Exception {
        ProjectMemoryStore store = new ProjectMemoryStore(tmp.getRoot().toPath());
        ProjectMemoryStore.Entry goal = verified(store, "销售统计采用季度合计", null);
        ProjectMemoryStore.Entry step = verified(store, "读取原始票据执行数量核验", null);
        ProjectMemoryStore.Entry correction = verified(store, "禁止上传客户资料", null);
        MemoryRecallQuery query = MemoryRecallQuery.from("销售统计采用季度合计", "读取原始票据执行数量核验", "禁止上传客户资料");
        List<ProjectMemoryStore.Entry> hits = store.recall(query, 20);
        assertEquals(correction.id, hits.get(0).id); assertEquals(step.id, hits.get(1).id); assertEquals(goal.id, hits.get(2).id);
        assertTrue(store.recall(MemoryRecallQuery.from("火星着陆器引擎"), 3).isEmpty());
    }

    @Test public void sourceHashIsSharedByOneThousandMemoriesAndAllQuerySegmentsButNotAcrossCalls() throws Exception {
        Path root = tmp.getRoot().toPath(); final int sourceBytes = 2 * 1024 * 1024;
        Files.write(root.resolve("large.txt"), new byte[sourceBytes]);
        AtomicInteger hashes = new AtomicInteger(); AtomicLong bytes = new AtomicLong();
        ProjectMemoryStore store = new ProjectMemoryStore(root, path -> {
            hashes.incrementAndGet(); bytes.addAndGet(Files.size(path));
            return SourceFingerprintBatch.SHA256.sha256(path);
        });
        ProjectMemoryStore.Details details = new ProjectMemoryStore.Details();
        details.sourcePaths = Arrays.asList("large.txt", "./large.txt");
        ProjectMemoryStore.Entry original = store.remember("decision", "报表交付必须核对来源并保留路径", "人工核验", details);
        hashes.set(0); bytes.set(0); store.setVerified(original.id, true);
        assertEquals("capture and return evaluation share the same verification batch", 1, hashes.get());
        Path entryFile = root.resolve(".minion/memory/entries/" + original.id + ".md");
        String markdown = new String(Files.readAllBytes(entryFile), StandardCharsets.UTF_8);
        // IDs are not part of a verification hash. Clone the verified fixture without 1000 mutation scans.
        for (int i = 1; i < 1000; i++) {
            String id = UUID.randomUUID().toString();
            Files.write(entryFile.resolveSibling(id + ".md"), markdown.replace(original.id, id).getBytes(StandardCharsets.UTF_8));
        }
        hashes.set(0); bytes.set(0);
        List<ProjectMemoryStore.Entry> hits = store.recall(MemoryRecallQuery.from("报表交付", "核对来源", "保留路径"), 20);
        assertEquals("duplicate text is injected only once", 1, hits.size());
        assertEquals(1, hashes.get()); assertEquals(sourceBytes, bytes.get());
        hashes.set(0); bytes.set(0);
        assertEquals(1000, store.list().size()); assertEquals(1, hashes.get()); assertEquals(sourceBytes, bytes.get());
        hashes.set(0); assertEquals(1, store.verifiedIndex(20).size()); assertEquals(1, hashes.get());
    }

    @Test public void sameSecondSameLengthModificationInvalidatesEveryPublicReadPathImmediately() throws Exception {
        Path root = tmp.getRoot().toPath(), source = root.resolve("source.txt");
        Files.write(source, "AAAA".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() / 1000 * 1000));
        ProjectMemoryStore store = new ProjectMemoryStore(root);
        ProjectMemoryStore.Details details = new ProjectMemoryStore.Details(); details.sourcePaths = Collections.singletonList("source.txt");
        ProjectMemoryStore.Entry entry = verified(store, "报表使用已核验配置", details);
        MemoryRecallQuery query = MemoryRecallQuery.from("报表配置", "核验配置", null);
        assertEquals(1, store.recall(query, 3).size()); assertEquals(1, store.verifiedIndex(3).size());
        FileTime time = Files.getLastModifiedTime(source);
        Files.write(source, "BBBB".getBytes(StandardCharsets.UTF_8)); Files.setLastModifiedTime(source, time);
        assertTrue(store.recall(query, 3).isEmpty()); assertTrue(store.search("报表", 3).isEmpty());
        assertTrue(store.verifiedIndex(3).isEmpty()); assertFalse(store.read(entry.id).verified);
        assertEquals("changed", store.list().get(0).sourceState);
        assertTrue("disk verification evidence remains inspectable", new String(Files.readAllBytes(root.resolve(
                ".minion/memory/entries/" + entry.id + ".md")), StandardCharsets.UTF_8).contains("\"verified\":true"));
    }

    @Test public void sharedSourceDigestIsComparedSeparatelyAgainstEachMemoryBaseline() throws Exception {
        Path root = tmp.getRoot().toPath(), source = root.resolve("source.txt");
        Files.write(source, "AAAA".getBytes(StandardCharsets.UTF_8));
        AtomicInteger hashes = new AtomicInteger();
        ProjectMemoryStore store = new ProjectMemoryStore(root, path -> {
            hashes.incrementAndGet(); return SourceFingerprintBatch.SHA256.sha256(path);
        });
        ProjectMemoryStore.Details d = new ProjectMemoryStore.Details(); d.sourcePaths = Collections.singletonList("source.txt");
        ProjectMemoryStore.Entry old = verified(store, "来源规则采用旧值", d);
        FileTime time = Files.getLastModifiedTime(source);
        Files.write(source, "BBBB".getBytes(StandardCharsets.UTF_8)); Files.setLastModifiedTime(source, time);
        ProjectMemoryStore.Entry current = verified(store, "来源规则采用新值", d);
        hashes.set(0);
        List<ProjectMemoryStore.Entry> hits = store.recall(MemoryRecallQuery.from("来源规则"), 20);
        assertEquals(1, hits.size()); assertEquals(current.id, hits.get(0).id); assertEquals(1, hashes.get());
        assertFalse(store.read(old.id).verified);
    }

    @Test public void currentTaskRevisionAndPathFilterBeforeTheResultLimitAndProjectsStayIsolated() throws Exception {
        Path root = tmp.getRoot().toPath(); Files.createDirectories(root.resolve("data"));
        ProjectMemoryStore store = new ProjectMemoryStore(root);
        verified(store, "报表先核验总数", null);
        ProjectMemoryStore.Details details = new ProjectMemoryStore.Details(); details.conditions = new ProjectMemoryStore.Conditions();
        details.conditions.taskId = "task-a"; details.conditions.revision = 2L; details.conditions.pathPrefix = "data";
        ProjectMemoryStore.Entry current = verified(store, "报表改为核验明细", details);
        MemoryRecallQuery query = MemoryRecallQuery.from("报表改为核验明细");
        ProjectMemoryStore.Scope scope = new ProjectMemoryStore.Scope(); scope.taskId = "task-a"; scope.revision = 2; scope.path = "data/report.csv";
        assertEquals(current.id, store.recall(query, 1, scope).get(0).id);
        scope.revision = 1; assertTrue(store.recall(query, 1, scope).isEmpty());
        scope.revision = 2; scope.taskId = "task-b"; assertTrue(store.recall(query, 1, scope).isEmpty());
        scope.taskId = "task-a"; scope.path = "elsewhere/report.csv"; assertTrue(store.recall(query, 1, scope).isEmpty());
        ProjectMemoryStore other = new ProjectMemoryStore(tmp.newFolder("other").toPath());
        assertTrue(other.recall(query, 20).isEmpty()); assertTrue(ProjectMemoryStore.global(root).recall(query, 20).isEmpty());
    }

    @Test public void addingRevisionDoesNotInvalidateExistingConditionVerificationHashes() throws Exception {
        Path root = tmp.getRoot().toPath(); ProjectMemoryStore store = new ProjectMemoryStore(root);
        ProjectMemoryStore.Details d = new ProjectMemoryStore.Details(); d.conditions = new ProjectMemoryStore.Conditions();
        d.conditions.taskId = "task-a"; d.conditions.pathPrefix = "data";
        ProjectMemoryStore.Entry entry = verified(store, "原有报表审查规则", d);
        String oldDetails = "{\"supersedes\":[],\"conflicts\":[],\"aliases\":[],\"sourcePaths\":[],\"conditions\":{"
                + "\"taskId\":\"task-a\",\"pathPrefix\":\"data\",\"validFrom\":0,\"validUntil\":0}}";
        String oldHash = digest(oldDetails + "\n" + entry.type + "\n" + entry.text + "\n" + entry.source
                + "\n" + entry.project + "\n[]");
        assertEquals(oldHash, entry.verificationHash);
        ProjectMemoryStore.Scope scope = new ProjectMemoryStore.Scope(); scope.taskId = "task-a"; scope.path = "data/report.csv";
        assertEquals(entry.id, store.recall(MemoryRecallQuery.from("报表审查"), 3, scope).get(0).id);
    }

    @Test public void sourcePathsCannotEscapeTheProjectAndFailuresNeverFallBackToCachedResults() throws Exception {
        Path root = tmp.newFolder("project").toPath(), outside = tmp.newFolder("outside").toPath().resolve("source.txt");
        Files.write(outside, "evidence".getBytes(StandardCharsets.UTF_8));
        ProjectMemoryStore store = new ProjectMemoryStore(root);
        ProjectMemoryStore.Details details = new ProjectMemoryStore.Details(); details.sourcePaths = Collections.singletonList(outside.toString());
        try { store.remember("decision", "不能跨项目读取来源", "人工核验", details); fail("project boundary"); }
        catch (IllegalArgumentException expected) { }
        Path inside = root.resolve("source.txt"); Files.write(inside, "evidence".getBytes(StandardCharsets.UTF_8));
        details.sourcePaths = Collections.singletonList("source.txt"); verified(store, "来源缺失不得注入", details);
        MemoryRecallQuery query = MemoryRecallQuery.from("来源缺失"); assertEquals(1, store.recall(query, 3).size());
        Files.delete(inside); assertTrue(store.recall(query, 3).isEmpty());
    }

    private static ProjectMemoryStore.Entry verified(ProjectMemoryStore store, String text, ProjectMemoryStore.Details details) throws Exception {
        ProjectMemoryStore.Entry entry = store.remember("decision", text, "用户明确核验", details);
        return store.setVerified(entry.id, true);
    }

    private static String digest(String text) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
}
