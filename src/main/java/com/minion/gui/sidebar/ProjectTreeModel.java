package com.minion.gui.sidebar;

import com.minion.core.config.WorkspaceConfig;
import com.minion.gui.session.SessionHandle;
import java.util.*;
import java.util.function.Function;

/** Pure presentation of existing identities. Never loads messages, creates sessions or merges paths. */
public final class ProjectTreeModel {
    public enum Kind { PROJECT, SESSION, MORE, LESS, EMPTY }
    public static final class Row {
        public final Kind kind;public final String workspace,path,key;public final SessionHandle session;
        public final int total,shown;public final boolean expanded;
        Row(Kind kind,String workspace,String path,SessionHandle session,int total,int shown,boolean expanded){
            this.kind=kind;this.workspace=workspace;this.path=path;this.session=session;this.total=total;this.shown=shown;this.expanded=expanded;
            key=workspace+"\u0000"+kind+"\u0000"+(session==null?"":session.id);
        }
    }
    static final int PAGE_SIZE = 5;
    private ProjectTreeModel(){}
    public static List<Row> rows(List<WorkspaceConfig> projects,Function<String,List<SessionHandle>> sessions,
                                  Set<String> collapsed,Map<String,Integer> limits,String search,boolean archived){
        List<Row> out=new ArrayList<Row>();String q=search==null?"":search.trim().toLowerCase(Locale.ROOT);
        for(WorkspaceConfig project:projects){
            String name=project.workSpaceName;if(name==null)continue;
            boolean projectMatch=q.isEmpty()||contains(name,q)||contains(displayName(name),q)||contains(project.workDir,q);
            List<SessionHandle> matches=new ArrayList<SessionHandle>();
            for(SessionHandle h:sessions.apply(name)){
                if(h==null||h.deleted||!name.equals(h.workspaceName)||h.session==null||(!archived&&h.session.archived))continue;
                if(projectMatch||contains(h.title,q))matches.add(h);
            }
            if(!projectMatch&&matches.isEmpty())continue;
            boolean expanded=!collapsed.contains(name)||!q.isEmpty();
            int limit=Math.max(1,Math.min(10000,limits.containsKey(name)?limits.get(name):PAGE_SIZE));int shown=Math.min(limit,matches.size());
            out.add(new Row(Kind.PROJECT,name,project.workDir,null,matches.size(),shown,expanded));
            if(!expanded)continue;
            for(int i=0;i<shown;i++)out.add(new Row(Kind.SESSION,name,project.workDir,matches.get(i),matches.size(),shown,true));
            if(matches.size()>shown)out.add(new Row(Kind.MORE,name,project.workDir,null,matches.size(),shown,true));
            if(shown>PAGE_SIZE)out.add(new Row(Kind.LESS,name,project.workDir,null,matches.size(),shown,true));
            if(matches.isEmpty())out.add(new Row(Kind.EMPTY,name,project.workDir,null,0,0,true));
        }return out;
    }
    static String displayName(String name){return "default".equals(name)?"默认项目":name;}
    private static boolean contains(String s,String q){return s!=null&&s.toLowerCase(Locale.ROOT).contains(q);}
}
