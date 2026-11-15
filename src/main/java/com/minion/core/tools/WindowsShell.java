package com.minion.core.tools;

/** cmd /s strips the first and last quote of /c. A quoted executable therefore needs
 * an outer pair, otherwise JDK8 joins its path with the first quoted argument.
 * This is command-line framing only, not sanitization/authorization or an OS sandbox. */
public final class WindowsShell {
    private WindowsShell(){}
    public static String frame(String command){
        String start=command.trim();
        return start.startsWith("\"")&&!start.startsWith("\"\"")?"\""+command+"\"":command;
    }
}
