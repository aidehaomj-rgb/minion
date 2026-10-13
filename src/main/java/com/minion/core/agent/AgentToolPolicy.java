package com.minion.core.agent;

import com.google.gson.JsonObject;
import java.util.*;

/** Fail closed for exploration. Shell/Python/MCP are never guessed to be read-only. */
public final class AgentToolPolicy {
    private final boolean readOnly;
    private final Set<String> allowed;
    private static final Set<String> BLOCKED = new HashSet<String>(Arrays.asList(
            "task","context","agentcontrol","askuserquestion","skill","permission","secrets","update"));
    public AgentToolPolicy(boolean readOnly, String tools) {
        this.readOnly=readOnly;
        allowed=new HashSet<String>();
        if(tools!=null) for(String t:tools.split("[,，\\s]+")) if(!t.isEmpty()) allowed.add(t.toLowerCase(Locale.ROOT));
    }
    public boolean readOnly() { return readOnly; }
    public boolean visible(String name) {
        String n=name.toLowerCase(Locale.ROOT);
        if(BLOCKED.contains(n)) return false;
        if(!allowed.isEmpty()&&!allowed.contains(n)) return false;
        return !readOnly || Arrays.asList("read","glob","grep","knowledge","todowrite").contains(n);
    }
    public boolean permits(String name, JsonObject args) {
        if(!visible(name)) return false;
        if(readOnly&&"knowledge".equalsIgnoreCase(name)) {
            String action=args.has("action")&&!args.get("action").isJsonNull()?args.get("action").getAsString():"";
            return Arrays.asList("search","list","read").contains(action.toLowerCase(Locale.ROOT));
        }
        return true;
    }
}
