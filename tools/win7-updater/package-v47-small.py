# -*- coding: utf-8 -*-
"""V47 cumulative entry patch. Build/QA only; never writes outside the workspace."""
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import sys
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('previous_packaging', str(HERE / 'package-v45-small.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
ROOT = p.ROOT
WORK = ROOT / ('target/v47-package-work-' + p.uuid.uuid4().hex)
p.WORK = WORK
NAME = 'minion-win7-v47-small-from-v40-v46-20261106'
ARCHIVE = ROOT / 'dist' / (NAME + '.zip')
PKG = WORK / 'package'
PREVIOUS = ROOT / 'dist/minion-win7-v46-small-from-v40-v45-20261105.zip'


def main():
    assert not ARCHIVE.exists() and not (p.DELIVERY / ARCHIVE.name).exists(), 'Refusing to overwrite a delivery'
    WORK.mkdir()
    (ROOT / 'target/v47-package-work-path.txt').write_text(str(WORK), encoding='utf-8')
    proof_path = ROOT / 'docs/V47-开发验收结果.json'
    proof = json.loads(proof_path.read_text(encoding='utf-8'))
    assert proof['final_test_totals'] == {'tests': 1932, 'failures': 0, 'errors': 0, 'skipped': 3}
    for row in proof['source']['files']:
        assert p.digest((ROOT / row['path']).read_bytes()) == row['sha256'], 'Source changed: ' + row['path']
    target = ROOT / 'target/minion-0.1.0.jar'
    assert p.digest(target.read_bytes()) == proof['build']['sha256'], 'Not the tested development JAR'
    expected = p.contents(target)
    with zipfile.ZipFile(target) as z:
        assert b'V47' in z.read('com/minion/gui/dialog/SettingsDialog.class')
        for name in ['RuntimeJobRegistry', 'ProgressReceiptLedger', 'ProgressEvidencePolicy', 'ToolBatchScheduler']:
            assert 'com/minion/core/agent/' + name + '.class' in expected
        for n, data in ((n, z.read(n)) for n in z.namelist() if n.startswith('com/minion/') and n.endswith('.class')):
            assert struct.unpack('>H', data[6:8])[0] <= 52, n

    assert p.digest(PREVIOUS.read_bytes()).upper() == 'A83BCB31F92CC617EE0275E1BA647793D0E6EFE868C5DDC11534AD68C9A47509'
    maps = {}
    with zipfile.ZipFile(PREVIOUS) as z:
        for v in ['v40', 'v41', 'v42', 'v43', 'v44', 'v45', 'v46']:
            data = z.read('payload/' + v + '-entries.tsv')
            assert p.digest(data).upper() == z.read('payload/' + v + '-entries.tsv.sha256').decode('ascii').strip().upper()
            rows = [line.split('\t', 1) for line in data.decode('utf-8-sig').splitlines()]
            maps[v] = {n: h for h, n in rows}
            assert len(maps[v]) == len(rows)
    bases = {}
    for version, filename in [('v40', 'v40-base.jar'), ('v41', 'v41-patched-test.jar'), ('v42', 'v42-patched-test.jar')]:
        for candidate in sorted((ROOT / 'target').glob('v4*-package-work-*/' + filename)):
            if p.contents(candidate) == maps[version]:
                bases[version] = candidate
                break
        assert version in bases, version
    bases['v43'] = ROOT / 'target/v43-offline-stage/Minion-Win7-x64-offline-V43/minion-0.1.0.jar'
    old = Path((ROOT / 'target/v45-baseline-path.txt').read_text(encoding='utf-8').strip())
    relocated = ROOT / 'target' / old.name / 'v44-base.jar'
    bases['v44'] = relocated if relocated.is_file() else old / 'v44-base.jar'
    bases['v45'] = ROOT / 'target/desktop-app-v45-baseline/minion-0.1.0.jar'
    bases['v46'] = ROOT / 'target/desktop-app-ready/minion-0.1.0.jar'
    changed = set()
    for v, before in maps.items():
        assert p.contents(bases[v]) == before, 'Unverified baseline ' + v
        assert not set(before) - set(expected), 'Entry deletion is not supported by this patcher'
        changed.update(n for n in expected if before.get(n) != expected[n])
    payload = PKG / 'payload'
    payload.mkdir(parents=True)
    with zipfile.ZipFile(target) as z, zipfile.ZipFile(payload / 'entries-patch.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for n in sorted(changed):
            assert n.startswith(('com/minion/', 'com/sun/jna/', 'workflows/', 'theme/', 'app/', 'META-INF/')) or n == 'config.properties', n
            data = z.read(n)
            if n.endswith('.class'):
                assert struct.unpack('>H', data[6:8])[0] <= 52
            out.writestr(n, data)
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-d', str(payload), str(HERE / 'JarPatchApplier.java'),
           str(HERE / 'JarEntryVerifier.java'), str(HERE / 'v47/MinionSmallUpdate.java')], 'compile-updater')
    for v, entries in maps.items():
        p.write_manifest(payload / (v + '-entries.tsv'), entries)
    p.write_manifest(payload / 'v47-entries.tsv', expected)
    # Reuse the already verified V46 launcher, rather than silently rebuilding unrelated native code.
    with zipfile.ZipFile(PREVIOUS) as z:
        for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            data = z.read('payload/' + n)
            assert p.digest(data).upper() == z.read('payload/' + n + '.sha256').decode('ascii').strip().upper()
            (payload / n).write_bytes(data)
    for f in list(payload.iterdir()):
        Path(str(f) + '.sha256').write_bytes((p.digest(f.read_bytes()).upper() + '\r\n').encode('ascii'))
    updater = PKG / 'Update-Minion-V47-Small.cmd'
    shutil.copyfile(HERE / updater.name, updater)
    p.java(payload, 'verify', name='verify')
    results = []
    for v, baseline in bases.items():
        install, keep = fixture('install-' + v + '-中文 空格 & ! 路径', baseline)
        if v in ['v45', 'v46']:
            for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
                (install / n).write_bytes(b'previous desktop asset: ' + n.encode('ascii'))
        p.java(payload, 'apply', install, 'apply-' + v)
        assert p.contents(install / 'minion-0.1.0.jar') == expected
        p.assert_preserved(install, keep, baseline)
        for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            assert (install / n).read_bytes() == (payload / n).read_bytes()
        assert 'Already V47' in p.java(payload, 'apply', install, 'repeat-' + v)
        assert len(list((install / 'backup').glob('*.bak'))) == 1
        results.append({'baseline': v, 'jarAllEntriesMatch': True, 'desktopAssetsMatch': True,
                        'userDataUnchanged': True, 'backupExact': True, 'repeatNoOp': True})

    install, _ = fixture('unsupported', bases['v46'])
    with zipfile.ZipFile(install / 'minion-0.1.0.jar', 'a') as z:
        z.writestr('custom-marker', b'custom')
    original = (install / 'minion-0.1.0.jar').read_bytes()
    p.java(payload, 'apply', install, 'unsupported', expect=1)
    assert (install / 'minion-0.1.0.jar').read_bytes() == original and not (install / 'backup').exists()
    broken = WORK / 'corrupt-payload'
    shutil.copytree(payload, broken)
    with (broken / 'Minion.exe').open('ab') as f:
        f.write(b'corrupt')
    p.java(broken, 'verify', name='corrupt-rejected', expect=1)

    # Failure branch exists only in this isolated QA helper, never in the delivered helper.
    faulty = WORK / 'fault-payload'
    shutil.copytree(payload, faulty)
    src = WORK / 'fault-src/MinionSmallUpdate.java'
    src.parent.mkdir()
    code = (HERE / 'v47/MinionSmallUpdate.java').read_text(encoding='utf-8')
    needle = 'if(!entries(jar).equals(target))throw new IOException("Final content validation failed.");'
    assert needle in code
    src.write_text(code.replace(needle, 'if(Files.exists(jar))throw new IOException("QA failure after replacement");\n' + needle), encoding='utf-8')
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-cp', str(faulty), '-d', str(faulty), str(src)], 'compile-fault')
    (faulty / 'MinionSmallUpdate.class.sha256').write_text(p.digest((faulty / 'MinionSmallUpdate.class').read_bytes()), encoding='ascii')
    for existing in [False, True]:
        install, keep = fixture('rollback-' + str(existing), bases['v46'])
        if existing:
            for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
                (install / n).write_bytes(b'original')
        assert 'Previous JAR and desktop files restored' in p.java(faulty, 'apply', install, 'fault-' + str(existing), expect=1)
        assert (install / 'minion-0.1.0.jar').read_bytes() == bases['v46'].read_bytes()
        p.assert_preserved(install, keep, bases['v46'])
        for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            assert ((install / n).read_bytes() == b'original') if existing else not (install / n).exists()

    p.cmd(updater, '/verify', 'cmd-verify', b'\r\n')
    for v in ['v40', 'v46']:
        install, keep = fixture('cmd-' + v + '-中文 空格 & ! 路径', bases[v])
        assert 'V47 update completed successfully.' in p.cmd(updater, install, 'cmd-' + v)
        assert p.contents(install / 'minion-0.1.0.jar') == expected
        p.assert_preserved(install, keep, bases[v])
    damaged_cmd = WORK / 'cmd-corrupt'
    shutil.copytree(PKG, damaged_cmd)
    with (damaged_cmd / 'payload/entries-patch.zip').open('ab') as f:
        f.write(b'corrupt')
    assert 'checksum mismatch' in p.cmd(damaged_cmd / updater.name, '/verify', 'cmd-corrupt-rejected', b'\r\n', expect=1)

    # These smoke tests run against an actually patched JAR, not target/classes.
    install = WORK / 'cmd-v46-中文 空格 & ! 路径'
    patched = install / 'minion-0.1.0.jar'
    qa = WORK / 'qa'
    qa.mkdir()
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-cp', str(patched), '-d', str(qa),
           str(HERE / 'v47/PackageSmokeCheckV47.java'), str(ROOT / 'tools/desktop-app/SidebarTaskbarSmoke.java'),
           str(ROOT / 'tools/desktop-app/DesktopShellSmoke.java')], 'compile-patched-smoke')
    cp = str(qa) + p.os.pathsep + str(patched)
    p.run([p.JAVA, '-Dfile.encoding=UTF-8', '-cp', cp, 'PackageSmokeCheckV47', str(WORK / 'smoke-data')], 'patched-runtime-smoke')
    p.run([p.JAVA, '-Dprism.order=sw', '-cp', cp, 'SidebarTaskbarSmoke'], 'patched-sidebar-taskbar')
    p.run([p.JAVA, '-Dprism.order=sw', '-Dminion.launcher.path=' + str(install / 'Minion.exe'), '-cp', cp, 'DesktopShellSmoke'], 'patched-icon-shell')
    finish_package(proof, target, expected, changed, bases, results)


