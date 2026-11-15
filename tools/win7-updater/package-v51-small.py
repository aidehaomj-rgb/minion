# -*- coding: utf-8 -*-
"""Cumulative V40-V47 -> tested V51 entry update. Workspace-only build and isolated QA."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('packaging_helpers', str(HERE / 'package-v45-small.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
ROOT = p.ROOT
WORK = ROOT / ('target/v51-package-work-' + p.uuid.uuid4().hex)
p.WORK = WORK
PKG = WORK / 'package'
NAME = 'minion-win7-v51-small-from-v40-v47-20261108'
ARCHIVE = ROOT / 'dist' / (NAME + '.zip')
PREVIOUS = ROOT / 'dist/minion-win7-v47-small-from-v40-v46-20261106.zip'
VERSIONS = ['v%d' % v for v in range(40, 48)]


def fixture(name, base):
    install, keep = p.fixture(name, base, True)
    for name in ['sidebar-view.json', 'session/项目甲/session-order.txt',
                 'session/项目甲/same-id.json', 'session/项目甲/.events/same-id/0000000000000001.json',
                 'session/项目甲/.history/same-id/original.json', '.minion/runtime-jobs/original.json',
                 '.minion/progress/original.txt']:
        f = install / name
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(('preserve V51 user state: ' + name).encode('utf-8'))
        keep[name] = p.digest(f.read_bytes())
    return install, keep


def validate_build():
    proof = json.loads((ROOT / 'docs/V51-开发验收结果.json').read_text(encoding='utf-8'))
    assert proof['fullRegression']['tests'] == 2182
    assert proof['fullRegression']['failures'] == proof['fullRegression']['errors'] == 0
    h = hashlib.sha256()
    for file in sorted(f for f in (ROOT / 'src').rglob('*') if f.is_file()):
        h.update(file.relative_to(ROOT).as_posix().encode('utf-8'))
        h.update(b'\0'); h.update(file.read_bytes()); h.update(b'\0')
    assert h.hexdigest() == proof['build']['sourceSha256'], 'Source changed after regression'
    target = ROOT / 'target/minion-0.1.0.jar'
    assert p.digest(target.read_bytes()) == proof['build']['jarSha256'], 'Not tested V51 JAR'
    expected = p.contents(target)
    with zipfile.ZipFile(target) as z:
        assert b'V51' in z.read('com/minion/gui/dialog/SettingsDialog.class')
        for name in ['com/minion/gui/sidebar/ProjectTreeReorder.class', 'com/minion/gui/input/ComposerDraft.class']:
            assert name in expected
        for name in z.namelist():
            if name.startswith('com/minion/') and name.endswith('.class'):
                assert struct.unpack('>H', z.read(name)[6:8])[0] == 52, name
    return proof, target, expected


def baselines():
    assert p.digest(PREVIOUS.read_bytes()).upper() == Path(str(PREVIOUS) + '.sha256').read_text().strip().upper()
    maps = {}
    with zipfile.ZipFile(PREVIOUS) as z:
        for version in VERSIONS:
            data = z.read('payload/' + version + '-entries.tsv')
            assert p.digest(data).upper() == z.read('payload/' + version + '-entries.tsv.sha256').decode('ascii').strip().upper()
            rows = [line.split('\t', 1) for line in data.decode('utf-8-sig').splitlines()]
            maps[version] = {name: h for h, name in rows}
            assert len(rows) == len(maps[version])
    candidates = {
        'v40': sorted((ROOT / 'target').glob('v4*-package-work-*/v40-base.jar')),
        'v41': sorted((ROOT / 'target').glob('v4*-package-work-*/v41-patched-test.jar')),
        'v42': sorted((ROOT / 'target').glob('v4*-package-work-*/v42-patched-test.jar')),
        'v43': [ROOT / 'target/v43-offline-stage/Minion-Win7-x64-offline-V43/minion-0.1.0.jar'],
        'v44': sorted((ROOT / 'target').glob('v45-*/v44-base.jar')),
        'v45': [ROOT / 'target/desktop-app-v45-baseline/minion-0.1.0.jar'],
        'v46': [ROOT / 'target/desktop-app-ready/minion-0.1.0.jar'],
        'v47': sorted((ROOT / 'target').glob('v47-package-work-*/final-zip-v46/minion-0.1.0.jar')),
    }
    bases = {}
    for version in VERSIONS:
        for candidate in candidates[version]:
            if candidate.is_file() and p.contents(candidate) == maps[version]:
                bases[version] = candidate
                break
        assert version in bases, 'Missing verified baseline: ' + version
    return bases, maps


def main():
    assert not ARCHIVE.exists() and not (p.DELIVERY / ARCHIVE.name).exists(), 'Do not overwrite deliveries'
    WORK.mkdir()
    (ROOT / 'target/v51-package-work-path.txt').write_text(str(WORK), encoding='utf-8')
    proof, target, expected = validate_build()
    bases, maps = baselines()
    changed = set()
    for version, old in maps.items():
        assert not set(old) - set(expected), 'Unsupported entry deletion: ' + version
        changed.update(name for name in expected if old.get(name) != expected[name])
    payload = PKG / 'payload'
    payload.mkdir(parents=True)
    with zipfile.ZipFile(target) as z, zipfile.ZipFile(payload / 'entries-patch.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for name in sorted(changed):
            assert name.startswith(('com/minion/', 'com/sun/jna/', 'workflows/', 'theme/', 'app/', 'META-INF/')) or name == 'config.properties', name
            data = z.read(name)
            if name.endswith('.class'):
                assert struct.unpack('>H', data[6:8])[0] <= 52, name
            out.writestr(name, data)
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-d', str(payload), str(HERE / 'JarPatchApplier.java'),
           str(HERE / 'JarEntryVerifier.java'), str(HERE / 'v51/MinionSmallUpdate.java')], 'compile-updater')
    for version, before in maps.items():
        p.write_manifest(payload / (version + '-entries.tsv'), before)
    p.write_manifest(payload / 'v51-entries.tsv', expected)
    with zipfile.ZipFile(PREVIOUS) as z:
        for name in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            data = z.read('payload/' + name)
            assert p.digest(data).upper() == z.read('payload/' + name + '.sha256').decode('ascii').strip().upper()
            (payload / name).write_bytes(data)
    for file in list(payload.iterdir()):
        Path(str(file) + '.sha256').write_bytes((p.digest(file.read_bytes()).upper() + '\r\n').encode('ascii'))
    updater = PKG / 'Update-Minion-V51-Small.cmd'
    shutil.copyfile(HERE / updater.name, updater)
    p.java(payload, 'verify', name='verify')
    validations = []
    for version, source in bases.items():
        install, keep = fixture('install-' + version + '-中文 空格 & ! 路径', source)
        p.java(payload, 'apply', install, 'apply-' + version)
        assert p.contents(install / 'minion-0.1.0.jar') == expected
        p.assert_preserved(install, keep, source)
        for name in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            assert (install / name).read_bytes() == (payload / name).read_bytes()
        assert 'Already V51' in p.java(payload, 'apply', install, 'repeat-' + version)
        assert len(list((install / 'backup').glob('*.bak'))) == 1
        validations.append({'baseline': version, 'allEntriesMatch': True, 'userDataUnchanged': True,
                            'backupExact': True, 'repeatNoOp': True, 'desktopAssetsMatch': True})
    # Unknown content and corrupt packages fail before modifying an installation.
    install, _ = fixture('unknown', bases['v47'])
    with zipfile.ZipFile(install / 'minion-0.1.0.jar', 'a') as z:
        z.writestr('custom-marker', b'unsupported-custom-build')
    original = (install / 'minion-0.1.0.jar').read_bytes()
    p.java(payload, 'apply', install, 'unknown-rejected', expect=1)
    assert (install / 'minion-0.1.0.jar').read_bytes() == original and not (install / 'backup').exists()
    broken = WORK / 'corrupt-payload'; shutil.copytree(payload, broken)
    with (broken / 'entries-patch.zip').open('ab') as f: f.write(b'corrupt')
    p.java(broken, 'verify', name='corrupt-rejected', expect=1)
    # Fault injection exists only in QA's separate helper, never in the delivered helper.
    faulty = WORK / 'fault-payload'; shutil.copytree(payload, faulty)
    src = WORK / 'fault-src/MinionSmallUpdate.java'; src.parent.mkdir()
    code = (HERE / 'v51/MinionSmallUpdate.java').read_text(encoding='utf-8')
    needle = 'if(!entries(jar).equals(target))throw new IOException("Final content validation failed.");'
    assert needle in code
    src.write_text(code.replace(needle, 'if(Files.exists(jar))throw new IOException("QA failure after replacement");\n' + needle), encoding='utf-8')
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-cp', str(faulty), '-d', str(faulty), str(src)], 'compile-fault')
    (faulty / 'MinionSmallUpdate.class.sha256').write_text(p.digest((faulty / 'MinionSmallUpdate.class').read_bytes()), encoding='ascii')
    for existed in [False, True]:
        install, keep = fixture('rollback-' + str(existed), bases['v47'])
        if existed:
            for name in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
                (install / name).write_bytes(b'original desktop asset')
        assert 'Previous JAR and desktop files restored' in p.java(faulty, 'apply', install, 'rollback-' + str(existed), expect=1)
        assert (install / 'minion-0.1.0.jar').read_bytes() == bases['v47'].read_bytes()
        p.assert_preserved(install, keep, bases['v47'])
        for name in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            assert (install / name).read_bytes() == b'original desktop asset' if existed else not (install / name).exists()
    p.cmd(updater, '/verify', 'cmd-verify', b'\r\n')
    for version in ['v40', 'v47']:
        install, keep = fixture('cmd-' + version + '-中文 空格 & ! 路径', bases[version])
        assert 'V51 update completed successfully.' in p.cmd(updater, install, 'cmd-' + version)
        assert p.contents(install / 'minion-0.1.0.jar') == expected
        p.assert_preserved(install, keep, bases[version])
    patched = WORK / 'cmd-v47-中文 空格 & ! 路径/minion-0.1.0.jar'
    qa = WORK / 'qa'; qa.mkdir()
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-cp', str(patched), '-d', str(qa),
           str(ROOT / 'tools/qa/VerifyOrderingDraftsV51.java'), str(ROOT / 'tools/qa/VerifyProjectSidebarV50.java')], 'compile-patched-ui')
    cp = str(qa) + p.os.pathsep + str(patched)
    p.run([p.JAVA, '-Dprism.order=sw', '-cp', cp, 'VerifyOrderingDraftsV51', str(WORK / 'patched-ui')], 'patched-ui-v51')
    p.run([p.JAVA, '-Dprism.order=sw', '-cp', cp, 'VerifyProjectSidebarV50', str(WORK / 'patched-tree')], 'patched-ui-v50')
    info = {'release': 'V51', 'date': '2026-11-08', 'supportedBases': VERSIONS,
            'patchEntries': len(changed), 'targetEntries': len(expected), 'baselineValidation': validations,
            'featureRegression': proof['fullRegression'], 'sourceTreeSHA256': proof['build']['sourceSha256'],
            'referenceBuiltJarSHA256': proof['build']['jarSha256'], 'autoRollbackVerified': True,
            'cmdUpdateTested': ['v40', 'v47'], 'unknownAndCorruptRejected': True,
            'patchedJarNativeFxAssertions': {'V51': 33, 'V50': 169},
            'userDataPreserved': True, 'realWin7Tested': False, 'realIntranetTested': False,
            'desktopOrTaskbarLayoutChanged': False, 'draftPersistence': 'current process memory only',
            'note': 'Uncompressed JAR entries match V51 exactly; ZIP timestamps/order and whole-JAR hash may differ.'}
    (PKG / '更新信息.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    shutil.copyfile(HERE / 'v51/安装说明.txt', PKG / '安装说明.txt')
    for name in ['V51-拖动排序与会话草稿隔离-开发说明.md', 'V51-开发验收结果.md']:
        shutil.copyfile(ROOT / 'docs' / name, PKG / name)
    sums = [p.digest(f.read_bytes()).upper() + '  ' + f.relative_to(PKG).as_posix() for f in sorted(PKG.rglob('*')) if f.is_file()]
    (PKG / 'SHA256SUMS.txt').write_text('\n'.join(sums) + '\n', encoding='utf-8')
    with zipfile.ZipFile(ARCHIVE, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in sorted(PKG.rglob('*')):
            if f.is_file(): z.write(f, f.relative_to(PKG).as_posix())
    with zipfile.ZipFile(ARCHIVE) as z:
        assert z.testzip() is None and not any(n.endswith('.jar') for n in z.namelist())
        for line in z.read('SHA256SUMS.txt').decode('utf-8').splitlines():
            h, n = line.split('  ', 1); assert p.digest(z.read(n)).upper() == h
        extracted = WORK / 'final-zip-extracted'; z.extractall(extracted)
    p.java(extracted / 'payload', 'verify', name='final-zip-verify')
    install, keep = fixture('final-zip-v47', bases['v47'])
    p.java(extracted / 'payload', 'apply', install, 'final-zip-apply')
    assert p.contents(install / 'minion-0.1.0.jar') == expected
    p.assert_preserved(install, keep, bases['v47'])
    info['finalZipExtractAndApplyPassed'] = True
    checksum = p.digest(ARCHIVE.read_bytes()).upper()
    Path(str(ARCHIVE) + '.sha256').write_text(checksum + '\n', encoding='ascii')
    Path(str(ARCHIVE) + '.verify.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    readme = ROOT / 'dist/Minion-V51-小更新安装说明.txt'; assert not readme.exists()
    shutil.copyfile(PKG / '安装说明.txt', readme)
    print(json.dumps({'archive': str(ARCHIVE), 'bytes': ARCHIVE.stat().st_size,
                      'sha256': checksum, 'patchEntries': len(changed), 'work': str(WORK), 'deliveryCopied': False}, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
