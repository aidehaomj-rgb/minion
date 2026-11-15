package com.minion.gui.dialog;

import org.junit.Test;
import static org.junit.Assert.*;

public class AboutOfflineTest {
    @Test public void offlineAboutShowsRuntimeWithoutStaleReleaseOrPersonalContact() throws Exception {
        StringBuilder text=new StringBuilder();
        for(String line:SettingsDialog.aboutText())text.append(line).append('\n');
        assertTrue(text.toString().contains("Java 8"));
        assertFalse(text.toString().contains("V57开发版"));
        assertFalse(text.toString().contains("@"));
        assertFalse(text.toString().contains("联系方式"));
        assertFalse(text.toString().contains("作者："));
    }
}
