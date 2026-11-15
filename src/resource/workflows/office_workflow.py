"""模板/报告/结构核验/可选LibreOffice预览。兼容Python3.7，不联网。"""
import json, os, sys, re, tempfile, hashlib, shutil, subprocess
with open(sys.argv[1], encoding='utf-8') as f:
    opt = json.load(f)
action, src, out = opt['action'], opt['path'], opt['outputPath']
if src:
    import zipfile
    with zipfile.ZipFile(src) as package:
        if sum(e.file_size for e in package.infolist()) > 100 * 1024 * 1024:
            raise ValueError('DOCX解压体积超过100MB，未读取')
        if action == 'office_preview':
            names = [e.filename.lower() for e in package.infolist()]
            if any('vbaproject' in n or n.startswith(('word/activex/', 'word/embeddings/')) for n in names):
                raise ValueError('预览拒绝含宏、ActiveX或嵌入对象的文档，请先人工另存为安全副本')
            import xml.etree.ElementTree as ET
            for name in package.namelist():
                if name.endswith('.rels'):
                    root = ET.fromstring(package.read(name))
                    if any(e.attrib.get('TargetMode') == 'External' for e in root):
                        raise ValueError('预览拒绝外部关系链接，防止Office更新链接访问外网；请先另存安全副本')
data = json.loads(opt.get('data') or '{}')
if not isinstance(data, dict):
    raise ValueError('data须为JSON对象')

def paragraphs(container):
    for p in container.paragraphs:
        yield p
    for table in container.tables:
        for row in table.rows:
            for cell in row.cells:
                for p in paragraphs(cell):
                    yield p

def all_paragraphs(doc):
    for p in paragraphs(doc): yield p
    for section in doc.sections:
        for container in (section.header, section.footer, section.first_page_header, section.first_page_footer,
                          section.even_page_header, section.even_page_footer):
            for p in paragraphs(container): yield p

def replace_runs(p, token, replacement):
    # 逆序替换跨run占位符；保留未覆盖run的字体/样式，新增字继承起始run。
    matches = list(re.finditer(re.escape(token), p.text))
    for match in reversed(matches):
        pos = 0
        for run in p.runs:
            text = run.text
            end = pos + len(text)
            if end > match.start() and pos < match.end():
                left = text[:max(0, match.start()-pos)]
                right = text[max(0, match.end()-pos):] if match.end() < end else ''
                run.text = left + (replacement if pos <= match.start() < end else '') + right
            pos = end
    return len(matches)

def save_atomic(doc):
    fd, stage = tempfile.mkstemp(suffix='.docx', dir=os.path.dirname(os.path.abspath(out)))
    os.close(fd)
    try:
        doc.save(stage)
        from docx import Document
        Document(stage)  # 验证包可重新打开；不等于排版视觉验收。
        os.replace(stage, out)
    finally:
        if os.path.exists(stage): os.unlink(stage)

def evidence_list():
    evidence = data.get('evidence', [])
    if not isinstance(evidence, list) or len(evidence) > 5000:
        raise ValueError('evidence须为不超过5000项的数组')
    ids = set()
    for item in evidence:
        if not isinstance(item, dict) or not item.get('id') or item['id'] in ids:
            raise ValueError('证据ID缺失/重复')
        ids.add(item['id'])
        # 来源仅作为定位文本；不读取其中的任意路径，不据路径字符串保证证据存在。
        if not item.get('source') or not item.get('location'):
            raise ValueError('证据须含source和location(页码/表行/段落)')
    return evidence, ids

def report_state(doc, evidence):
    # 跟随产出文件的一份材料状态，原任务权威仍是Agent任务状态，不替换用户指令。
    state = {'artifact': out, 'phase': 'drafted_not_visually_verified',
             'sections': [{'heading': s.get('heading', ''), 'status': s.get('status', '待核实'), 'evidenceIds': s.get('evidenceIds', [])} for s in data.get('sections', [])],
             'evidence': evidence, 'visualVerified': False,
             'note': '来源是用户/Agent登记的定位信息，不代表已核对；需要docx_validate和office_preview后逐页复核'}
    with open(out, 'rb') as f:
        state['artifactSha256'] = hashlib.sha256(f.read()).hexdigest()
    fd, stage = tempfile.mkstemp(suffix='.json', dir=os.path.dirname(os.path.abspath(out)))
    os.close(fd)
    try:
        with open(stage, 'w', encoding='utf-8') as f: json.dump(state, f, ensure_ascii=False, indent=2)
        os.replace(stage, out + '.material-state.json')
    finally:
        if os.path.exists(stage): os.unlink(stage)
    print('材料状态/证据清单: ' + out + '.material-state.json')

if action == 'office_preview':
    # 独立临时用户配置避免连接已有Office进程；原DOCX不修改。
    with tempfile.TemporaryDirectory(prefix='minion-preview-', dir=os.path.dirname(os.path.abspath(out))) as temp:
        from pathlib import Path
        profile = Path(os.path.join(temp, 'profile')).as_uri()
        env = dict(os.environ)
        result = subprocess.run([opt.get('office') or 'soffice', '-env:UserInstallation=' + profile,
                                 '--headless', '--convert-to', 'pdf:writer_pdf_Export', '--outdir', temp, src],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90, env=env)
        made = os.path.join(temp, os.path.splitext(os.path.basename(src))[0] + '.pdf')
        if result.returncode != 0 or not os.path.isfile(made):
            raise RuntimeError('Office预览失败/缺少LibreOffice，未完成视觉核验: ' + result.stderr.decode('utf-8', 'replace')[:4000])
        with open(made, 'rb') as f:
            if f.read(5) != b'%PDF-': raise ValueError('预览输出不是PDF')
        os.replace(made, out)
    print('已生成预览PDF: %s；请用Document pdf_view逐页检查。转换成功不代表已视觉核验。' % out)
