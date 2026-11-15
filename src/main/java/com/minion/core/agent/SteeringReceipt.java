package com.minion.core.agent;

/** Technical delivery receipt only: read does not mean understood, executed or accepted. */
public class SteeringReceipt {
    public String id, text, state, eventId, requestId, taskId;
    public Long revision;
    public long createdAt, updatedAt, readAt;

    public SteeringReceipt copy() {
        SteeringReceipt r=new SteeringReceipt();r.id=id;r.text=text;r.state=state;r.eventId=eventId;
        r.requestId=requestId;r.taskId=taskId;r.revision=revision;
        r.createdAt=createdAt;r.updatedAt=updatedAt;r.readAt=readAt;return r;
    }
    public boolean isRead() { return "read".equals(state); }
    public String displayState() {
        if("inserted".equals(state))return "已插入等待发送";
        if("sending".equals(state))return "发送中";
        if(isRead())return "已读";
        if("unconfirmed".equals(state))return "未确认待重试";
        return "未读";
    }
}
