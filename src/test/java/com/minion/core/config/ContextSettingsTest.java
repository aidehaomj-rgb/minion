package com.minion.core.config;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContextSettingsTest {
    @Test public void percentagePreviewMatchesSavedRatioAndRuntime() {
        ContextSettings s=ContextSettings.parse("1000000","90","8192","50");
        assertEquals(1000000,s.window); assertEquals(0.9,s.ratio,0);
        assertEquals(150000,s.triggerTokens());
        assertEquals(150000,ContextSettings.parse("1000000","95","8192","0").triggerTokens());
        assertEquals(150000,ContextSettings.parse("1000000","100","65536","50").triggerTokens());
        assertEquals(150000,ContextSettings.parse("1000000","89.5","8192","50").triggerTokens());
    }
    @Test public void invalidValuesAreRejectedInsteadOfSilentlyReplaced() {
        for(String p:new String[]{"","0","-1","101","NaN","Infinity","abc"}) reject("1000000",p,"8192","50");
        reject("2147483648","90","8192","50"); reject("0","90","8192","50");
        reject("8192","90","8192","50"); reject("1000000","90","0","50");
        reject("1000000","90","65537","50"); reject("1000000","90","8192","-1");
    }
    @Test public void perModelStableWorksetCannotBypassWindowAndOutputReserve() {
        ContextSettings pro=ContextSettings.parse("1000000","90","8192","50","180000");
        assertEquals(180000,pro.triggerTokens());
        ContextSettings flash=ContextSettings.parse("1000000","90","8192","50","80000");
        assertEquals(80000,flash.triggerTokens());
        assertEquals(51808,ContextSettings.parse("60000","100","8192","10","55000").triggerTokens());
        for(String s:new String[]{"-1","8191","1000000","oops"}) {
            try { ContextSettings.parse("1000000","90","8192","50",s); fail(s); }
            catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }
    private static void reject(String w,String p,String o,String k) {
        try { ContextSettings.parse(w,p,o,k); fail("Invalid settings accepted"); }
        catch(IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
}
