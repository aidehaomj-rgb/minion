# -*- coding: utf-8 -*-
"""从已验证的 V40/V41/V42/V43 JAR 制作一个内容基线通用的小更新包。Python3.7。"""
import hashlib
import json
import pathlib
import shutil
import struct
import subprocess
import uuid
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools/win7-updater'
NAME = 'minion-win7-v44-small-from-v40-v43-20261104'
PACKAGE = ROOT / 'dist' / (NAME + '-package')
ARCHIVE = ROOT / 'dist' / (NAME + '.zip')
WORK = ROOT / 'target' / ('v44-package-work-' + uuid.uuid4().hex)
JDK = next((ROOT / 'target/build-tools-memory').glob('zulu8*'))
JAVA, JAVAC = str(JDK / 'bin/java.exe'), str(JDK / 'bin/javac.exe')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contents(path):
    with zipfile.ZipFile(str(path)) as z:
        out = {}
        for n in z.namelist():
            if n.endswith('/'):
                continue
            if n in out:
                raise RuntimeError('Duplicate entry: ' + n)
            out[n] = digest(z.read(n))
        return out


def read_manifest(path):
    result = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        h, name = line.split('\t', 1)
        assert len(h) == 64 and name not in result
        result[name] = h
    return result


def write_manifest(path, entries):
    path.write_bytes(('\r\n'.join(h + '\t' + n for n, h in sorted(entries.items())) + '\r\n').encode('utf-8'))


