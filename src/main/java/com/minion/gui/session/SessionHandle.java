package com.minion.gui.session;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.llm.LlmClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 会话句柄：GUI 层持有的会话视图状态（后台运行实体为 AgentLoop） */
public class SessionHandle {
    public volatile String externalJob;
    /** 最近生成的任务报告，供主窗口关闭后再次打开。 */
    public volatile String lastArtifactPath;
    /** User's preview selection is distinct from the latest produced artifact. UI-only. */
    public volatile String selectedArtifactPath;
    public volatile boolean suppressArtifactAutoOpen;

    public final String id;
    /** 所属工作空间名：工作空间重命名时同步更新（非 final，否则 activate/send/persist 按旧名查 ctx 全部失效） */
    public String workspaceName;
    public volatile Session session;
    /** 未打开的历史会话不创建运行时。 */
    public volatile AgentLoop loop;
    public boolean isLoaded() { return loop != null; }
    public String listPreview, listSummary;
    public long listMessageTs;
    public String preview() { return isLoaded() ? session.preview() : listPreview == null ? "(空会话)" : listPreview; }
    public final SessionController controller;
    /** 会话独占工作线程（真并行：每会话一个单线程池，互不阻塞） */
    public final ExecutorService pool;
    /** 会话独享的 LLM 客户端（换模型时换新实例；删除/退出时 close 释放 okhttp 资源） */
    public volatile LlmClient llm; // 由 final 改 volatile：模型热更新允许换实例

    /** 已退役（换模型替换下来）的客户端：close 会 cancel 运行中请求，运行中不能立即关，登记待回收 */
    private final List<LlmClient> retiredLlms = new ArrayList<LlmClient>();

    /** 登记待回收客户端 */
    public synchronized void retireLlm(LlmClient old) {
        if (old != null && old != llm) retiredLlms.add(old);
    }

    /** 关闭全部客户端（当前 + 待回收）：会话删除/工作空间删除/应用退出时调用 */
    public synchronized void closeAll() {
        if (llm != null) llm.close();
        for (LlmClient c : retiredLlms) c.close();
        retiredLlms.clear();
    }

    /** 会话空闲（running→false）时回收换模型遗留的旧客户端 */
    public synchronized void closeRetired() {
        for (LlmClient c : retiredLlms) c.close();
        retiredLlms.clear();
    }

    /** 展示标题（新建会话由 LLM 摘要生成；恢复会话来自落盘） */
    public volatile String title;
    /** 新建会话尚未生成标题（发送时先摘要 → 再跑任务） */
    public volatile boolean titlePending;
    /** 是否正在后台运行（UI 徽标/终止按钮依据） */
    public volatile boolean running;
    /** Settings changed during a task; refresh the parent client only after it becomes idle. */
    public volatile boolean pendingModelRefresh;
    private long dispatchGeneration;
    public synchronized long beginDispatch() { running=true; return ++dispatchGeneration; }
    public synchronized boolean finishDispatch(long generation) {
        if(generation!=dispatchGeneration) return false;
        running=false; return true;
    }
    /** 最近一次运行已结束、用户尚未点击查看；仅为本次进程 UI 状态，不落盘。 */
    public volatile boolean completionUnread;
    /** 内部保留暂停语义；V41 后回合结束提示统一为绿色。 */
    public volatile boolean pauseUnread;

    public enum ActivityState { NONE, RUNNING, COMPLETED_UNREAD, PAUSED_UNREAD }

    /** 左侧状态点三态（纯逻辑，便于无 JavaFX 测试）。 */
    public ActivityState activityState() {
        if (running) return ActivityState.RUNNING;
        if(completionUnread)return ActivityState.COMPLETED_UNREAD;
        return pauseUnread?ActivityState.PAUSED_UNREAD:ActivityState.NONE;
    }
    /** 会话已删除（send 中据此中止，防已删除会话的文件/事件复活） */
    public volatile boolean deleted;
    /** AskUserQuestion 挂起中（输入框进入回答模式；回答/中断/回合结束复位） */
    public volatile boolean askPending;
    /** AskUserQuestion 问题文本（回答模式占位提示用） */
    public volatile String askQuestion;

    /** 显式后台任务队列（/queue add）；与 Steering 的“立即补充当前轮”语义分开。 */
    private List<Session.QueuedTask> queue() {
        // Gson旧数据/重复ID迁移；每个条目的稳定身份独立于序号、时间和文字。
        synchronized(session.queuedTasks){java.util.Set<String> ids=new java.util.HashSet<String>();
        for(Session.QueuedTask t:session.queuedTasks) {
            if(t.id==null||t.id.trim().isEmpty()||!ids.add(t.id)){t.id=java.util.UUID.randomUUID().toString();ids.add(t.id);}
            if(t.revision<1)t.revision=1;
        }}
        return session.queuedTasks;
    }

