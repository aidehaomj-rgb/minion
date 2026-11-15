package com.minion.core.agent;

/**
 * 回复启发式分类器（中文为主）：判定一段回复是「中间进度/待用户输入/完成」的纯函数集合。
 * 从 AgentLoop 抽出为独立无状态类——StopGate / CompletionEvidence / SubAgentLoop 原先
 * 反向依赖 AgentLoop 上的这些函数，抽离后完成门禁各组件不再依赖主循环类。
 * 全部为 static 纯函数、零字段依赖；正则语义与抽出前逐字一致。
 */
final class ReplyHeuristics {

    private ReplyHeuristics() {}

    static final String TASK_COMPLETE_MARKER = "[[MINION_TASK_COMPLETE]]";

    /** 只匹配句尾主动计划；有限修饰词避免“做全面比对”等变体漏判。 */
    static final java.util.regex.Pattern TERMINAL_COMPARISON_PLAN = java.util.regex.Pattern.compile(
            "(?:^|[。！？!?]\\s*)(?:让我|我(?:将|会|准备|打算)|现在(?:我)?|接下来(?:我)?(?:会|将)?|下一步(?:我)?(?:会|将)?|还需要)"
            + "(?:再|先|立即|现在|继续|开始|做(?:一次|一个|一轮|一遍|一份)?|进行|全面|完整|逐项|逐行|详细|进一步|实际|着手){0,8}"
            + "(?:(?:比对|对比|比较|匹配)(?!结果|结论|情况|报告|已|完成|完毕|结束)|交叉验证|编写|编制|写入|制作|核验|查验|检查|核对|验证|分析|处理|执行|生成|读取|查看|补充|复核|确认|排查|扫描|派发|汇总)"
            + "[^。！？!?]*[：:，,。.!！]*$");

    static boolean hasTaskCompleteMarker(String text) {
        return text != null && text.trim().endsWith(TASK_COMPLETE_MARKER);
    }

    static boolean looksLikeInterimAnswer(String text) {
        if (text == null) return false;
        String s = text.trim().replace('\r', ' ').replace('\n', ' ');
        if (s.isEmpty() || hasTaskCompleteMarker(s)) return false;
        if(!hasConcreteHumanDependency(s,"") && s.substring(Math.max(0,s.length()-240))
                .matches("(?s).*(?:下一步|接下来|我需要|需要|准备|将会).{0,18}(?:向用户|向您|向你).{0,8}(?:提问|询问|确认)[^。！？!?]*[。.!！]*$")) return true;
        String actionTail=s.substring(Math.max(0,s.length()-240));
        if(!actionTail.matches("(?s).*(?:建议|如需|可以考虑)[^。！？!?]{0,35}(?:下一步|后续).*" )
                && actionTail.matches("(?s).*(?:下一步|接下来|我还需要|我需要|然后我会|随后我会|现在继续).{0,24}"
                +"(?:做|完成|执行|实现|开发|修改|更新|验证|测试|核对|检查|生成|编写|制作|提问|询问)"
                +"[^。！？!?]*[。.!！]*$"))return true;
        if (hasToolActionPromise(s)) return true;
        // Long replies can begin with the next concrete operation and then describe
        // background for many paragraphs; checking only the tail missed such turns.
        String head=s.substring(0,Math.min(180,s.length()));
        if(head.matches("(?s)^(?:我(?:先|现在|接下来)|接下来|下一步|继续(?:追加|完成|生成|核对|处理|执行|补充|编写|制作))[^。！？!?]{0,150}.*")
                && !looksLikeUserBlock(s)) return true;
        // 长篇分析后以“让我再查验…”结尾仍是进度回复。只检查末尾，避免把正文中
        // 提到的计划误当成未完成；原有短回复规则继续保持保守。
        if (s.length() > 240) {
            String tail = s.substring(Math.max(0, s.length() - 200));
            return endsWithActionPlan(tail);
        }
        if (endsWithActionPlan(s)) return true;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("我先") || lower.startsWith("先看")
                || lower.startsWith("接下来") || lower.startsWith("下一步")
                || lower.startsWith("正在") || lower.startsWith("准备")
                || lower.startsWith("好的，继续") || lower.startsWith("好，继续")
                || lower.matches("^继续[。！!，, ]*.*(?:现在|接下来|下一步)(?:要|将|先|开始|继续|立即)?(?:补齐|检查|核对|验证|分析|处理|执行|生成|读取|查看).*")
                || lower.matches("^(?:好的?，?继续[。！!，, ]*)?(?:现在|接下来|下一步)(?:要|将|先|开始|继续|立即)?(?:补齐|检查|核对|验证|分析|处理|执行|生成|读取|查看).*")
                || lower.matches(".*(?:我(?:还)?需要先|我接下来(?:会|将)|随后我会|然后我会)(?:确认|检查|核对|验证|分析|处理|执行|生成|读取|查看|补充|继续).*")
                || lower.matches("^(?:let me|i will|i'll|next,? i).*(?:check|verify|analy[sz]e|continue|run|read|inspect).*")
                || lower.matches(".*(?:找到|发现|检测到).{0,40}(?:个)?(?:文件|表格|工作簿|数据源)[了。！!]*$")
                || lower.endsWith("继续处理") || lower.endsWith("继续分析");
    }

