# 已确认实施计划

1. Document 增加 pdf_read/pdf_view、页码和截断提示，复用 V31 图像结果；扫描页能直接看图。
2. OCR 配置、真实 TSV 输出与原图上下文；有界进程/语言参数、诊断，不自动安装。
3. Excel offset、textColumns、merge_audit、validate、patch_cells；保留原有行为和原工作簿。
4. Document docx_template/docx_report/docx_validate/office_preview；证据清单、原文/分析/未核实状态，模板保留和依赖降级。
5. 能力诊断、版本缓存、提示词及只读策略同步，增加合成文件与权限回归测试。
6. 串行 Java8 编译、测试，并报告外部运行时和内网模型尚未验证项；本次不自动打包。

完成记录：上述基础能力已实现。1326项测试报告，0失败/错误/跳过；合成PDF/Excel/Word与真实本地Tesseract集成通过；真实JavaFX文档设置控件落盘通过；Python3.7语法检查通过。未安装LibreOffice，实际预览和内网Win7/模型识别需后续环境验收。本轮未打包/推送；准备了包含workflows/*.py及默认配置资源的V32打包脚本（只做语法检查，未执行）。
