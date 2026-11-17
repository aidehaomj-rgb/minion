# minion

2026-11-16 第二批主流对齐优化与四主题质感增强（V68 小更新）：[设计文档](docs/superpowers/specs/2026-11-16-第二批主流对齐优化-design.md)。Grep/Glob 尊重 .gitignore、恒跳 .git 与二进制；ripgrep 13.0.0（Win7 兼容）作为受 SHA-256 校验的 JAR 资源随程序分发，首次使用自解压到 `.session/tools`，不可用时无感回退 Java 遍历；ripgrep 搜索默认 120 秒整体截止，用户停止会强杀进程，不再因超大目录/网络盘无限等待。token 校准向上立即/向下平滑并按模型指纹持久化，图片按真实尺寸粗估；高危命令白名单支持前缀规则（如 `del /q`）窄化确认粒度，旧条目不收紧；完成裁决抽出 StopAdjudicator（纯机械搬移，特征化 A/B 验证）。全量回归 2339 项通过（0 失败、38 环境跳过）。

2026-11-16 第一批主流对齐优化（开发完成，未打包）：[设计文档](docs/superpowers/specs/2026-11-16-第一批主流对齐优化-design.md)。系统提示词领域规则迁为内置技能按需加载；瞬时错误重试改指数退避+抖动并尊重 Retry-After，非 2xx 响应体全文落诊断日志；Edit 匹配失败附最近匹配行号提示。全量回归通过。

V48 本地开发完成：[成果预览、文件交付与引导回执](docs/V48-成果预览与文件交付-开发说明.md)、[验收结果](docs/V48-开发验收结果.md)。MD/DOCX离线内容预览、多成果切换、可点击文件链接及定位/复制/另存为；立即引导提供未读/已读及发送阶段，已读仅表示特定主请求收到有效响应。完整回归2070项，0失败、3跳过；尚未制作V48更新包或进行真实Win7验收，原V47交付ZIP未变。

已打包：[V47任务运行与上下文优化小更新](docs/releases/V47-任务运行与上下文优化小更新.md)，支持V40–V46直接更新；保留配置、会话、项目及Java/Python环境。包约6.8MiB，包含更新程序与安装说明，七种基线、回退和最终ZIP解压更新已验证。

V47 开发记录：[ZCode参考优化与验收边界](docs/V47-ZCode参考优化-开发与验收.md)、[最终验收记录](docs/V47-开发验收结果.md)。本轮完善进程/JDBC外部作业事件等待、进展续接账本、压缩后当前工作集、技能退场、能力驱动工具调度、资源锁与中文多查询召回；保持150K完整摘要目标。后续已按用户请求打包上述小更新；MaxCompute专用适配和真实Win7/内网模型验收尚未完成。

V44 小更新：[压缩断点续接与本地绘图](docs/releases/V44-压缩续接与本地绘图小更新.md)。同一包适配 V40/V41/V42/V43，自动识别基线并备份，只替换 JAR 差异，保留配置、会话、项目与运行环境；默认150K阈值不变。

已打包：[V45小更新说明](docs/releases/V45-任务记忆与多Agent小更新.md)，支持已核验V40–V44；5.22MiB，保留配置/会话/项目，自动备份。安装目录名不要以`!`结尾。新增版本化步骤、进展续跑、真实测试、来源失效记忆、能力探测、按需工具、异步子任务及成果核验；回答提问支持图文/仅图。8批计划仍有未收尾项，见[任务清单](docs/superpowers/plans/2026-11-04-agent-gap-roadmap.md)与[验收状态](docs/superpowers/plans/2026-11-05-eight-batch-integration-status.md)；本机通过不等于真实Win7/内网模型已验收。

V44 包含[压缩后当前断点续接](docs/superpowers/specs/2026-11-04-task-scoped-compaction-design.md)和[本地绘图 Artwork](docs/本地绘图与模型能力.md)。执行中压缩不再自动回读历史；直接交接当前任务、最新修改、清单和最近工具回执。本地绘图无需新服务/依赖，输出可编辑 JSON、SVG 与高清 PNG；聊天模型仍不是专用照片生图模型。

V43 完整离线整合：[Win7 环境与兼容修复](docs/releases/V43-完整离线环境整合版.md)。包含 V42 功能，保留 R2 的包内 Java/Python 和进程清理兼容修复，移除界面个人联系方式。完整包无需逐个安装旧版小更新。

V42 启动性能优化：[按需加载与事件尾部恢复](docs/releases/V42-启动按需加载与事件尾部恢复.md)。启动只加载轻量会话索引，打开会话才恢复正文/工具/客户端；不再每次全量读取和校验所有旧事件。首次建立索引后复用缓存，原始记录不删除。V42 可直接从 V40 更新，已包含 V41 结束绿灯修改。

