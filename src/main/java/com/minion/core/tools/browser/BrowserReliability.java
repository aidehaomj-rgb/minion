package com.minion.core.tools.browser;

import com.google.gson.*;
import com.minion.core.tools.dev.ArtifactVerifier;
import java.io.IOException;
import java.util.*;

/** 会话内闩锁与语义定位；调用方在 BrowserSession monitor 内串行执行整次操作。 */
final class BrowserReliability {
    private final BrowserSession session;
    private String pauseReason = "", token = "", uncertain = "";
    private final JsonArray receipts = new JsonArray();
    BrowserReliability(BrowserSession session) { this.session = session; }

    void assertAutomaticAllowed() throws IOException {
        if (!pauseReason.isEmpty() || !uncertain.isEmpty())
            throw new IOException("waiting_user: " + pauseReason + (uncertain.isEmpty() ? "" : "；动作回执不确定: " + uncertain)
                    + "；禁止自动重放，请执行 BrowserAction status / resume 核对");
    }

    JsonObject pause(String reason) {
        pauseReason = reason == null || reason.isEmpty() ? "需要人工完成登录/验证码/结果核对" : reason;
        if (token.isEmpty()) token = UUID.randomUUID().toString();
        JsonObject out = state(); out.addProperty("resumeToken", token); return out;
    }

    JsonObject state() {
        JsonObject out = new JsonObject(); out.addProperty("status", pauseReason.isEmpty() && uncertain.isEmpty() ? "ready" : "waiting_user");
        out.addProperty("reason", pauseReason); out.addProperty("uncertainAction", uncertain);
        out.add("interactionReceipts", receipts.deepCopy());
        if (!token.isEmpty()) out.addProperty("resumeToken", token);
        return out;
    }

    JsonObject resume(JsonObject args) throws IOException {
        if (!args.has("userConfirmed") || !args.get("userConfirmed").getAsBoolean()
                || token.isEmpty() || !token.equals(text(args, "resumeToken")))
            throw new IOException("需要用户确认及当前 resumeToken，禁止模型自行推断已登录");
        String anchor = text(args, "selector");
        if (anchor.isEmpty()) throw new IOException("resume 需要登录后/结果核对后的唯一可见 selector");
        JsonObject scene = probe(anchor, text(args, "frameSelector"), false);
        if (bool(scene, "authRequired") || !bool(scene, "visible")) throw new IOException("人工核对未完成：锚点不可见或仍有登录/验证码");
        if (!uncertain.isEmpty() && !Arrays.asList("applied", "not_applied").contains(text(args, "resolution")))
            throw new IOException("不确定动作需人工明确 resolution=applied/not_applied；不会自动重放");
        JsonObject receipt = new JsonObject(); receipt.addProperty("kind", "human_reconciliation");
        receipt.addProperty("uncertainAction", uncertain); receipt.addProperty("resolution", text(args, "resolution"));
        receipt.add("page", scene); addReceipt(receipt);
        pauseReason = ""; uncertain = ""; token = "";
        JsonObject out = state(); out.add("page", scene); return out;
    }

    JsonObject probe(String selector, String frame, boolean interactive) throws IOException {
        return object(session.inspect(script(frame, selector, interactive, "return info;")));
    }

    JsonObject waitFor(String selector, String frame, boolean interactive, int timeout) throws IOException {
        long end = deadline(timeout); JsonObject last = null;
        do {
            last = probe(selector, frame, interactive);
            if (bool(last, "authRequired")) { pause("检测到可见登录/验证码控件，需要人工接管"); throw new IOException(state().toString()); }
            if (bool(last, interactive ? "interactive" : "visible")) return last;
            ArtifactVerifier.pause(100);
        } while (System.nanoTime() < end);
        throw new IOException("等待真正可见/可交互元素超时: " + last);
    }

