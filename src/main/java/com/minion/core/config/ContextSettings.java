package com.minion.core.config;

import com.minion.core.context.ContextManager;

/** Strict parsing shared by model settings validation and trigger preview. Stored ratio stays 0..1. */
public final class ContextSettings {
    public final int window, output, keep, stable;
    public final double ratio;
    private ContextSettings(int window, int output, int keep, double ratio, int stable) {
        this.window=window; this.output=output; this.keep=keep; this.ratio=ratio; this.stable=stable;
    }
    public static ContextSettings parse(String window, String percent, String output, String keep) {
        return parse(window,percent,output,keep,"0");
    }
    public static ContextSettings parse(String window, String percent, String output, String keep, String stable) {
        try {
            int w=Integer.parseInt(window.trim()), o=Integer.parseInt(output.trim()), k=Integer.parseInt(keep.trim()), s=Integer.parseInt(stable.trim());
            double p=Double.parseDouble(percent.trim());
            if(w<8192) throw new IllegalArgumentException("上下文窗口至少8192 token");
            if(o<256 || o>65536 || o>=w) throw new IllegalArgumentException("单次输出需为256~65536 token，且小于上下文窗口");
            if(Double.isNaN(p)||Double.isInfinite(p)||p<=0||p>100) throw new IllegalArgumentException("自动压缩阈值需大于0且不超过100（百分比，例如90）");
            if(k<0) throw new IllegalArgumentException("保留近期消息数不能为负数");
            if(s!=0 && (s<8192 || s>=w)) throw new IllegalArgumentException("稳定工作区需为0（默认150K）或8192到上下文窗口以下");
            return new ContextSettings(w,o,k,p/100.0,s);
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("窗口、输出、消息数和稳定工作区请填写整数；压缩阈值填写百分比，例如90或95");
        }
    }
    public int triggerTokens() { return Math.min(stable==0?ContextManager.DEFAULT_SOFT_TRIGGER_TOKENS:stable,
            ContextManager.triggerAtTokens(window,ratio,output)); }
}
