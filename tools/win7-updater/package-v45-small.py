# -*- coding: utf-8 -*-
"""V45 content-verified cumulative small update. Build tooling only; Python 3.7 stdlib."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools/win7-updater'
NAME = 'minion-win7-v45-small-from-v40-v44-20261105'
PACKAGE = ROOT / 'dist' / (NAME + '-package')
ARCHIVE = ROOT / 'dist' / (NAME + '.zip')
DELIVERY = Path('D:/chrome浏览器下载/win7-agent-main')
RESUME = len(sys.argv) == 3 and sys.argv[1] == '--resume-tested-stage'
WORK = Path(sys.argv[2]).resolve() if RESUME else ROOT / 'target' / ('v45-package-work-' + uuid.uuid4().hex)
JDK = next((ROOT / 'target/build-tools-memory').glob('zulu8*'))
JAVA = str(JDK / 'bin/java.exe')
JAVAC = str(JDK / 'bin/javac.exe')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contents(path):
    result = {}
    with zipfile.ZipFile(str(path)) as z:
        assert z.testzip() is None, str(path)
        for item in z.infolist():
            if item.is_dir():
                continue
            assert item.filename not in result, 'Duplicate JAR entry'
            result[item.filename] = digest(z.read(item))
    return result


def manifest(path):
    result = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        h, n = line.split('\t', 1)
        assert re.fullmatch('[0-9a-f]{64}', h) and n not in result
        result[n] = h
    assert result
    return result


def write_manifest(path, entries):
    path.write_bytes(('\r\n'.join(h + '\t' + n for n, h in sorted(entries.items())) + '\r\n').encode('utf-8'))


def run(args, name, data=None, expect=0):
    env = os.environ.copy()
    env['MINION_JAVA'] = JAVA
    env['JAVA_HOME'] = str(JDK)
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            env=env, timeout=180)
    (WORK / 'logs').mkdir(exist_ok=True)
    (WORK / 'logs' / (name + '.log')).write_bytes(result.stdout)
    output = result.stdout.decode('utf-8', errors='replace')
    if result.returncode != expect:
        raise RuntimeError('%s: exit %s != %s\n%s' % (name, result.returncode, expect, output))
    return output


def java(payload, action, installation=None, name='java', expect=0):
    args = [JAVA, '-Dfile.encoding=UTF-8', '-cp', str(payload), 'MinionSmallUpdate', action, str(payload)]
    if installation is not None:
        args.append(str(installation))
    return run(args, name, expect=expect)


def cmd(script, argument, name, data=b'Y\r\n\r\n', expect=0):
    # Raw Windows command line: list2cmdline escapes quotes for CRT, not cmd /s /c.
    # Only these explicitly created fixture/program paths are passed; no filesystem deletion.
    command = 'cmd.exe /d /s /c ""%s" "%s""' % (script, argument)
    return run(command, name, data=data, expect=expect)


def fixture(name, baseline, sentinels=False):
    install = WORK / name
    install.mkdir()
    shutil.copyfile(str(baseline), str(install / 'minion-0.1.0.jar'))
    keep = {}
    if sentinels:
        for n in ['model.json', 'workspace.json', 'config.properties', 'session/test.json',
                  '.minion/memory/keep.md', 'knowledge/keep.md', 'project/keep.xlsx',
                  'jdk8/runtime-sentinel', 'python/runtime-sentinel']:
            p = install / n
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(('preserve exactly: ' + n).encode('utf-8'))
            keep[n] = digest(p.read_bytes())
    return install, keep


def assert_preserved(install, keep, source):
    backups = list((install / 'backup').glob('*.bak'))
    assert len(backups) == 1 and backups[0].read_bytes() == source.read_bytes()
    for n, h in keep.items():
        assert digest((install / n).read_bytes()) == h, n
    return backups[0]


def main():
    if PACKAGE.exists() or ARCHIVE.exists() or (DELIVERY / ARCHIVE.name).exists():
        raise RuntimeError('Refusing to overwrite an existing/delivered V45 package')
    assert DELIVERY.is_dir(), 'Delivery directory unavailable'
    WORK.mkdir(parents=True)
    (ROOT / 'target/v45-package-work-path.txt').write_text(str(WORK), encoding='utf-8')

    # Require the release build including tests; never package a stale V44 JAR as V45.
    build = (ROOT / 'target/v45-release-build.log').read_text(encoding='utf-8', errors='replace')
    totals = re.findall(r'^\[(?:INFO|WARNING|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$', build, re.M)
    assert 'BUILD SUCCESS' in build and totals, 'Release build not completed successfully'
    tests, failures, errors, skipped = map(int, totals[-1])
    assert failures == errors == 0 and tests >= 1757
    target = ROOT / 'target/minion-0.1.0.jar'
    expected = contents(target)
    with zipfile.ZipFile(str(target)) as z:
        assert b'V45' in z.read('com/minion/gui/dialog/SettingsDialog.class')
        for required in ['TaskWorkflow', 'TaskValidation', 'ChildTaskHost', 'ChildTaskCoordinator', 'ToolSchemaRouter']:
            assert 'com/minion/core/agent/' + required + '.class' in expected

    bases = {}
    for version, filename, folder in [
        ('v40', 'v40-base.jar', 'minion-win7-v40-small-from-v39-20261026-package'),
        ('v41', 'v41-patched-test.jar', 'minion-win7-v41-small-from-v40-20261026-package'),
        ('v42', 'v42-patched-test.jar', 'minion-win7-v42-small-from-v40-20261102-package'),
    ]:
        mf = ROOT / 'dist' / folder / 'payload' / (version + '-entries.tsv')
        assert digest(mf.read_bytes()).upper() == Path(str(mf) + '.sha256').read_text().strip().upper()
        baseline_map = manifest(mf)
        for candidate in sorted((ROOT / 'target').glob('v4*-package-work-*/' + filename)):
            if contents(candidate) == baseline_map:
                bases[version] = candidate
                break
        assert version in bases, 'No verified baseline: ' + version
    bases['v43'] = ROOT / 'target/v43-offline-stage/Minion-Win7-x64-offline-V43/minion-0.1.0.jar'
    assert digest(bases['v43'].read_bytes()).upper() == '3144122922C0A6E215D259256332147A9917DF62326A2257163D53A71B57F234'
    old = Path((ROOT / 'target/v45-baseline-path.txt').read_text(encoding='utf-8'))
    bases['v44'] = old / 'v44-base.jar'
    assert digest(bases['v44'].read_bytes()).upper() == '470EE3067FF4652AFF6EB2950C4B174E1CD4285F7092C3002B98268CFC93865B'
    maps = {v: contents(p) for v, p in bases.items()}
    changed = set()
    for version, before in maps.items():
        removed = set(before) - set(expected)
        assert not removed, 'Removal not supported: ' + version + ' ' + repr(sorted(removed)[:10])
        changed.update(n for n in expected if before.get(n) != expected[n])

    pkg = WORK / 'package'
    payload = pkg / 'payload'
    payload.mkdir(parents=True)
    metadata = {'META-INF/MANIFEST.MF', 'META-INF/LICENSE', 'META-INF/LICENSE.txt', 'META-INF/AL2.0',
                'META-INF/LGPL2.1', 'META-INF/maven/com.minion/minion/pom.xml'}
    with zipfile.ZipFile(str(target)) as z, zipfile.ZipFile(str(payload / 'entries-patch.zip'), 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for n in sorted(changed):
            assert n.startswith(('com/minion/', 'com/sun/jna/', 'workflows/', 'theme/')) or n in metadata, 'Unexpected patch scope: ' + n
            data = z.read(n)
            if n.endswith('.class'):
                assert data[:4] == b'\xca\xfe\xba\xbe' and struct.unpack('>H', data[6:8])[0] <= 52
            out.writestr(n, data)
    run([JAVAC, '-encoding', 'UTF-8', '-d', str(payload), str(TOOLS / 'JarPatchApplier.java'),
         str(TOOLS / 'JarEntryVerifier.java'), str(TOOLS / 'v45/MinionSmallUpdate.java')], 'compile-updater')
    for version, before in maps.items():
        write_manifest(payload / (version + '-entries.tsv'), before)
    write_manifest(payload / 'v45-entries.tsv', expected)
    for p in list(payload.iterdir()):
        Path(str(p) + '.sha256').write_bytes((digest(p.read_bytes()).upper() + '\r\n').encode('ascii'))
    java(payload, 'verify', name='payload-verify')
    updater = pkg / 'Update-Minion-V45-Small.cmd'
    shutil.copyfile(str(TOOLS / updater.name), str(updater))

    validations = []
    for version, source in bases.items():
        install, keep = fixture('install-' + version + '-测试 空格 & !', source, sentinels=True)
        java(payload, 'apply', install, 'apply-' + version)
        assert contents(install / 'minion-0.1.0.jar') == expected
        backup = assert_preserved(install, keep, source)
        before_repeat = (install / 'minion-0.1.0.jar').read_bytes()
        repeat = java(payload, 'apply', install, 'repeat-' + version)
        assert 'Already V45' in repeat and (install / 'minion-0.1.0.jar').read_bytes() == before_repeat
        assert len(list((install / 'backup').glob('*.bak'))) == 1
        # Manual rollback is exactly the user's documented operation, only in this fixture.
        shutil.copyfile(str(backup), str(install / 'minion-0.1.0.jar'))
        assert (install / 'minion-0.1.0.jar').read_bytes() == source.read_bytes()
        validations.append({'base': version, 'allTargetEntriesMatch': True, 'backupExact': True,
                            'dataUnchanged': True, 'repeatNoOp': True, 'manualRollbackExact': True})

    unsupported, _ = fixture('unsupported-install', bases['v44'])
    with zipfile.ZipFile(str(unsupported / 'minion-0.1.0.jar'), 'a') as z:
        z.writestr('unsupported-marker.txt', b'custom jar')
    original = (unsupported / 'minion-0.1.0.jar').read_bytes()
    failure = java(payload, 'apply', unsupported, 'unsupported-rejected', expect=1)
    assert 'not a supported' in failure and (unsupported / 'minion-0.1.0.jar').read_bytes() == original
    assert not (unsupported / 'backup').exists()

    broken = WORK / 'broken-payload'
    shutil.copytree(str(payload), str(broken))
    with zipfile.ZipFile(str(broken / 'entries-patch.zip'), 'w') as z:
        z.writestr('invalid-marker.txt', b'invalid patch')
    (broken / 'entries-patch.zip.sha256').write_text(digest((broken / 'entries-patch.zip').read_bytes()), encoding='ascii')
    install, _ = fixture('incomplete-patch-install', bases['v44'])
    failure = java(broken, 'apply', install, 'incomplete-patch-rejected', expect=1)
    assert 'Patched content validation failed' in failure
    assert (install / 'minion-0.1.0.jar').read_bytes() == bases['v44'].read_bytes()
    assert not list(install.glob('.minion-*.new'))

    # Inject a failure only into a separate TEST helper immediately after replacement;
    # the published helper does not contain the injected branch.
    faulty = WORK / 'post-replace-fault-payload'
    shutil.copytree(str(payload), str(faulty))
    injected = WORK / 'fault-src/MinionSmallUpdate.java'
    injected.parent.mkdir()
    code = (TOOLS / 'v45/MinionSmallUpdate.java').read_text(encoding='utf-8')
    needle = 'if(!entries(jar).equals(target))throw new IOException("Final content validation failed.");'
    assert needle in code
    code = code.replace(needle, 'if(Files.exists(jar))throw new IOException("QA injected post-replace failure");\n            ' + needle)
    injected.write_text(code, encoding='utf-8')
    run([JAVAC, '-encoding', 'UTF-8', '-cp', str(faulty), '-d', str(faulty), str(injected)], 'compile-fault-fixture')
    (faulty / 'MinionSmallUpdate.class.sha256').write_text(digest((faulty / 'MinionSmallUpdate.class').read_bytes()), encoding='ascii')
    install, keep = fixture('post-replace-rollback-install', bases['v44'], sentinels=True)
    failure = java(faulty, 'apply', install, 'post-replace-auto-rollback', expect=1)
    assert 'Previous JAR restored from backup.' in failure
    assert (install / 'minion-0.1.0.jar').read_bytes() == bases['v44'].read_bytes()
    assert_preserved(install, keep, bases['v44'])
    assert not list(install.glob('.minion-*.new'))

    cmdpkg = WORK / 'cmd-package-中文 空格 & !'
    shutil.copytree(str(pkg), str(cmdpkg))
    output = cmd(cmdpkg / updater.name, '/verify', 'cmd-verify', data=b'\r\n')
    assert 'Package SHA-256 checks passed' in output
    for version in ['v40', 'v44']:
        install, keep = fixture('cmd-install-' + version + '-中文 空格 & !', bases[version], sentinels=True)
        output = cmd(cmdpkg / updater.name, install, 'cmd-apply-' + version)
        assert 'V45 update completed successfully.' in output
        assert contents(install / 'minion-0.1.0.jar') == expected
        assert_preserved(install, keep, bases[version])
    damaged = WORK / 'cmd-damaged-package'
    shutil.copytree(str(cmdpkg), str(damaged))
    patch = damaged / 'payload/entries-patch.zip'
    patch.write_bytes(patch.read_bytes() + b'corrupt')
    output = cmd(damaged / updater.name, '/verify', 'cmd-corrupt-rejected', data=b'\r\n', expect=1)
    assert 'checksum mismatch' in output

    finish_release(target, expected, changed, bases, validations, (tests, failures, errors, skipped))


def finish_release(target, expected, changed, bases, validations, counts):
    tests, failures, errors, skipped = counts
    pkg = WORK / 'package'
    payload = pkg / 'payload'
    updater = pkg / 'Update-Minion-V45-Small.cmd'

    # Real runtime and controls from the CMD-patched JAR, not target/classes or the old V44 app.
    cmd_patched = WORK / 'cmd-install-v44-中文 空格 & !/minion-0.1.0.jar'
    assert contents(cmd_patched) == expected
    # Java8 jar: resource URLs misparse a directory ending in ! (the sequence !/).
    # Keep the extreme path as an updater-only test, and test actual runtime at a supported
    # Chinese/space/&/internal-! path. Document the limitation instead of hiding the failed QA.
    runtime_dir = WORK / 'runtime-中文 空格 & ! 路径'
    runtime_dir.mkdir(exist_ok=True)
    patched = runtime_dir / 'minion-0.1.0.jar'
    if patched.exists():
        assert patched.read_bytes() == cmd_patched.read_bytes()
    else:
        shutil.copyfile(str(cmd_patched), str(patched))
    qa = WORK / 'qa'
    qa.mkdir(exist_ok=True)
    testcp = str(ROOT / 'target/test-classes') + os.pathsep + str(patched)
    run([JAVAC, '-encoding', 'UTF-8', '-cp', testcp, '-d', str(qa),
         str(TOOLS / 'v45/PackageSmokeCheckV45.java'), str(TOOLS / 'VerifyLocalArtwork.java'),
         str(ROOT / 'tools/qa/VerifyQuestionImageComposer.java')], 'compile-runtime-qa')
    runtimecp = str(qa) + os.pathsep + str(patched)
    output = run([JAVA, '-Dfile.encoding=UTF-8', '-Dprism.order=sw', '-cp', runtimecp,
                  'PackageSmokeCheckV45'], 'patched-jar-runtime-smoke')
    assert 'PASS V45 patched JAR' in output
    artwork = WORK / ('artwork-verified-' + uuid.uuid4().hex)
    run([JAVA, '-Dfile.encoding=UTF-8', '-Dprism.order=sw', '-cp', runtimecp,
         'VerifyLocalArtwork', str(artwork)], 'patched-jar-artwork')
    for kind in ['cover', 'process', 'dashboard']:
        assert (artwork / (kind + '.png')).stat().st_size > 1000
        assert (artwork / (kind + '.svg')).is_file()
    fxcp = str(qa) + os.pathsep + testcp
    output = run([JAVA, '-Dfile.encoding=UTF-8', '-Dprism.order=sw', '-cp', fxcp,
                  'VerifyQuestionImageComposer'], 'patched-jar-question-image-javafx')
    assert 'PASS:' in output
    output = run([JAVA, '-Dfile.encoding=UTF-8', '-Dprism.order=sw', '-cp',
                  fxcp + os.pathsep + str(ROOT / 'target/batch-e-isolated'),
                  'com.minion.gui.ReliabilityModelCapabilitiesUiSmoke'], 'patched-jar-capability-javafx')
    assert 'PASS JavaFX8:' in output

    info = {'release': 'V45', 'date': '2026-11-05', 'supportedBases': list(bases),
            'patchEntries': len(changed), 'targetEntryCount': len(expected),
            'referenceBuiltJarSHA256': digest(target.read_bytes()).upper(),
            'targetEntryManifestSHA256': digest((payload / 'v45-entries.tsv').read_bytes()).upper(),
            'validation': validations, 'unsupportedRejected': True, 'incompletePatchRejected': True,
            'postReplaceAutomaticRollback': True, 'cmdVerifyPassed': True, 'cmdUpdateBasesTested': ['v40', 'v44'],
            'cmdChecksumCorruptionRejected': True,
            'patchedJarRuntimeSmoke': True, 'patchedJarArtworkRendered': True, 'patchedJarJavafxControls': True,
            'reusedProtocolEvidence': RESUME,
            'runtimePathLimitation': 'Java8 jar resource URLs do not support a directory name ending in !; updater-only path tests are not whole-app certification.',
            'featureRegression': {'tests': tests, 'failures': failures, 'errors': errors, 'skipped': skipped,
                                  'skipReason': 'optional OCR runtime not configured'},
            'Win7IntranetTested': False, 'userAnaconda376Tested': False,
            'note': 'Content manifests validate installed JAR; ZIP order/timestamps may differ from reference JAR SHA256. Remaining roadmap tasks are not claimed complete.'}
    (pkg / '更新信息.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    readme = '''Minion V45 小更新包（已有 V40 / V41 / V42 / V43 / V44 可用）

【更新步骤】
1. 退出全部 Minion 窗口；建议先复制一份整个安装目录备份。
   建议普通中文/空格安装目录；任何一级目录名不要以 ! 结尾（Java8会把 !/ 误判为JAR资源分隔符）。
2. 把整个ZIP完整解压到本地文件夹，不能只取CMD或直接在压缩包里点运行。
3. 双击 Update-Minion-V45-Small.cmd，输入包含 minion-0.1.0.jar 的原安装目录。
4. 检测到支持的版本后按 Y；显示 V45 update completed successfully 即完成。
5. 按原来的方式启动；设置→关于应显示 V45。无需重装Java/Python或重新配置模型。

【本次更新】
提问回答可附图/仅图；拒收保留草稿，不误发到新任务。
任务步骤、指令修订、压缩后当前未完成步骤续跑、实际进展续接预算。
记忆替代/冲突/来源文件失效、中文关键词索引和按需检索。
真实Python/Maven/Gradle/npm测试发现、源码/日志/报告版本证据与完成核对。
模型能力显式探测、实际usage校准、按需工具、分项耗时；默认150K压缩目标保持不变。
异步有界子任务、同主模型、范围隔离、worktree、父复核、合并后实际验证和取消恢复。
浏览器动作/下载/分页证据、交付物检查；修复中文引号命令和含!路径JNA加载。
关于页继续不带个人联系方式。不新增OpenAI服务或在线安装依赖。

【保留内容】
只更新JAR差异；保留model.json、config.properties、workspace.json、会话、记忆、知识库、项目及Java/Python目录。
自动核验V40-V44内容基线，不要求逐个安装中间版本。更早版本/自改JAR不强行套用。
旧JAR自动保存到安装目录backup下，文件名含pre-v45；失败时不提交新JAR，替换后异常会尝试恢复旧JAR。
重复更新已是V45的程序不会重复改写或备份。
可先运行 Update-Minion-V45-Small.cmd /verify 只检查包，不更新程序。

【回退】
退出Minion；到安装目录backup找到本次pre-v45的.bak，复制回原安装目录，改名为minion-0.1.0.jar并替换。
回退只恢复程序，不撤销新版本使用期间生成的会话/项目数据。

【已测与未测】
本机Java8完整回归1757项，0失败0错误，1项可选OCR环境跳过；JavaFX输入/能力面板实跑通过。
打包回放5种基线、用户文件保持、备份/回退、重复执行、异常补丁拒绝及替换后故障自动恢复。
CMD完整交互更新在V40和V44、中文/空格/&/!测试目录回放；程序界面、资源、原生加载在含中文/空格/&/非末尾!的目录验证。
已知限制：目录名以!结尾时Java8的JAR资源读取会失败，不能把更新器路径测试当成整个应用支持该目录。
尚未在你的内网Win7、真实DeepSeek/Qwen及Anaconda3.7.6试机，请保留备份。
不是把8批47项全部宣称完成；复杂自动修复、合并后父修复再核验、原生Excel/Office业务视觉、EXE/APP实际运行等仍需后续完善和实测。
文件/尺寸/架构检查不代表业务正确或程序已运行；无专用生图服务不声称照片级模型生图。
本包不带完整运行环境，不适合尚未安装Minion的新用户。
'''
    (pkg / '安装说明.txt').write_bytes(b'\xef\xbb\xbf' + readme.replace('\n', '\r\n').encode('utf-8'))
    shutil.copyfile(str(ROOT / 'docs/acceptance/内网八批次回放验收.md'), str(pkg / '内网回放验收.md'))
    shutil.copyfile(str(ROOT / 'tools/qa/summarize_agent_replays.py'), str(pkg / 'summarize_agent_replays.py'))
    sums = [digest(p.read_bytes()).upper() + '  ' + p.relative_to(pkg).as_posix()
            for p in sorted(pkg.rglob('*')) if p.is_file()]
    (pkg / 'SHA256SUMS.txt').write_bytes(('\r\n'.join(sums) + '\r\n').encode('utf-8'))
    shutil.copytree(str(pkg), str(PACKAGE))
    with zipfile.ZipFile(str(ARCHIVE), 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(PACKAGE.rglob('*')):
            if p.is_file():
                z.write(str(p), p.relative_to(PACKAGE).as_posix())
    with zipfile.ZipFile(str(ARCHIVE)) as z:
        assert z.testzip() is None
        assert not any(n.endswith('minion-0.1.0.jar') for n in z.namelist())
        assert all(not n.startswith(('/', '../')) and '\\' not in n for n in z.namelist())
        for line in z.read('SHA256SUMS.txt').decode('utf-8').splitlines():
            h, n = line.split('  ', 1)
            assert digest(z.read(n)).upper() == h
    outer_hash = digest(ARCHIVE.read_bytes()).upper()
    Path(str(ARCHIVE) + '.sha256').write_bytes((outer_hash + '\r\n').encode('ascii'))
    Path(str(ARCHIVE) + '.verify.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    for p in [ARCHIVE, Path(str(ARCHIVE) + '.sha256'), Path(str(ARCHIVE) + '.verify.json')]:
        dest = DELIVERY / p.name
        assert not dest.exists()
        shutil.copyfile(str(p), str(dest))
        assert digest(dest.read_bytes()) == digest(p.read_bytes())
    instructions = DELIVERY / 'Minion-V45-小更新安装说明.txt'
    assert not instructions.exists()
    shutil.copyfile(str(pkg / '安装说明.txt'), str(instructions))
    print(json.dumps({'archive': str(DELIVERY / ARCHIVE.name), 'bytes': ARCHIVE.stat().st_size,
                      'sha256': outer_hash, 'changedEntries': len(changed), 'work': str(WORK)}, ensure_ascii=True, indent=2))


def resume_tested_stage():
    """Reuse completed protocol tests only when source, target, helpers and fixtures still match.
    Runtime controls/rendering are always run again before publication. No unverified package reuse.
    """
    WORK.relative_to((ROOT / 'target').resolve())
    assert WORK.name.startswith('v45-package-work-') and WORK.is_dir()
    assert not PACKAGE.exists() and not ARCHIVE.exists() and not (DELIVERY / ARCHIVE.name).exists()
    build = (ROOT / 'target/v45-release-build.log').read_text(encoding='utf-8', errors='replace')
    totals = re.findall(r'^\[(?:INFO|WARNING|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$', build, re.M)
    assert 'BUILD SUCCESS' in build and totals
    counts = tuple(map(int, totals[-1]))
    assert counts[0] >= 1757 and counts[1] == counts[2] == 0
    proof = json.loads((ROOT / 'target/eight-batch-validation-20261105.json').read_text(encoding='utf-8'))
    release_only = {'src/main/java/com/minion/gui/dialog/SettingsDialog.java',
                    'src/test/java/com/minion/gui/dialog/AboutOfflineTest.java'}
    for name, h in proof['sourceSha256'].items():
        if name not in release_only:
            assert digest((ROOT / name).read_bytes()).upper() == h, 'Source drift: ' + name
    pkg = WORK / 'package'
    payload = pkg / 'payload'
    target = ROOT / 'target/minion-0.1.0.jar'
    expected = contents(target)
    assert manifest(payload / 'v45-entries.tsv') == expected
    with zipfile.ZipFile(str(target)) as z:
        assert b'V45' in z.read('com/minion/gui/dialog/SettingsDialog.class')
    java(payload, 'verify', name='resumed-payload-verify')
    compiled = WORK / ('helper-source-proof-' + uuid.uuid4().hex)
    compiled.mkdir()
    run([JAVAC, '-encoding', 'UTF-8', '-d', str(compiled), str(TOOLS / 'JarPatchApplier.java'),
         str(TOOLS / 'JarEntryVerifier.java'), str(TOOLS / 'v45/MinionSmallUpdate.java')], 'resumed-helper-source-proof')
    for p in compiled.glob('*.class'):
        assert p.read_bytes() == (payload / p.name).read_bytes(), 'Updater source changed: ' + p.name
    assert (pkg / 'Update-Minion-V45-Small.cmd').read_bytes() == (TOOLS / 'Update-Minion-V45-Small.cmd').read_bytes()
    bases = {}
    validations = []
    changed = set()
    for version in ['v40', 'v41', 'v42', 'v43', 'v44']:
        install = WORK / ('install-' + version + '-测试 空格 & !')
        backups = list((install / 'backup').glob('*.bak'))
        assert len(backups) == 1
        original = backups[0]
        before = contents(original)
        assert before == manifest(payload / (version + '-entries.tsv'))
        assert (install / 'minion-0.1.0.jar').read_bytes() == original.read_bytes(), 'Manual rollback proof changed'
        keep = {n: digest(('preserve exactly: ' + n).encode('utf-8')) for n in
                ['model.json', 'workspace.json', 'config.properties', 'session/test.json',
                 '.minion/memory/keep.md', 'knowledge/keep.md', 'project/keep.xlsx',
                 'jdk8/runtime-sentinel', 'python/runtime-sentinel']}
        assert_preserved(install, keep, original)
        assert 'V45 update completed successfully.' in (WORK / 'logs' / ('apply-' + version + '.log')).read_text(encoding='utf-8')
        assert 'Already V45' in (WORK / 'logs' / ('repeat-' + version + '.log')).read_text(encoding='utf-8')
        changed.update(n for n in expected if before.get(n) != expected[n])
        assert not set(before) - set(expected)
        bases[version] = original
        validations.append({'base': version, 'allTargetEntriesMatch': True, 'backupExact': True,
                            'dataUnchanged': True, 'repeatNoOp': True, 'manualRollbackExact': True,
                            'evidence': 'completed same-content previous protocol run; fixture/hash rechecked'})
    assert digest(bases['v44'].read_bytes()).upper() == '470EE3067FF4652AFF6EB2950C4B174E1CD4285F7092C3002B98268CFC93865B'
    assert digest(bases['v43'].read_bytes()).upper() == '3144122922C0A6E215D259256332147A9917DF62326A2257163D53A71B57F234'
    assert contents(payload / 'entries-patch.zip') == {n: expected[n] for n in changed}
    for logname, text in [('unsupported-rejected', 'not a supported'),
                          ('incomplete-patch-rejected', 'Patched content validation failed'),
                          ('post-replace-auto-rollback', 'Previous JAR restored from backup.'),
                          ('cmd-verify', 'Package SHA-256 checks passed'),
                          ('cmd-corrupt-rejected', 'checksum mismatch'),
                          ('cmd-apply-v40', 'V45 update completed successfully.'),
                          ('cmd-apply-v44', 'V45 update completed successfully.')]:
        assert text in (WORK / 'logs' / (logname + '.log')).read_text(encoding='utf-8', errors='replace')
    for version in ['v40', 'v44']:
        install = WORK / ('cmd-install-' + version + '-中文 空格 & !')
        assert contents(install / 'minion-0.1.0.jar') == expected
        assert_preserved(install, keep, bases[version])
    finish_release(target, expected, changed, bases, validations, counts)


if __name__ == '__main__':
    if RESUME:
        resume_tested_stage()
    else:
        main()
