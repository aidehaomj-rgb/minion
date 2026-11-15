package com.minion.core.context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class ProjectMemoryPrecisionTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private ProjectMemoryStore store() { return new ProjectMemoryStore(tmp.getRoot().toPath()); }
    private Path file(String name, String text) throws Exception {
        Path p=tmp.getRoot().toPath().resolve(name);Files.createDirectories(p.getParent());
        Files.write(p,text.getBytes(StandardCharsets.UTF_8));return p;
    }
    private ProjectMemoryStore.Entry verified(ProjectMemoryStore s,String text) throws Exception {
        ProjectMemoryStore.Entry e=s.remember("decision",text,"用户确认");s.setVerified(e.id,true);return e;
    }
    @Test public void sameSizeAndTimestampSourceChangesInvalidateBothActualInjectionPaths() throws Exception {
        Path p=file("资料/配置.txt","AAAA");ProjectMemoryStore s=store();
        ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.sourcePaths=Collections.singletonList("资料/配置.txt");
        ProjectMemoryStore.Entry e=s.remember("decision","默认服务使用甲环境","人工已读来源",d);s.setVerified(e.id,true);
        assertEquals(1,s.verifiedIndex(6).size());assertEquals(1,s.search("默认服务",5).size());
        FileTime time=Files.getLastModifiedTime(p);Files.write(p,"BBBB".getBytes(StandardCharsets.UTF_8));Files.setLastModifiedTime(p,time);
        assertTrue(s.verifiedIndex(6).isEmpty());assertTrue(s.search("默认服务",5).isEmpty());
        assertEquals("changed",s.read(e.id).sourceState);assertFalse(s.read(e.id).verified);
        assertTrue("原核验记录保留",new String(Files.readAllBytes(Paths.get(s.read(e.id).location)),StandardCharsets.UTF_8).contains("\"verified\":true"));
    }
    @Test public void exactLegacySourcePathIsBoundAtVerificationAndMissingSourceFailsClosed() throws Exception {
        Path p=file("规则.md","版本一");ProjectMemoryStore s=store();
        ProjectMemoryStore.Entry e=s.remember("decision","版本规则走甲方案","规则.md");s.setVerified(e.id,true);
        assertEquals(1,s.verifiedIndex(6).size());Files.delete(p);
        assertTrue(s.search("版本规则",5).isEmpty());assertEquals("changed",s.read(e.id).state);
        try{s.setVerified(e.id,true);fail("missing source");}catch(java.io.IOException expected){}
    }
    @Test public void untrackedLegacyVerifiedFileDecisionNeedsReviewRatherThanInventedBaseline() throws Exception {
        file("old.txt","证据");ProjectMemoryStore s=store();
        ProjectMemoryStore.Entry e=s.remember("decision","旧版规则待核对","old.txt");
        Path p=tmp.getRoot().toPath().resolve(".minion/memory/entries/"+e.id+".md");
        String md=new String(Files.readAllBytes(p),StandardCharsets.UTF_8).replace("\"verified\":false","\"verified\":true");
        Files.write(p,md.getBytes(StandardCharsets.UTF_8));
        assertEquals("needs_reverify",s.read(e.id).state);assertTrue(s.verifiedIndex(6).isEmpty());
    }
    @Test public void onlyReviewedSupersessionWinsAndDeletingReplacementDoesNotResurrectOldDecision() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Entry old=verified(s,"日志采用旧路径");
        ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.supersedes=Collections.singletonList(old.id);
        ProjectMemoryStore.Entry newer=s.remember("decision","日志采用新路径","用户更正",d);
        assertEquals(old.id,s.search("日志",5).get(0).id);s.setVerified(newer.id,true);
        assertEquals("superseded",s.read(old.id).state);assertEquals(newer.id,s.search("日志",5).get(0).id);
        s.forget(newer.id);assertTrue(s.search("日志",5).isEmpty());assertEquals("superseded",s.read(old.id).state);
    }
    @Test public void changedReplacementNeverReactivatesOldFileDependentDecision() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Entry old=verified(s,"部署使用旧环境");
        Path source=file("deploy.txt","甲环境");ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();
        d.supersedes=Collections.singletonList(old.id);d.sourcePaths=Collections.singletonList("deploy.txt");
        ProjectMemoryStore.Entry n=s.remember("decision","部署使用新环境","复核文件",d);s.setVerified(n.id,true);
        Files.write(source,"乙环境".getBytes(StandardCharsets.UTF_8));
        assertTrue(s.verifiedIndex(6).isEmpty());assertTrue(s.search("部署",5).isEmpty());
    }
    @Test public void conflictsAreSymmetricAndUnverifiedClaimsCannotSuppressReviewedMemory() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Entry a=verified(s,"构建采用甲方案");
        ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.conflicts=Collections.singletonList(a.id);
        ProjectMemoryStore.Entry b=s.remember("decision","构建采用乙方案","另一份证据",d);
        assertEquals(1,s.verifiedIndex(6).size());s.setVerified(b.id,true);
        assertEquals("conflict",s.read(a.id).state);assertEquals("conflict",s.read(b.id).state);
        assertTrue(s.verifiedIndex(6).isEmpty());assertTrue(s.search("构建",5).isEmpty());
        s.setVerified(b.id,false);assertEquals(a.id,s.search("构建",5).get(0).id);
    }
    @Test public void taskPathAndTimeConditionsFailClosedWithoutContext() throws Exception {
        file("模块 A/配置.txt","来源");ProjectMemoryStore s=store();ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();
        d.conditions=new ProjectMemoryStore.Conditions();d.conditions.taskId="task-a";d.conditions.pathPrefix="模块 A";
        d.conditions.validFrom=10;d.conditions.validUntil=100;d.sourcePaths=Collections.singletonList("模块 A/配置.txt");
        ProjectMemoryStore.Entry e=s.remember("decision","模块采用中文配置","人工确认",d);s.setVerified(e.id,true);
        assertTrue(s.verifiedIndex(6).isEmpty());assertTrue(s.search("中文配置",5).isEmpty());
        ProjectMemoryStore.Scope scope=new ProjectMemoryStore.Scope();scope.taskId="task-a";scope.path="模块 A/配置.txt";scope.at=50;
        assertEquals(e.id,s.verifiedIndex(6,scope).get(0).id);assertEquals(1,s.search("中文配置",5,scope).size());
        scope.path="模块 AB/配置.txt";assertTrue(s.verifiedIndex(6,scope).isEmpty());scope.path="模块 A/配置.txt";
        scope.taskId="task-b";assertTrue(s.search("中文配置",5,scope).isEmpty());scope.taskId="task-a";
        scope.at=100;assertTrue(s.verifiedIndex(6,scope).isEmpty());
    }
    @Test public void scopedSupersessionDoesNotLeakOrFallBackWhenScopeUnknown() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Entry old=verified(s,"语言采用英文回复");
        ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.supersedes=Collections.singletonList(old.id);
        d.conditions=new ProjectMemoryStore.Conditions();d.conditions.taskId="中文任务";
        ProjectMemoryStore.Entry n=s.remember("user","语言采用中文回复","用户限定",d);s.setVerified(n.id,true);
        assertTrue(s.verifiedIndex(6).isEmpty());
        ProjectMemoryStore.Scope scope=new ProjectMemoryStore.Scope();scope.taskId="其它任务";
        assertEquals(old.id,s.verifiedIndex(6,scope).get(0).id);scope.taskId="中文任务";
        assertEquals(n.id,s.verifiedIndex(6,scope).get(0).id);
    }
    @Test public void sourceTraversalAndGlobalFileBindingsAreRejected() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.sourcePaths=Collections.singletonList("../private.txt");
        try{s.remember("decision","不能读取其它项目","人工说明",d);fail("traversal");}catch(IllegalArgumentException expected){}
        d.sourcePaths=Collections.singletonList("anything.txt");
        try{ProjectMemoryStore.global(tmp.getRoot().toPath()).remember("user","我喜欢简洁回答","人工说明",d);fail("global source");}
        catch(IllegalArgumentException expected){}
        d.sourcePaths=null;d.aliases=Collections.singletonList("数据库密码是项目资料");
        try{ProjectMemoryStore.global(tmp.getRoot().toPath()).remember("user","我喜欢简洁回答","人工说明",d);fail("global alias");}
        catch(IllegalArgumentException expected){}
    }
    @Test public void editDisableEnableForgetPreserveAuditAndNeedFreshVerification() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Entry e=verified(s,"输出使用中文标题");
        s.setDisabled(e.id,true);assertEquals("disabled",s.read(e.id).state);assertTrue(s.verifiedIndex(6).isEmpty());
        try{s.setVerified(e.id,true);fail("disabled");}catch(IllegalArgumentException expected){}
        s.setDisabled(e.id,false);assertFalse(s.read(e.id).verified);s.setVerified(e.id,true);
        s.edit(e.id,"输出使用简短中文标题","用户纠正");assertFalse(s.read(e.id).verified);
        Path revisions=tmp.getRoot().toPath().resolve(".minion/memory/revisions/"+e.id);
        try(java.util.stream.Stream<Path> files=Files.list(revisions)){assertTrue(files.count()>0);}
        s.forget(e.id);assertTrue(s.list().isEmpty());assertTrue(Files.isDirectory(tmp.getRoot().toPath().resolve(".minion/memory/trash")));
    }
    @Test public void aliasSearchIncrementalEditsAndBodyLocationAreReal() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.aliases=Arrays.asList("营收报表","Revenue Report");
        ProjectMemoryStore.Entry e=s.remember("project","销售统计必须校验合计","用户确认",d);s.setVerified(e.id,true);
        ProjectMemoryStore.Entry hit=s.search("营收报表",5).get(0);assertEquals(e.id,hit.id);
        assertTrue(Paths.get(hit.location).isAbsolute());assertTrue(Files.readAllLines(Paths.get(hit.location),StandardCharsets.UTF_8).get(hit.line-1).contains(hit.text));
        s.edit(e.id,"销售统计改为校验明细","用户更正");assertTrue(s.search("营收报表",5).isEmpty());
        s.setVerified(e.id,true);s.rebuildIndex();assertEquals(1,new ProjectMemoryStore(tmp.getRoot().toPath()).search("营收报表",5).size());
    }
    @Test public void duplicateTextAndTimeFilterAreAppliedBeforeLimit() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Entry a=verified(s,"报表先校验合计");
        ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();
        ProjectMemoryStore.Entry b=s.remember("project",a.text,"另一来源",d);s.setVerified(b.id,true);
        assertEquals(1,s.search("报表",10).size());assertEquals(1,s.verifiedIndex(10).size());
        ProjectMemoryStore.Scope scope=new ProjectMemoryStore.Scope();scope.createdAfter=System.currentTimeMillis()+1000;
        assertTrue(s.search("报表",10,scope).isEmpty());
    }
    @Test public void metadataChangesAndCyclicSupersessionRequireReview() throws Exception {
        ProjectMemoryStore s=store();ProjectMemoryStore.Entry a=verified(s,"测试运行第一方案");
        ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.supersedes=Collections.singletonList(a.id);
        ProjectMemoryStore.Entry b=s.remember("decision","测试运行第二方案","用户更正",d);
        d.supersedes=Collections.singletonList(b.id);
        try{s.setDetails(a.id,d);fail("cycle");}catch(IllegalArgumentException expected){}
        Path p=Paths.get(s.read(a.id).location);String md=new String(Files.readAllBytes(p),StandardCharsets.UTF_8);
        Files.write(p,md.replace("\"source\":\"用户确认\"","\"source\":\"伪造说明\"").getBytes(StandardCharsets.UTF_8));
        assertFalse(s.read(a.id).verified);
    }
    @Test public void projectAndGlobalIsolationAndAutomaticCandidatesRemainUnverified() throws Exception {
        ProjectMemoryStore one=store();verified(one,"私有项目审计规则");
        ProjectMemoryStore two=new ProjectMemoryStore(tmp.newFolder("其它项目").toPath());
        assertTrue(two.search("审计规则",5).isEmpty());assertTrue(ProjectMemoryStore.global(tmp.getRoot().toPath()).list().isEmpty());
        one.setAutoExtractEnabled(true);assertNull(one.proposeFromUser("刚才测试通过了，任务完成","s1"));
        ProjectMemoryStore.Entry e=one.proposeFromUser("以后每次先核对来源文件","s1");assertNotNull(e);assertFalse(one.read(e.id).verified);
        one.setAutoExtractEnabled(false);assertNull(one.proposeFromUser("以后每次先核对来源文件","s2"));
    }
    @Test public void concurrentStoreInstancesDoNotLoseConfirmedRetirements() throws Exception {
        final ProjectMemoryStore s=store();ProjectMemoryStore.Entry old=verified(s,"旧版任务采用通用规则");
        final List<String> ids=new ArrayList<String>();
        for(int n=0;n<8;n++){
            ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.supersedes=Collections.singletonList(old.id);
            d.conditions=new ProjectMemoryStore.Conditions();d.conditions.taskId="task-"+n;
            ids.add(s.remember("decision","新版任务采用专用规则"+n,"用户逐项确认",d).id);
        }
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try{
            List<java.util.concurrent.Future<?>> futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(final String id:ids)futures.add(pool.submit(new java.util.concurrent.Callable<Void>(){
                public Void call()throws Exception{new ProjectMemoryStore(tmp.getRoot().toPath()).setVerified(id,true);return null;}
            }));
            for(java.util.concurrent.Future<?> f:futures)f.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertEquals(8,s.read(old.id).retiredBy.size());
        for(String id:ids)s.forget(id);
        for(int n=0;n<8;n++){
            ProjectMemoryStore.Scope scope=new ProjectMemoryStore.Scope();scope.taskId="task-"+n;
            assertTrue(s.verifiedIndex(20,scope).isEmpty());
        }
    }
    @Test public void globalReplacementIsStillPendingUntilManuallyReviewed() throws Exception {
        ProjectMemoryStore g=ProjectMemoryStore.global(tmp.getRoot().toPath());
        ProjectMemoryStore.Entry a=g.remember("user","我喜欢详细解释","用户要求");g.setVerified(a.id,true);
        ProjectMemoryStore.Details d=new ProjectMemoryStore.Details();d.supersedes=Collections.singletonList(a.id);
        ProjectMemoryStore.Entry b=g.remember("user","我喜欢简短解释","用户更正",d);
        assertEquals(a.id,g.verifiedIndex(6).get(0).id);g.setVerified(b.id,true);
        assertEquals(b.id,g.verifiedIndex(6).get(0).id);g.setDisabled(b.id,true);
        assertTrue(g.verifiedIndex(6).isEmpty());
    }
}