最新离线更新：[V30 统一结束门禁](docs/releases/V30-统一结束门禁更新.md)。正常回复结束不直接算任务完成，独立只读核验当前目标与实际证据；未完成则有界续接，未知/等待用户明确暂停并保留队列。旧关键词、字数和完成标记不再拥有完成放行权；包含V29及此前改动，保留压缩配置。

上一版离线更新：[V29 完成收尾与完成时间](docs/releases/V29-完成收尾与时间更新.md)。包含V28全部内容，修复旧断点哈希与状态查询造成的反复收尾，完成回复自动附北京时间；保留未完成任务保护和已有配置。

开发中：主任务通过完成校验后，程序自动在回复附“完成时间：yyyy-MM-dd HH:mm:ss（北京时间）”，历史与报告保留；阶段回复、中断及被阻塞的完成声明不添加。暂不打包。

开发中：修复完成声明因旧断点哈希阻塞而反复续跑。AgentControl status显示实时差异；refresh_checkpoint需复核说明并重新采集完整文件快照。收尾恢复独立计数，不因状态查询清零；缺失文件、待办和子任务保护保留。本次尚未打包。

已发布：[V28：V27之后的合并更新](docs/releases/V28-V27后合并更新.md)，包含下述流式保护、默认模型、历史分页及选择性迁移。

开发中：已加入主/子Agent单次流式工具承诺循环检测，提前结束当前请求并有界恢复，避免等待输出上限后继续重复；不改输出预算、压缩阈值和权限。暂未发布更新包。

开发中：设置→模型新增“新会话默认模型”选择，选择后立即保存，仅影响新建会话。重新打开旧会话仍使用该会话保存的模型；输入框模型按钮继续只影响当前会话。

开发中：历史页增加至每页150条可见内容，连续子任务输出合并折叠展示，轮次结束标记不占分页预算；修复执行中历史页仅有导航栏的问题。完整记录保留，暂未打包。

开发中：选择性迁移 aacw1/minion e052ea1 的并发文件保护（Write/Edit 路径锁与临时文件替换）、子Agent独立消息通道、Read full及数据库大字段原样导出。保留检查点、append、越界确认、历史分页和本地压缩配置；不整仓库覆盖，暂未打包。

最新离线修复：[V27 中断误触与工具动作恢复](docs/releases/V27-中断误触与工具动作恢复.md)。发送键不再误触停止；连续只说工具执行计划时明确关闭思考并升级工具动作请求，保留协议兼容回退与原权限确认。长文本完整分页和复制全文，思考结束自动收起；数字选项回答附带对应含义。包含 V23～V26，保留现有模型、压缩配置和历史。

win7&jdk8 本地 Agent，使用 Java 8 开发，面向 Qwen 等 OpenAI Chat Completions 兼容模型。含子 agent、MCP 工具扩展（stdio/SSE/Streamable HTTP）、代码开发、浏览器自动化、Anaconda/Python、Excel/Office、SQL 数据库、本地知识库、项目记忆、上下文压缩、会话管理、离线更新、风险审计和 Skills。

## 启动（GUI）

    java -jar minion-0.1.0.jar   # 双击 jar 或命令行运行；jar 自举，无需 bat

图形界面为唯一界面（CLI 已移除）。需要 JDK 8 且自带 JavaFX：Oracle JDK 8 或 Zulu/AdoptOpenJDK 8 含 OpenJFX 的发行版；Win7 用户注意 Win7 只支持到 8u251 之前的 Oracle 版本。

jar 自举行为（启动器内置，双击 / 命令行同样生效）：

- 双击 jar（javaw、无控制台）默认隐藏控制台：javaw 直启无多余窗口；排障需看日志时，在 jar 同目录 `config.properties` 设 `boot.console=true` 恢复开窗
- 终端 `java -jar` 启动：命令行窗口不隐藏（启动器只在无控制台场景才改用 javaw；mintty/Git Bash 因 `System.console()==null` 误判为无控制台，`boot.console=true` 时仍会额外开窗——真实 Windows 控制台 cmd/PowerShell 无此问题）
- 当前 JVM 非 JDK 8 时自动切换：按 `MINION_JAVA` → `JAVA_HOME` → 常见 JDK 8 安装位置的顺序探测含 JavaFX 的 JDK 8 并重启，全程无感
- 找不到 JDK 8 但当前 JVM 能运行 → 照常启动并弹窗提示建议安装 JDK 8（可关闭，不影响使用）；当前 JVM 连 JavaFX 都没有 → 错误弹窗并退出
- 渲染管线默认 `es2,sw`（OpenGL 硬件加速优先，失败自动回退软件渲染）：JDK 8 的 D3D 管线在 VM/低端显卡上，消息区切页签等节点突发后设备状态损坏，渲染线程每帧抛 `D3DTexture.getContext` NPE（刷屏+界面卡死，2026-08-16 实证），故默认候选**不含 d3d**；旧默认纯软件渲染（`prism.order=sw`）在 4K 屏上全局卡顿（悬停/打字慢 1 秒，2026-08-17 实测），es2 在集成显卡上不可用时自动回退 sw 与旧行为一致（候选列表须逗号分隔，空格会被当作单一管线名导致启动崩溃）。手动覆盖：`set MINION_PRISM=d3d|es2|sw` 再启动
- 环境变量 `MINION_JAVA`（java.exe 全路径）优先于一切探测，显式指定即信任

