package com.minion.core.llm;

import org.junit.Test;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** §1.6 SideQuery 验收：≤2 次重试、限流/过载立即放弃、异常不外抛 */
public class SideQueryTest {

    /** 可编程假客户端：按脚本依次抛错或返回文本 */
    private static class Scripted implements LlmClient {
        final List<Object> script = new ArrayList<Object>();   // LlmException=抛错，String=返回
        int calls;

        public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
            calls++;
            Object step = script.isEmpty() ? "ok" : script.remove(0);
            if (step instanceof LlmException) throw (LlmException) step;
            return (String) step;
        }
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
                throws LlmException {
            throw new UnsupportedOperationException();
        }
    }

    private static LlmException rateLimit() {
        return new LlmException(LlmException.Type.RATE_LIMIT, "请求过于频繁(429)", true, 429, "", 0);
    }

    private static LlmException overload529() {
        return new LlmException(LlmException.Type.OTHER, "server overloaded_error", true, 529, "", 0);
    }

    private static LlmException transient500() {
        return new LlmException(LlmException.Type.OTHER, "API 错误(500)", true, 500, "", 0);
    }

    @Test public void successFirstTryNoRetry() {
        Scripted llm = new Scripted();
        llm.script.add("摘要文本");
        assertEquals("摘要文本", SideQuery.complete(llm, new ArrayList<Message>(), "sys", "title"));
        assertEquals(1, llm.calls);
    }

    /** 瞬时 500：重试一次成功（共 2 次调用） */
    @Test public void transientErrorRetriedOnceThenSucceeds() {
        Scripted llm = new Scripted();
        llm.script.add(transient500());
        llm.script.add("恢复文本");
        assertEquals("恢复文本", SideQuery.complete(llm, new ArrayList<Message>(), "sys", "compact"));
        assertEquals(2, llm.calls);
    }

    /** 限流/过载：立即放弃（第 1 次失败不重试）——侧路请求不放大重试 */
    @Test public void rateLimitGivesUpImmediately() {
        Scripted rate = new Scripted();
        rate.script.add(rateLimit());
        assertNull(SideQuery.complete(rate, new ArrayList<Message>(), "sys", "compact"));
        assertEquals(1, rate.calls);

        Scripted overload = new Scripted();
        overload.script.add(overload529());
        assertNull(SideQuery.complete(overload, new ArrayList<Message>(), "sys", "compact"));
        assertEquals(1, overload.calls);
    }

    /** 一直失败：恰好 MAX_RETRIES 次后放弃返回 null（不抛） */
    @Test public void persistentFailureStopsAtMaxRetries() {
        Scripted llm = new Scripted();
        llm.script.add(transient500());
        llm.script.add(transient500());
        llm.script.add(transient500());
        assertNull(SideQuery.complete(llm, new ArrayList<Message>(), "sys", "title"));
        assertEquals(SideQuery.MAX_RETRIES, llm.calls);
    }

    /** 运行时异常：立即放弃不外抛（主循环不受影响） */
    @Test public void runtimeExceptionSwallowed() {
        Scripted llm = new Scripted() {
            @Override public String completeChat(List<Message> m, String s) {
                calls++;
                throw new IllegalStateException("boom");
            }
        };
        assertNull(SideQuery.complete(llm, new ArrayList<Message>(), "sys", "x"));
        assertEquals(1, llm.calls);
    }

    /** 401 认证错误：配置类错误不重试 */
    @Test public void authErrorNotRetried() {
        Scripted llm = new Scripted();
        llm.script.add(new LlmException(LlmException.Type.AUTH, "认证失败(401)", false, 401, "", 0));
        assertNull(SideQuery.complete(llm, new ArrayList<Message>(), "sys", "x"));
        assertEquals(1, llm.calls);
    }
}
