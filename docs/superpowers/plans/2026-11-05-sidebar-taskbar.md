# Sidebar resizing and taskbar minimization

Implemented after the A-icon desktop entry work:

- Replaced the fixed 25/75 GridPane with an independent horizontal SplitPane. Default 25%, sidebar minimum 180px, chat minimum 360px. Sidebar width stays fixed when the parent is resized where space permits. Visible 6px draggable divider with hover feedback; no changes to the report split.
- Main undecorated Stage receives WS_MINIMIZEBOX and WS_SYSMENU after showing. Existing style bits, custom title bar, size, focus and z-order are preserved. No hooks, polling, registry/taskbar pin edits or agent cancellation. Owned dialogs are not modified.
- "Click icon" means the normal running taskbar button: Shell minimizes the active window, restores a minimized window, or foregrounds an inactive one. Desktop shortcut repeated-launch/single-instance routing is not part of this change.

Validation: 13 focused JUnit tests pass. tools/desktop-app/SidebarTaskbarSmoke.java shows an isolated Java8 undecorated test window, verifies actual sidebar width changes after moving its divider, checks native style bits, sends SC_MINIMIZE and verifies the real HWND is iconic, then restores it. This does not simulate a taskbar click. Evidence: target/sidebar-taskbar-tests.log and target/sidebar-taskbar-smoke.log.

Native API basis: https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setwindowlonga and https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setwindowpos . GWL_STYLE is a 32-bit LONG on x64 too; frame changes are applied with SWP_FRAMECHANGED.

Remaining manual acceptance: real taskbar-button clicks on Win7, active model task continuing during minimize/restore, and interaction with modal dialogs. No existing installation changed, no new update ZIP and no GitHub push. The previous full-suite 1762-test report belongs to the earlier A-icon revision; this revision ran the 13 focused tests, not another full suite. The ready-directory JAR is rebuilt after focused validation; the earlier desktop-app-validation-20261105.json JAR hash is a historical snapshot, not the new build hash.
