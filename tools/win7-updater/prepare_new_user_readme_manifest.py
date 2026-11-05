import hashlib, pathlib, sys, zipfile

archive, guide, output = map(pathlib.Path, sys.argv[1:4])
root = 'Minion-Win7-x64-offline-V43/'
text = guide.read_text(encoding='utf-8-sig')
for old_text in ['整合内容', '迁移', '原 R2', 'V42']:
    assert old_text not in text, 'Outdated-user content: ' + old_text
assert 'Start-Minion.cmd' in text and 'Install-Prerequisites.cmd' in text
with zipfile.ZipFile(archive) as z:
    lines = z.read(root + 'SHA256SUMS.txt').decode('utf-8-sig').splitlines()
new_hash = hashlib.sha256(guide.read_bytes()).hexdigest().upper()
replaced = 0
for i, line in enumerate(lines):
    if not line.strip():
        continue
    digest, name = line.split('  ', 1)
    if name.replace('\\', '/') == 'README-先读我.txt':
        lines[i] = new_hash + '  README-先读我.txt'
        replaced += 1
assert replaced == 1
output.write_bytes(('\r\n'.join(lines) + '\r\n').encode('utf-8'))
print('New-user guide manifest ready; application and runtime hashes unchanged.')
