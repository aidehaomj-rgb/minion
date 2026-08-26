package com.minion.core.tools.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.io.IOException;
import java.util.Locale;

/** 给中小模型使用的语义化浏览器动作，减少手写 BrowserEval JavaScript 的失败率。 */
public class BrowserActionTool implements Tool {
    private final BrowserSession session;
    public BrowserActionTool(BrowserSession session){this.session=session;}
    @Override public String name(){return "BrowserAction";}
    @Override public String description(){return "语义化网页操作：click/type/select/text/table/links/scroll/wait；selector 使用 CSS 选择器";}
    @Override public JsonObject schema(){return SchemaGenerator.objectSchema("网页元素操作",
            new String[]{"action","selector","value","timeoutMs"},new String[]{"action"});}
    @Override public ToolResult execute(JsonObject args){
        String action=text(args,"action").toLowerCase(Locale.ROOT),selector=text(args,"selector"),value=text(args,"value");
        try{
            if("links".equals(action))return ok(session.evaluate("JSON.stringify([...document.querySelectorAll('a[href]')].slice(0,200).map(a=>({text:(a.innerText||a.textContent||'').trim(),href:a.href})))"));
            if("scroll".equals(action)){String amount=value.isEmpty()?"window.innerHeight":value;return ok(session.evaluate("window.scrollBy(0,"+numeric(amount)+"); '已滚动'"));}
            if(selector.isEmpty())return ToolResult.error(action+" 需要 selector 参数");
            String q="document.querySelector("+js(selector)+")";
            if("click".equals(action)){
                ToolResult result=ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到元素');e.click();return '已点击 '+"+js(selector)+"})()"));
                session.waitForPage(10000);
                return result;
            }
            if("type".equals(action))return ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到元素');e.focus();__minion_set_value(e,"+js(value)+");return '已输入' })()"));
            if("select".equals(action))return ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到元素');e.value="+js(value)+";e.dispatchEvent(new Event('change',{bubbles:true}));return '已选择' })()"));
            if("text".equals(action))return ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到元素');return e.innerText||e.textContent||''})()"));
            if("table".equals(action))return ok(session.evaluate("(()=>{let e="+q+";if(!e)throw Error('未找到表格');return JSON.stringify([...e.querySelectorAll('tr')].map(r=>[...r.querySelectorAll('th,td')].map(c=>(c.innerText||'').trim())))})()"));
            if("wait".equals(action))return waitFor(selector,integer(args,"timeoutMs",10000));
            return ToolResult.error("未知 action: "+action+"（支持 click/type/select/text/table/links/scroll/wait）");
        }catch(IOException e){return ToolResult.error(e.getMessage());}
    }
    private ToolResult waitFor(String selector,int timeout)throws IOException{
        long end=System.currentTimeMillis()+Math.max(100,Math.min(timeout,60000));
        while(System.currentTimeMillis()<end){if("true".equals(session.evaluate("!!document.querySelector("+js(selector)+")")))return ToolResult.success("元素已出现: "+selector);try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        return ToolResult.error("等待元素超时: "+selector);
    }
    private static ToolResult ok(String value){return ToolResult.success(value==null?"":value);}
    private static String js(String s){return new Gson().toJson(s==null?"":s);}
    private static String numeric(String s){try{return String.valueOf(Integer.parseInt(s));}catch(Exception e){return "window.innerHeight";}}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){try{return o.has(k)?Integer.parseInt(o.get(k).getAsString()):d;}catch(Exception e){return d;}}
}
