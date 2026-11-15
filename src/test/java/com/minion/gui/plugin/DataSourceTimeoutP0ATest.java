package com.minion.gui.plugin;

import com.minion.core.tools.db.DataSourceConfig;
import org.junit.Test;
import static org.junit.Assert.*;

/** 无需启动 FX 的表单保存规则测试。 */
public class DataSourceTimeoutP0ATest {
    @Test public void blankPreservesExistingValueAndNewSourcesDefaultTo300() {
        DataSourceConfig old = new DataSourceConfig(); old.queryTimeoutSeconds = 3600;
        assertEquals(3600, DataSourceDialog.parseQueryTimeout(" ", old));
        assertEquals(300, DataSourceDialog.parseQueryTimeout(null, null));
        assertEquals(1, DataSourceDialog.parseQueryTimeout("1", old));
        assertEquals(86400, DataSourceDialog.parseQueryTimeout(" 86400 ", old));
    }
    @Test public void invalidAndOutOfRangeTimeoutsAreRejected() {
        for (String value : new String[]{"0", "-1", "86401", "1.5", "abc", "999999999999999999"}) {
            try { DataSourceDialog.parseQueryTimeout(value, null); fail(value); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("1–86400")); }
        }
    }
}