def finish_package(proof, target, expected, changed, bases, results):
    payload = PKG / 'payload'
    install = WORK / 'cmd-v46-中文 空格 & ! 路径'
    p.run([str(install / 'Minion.exe'), '--check', str(WORK / 'native-check.txt')], 'native-launcher-check')
    shortcut_dir = WORK / 'shortcut-qa'
    shortcut_dir.mkdir()
    p.run([str(install / 'Minion.exe'), '--shortcut', str(shortcut_dir)], 'isolated-shortcut-creation')
    assert (shortcut_dir / 'MINION.lnk').is_file()

    info = {'release': 'V47', 'date': '2026-11-06', 'aboutLabel': 'V47开发版（任务运行与上下文优化）',
            'supportedBases': list(bases), 'patchEntries': len(changed), 'targetEntryCount': len(expected),
            'validation': results, 'jarAndDesktopAutoRollbackPassed': True, 'cmdUpdateTested': ['v40', 'v46'],
            'unsupportedAndCorruptRejected': True, 'patchedJarJava8RuntimeSmoke': True, 'patchedJarJavafxSmoke': True,
            'nativeLauncherAndIsolatedShortcutPassed': True, 'realUserDesktopOrTaskbarChanged': False,
            'featureRegression': proof['final_test_totals'], 'sourceTreeSHA256': proof['source']['sha256'],
            'realWin7Tested': False, 'realIntranetModelsOrDatabasesTested': False, 'realUserAnaconda376Tested': False,
            'maxcomputeDedicatedAdapterIncluded': False, 'defaultFullCompactTargetTokens': 150000,
            'referenceBuiltJarSHA256': p.digest(target.read_bytes()).upper(),
            'note': 'JAR ZIP order/timestamps can differ; every uncompressed entry must match the target manifest.'}
    (PKG / '更新信息.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    readme = (HERE / 'v47/安装说明.txt').read_text(encoding='utf-8-sig')
    (PKG / '安装说明.txt').write_bytes(b'\xef\xbb\xbf' + readme.replace('\r\n', '\n').replace('\n', '\r\n').encode('utf-8'))
    sums = [p.digest(f.read_bytes()).upper() + '  ' + f.relative_to(PKG).as_posix() for f in sorted(PKG.rglob('*')) if f.is_file()]
    (PKG / 'SHA256SUMS.txt').write_text('\n'.join(sums) + '\n', encoding='utf-8')
    with zipfile.ZipFile(ARCHIVE, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in sorted(PKG.rglob('*')):
            if f.is_file():
                z.write(f, f.relative_to(PKG).as_posix())
    with zipfile.ZipFile(ARCHIVE) as z:
        assert z.testzip() is None
        assert not any(n.endswith('.jar') for n in z.namelist())
        for line in z.read('SHA256SUMS.txt').decode('utf-8').splitlines():
            h, n = line.split('  ', 1)
            assert p.digest(z.read(n)).upper() == h
        extracted = WORK / 'final-zip-extracted'
        z.extractall(extracted)
    p.java(extracted / 'payload', 'verify', name='final-zip-verify')
    install, keep = fixture('final-zip-v46', bases['v46'])
    p.java(extracted / 'payload', 'apply', install, 'final-zip-apply')
    assert p.contents(install / 'minion-0.1.0.jar') == expected
    p.assert_preserved(install, keep, bases['v46'])
    info['finalZipExtractAndApplyPassed'] = True
    checksum = p.digest(ARCHIVE.read_bytes()).upper()
    Path(str(ARCHIVE) + '.sha256').write_text(checksum + '\n', encoding='ascii')
    Path(str(ARCHIVE) + '.verify.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    readme_out = ROOT / 'dist/Minion-V47-小更新安装说明.txt'
    assert not readme_out.exists()
    shutil.copyfile(PKG / '安装说明.txt', readme_out)
    print(json.dumps({'archive': str(ARCHIVE), 'bytes': ARCHIVE.stat().st_size,
                      'sha256': checksum, 'patchEntries': len(changed), 'work': str(WORK),
                      'deliveryCopied': False}, ensure_ascii=True, indent=2))


def resume_qa_stage(directory):
    """Recheck a completed protocol/JavaFX stage before retrying native QA outside the sandbox."""
    global WORK, PKG
    WORK = Path(directory).resolve()
    assert WORK.parent == (ROOT / 'target').resolve() and WORK.name.startswith('v47-package-work-')
    p.WORK = WORK
    PKG = WORK / 'package'
    assert not ARCHIVE.exists() and not (p.DELIVERY / ARCHIVE.name).exists()
    proof = json.loads((ROOT / 'docs/V47-开发验收结果.json').read_text(encoding='utf-8'))
    for row in proof['source']['files']:
        assert p.digest((ROOT / row['path']).read_bytes()) == row['sha256']
    target = ROOT / 'target/minion-0.1.0.jar'
    assert p.digest(target.read_bytes()) == proof['build']['sha256']
    expected = p.contents(target)
    payload = PKG / 'payload'
    assert p.manifest(payload / 'v47-entries.tsv') == expected
    p.java(payload, 'verify', name='resume-verify')
    required = {'verify': 'Package SHA-256 checks passed', 'unsupported': 'not a supported',
                'corrupt-rejected': 'Package hash mismatch', 'fault-False': 'Previous JAR and desktop files restored',
                'fault-True': 'Previous JAR and desktop files restored', 'cmd-verify': 'Package SHA-256 checks passed',
                'cmd-corrupt-rejected': 'checksum mismatch', 'patched-runtime-smoke': 'PASS V47 patched JAR',
                'patched-sidebar-taskbar': 'PASS actual sidebar width change', 'patched-icon-shell': 'PASS: native process'}
    for v in ['v40', 'v41', 'v42', 'v43', 'v44', 'v45', 'v46']:
        required['apply-' + v] = 'V47 update completed successfully.'
        required['repeat-' + v] = 'Already V47'
    for v in ['v40', 'v46']:
        required['cmd-' + v] = 'V47 update completed successfully.'
    for name, marker in required.items():
        assert marker in (WORK / 'logs' / (name + '.log')).read_text(encoding='utf-8', errors='replace'), name
    bases, results, changed = {}, [], set()
    for v in ['v40', 'v41', 'v42', 'v43', 'v44', 'v45', 'v46']:
        before = p.manifest(payload / (v + '-entries.tsv'))
        install = WORK / ('install-' + v + '-中文 空格 & ! 路径')
        backups = list((install / 'backup').glob('*.bak'))
        assert len(backups) == 1 and p.contents(backups[0]) == before, 'Backup baseline ' + v
        bases[v] = backups[0]
        assert p.contents(install / 'minion-0.1.0.jar') == expected, v
        for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            assert (install / n).read_bytes() == (payload / n).read_bytes()
        for n in ['model.json', 'workspace.json', 'config.properties', 'session/test.json',
                  '.minion/memory/keep.md', 'knowledge/keep.md', 'project/keep.xlsx',
                  'jdk8/runtime-sentinel', 'python/runtime-sentinel']:
            assert (install / n).read_bytes() == ('preserve exactly: ' + n).encode('utf-8')
        for n in ['.session/original/events.jsonl', '.minion/runtime-jobs/original.json', '.minion/progress/original.txt']:
            assert (install / n).read_bytes() == ('preserve V47 user state: ' + n).encode('utf-8')
        assert not set(before) - set(expected)
        changed.update(n for n in expected if before.get(n) != expected[n])
        results.append({'baseline': v, 'jarAllEntriesMatch': True, 'desktopAssetsMatch': True,
                        'userDataUnchanged': True, 'backupExact': True, 'repeatNoOp': True})
    patch = p.contents(payload / 'entries-patch.zip')
    assert set(patch) == changed and all(expected[n] == h for n, h in patch.items())
    assert p.digest(PREVIOUS.read_bytes()).upper() == 'A83BCB31F92CC617EE0275E1BA647793D0E6EFE868C5DDC11534AD68C9A47509'
    with zipfile.ZipFile(PREVIOUS) as z:
        for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            assert (payload / n).read_bytes() == z.read('payload/' + n)
    finish_package(proof, target, expected, changed, bases, results)


def fixture(name, baseline):
    install, keep = p.fixture(name, baseline, True)
    for n in ['.session/original/events.jsonl', '.minion/runtime-jobs/original.json', '.minion/progress/original.txt']:
        f = install / n
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(('preserve V47 user state: ' + n).encode('utf-8'))
        keep[n] = p.digest(f.read_bytes())
    return install, keep


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--resume-qa-stage':
        resume_qa_stage(sys.argv[2])
    else:
        assert len(sys.argv) == 1, 'Usage: package-v47-small.py [--resume-qa-stage <tested work directory>]'
        main()
