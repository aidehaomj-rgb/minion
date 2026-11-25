package com.minion.core.agent;

import com.minion.Main;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.storage.SessionStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** F5：Main.artifactCleanupGuard 枚举会话登记表 → 被引用产物（含历史快照）豁免清理。 */
public class ArtifactCleanupGuardTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private GeneratedArtifact artifact(Session s, Path file) throws Exception {
        GeneratedArtifact a = new GeneratedArtifact();
        a.path = file.toString();
        a.kind = GeneratedArtifact.DELIVERABLE;
        a.title = file.getFileName().toString();
        a.taskId = s.taskState.id;
        a.revision = 1L;
        a.updatedAt = System.currentTimeMillis();
        a.size = Files.size(file);
        return a;
    }

    /** 登记产物路径 + 快照路径 + 历史快照路径均豁免；未登记文件不豁免 */
    @Test public void registeredPathsAndSnapshotsAreExempt() throws Exception {
        Path jarDir = temp.newFolder("jar").toPath(), workDir = temp.newFolder("ws").toPath();
        WorkspaceManager wm = WorkspaceManager.load(jarDir);
        Session s = Session.create(workDir.toString(), "fixture");
        Path deliverable = workDir.resolve("报告.docx");
        Files.write(deliverable, new byte[]{1});
        s.registerArtifact(artifact(s, deliverable));
        Files.write(deliverable, new byte[]{1, 2});
        GeneratedArtifact second = artifact(s, deliverable);
        second.updatedAt += 10;
        s.registerArtifact(second);
        new SessionStore(WorkspaceManager.sessionDirFor(jarDir, "default")).save(s);

        Predicate<Path> keep = Main.artifactCleanupGuard(jarDir, wm);
        assertTrue("登记产物路径豁免", keep.test(deliverable));
        GeneratedArtifact registered = s.artifactsSnapshot().get(0);
        assertNotNull(registered.snapshotPath);
        assertTrue("当前快照豁免", keep.test(Paths.get(registered.snapshotPath)));
        assertFalse(registered.history.isEmpty());
        assertTrue("历史快照豁免", keep.test(Paths.get(registered.history.get(0).snapshotPath)));
        assertFalse("未登记文件不豁免", keep.test(workDir.resolve("无关.txt")));
    }

    /** 无会话（新装默认工作空间）：任意 tmp 文件不豁免，清理照常 */
    @Test public void noSessionsKeepsNothing() throws Exception {
        Path jarDir = temp.newFolder("jar").toPath();
        WorkspaceManager wm = WorkspaceManager.load(jarDir);
        Predicate<Path> keep = Main.artifactCleanupGuard(jarDir, wm);
        assertFalse(keep.test(jarDir.resolve(".session").resolve("tmp").resolve("s1").resolve("old.txt")));
    }
}