package com.minion.core.agent;

import com.minion.core.llm.Message;
import java.util.*;

/** Pure local state transitions; callers fence session identity and perform I/O/UI outside locks. */
final class SteeringTracker {
    static final class Request {
        final String sessionId, taskId, id=UUID.randomUUID().toString();
        final long epoch;
        final List<String> ids;
        boolean feedback;
        Request(String sessionId,String taskId,long epoch,List<String> ids) {
            this.sessionId=sessionId;this.taskId=taskId;this.epoch=epoch;
            this.ids=Collections.unmodifiableList(new ArrayList<String>(ids));
        }
    }

    static SteeringReceipt create(Session s,String id,String text,String taskId,Long revision) {
        synchronized(s) {
            List<SteeringReceipt> next=s.steeringSnapshot();
            for(SteeringReceipt old:next)if(Objects.equals(id,old.id))return old.copy();
            SteeringReceipt r=new SteeringReceipt();r.id=id;r.text=excerpt(text);r.state="unread";
            r.taskId=taskId;r.revision=revision;r.createdAt=r.updatedAt=System.currentTimeMillis();
            next.add(r);while(next.size()>100)next.remove(0);s.steeringReceipts=next;return r.copy();
        }
    }

    static SteeringReceipt inserted(Session s,Message m) {
        synchronized(s) {
            List<SteeringReceipt> next=s.steeringSnapshot();
            for(SteeringReceipt r:next)if(Objects.equals(r.id,m.steeringId)) {
                if(r.isRead())return null;
                r.eventId=m.eventId;r.taskId=m.taskId;r.revision=m.taskRevision;
                r.state="inserted";touch(r);s.steeringReceipts=next;return r.copy();
            }
            return null;
        }
    }

    static Request snapshot(Session s,List<Message> actual,long epoch) {
        LinkedHashSet<String> ids=new LinkedHashSet<String>();
        for(Message m:actual)if(m!=null&&m.steeringId!=null&&steeringMessage(m)
                && Objects.equals(s.taskState.id,m.taskId))ids.add(m.steeringId);
        return new Request(s.id,s.taskState.id,epoch,new ArrayList<String>(ids));
    }

    static List<SteeringReceipt> sending(Session s,Request request) {
        return transition(s,request,"sending");
    }
    static List<SteeringReceipt> feedback(Session s,Request request) {
        request.feedback=true;return transition(s,request,"read");
    }
    static List<SteeringReceipt> failed(Session s,Request request) {
        return request.feedback?Collections.<SteeringReceipt>emptyList():transition(s,request,"unconfirmed");
    }
    private static List<SteeringReceipt> transition(Session s,Request request,String state) {
        synchronized(s) {
            List<SteeringReceipt> next=s.steeringSnapshot(),changed=new ArrayList<SteeringReceipt>();
            for(SteeringReceipt r:next) {
                if(r.isRead()||!request.ids.contains(r.id)||!Objects.equals(request.taskId,r.taskId))continue;
                if(!"sending".equals(state)&&!Objects.equals(request.id,r.requestId))continue;
                if(state.equals(r.state)&&Objects.equals(request.id,r.requestId))continue;
                r.state=state;r.requestId=request.id;touch(r);
                if("read".equals(state))r.readAt=r.updatedAt;
                changed.add(r.copy());
            }
            if(!changed.isEmpty())s.steeringReceipts=next;
            return changed;
        }
    }

    /** Only materialize the nullable parallel queue when an actual enqueue/drain needs it. */
    static void alignPending(Session s) {
        if(s.pendingSteeringIds==null)s.pendingSteeringIds=new ArrayList<String>();
        while(s.pendingSteeringIds.size()<s.pendingSupplements.size())s.pendingSteeringIds.add(null);
        while(s.pendingSteeringIds.size()>s.pendingSupplements.size())s.pendingSteeringIds.remove(s.pendingSteeringIds.size()-1);
        while(s.pendingSupplementImages.size()<s.pendingSupplements.size())s.pendingSupplementImages.add(new ArrayList<com.minion.core.llm.ImagePart>());
    }

