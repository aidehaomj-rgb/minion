package com.minion.core.tools.browser;

import com.google.gson.*;
import org.junit.*;
import javax.script.*;
import static org.junit.Assert.*;

/** Java8 Nashorn执行实际探针代码，DOM对象是模拟；不是实际浏览器渲染证据。 */
public class BrowserDomProbeGTest {
    private ScriptEngine engine;
    @Before public void setup()throws Exception {
        engine=new ScriptEngineManager().getEngineByName("nashorn");Assume.assumeNotNull(engine);
        engine.eval("var hit=true,auth=false,many=false,hidden=false,disabled=false,offscreen=false;"
          +"function doc(){var d={location:{href:'http://127.0.0.1/'},title:'demo'};d.defaultView={innerWidth:800,innerHeight:600,Event:function(){},MutationObserver:function(){this.observe=function(){}},getComputedStyle:function(e){return {display:hidden?'none':'block',visibility:'visible',opacity:1}}};"
          +"var e={ownerDocument:d,isConnected:true,nodeType:1,parentElement:null,tagName:'BUTTON',innerText:'save',getClientRects:function(){return [1]},getBoundingClientRect:function(){return {left:10,top:offscreen?700:10,width:20,height:10,right:30,bottom:offscreen?710:20}},scrollIntoView:function(){offscreen=false},matches:function(){return disabled},closest:function(){return null},getAttribute:function(){return ''},contains:function(x){return x===this}};d.e=e;d.documentElement=e;d.querySelectorAll=function(s){return s.indexOf('input[type=password]')>=0?(auth?[e]:[]):s==='#missing'?[]:many?[e,e]:[e]};d.elementFromPoint=function(){return hit?e:null};return d;}var document=doc();");
    }
    private JsonObject probe(boolean interactive)throws Exception {return probe("",interactive);}
    private JsonObject probe(String frame,boolean interactive)throws Exception {String script=BrowserReliability.script(frame,"#button",interactive,"return info;");return JsonParser.parseString(String.valueOf(engine.eval("JSON.stringify("+script+")"))).getAsJsonObject();}
    @Test public void visibleEnabledUncoveredElementIsInteractive()throws Exception{assertTrue(probe(true).get("interactive").getAsBoolean());}
    @Test public void hiddenDisabledCoveredAndDuplicateAreNotInteractive()throws Exception{engine.eval("hidden=true");assertFalse(probe(true).get("visible").getAsBoolean());engine.eval("hidden=false;disabled=true");assertFalse(probe(true).get("interactive").getAsBoolean());engine.eval("disabled=false;hit=false");assertFalse(probe(true).get("interactive").getAsBoolean());engine.eval("hit=true;many=true");assertFalse(probe(true).get("interactive").getAsBoolean());}
    @Test public void offscreenIsNotVisibleUntilInteractiveProbeScrolls()throws Exception{engine.eval("offscreen=true");assertFalse(probe(false).get("visible").getAsBoolean());assertTrue(probe(true).get("interactive").getAsBoolean());}
    @Test public void visiblePasswordRequestsHumanTakeover()throws Exception{engine.eval("auth=true");assertTrue(probe(false).get("authRequired").getAsBoolean());}
    @Test public void frameMustBeAUniqueReadableFrame()throws Exception{try{probe("#frame",true);fail();}catch(ScriptException e){assertTrue(e.getMessage().contains("选择器不是frame"));}engine.eval("document.e.tagName='IFRAME';document.e.contentDocument=null");try{probe("#frame",true);fail();}catch(ScriptException e){assertTrue(e.getMessage().contains("禁止回退顶层"));}}
    @Test public void explicitSameOriginFrameHasOwnDocumentIdentity()throws Exception{engine.eval("document.e.tagName='IFRAME';document.e.contentDocument=doc()");JsonObject out=probe("#frame",true);assertTrue(out.get("interactive").getAsBoolean());assertTrue(out.get("pageVersion").getAsString().contains("::"));}
    @Test public void pageVersionChangesForDomRevisionAndNavigation()throws Exception{String first=probe(false).get("pageVersion").getAsString();engine.eval("document.__minionVersion.rev++");String revision=probe(false).get("pageVersion").getAsString();assertNotEquals(first,revision);engine.eval("document.location.href='http://127.0.0.1/next'");assertNotEquals(revision,probe(false).get("pageVersion").getAsString());}
}