首次运行在 jar 同目录自动生成 `config.properties`、`workspace.json`、`model.json`（MCP 服务器配置 `mcp.json` 在设置窗首次保存时生成）。

## 配置文件（jar 同目录）

| 文件 | 内容 |
|---|---|
| `workspace.json` | 工作空间（名称、项目路径 workDir、项目主说明文件 projectMd、项目级技能路径 projectSkillsDir）；界面「＋ 新建工作空间」创建（名称与项目路径必填且须是已存在文件夹，另两项可选、填了才校验：主说明文件须是已存在文件、技能路径须是已存在文件夹；均可浏览选取）；首次启动无本文件时生成的 default 空间只填项目路径 `.`，主说明文件与技能路径留空 |
| `model.json` | 模型配置（多模型：url/apiKey/modelName/provider/thinking/maxContextTokens 等）；设置窗「模型」页管理 |
| `config.properties` | python.path（Anaconda/Python）、permission/confirm（权限模式与高危确认）、context（输出/读取策略）、agent（工具空输出占位）、paths（空间外读/写）、skills.dir（技能目录）、boot.console（自举控制台窗口开关）；设置窗「基础设置」页可改并可浏览选择 Python 和技能目录；浏览器改由 `tools.json` 管理 |
| `mcp.json` | MCP 服务器列表（名称/传输/命令/参数/环境变量/URL/请求头/启用开关）；设置窗「MCP」页管理（列表+状态点+启用开关+新建/编辑/删除/重连） |
| `tools.json` | 可插拔工具配置：`browser`（路径/端口/用户数据目录/无头/超时 + 启用）、`mysql`/`postgresql`/`oracle`（启用 + 数据源列表 + 当前选中）、`ssh`（启用 + 连接列表 + 当前选中）；设置窗「工具」页管理，改动即落盘、全局会话下一轮生效 |

工作空间弹窗（新建/修改）各字段的填写要求与含义：

- **名称**：必填，不能与已有空间重名（`WorkspaceManager.isValidName`）。
- **项目路径**：必填，且**必须是已存在的文件夹**——`WorkspaceManager.add` / `update` 拒绝空白或
  指向文件/不存在的路径（返回 false、不落盘、原配置不变）。它是会话的工作目录，也是文件工具与
  Bash 的守卫边界；已落盘配置若被手改成空或无效路径，读取时按软件所在目录兜底
  （`WorkspacePaths.workDirAbs`，对不可信外部输入的容错）。
- **项目主说明文件**：可选。内容作为「项目介绍」注入系统提示词；**留空 = 不注入**（不再隐式读 `<项目路径>/project.md`）。填了则必须指向一个已存在的文件，指向不存在的文件或文件夹时，界面点「确定」弹框报错、core 的 add/update 也一并拒绝。
- **项目级技能路径**：可选，**填了就必须是已存在的文件夹**（core 同样拒绝非目录；相对写法按该空间
  项目路径解析）。递归扫描该目录下所有 `SKILL.md` / `*.skill.md`，以 `[项目]` 标注并入
  系统提示词的可用技能清单，可用 `/skill <名>` 渐进式加载正文；同名时**项目级覆盖内置**。
  留空即只有内置技能（`skills.dir`）。三项路径修改都**只对新建会话生效**。
- 界面校验反馈：名称/项目路径未填时「确定」按钮置灰；填了但不是文件夹时点「确定」会**弹出错误框**
  说明是哪个路径、并要求填写已存在的文件夹，点「确定」回到本表单继续改（表单不关闭）。

## 快捷操作

