package com.minion.core.context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ProjectMemoryStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void typedProjectMemoryIsSearchableAndDeletable() throws Exception {
        ProjectMemoryStore store = new ProjectMemoryStore(temp.getRoot().toPath());
        ProjectMemoryStore.Entry entry = store.remember("feedback",
                "压缩后必须保持最新用户指令优先", "用户明确确认");
        Path markdown = temp.getRoot().toPath().resolve(".minion/memory/entries/" + entry.id + ".md");
        assertTrue(Files.isRegularFile(markdown));
        assertTrue(new String(Files.readAllBytes(markdown), StandardCharsets.UTF_8).contains("## 内容"));
        assertFalse(entry.verified);
        assertTrue(store.search("最新用户指令", 5).isEmpty());
        store.setVerified(entry.id, true);
        assertEquals(1, store.search("最新用户指令", 5).size());
        assertEquals(entry.id, store.read(entry.id).id);
        assertEquals(1, store.list().size());
        assertEquals(entry.id, store.remember("feedback", entry.text, "再次提出").id);
        store.edit(entry.id, "压缩后必须保留最新用户指令与证据来源", "用户再次确认");
        assertTrue(store.search("证据来源", 5).isEmpty());
        store.setVerified(entry.id, true);
        assertEquals(1, store.search("证据来源", 5).size());
        store.forget(entry.id);
        assertTrue(store.search("最新用户指令", 5).isEmpty());
    }

    @Test public void memoryIsScopedToProjectAndDecisionsRequireVerification() throws Exception {
        ProjectMemoryStore first = new ProjectMemoryStore(temp.newFolder("first").toPath());
        ProjectMemoryStore second = new ProjectMemoryStore(temp.newFolder("second").toPath());
        ProjectMemoryStore.Entry decision = first.remember("decision", "仅使用内网数据源", "用户确认");
        assertTrue(second.list().isEmpty());
        assertTrue(first.search("内网数据源", 5).isEmpty());
        first.setVerified(decision.id, true);
        assertEquals(1, first.search("内网数据源", 5).size());
        assertTrue(second.search("内网数据源", 5).isEmpty());
        first.setVerified(decision.id, false);
        assertTrue(first.search("内网数据源", 5).isEmpty());
    }

    @Test public void memoryRequiresTypeAndSource() throws Exception {
        ProjectMemoryStore store = new ProjectMemoryStore(temp.getRoot().toPath());
        try { store.remember("fact", "随意结论", "模型猜测"); fail("type"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("类型")); }
        try { store.remember("project", "无来源结论", ""); fail("source"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("来源")); }
    }

    @Test public void markdownBodyCanBeEditedAndOldJsonRemainsReadable() throws Exception {
        ProjectMemoryStore store = new ProjectMemoryStore(temp.getRoot().toPath());
        ProjectMemoryStore.Entry entry = store.remember("project", "旧项目约定", "用户确认");
        store.setVerified(entry.id, true);
        Path markdown = temp.getRoot().toPath().resolve(".minion/memory/entries/" + entry.id + ".md");
        String body = new String(Files.readAllBytes(markdown), StandardCharsets.UTF_8);
        Files.write(markdown, body.replace("旧项目约定", "新项目约定").getBytes(StandardCharsets.UTF_8));
        assertEquals("新项目约定", store.read(entry.id).text);
        assertFalse("手工修改已核验正文后需重新核验", store.read(entry.id).verified);
        String legacyId = java.util.UUID.randomUUID().toString();
        Path legacy = markdown.resolveSibling(legacyId + ".json");
        Files.write(legacy, ("{\"id\":\"" + legacyId + "\",\"type\":\"user\",\"text\":\"旧版记忆\","
                + "\"source\":\"旧版文件\",\"verified\":false}").getBytes(StandardCharsets.UTF_8));
        assertEquals("旧版记忆", store.read(legacyId).text);
        assertEquals(2, store.list().size());
    }
    @Test public void optInExtractionOnlyProposesDurableUserPreferenceAndNeedsReview() throws Exception {
        ProjectMemoryStore first = new ProjectMemoryStore(temp.getRoot().toPath());
        assertNull(first.proposeFromUser("以后都先核对文件再修改", "session-a"));
        first.setAutoExtractEnabled(true);
        assertTrue(new ProjectMemoryStore(temp.getRoot().toPath()).autoExtractEnabled());
        assertNull(first.proposeFromUser("分析这份 Excel", "session-a"));
        assertNull(first.proposeFromUser("记住我的密码是secret123", "session-a"));
        ProjectMemoryStore.Entry candidate=first.proposeFromUser("以后都先核对文件再修改", "session-a");
        assertNotNull(candidate);assertFalse(candidate.verified);
        assertTrue(first.search("先核对文件",5).isEmpty());
        first.setVerified(candidate.id,true);
        assertEquals(1,new ProjectMemoryStore(temp.getRoot().toPath()).verifiedIndex(6).size());
        assertEquals(1,first.search("先核对文件",5).size());
        first.setAutoExtractEnabled(false);
        assertNull(first.proposeFromUser("以后都要检查数据", "session-b"));
    }

    @Test public void globalPreferencesAreSeparateFromProjectsAndCannotStoreProjectFacts() throws Exception {
        Path app=temp.newFolder("app-root").toPath();
        ProjectMemoryStore one=new ProjectMemoryStore(temp.newFolder("one").toPath());
        ProjectMemoryStore two=new ProjectMemoryStore(temp.newFolder("two").toPath());
        ProjectMemoryStore global=ProjectMemoryStore.global(app);
        ProjectMemoryStore.Entry privateFact=one.remember("project","内网数据库地址只用于项目一","用户确认");
        one.setVerified(privateFact.id,true);
        assertTrue(two.search("数据库地址",5).isEmpty());
        assertTrue(global.search("数据库地址",5).isEmpty());
        try {global.remember("project","内网数据库地址只用于项目一","用户确认");fail("project leak");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("跨项目"));}
        ProjectMemoryStore.Entry preference=global.remember("feedback","不要修改无关项目文件","用户显式共享");
        assertTrue(global.search("无关项目",5).isEmpty());
        global.setVerified(preference.id,true);
        assertEquals(1,ProjectMemoryStore.global(app).search("无关项目",5).size());
        assertTrue(two.list().isEmpty());
    }
}
