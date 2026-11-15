package com.minion.gui.sidebar;
import com.minion.core.agent.Session;
import com.minion.core.config.WorkspaceConfig;
import com.minion.gui.session.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ProjectTreeModelTest {
    private SessionHandle handle(String project,String id,String title,boolean archived){
        Session s=Session.create(".","fake");s.id=id;s.title=title;s.archived=archived;
        return new SessionHandle(id,project,s,null,new SessionController(),title,false,null);
    }
    @Test public void sameDirectoryAndDuplicateIdsRemainSeparateWithoutHydration(){
        List<WorkspaceConfig> projects=Arrays.asList(new WorkspaceConfig("甲","D:/same",null,null),new WorkspaceConfig("乙","D:/same",null,null));
        SessionHandle a=handle("甲","same-id","旧对话",false),b=handle("乙","same-id","旧对话",false);
        List<ProjectTreeModel.Row> rows=ProjectTreeModel.rows(projects,n->Collections.singletonList(n.equals("甲")?a:b),Collections.emptySet(),Collections.emptyMap(),"",false);
        assertEquals(4,rows.size());assertNotEquals(rows.get(1).key,rows.get(3).key);assertSame(a,rows.get(1).session);assertSame(b,rows.get(3).session);
        assertFalse(a.isLoaded());assertFalse(b.isLoaded());a.pool.shutdownNow();b.pool.shutdownNow();
    }
    @Test public void collapsedSearchDoesNotMutatePreferencesAndEmptyProjectsRemain(){
        List<WorkspaceConfig> projects=Arrays.asList(new WorkspaceConfig("甲","missing",null,null),new WorkspaceConfig("空项目","missing",null,null));
        SessionHandle a=handle("甲","id","报表核对",false);Set<String> collapsed=new HashSet<String>(Collections.singleton("甲"));
        java.util.function.Function<String,List<SessionHandle>> sessions=n->n.equals("甲")?Collections.singletonList(a):Collections.emptyList();
        List<ProjectTreeModel.Row> plain=ProjectTreeModel.rows(projects,sessions,collapsed,Collections.emptyMap(),"",false);assertEquals(3,plain.size());
        List<ProjectTreeModel.Row> match=ProjectTreeModel.rows(projects,sessions,collapsed,Collections.emptyMap(),"报表",false);assertEquals(2,match.size());assertTrue(collapsed.contains("甲"));
        assertEquals(ProjectTreeModel.Kind.EMPTY,plain.get(2).kind);a.pool.shutdownNow();
    }
    @Test public void paginationArchivedAndDeletedAreOnlyViewFilters(){
        WorkspaceConfig w=new WorkspaceConfig("p",".",null,null);List<SessionHandle> list=new ArrayList<SessionHandle>();
        for(int i=0;i<35;i++)list.add(handle("p","id-"+i,"对话"+i,false));SessionHandle archived=handle("p","arch","旧归档",true);list.add(archived);
        List<ProjectTreeModel.Row> rows=ProjectTreeModel.rows(Collections.singletonList(w),n->list,Collections.emptySet(),Collections.emptyMap(),"",false);
        assertEquals(ProjectTreeModel.Kind.MORE,rows.get(rows.size()-1).kind);assertEquals(35,rows.get(0).total);assertEquals(5,rows.get(0).shown);
        Map<String,Integer> limits=new HashMap<String,Integer>();limits.put("p",10);
        List<ProjectTreeModel.Row> partial=ProjectTreeModel.rows(Collections.singletonList(w),n->list,Collections.emptySet(),limits,"",false);
        assertEquals(ProjectTreeModel.Kind.MORE,partial.get(partial.size()-2).kind);
        assertEquals(ProjectTreeModel.Kind.LESS,partial.get(partial.size()-1).kind);
        limits.put("p",100);
        List<ProjectTreeModel.Row> all=ProjectTreeModel.rows(Collections.singletonList(w),n->list,Collections.emptySet(),limits,"",true);
        assertEquals(38,all.size());assertEquals(ProjectTreeModel.Kind.LESS,all.get(all.size()-1).kind);
        limits.remove("p");
        List<ProjectTreeModel.Row> reset=ProjectTreeModel.rows(Collections.singletonList(w),n->list,Collections.emptySet(),limits,"",false);
        assertEquals(5,reset.get(0).shown);assertEquals(ProjectTreeModel.Kind.MORE,reset.get(reset.size()-1).kind);
        limits.put("p",100);
        assertTrue(archived.session.archived);assertFalse(archived.isLoaded());for(SessionHandle h:list)h.pool.shutdownNow();
        list.get(0).deleted=true;
        assertEquals(34,ProjectTreeModel.rows(Collections.singletonList(w),n->list,Collections.emptySet(),limits,"",false).get(0).total);
    }
}
