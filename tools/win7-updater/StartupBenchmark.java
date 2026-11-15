import com.google.gson.Gson;
import com.minion.core.agent.Session;
import com.minion.core.config.*;
import com.minion.core.llm.*;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.session.SessionManager;
import java.nio.file.*;
import java.util.*;

/** 离线启动基准：不联网、不触碰真实用户会话。两版使用同一组合成数据。 */
public class StartupBenchmark {
    private static int clients;
    private static class Manager extends SessionManager {
        Manager(Path dir) throws Exception {
            super(message -> ConfirmUi.Decision.REJECT, Config.load(dir), dir,
                    WorkspaceManager.load(dir), ModelManager.load(dir), new ArrayList<Skill>(), null, null);
        }
        @Override public LlmClient newLlm(ModelConfig config) {
            clients++;
            return new LlmClient() {
                public void streamChat(List<Message> m, List<com.google.gson.JsonObject> t, StreamHandler h) { throw new UnsupportedOperationException(); }
                public String completeChat(List<Message> m, String s) { throw new UnsupportedOperationException(); }
            };
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[1]).toAbsolutePath();
        if ("create".equals(args[0])) {
            Files.createDirectories(root);
            Config.load(root); ModelManager.load(root); WorkspaceManager ws=WorkspaceManager.load(root);
            Path dir=WorkspaceManager.sessionDirFor(root,ws.currentName()); Files.createDirectories(dir);
            StringBuilder payload=new StringBuilder(); for(int i=0;i<180;i++)payload.append("合成性能测试内容abcdefgh，非用户数据。");
            long events=0, bytes=0;
            for(int i=0;i<20;i++) {
                Session s=Session.create(root.toString(),"deepseek-v4-flash"); s.title="合成长会话 "+i;
                // 第一版索引缺失的冷启动也须衡量；直接写旧版快照和不可变事件。
                SessionStore store=new SessionStore(dir);
                for(int j=0;j<120;j++)s.messages.add(j%2==0?Message.user("要求 "+j):Message.assistant(payload.toString()));
                store.save(s); events+=s.journalSequence; bytes+=Files.size(dir.resolve(s.id+".json"));
            }
            Files.deleteIfExists(dir.resolve(".session-index"));
            System.out.println("FIXTURE sessions=20 messages=2400 events="+events+" snapshotBytes="+bytes); return;
        }
        long start=System.nanoTime(); Manager m=new Manager(root);
        long elapsed=System.nanoTime()-start;
        System.out.println("STARTUP ms="+elapsed/1000000+" clients="+clients+" visible="+m.sessions().size());
        System.out.println("HEAP usedBytes="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
        long open=System.nanoTime(); m.activateSession(m.sessions().get(0));
        System.out.println("OPEN_ONE ms="+(System.nanoTime()-open)/1000000+" clients="+clients);
        m.shutdown();
    }
}
