package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.dev.ValidationRunner;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

/** 仅接收实际Build工具回执，不能用模型正文伪造测试通过；结束时再次绑定源码和报告版本。 */
public final class TaskValidation {
    private TaskValidation() { }
    public static JsonObject report(String tool,String output) {
        if(!"Build".equals(tool)||output==null)return null;
        try {JsonObject raw=JsonParser.parseString(output).getAsJsonObject();
            if(!raw.has("type")||!ValidationRunner.TYPE.equals(raw.get("type").getAsString()))return null;
            JsonObject result=new JsonObject();
            for(String key:new String[]{"type","kind","status","runId","scope","sourceDigest","sourceDigestAfter","sourcePolicy","sourceUnchanged","reportPath","logPath","logDigest","tests","failures","errors","skipped","exitCode"})
                if(raw.has(key))result.add(key,raw.get(key).deepCopy());
            return result;
        }catch(RuntimeException invalid){return null;}
    }
    public static boolean current(TaskState.Evidence evidence,Predicate<Path> allowed) {
        try {
            if(evidence.validation==null||evidence.validationFile==null)return false;
            JsonObject r=evidence.validation;Path root=Paths.get(r.getAsJsonObject("scope").get("root").getAsString()).toAbsolutePath().normalize();
            Path report=Paths.get(r.get("reportPath").getAsString()).toAbsolutePath().normalize();
            if(!allowed.test(root)||!allowed.test(report)||!report.toString().equals(evidence.validationFile.path)
                    ||!"unchanged".equals(evidence.validationFile.verify()))return false;
            return ValidationRunner.verifyReport(root,report);
        }catch(Exception invalid){return false;}
    }
    public static boolean passed(TaskState.Evidence evidence,String kind,Predicate<Path> allowed) {
        return passedWith(evidence,kind,e->current(e,allowed));
    }
    private static boolean passedWith(TaskState.Evidence evidence,String kind,Predicate<TaskState.Evidence> isCurrent) {
        if(!evidence.success||evidence.validation==null)return false;
        try {JsonObject r=evidence.validation;if(!kind.equals(r.get("kind").getAsString()))return false;
            String status=r.get("status").getAsString();
            if(!"passed".equals(status)&&!("command".equals(kind)&&"command_succeeded".equals(status)))return false;
            return isCurrent.test(evidence);
        }catch(RuntimeException invalid){return false;}
    }
    public static JsonArray summaries(TaskState state,Predicate<Path> allowed) {
        return new Probe(allowed).summaries(state);
    }

    /** A fresh probe at every stop boundary, shared by reports and workflow blockers. */
    static final class Probe {
        private final Predicate<Path> allowed;
        private final Map<TaskState.Evidence,Boolean> checked=new IdentityHashMap<TaskState.Evidence,Boolean>();
        Probe(Predicate<Path> allowed){this.allowed=allowed;}
        boolean current(TaskState.Evidence evidence){
            return checked.computeIfAbsent(evidence,e->e.success&&TaskValidation.current(e,allowed));
        }
        boolean passed(TaskState.Evidence evidence,String kind){return TaskValidation.passedWith(evidence,kind,this::current);}
        JsonArray summaries(TaskState state) {
            JsonArray out=new JsonArray();Set<String> seen=new HashSet<String>();
            List<TaskState.Evidence> latest=new ArrayList<TaskState.Evidence>();
            synchronized(state) {
                for(int i=state.evidence.size()-1;i>=0&&latest.size()<8;i--) {
                    TaskState.Evidence e=state.evidence.get(i);
                    if(!Objects.equals(state.id,e.taskId)||e.validation==null)continue;
                    String key=String.valueOf(e.validation.get("scope"))+"/"+e.validation.get("kind");
                    if(seen.add(key))latest.add(e);
                }
            }
            for(TaskState.Evidence e:latest) {
                JsonObject r=e.validation.deepCopy();r.addProperty("receiptId",e.id);
                if(e.revision!=null)r.addProperty("instructionRevision",e.revision);
                r.addProperty("currentSourceAndReport",current(e));out.add(r);
            }
            return out;
        }
    }
}
