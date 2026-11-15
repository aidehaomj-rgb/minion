package com.minion.gui.artifact;
import com.minion.core.agent.GeneratedArtifact;
import com.minion.core.agent.Session;
import java.util.*;

/** Presentation-only ordering for user-facing results; execution records stay backend-only. */
public final class ArtifactSelection {
    private static final Set<String> USER_FACING_EXTENSIONS=new HashSet<String>(Arrays.asList(
            "md","markdown","txt","html","htm","doc","docx","pdf","ofd","gd",
            "ppt","pptx","xls","xlsx","csv",
            "png","jpg","jpeg","gif","bmp",
            "rtf","odt","ods","odp"));
    private ArtifactSelection(){}
    public static boolean sameOwner(Object visible,Object active,Object event){return event!=null&&visible==event&&active==event;}
    public static boolean presentable(GeneratedArtifact artifact){
        return artifact!=null
                && artifact.path!=null
                && !artifact.path.trim().isEmpty()
                && (artifact.kind==null||GeneratedArtifact.DELIVERABLE.equals(artifact.kind)
                    ||GeneratedArtifact.REFERENCE.equals(artifact.kind))
                && userFacingFile(artifact.path);
    }
    public static boolean userFacingFile(String path){
        if(path==null)return false;
        String name=path.trim();
        int slash=Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'));
        int dot=name.lastIndexOf('.');
        if(dot<=slash||dot+1>=name.length())return false;
        return USER_FACING_EXTENSIONS.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));
    }
    public static List<GeneratedArtifact> ordered(Session session) {
        if(session==null)return Collections.emptyList();
        List<GeneratedArtifact> out=new ArrayList<GeneratedArtifact>(session.artifactsSnapshot());
        final String task=session.taskState==null?null:session.taskState.id;
        out.removeIf(a->!presentable(a));
        out.sort((a,b)->{
            int kind=Integer.compare(rank(a.kind),rank(b.kind));
            if(kind!=0)return kind;
            int scope=Boolean.compare(Objects.equals(task,b.taskId),Objects.equals(task,a.taskId));
            return scope!=0?scope:Long.compare(b.updatedAt,a.updatedAt);
        });return out;
    }
    public static String preferred(Session session) {List<GeneratedArtifact> rows=ordered(session);return rows.isEmpty()?null:rows.get(0).path;}
    public static GeneratedArtifact find(Session session,String path) {
        if(session!=null&&path!=null)for(GeneratedArtifact item:session.artifactsSnapshot())if(item!=null&&path.equals(item.path))return item;
        return null;
    }
    private static int rank(String kind){
        return GeneratedArtifact.DELIVERABLE.equals(kind)?0:1;
    }
}
