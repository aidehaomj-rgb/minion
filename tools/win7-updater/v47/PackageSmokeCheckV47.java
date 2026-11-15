import com.minion.core.agent.*;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.Message;
import com.minion.core.llm.PhaseTimings;
import java.nio.file.*;

/** Runs only on a patched fixture JAR; no model/database/network calls. */
public final class PackageSmokeCheckV47 {
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        check(ContextManager.DEFAULT_SOFT_TRIGGER_TOKENS==150000,"150K target");
        Message user=Message.realUser("current user instruction");
        check(user.hasUserAuthorization(),"real user authorization");
        Message control=Message.taskControl("not a new user instruction");
        control.userAuthored=true;
        check(!control.hasUserAuthorization(),"typed control cannot become user authorization");
        ContinuationBudget budget=new ContinuationBudget();
        budget.bindLedger(new ProgressReceiptLedger(root,"package-session","package-task"));
        budget.configure(96,1000);
        for(int i=0;i<650;i++)budget.observe("Read","path="+i,true,"content="+i,null);
        check(budget.evidenceVersion==650&&budget.seen.size()<=512,"bounded hot cache and durable history");
        budget.observe("Read","path=0",true,"content=0",null);
        budget.observe("DbMysql","{\"action\":\"status\"}",true,"new timestamp",null);
        check(budget.evidenceVersion==650,"old evidence and polling cannot refresh progress");
        budget.usage(1000);check(!budget.permits(40),"task token budget pauses");
        for(String name:new String[]{"com.minion.core.agent.RuntimeJobRegistry","com.minion.core.context.WorkingSetProjection",
                "com.minion.core.context.SkillResidency","com.minion.core.tools.db.DbTool","com.minion.core.agent.ToolBatchScheduler"})Class.forName(name);
        check(new PhaseTimings().snapshot().getAsJsonObject("EXTERNAL_WAIT").get("elapsedMs").getAsLong()==-1,"unknown timing not zero");
        System.out.println("PASS V47 patched JAR: Java8 linkage, 150K, typed origins, 650 exact receipts, polling exclusion, finite budget, runtime-job classes");
    }
}