    static List<SteeringReceipt> recover(Session s) {
        List<SteeringReceipt> changed=new ArrayList<SteeringReceipt>();
        synchronized(s) {
            // Merge first: create()/inserted() trim the live hot list and would evict
            // authoritative read receipts before their historical messages are visited.
            Map<String,SteeringReceipt> merged=new LinkedHashMap<String,SteeringReceipt>();
            if(s.steeringReceipts!=null)for(SteeringReceipt r:s.steeringReceipts) {
                if(r==null||r.id==null)continue;
                SteeringReceipt previous=merged.get(r.id);
                if(previous==null||r.isRead()&&!previous.isRead()
                        ||r.isRead()==previous.isRead()&&r.updatedAt>previous.updatedAt)merged.put(r.id,r.copy());
            }
            Map<String,Long> times=new HashMap<String,Long>();
            Map<String,Integer> order=new HashMap<String,Integer>();
            Set<String> updated=new HashSet<String>();
            long priorTime=0;int position=0;
            // Journal may contain the inserted real user message newer than the JSON checkpoint.
            for(Message m:s.messages)if(m.steeringId!=null&&steeringMessage(m)) {
                SteeringReceipt existing=merged.get(m.steeringId);
                long time=existing!=null&&existing.createdAt>0?existing.createdAt:m.ts>0?m.ts:
                        existing!=null&&existing.updatedAt>0?existing.updatedAt:priorTime;
                priorTime=Math.max(priorTime,time);
                if(!order.containsKey(m.steeringId)) {
                    times.put(m.steeringId,time);order.put(m.steeringId,position++);
                }
                if(existing==null) {
                    existing=new SteeringReceipt();existing.id=m.steeringId;existing.text=excerpt(m.content);
                    existing.state="inserted";existing.eventId=m.eventId;existing.taskId=m.taskId;existing.revision=m.taskRevision;
                    // Zero-time legacy messages use historical order for retention, never recovery wall time.
                    existing.createdAt=existing.updatedAt=Math.max(0,m.ts);
                    merged.put(existing.id,existing);updated.add(existing.id);
                }
                if("unread".equals(existing.state)) {
                    existing.state="inserted";
                    if(existing.eventId==null)existing.eventId=m.eventId;
                    if(existing.taskId==null)existing.taskId=m.taskId;
                    if(existing.revision==null)existing.revision=m.taskRevision;
                    updated.add(existing.id);
                }
            }
            for(SteeringReceipt r:merged.values()) {
                if(!order.containsKey(r.id)) {
                    times.put(r.id,r.createdAt>0?r.createdAt:Math.max(0,r.updatedAt));order.put(r.id,position++);
                }
                // Recovery changes delivery certainty, not the original receipt's timestamps.
                if("sending".equals(r.state)){r.state="unconfirmed";updated.add(r.id);}
            }
            List<SteeringReceipt> next=new ArrayList<SteeringReceipt>(merged.values());
            next.sort((a,b)->{
                int time=Long.compare(times.get(a.id),times.get(b.id));
                return time!=0?time:Integer.compare(order.get(a.id),order.get(b.id));
            });
            if(next.size()>100)next=new ArrayList<SteeringReceipt>(next.subList(next.size()-100,next.size()));
            if(s.steeringReceipts!=null||!next.isEmpty())s.steeringReceipts=next;
            // Do not emit newly reconstructed orphans that did not survive the one final cut.
            for(SteeringReceipt r:next)if(updated.contains(r.id))changed.add(r.copy());
        }
        return changed;
    }

    /** Keep actual user originals/IDs, not a synthesized summary claiming delivery. */
    static List<Message> protect(Session s,List<Message> before,List<Message> after) {
        Set<String> preserve=new HashSet<String>();
        for(SteeringReceipt r:s.steeringSnapshot())if(!r.isRead()&&Objects.equals(s.taskState.id,r.taskId))preserve.add(r.id);
        for(int i=before.size()-1;i>=0;i--) {
            Message m=before.get(i);
            if(m.steeringId!=null&&Objects.equals(s.taskState.id,m.taskId)){preserve.add(m.steeringId);break;}
        }
        Set<String> present=new HashSet<String>();for(Message m:after)if(m.steeringId!=null)present.add(m.steeringId);
        List<Message> result=new ArrayList<Message>(after);int at=0;
        while(at<result.size()&&(result.get(at).summary||result.get(at).pinned||result.get(at).role==Message.Role.SYSTEM))at++;
        for(Message m:before)if(m.steeringId!=null&&Objects.equals(s.taskState.id,m.taskId)
                &&preserve.contains(m.steeringId)&&present.add(m.steeringId))result.add(at++,m);
        return result;
    }
    private static void touch(SteeringReceipt r) { r.updatedAt=Math.max(System.currentTimeMillis(),r.updatedAt+1); }
    private static boolean steeringMessage(Message m) {
        return m.isUserInstruction() || m.role==Message.Role.USER&&m.userAuthored&&!m.summary&&!m.pinned&&!m.toolImage
                &&m.images!=null&&!m.images.isEmpty();
    }
    private static String excerpt(String s) {
        if(s==null)return "";
        String note="\n[显示节选，原文保留]";
        return s.length()<=2000?s:s.substring(0,2000-note.length())+note;
    }
}
