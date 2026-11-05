"""固定本地数据工作流，兼容Python3.7；正文不进入命令行，源文件不默认改写。"""
import sys, os, json, hashlib, tempfile, re
from decimal import Decimal, InvalidOperation, localcontext
import pandas as pd

with open(sys.argv[1], encoding='utf-8') as handle:
    opt = json.load(handle)
action, path, output = opt['action'], opt['path'], opt['outputPath']

def cols(name):
    return [v.strip() for v in opt.get(name, '').split(',') if v.strip()]

def digest(name):
    h = hashlib.sha256()
    with open(name, 'rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()

def emit(text):
    if len(text) <= 6000:
        print(text)
        return
    encoded = text.encode('utf-8')
    if len(encoded) > 20 * 1024 * 1024:
        raise ValueError('本次结果超过20MB，未完整输出；请减少rows/columns或按文件分批')
    with open(opt['resultPath'], 'wb') as f: f.write(encoded)
    print(text[:4000])
    print('\n[仅显示前4000字符；本次完整结果已落盘: %s；请用Read分段续读，不可把预览当全量已读。]' % opt['resultPath'])

def load(name, sheet=''):
    if os.path.getsize(name) > 100 * 1024 * 1024:
        raise ValueError('数据文件超过100MB，请先按文件/批次分块')
    options = {'dtype': {c: str for c in cols('textColumns')}} if cols('textColumns') else {}
    if options:
        options['keep_default_na'] = False
    if name.lower().endswith(('.csv', '.tsv')):
        for encoding in ('utf-8-sig', 'gbk'):
            try:
                return pd.read_csv(name, sep='\t' if name.lower().endswith('.tsv') else ',', encoding=encoding, **options)
            except UnicodeDecodeError:
                if encoding == 'gbk':
                    raise
    return pd.read_excel(name, sheet_name=sheet or 0, **options)

def selected(frame):
    # 先筛选再选列，筛选条件可以引用不输出的列。
    if opt.get('filter'):
        frame = frame.query(opt['filter'])
    if cols('columns'):
        frame = frame[cols('columns')]
    return frame

def missing(frame):
    return frame.isna() | frame.astype(str).apply(lambda column: column.str.strip() == '')

def atomic_save(frame, name):
    suffix = os.path.splitext(name)[1]
    fd, stage = tempfile.mkstemp(prefix='.minion-', suffix=suffix, dir=os.path.dirname(os.path.abspath(name)))
    os.close(fd)
    try:
        if suffix.lower() in ('.csv', '.tsv'):
            frame.to_csv(stage, index=False, encoding='utf-8-sig', sep='\t' if suffix.lower() == '.tsv' else ',')
        else:
            frame.to_excel(stage, index=False)
        os.replace(stage, name)
    finally:
        if os.path.exists(stage):
            os.unlink(stage)

def workbook(name, **kwargs):
    import zipfile
    with zipfile.ZipFile(name) as z:
        if sum(e.file_size for e in z.infolist()) > 300 * 1024 * 1024:
            raise ValueError('工作簿解压体积过大，未打开')
    from openpyxl import load_workbook
    return load_workbook(name, **kwargs)

def patch_cells():
    if os.path.realpath(output) == os.path.realpath(path):
        raise ValueError('不能覆盖源工作簿')
    changes = json.loads(opt.get('data') or '[]')
    if not isinstance(changes, list) or not changes or len(changes) > 20000:
        raise ValueError('data须为1到20000项[{sheet,cell,value}]')
    book = workbook(path, keep_vba=path.lower().endswith('.xlsm'), data_only=False)
    before = list(book.sheetnames)
    seen = set()
    try:
        for change in changes:
            sheet = change.get('sheet') or opt.get('sheet')
            cell = change.get('cell', '')
            if sheet not in book.sheetnames or not re.fullmatch(r'[A-Z]{1,3}[1-9][0-9]{0,6}', cell):
                raise ValueError('工作表或单元格地址非法: %s/%s' % (sheet, cell))
            from openpyxl.utils.cell import coordinate_to_tuple
            row, col = coordinate_to_tuple(cell)
            if row > 1048576 or col > 16384 or (sheet, cell) in seen:
                raise ValueError('单元格越界或重复: %s/%s' % (sheet, cell))
            seen.add((sheet, cell))
            value = change.get('value')
            if value is not None and not isinstance(value, (str, int, float, bool)):
                raise ValueError('单元格value只能是标量或null')
            if isinstance(value, int) and abs(value) >= 10 ** 15:
                raise ValueError('长单号必须用JSON字符串，不能用数值')
            if isinstance(value, str) and len(value) > 32767:
                raise ValueError('单元格文字超过Excel32767字符上限')
            target = book[sheet][cell]
            # 不把以等号开头的用户资料解释成公式；只有formula=true才写公式。
            target.value = value
            if isinstance(value, str) and not change.get('formula', False):
                target.data_type = 's'
        fd, stage = tempfile.mkstemp(suffix=os.path.splitext(output)[1], dir=os.path.dirname(os.path.abspath(output)))
        os.close(fd)
        try:
            book.save(stage)
            check = workbook(stage, keep_vba=path.lower().endswith('.xlsm'), data_only=False)
            try:
                if check.sheetnames != before:
                    raise ValueError('输出sheet校验失败')
                for change in changes:
                    sh = change.get('sheet') or opt.get('sheet')
                    if check[sh][change['cell']].value != change.get('value'):
                        # openpyxl把空字符串落盘为None，这不等于丢失非空数据。
                        if not (change.get('value') == '' and check[sh][change['cell']].value is None):
                            raise ValueError('回填值核验失败')
            finally:
                check.close()
            os.replace(stage, output)
        finally:
            if os.path.exists(stage): os.unlink(stage)
    finally:
        book.close()
    print('回填并重新打开核验通过: %s；修改%d格；保留sheet=%s' % (output, len(changes), before))
    print('保留普通单元格样式/公式；含复杂绘图/控件的工作簿仍须人工核对。')

def validate():
    d = load(path, opt.get('sheet', ''))
    result = {'file': path, 'sha256': digest(path), 'rows': len(d), 'columns': list(map(str, d.columns)),
              'duplicateRows': int(d.duplicated().sum()), 'missingByColumn': {str(c): int(missing(d)[c].sum()) for c in d.columns}}
    result['ids'] = {c: {'unique': int(d[c].nunique(dropna=False)), 'blank': int((d[c].fillna('').astype(str).str.strip() == '').sum())} for c in cols('textColumns')}
    sums = {}
    for c in cols('valueColumn'):
        numbers, invalid, blank, integer_digits, fractional_digits = [], 0, 0, 1, 0
        for val in d[c]:
            if pd.isna(val) or str(val).strip() == '':
                blank += 1
                continue
            try:
                raw = str(val).strip()
                if len(raw) > 200: raise ValueError('金额过长')
                number = Decimal(raw)
                if not number.is_finite(): raise InvalidOperation()
                digits = number.as_tuple()
                if abs(digits.exponent) > 200: raise ValueError('金额指数超限')
                integer_digits = max(integer_digits, len(digits.digits) + digits.exponent)
                fractional_digits = max(fractional_digits, -digits.exponent)
                numbers.append(number)
            except (InvalidOperation, ValueError):
                invalid += 1
        with localcontext() as context:
            context.prec = integer_digits + fractional_digits + len(str(len(numbers) + 1)) + 5
            total = sum(numbers, Decimal('0'))
        sums[c] = {'sum': str(total), 'nonNumericOrOutOfBounds': invalid, 'blank': blank,
                   'note': 'Decimal按读入值求和；空白、非数值/超过200字符或指数绝对值200的项另计，不能视为完整金额口径；不恢复源精度、不自动理解千分位/币种'}
    result['totals'] = sums
    if path.lower().endswith(('.xlsx', '.xlsm')):
        book = workbook(path, read_only=True, data_only=False)
        try:
            result['sheets'] = []
            for sh in book:
                formulas, errors = 0, 0
                for row in sh.iter_rows():
                    for cell in row:
                        formulas += cell.data_type == 'f'
                        errors += cell.data_type == 'e'
                result['sheets'].append({'name': sh.title, 'maxRow': sh.max_row, 'maxColumn': sh.max_column,
                                         'formulaCells': formulas, 'errorCells': errors})
        finally:
            book.close()
    emit(json.dumps(result, ensure_ascii=False))
    print('此检查不重算公式、不证明业务口径正确；19位单号若源单元格已存为浮点，无法恢复。')

if path:
    print('来源文件: %s；SHA256=%s' % (path, digest(path)))
if cols('textColumns'):
    print('文本列: %s；已损失的数值精度不能恢复，空值以空字符串保留。' % ','.join(cols('textColumns')))
if action == 'patch_cells':
    patch_cells()
elif action == 'validate':
    validate()
elif action == 'merge_audit':
    a, b = load(path, opt.get('sheet', '')), load(opt['otherPath'])
    keys = cols('on')
    if not keys: raise ValueError('merge_audit需要on键列')
    left, right = a[keys], b[keys]
    # 仅比较唯一键，不构造多对多乘积；空键单独统计。
    valid_left = ~(left.isna() | (left.astype(str).apply(lambda c: c.str.strip()) == '')).any(axis=1)
    valid_right = ~(right.isna() | (right.astype(str).apply(lambda c: c.str.strip()) == '')).any(axis=1)
    la, rb = left[valid_left].drop_duplicates(), right[valid_right].drop_duplicates()
    joined = pd.merge(la, rb, on=keys, how='outer', indicator=True)
    stats = joined['_merge'].value_counts().to_dict()
    print(json.dumps({'leftRows': len(a), 'rightRows': len(b),
                      'leftDuplicateKeyRows': int(left.duplicated(keep=False).sum()),
                      'rightDuplicateKeyRows': int(right.duplicated(keep=False).sum()),
                      'leftBlankKeyRows': int((left.isna() | (left.astype(str).apply(lambda c: c.str.strip()) == '')).any(axis=1).sum()),
                      'rightBlankKeyRows': int((right.isna() | (right.astype(str).apply(lambda c: c.str.strip()) == '')).any(axis=1).sum()),
                      'uniqueKeyMatches': {str(k): int(v) for k, v in stats.items()},
                      'unmatchedSample': joined[joined['_merge'] != 'both'].head(20).astype(str).to_dict('records')}, ensure_ascii=False))
    print('匹配统计覆盖全量唯一键；未匹配样例只显示20项，重复键可能导致行数膨胀，merge前需明确关系。')
elif action == 'inspect':
    names = ['CSV'] if path.lower().endswith(('.csv', '.tsv')) else pd.ExcelFile(path).sheet_names
    print('工作表: ' + ', '.join(map(str, names)))
    for n in names:
        d = load(path, '' if n == 'CSV' else n)
        print('[%s] 行=%d 列=%d 字段=%s' % (n, len(d), len(d.columns), list(map(str, d.columns))))
elif action in ('read', 'filter'):
    d = selected(load(path, opt.get('sheet', '')))
    start, count = opt['offset'], opt['rows']
    shown = d.iloc[start:start + count]
    next_offset = min(len(d), start + len(shown))
    print('分页: 结果总行数=%d offset=%d 本次=%d；不是全表；下一offset=%d；剩余=%d' % (len(d), start, len(shown), next_offset, max(0, len(d)-next_offset)))
    emit(shown.to_csv(index=False))
elif action == 'profile':
    d = load(path, opt.get('sheet', ''))
    print('全表统计: 行=%d 列=%d 缺失=%d 重复=%d' % (len(d), len(d.columns), int(missing(d).sum().sum()), int(d.duplicated().sum())))
    emit(d.describe(include='all').transpose().fillna('').to_csv())
elif action == 'batch_profile':
    for name in opt['paths'].split(';'):
        d = load(name)
        print(json.dumps({'file': name, 'rows': len(d), 'columns': len(d.columns), 'missing': int(missing(d).sum().sum()), 'duplicateRows': int(d.duplicated().sum())}, ensure_ascii=False))
elif action == 'write':
    obj = json.loads(opt.get('data') or '[]')
    d = pd.DataFrame(obj if isinstance(obj, list) else [obj])
    atomic_save(d, output)
    print('已写出%d行: %s' % (len(d), output))
elif action == 'append':
    obj = json.loads(opt.get('data') or '[]')
    d = pd.concat([load(path, opt.get('sheet', '')), pd.DataFrame(obj if isinstance(obj, list) else [obj])], ignore_index=True, sort=False)
    atomic_save(d, output)
    print('已追加并写出%d行: %s' % (len(d), output))
elif action == 'merge':
    a, b, keys = load(path, opt.get('sheet', '')), load(opt['otherPath']), cols('on')
    if not keys: raise ValueError('merge需要on键列')
    print('合并前键重复行(含全部重复项): 左=%d 右=%d；请用merge_audit核对关系' % (a.duplicated(keys, keep=False).sum(), b.duplicated(keys, keep=False).sum()))
    d = pd.merge(a, b, on=keys, how=opt.get('how') or 'inner')
    atomic_save(d, output)
    print('已合并写出%d行: %s' % (len(d), output))
elif action == 'pivot':
    d = load(path, opt.get('sheet', ''))
    d = pd.pivot_table(d, index=cols('index'), values=opt['valueColumn'], aggfunc=opt.get('agg') or 'sum').reset_index()
    atomic_save(d, output)
    print('透视已写出%d行: %s' % (len(d), output))
elif action == 'groupby':
    d = selected(load(path, opt.get('sheet', '')))
    d = d.groupby(cols('index'))[cols('valueColumn')].agg(opt.get('agg') or 'sum').reset_index()
    atomic_save(d, output)
    print('分组已写出%d行: %s' % (len(d), output))
elif action == 'export':
    d = selected(load(path, opt.get('sheet', '')))
    atomic_save(d, output)
    print('已导出%d行: %s' % (len(d), output))
elif action == 'chart':
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    d = selected(load(path, opt.get('sheet', '')))
    d.plot(x=opt.get('x') or None, y=cols('y') or None, kind=opt.get('chartType') or 'line')
    plt.tight_layout()
    plt.savefig(output, dpi=150)
    print('图表已生成，需ViewImage复核: ' + output)
else:
    raise ValueError('不支持的Excel动作: ' + action)
