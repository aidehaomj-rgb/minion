package com.minion.gui.artifact;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class LocalFileLinksTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void encodedUriRoundTripsChineseSpacesHashAndPlus()throws Exception {
        Path p=temp.newFolder().toPath().resolve("中文 空格+#100%.docx");
        assertEquals(p,LocalFileLinks.resolve(p.toUri().toASCIIString(),temp.getRoot().toPath()));
        assertEquals(p,LocalFileLinks.resolve(p.toString(),temp.getRoot().toPath()));
    }
    @Test public void relativeLinksUseConversationBaseNotProcessDirectory()throws Exception {
        Path base=temp.newFolder().toPath();
        assertEquals(base.resolve("子目录/报告 结果.md"),LocalFileLinks.resolve("子目录/报告%20结果.md",base));
        assertEquals(base.resolve("report.md"),LocalFileLinks.resolve("report.md#L12",base));
    }
    @Test public void unsupportedCloudJavascriptAndControlLinksDoNotBecomeLocalPaths()throws Exception {
        for(String value:new String[]{"javascript:alert(1)","sandbox:/mnt/data/file.docx","cmd:run","report\n.txt"})
            try{LocalFileLinks.resolve(value,temp.getRoot().toPath());fail(value);}catch(java.io.IOException expected){}
    }
    @Test public void fileOpenAndRevealAreExplicitAndDoNotInvokeCommandShell()throws Exception {
        Path p=temp.newFile("报告 & ! 文件.docx").toPath();BlockingQueue<String> calls=new LinkedBlockingQueue<String>();
        LocalFileLinks.Backend backend=new LocalFileLinks.Backend(){
            public void open(Path path){calls.add("open:"+path);}public void reveal(Path path){calls.add("reveal:"+path);}public void browse(URI uri){calls.add("web:"+uri);}};
        try(LocalFileLinks links=new LocalFileLinks(()->temp.getRoot().toPath(),()->null,path->calls.add("preview:"+path),calls::add,backend)) {
            links.open(p.toUri().toString());assertEquals("open:"+p,calls.poll(5,TimeUnit.SECONDS));
            links.reveal(p.toUri().toString());assertEquals("reveal:"+p,calls.poll(5,TimeUnit.SECONDS));
            links.open("https://example.com/help");assertEquals("web:https://example.com/help",calls.poll(5,TimeUnit.SECONDS));
        }
    }
    @Test public void executableClassificationBlocksScriptsAndShortcutsButNotDocuments() {
        for(String n:new String[]{"a.EXE","a.cmd","a.js","a.jar","a.lnk","a.url","程序.py","a.pif","a.msc","a.scf"})assertTrue(n,LocalFileLinks.executable(Paths.get(n)));
        for(String n:new String[]{"报告.docx","说明.md","数据.xlsx","截图.png"})assertFalse(n,LocalFileLinks.executable(Paths.get(n)));
    }
}
