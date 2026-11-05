package com.minion.core.agent;
import com.google.gson.*;
import com.minion.core.tools.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ToolSchemaRouterTest {
    private JsonObject schema(String name,String description){JsonObject fn=new JsonObject();fn.addProperty("name",name);fn.addProperty("description",description);fn.add("parameters",new JsonObject());JsonObject schema=new JsonObject();schema.addProperty("type","function");schema.add("function",fn);return schema;}
    private List<JsonObject> all(){return Arrays.asList(schema("Read","读取文件"),schema("DiscoverTools","工具发现"),schema("Excel","表格分析"),schema("BrowserAction","浏览器网页操作"),schema("DbOracle","数据库查询"),schema("CustomTool","未知扩展保持兼容"));}
    private boolean has(List<JsonObject> schemas,String name){return schemas.stream().anyMatch(s->name.equals(s.getAsJsonObject("function").get("name").getAsString()));}
    @Test public void unusedHeavyGroupsAreDeferredButExtensionsRemainCompatible(){ToolSchemaRouter r=new ToolSchemaRouter();List<JsonObject> tools=r.select(all(),"修改Java代码");assertTrue(has(tools,"Read"));assertTrue(has(tools,"DiscoverTools"));assertTrue(has(tools,"CustomTool"));assertFalse(has(tools,"Excel"));assertFalse(has(tools,"BrowserAction"));assertTrue(r.stats().get("estimatedSchemaTokensAfter").getAsInt()<r.stats().get("estimatedSchemaTokensBefore").getAsInt());}
    @Test public void taskKeywordsAndExplicitDiscoveryLoadFullSchemasOnNextRequest(){ToolSchemaRouter r=new ToolSchemaRouter();assertTrue(has(r.select(all(),"分析Excel表格"),"Excel"));JsonObject found=r.discover(all(),"网页","",0,10);assertEquals(1,found.getAsJsonArray("matches").size());assertTrue(has(r.select(all(),"继续"),"BrowserAction"));r.discover(all(),"","DbOracle",0,10);assertTrue(has(r.select(all(),"继续"),"DbOracle"));}
    @Test public void pluginDisablingAndSchemaChangingInvalidateCache(){ToolSchemaRouter r=new ToolSchemaRouter();r.discover(all(),"","DbOracle",0,10);List<JsonObject> disabled=Arrays.asList(schema("Read","read"),schema("DiscoverTools","find"));assertFalse(has(r.select(disabled,"数据库"),"DbOracle"));try{r.discover(disabled,"","DbOracle",0,10);fail();}catch(IllegalArgumentException expected){}List<JsonObject> changed=Arrays.asList(schema("Read","new description"));assertTrue(r.select(changed,"").get(0).toString().contains("new description"));}
    @Test public void selectedCopiesCannotMutateCachedSchemasAndNewTaskResetsChoices(){ToolSchemaRouter r=new ToolSchemaRouter();r.remember("Excel");List<JsonObject> first=r.select(all(),"");assertTrue(has(first,"Excel"));first.get(0).getAsJsonObject("function").addProperty("name","tampered");assertTrue(has(r.select(all(),""),"Read"));assertTrue(r.stats().get("cacheHits").getAsInt()>0);r.reset();assertFalse(has(r.select(all(),""),"Excel"));}
    @Test public void discoveryIsNeitherBusinessProgressNorMutationEvidence(){ContinuationBudget budget=new ContinuationBudget();budget.observe("DiscoverTools","query",true,"different every call",null);assertEquals(0,budget.evidenceVersion);assertFalse(StopGate.mutationEvidence("DiscoverTools",new JsonObject()));assertFalse(new AgentToolPolicy(false,"").visible("DiscoverTools"));}
}
