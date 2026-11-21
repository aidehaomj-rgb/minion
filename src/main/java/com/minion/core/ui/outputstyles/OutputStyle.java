package com.minion.core.ui.outputstyles;

/**
 * v2.4 §10.5 输出样式：frontmatter（name/description/keep-coding-instructions）+ body
 * （风格段，非默认样式注入系统提示——keep-coding-instructions=true 时保留编码指令段仅
 * 追加风格，false 时替换风格段；完整系统段替换随 §11.3 提示词重构接线）。
 * 优先级 built-in &lt; user（jarDir/.minion/output-styles）&lt; project
 * （workspace/.minion/output-styles）；同名高优先级覆盖。
 */
public final class OutputStyle {

    /** 内置默认（不改系统提示） */
    public static final String DEFAULT = "default";
    public static final String EXPLANATORY = "Explanatory";
    public static final String LEARNING = "Learning";

    public final String name;
    public final String description;
    public final boolean keepCodingInstructions;
    public final String body;
    public final String source;      // built-in / user / project

    OutputStyle(String name, String description, boolean keepCoding, String body, String source) {
        this.name = name;
        this.description = description;
        this.keepCodingInstructions = keepCoding;
        this.body = body == null ? "" : body;
        this.source = source;
    }

    /** hint（/output-style 列表用） */
    public String hint() {
        return "[" + source + "] " + name + " — " + (description.isEmpty() ? "无描述" : description);
    }

    static OutputStyle builtin(String name, String description, boolean keep, String body) {
        return new OutputStyle(name, description, keep, body, "built-in");
    }
}