def java(*args):
    run = subprocess.run([JAVA, '-Dfile.encoding=UTF-8'] + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = run.stdout.decode('utf-8', errors='replace')
    if run.returncode:
        raise RuntimeError(output)
    return output


def main():
    if PACKAGE.exists() or ARCHIVE.exists():
        raise RuntimeError('Do not overwrite an existing/delivered V44 package')
    WORK.mkdir(parents=True)
    target = ROOT / 'target/minion-0.1.0.jar'
    bases = {}
    for version, jarname, package in [
        ('v40', 'v40-base.jar', 'minion-win7-v40-small-from-v39-20261026-package'),
        ('v41', 'v41-patched-test.jar', 'minion-win7-v41-small-from-v40-20261026-package'),
        ('v42', 'v42-patched-test.jar', 'minion-win7-v42-small-from-v40-20261102-package'),
    ]:
        source_manifest = ROOT / 'dist' / package / 'payload' / (version + '-entries.tsv')
        sidecar = pathlib.Path(str(source_manifest) + '.sha256')
        assert digest(source_manifest.read_bytes()).upper() == sidecar.read_text().strip().upper()
        expected = read_manifest(source_manifest)
        candidates = sorted((ROOT / 'target').glob('v4*-package-work-*/' + jarname))
        for candidate in candidates:
            if contents(candidate) == expected:
                bases[version] = candidate
                break
        if version not in bases:
            raise RuntimeError('Verified cached baseline unavailable: ' + version)
    bases['v43'] = ROOT / 'target/v43-offline-stage/Minion-Win7-x64-offline-V43/minion-0.1.0.jar'
    assert digest(bases['v43'].read_bytes()).upper() == '3144122922C0A6E215D259256332147A9917DF62326A2257163D53A71B57F234'
    expected = contents(target)
    base_maps = {v: contents(p) for v, p in bases.items()}
    changed = set()
    for version, entries in base_maps.items():
        if set(entries) - set(expected):
            raise RuntimeError('Removed entries cannot be handled by this patch: ' + version)
        changed.update(n for n in expected if entries.get(n) != expected[n])
    allowed_metadata = {'META-INF/LICENSE', 'META-INF/LICENSE.txt', 'META-INF/AL2.0', 'META-INF/LGPL2.1',
                        'META-INF/maven/com.minion/minion/pom.xml', 'theme/theme.css'}
    with zipfile.ZipFile(str(target)) as z:
        for n in changed:
            if not (n.startswith('com/minion/') or n.startswith('com/sun/jna/') or n in allowed_metadata):
                raise RuntimeError('Unexpected patch scope: ' + n)
            if n.endswith('.class'):
                b = z.read(n)
                assert b[:4] == b'\xca\xfe\xba\xbe' and struct.unpack('>H', b[6:8])[0] <= 52
        payload = WORK / 'package/payload'
        payload.mkdir(parents=True)
        patch = payload / 'entries-patch.zip'
        with zipfile.ZipFile(str(patch), 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as out:
            for n in sorted(changed):
                out.writestr(n, z.read(n))
    compile_result = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-d', str(payload)] +
                                    [str(TOOLS / n) for n in ['JarPatchApplier.java', 'JarEntryVerifier.java', 'MinionSmallUpdate.java']],
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if compile_result.returncode:
        raise RuntimeError(compile_result.stdout.decode('utf-8', errors='replace'))
    for version, entries in base_maps.items():
        write_manifest(payload / (version + '-entries.tsv'), entries)
    write_manifest(payload / 'v44-entries.tsv', expected)
    for p in list(payload.iterdir()):
        pathlib.Path(str(p) + '.sha256').write_bytes((digest(p.read_bytes()).upper() + '\r\n').encode('ascii'))
    java('-cp', str(payload), 'MinionSmallUpdate', 'verify', str(payload))

    validations = []
    for version, source in bases.items():
        # 只操作本次创建的测试目录，不使用用户的真实安装目录。
        install = WORK / ('install-' + version + '-测试 空格 & !')
        install.mkdir()
        installed_jar = install / 'minion-0.1.0.jar'
        shutil.copyfile(str(source), str(installed_jar))
        for name in ['model.json', 'workspace.json', 'config.properties', '.minion/memory/keep.md',
                     'session/test.json', 'project/keep.xlsx', 'jdk8/runtime-sentinel', 'python/runtime-sentinel']:
            f = install / name
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(('preserve exactly: ' + name).encode('utf-8'))
        sentinels = {str(p.relative_to(install)): digest(p.read_bytes()) for p in install.rglob('*') if p.is_file() and p != installed_jar}
        output = java('-cp', str(payload), 'MinionSmallUpdate', 'apply', str(payload), str(install))
        assert contents(installed_jar) == expected
        backups = list((install / 'backup').glob('*.bak'))
        assert len(backups) == 1 and backups[0].read_bytes() == source.read_bytes()
        for relative, h in sentinels.items():
            assert digest((install / relative).read_bytes()) == h, relative
        before_repeat = installed_jar.read_bytes()
        repeated = java('-cp', str(payload), 'MinionSmallUpdate', 'apply', str(payload), str(install))
        assert 'Already V44' in repeated and installed_jar.read_bytes() == before_repeat
        assert len(list((install / 'backup').glob('*.bak'))) == 1
        validations.append({'base': version, 'entries': len(expected), 'backupExact': True, 'dataUnchanged': True, 'repeatNoOp': True})

    # 不支持/被改动的安装包必须拒绝，且不创建备份或替换原件。
    bad = WORK / 'unsupported-install'
    bad.mkdir()
    shutil.copyfile(str(bases['v40']), str(bad / 'minion-0.1.0.jar'))
    with zipfile.ZipFile(str(bad / 'minion-0.1.0.jar'), 'a') as z:
        z.writestr('unsupported-marker.txt', b'custom jar')
    bad_bytes = (bad / 'minion-0.1.0.jar').read_bytes()
    try:
        java('-cp', str(payload), 'MinionSmallUpdate', 'apply', str(payload), str(bad))
        raise AssertionError('Unsupported baseline was accepted')
    except RuntimeError as e:
        assert 'not a supported' in str(e)
    assert (bad / 'minion-0.1.0.jar').read_bytes() == bad_bytes and not (bad / 'backup').exists()

    # 合法校验和但内容不足的补丁：应用后验证失败，原JAR必须逐字节保留。
    broken_payload = WORK / 'broken-payload'
    shutil.copytree(str(payload), str(broken_payload))
    with zipfile.ZipFile(str(broken_payload / 'entries-patch.zip'), 'w') as z:
        z.writestr('invalid-marker.txt', b'invalid patch')
    (broken_payload / 'entries-patch.zip.sha256').write_text(digest((broken_payload / 'entries-patch.zip').read_bytes()), encoding='ascii')
    broken_install = WORK / 'broken-patch-install'
    broken_install.mkdir()
    shutil.copyfile(str(bases['v40']), str(broken_install / 'minion-0.1.0.jar'))
    try:
        java('-cp', str(broken_payload), 'MinionSmallUpdate', 'apply', str(broken_payload), str(broken_install))
        raise AssertionError('Incomplete patch was accepted')
    except RuntimeError as e:
        assert 'Patched content validation failed' in str(e)
    assert (broken_install / 'minion-0.1.0.jar').read_bytes() == bases['v40'].read_bytes()
    assert not list(broken_install.glob('.minion-*.new'))

    pkg = WORK / 'package'
    script = (TOOLS / 'Update-Minion-V44-Small.cmd').read_text(encoding='ascii').replace('\r\n', '\n')
    (pkg / 'Update-Minion-V44-Small.cmd').write_bytes(script.replace('\n', '\r\n').encode('ascii'))
    readme = '''Minion V44 小更新包（V40 / V41 / V42 / V43 通用）

【使用步骤】
1. 先退出全部 Minion 窗口。
   安装目录不要包含感叹号 !，否则JNA原生组件可能加载失败；普通中文和空格目录已验证。
2. 把整个 ZIP 完整解压到普通目录，不要只取出 CMD 或在压缩包里运行。
3. 双击 Update-Minion-V44-Small.cmd。
4. 输入原安装目录（里面有 minion-0.1.0.jar），检测到支持版本后按 Y。
5. 出现 V44 update completed successfully 后，按原来的方式启动 Minion。
6. 设置→关于应显示 V44。无需重配模型或重新安装 Java/Python。

【本次内容】
执行中压缩后，在原回合接着做当前未完成步骤，不再因压缩自动回读旧历史。
最新用户要求、任务/指令修订、清单、断点及工具回执优先；旧任务待办不当当前指令。
新增本地 Artwork：高清 PNG、可编辑 SVG/JSON、中文字体、换行与布局警告。
适合流程图、封面、信息图及排版；统计图仍用真实数据的 Matplotlib。
这不是专用 AI 照片生图，不新增生图模型服务，也不自动换主/子模型。
保留150K默认压缩阈值、结束绿灯，并包含此前启动按需加载及Win7兼容改动。

【适用与保护】
自动识别内容完全匹配的 V40、V41、V42、V43；不要求先装中间小包。
只补程序JAR内差异；旧版缺失的JNA兼容组件会随差异一起补齐。
不覆盖 model.json、config.properties、会话、知识库、项目文件或 Java/Python 目录。
旧JAR自动保存在安装目录 backup 下，文件名含 pre-v44。
版本不匹配或校验失败不要强制安装；把错误和当前版本发来再处理。
可先运行 Update-Minion-V44-Small.cmd /verify 仅校验更新包，不更新程序。
若更新后需回退：退出Minion，把本次backup中的旧JAR复制回安装目录并命名为minion-0.1.0.jar。
回退只恢复程序，不撤销更新后产生的聊天/项目数据。

【验证边界】
本机Java8完整回归1488项，0失败0错误，1项OCR因环境跳过；版本标识另有定向回归。
已回放四种基线的更新、备份、用户文件不变、重复运行和异常补丁拒绝。
尚未在你的内网Win7真机试用，请保留自动备份。
'''
    (pkg / '安装说明.txt').write_bytes(b'\xef\xbb\xbf' + readme.replace('\n', '\r\n').encode('utf-8'))
    for name in ['本地绘图与模型能力.md', 'long-conversation-memory.md']:
        body = (ROOT / 'docs' / name).read_text(encoding='utf-8')
        body = body.replace('(releases/V44-压缩续接与本地绘图小更新.md)', '(安装说明.txt)')
        (pkg / name).write_text(body, encoding='utf-8')
    result = {'release': 'V44', 'date': '2026-11-04', 'supportedBases': list(bases), 'patchEntries': len(changed),
              'targetJarSHA256': digest(target.read_bytes()).upper(), 'targetEntryCount': len(expected),
              'validations': validations, 'unsupportedRejected': True, 'incompletePatchRejected': True,
              'Win7IntranetTested': False}
    (pkg / '更新信息.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    sums = [digest(f.read_bytes()).upper() + '  ' + f.relative_to(pkg).as_posix() for f in sorted(pkg.rglob('*')) if f.is_file()]
    (pkg / 'SHA256SUMS.txt').write_bytes(('\r\n'.join(sums) + '\r\n').encode('utf-8'))
    shutil.copytree(str(pkg), str(PACKAGE))
    with zipfile.ZipFile(str(ARCHIVE), 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in sorted(PACKAGE.rglob('*')):
            if f.is_file():
                z.write(str(f), f.relative_to(PACKAGE).as_posix())
    with zipfile.ZipFile(str(ARCHIVE)) as z:
        assert z.testzip() is None
        for line in z.read('SHA256SUMS.txt').decode('utf-8').splitlines():
            h, n = line.split('  ', 1)
            assert digest(z.read(n)).upper() == h
        assert not any(n.endswith('minion-0.1.0.jar') for n in z.namelist())
    outer_hash = digest(ARCHIVE.read_bytes()).upper()
    pathlib.Path(str(ARCHIVE) + '.sha256').write_bytes((outer_hash + '\r\n').encode('ascii'))
    pathlib.Path(str(ARCHIVE) + '.verify.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    (ROOT / 'target/v44-package-work-path.txt').write_text(str(WORK), encoding='utf-8')
    print(json.dumps({'archive': str(ARCHIVE), 'bytes': ARCHIVE.stat().st_size, 'sha256': outer_hash,
                      'changedEntries': len(changed), 'work': str(WORK)}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
