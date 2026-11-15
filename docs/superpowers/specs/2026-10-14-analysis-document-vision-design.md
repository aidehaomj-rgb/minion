# 数据分析、材料撰写与视觉文档能力补强设计

状态：用户已确认实施。首轮基础能力开发与本地验证完成；未打包、未推送。Java8编译、全量回归及最终专项测试报告合计1326项，0失败、0错误、0跳过（显式使用本机Python和已安装Tesseract）。Python3.7语法检查通过，真实JavaFX文档/OCR控件及配置保存验证通过。内网Win7/旧库和模型识别质量未验收；本机无LibreOffice，仅验证缺依赖失败路径，实际预览排版尚未验收。

## 对照范围与约束
参考OpenAI官方PDF文件输入、Python执行能力，以及Claude官方PDF和文件创建能力；API功能不能视为所有Codex/Claude Code客户端默认具备。借鉴工作流，不承诺本地模型达到同等准确率，也不新增外网数据传输。保持Java8/Win7、内网模型、现有上下文阈值、任务状态、权限和历史机制。V31已有本地图像上下文、旋转裁剪，不重复实现。

参考：
- https://developers.openai.com/api/docs/guides/file-inputs
- https://developers.openai.com/api/docs/guides/tools-code-interpreter
- https://platform.claude.com/docs/en/build-with-claude/pdf-support
- https://support.claude.com/en/articles/12111783-create-and-edit-files-with-claude

## 本地代码证据
- DocumentTool.extractPdf仅PDFTextStripper，空文本提示先OCR；未渲染页面图像，无分页文字/视觉读取工作流。
- DocumentTool.ocrStatus仅检查tesseract版本，未实际执行带证据定位的OCR。
- ExcelTool已有inspect/profile/filter/merge/groupby/pivot/chart，但pandas默认推断数据类型；read/filter仅head(rows)，无offset；写出DataFrame不保留原工作簿全部结构。
- DocumentTool有基础docx_create/docx_replace，缺少材料模板、证据清单、渲染预览和交付质量检查。
- PythonRuntime已有进程执行和超时，不是操作系统沙箱；不能宣传为等同云端隔离运行环境。

## 拟开发范围（依次交付）

### 一、PDF按页读取与视觉上下文（优先）
基于现有PDFBox，无新增大依赖。Document新增按页文字读取、页面渲染到图像上下文、局部裁剪复核；页码一律1-based，返回文件/页码/阅读范围/剩余页数。扫描件和含表格图表页面由模型主动选择视觉读取，避免只提取文字漏内容。Read遇PDF引导到Document，不当文本解码。限制PDF大小、页数范围、单次页数、DPI和总像素；加密、损坏、权限或渲染失败明确报告。

### 二、OCR真实执行与复核（优先）
可配置本地Tesseract可执行路径、语言；工具执行OCR并输出文字、坐标及引擎置信度/来源。置信度不是事实正确概率。OCR和视觉不一致标待人工复核。无依赖时明确提示，不自动下载或安装，不将无法识别当无品牌。新增配置同步设置、默认值、说明和离线依赖检测。批量仅在用户指定范围内执行。

### 三、数据可靠性与分页（优先）
明确选择ID文本列以保护报关单号、前导零、金额精度；原文件已丢失精度不能恢复。新增offset分页、处理全表/抽样显式标记、合并键重复及未匹配报告、行数/唯一数/合计核验。新增保留全部sheet和样式的指定单元格回填动作；不改现有导出行为，输出新文件。长结果落盘按页读取，不能把head样本作为全量结论。

### 四、材料模板与证据可追溯
支持用户指定Word模板和样式映射，先大纲、后章节，保留标题、表格和页眉页脚；事实引用来源文件/页码/表行或证据ID，区分原文、分析和待核实。输出证据清单，不伪造引文。只在当前授权资料中检索，保持共享知识库权限。分阶段写稿状态独立落盘，避免压缩后回到旧指令。

### 五、交付质量核验与预览
Excel检查行列、sheet、字段、公式及合计；Word检查占位符、标题层级、来源和未核实项。可选本地Office/LibreOffice渲染预览，缺依赖报告未视觉验证，不能声称排版已验证。图表和PDF页面复用V31图像上下文。新增产出列表/证据与页码链接，先提供文件定位能力，不一次重写GUI。

### 六、依赖诊断与上下文节省
统一Python/pandas/openpyxl/python-docx/OCR/PDF能力诊断；按页/列/相关证据读取，缓存按内容版本失效，数据正文留本地，摘要不得取代待核对原始证据。子Agent接收指定文件/页码/任务，不复制全量材料。保留当前模型和压缩阈值；不扩大Shell权限，不声称真正沙箱化。

## 验收
用合成文件覆盖：扫描PDF、有图表文字PDF、页码分页、畸形文件、目录越界；19位文本ID与前导零、重复合并键、全表分页、多sheet回填；Word模板、证据引用、未核实项。Java8编译和原有回归必须通过；OCR及Office等外部依赖存在时做集成测试，不存在时明确跳过。真实内网模型识别效果另做用户样本验收。后续打包需用户明确要求，不在设计阶段生成新包。

## 本轮实现边界
- PDF正文缓存按内容快照SHA256和页码区分，最多32页；无全量常驻Python内存会话。
- OCR对图片实际输出TSV并附图，不直接批量OCR整个PDF；PDF页面先视觉读取，需OCR时在授权范围导出图片再ocr。
- 材料状态和证据清单落盘，登记来源不证明引文真实；只读模式不允许写出材料。
- Office预览仅实现本地LibreOffice路径，未接入Windows Word COM；拒绝宏、嵌入对象及外部关系链接。复杂绘图/控件保留和公式重算不作保证。
- Excel长结果完整落盘并给Read续读路径；参数说明及默认操作引导已同步。