    /** 只识别末尾明确的下一步意图；不因已交付正文中提及过去计划而续接。 */
    static boolean endsWithActionPlan(String text) {
        int colon=Math.max(text.lastIndexOf('：'),text.lastIndexOf(':'));
        if(colon>=0&&text.substring(colon+1).matches("(?s).*(?:这里|这是|该句|这句话|意思是|表示|说明|不执行|无需执行|不是要).*")){
            return false;
        }
        if (TERMINAL_COMPARISON_PLAN.matcher(text).find()) return true;
        if (text.matches("(?s).*(?:先|准备|计划|将)(?:登记|确认|检查|核对|构建|生成|处理|分块)[^。！？!?]{0,160}(?:再|然后|后)(?:登记|生成|处理|派发|执行|写入|开始)[^。！？!?]*[。.!！]*$")) return true;
        return text.matches(".*(?:让我(?:再|先)?|我(?:再|先|将|会)|继续(?:推进|执行|处理|派发)[。！!，, ]*(?:先|再)?"
                + "|接下来(?:我)?(?:会|要|将)?(?:先|再)?|下一步(?:我)?(?:会|要|将)?(?:先|再)?"
                + "|现在(?:我)?(?:会|要|将)?(?:先|再)?|还需(?:要)?|需要继续)"
                + "(?:查验|检查|核对|验证|分析|处理|执行|生成|读取|查看|补充|复核|确认|排查|扫描|派发|汇总)"
                + "[^。！？!?]*[：:，,。.!！]*$");
    }

    /** 工具调用的口头承诺不是执行事实；仅识别靠近末尾的主动意图。 */
    static boolean hasToolActionPromise(String text) {
        String tail = text.substring(Math.max(0, text.length() - 300));
        return tail.matches("(?i).*(?:我(?:现在|立即|将|会)(?:实际)?(?:调用|使用)|我实际(?:调用|使用)|先(?:调用|使用)|立即(?:调用|使用)|让我(?:先)?(?:调用|使用))"
                + "(?:bash|python|read|write|edit|task|todowrite|agentcontrol|工具)[^。！？!?]*"
                + "(?:[。.!！][ ]*(?:执行工具调用|现在|执行|good|execute)[。.!！]*)*[。.!！]*$");
    }

    /** 旧措辞诊断与测试兼容；生产waiting_user由统一结束核验决定。 */
    static boolean looksLikeUserBlock(String text) {
        if (text == null || hasTaskCompleteMarker(text)) return false;
        String s = text.trim().replace('\r',' ').replace('\n',' ');
        return s.matches(".*(?:缺少|缺失|无法访问|找不到|尚未提供|需要您|需要你|等待您|等待你|等待用户)[^。！？!?]*"
                + "(?:请(?:您|你)?(?:先)?(?:提供|上传|补充|选择|确认|授权)|才能继续|后(?:才能|再)继续|确认|授权)[^。！？!?]*[。.!！?？]*$")
                || s.matches(".*(?:请(?:您|你)?(?:先)?(?:提供|上传|补充|选择|确认|授权))[^。！？!?]*"
                + "(?:才能继续|后(?:才能|再)继续|等待(?:您|你|用户)(?:回答|确认|授权))[^。！？!?]*[。.!！?？]*$")
                || s.matches("(?i).*(?:waiting for (?:your|user)|need your) (?:input|confirmation|approval|permission).*" );
    }

    static boolean hasConcreteHumanDependency(String text,String blocking) {
        if(text==null)return false;
        String s=text.trim();
        if(blocking!=null&&!blocking.isEmpty() && s.matches("(?s).*(?:权限不足|没有权限|无法访问|未授权).*"))return true;
        return s.matches("(?s).*(?:请(?:您|你)?(?:提供|上传|补充|选择|确认|授权)|麻烦(?:您|你)?(?:提供|上传|确认)|能否(?:提供|上传|确认|授权))[^。！？!?]{1,140}.*")
                || s.matches("(?s).*(?:请问|能否|是否).{1,120}(?:资料|文件|路径|数据|权限|授权|方案|范围|选择)[^。！？!?]*[？?].*")
                || s.matches("(?is).*(?:please (?:provide|upload|confirm|authorize)|could you (?:provide|upload|confirm|authorize)).*");
    }
}
