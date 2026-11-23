package com.minion.gui.session;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.notify.Notifier;
import com.minion.core.skills.Skill;
import com.minion.core.tools.confirm.ConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * 关窗后托盘图标残留的根因之一（2026-11-23 修复）：shutdown 若把 notifier 置空，随后被中断的会话线程
 * 收尾时调用 notifyRunningChanged(false) 会经 notifier() 惰性重建一个新实例——托盘图标被再建一次，
 * 而那一次不会再被清理。正确做法是保留已关闭的实例（其 notify() 是空操作）。
 */
public class NotifierShutdownTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void shutdownKeepsAClosedNotifierInsteadOfLeavingNothingToRecreate() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        SessionManager m = new SessionManager(new ConfirmUi() {
            @Override public Decision ask(String message) { return Decision.APPROVE; }
        }, Config.load(jar), jar, WorkspaceManager.load(jar), ModelManager.load(jar),
                new ArrayList<Skill>(), null, null);
        m.createSession("通知");
        m.shutdown();

        Field field = SessionManager.class.getDeclaredField("notifier");
        field.setAccessible(true);
        Notifier notifier = (Notifier) field.get(m);
        assertNotNull("关闭后不能是 null：null 会让下一次通知惰性重建托盘", notifier);
        Field closed = Notifier.class.getDeclaredField("closed");
        closed.setAccessible(true);
        assertTrue("保留下来的必须是已关闭的实例", closed.getBoolean(notifier));
    }
}
