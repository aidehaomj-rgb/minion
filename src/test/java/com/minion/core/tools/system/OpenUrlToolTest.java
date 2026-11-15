package com.minion.core.tools.system;

import com.google.gson.JsonParser;
import com.minion.core.tools.ToolResult;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OpenUrlToolTest {
    @Test public void sameUrlOpensOnlyOnceUnlessUserForcesIt() {
        AtomicInteger opens=new AtomicInteger();
        OpenUrlTool tool=new OpenUrlTool(uri->opens.incrementAndGet());
        assertTrue(tool.execute(args("https://WWW.data10086.com")).ok);
        ToolResult duplicate=tool.execute(args("https://www.data10086.com/"));
        assertTrue(duplicate.ok);
        assertTrue(duplicate.output.contains("already_open"));
        assertEquals(1,opens.get());
        assertTrue(tool.execute(args("https://www.data10086.com/","true")).ok);
        assertEquals(2,opens.get());
    }

    @Test public void rejectsUnsafeOrIncompleteUrlsBeforeOpening() {
        AtomicInteger opens=new AtomicInteger();
        OpenUrlTool tool=new OpenUrlTool(uri->opens.incrementAndGet());
        assertFalse(tool.execute(args("file:///C:/secret.txt")).ok);
        assertFalse(tool.execute(args("javascript:alert(1)")).ok);
        assertFalse(tool.execute(args("https:///missing-host")).ok);
        assertEquals(0,opens.get());
    }

    @Test public void openerFailureIsReportedAndCanBeRetried() {
        AtomicInteger calls=new AtomicInteger();
        OpenUrlTool tool=new OpenUrlTool(uri->{
            if(calls.incrementAndGet()==1)throw new IllegalStateException("browser unavailable");
        });
        assertFalse(tool.execute(args("https://example.com")).ok);
        assertTrue(tool.execute(args("https://example.com")).ok);
        assertEquals(2,calls.get());
    }

    private static com.google.gson.JsonObject args(String url){
        return JsonParser.parseString("{\"url\":\""+url+"\"}").getAsJsonObject();
    }
    private static com.google.gson.JsonObject args(String url,String force){
        return JsonParser.parseString("{\"url\":\""+url+"\",\"force\":"+force+"}").getAsJsonObject();
    }
}
