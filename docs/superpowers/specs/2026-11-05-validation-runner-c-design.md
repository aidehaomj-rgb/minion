# 批次 C：真实验证与源码版本证据

范围仅 BuildTool、新建 dev 帮助类和 dev 测试；用户已授权实施，不修改总计划/README、任务状态、主循环或旧交付包。不启动时探测，不安装任何依赖，不增加运行时库。Java 8 / Python 3.7 语法。

## 契约

- Build 的 test/build/syntax_check/command 执行仍走现有高危确认。detect 只读文件，不启动解释器或运行配置。verify 只重算文件摘要。
- 工具输出和落盘报告均为 JSON，type=minion.validation.v1，字段固定 kind、status、exitCode、tests/failures/errors/skipped（未知为 null）、durationMillis、logPath、reportPath、scope.root、sourceDigest、sourceUnchanged。每次独立 runId 和 .minion/validation-runs/<runId>，完整原始日志落盘，模型预览有界且注明编码。
- test/passed 仅限本次非零真实测试，failures/errors 为零、存在非跳过测试、退出 0、源码未变化。退出 0 但未知测试数为 command_succeeded；零测试为 no_tests，全部跳过为 skipped，缺配置 not_configured，缺工具/离线依赖为 blocked 或 failed，不伪装成功。
- build/passed 仅表示构建命令完成；syntax_check/passed 仅表示语法检查。两者不证明功能正确。command/command_succeeded 不证明功能正确。
- Python 优先显式 .minion/validation.json，其次已有 pytest（仅执行验证时检查模块可用），再 unittest。pytest 使用本次唯一 JUnit XML；unittest 由标准库 runner 记录真实 testsRun/failures/errors/skipped。compileall 为独立 syntax_check，不再冒充 test/build。
- Maven 优先项目 wrapper，无 wrapper 才使用系统 Maven；Gradle 必须 wrapper；npm 必须存在真实非空 scripts。默认离线 Maven/Gradle，npm 禁止自动安装，缺缓存按真实失败报告。Maven/Gradle 报告只采纳本次新建或变化的 JUnit，旧报告不计数。
- 显式配置 v1 示例：`{"version":1,"test":{"command":["${python}","-m","pytest","--junitxml=${runDir}/result.xml"],"reports":["${runDir}/result.xml"],"encoding":"UTF-8"}}`。command 可为字符串（显式 shell）或 argv 数组；reports 为项目内 XML 文件或目录，不支持 glob/越界/符号链接。`${python}` 仅替换数组中的独立参数，`${runDir}` 替换命令/报告路径。无报告的自定义测试命令不能凭退出 0 变 passed。生成的报告/日志请放输出目录或 `${runDir}`，若写入受摘要保护的源码区，将按文件变化失效，不做隐式扩大排除。
- 内容 SHA-256 包含相对路径与文件内容，排除 .git/依赖/输出目录及验证日志；包含 .minion/validation.json。运行前后比对，并提供 `SourceVersion.verify(Path root, String sourceDigest)`。这不是语义审查，也不保证输出目录中源代码受覆盖；排除规则在报告可见。
- 超时或线程中断调用现有 ProcessCleanup.terminate；取消保留线程中断标记。日志写入失败、报告解析失败、路径权限失败均不可通过。

## 主流程接线（C04/C05/C06）

后续由主流程消费 Build 返回 JSON（不从“成功”文字猜测）：记录 reportPath/runId/kind/status/scope.root/sourceDigest；步骤 validationKind=test 只接受 test/passed、非零非全跳过计数、sourceUnchanged=true、functionalVerified=true、processStarted=true。还要核对 startedAtMillis 属于本任务/步骤启动以后、同一 scope、成功回执的唯一归属；不要把已存在的报告当作新回执。

公共 API（包 com.minion.core.tools.dev）：

```java
new ValidationRunner(PythonRuntime).run(Path root, String kind, String command, int timeoutSeconds); // 返回 JsonObject，须由 Build 高危确认后调用
SourceVersion.verify(Path root, String sourceDigest); // boolean；重算源码，无外部进程，I/O 失败 false
SourceVersion.capture(Path root); // Snapshot { public final String digest; public final int fileCount; }，throws IOException
ValidationRunner.verifyReport(Path root, Path reportPath); // boolean；校验路径/runId/源码/日志，不是成功判断或报告签名
```

`type=minion.validation.v1`；`kind=test/build/syntax_check/command`；`sourceDigest` 是运行前 `sha256:<64位小写hex>`，`sourceDigestAfter` 为运行后；`scope.root` 为绝对真实路径。`tests/failures/errors/skipped/exitCode` 未知为 JSON null。`startedAtMillis/finishedAtMillis/durationMillis` 为 long；`logEncoding/logBytes/logDigest/logPreview/testReportPaths/staleReportsIgnored` 便于读取证据。logPreview 至多 6000 字符＋截断提示，完整原始输出在 logPath。JSON reportPath 独立落盘，ToolResult.output 为同一 JSON。

最终收口优先调用 verifyReport(root, reportPath)；只想核对版本可以调用 SourceVersion.verify(root, sourceDigest)。verifyReport=true 也不能把失败/未知计数/其他任务的报告变为通过。Build action=verify 是只读工具入口，输出 `type=minion.validation.verify.v1/current`，刻意不冒充新验证执行。

未知计数、构建、语法检查不能满足功能测试验收。失败修复预算、独立只读真实 diff 审查仍需主循环实现，本批不越界接线。摘要是调用时刻的内容快照，不加工作区锁，也不是 OS 沙箱；排除目录内源码、项目自定义假报告的真实性不由摘要证明。

## 验证记录

待执行独立 target 子目录 javac + JUnit；不运行共享 Maven/clean/package，不提交不打包。完成后在此追加真实证据与未测边界。
