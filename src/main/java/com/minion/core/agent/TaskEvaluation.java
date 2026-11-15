package com.minion.core.agent;

import com.google.gson.*;
import com.minion.core.tools.AtomicFiles;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** Observable workflow metrics for the current task; business correctness stays unknown until user review. */
public final class TaskEvaluation {
    private static final Gson G=new GsonBuilder().setPrettyPrinting().create();
    private static Path reviewPath(ReliabilityService reliability,String taskId)throws IOException{
        if(taskId==null||!taskId.matches("[A-Za-z0-9-]+"))throw new IOException("当前任务ID不可用于复核记录");
        Path path=reliability.root().resolve("reviews").resolve(taskId+".json").normalize();
        if(!path.startsWith(reliability.root())||!com.minion.core.tools.PathsGuard.inside(reliability.workspace().workDir(),path))throw new IOException("复核路径越界");
        return path;
    }
    private static int count(JsonObject item,String key){
        if(!item.has(key)||!item.get(key).isJsonPrimitive())throw new IllegalArgumentException("缺少复核数字: "+key);
        int n=item.get(key).getAsInt();if(n<0||n>1000000)throw new IllegalArgumentException("复核数字越界: "+key);return n;
    }
    /** Human-entered business review, never inferred from the model's completion text. */
    public static JsonObject recordReview(ReliabilityService reliability,TaskState state,String expectedTaskId,String json)throws IOException {
        JsonObject raw=JsonParser.parseString(json).getAsJsonObject();
        int expected=count(raw,"expectedItems"),omitted=count(raw,"omittedItems"),duplicated=count(raw,"duplicateItems"),incorrect=count(raw,"incorrectItems");
        if(omitted>expected||incorrect>expected)throw new IllegalArgumentException("漏处理/错误数不得超过预期总数");
        if(!raw.has("accepted")||!raw.get("accepted").isJsonPrimitive()||!raw.get("accepted").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("需明确accepted真假");
        String note=raw.has("note")&&!raw.get("note").isJsonNull()?raw.get("note").getAsString():"";
        if(note.length()>2000)throw new IllegalArgumentException("复核说明过长");
        String taskId;synchronized(state){taskId=state.id;}
        if(!Objects.equals(expectedTaskId,taskId))throw new IOException("当前任务已切换，复核记录未保存");
        JsonObject review=new JsonObject();review.addProperty("taskId",taskId);review.addProperty("reviewedAt",System.currentTimeMillis());
        review.addProperty("accepted",raw.get("accepted").getAsBoolean());review.addProperty("expectedItems",expected);
        for(String key:new String[]{"prematureStop","latestInstructionFollowed","sourceCitationFound","crossProjectLeak"})if(raw.has(key)) {
            if(!raw.get(key).isJsonPrimitive()||!raw.get(key).getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException(key+"须人工明确填写布尔值，未核验请省略");
            review.add(key,raw.get(key).deepCopy());
        }
        review.addProperty("omittedItems",omitted);review.addProperty("duplicateItems",duplicated);review.addProperty("incorrectItems",incorrect);review.addProperty("note",note);
        AtomicFiles.writeText(reviewPath(reliability,taskId),G.toJson(review),StandardCharsets.UTF_8);return review;
    }
    public static JsonObject current(Session session,ReliabilityService reliability)throws Exception {
        TaskState state=session.taskState;
        JsonObject result=new JsonObject();String task,status,goal;StopGate.Decision decision;
        long started,finished,active; synchronized(state){task=state.id;status=state.status;goal=state.objective;decision=state.stopDecision;
            started=state.startedAt;finished=state.lastFinishedAt;active=state.activeMillis+(state.turnStartedAt>0?Math.max(0,System.currentTimeMillis()-state.turnStartedAt):0);}
        result.addProperty("taskId",task);result.addProperty("status",status);result.addProperty("goal",goal);result.addProperty("time",System.currentTimeMillis());
        result.addProperty("startedAt",started);result.addProperty("lastFinishedAt",finished);result.addProperty("activeMillis",active);
        result.addProperty("executionPhase",state.executionPhase());
        if(state.observedPhaseTimings!=null)result.add("runtimePhases",state.observedPhaseTimings.deepCopy());
        synchronized(state) {
            if(state.taskInputBaseline!=null&&state.taskOutputBaseline!=null
                    &&session.usage.sessionInput()>=state.taskInputBaseline&&session.usage.sessionOutput()>=state.taskOutputBaseline) {
                result.addProperty("taskInputTokens",session.usage.sessionInput()-state.taskInputBaseline);
                result.addProperty("taskOutputTokens",session.usage.sessionOutput()-state.taskOutputBaseline);
                result.addProperty("taskTokenScope","current_task_parent_session_delta; child usage listed separately");
            } else result.addProperty("taskTokenScope","unknown_missing_or_reset_baseline");
            if(state.workflow!=null){result.addProperty("workflowRevision",state.workflow.revision);result.addProperty("workflowSteps",state.workflow.steps.size());}
        }
        JsonObject acceptance=reliability.evaluate(state,p->com.minion.core.tools.PathsGuard.inside(reliability.workspace().workDir(),p)||com.minion.core.tools.PathsGuard.insideExtra(reliability.workspace(),p));result.add("acceptance",acceptance);
        int children=0,reviewed=0,failed=0;for(TaskState.Child c:state.childSnapshot()){children++;if("reviewed".equals(c.status)||"validated".equals(c.status))reviewed++;if(Arrays.asList("failed_or_interrupted","failed","timed_out","interrupted_needs_review").contains(c.status))failed++;}
        result.addProperty("children",children);result.addProperty("reviewedChildren",reviewed);result.addProperty("failedChildren",failed);
        int reads=0,writes=0,repeats=0,uncertain=0;Set<String> unique=new HashSet<String>();
        for(ReliabilityService.Step step:reliability.steps())if(Objects.equals(task,step.taskId)){
            if(step.mutation)writes++;else reads++;if(!unique.add(step.tool+":"+step.argumentsHash))repeats++;
            if(Arrays.asList("started","uncertain").contains(step.state))uncertain++;
        }
        result.addProperty("readSteps",reads);result.addProperty("mutationSteps",writes);result.addProperty("duplicateToolArguments",repeats);result.addProperty("uncertainSteps",uncertain);
        result.addProperty("stopGate",decision==null?"not_checked":decision.status);result.addProperty("noProgress",decision==null?0:decision.noProgress);
        Path review=task==null?null:reviewPath(reliability,task);
        if(review!=null&&Files.isRegularFile(review))try{result.add("manualBusinessReview",JsonParser.parseString(new String(Files.readAllBytes(review),StandardCharsets.UTF_8)));}
        catch(Exception e){result.addProperty("manualBusinessReviewStatus","unreadable");}
        else result.addProperty("manualBusinessReviewStatus","not_reviewed");
        result.addProperty("businessAccuracy","not_computed_without_item_level_gold_labels");
        result.addProperty("falseCompletion","unknown_without_manual_review_and_acceptance_evidence");
        addManualMetrics(result);
        result.addProperty("sessionInputTokens",session.usage.sessionInput());result.addProperty("sessionOutputTokens",session.usage.sessionOutput());
        result.addProperty("tokenScope","session_not_task");
        return result;
    }

    /** 只计算人工已明确标注的指标；没有金标准不能用工具成功率代替业务准确率。 */
    public static void addManualMetrics(JsonObject report) {
        JsonObject metrics=new JsonObject();metrics.addProperty("source","manual_review_only");
        report.add("qualityMetrics",metrics);
        if(!report.has("manualBusinessReview")||!report.get("manualBusinessReview").isJsonObject()) {
            metrics.addProperty("status","not_reviewed");return;
        }
        try {
            JsonObject review=report.getAsJsonObject("manualBusinessReview");
            int expected=review.get("expectedItems").getAsInt(),omitted=review.get("omittedItems").getAsInt();
            int duplicates=review.get("duplicateItems").getAsInt(),incorrect=review.get("incorrectItems").getAsInt();
            if(expected<0||omitted<0||duplicates<0||incorrect<0||omitted>expected||incorrect>expected)throw new IllegalArgumentException();
            if(!review.has("accepted")||!review.get("accepted").getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException();
            boolean accepted=review.get("accepted").getAsBoolean();
            metrics.addProperty("status","reviewed");metrics.addProperty("accepted",accepted);
            for(String key:new String[]{"prematureStop","latestInstructionFollowed","sourceCitationFound","crossProjectLeak"})
                if(review.has(key)&&review.get(key).isJsonPrimitive()&&review.get(key).getAsJsonPrimitive().isBoolean())metrics.add(key,review.get(key).deepCopy());
                else metrics.addProperty(key+"Status","unknown_not_labelled");
            if(expected>0){metrics.addProperty("coverage",(expected-omitted)/(double)expected);
                metrics.addProperty("incorrectRate",incorrect/(double)expected);metrics.addProperty("duplicateRate",duplicates/(double)expected);}
            else metrics.addProperty("rates","unknown_zero_expected_items");
            metrics.addProperty("businessAccuracy","unknown_without_non_overlapping_item_level_gold_labels");
            if(report.has("status")&&"completed".equals(report.get("status").getAsString()))report.addProperty("falseCompletion",!accepted);
        } catch(RuntimeException invalid){metrics.addProperty("status","invalid_review");}
    }
}
