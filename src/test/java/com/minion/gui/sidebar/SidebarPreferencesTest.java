package com.minion.gui.sidebar;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class SidebarPreferencesTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void loadDoesNotCreateFileAndSavedChangesStayInSeparateFile()throws Exception {
        Path p=tmp.getRoot().toPath().resolve("sidebar-view.json");SidebarPreferences prefs=new SidebarPreferences(p);assertFalse(prefs.load().legacy);assertFalse(Files.exists(p));
        SidebarPreferences.State a=new SidebarPreferences.State();a.collapsed.add("中文项目");a.includeArchived=true;a.legacy=true;
        prefs.save(a,prefs.changed());SidebarPreferences.State b=new SidebarPreferences(p).load();assertTrue(b.legacy);assertTrue(b.includeArchived);assertTrue(b.collapsed.contains("中文项目"));
    }
    @Test public void corruptOrFuturePreferencesAreNotOverwritten()throws Exception {
        for(String raw:new String[]{"not-json","{\"version\":999,\"legacy\":true}"}){
            Path p=tmp.newFile().toPath();byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);Files.write(p,bytes);
            SidebarPreferences prefs=new SidebarPreferences(p);prefs.load();assertFalse(prefs.writable());prefs.save(new SidebarPreferences.State(),prefs.changed());assertArrayEquals(bytes,Files.readAllBytes(p));
        }
    }
    @Test public void olderBackgroundSaveCannotOverwriteLatestChoice()throws Exception {
        Path p=tmp.getRoot().toPath().resolve("sidebar-view.json");SidebarPreferences prefs=new SidebarPreferences(p);prefs.load();
        SidebarPreferences.State a=new SidebarPreferences.State(),b=new SidebarPreferences.State();b.legacy=true;
        long old=prefs.changed(),latest=prefs.changed();prefs.save(b,latest);prefs.save(a,old);assertTrue(new SidebarPreferences(p).load().legacy);
    }
}
