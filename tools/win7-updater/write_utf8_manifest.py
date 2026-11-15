import hashlib, pathlib, sys
root=pathlib.Path(sys.argv[1])
lines=[]
for p in sorted(root.rglob('*')):
    if not p.is_file() or p.name=='SHA256SUMS.txt':continue
    h=hashlib.sha256()
    with p.open('rb') as f:
        for data in iter(lambda:f.read(1024*1024),b''):h.update(data)
    lines.append(h.hexdigest().upper()+'  '+p.relative_to(root).as_posix())
(root/'SHA256SUMS.txt').write_bytes(('\r\n'.join(lines)+'\r\n').encode('utf-8'))
print('UTF-8 payload manifest written:',len(lines))
