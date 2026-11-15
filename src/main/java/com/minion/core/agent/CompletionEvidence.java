package com.minion.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.tools.dev.SourceVersion;

import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/** Only use live receipts and current, version-bound host facts for the fast stop path. */
final class CompletionEvidence {
    private static final Pattern INCOMPLETE = Pattern.compile(
            "(?is)(?:尚未|还没|仍未|尚待|待完成|未完成|无法完成|未能|目前只完成|仅完成|还(?:需|需要|要)继续|等(?:待)?(?:用户|您|你)(?:确认|授权|提供)|"
            +"not (?:yet )?(?:done|complete|finished)|still (?:pending|need to)|could not (?:complete|finish))");

    private CompletionEvidence() { }

    static boolean canBeFinal(String candidate) {
        String body=StopGate.visibleCandidate(candidate);
        if(body.isEmpty()||ReplyHeuristics.looksLikeInterimAnswer(body)||ReplyHeuristics.looksLikeUserBlock(body)
                ||ReplyHeuristics.hasConcreteHumanDependency(body,""))return false;
        // An advisory note about verification limits can go through semantic review; it is
        // not safe to distinguish that from a real missing requirement using a regex alone.
        return !INCOMPLETE.matcher(body).find();
    }

    static Set<String> readyWorkflowReceipts(TaskState state) {
        TaskWorkflow workflow=state.workflow;
        if(workflow==null||workflow.steps.isEmpty()||!Objects.equals(workflow.taskId,state.id)
                ||workflow.revision!=state.revision())return Collections.emptySet();
        Map<String,TaskState.Evidence> evidence=new HashMap<String,TaskState.Evidence>();
        for(TaskState.Evidence e:state.evidence)
            if(e.success&&"root".equals(e.scope)&&Objects.equals(state.id,e.taskId)
                    &&Objects.equals(state.revision(),e.revision))evidence.put(e.id,e);
        Set<String> refs=new LinkedHashSet<String>();
        for(TaskWorkflow.Step step:workflow.steps) {
            if(!"completed".equals(step.status)||step.receipts.isEmpty())return Collections.emptySet();
            for(String ref:step.receipts) {
                TaskState.Evidence e=evidence.get(ref);
                // These must be execution receipts, not plan/status control messages.
                if(e==null||Arrays.asList("AgentControl","TodoWrite","Context","Skill","AskUserQuestion","DiscoverTools","task")
                        .contains(e.tool))return Collections.emptySet();
                refs.add(ref);
            }
        }
        return refs;
    }

    static List<String> deliveredProof(String candidate,long revision,boolean hasCheckpoint,boolean currentFinalCheckpoint,
                                       JsonArray checkpoints,JsonArray outputs,JsonArray validationReports,
                                       Set<String> workflowReceipts,List<StopGate.Receipt> receipts,
                                       JsonArray acceptance,boolean requiresMutation,Set<String> currentReadPaths,
                                       boolean allowReadProof) {
        if(!canBeFinal(candidate))return Collections.emptyList();
        // Unsuccessful later operations may be material; don't take the fast path based on earlier success.
        // A failure may still be resolved semantically by a replacement operation.
        Map<String,StopGate.Receipt> checked=new HashMap<String,StopGate.Receipt>();
        String mutation=null;
        String mutationTool=null;
        JsonObject mutationArgs=null;
        boolean failed=false;
        for(StopGate.Receipt r:receipts) {
            if(!Objects.equals(r.revision,revision))continue;
            if(!r.ok) {
                // Failures before any successful deliverable mutation are superseded by the
                // later successful write. A failure after the current mutation remains material.
                if(mutation!=null){failed=true;checked.clear();mutation=null;mutationTool=null;mutationArgs=null;}
                continue;
            }
            try {
                JsonObject args=JsonParser.parseString(r.args).getAsJsonObject();
                if(!Arrays.asList("Build","ArtifactVerification","Process","Permission","ExternalJob").contains(r.tool)
                        &&!ProgressEvidencePolicy.controlOnly(r.tool,r.args)&&StopGate.mutationEvidence(r.tool,args)) {
                    mutation=r.ref;
                    mutationTool=r.tool;
                    mutationArgs=args;
                    failed=false;checked.clear();
                }
                if(!"ArtifactVerification".equals(r.tool))continue;
                String action=string(args,"action");
                if(!Arrays.asList("docx","xlsx").contains(action)||!args.has("officeChecks")
                        ||args.getAsJsonArray("officeChecks").size()==0)continue;
                JsonObject result=JsonParser.parseString(r.output).getAsJsonObject(),facts=result.getAsJsonObject("facts");
                if(!"passed".equals(string(result,"status"))||!"passed".equals(string(facts.getAsJsonObject("office"),"status")))continue;
                checked.put(key(facts,"sha256"),r);
            } catch(RuntimeException ignored) { /* No parseable, version-bound assertion receipt. */ }
        }
        boolean marked=ReplyHeuristics.hasTaskCompleteMarker(candidate);
        // A simple Write/Edit task may finish without an explicit checkpoint. Accept only
        // the current revision's successful mutation, its live hash-checked output, and a
        // final reply that names that exact file. This avoids recovery loops after real writes.
        if(!failed&&mutation!=null&&mutationArgs!=null&&!hasCheckpoint&&marked
                &&Arrays.asList("Write","Edit").contains(mutationTool)) {
            String target=mutationTarget(mutationArgs);
            if(!target.isEmpty())for(JsonElement item:outputs) {
                JsonObject file=item.getAsJsonObject();
                String path=string(file,"path");
                if(targetMatches(path,target)&&StopGate.unchanged(file)&&namedIn(candidate,path))
                    return Collections.singletonList(mutation);
            }
        }
        if(!failed&&currentFinalCheckpoint&&mutation!=null&&checkpoints.size()>0&&marked) {
            List<String> proof=new ArrayList<String>();proof.add(mutation);
            Set<String> coveredPaths=new HashSet<String>();
            Set<String> actualOutputs=new HashSet<String>();
            for(JsonElement item:outputs)actualOutputs.add(string(item.getAsJsonObject(),"path"));
            boolean allCovered=true;
            for(JsonElement item:checkpoints) {
                JsonObject file=item.getAsJsonObject();
                StopGate.Receipt verified=checked.get(key(file,"recordedSha256"));
                int rule=hashRule(acceptance,file);
                String path=string(file,"path");
                boolean reread=allowReadProof&&currentReadPaths!=null&&currentReadPaths.contains(path);
                String report=verified==null&&rule<0?reportCovering(validationReports,revision,Collections.singletonList(file),false,
                        workflowReceipts):null;
                if(!actualOutputs.contains(path)||!StopGate.unchanged(file)||!namedIn(candidate,path)
                        ||(!reread&&verified==null&&rule<0&&report==null)) {
                    allCovered=false;break;
                }
                coveredPaths.add(path);
                String ref=reread?"read:"+path:verified!=null?verified.ref:rule>=0?"acceptance:sha256:"+rule:report;
                if(!proof.contains(ref))proof.add(ref);
            }
            if(!coveredPaths.containsAll(actualOutputs))allCovered=false;
            if(allCovered)return proof;
        }
        if(!failed&&checkpoints.size()==0&&!workflowReceipts.isEmpty()&&marked&&(!requiresMutation||mutation!=null)) {
            List<JsonObject> files=new ArrayList<JsonObject>();
            for(JsonElement item:outputs)files.add(item.getAsJsonObject());
            String report=reportCovering(validationReports,revision,files,true,workflowReceipts);
            if(report!=null) return requiresMutation?Arrays.asList(mutation,report):Collections.singletonList(report);
        }
        return Collections.emptyList();
    }