- 发送键两种模式（基础设置「发送键」勾选即生效，默认开启）：默认 Enter 发送、Ctrl+Enter 换行；取消勾选后 Ctrl+Enter 发送、Enter 换行；Esc 关闭补全弹层 / 终止当前运行
- `@` 引用工作空间文件：按文件名反显（↑↓/鼠标选择、滚轮滚动），Enter/Tab/鼠标点击把 `@路径` 内联进输入框（所见即所得，非输入块）；列表跳过 .gitignore 忽略与点目录（无条数上限、字典序），每工作空间独立缓存 5 分钟——首次打开会话即后台预热扫描，切回项目直接命中；缓存过期时先用旧列表即时显示、后台异步刷新后免闪替换（新建文件最迟 5 分钟后可见）
- 补全确认反显为输入块：弹层选中的 /命令、/技能 与粘贴的大于 1000 字符长文本变为输入框上方不可编辑块，块右上角关闭按钮或空输入时 Backspace 删除；长文本粘贴在光标处插入「[粘贴块N]」占位符，发送时占位符原位展开为全文（落位 = 光标位置）；其余块与文本按顺序组合（/命令仍须在消息开头）
- `/` 斜杠命令与技能补全：/help /skills /skill <名> [参数] /compact /tokens；`/skill ` 后按技能名过滤，命令后尾随文字作为技能参数（以「用户参数: 」紧跟 `<skill>` 技能块之后注入）；命令由客户端本地执行，结果以系统行显示在聊天区，不发给模型
- `/capabilities`：显示 Python、Excel、SQLite、知识库、浏览器和开发构建能力入口
- `/diagnostics`、`/logs`、`/update`、`/checkpoints`：环境诊断、脱敏日志、离线更新检查和文件修改恢复点
- `/queue add <任务>`：当前任务完成后按 FIFO 自动执行；`/queue list|remove|clear` 管理队列
- `/sessions [关键词]`：搜索会话；`/session pin|archive|fork|export|import` 管理会话
- `/context`、`/memory`、`/permissions`、`/secrets`：上下文、项目记忆、权限策略和加密密钥库入口
- 设置（左侧栏底部齿轮）：左列导航（基础设置 / 文档/OCR / 模型 / MCP / 工具 / 关于）；模型页单击仅选中模型（查看配置用「修改」），选中后点「激活」按钮切换，选中已激活模型时按钮置灰；切换/修改参数即时生效（运行中会话下一轮生效）；基础设置页底部按钮栏「应用」（保存不关窗）与「关闭」
- 右侧工作区只展示本会话的用户成果，不提供项目文件浏览器；执行记录、引用文件、源代码、配置和临时文件不进入成果列表。默认用紧凑成果卡片展示，图片提供缩略图画廊，同一文件保留最多 20 个内部历史快照。Markdown、DOCX、TXT、CSV、PDF 首页、图片可直接预览；PPTX 优先经 LibreOffice 显示前 6 页，缺少转换器时回退文字；HTML 使用禁用脚本和外部资源的离线静态视图
- 界面主题：基础设置顶部可选择「深夜黑 / 石墨灰 / 雾蓝灰 / 明亮浅色」，切换立即生效并写入 `ui.theme`；新安装和旧配置缺省使用深夜黑，所有主题均仅使用 Java 8 + JavaFX 8 样式能力
- 默认执行方式（主流 Agent 对齐）：用户直接提问或下达任务，Minion 自行决定直接回答、调用工具或多步执行；普通模式下，模型给出实质答复即回合结束——本回合已有成功工具结果时，产出型任务同样直接交付，不再二次盘问文件证据。防懒网只拦“整轮零工具+纯计划承诺”的回复（无进展预算 4 次后显式暂停）；副作用未知（安全）、未核对子任务、明确缺少用户资料仍会优先阻断。目标/审查模式仍交给独立核验。`/plan`、`/goal`、`/review` 是可选高级命令，分别用于只读计划、持续目标和独立审查，系统不会主动要求用户选择模式。聊天区隐藏 `AgentControl`、`TodoWrite`、`DiscoverTools`、`Context`、`Skill` 等内部登记，只显示用户需要的结论、错误、审批、真实工具动作和交付
- 连续重复 `checkpoint` / `refresh_checkpoint` 时，若当前断点内已有稳定、未变化的用户成果，系统会停止重复登记，直接生成成果链接并完成交付；没有可核验成果时才暂停为控制循环错误
- 打开网页时，已配置 Browser 工具优先用 `Browser(action=open,url)` 一次完成导航，后续读取和点击继续使用 BrowserAction；只需要交给系统浏览器时使用 `OpenUrl(url)`。同一轮对同一 URL 的重复 `OpenUrl/Browser open` 或 Bash `start/Start-Process/chrome.exe` 启动会被直接阻止，避免重复弹窗和重复标签页
- 无会话时直接发送自动新建会话；发送后输入框自动清空
- 消息区发送消息强制置底；新内容增长时贴底自动跟随，向上翻过半屏暂停、翻回底半屏恢复
- 每轮回复结束显示 token 统计行（计时器图标 · 耗时 · in/out/thinking 会话累计 · ctx 上下文占比）
- 切换消息页签不重建消息区：会话视图缓存 + 增量重放（切回秒开、滚动位置保留）；长会话显示层截断保活 200 段（滚动不卡，历史头部自动收起）
- 侧栏悬停会话项显示操作按钮（重命名 / 删除）、工作空间项（修改 / 删除，重命名并入修改弹窗），移开隐藏；会话项非悬停显示最近消息时间（如 1m/5m/3h/2d，60 秒周期刷新）
- 工作空间可拖拽排序（顺序持久化，重启保持）
- 关闭会话页签 = 仅关闭页签不删除会话（运行中弹确认、确认后中断运行）；关闭后再从左侧点击会话会重新加载；删除会话/切换工作空间后右侧自动清空
- 高危操作确认卡片：右侧底部两行紧凑小卡弹出（距底 1 行），Enter 同意 / Esc 拒绝，点遮罩或侧栏不关闭；点击结果即决策，无超时判拒
- 启动懒加载：不自动打开任何会话，右侧空白占位；点击左侧会话（或新建/发送）才加载并出现页签；页签与工作空间无关，切换工作空间页签保持不变，点击旧空间页签自动切回该空间
- 关闭窗口时若有会话仍在运行会弹确认
- 运行中补充：模型运行时输入框有内容 → 发送按钮变为补充箭头，点击即把内容注入正在进行的对话（不中断流程），消息带「⤒ 运行中补充」标识
- AskUserQuestion 提问：模型需要用户信息时会调用 AskUserQuestion 工具提问，消息区显示问题与选项列表；畸形参数会自动容错，连续失败会停止重试并提示用户
- 附件上传：回形针可选图片及文档/表格/代码/压缩包等文件，也可把资源管理器文件拖入窗口或从剪贴板粘贴文件/截图；图片随视觉协议发送，普通文件以工作区路径交给 Document/Files/Excel 等工具读取