elif action == 'docx_template':
    from docx import Document
    doc = Document(src)
    fields = data.get('fields', {})
    if not isinstance(fields, dict) or not fields:
        raise ValueError('data.fields须为{占位符名称:替换文字}，模板用{{名称}}')
    count = 0
    visited = set()
    for p in all_paragraphs(doc):
        # 合并单元格、链接页眉可能重复出现，去重。
        if p._p in visited: continue
        visited.add(p._p)
        for key, value in fields.items():
            if not isinstance(value, (str, int, float, bool)) and value is not None:
                raise ValueError('fields值须为文字或标量')
            count += replace_runs(p, '{{' + str(key) + '}}', '' if value is None else str(value))
    pending = [p.text for p in all_paragraphs(doc) if re.search(r'\{\{[^}]+\}\}', p.text)]
    save_atomic(doc)
    print('模板已输出: %s；替换%d处；未替换段落%d（文本框/域/批注等特殊对象不保证处理）' % (out, count, len(pending)))
    print(json.dumps({'unresolvedSample': pending[:20], 'visualVerified': False}, ensure_ascii=False))
elif action == 'docx_report':
    from docx import Document
    doc = Document(src) if src else Document()
    for p in all_paragraphs(doc):
        for key, value in data.get('fields', {}).items(): replace_runs(p, '{{' + str(key) + '}}', str(value))
    styles = data.get('styles', {})
    for name in styles.values():
        if name not in doc.styles: raise ValueError('不存在的样式: ' + str(name))
    if data.get('title'): doc.add_heading(str(data['title']), 0)
    sections = data.get('sections', [])
    if not isinstance(sections, list) or not sections or len(sections) > 300:
        raise ValueError('sections须为1到300项的数组')
    evidence, ids = evidence_list()
    for section in sections:
        status = section.get('status', '待核实')
        if status not in ('原文', '分析', '已核对', '待核实'): raise ValueError('章节status须为原文/分析/已核对/待核实')
        references = section.get('evidenceIds', [])
        if not isinstance(references, list) or any(v not in ids for v in references): raise ValueError('章节引用不存在的证据ID')
        if status in ('原文', '已核对') and not references: raise ValueError('原文/已核对章节必须登记证据ID，不能无来源宣称已核对')
        level = int(section.get('level', 1))
        if level not in (1, 2, 3): raise ValueError('标题level须为1/2/3')
        heading = str(section.get('heading', ''))
        if heading:
            if styles.get('heading'): doc.add_paragraph(heading, style=styles['heading'])
            else: doc.add_heading(heading, level)
        doc.add_paragraph('[' + status + ']' + (' 来源证据: ' + ', '.join(references) if references else ''))
        content = section.get('paragraphs', [])
        if isinstance(content, str): content = content.splitlines()
        if not isinstance(content, list): raise ValueError('paragraphs须为数组或文字')
        for text in content: doc.add_paragraph(str(text), style=styles.get('body'))
        for table in section.get('tables', []):
            if not table: continue
            width = len(table[0])
            if width < 1 or width > 30 or len(table) > 500 or any(len(row) != width for row in table):
                raise ValueError('表格必须为等宽二维数组，最多500行30列')
            t = doc.add_table(rows=0, cols=width)
            if styles.get('table'): t.style = styles['table']
            for row in table:
                cells = t.add_row().cells
                for i, value in enumerate(row): cells[i].text = str(value)
    if evidence:
        doc.add_heading('来源证据清单（定位信息，不代替原文复核）', 1)
        for item in evidence:
            doc.add_paragraph('%s | %s | %s' % (item['id'], item['source'], item['location']))
    save_atomic(doc)
    report_state(doc, evidence)
    print('材料已生成: ' + out + '；请docx_validate检查，再office_preview逐页复核。')
elif action == 'docx_validate':
    from docx import Document
    doc = Document(src)
    text = [p.text for p in all_paragraphs(doc)]
    placeholders = [s for s in text if re.search(r'\{\{[^}]+\}\}', s)]
    result = {'file': src, 'paragraphs': len(doc.paragraphs), 'tables': len(doc.tables),
              'sections': len(doc.sections), 'unresolvedPlaceholderParagraphs': len(placeholders),
              'unverifiedParagraphs': sum('待核实' in s for s in text),
              'headings': [{'text': p.text, 'style': p.style.name} for p in doc.paragraphs if p.style.name.startswith('Heading')],
              'visualVerified': False, 'placeholderSample': placeholders[:20]}
    state_path = opt.get('statePath', '')
    if state_path and os.path.isfile(state_path):
        with open(state_path, encoding='utf-8') as f: state = json.load(f)
        with open(src, 'rb') as f: digest = hashlib.sha256(f.read()).hexdigest()
        result['stateMatchesCurrentFile'] = digest == state.get('artifactSha256')
        result['registeredEvidenceCount'] = len(state.get('evidence', []))
    print(json.dumps(result, ensure_ascii=False))
    print('结构检查不保证排版、引用真实性或事实准确；源文件/页码/表行需实际核对，特殊文本框/脚注等仍需视觉复核。')
else:
    raise ValueError('未知Office动作')
