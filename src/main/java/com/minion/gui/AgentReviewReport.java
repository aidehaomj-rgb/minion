package com.minion.gui;

import com.minion.core.agent.TaskState;
import java.util.*;

/** Bounded pure projection; never copies/serializes the entire result or evidence ledger. */
public final class AgentReviewReport {
    public static final int MAX_CHILDREN=64, MAX_RECEIPTS=16, MAX_BYTES=128*1024;
    private static final String FOOTER="\n\n[展示限制] 最多64子任务、每项最近16回执、总128KiB UTF-8；正文为节选。省略/截断、缺失或未展示的证据绝不构成完成证明。完整内容请查上方原始记录路径及会话任务账本；本页不核验、不更新验收。";
    private AgentReviewReport() {}
    public static String render(TaskState state,String root) {
        synchronized(state) {
            Bounded out=new Bounded(MAX_BYTES-FOOTER.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            out.add("只读快照；不是验收。执行根: ");out.field(root,8192);
            out.add("\n当前 task=");out.field(state.id,256);out.add(" revision="+state.revision());
            // Read references under the existing state lock, not childSnapshot's unbounded deep copies.
            List<TaskState.Child> chosen=new ArrayList<TaskState.Child>();int total=0;
            if(state.children!=null)for(TaskState.Child c:state.children)if(c!=null&&Objects.equals(state.id,c.taskId))total++;
            if(state.children!=null)for(int i=state.children.size()-1;i>=0&&chosen.size()<MAX_CHILDREN;i--){
                TaskState.Child c=state.children.get(i);if(c!=null&&Objects.equals(state.id,c.taskId))chosen.add(c);
            }
            out.add("\n当前任务子任务总数="+total+"；展示最近登记="+chosen.size()+"；省略="+(total-chosen.size()));
            Map<String,List<TaskState.Evidence>> receipts=new LinkedHashMap<String,List<TaskState.Evidence>>();
            Map<String,Integer> counts=new HashMap<String,Integer>();
            for(TaskState.Child c:chosen){receipts.put(c.id,new ArrayList<TaskState.Evidence>());counts.put(c.id,0);}
            if(state.evidence!=null)for(int i=state.evidence.size()-1;i>=0;i--){TaskState.Evidence e=state.evidence.get(i);
                if(e==null||!Objects.equals(state.id,e.taskId)||!receipts.containsKey(e.scope))continue;
                counts.put(e.scope,counts.get(e.scope)+1);List<TaskState.Evidence> list=receipts.get(e.scope);if(list.size()<MAX_RECEIPTS)list.add(e);
            }
            // Paths precede all large bodies so oversized results cannot hide later evidence references.
            for(TaskState.Child c:chosen){
                out.add("\n\nAgent ");out.field(c.id,256);out.add(" attempt="+(c.coordinatedAttempt==null?"unknown":c.coordinatedAttempt)+" revision="+c.revision);
                out.add("\n执行根: ");out.field(c.isolatedOutput?c.outputDir:root,8192);
                out.add("\n原始证据记录路径: ");out.field(c.transcript,8192);
                out.add("\n范围冲突原因: "+(c.revision!=null&&c.revision==state.revision()?"当前任务修订匹配；不代表无产物冲突":"旧修订或缺失修订；不得复用验收"));
                out.add("\n状态（非本页认证）: ");out.field(c.status,128);
                List<TaskState.Evidence> list=receipts.get(c.id);
                out.add("\n回执：展示最近="+list.size()+"；省略="+(counts.get(c.id)-list.size()));
                for(TaskState.Evidence e:list){out.add("\n回执ID: ");out.field(e.id,256);out.add(" revision="+e.revision);
                    out.add(" 原始文件: ");out.field(e.file==null?"未登记（请查子任务记录）":e.file.path,8192);
                    if(e.validationFile!=null){out.add(" 核验文件: ");out.field(e.validationFile.path,8192);}
                }
            }
            for(TaskState.Child c:chosen){
                out.add("\n\nAgent正文节选 ");out.field(c.id,256);
                out.add("\n停止/冲突原因（节选；缺失不等于通过）: ");out.field(c.stopDecision==null?"未登记":c.stopDecision.reason,2048);
                out.add("\n返回内容（节选，未经本页核验）: ");out.field(c.result,2048);
                for(TaskState.Evidence e:receipts.get(c.id)){out.add("\n回执正文ID: ");out.field(e.id,256);out.add(" tool=");out.field(e.tool,128);
                    out.add(" success="+e.success+"（历史记录，非完成证明） ");out.field(e.result,512);}
            }
            if(out.truncated)out.finish("\n[已截断：字段或总字节达到上限；部分路径也可能未展示，请回到原始会话任务账本查阅。]");
            return out.text.toString()+FOOTER;
        }
    }
    private static final class Bounded {
        final StringBuilder text=new StringBuilder();final int limit;int bytes;boolean truncated;
        Bounded(int limit){this.limit=limit-256;}
        void add(String s){field(s,Integer.MAX_VALUE);}
        void field(String raw,int maxChars){
            String s=raw==null?"unknown":raw;int end=Math.min(s.length(),maxChars),i=0;
            while(i<end){int cp=s.codePointAt(i),chars=Character.charCount(cp);if(i+chars>end)break;
                int size=cp<=127?1:cp<=2047?2:cp<=65535?3:4;
                if(bytes+size>limit)break;text.appendCodePoint(cp);bytes+=size;i+=chars;
            }
            if(i<s.length()){truncated=true;String marker="[截断]";if(bytes+marker.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=limit){text.append(marker);bytes+=marker.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}}
        }
        void finish(String notice){text.append(notice);}
    }
}
