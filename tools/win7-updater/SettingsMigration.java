import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Only rewrites copied configuration/snapshot paths; never edits the old installation. */
public final class SettingsMigration {
    private static final Gson G = new GsonBuilder().setPrettyPrinting().create();
    private static int changed;
    private static boolean fix(JsonObject o, Path old) {
        if (!o.has("workDir") || o.get("workDir").isJsonNull()) return false;
        String raw=o.get("workDir").getAsString(); if(raw.trim().isEmpty()) return false;
        Path p=Paths.get(raw); if(p.isAbsolute()) return false;
        o.addProperty("workDir",old.resolve(p).normalize().toString()); return true;
    }
    private static void rewrite(Path file,Path old,boolean workspace) throws IOException {
        JsonElement json;
        try(java.io.Reader r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){json=JsonParser.parseReader(r);}
        boolean dirty=false;
        if(workspace) {
            if(json.isJsonObject())for(java.util.Map.Entry<String,JsonElement> field:json.getAsJsonObject().entrySet()){
                if(field.getValue().isJsonArray())for(JsonElement item:field.getValue().getAsJsonArray())
                    if(item.isJsonObject())dirty|=fix(item.getAsJsonObject(),old);
            }
        } else if(json.isJsonObject())dirty=fix(json.getAsJsonObject(),old);
        if(!dirty)return;
        Path tmp=file.resolveSibling(file.getFileName()+".migration.tmp");
        Files.write(tmp,G.toJson(json).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);changed++;
    }
    public static void main(String[] args) throws Exception {
        final Path old=Paths.get(args[0]).toAbsolutePath().normalize();
        Path target=Paths.get(args[1]).toAbsolutePath().normalize();
        if(old.equals(target))throw new IllegalArgumentException("Source equals destination");
        Path ws=target.resolve("workspace.json");if(Files.isRegularFile(ws))rewrite(ws,old,true);
        Path sessions=target.resolve("session");
        if(Files.isDirectory(sessions))Files.walkFileTree(sessions,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a){
                return dir.getFileName().toString().startsWith(".")?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException {
                if(file.toString().endsWith(".json")&&!file.toString().endsWith(".queue.json"))rewrite(file,old,false);
                return FileVisitResult.CONTINUE;
            }
        });
        System.out.println("Normalized copied relative project paths: "+changed);
    }
}
