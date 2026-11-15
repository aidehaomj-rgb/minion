import hashlib, io, sys, zipfile
from pathlib import PurePosixPath

path, base = sys.argv[1:3]
root = 'Minion-Win7-x64-offline-V43/'
with zipfile.ZipFile(path) as z:
    names = z.namelist()
    assert len(names) == len(set(names)), 'duplicate ZIP entry'
    for name in names:
        assert name.startswith(root) and '..' not in PurePosixPath(name).parts, name
    expected = {}
    for line in z.read(root + 'SHA256SUMS.txt').decode('utf-8-sig').splitlines():
        if not line.strip():
            continue
        digest, name = line.split('  ', 1)
        expected[root + name.replace('\\', '/')] = digest.upper()
    files = {n for n in names if not n.endswith('/')}
    assert files == set(expected) | {root + 'SHA256SUMS.txt'}, 'unlisted or missing files: ' + repr(list(files ^ (set(expected) | {root + 'SHA256SUMS.txt'}))[:5])
    for i, (name, digest) in enumerate(expected.items(), 1):
        h = hashlib.sha256()
        with z.open(name) as f:
            for data in iter(lambda: f.read(1024 * 1024), b''):
                h.update(data)  # reading to EOF also verifies the ZIP CRC
        assert h.hexdigest().upper() == digest, name
        if i % 2000 == 0:
            print('Verified payload files:', i, flush=True)
    for forbidden in ['model.json', 'workspace.json', 'config.properties', 'tools.json', 'mcp.json']:
        assert root + forbidden not in files, 'user config at root: ' + forbidden
    with zipfile.ZipFile(io.BytesIO(z.read(root + 'minion-0.1.0.jar'))) as jar:
        assert 'com/sun/jna/win32-x86-64/jnidispatch.dll' in jar.namelist()
        count = 0
        for n in jar.namelist():
            if not n.startswith('com/minion/') or not n.endswith('.class'):
                continue
            data = jar.read(n)
            assert data[:4] == b'\xca\xfe\xba\xbe' and int.from_bytes(data[6:8], 'big') <= 52, n
            assert b'258915527' not in data, 'contact in ' + n
            count += 1
        print('Verified Java 8 application classes:', count)
    with zipfile.ZipFile(base) as old:
        for suffix in ['jdk8/bin/java.exe', 'jdk8/bin/javaw.exe', 'jdk8/jre/lib/ext/jfxrt.jar',
                       'python/python.exe', 'python/python37.dll', 'python/python37._pth',
                       'prerequisites/Windows6.1-KB2999226-x64.msu', 'prerequisites/vc_redist.x64.exe']:
            assert z.read(root + suffix) == old.read('Minion-Win7-x64-offline/' + suffix), suffix
    print('PASS: all %d payload SHA-256/CRC checks; archive layout; no root user config; Java8/contact check; original R2 runtime samples unchanged.' % len(expected))
