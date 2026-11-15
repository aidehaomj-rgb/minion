import com.minion.core.tools.artwork.ArtworkTool;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.ToolResult;
import com.google.gson.JsonObject;
import java.nio.file.*;

/** 用包内 Java8 在新目录生成三类成图，供人工检查；无需 Python/浏览器/模型。 */
public class VerifyLocalArtwork {
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(root);
        ArtworkTool tool=new ArtworkTool(new Workspace(root.toString()),null);
        for(String kind:new String[]{"cover","process","dashboard"}) {
            JsonObject a=new JsonObject();a.addProperty("action","template");a.addProperty("path",kind+".json");a.addProperty("template",kind);
            check(tool.execute(a));a.addProperty("action","render");a.addProperty("outputPath",kind+".png");a.addProperty("scale",2);check(tool.execute(a));
        }
    }
    private static void check(ToolResult r){if(!r.ok)throw new IllegalStateException(r.output);System.out.println(r.output);}
}