## 2026-08-28 全功能完善

- 稳定性：Qwen 工具参数 JSON 容错、滚动脱敏日志、环境诊断、显式后台队列、文件检查点恢复、离线更新包校验
- 办公数据：Excel 的 profile/filter/write/append/merge/pivot/chart/export；DOCX/PPTX/PDF 提取与生成、PDF 合并拆分；SQLite/ODBC/SQL Server/MySQL/PostgreSQL 查询导出；知识库文件夹增量同步
- 开发工作台：Git、目录树/搜索/大文件分段及摘要缓存、后台进程、六类项目模板、Maven/Python/npm 自动测试构建与服务启动
- 浏览器：元素等待、网络空闲、上传、下载目录、多页滚动提取、标签页管理和验证码/登录人工接管
- 长期使用：会话搜索/置顶/归档/分叉/导入导出，项目记忆与交接，三档权限策略、审计日志、本机用户绑定加密密钥库及 Skills 管理

## 2026-08-31 Win7 运行时增强

- Anaconda/SQLite：启动所有 Python 子进程时自动补齐 Conda 根目录、`DLLs`、`Library\\bin`、`Scripts` 到 PATH；`/diagnostics` 会实际导入 sqlite3 并显示 DLL 错误。
- PDF：Java PDFBox 解析继续内置可用；`offline-package/python-wheels` 同时提供 Python 3.7.6/Win7 可离线安装的 PyPDF2、pdfminer.six 及固定版本依赖，运行 `安装Python-PDF模块.cmd "D:\\Anaconda3\\python.exe"` 安装。
- 对话滚动：离开底部浏览历史时右下角出现“↓ 最新对话”，点击直达底部并恢复流式跟随。
- 运行中引导：任务执行期间输入补充指令并发送，只会进入输入框上方的“待引导区”；点击“立即引导”后才注入当前对话，也可取消。新会话保持可直接输入。
- 运行提示位置：“正在加载中/可随时补充信息/上下文压缩中”显示在输入框左侧，不再遮挡思考和回复内容。
- 会话状态点：左侧黄色呼吸点表示正在执行，执行完成后变为绿色常亮；点击查看该会话后绿色点消失。
- 自动压缩：模型调用前按安全线主动压缩，预留输出/工具结果余量，显示压缩前后 token；保留近期任务状态，并对失败压缩防抖。

## 运行状态指示器

- 状态点：会话页签与侧栏会话列表每行左侧的绿色圆点，该会话运行时呈呼吸动画（透明度 0.35↔1.0 往复，约 1.2s 周期），空闲时静止
- 正文区左下角悬浮指示器（仅当前激活会话反映）：运行中显示旋转齿轮 + 文案，齿轮约 2s/圈旋转，文案每 10s 随机轮换「正在加载中...」/「可随时补充信息...」；上下文压缩中固定显示「上下文压缩中...」（不参与轮换，压缩结束恢复）；运行结束或切换会话时隐藏；工具提问弹窗显示期间整体隐藏（防"等待用户操作"误判卡死），关闭后恢复
- 正文底部预留指示器高度留白，窗口化（非全屏）滚动到底也不会遮挡最后一行消息

## 界面图标

全部界面图标为 SVG 矢量图形（Material Symbols Outlined 线性风格，24×24 坐标系），由 `IconFactory`（gui/icon 包）集中提供，CSS（theme.css `.icon-*` 类）控制颜色与 hover 变色——不依赖系统字体，Win7 等缺字环境不再显示方块。涵盖：标题栏窗口按钮（最小化/最大化/还原/关闭）、侧栏操作按钮（修改/删除）与当前工作空间标记点、设置窗模型激活标记与 MCP 状态点、正文工具区状态图标（提问/成功/失败/子任务/工具调用/统计）、折叠段展开箭头、输入框发送/终止/上传按钮与块删除按钮。