    JsonObject act(String action, JsonObject args) throws IOException {
        assertAutomaticAllowed();
        String selector = text(args, "selector"), frame = text(args, "frameSelector");
        int timeout = integer(args, "timeoutMs", 10000);
        JsonObject before = waitFor(selector, frame, true, timeout);
        String expected = text(args, "expectedVersion");
        if ("click".equals(action) && expected.isEmpty()) throw new IOException("click 需要 status/wait 返回的 expectedVersion 和 receiptSelector；未执行");
        if (!expected.isEmpty() && !expected.equals(text(before, "pageVersion"))) throw new IOException("stale_page: 页面版本改变，请重新观察；未执行");
        String receiptSelector = text(args, "receiptSelector");
        if ("click".equals(action)) {
            if (receiptSelector.isEmpty()) throw new IOException("click 必须指定动作后 receiptSelector，禁止无回执提交");
            JsonObject prior = probe(receiptSelector, frame, false);
            if (matches(prior, text(args, "receiptText"))) {
                JsonObject out = new JsonObject(); out.addProperty("status", "already_satisfied");
                out.addProperty("actionExecuted", false); out.add("receipt", prior); return out;
            }
        }
        String operation;
        if ("click".equals(action)) operation = "e.click();";
        else if ("type".equals(action)) operation = "e.focus();var pd=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');if(pd&&pd.set)pd.set.call(e,"+js(text(args,"value"))+");else e.value="+js(text(args,"value"))+";e.dispatchEvent(new d.defaultView.Event('input',{bubbles:true}));e.dispatchEvent(new d.defaultView.Event('change',{bubbles:true}));";
        else if ("select".equals(action)) operation = "if(e.tagName!=='SELECT')throw Error('不是select控件');e.value="+js(text(args,"value"))+";if(e.value!=="+js(text(args,"value"))+")throw Error('选项不存在');e.dispatchEvent(new d.defaultView.Event('change',{bubbles:true}));";
        else throw new IOException("未知语义动作");
        // 在发送前上锁，即使 CDP 响应丢失，也绝不自动再次提交。
        uncertain = UUID.randomUUID().toString() + ":" + action + ":" + selector;
        try {
            JsonObject result = object(session.inspect(script(frame, selector, true,
                    "if(!info.interactive||info.authRequired)throw Error('元素不可交互或登录失效');"
                    + "if(info.pageVersion!==" + js(text(before,"pageVersion")) + ")throw Error('stale_page');"
                    + operation + "return {executed:true,before:info};")));
            if (!bool(result, "executed")) throw new IOException("动作未收到明确执行回执");
            JsonObject after;
            if ("click".equals(action)) {
                long end = deadline(timeout); after = null;
                do {
                    after = probe(receiptSelector, frame, false);
                    if (bool(after, "authRequired")) throw new IOException("动作后登录失效/验证码");
                    if (matches(after, text(args, "receiptText"))) break;
                    ArtifactVerifier.pause(100);
                } while (System.nanoTime() < end);
                if (!matches(after, text(args, "receiptText"))) throw new IOException("动作后回执等待超时");
            } else after = probe(selector, frame, false);
            JsonObject receipt = new JsonObject(); receipt.addProperty("status", "passed"); receipt.addProperty("action", action);
            receipt.addProperty("selector", selector); receipt.add("before", before); receipt.add("after", after);
            receipt.addProperty("observedAt", System.currentTimeMillis()); addReceipt(receipt); uncertain = ""; return receipt;
        } catch (IOException e) {
            pause("动作结果不确定，禁止重试；请人工核对页面/服务端回执: " + e.getMessage());
            throw new IOException(state().toString(), e);
        }
    }

    void beginUncertain(String action) throws IOException { assertAutomaticAllowed(); uncertain = action + ":" + UUID.randomUUID(); }
    void finishUncertain() { uncertain = ""; }
    void failedUncertain(String reason) { pause(reason); }
    private void addReceipt(JsonObject r) { if (receipts.size() >= 50) receipts.remove(0); receipts.add(r); }

    static boolean matches(JsonObject probe, String expectedText) {
        return probe != null && bool(probe, "visible") && (expectedText.isEmpty() || text(probe,"text").contains(expectedText));
    }

