package com.minion.gui.sidebar;

import com.google.gson.*;
import com.minion.core.tools.AtomicFiles;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Separate view-only file. Invalid/future preferences cannot rewrite any legacy or preference data. */
public final class SidebarPreferences {
    public static final class State {
        public int version=1;public boolean legacy,includeArchived;public Set<String> collapsed=new LinkedHashSet<String>();
        public State copy(){State s=new State();s.legacy=legacy;s.includeArchived=includeArchived;s.collapsed.addAll(collapsed);return s;}
    }
    private final Path file;private final AtomicLong revision=new AtomicLong();private volatile boolean writable=true;
    public SidebarPreferences(Path file){this.file=file;}
    public State load(){
        if(!Files.exists(file))return new State();
        try{
            if(!Files.isRegularFile(file)||Files.isSymbolicLink(file)||Files.size(file)>65536)throw new IOException("unsafe preferences");
            JsonObject o=JsonParser.parseString(new String(Files.readAllBytes(file),StandardCharsets.UTF_8)).getAsJsonObject();
            if(o.has("version")&&o.get("version").getAsInt()!=1)throw new IOException("future preferences");
            State s=new State();if(o.has("legacy"))s.legacy=o.get("legacy").getAsBoolean();if(o.has("includeArchived"))s.includeArchived=o.get("includeArchived").getAsBoolean();
            if(o.has("collapsed"))for(JsonElement e:o.getAsJsonArray("collapsed")){if(s.collapsed.size()>=1000)break;String key=e.getAsString();if(key.length()<=256)s.collapsed.add(key);}
            return s;
        }catch(Exception e){writable=false;return new State();}
    }
    public boolean writable(){return writable;}
    public long changed(){return revision.incrementAndGet();}
    public synchronized void save(State state,long expected) throws IOException {
        if(!writable||expected!=revision.get())return;
        if(Files.isSymbolicLink(file))throw new IOException("sidebar preference symlink rejected");
        String text=new Gson().toJson(state);if(text.getBytes(StandardCharsets.UTF_8).length>65536)throw new IOException("sidebar preferences too large");
        AtomicFiles.writeText(file,text,StandardCharsets.UTF_8);
    }
}