    public synchronized int enqueue(String text) {
        return enqueue(text, null, false);
    }

    public synchronized int enqueue(String text, List<com.minion.core.llm.ImagePart> images, boolean guide) {
        return enqueue(text, images, guide, "later");
    }

    /**
     * §2.3 三档入队：next=插队首（当前工具结束/下一轮优先处理）；later=队尾（本轮结束后，
     * 既有语义）；now 的「中断在途并发送」由 SessionManager.sendPrioritized 承担
     * （中断+队首），入队侧与 next 同位。
     */
    public synchronized int enqueue(String text, List<com.minion.core.llm.ImagePart> images,
                                    boolean guide, String priority) {
        synchronized (queue()) {
            if (text == null || text.trim().isEmpty()) return queue().size();
            Session.QueuedTask task = new Session.QueuedTask();
            task.text=text.trim(); task.createdAt=System.currentTimeMillis(); task.guidance=guide;
            task.priority = "next".equals(priority) || "now".equals(priority) ? priority : "later";
            if(images!=null) task.images.addAll(images);
            if ("later".equals(task.priority)) queue().add(task);
            else queue().add(0, task);
            return queue().size();
        }
    }

    public synchronized QueuedMessage pollQueued() {
        synchronized (queue()) { return queue().isEmpty() ? null : new QueuedMessage(queue().remove(0)); }
    }

    public synchronized List<QueuedMessage> queuedSnapshot() {
        synchronized (queue()) {
            List<QueuedMessage> copy = new ArrayList<QueuedMessage>();
            for(Session.QueuedTask task:queue()) copy.add(new QueuedMessage(task));
            return Collections.unmodifiableList(copy);
        }
    }

    public synchronized QueuedMessage pendingGuide() {
        for(QueuedMessage task:queuedSnapshot()) if(task.guidance) return task;
        return null;
    }

    public synchronized QueuedMessage takeGuide() {
        synchronized (queue()) {
            for(int i=0;i<queue().size();i++) if(queue().get(i).guidance) return new QueuedMessage(queue().remove(i));
            return null;
        }
    }

    public synchronized boolean removeQueued(int oneBasedIndex) {
        int i = oneBasedIndex - 1;
        synchronized (queue()) {
            if (i < 0 || i >= queue().size()) return false;
            queue().remove(i); return true;
        }
    }

    public synchronized int clearQueued() {
        synchronized (queue()) { int count=queue().size(); queue().clear(); return count; }
    }
    public synchronized boolean editQueued(String id,long expectedRevision,String text) {
        if(text==null||text.trim().isEmpty())throw new IllegalArgumentException("引导信息不能为空");
        if(text.length()>120000)throw new IllegalArgumentException("引导信息超过120000字符，请分开处理");
        synchronized(queue()) {
            for(Session.QueuedTask t:queue())if(t.id.equals(id)) {
                if(t.revision!=expectedRevision)return false;
                t.text=text.trim();t.revision++;return true;
            }
        }return false;
    }
    public synchronized void rollbackQueuedEdit(String id,long savedRevision,String oldText){
        synchronized(queue()){for(Session.QueuedTask t:queue())if(t.id.equals(id)&&t.revision==savedRevision){t.text=oldText;t.revision=savedRevision-1;return;}}
        throw new IllegalStateException("队列条目在保存期间改变，无法撤回");
    }
    public synchronized boolean removeQueued(String id,long expectedRevision) {
        synchronized(queue()){
            for(int i=0;i<queue().size();i++){Session.QueuedTask t=queue().get(i);if(t.id.equals(id)&&t.revision==expectedRevision){queue().remove(i);return true;}}
        }return false;
    }

    public static final class QueuedMessage {
        public final String id;
        public final long revision;
        public final String text;
        public final long createdAt;
        public final boolean guidance;
        public final List<com.minion.core.llm.ImagePart> images;
        QueuedMessage(Session.QueuedTask t) {
            id=t.id;revision=t.revision;
            text=t.text; createdAt=t.createdAt; guidance=t.guidance;
            images=t.images==null ? Collections.emptyList() : new ArrayList<com.minion.core.llm.ImagePart>(t.images);
        }
    }

    public SessionHandle(String id, String workspaceName, Session session,
                         AgentLoop loop, SessionController controller, String title,
                         boolean titlePending, LlmClient llm) {
        this.id = id;
        this.workspaceName = workspaceName;
        this.session = session;
        if(session.queuedTasks==null) session.queuedTasks=new ArrayList<Session.QueuedTask>();
        this.loop = loop;
        this.controller = controller;
        this.title = title;
        this.titlePending = titlePending;
        this.llm = llm;
        String idPrefix = id.length() > 8 ? id.substring(0, 8) : id;
        this.pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "minion-session-" + workspaceName + "-" + idPrefix);
            t.setDaemon(true);
            return t;
        });
    }
}
