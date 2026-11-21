package com.minion.core.config;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** §13.1 env 显式映射：已登记键解析、空值忽略、未知 MINION_* 告警、MINION_PYTHON 保留 */
public class EnvMappingTest {

    /** 显式表解析：仅非空值进链 */
    @Test public void resolvesOnlyRegisteredNonEmpty() {
        Map<String, String> env = new HashMap<String, String>();
        env.put("MINION_PERMISSION_MODE", "read-only ");
        env.put("MINION_HOOKS_ENABLED", "");
        env.put("MINION_NOTIFY_CHANNEL", "tray");
        env.put("UNRELATED", "x");
        Map<String, String> resolved = EnvMapping.resolved(env);
        assertEquals(2, resolved.size());
        assertEquals("read-only", resolved.get("permission.mode"));
        assertEquals("tray", resolved.get("notify.channel"));
        assertNull(resolved.get("hooks.enabled"));   // 空值忽略
    }

    /** 未知 MINION_* 告警（MINION_PYTHON 探测保留除外） */
    @Test public void unknownVarsFlagged() {
        Map<String, String> env = new HashMap<String, String>();
        env.put("MINION_TYOP_KEY", "1");       // 拼写错误
        env.put("MINION_OBSOLETE", "1");       // 已废弃
        env.put("MINION_PYTHON", "C:/py");     // 保留：解释器探测
        env.put("MINION_PERMISSION_MODE", "full");
        assertEquals(java.util.Arrays.asList("MINION_OBSOLETE", "MINION_TYOP_KEY"),
                EnvMapping.unknownMinionEnv(env));
    }

    /** 双向检索：env 名 → 配置键 */
    @Test public void configKeyOfBidirectional() {
        assertEquals("permission.mode", EnvMapping.configKeyOf("MINION_PERMISSION_MODE"));
        assertEquals("trust.store.v2", EnvMapping.configKeyOf("MINION_TRUST_STORE_V2"));
        assertNull(EnvMapping.configKeyOf("MINION_NOPE"));
        assertTrue(EnvMapping.table().containsKey("toolsearch.enabled"));
    }
}
