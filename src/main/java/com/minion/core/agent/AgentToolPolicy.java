package com.minion.core.agent;

import com.google.gson.JsonObject;
import java.util.*;

/** Fail closed for exploration. Shell/Python/MCP are never guessed to be read-only. */
public final class AgentToolPolicy {
    private final boolean readOnly;
    private final Set<String> allowed;
    private final String isolatedOutput;
    private static final Set<String> BLOCKED = new HashSet<String>(Arrays.asList(
            "task","context","memory","agentcontrol","askuserquestion","skill","permission","secrets","update","discovertools"));
    public AgentToolPolicy(boolean readOnly, String tools) {
        this(readOnly,tools,null);
    }
    public AgentToolPolicy(boolean readOnly,String tools,String isolatedOutput) {
        this.readOnly=readOnly;
        this.isolatedOutput=isolatedOutput;
        allowed=new HashSet<String>();
        if(tools!=null) for(String t:tools.split("[,，\\s]+")) if(!t.isEmpty()) allowed.add(t.toLowerCase(Locale.ROOT));
    }
    public boolean readOnly() { return readOnly; }
    public String isolatedOutput(){return isolatedOutput;}
    public boolean visible(String name) {
        String n=name.toLowerCase(Locale.ROOT);
        if(BLOCKED.contains(n)) return false;
        if(isolatedOutput!=null&&!Arrays.asList("read","viewimage","glob","grep","write","edit","document","excel","artwork","knowledge","todowrite","artifactverification").contains(n))return false;
        if(!allowed.isEmpty()&&!allowed.contains(n)) return false;
        return !readOnly || Arrays.asList("read","viewimage","glob","grep","knowledge","todowrite","document","excel","artwork","git","artifactverification").contains(n);
    }
    public boolean permits(String name, JsonObject args) {
        if(!visible(name)) return false;
        if("ArtifactVerification".equalsIgnoreCase(name)&&(readOnly||isolatedOutput!=null))return ToolExecutionGuard.readOnly("ArtifactVerification",args);
        if("Git".equalsIgnoreCase(name)&&readOnly)return ToolExecutionGuard.readOnly("Git",args);
        // A child can cite/read the shared knowledge base, but cannot promote its working notes to shared facts.
        if("knowledge".equalsIgnoreCase(name)) {
            String action=args.has("action")&&!args.get("action").isJsonNull()?args.get("action").getAsString().toLowerCase(Locale.ROOT):"";
            if(!Arrays.asList("search","list","read").contains(action)) return false;
        }
        if(isolatedOutput!=null){
            String action=args.has("action")&&!args.get("action").isJsonNull()?args.get("action").getAsString().toLowerCase(Locale.ROOT):"";
            if("Knowledge".equalsIgnoreCase(name)&&!Arrays.asList("search","list","read").contains(action))return false;
            String key=null;
            if(Arrays.asList("Write","Edit").contains(name))key="path";
            if("Excel".equals(name)&&!com.minion.core.tools.data.ExcelTool.readOnlyAction(action))key="outputPath";
            if("Document".equals(name)&&!com.minion.core.tools.data.DocumentTool.readOnlyAction(action))key="outputPath";
            if("Artwork".equals(name)&&!com.minion.core.tools.artwork.ArtworkTool.readOnlyAction(action))key="template".equals(action)?"path":"outputPath";
            if(key!=null){if(!args.has(key)||args.get(key).isJsonNull())return false;
                try{java.nio.file.Path dest=java.nio.file.Paths.get(args.get(key).getAsString());
                    if(!dest.isAbsolute()||!com.minion.core.tools.PathsGuard.inside(isolatedOutput,dest))return false;
                }catch(Exception invalid){return false;}}
        }
        if(readOnly&&"Artwork".equals(name))return ToolExecutionGuard.readOnly(name,args);
        if(readOnly&&Arrays.asList("document","excel").contains(name.toLowerCase(Locale.ROOT))) {
            String action=args.has("action")&&!args.get("action").isJsonNull()?args.get("action").getAsString().toLowerCase(Locale.ROOT):"";
            // read带filter同样会进入pandas.query，不能视为只读探查。
            if("excel".equalsIgnoreCase(name)&&args.has("filter")&&!args.get("filter").isJsonNull()&&!args.get("filter").getAsString().trim().isEmpty())return false;
            return "document".equalsIgnoreCase(name)?com.minion.core.tools.data.DocumentTool.readOnlyAction(action)
                    :com.minion.core.tools.data.ExcelTool.readOnlyAction(action);
        }
        if(readOnly&&"knowledge".equalsIgnoreCase(name)) {
            String action=args.has("action")&&!args.get("action").isJsonNull()?args.get("action").getAsString():"";
            return Arrays.asList("search","list","read").contains(action.toLowerCase(Locale.ROOT));
        }
        return true;
    }
}
