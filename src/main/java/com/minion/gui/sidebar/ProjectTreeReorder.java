package com.minion.gui.sidebar;

import com.minion.core.config.WorkspaceConfig;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import java.util.*;

/** Order-only operations on full metadata, never on the filtered/paged list's row indices. */
public final class ProjectTreeReorder {
    private ProjectTreeReorder() {}
    public static boolean canMove(ProjectTreeModel.Row source,ProjectTreeModel.Row target) {
        if(source==null||target==null||source.key.equals(target.key))return false;
        if(source.kind==ProjectTreeModel.Kind.PROJECT)
            return !source.workspace.equals(target.workspace);
        SessionHandle a=source.session,b=target.session;
        return source.kind==ProjectTreeModel.Kind.SESSION&&target.kind==ProjectTreeModel.Kind.SESSION
                &&a!=null&&b!=null&&a!=b&&!a.deleted&&!b.deleted
                &&source.workspace.equals(target.workspace)&&source.workspace.equals(a.workspaceName)&&target.workspace.equals(b.workspaceName)
                &&!a.session.archived&&!b.session.archived&&a.session.pinned==b.session.pinned;
    }
    public static boolean move(SessionManager manager,ProjectTreeModel.Row source,ProjectTreeModel.Row target,boolean before) {
        if(!canMove(source,target))return false;
        if(source.session!=null)return manager.reorderSession(source.session,target.session,before);
        List<String> names=new ArrayList<String>();for(WorkspaceConfig w:manager.workspaces().list())names.add(w.workSpaceName);
        if(!names.remove(source.workspace))return false;
        int index=names.indexOf(target.workspace);if(index<0)return false;
        return manager.moveWorkspace(source.workspace,index+(before?0:1));
    }
    public static boolean moveAdjacent(SessionManager manager,ProjectTreeModel.Row row,int direction) {
        if(row==null||Math.abs(direction)!=1)return false;
        List<ProjectTreeModel.Row> peers=new ArrayList<ProjectTreeModel.Row>();
        if(row.kind==ProjectTreeModel.Kind.PROJECT){
            for(WorkspaceConfig w:manager.workspaces().list())peers.add(new ProjectTreeModel.Row(ProjectTreeModel.Kind.PROJECT,w.workSpaceName,w.workDir,null,0,0,false));
        }else if(row.kind==ProjectTreeModel.Kind.SESSION){
            for(SessionHandle h:manager.sessions(row.workspace,false))if(h.session.pinned==row.session.session.pinned)
                peers.add(new ProjectTreeModel.Row(ProjectTreeModel.Kind.SESSION,row.workspace,row.path,h,0,0,true));
        }else return false;
        for(int i=0;i<peers.size();i++)if(peers.get(i).key.equals(row.key)){
            int next=i+direction;return next>=0&&next<peers.size()&&move(manager,row,peers.get(next),direction<0);
        }
        return false;
    }
}
