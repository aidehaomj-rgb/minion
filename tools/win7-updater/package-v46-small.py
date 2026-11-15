# -*- coding: utf-8 -*-
"""Build-only tooling. The delivered updater requires only existing Java8 + Windows CMD."""
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('previous_packaging', str(HERE / 'package-v45-small.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
ROOT, WORK = p.ROOT, p.ROOT / ('target/v46-package-work-' + p.uuid.uuid4().hex)
p.WORK = WORK
NAME = 'minion-win7-v46-small-from-v40-v45-20261105'
ARCHIVE = ROOT / 'dist' / (NAME + '.zip')
PKG = WORK / 'package'


def main():
    assert not ARCHIVE.exists() and not (p.DELIVERY / ARCHIVE.name).exists()
    WORK.mkdir()
    (ROOT / 'target/v46-package-work-path.txt').write_text(str(WORK), encoding='utf-8')
    log = (ROOT / 'target/v46-package-focused-tests.log').read_text(encoding='utf-8', errors='replace')
    assert 'Tests run: 14, Failures: 0, Errors: 0, Skipped: 0' in log and 'BUILD SUCCESS' in log
    target = ROOT / 'target/minion-0.1.0.jar'
    expected = p.contents(target)
    for name in ['SidebarSplit', 'WindowsTaskbar', 'icon/AppIcons']:
        assert 'com/minion/gui/' + name + '.class' in expected
    assert expected['com/minion/gui/MainWindow.class'] == p.digest((ROOT / 'target/classes/com/minion/gui/MainWindow.class').read_bytes())
    with zipfile.ZipFile(target) as z:
        assert b'V46' in z.read('com/minion/gui/dialog/SettingsDialog.class')

    old_payload = ROOT / 'dist/minion-win7-v45-small-from-v40-v44-20261105-package/payload'
    bases = {}
    for version, filename in [('v40', 'v40-base.jar'), ('v41', 'v41-patched-test.jar'), ('v42', 'v42-patched-test.jar')]:
        wanted = p.manifest(old_payload / (version + '-entries.tsv'))
        for candidate in sorted((ROOT / 'target').glob('v4*-package-work-*/' + filename)):
            if p.contents(candidate) == wanted:
                bases[version] = candidate
                break
        assert version in bases
    bases['v43'] = ROOT / 'target/v43-offline-stage/Minion-Win7-x64-offline-V43/minion-0.1.0.jar'
    bases['v44'] = Path((ROOT / 'target/v45-baseline-path.txt').read_text(encoding='utf-8').strip()) / 'v44-base.jar'
    bases['v45'] = ROOT / 'target/desktop-app-v45-baseline/minion-0.1.0.jar'
    maps = {v: p.contents(f) for v, f in bases.items()}
    for v, entries in maps.items():
        assert entries == p.manifest(old_payload / (v + '-entries.tsv')), v
    changed = set()
    for before in maps.values():
        assert not set(before) - set(expected)
        changed.update(n for n in expected if before.get(n) != expected[n])
    payload = PKG / 'payload'
    payload.mkdir(parents=True)
    with zipfile.ZipFile(target) as z, zipfile.ZipFile(payload / 'entries-patch.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for n in sorted(changed):
            assert n.startswith(('com/minion/', 'com/sun/jna/', 'workflows/', 'theme/', 'app/')) or n.startswith('META-INF/')
            data = z.read(n)
            if n.endswith('.class'):
                assert struct.unpack('>H', data[6:8])[0] <= 52
            out.writestr(n, data)
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-d', str(payload), str(HERE / 'JarPatchApplier.java'),
           str(HERE / 'JarEntryVerifier.java'), str(HERE / 'v46/MinionSmallUpdate.java')], 'compile-updater')
    for v, entries in maps.items():
        p.write_manifest(payload / (v + '-entries.tsv'), entries)
    p.write_manifest(payload / 'v46-entries.tsv', expected)
    shutil.copyfile(ROOT / 'target/desktop-app/Minion.exe', payload / 'Minion.exe')
    shutil.copyfile(ROOT / 'tools/desktop-app/Create-Minion-Shortcuts.cmd', payload / 'Create-Minion-Shortcuts.cmd')
    for f in list(payload.iterdir()):
        Path(str(f) + '.sha256').write_text(p.digest(f.read_bytes()).upper() + '\r\n', encoding='ascii')
    updater = PKG / 'Update-Minion-V46-Small.cmd'
    shutil.copyfile(HERE / updater.name, updater)
    p.java(payload, 'verify', name='verify')
    results = []
    for v, baseline in bases.items():
        install, keep = p.fixture('install-' + v + '-中文 空格 & ! 路径', baseline, True)
        # Include existing desktop assets on one baseline to verify backups/rollback preserve them.
        if v == 'v45':
            for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
                (install / n).write_bytes(b'previous desktop asset: ' + n.encode())
        p.java(payload, 'apply', install, 'apply-' + v)
        assert p.contents(install / 'minion-0.1.0.jar') == expected
        p.assert_preserved(install, keep, baseline)
        for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            assert (install / n).read_bytes() == (payload / n).read_bytes()
        repeat = p.java(payload, 'apply', install, 'repeat-' + v)
        assert 'Already V46' in repeat
        assert len(list((install / 'backup').glob('*.bak'))) == 1
        results.append({'baseline': v, 'jarAllEntriesMatch': True, 'desktopAssetsMatch': True,
                        'userDataUnchanged': True, 'backupExact': True, 'repeatNoOp': True})

    # Unknown content and corrupt bytes must not change the original installation.
    install, _ = p.fixture('unsupported', bases['v45'])
    with zipfile.ZipFile(install / 'minion-0.1.0.jar', 'a') as z:
        z.writestr('custom-marker', b'custom')
    h = p.digest((install / 'minion-0.1.0.jar').read_bytes())
    p.java(payload, 'apply', install, 'unsupported', expect=1)
    assert h == p.digest((install / 'minion-0.1.0.jar').read_bytes()) and not (install / 'backup').exists()
    broken = WORK / 'corrupt-payload'
    shutil.copytree(payload, broken)
    with (broken / 'Minion.exe').open('ab') as f:
        f.write(b'corrupt')
    p.java(broken, 'verify', name='corrupt-rejected', expect=1)

    # Inject failure only into QA helper, after JAR and desktop files were replaced.
    faulty = WORK / 'fault-payload'
    shutil.copytree(payload, faulty)
    src = WORK / 'fault-src/MinionSmallUpdate.java'
    src.parent.mkdir()
    code = (HERE / 'v46/MinionSmallUpdate.java').read_text(encoding='utf-8')
    needle = 'if(!entries(jar).equals(target))throw new IOException("Final content validation failed.");'
    assert needle in code
    src.write_text(code.replace(needle, 'if(Files.exists(jar))throw new IOException("QA failure after replacement");\n' + needle), encoding='utf-8')
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-cp', str(faulty), '-d', str(faulty), str(src)], 'compile-fault')
    (faulty / 'MinionSmallUpdate.class.sha256').write_text(p.digest((faulty / 'MinionSmallUpdate.class').read_bytes()), encoding='ascii')
    for existing in [False, True]:
        install, keep = p.fixture('rollback-' + str(existing), bases['v45'], True)
        if existing:
            for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
                (install / n).write_bytes(b'original')
        output = p.java(faulty, 'apply', install, 'fault-' + str(existing), expect=1)
        assert 'Previous JAR and desktop files restored' in output
        assert (install / 'minion-0.1.0.jar').read_bytes() == bases['v45'].read_bytes()
        p.assert_preserved(install, keep, bases['v45'])
        for n in ['Minion.exe', 'Create-Minion-Shortcuts.cmd']:
            assert ((install / n).read_bytes() == b'original') if existing else not (install / n).exists()

    p.cmd(updater, '/verify', 'cmd-verify', b'\r\n')
    for v in ['v40', 'v45']:
        install, keep = p.fixture('cmd-' + v + '-中文 空格 & ! 路径', bases[v], True)
        output = p.cmd(updater, install, 'cmd-' + v)
        assert 'V46 update completed successfully.' in output
        assert p.contents(install / 'minion-0.1.0.jar') == expected
        p.assert_preserved(install, keep, bases[v])
    # Test actual resources/window using the CMD-updated production JAR, never target/classes.
    install = WORK / 'cmd-v45-中文 空格 & ! 路径'
    qa = WORK / 'qa'
    qa.mkdir()
    patched = install / 'minion-0.1.0.jar'
    p.run([p.JAVAC, '-encoding', 'UTF-8', '-cp', str(patched), '-d', str(qa),
           str(ROOT / 'tools/desktop-app/SidebarTaskbarSmoke.java'), str(ROOT / 'tools/desktop-app/DesktopShellSmoke.java')], 'compile-gui-smoke')
    cp = str(qa) + p.os.pathsep + str(patched)
    p.run([p.JAVA, '-cp', cp, 'SidebarTaskbarSmoke'], 'patched-sidebar-taskbar')
    p.run([p.JAVA, '-Dminion.launcher.path=' + str(install / 'Minion.exe'), '-cp', cp, 'DesktopShellSmoke'], 'patched-icon-shell')
    p.run([str(install / 'Minion.exe'), '--check', str(WORK / 'native-check.txt')], 'native-launcher-check')
    shortcut_dir = WORK / 'shortcut-qa'
    shortcut_dir.mkdir()
    p.run([str(install / 'Minion.exe'), '--shortcut', str(shortcut_dir)], 'shortcut-creation')
    assert (shortcut_dir / 'MINION.lnk').is_file()

    info = {'release': 'V46', 'supportedBases': list(bases), 'patchEntries': len(changed),
            'validation': results, 'jarAndDesktopAutoRollbackPassed': True, 'cmdUpdateTested': ['v40', 'v45'],
            'unsupportedAndCorruptRejected': True, 'patchedJarJavafxSmoke': True,
            'nativeLauncherAndShortcutPassed': True, 'realUserDesktopOrTaskbarChanged': False,
            'focusedTests': {'tests': 14, 'failures': 0, 'errors': 0, 'skipped': 0},
            'realWin7Tested': False, 'fullSuiteThisRevision': False,
            'referenceJarSHA256': p.digest(target.read_bytes()).upper(),
            'note': 'JAR ZIP ordering/timestamps may differ; every uncompressed entry must match the target manifest.'}
    (PKG / '更新信息.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    readme = (HERE / 'v46/安装说明.txt').read_text(encoding='utf-8-sig')
    (PKG / '安装说明.txt').write_bytes(b'\xef\xbb\xbf' + readme.replace('\n', '\r\n').encode('utf-8'))
    sums = [p.digest(f.read_bytes()).upper() + '  ' + f.relative_to(PKG).as_posix() for f in sorted(PKG.rglob('*')) if f.is_file()]
    (PKG / 'SHA256SUMS.txt').write_text('\n'.join(sums) + '\n', encoding='utf-8')
    with zipfile.ZipFile(ARCHIVE, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in sorted(PKG.rglob('*')):
            if f.is_file():
                z.write(f, f.relative_to(PKG).as_posix())
    with zipfile.ZipFile(ARCHIVE) as z:
        assert z.testzip() is None
        assert not any(n.endswith('.jar') for n in z.namelist())
        for line in z.read('SHA256SUMS.txt').decode('utf-8').splitlines():
            h, n = line.split('  ', 1)
            assert p.digest(z.read(n)).upper() == h
    checksum = p.digest(ARCHIVE.read_bytes()).upper()
    Path(str(ARCHIVE) + '.sha256').write_text(checksum + '\n', encoding='ascii')
    Path(str(ARCHIVE) + '.verify.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    for f in [ARCHIVE, Path(str(ARCHIVE) + '.sha256'), Path(str(ARCHIVE) + '.verify.json')]:
        destination = p.DELIVERY / f.name
        assert not destination.exists()
        shutil.copyfile(f, destination)
        assert p.digest(f.read_bytes()) == p.digest(destination.read_bytes())
    shutil.copyfile(PKG / '安装说明.txt', p.DELIVERY / 'Minion-V46-小更新安装说明.txt')
    print(json.dumps({'archive': str(p.DELIVERY / ARCHIVE.name), 'bytes': ARCHIVE.stat().st_size,
                      'sha256': checksum, 'work': str(WORK)}, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
