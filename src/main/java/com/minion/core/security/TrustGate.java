package com.minion.core.security;

/**
 * 信任闸门（v2.4 §10.9）：子进程启动前调用 {@link #ensureTrusted}——
 * 已信任放行；未信任/指纹变化先问（{@link Prompt}），批准即落 {@link ProjectTrustStore}，
 * 拒绝返回 false（调用方必须不启动子进程）。{@code trust.store.v2=false}（应急排障）时全放行。
 *
 * <p>Prompt 由装配方注入：GUI 用确认对话框，headless/后台用 {@link #DENY}。
 * 检查点必须在 ProcessBuilder.start 之前——「拒绝时子进程数量为零」是硬性验收项。
 */
public final class TrustGate {

    /** 信任批准询问（在调用线程同步执行；实现方负责 UI 线程投递） */
    public interface Prompt {
        /**
         * @param spec    待批准的可执行配置
         * @param reason  未信任原因（首次使用/内容已变化/存储损坏）
         * @param summary 变更摘要（命令 + 参数预览）
         * @return true=批准（落盘指纹并放行）
         */
        boolean askApproval(TrustedExecutable spec, String reason, String summary);
    }

    /** 一律拒绝（headless/无人值守装配） */
    public static final Prompt DENY = new Prompt() {
        @Override public boolean askApproval(TrustedExecutable spec, String reason, String summary) {
            return false;
        }
    };

    private final ProjectTrustStore store;
    private final boolean enabled;
    private final Prompt prompt;

    public TrustGate(ProjectTrustStore store, boolean enabled, Prompt prompt) {
        this.store = store;
        this.enabled = enabled;
        this.prompt = prompt == null ? DENY : prompt;
    }

    public ProjectTrustStore store() { return store; }
    public boolean enabled() { return enabled; }

    /** 启动前复核：true=可启动；false=必须不启动子进程 */
    public boolean ensureTrusted(TrustedExecutable spec) {
        if (!enabled) return true;                       // 应急排障（trust.store.v2=false）
        if (store.isTrusted(spec)) return true;
        ProjectTrustStore.CheckResult c = store.check(spec);
        if (prompt.askApproval(spec, c.reason, spec.preview())) {
            store.approve(spec);
            return true;
        }
        return false;
    }
}