    /** 仅选择一个明确 frame；跨域/OOPIF 不可读时报错，而不是静默操作顶层。 */
    static String documentScript(String frame) {
        return "var topDoc=document,d=document,frameEl=null;" + (frame.isEmpty() ? "" :
            "var ff=document.querySelectorAll("+js(frame)+");if(ff.length!==1)throw Error('frame必须唯一');"
            + "if(!/^(IFRAME|FRAME)$/.test(ff[0].tagName))throw Error('选择器不是frame');"
            + "frameEl=ff[0];try{d=frameEl.contentDocument;if(!d||!d.documentElement)throw Error('unavailable');}catch(x){throw Error('跨域或未就绪frame，禁止回退顶层');}");
    }

    static String script(String frame, String selector, boolean interactive, String body) {
        return "(function(){" + documentScript(frame)
            + "function version(x){if(!x.__minionVersion){x.__minionVersion={id:Date.now()+'-'+Math.random(),rev:0};"
            + "var ob=new x.defaultView.MutationObserver(function(){x.__minionVersion.rev++});ob.observe(x,{subtree:true,childList:true,attributes:true,characterData:true});x.__minionVersion.ob=ob;}"
            + "return x.__minionVersion.id+'|'+x.location.href+'|'+x.__minionVersion.rev;}"
            + "function visible(x){if(!x||!x.isConnected||!x.getClientRects().length)return false;var w=x.ownerDocument.defaultView;for(var n=x;n&&n.nodeType===1;n=n.parentElement){var s=w.getComputedStyle(n);if(s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||Number(s.opacity)===0)return false;}var r=x.getBoundingClientRect();return r.width>0&&r.height>0&&r.right>0&&r.bottom>0&&r.left<w.innerWidth&&r.top<w.innerHeight;}"
            + "var nodes=" + (selector.isEmpty() ? "[d.documentElement]" : "d.querySelectorAll("+js(selector)+")") + ";var e=nodes.length===1?nodes[0]:null;"
            + (interactive ? "if(e&&e.isConnected&&e.getClientRects().length)e.scrollIntoView({block:'center',inline:'nearest'});" : "")
            + "var v=visible(e)&&(!frameEl||visible(frameEl)),r=e?e.getBoundingClientRect():{left:0,top:0,width:0,height:0};var hit=v?d.elementFromPoint(r.left+r.width/2,r.top+r.height/2):null;"
            + "if(frameEl&&v){var fr=frameEl.getBoundingClientRect(),fh=topDoc.elementFromPoint(fr.left+r.left+r.width/2,fr.top+r.top+r.height/2);if(fh!==frameEl)hit=null;}"
            + "var disabled=!!(e&&(e.disabled||e.matches(':disabled')||e.closest('[inert],[aria-disabled=true]')||e.getAttribute('aria-disabled')==='true'));"
            + "var auth=Array.prototype.some.call(d.querySelectorAll('input[type=password],iframe[src*=recaptcha],iframe[src*=hcaptcha],[data-sitekey],input[autocomplete=one-time-code]'),visible);"
            + "var info={pageVersion:version(topDoc)+'::'+version(d),url:d.location.href,title:d.title,matches:nodes.length,visible:v,interactive:v&&!disabled&&!!hit&&(e===hit||e.contains(hit)),disabled:disabled,authRequired:auth,text:e?String(e.innerText||e.textContent||'').slice(0,8000):''};"
            + body + "})()";
    }

    static JsonObject object(String raw) throws IOException {
        try { JsonElement e = JsonParser.parseString(raw); if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) e=JsonParser.parseString(e.getAsString()); return e.getAsJsonObject(); }
        catch (Exception e) { throw new IOException("浏览器未返回结构化证据: " + raw, e); }
    }
    static String js(String s) { return new Gson().toJson(s == null ? "" : s); }
    static String text(JsonObject o,String k) { return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():""; }
    static boolean bool(JsonObject o,String k) { return o.has(k)&&!o.get(k).isJsonNull()&&o.get(k).getAsBoolean(); }
    static int integer(JsonObject o,String k,int d) { return o.has(k)?o.get(k).getAsInt():d; }
    static long deadline(int ms) { return System.nanoTime()+Math.max(100,Math.min(ms,60000))*1000000L; }
}
