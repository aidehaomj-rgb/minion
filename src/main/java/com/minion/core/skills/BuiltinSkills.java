package com.minion.core.skills;

import java.util.ArrayList;
import java.util.List;

/** 针对 Win7 + Python 3.7 + Qwen 的内置工作流；不包含 OpenAI 专属功能。 */
public final class BuiltinSkills {
    private BuiltinSkills() { }
    public static List<Skill> all() {
        List<Skill> out=new ArrayList<Skill>();
        out.add(skill("data-analysis","Excel/CSV 数据清洗、统计、可视化与结果复核（使用本机 Anaconda pandas）",
                "处理 Excel/CSV 数据分析时：\n1. 先用 Python status 确认 pandas/openpyxl；用 Excel inspect 了解工作表和字段。\n"
              + "2. 先说明分析口径、缺失值、重复值和异常值处理；不要覆盖原文件。\n"
              + "3. 用 Python+pandas 完成清洗、聚合、透视、关联和图表，结果写入工作区新的 .xlsx/.csv/.png。\n"
              + "4. 复核行数、关键合计和输出文件是否存在，并简要报告口径与结论。Python 3.7 环境不得使用 3.8+ 语法。"));
        out.add(skill("skill-creator","把已经验证成功的分析或操作流程封装为可重复调用的本地 Skill",
                "当用户要求把本次过程保存、固化、封装成 Skill 时使用。\n"
              + "1. 先回顾本次任务中真正成功的步骤、输入字段、分析口径、工具/脚本、输出和复核方法；不要把失败尝试写成推荐步骤。\n"
              + "2. 选择简短的英文小写技能名（字母、数字、连字符），description 必须写清楚触发场景，让模型以后能自动匹配。\n"
              + "3. 用 SkillAdmin 的 capture 动作保存。content 应包含：适用场景、所需输入、执行步骤、校验规则、输出约定、常见异常；不得写入密码、令牌或真实敏感数据。\n"
              + "4. 可复用的 .py/.sql/.json/空白模板等放在当前工作区后，通过 resourcePaths（分号分隔）一起收进 assets；Skill 正文中说明如何使用这些相对资源。\n"
              + "5. 保存后调用 validate 校验，并告知用户：新建对话后生效，可用 /skills 查看、/skill <名称> 手动调用。"
              + "代码必须兼容 Win7、Java 8 和 Python 3.7.6。"));
        out.add(skill("sql-database","SQLite/SQL 数据库建模、查询、数据导入和结果校验",
                "处理数据库任务时：\n1. 先用 SQLite schema 查看结构；只读问题用 query。\n"
              + "2. 修改前说明影响范围，必要时备份数据库；建表/增删改用 execute。\n"
              + "3. 查询必须限制结果量，使用明确字段，避免 SELECT * 扫描大表。\n"
              + "4. 如目标是 MySQL/SQL Server/PostgreSQL，先检查本机 Python 驱动或可用 MCP，再通过 Python 连接；不要假设凭据。"));
        out.add(skill("knowledge-base","构建和维护完全离线的项目知识库，支持资料整理和检索",
                "构建知识库时：\n1. 明确资料范围、主题、来源和更新日期。\n2. 读取原始资料后按主题拆成短而完整的知识条目，使用 Knowledge add 保存，source 写原始路径。\n"
              + "3. 回答前先用 Knowledge search 检索，再用 read 查看命中全文；区分资料事实与推断。\n"
              + "4. 本知识库是工作区 .minion/knowledge 下的 Markdown 与本地关键词检索，不依赖网络或向量模型；大量 Office/PDF 可先分批提取和摘要。"));
        out.add(skill("browser-automation","登录网页、点击录入、抓取表格、截图和页面调试",
                "浏览器任务优先用 Browser 导航、BrowserEval 定位/点击/输入/提取，必要时 BrowserScreenshot 和 BrowserDebug 验证。\n"
              + "先读取页面状态再操作；元素选择器优先 id/name/aria-label，避免脆弱的深层 nth-child。提交、删除、付款等外部副作用操作前向用户确认。"
              + "对分页表格逐页提取并记录页码；登录态复用专用 browser.userDataDir。"));
        out.add(skill("web-development","在内网构建可离线运行的网页、后台服务和桌面可访问页面",
                "开发网页时先检查现有项目技术栈。Win7/Python3.7 优先使用兼容版本的 Flask/FastAPI(旧版)或纯 HTML/CSS/JS；不要默认安装最新依赖。\n"
              + "实现后启动本地服务，用 Browser 打开并实际检查关键交互、控制台和网络错误。交付启动/停止脚本、依赖清单和内网访问说明；不得擅自发布公网。"));
        out.add(skill("windows-packaging","在 Win7/Python3.7 环境制作 EXE、便携程序和离线依赖包",
                "制作 EXE 时先用 Python status 核对解释器和依赖。Python3.7 可优先 PyInstaller 5.13.x，避免要求 Python3.8+ 的新版。\n"
              + "构建使用独立输出目录，保留源文件；检查 hidden-import、数据文件、32/64 位和 Win7 API 兼容性。交付前在干净目录启动验证，并生成依赖/版本/排障说明。"));
        out.add(skill("app-development","规划和开发移动 App/PWA，明确 Win7 构建限制",
                "先区分目标：PWA、Android、iOS。Win7 可直接开发和验证 PWA；现代 Android/iOS 工具链通常不能在 Win7 完整构建。\n"
              + "可在当前机器生成跨平台源码和测试，Android 安装包应在兼容的较新 Windows/Linux 构建机生成，iOS 必须使用 macOS/Xcode。不要声称未实际构建的安装包已可用。"));
        return out;
    }
    private static Skill skill(String name,String desc,String body){return new Skill(name,desc,body,"builtin:"+name);}
}