## 会话存储

jar 同目录 `session/<workSpaceName>/`，每会话一个 JSON 文件（每轮请求完成后落盘，可安全恢复）。

## 内置工具输出上限与落盘

- 命令/搜索输出超限（Bash 30k 字符、Grep 250 条或 30k 字符）时：返回保留头部+尾部（Grep 保留前 250 条），完整结果落盘到 jar 运行目录 `<jarDir>/.session/tmp/<会话id>/`，返回中附绝对路径，可用 Read 查看；启动时自动清理 3 天前的落盘文件。
- `Grep`/`Glob` 尊重 `.gitignore`（根目录与各级子目录规则就近叠加，支持取反/目录限定/锚定），恒跳过 `.git` 目录，Grep 跳过二进制文件（前 8KB 含 NUL）；显式指定被忽略目录为搜索根时仍直搜。ripgrep 13.0.0（Win7 兼容）作为 `/ripgrep/rg.exe` 打进 JAR，首次使用时按 SHA-256 校验并自解压到安装目录 `.session/tools`；也兼容 jar 同目录 `rg.exe`、`tools/rg.exe` 与 PATH。找不到或执行失败无感回退 Java 遍历；中文模式与 `\b\w\d\s` 类正则仍走 Java 路径以保证 GBK 文件与 ASCII 词边界语义。ripgrep 调用默认 120 秒整体截止，可用 `-Dminion.ripgrep.timeoutMs=<毫秒>` 调整（钳制 0.5 秒至 30 分钟），超时或用户停止会终止进程并清理半截结果。

## Python、Excel、SQLite 与本地知识库

- `Python`：`status` 自动发现并诊断 Anaconda/Python；`run_code` 执行分析代码；`run_file` 执行工作区脚本。发现顺序为设置中的 `python.path`、`MINION_PYTHON`、当前 Conda、常见 Anaconda/Miniconda 目录、PATH
- `Excel`：`inspect/read/profile/filter/write/append/merge/pivot/chart/export`，基于内网 Anaconda 的 pandas/openpyxl；写操作默认输出新文件并建立检查点
- `Document`：提取 DOCX/PPTX/PDF/OFD/XLSX，创建/替换 DOCX、创建 PPTX、PDF 合并拆分并诊断 OCR/Tesseract。OFD 支持添加/拖入附件、`Read` 文字预览和知识库导入；`inspect` 查页数、`ofd_read` 按页读。扫描型 OFD 可用 `ofd_view` / `ofd_ocr` 读取所选页的内嵌图（OCR 沿用本地 Tesseract）；不渲染完整版式，也不验签。
- 书生 GD：添加/拖入附件时按内容识别 PDF/OFD。原生 GD 可经书生阅读器导出 TXT/PDF/OFD，再添加副本或用 `Document gd_read(path=原件, exportPath=副本)` 读取。若单位已有可批量转换 GD 的离线命令行程序，可在设置「文档/OCR」配置可信 EXE、JSON 参数和输出格式，之后可让 `gd_read` 或 `Knowledge import` 转换再读；不会猜测书生桌面版的命令行参数、自动联网或分发第三方解码器。
- `Database`：除 SQLite 外支持 ODBC/SQL Server、MySQL、PostgreSQL；`query` 默认只读，`execute` 需确认，`export` 可输出 CSV/XLSX
- `SQLite`：`schema` 查看结构、`query` 只读查询、`execute` 建表及增删改；使用 Python 标准库 sqlite3，不需要额外数据库驱动
- `Knowledge`：项目知识库保存在 `<工作区>/.minion/knowledge/`；可在基础设置中指定共享目录，或让 Minion 用 `Knowledge.connect` 连接用户指定的绝对目录（需确认）。所有项目的 search/list 都会直接读取共享目录及子目录中的 `.md` 文件，修改后下次查询即生效，无需逐项目导入。共享条目以 `shared/相对路径` 显示，可用此名称 read；add/import/sync/delete 仍只修改当前项目知识库。`disconnect` 可取消共享连接，不删除文件。目录不存在时会提示，不会自动新建或清空它。不依赖云端向量服务。
- 设置 → 基础设置 → `Python/Anaconda` 可选择内网解释器，例如 `C:\ProgramData\Anaconda3\python.exe`

内置技能包含：`visual-design`、`image-reading`、`document-materials`、`ofd-gd-documents`、`data-analysis`、`skill-creator`、`sql-database`、`knowledge-base`、`browser-automation`、`web-development`、`windows-packaging`、`app-development`。外部技能同名时覆盖内置技能。绘图/读图/PDF-Excel-DOCX 材料/OFD-书生GD 等领域工作流规则放在对应技能正文中，模型按技能描述匹配后经 Skill 工具按需加载，不再每轮常驻系统提示词（读图保留一行兜底规则）。

