package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.*;

/** One workspace mutation at a time, plus stale-read rejection for direct file writes. */
public final class ToolExecutionGuard {
    // Shared knowledge directories can cross project roots. A process-wide lock
    // conservatively serializes mutations, while model requests and reads stay parallel.
    private static final ReentrantReadWriteLock FILE_LOCK=new ReentrantReadWriteLock(true);
    private final Workspace workspace;
    private final boolean child;
    private final List<String> extraRoots=new ArrayList<String>();
    public void allowTrackingRoot(String root) { if(root!=null&&!root.isEmpty()) extraRoots.add(root); }
    private boolean trackable(Path path) {
        if(PathsGuard.inside(workspace.workDir(),path)||PathsGuard.insideExtra(workspace,path)) return true;
        for(String root:extraRoots) if(PathsGuard.inside(root,path)) return true;
        return false;
    }
    private final Map<String,String> observed=new HashMap<String,String>();
    public ToolExecutionGuard(Workspace workspace,boolean child) { this.workspace=workspace; this.child=child; }
    private ReentrantReadWriteLock lock() {
        return FILE_LOCK;
    }
    private static boolean control(String name) {
        return Arrays.asList("task","AgentControl","TodoWrite","AskUserQuestion","Skill","Context").contains(name);
    }
    private static boolean readOnly(String name,JsonObject args) {
        if(Arrays.asList("Read","Glob","Grep").contains(name)) return true;
        return "Knowledge".equals(name)&&args.has("action")&&Arrays.asList("search","list","read").contains(args.get("action").getAsString());
    }
    public ToolResult execute(Tool tool,JsonObject args) throws Exception {
        if(control(tool.name())) return tool.execute(args); // Never hold workspace locks while waiting for a child/user.
        Lock held=readOnly(tool.name(),args)?lock().readLock():lock().writeLock(); held.lockInterruptibly();
        try {
            Path path=null; String key=null;
            if(Arrays.asList("Read","Write","Edit").contains(tool.name())&&args.has("path")) {
                path=PathsGuard.resolve(workspace.cwd().toString(),args.get("path").getAsString()).toAbsolutePath().normalize();
                if(Files.exists(path)) path=path.toRealPath(); key=path.toString().toLowerCase(Locale.ROOT);
                // Do not inspect an external file before the tool's own permission gate.
                if(!trackable(path)) { path=null; key=null; }
            }
            if(path!=null&&!"Read".equals(tool.name())) {
                String current=FileEvidence.digest(path), seen;
                synchronized(observed) { seen=observed.get(key); }
                if(seen!=null&&!seen.equals(current)) return ToolResult.error("并行文件冲突：文件在上次Read之后已改变，未执行写入。请重新Read并合并修改: "+path);
                if(child&&Files.exists(path)&&seen==null) return ToolResult.error("子Agent修改已有文件必须先Read，避免覆盖其他Agent结果: "+path);
            }
            ToolResult result=tool.execute(args);
            if(result.ok&&path!=null) synchronized(observed) { observed.put(key,FileEvidence.digest(path)); }
            return result;
        } finally { held.unlock(); }
    }
}
