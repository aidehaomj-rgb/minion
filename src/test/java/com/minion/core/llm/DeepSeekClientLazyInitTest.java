package com.minion.core.llm;
import org.junit.Test;
import static org.junit.Assert.*;
public class DeepSeekClientLazyInitTest {
 @Test public void constructionAndCloseDoNotInitializeHttp() {
  DeepSeekClient c = new DeepSeekClient("http://localhost", "", "test", false, "", "qwen");
  assertFalse(c.httpInitialized()); c.close(); c.close(); assertFalse(c.httpInitialized());
 }
}