## 浏览器工具(登录、点击、查询、调试网页)

对接本机 Chrome(CDP 协议,零额外依赖)。首次使用自动启动 Chrome(默认有头窗口,便于观察调试;自动化场景可配置无头)。**默认不启用**——在 设置 → 工具 勾选「浏览器操作」的启用开关并在「配置」里填好浏览器路径后，模型才看得到这几个工具（改完下一轮对话即生效；配置保存会关闭已由本软件启动的 Chrome 并按新配置重建，已打开的页面随之关闭）。

配置项（存 `tools.json`，设置窗「工具」页管理）：

    path=          # Chrome 可执行文件路径,留空自动探测常见安装位置(也可改配置)
    port=9222      # 调试端口(Chrome 默认只绑定本机,不暴露局域网)
    userDataDir=./.minion/browser-profile   # 登录状态持久化目录(清空即重置)
    headless=false
    timeoutMs=30000

用法(模型自动调用,也可在对话里描述操作):

- `Browser`  open/back/refresh/status —— 打开页面与导航
- `BrowserAction`  click/type/select/text/table/links/scroll/wait_selector/network_idle/upload/download_setup/paginate/tabs/new_tab/switch_tab/close_tab/manual_takeover —— 适合 27B 模型稳定调用
- `BrowserEval`  执行 JS:输入、点击、提取表格数据(SPA 受控组件用 __minion_set_value 辅助)
- `BrowserScreenshot`  截图存工作区
- `BrowserDebug`  network/console/page —— 网络请求、控制台日志、页面状态

登录示例:对话里告知账号密码 → 模型用 BrowserEval 填表提交 → 登录态保存在 userDataDir,下次会话保留。

## MCP 工具扩展（stdio / SSE / Streamable HTTP）

对接 MCP（Model Context Protocol）服务器，把服务器上的工具暴露给模型调用。标准 JSON-RPC 2.0 协议，兼容 Claude Code / 千问等生态的 MCP 服务器。配置在设置窗「MCP」页管理（服务器列表 + 状态点 + 启用开关 + 新建/编辑/删除/重连），落盘 `mcp.json`。

字段：

    name=playwright            # 服务器名（工具名前缀区分来源）
    transport=stdio            # stdio（本地进程）| sse（旧版 HTTP+SSE）| streamable（Streamable HTTP，推荐远程）
    command=npx                # stdio：可执行命令（Windows 下 npx 自动解析为 npx.cmd 并以 cmd /c 包装）
    args=@playwright/mcp       # 参数，每行一个
    env=KEY=VALUE              # 环境变量，每行一个（仅 stdio：传给子进程，如 GITHUB_PERSONAL_ACCESS_TOKEN）
    url=                       # sse：SSE 端点（如 http://host:port/sse）；streamable：MCP 端点（如 http://host:port/mcp）
    headers=K:V                # 请求头，每行一个（仅 streamable；旧版 SSE 传输不支持自定义头，需鉴权请用 streamable）

传输方式为单选：选中一种后其余字段隐藏并清空（表单按传输类型裁剪保存）。

实现说明：MCP 客户端基于 aj-mcp-client 1.5 标准实现（JDK8 兼容）：握手、协议版本协商（2024-11-05/2025-03-26/2025-06-18）、stdio/SSE/Streamable 三传输由库完成；tools/list 与 tools/call 取原始 JSON（inputSchema 原样透传，非文本内容不丢）。依赖变化：okhttp 升 4.12（与库对齐，单份 okhttp + kotlin-stdlib），新增 jackson、slf4j-simple（warn 级日志），产物体积约 2.75 MB → 8 MB。

连接时机：启用服务器后首次新建/恢复会话时后台预连接（不阻塞界面），连接完成后该服务器的工具自动补充注册进所有会话（下一轮请求即可被模型调用）；与内置工具重名的自动跳过并在列表标注。