    /** A command counts only for a workflow step that accepted its actual receipt, never as a test pass. */
    private static String reportCovering(JsonArray reports,long revision,List<JsonObject> files,boolean testOnly,Set<String> workflowRefs) {
        for(JsonElement item:reports)try {
            JsonObject report=item.getAsJsonObject();String kind=string(report,"kind"),status=string(report,"status"),id=string(report,"receiptId");
            boolean passed="test".equals(kind)&&"passed".equals(status);
            if(!passed&&(testOnly||!"command".equals(kind)||!"command_succeeded".equals(status)||!workflowRefs.contains(id)))continue;
            if(id.isEmpty()||!workflowRefs.contains(id)||!report.get("currentSourceAndReport").getAsBoolean()
                    ||report.get("instructionRevision").getAsLong()!=revision)continue;
            java.nio.file.Path root=Paths.get(string(report.getAsJsonObject("scope"),"root"));
            if(files.stream().allMatch(f->StopGate.unchanged(f)&&SourceVersion.covers(root,Paths.get(string(f,"path")))))
                return "validation:"+id;
        } catch(RuntimeException invalid) { /* Legacy or malformed report is not fast-path evidence. */ }
        return null;
    }

    private static int hashRule(JsonArray acceptance,JsonObject file) {
        for(int i=0;i<acceptance.size();i++) {
            JsonObject result=acceptance.get(i).getAsJsonObject();
            if("passed".equals(string(result,"status"))&&"file_hash".equals(string(result.getAsJsonObject("rule"),"type"))
                    &&key(result,"actualSha256").equals(key(file,"recordedSha256")))return i;
        }
        return -1;
    }

    private static String key(JsonObject file,String hashKey) {
        return Paths.get(string(file,"path")).toAbsolutePath().normalize()+"\n"+string(file,hashKey);
    }

    private static String mutationTarget(JsonObject args) {
        for(String key:new String[]{"path","outputPath"}) {
            String value=string(args,key);
            if(!value.isEmpty())return value;
        }
        return "";
    }

    private static boolean targetMatches(String actual,String requested) {
        String output=actual.replace('\\','/'),target=requested.replace('\\','/');
        while(target.startsWith("./"))target=target.substring(2);
        return output.equals(target)||output.endsWith("/"+target);
    }

    private static boolean namedIn(String reply,String path) {
        String name=Paths.get(path).getFileName().toString();
        if(name.isEmpty())return false;
        String needle=name.toLowerCase(Locale.ROOT),raw=reply==null?"":reply;
        if(raw.toLowerCase(Locale.ROOT).contains(needle))return true;
        // Markdown links use percent-encoded file URIs; the visible label may omit extension.
        try {
            String decoded=java.net.URLDecoder.decode(raw.replace("+","%2B"),"UTF-8");
            return decoded.toLowerCase(Locale.ROOT).contains(needle);
        } catch(Exception invalid){return false;}
    }

    private static String string(JsonObject o,String key) {
        JsonElement value=o==null?null:o.get(key);
        return value!=null&&value.isJsonPrimitive()?value.getAsString():"";
    }
}
