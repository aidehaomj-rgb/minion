package com.minion.core.agent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative, deterministic minimum evidence for clearly operational user requests. */
final class CompletionContract {
    private static final Pattern QUESTION = Pattern.compile("^(?:如何|怎么|为什么|能否|是否|可否|是不是|介绍|解释|说明|比较|对比|什么是|有哪些|what|how|why|can |is |explain|compare).*");
    private static final Pattern OPERATION = Pattern.compile("(?:开发|修改|修复|修好|改好|解决(?!方案)|落实|落地|补全|完善|生成|制作|构建|创建|搭建|写入|导出|下载|安装|克隆|运行|执行|删除|更新|交付|核对|验证|补齐|补上|实现|处理|读取|检查|比对|匹配|交叉验证|汇总|整理|完成(?:任务|整表|工作|报告|文件|全部|这些|当前|后续|交付)|分析.{0,20}(?:文件|表格|excel|数据|数据库)|build|create|implement|fix|modify|generate|export|install|clone|run )",Pattern.CASE_INSENSITIVE);
    private static final Pattern MUTATION = Pattern.compile("(?:开发|修改|修复|修好|改好|解决(?!方案)|落实|落地|补全|完善|生成|制作|构建|创建|搭建|写入|导出|下载|安装|克隆|删除|更新|补齐|补上|实现|交付(?:文件|代码|程序|网页|报告|表格|excel|项目)|build|create|implement|fix|modify|generate|export|install|clone)",Pattern.CASE_INSENSITIVE);
    final boolean action;
    final boolean mutation;

    private CompletionContract(boolean action, boolean mutation) { this.action = action; this.mutation = mutation; }

    static CompletionContract from(String goal, List<String> guidance) {
        CompletionContract current = single(goal);
        if (guidance != null) for (String item : guidance) {
            if(item==null||item.trim().isEmpty())continue;
            CompletionContract next=single(item);
            current=explicitScopeReplacement(item)?next:new CompletionContract(current.action||next.action,current.mutation||next.mutation);
        }
        return current;
    }

    /** 只处理明确缩小/替代范围的措辞；普通补充不能静默删掉原要求。 */
    static boolean explicitScopeReplacement(String text) {
        if(text==null)return false;
        String value=text.trim().toLowerCase(Locale.ROOT);
        boolean laterWork=value.matches("(?s).*(?:然后|之后|再|并且).*(?:开发|修改|修复|生成|创建|执行|写入).*" );
        return !laterWork && (value.matches("(?s)^(?:(?:现在|这次|本次|接下来)?(?:任务|目标|要求)?(?:改为|改成|改做)|取消(?:之前|原来|当前|这个|本次|这项).*(?:任务|开发|修改)).*")
                || value.matches("(?s).*(?:只|仅)(?:回答|解释|分析|检查|核对|查看|阅读|给出方案|给出建议|告诉我结论).*"));
    }

    private static CompletionContract single(String goal) {
        String source = goal == null ? "" : goal.trim();
        if (source.isEmpty()) return new CompletionContract(false, false);
        String lower = source.toLowerCase(Locale.ROOT);
        // Questions about how to do work are not permission to perform it.
        boolean chainedWork=lower.matches("(?s).*(?:然后|再|并|同时|之后).*(?:开发|修改|修复|完善|生成|创建|实现|build|fix|implement).*" );
        if (!chainedWork && (QUESTION.matcher(lower).matches() || lower.matches("(?s).*(?:需要|有哪些|是什么|是什么样的)(?:什么)?(?:步骤|方法|方式|条件|差距|风险).*")))
            return new CompletionContract(false, false);
        if (lower.startsWith("只解释") || lower.startsWith("只回答")) return new CompletionContract(false, false);
        String operative=withoutProhibitions(lower);
        boolean graphic = operative.matches("(?s).*(?:绘制|绘图|画图|出图|渲染|画.{0,8}(?:流程图|信息图|图表|封面|海报)).*");
        boolean action = graphic || OPERATION.matcher(operative).find();
        boolean mutation = action && (graphic || MUTATION.matcher(operative).find());
        return new CompletionContract(action, mutation);
    }

    private static String withoutProhibitions(String text) {
        StringBuilder out=new StringBuilder();
        for(String clause:text.split("[，,。；;\\r\\n]")) {
            String part=clause.trim();
            if(part.matches("^(?:不要|不需要|不用|无需|禁止|别|切勿|取消(?:之前|原来|当前|这个|本次|这项)).*"))continue;
            out.append(part).append(' ');
        }
        return out.toString();
    }
}