Playwright 示例（需要 Node.js 18+，可在 [nodejs.org](https://nodejs.org) 安装 LTS）：

1. 设置 → MCP → 新建：名称 `playwright`、传输 `stdio`、命令 `npx`、参数 `@playwright/mcp`，保存后勾选「启用」
2. 新建会话，对话里让模型「打开 https://www.baidu.com 并返回标题」→ 模型会调用 playwright 的浏览器工具完成操作

与浏览器（CDP）工具的关系：MCP 是独立通道，二者可共存；浏览器工具需在 设置 → 工具 页启用并配置路径后才可用（未启用时模型看不到这些工具，MCP 不受影响）。

## 可插拔工具与只读数据库工具（设置 → 工具）

默认全部不启用。启用开关就是「工具描述是否注入」的开关——不启用时模型在系统提示与 schemas 里完全看不到该工具，调用返回「工具不存在或已停用」。改动即落盘 `tools.json`，全局会话下一轮请求生效，无需重启。

- **浏览器操作**：启用 + 配置后可用（见上节）
- **mysql / postgreSQL / oracle**（只读）：`DbMysql` / `DbPostgres` / `DbOracle` 三个工具，只读当前选中的数据源
  - **只支持读操作**：SQL 白名单（SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN，拒绝多语句与 INTO OUTFILE / FOR UPDATE 等），连接层 setReadOnly(true)，且每次调用**新建连接、用后即关**（不落连接池）——建议给只读账号（低权限）以求纵深防御
  - postgreSQL 仅支持 query；schema/describe 会返回禁用提示（MySQL/Oracle 支持 query+schema+describe）
  - 结果上限 100 行（超出在表头标注「行数超上限，已截断」）、单格超 120 字符截断、超 30k 字符落盘到会话临时目录并给路径
  - 数据源在 设置 → 工具 的行内下拉框选择当前数据源（切换即落盘生效，无需进管理弹窗），「数据源管理」里新建/修改/删除/测试连接；URL 示例：
    - MySQL：`jdbc:mysql://127.0.0.1:3306/db?useSSL=false&allowPublicKeyRetrieval=true&useInformationSchema=true`（连 5.x 需前两项；`useInformationSchema=true` 让表注释 REMARKS 有值）
    - PostgreSQL：`jdbc:postgresql://127.0.0.1:5432/db`
    - Oracle：`jdbc:oracle:thin:@127.0.0.1:1521:ORCL`
  - 密码明文存 `tools.json`（与 `model.json` 的 apiKey 同口径）；长查询 300 秒超时，超时不一定真能打断数据库侧的查询
- **ssh**（远程运维）：`SshExec`（远程执行命令，危险命令如 rm/dd/systemctl/apt 弹确认，默认超时 120s，输出超长自动截断落盘）+ `SftpLs`/`SftpGet`/`SftpPut`/`SftpRm`/`SftpMkdir`/`SftpRename` 六文件操作（SftpPut 覆盖与 SftpRm 删除弹确认；SftpRm 不递归，递归删除用 SshExec 的 rm -rf）
  - tools.json 段 `ssh`：`enabled` + `current` + `connections[]`（name/host/port/user/password/privateKeyPath/passphrase）；密码与私钥口令明文存（与 apiKey 同口径）
  - 设置 → 工具 → ssh：启用开关 + 当前连接下拉 + 「连接管理」（表单先选「密码/私钥」再填对应项，保存时两套字段互斥）
  - 认证支持密码与私钥（可选口令）；连接/命令均每次新建即关；known_hosts 指纹不校验（StrictHostKeyChecking=no，限内网/测试服务器使用）
  - 依赖：com.github.mwiede:jsch 2.28.7（JDK8 兼容、零传递依赖）

## 模型供应商配置（deepseek / qwen）

默认对接 deepseek（thinking max）。切千问（阿里百炼 DashScope OpenAI 兼容模式）在设置窗「模型」页或 model.json 里改：

    provider=qwen
    url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    key=sk-你的百炼APIKey
    # 选混合模型（qwen3 系列/qwen-plus）；纯思考模型（qwq/-thinking 变体）思考不可关闭
    name=qwen3-max
    # 千问窗口通常 128k~256k；默认 900000 会超窗报 400
    maxContextTokens=131072

说明：

- `thinking=true` 时按供应商翻译思考参数：deepseek → `thinking`/`reasoning_effort`（档位至 max）；qwen → `enable_thinking` + `reasoning_effort`（档位至 xhigh，qwen3 混合模型默认开思考，关闭时显式传 `enable_thinking:false` 且不带 effort）
- qwen 下请求自动带 `stream_options: {include_usage: true}`（token 统计准确）
- `provider` 为未知值时回退 deepseek 行为
- `model.json` 缺失/为空/损坏时自动生成 deepseek + 千问两套配置（除 key 外按各自调用参数预填，key 留空待填）
- 模板参考：源码目录 `src/resource/config_deepseek.properties` / `config_qwen.properties`（仅记录，实际生效仍为 jar 同目录的配置）
# 本地图片上下文（V31）

新增开发：PDF按页视觉读取、实际OCR、Excel分页/文本ID/合并审查/保留工作簿回填，以及Word模板/证据清单/结构检查/可选LibreOffice预览。详见[数据分析与材料工作流](docs/数据分析与材料工作流.md)。设置新增「文档/OCR」页，依赖缺失不自动安装；生成文件与实际核验严格区分。

Read 读取 PNG/JPEG/GIF/BMP 时会附上真实图片像素；ViewImage 支持原图坐标裁剪和顺时针旋转（0/90/180/270）。主/子 Agent 均支持，保留现有目录授权。图片仅发送当前配置的模型接口，要求服务具备视觉能力；不保证纯文本服务能看图。原图不改写，GIF仅首帧，WebP需先转PNG/JPEG。单图输入20MB/2400万像素、传输5MB上限，超限请先裁剪。图像资料不作为用户新任务。
