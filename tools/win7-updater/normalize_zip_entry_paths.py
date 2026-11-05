"""Repair legacy .NET ZIP separator names in our generated artifact without recompressing payloads."""
import struct, sys, zipfile
path = sys.argv[1]
with zipfile.ZipFile(path) as z:
    infos = z.infolist()
    directory = z.start_dir
assert len(infos) < 65535
with open(path, 'r+b') as f:
    for info in infos:
        f.seek(info.header_offset)
        header = f.read(30)
        assert header[:4] == b'PK\x03\x04'
        length = struct.unpack_from('<H', header, 26)[0]
        name = f.read(length)
        fixed = name.replace(b'\\', b'/')
        assert len(name) == len(fixed)
        f.seek(info.header_offset + 30)
        f.write(fixed)
    f.seek(directory)
    for info in infos:
        start = f.tell()
        header = f.read(46)
        assert header[:4] == b'PK\x01\x02'
        length, extra, comment = struct.unpack_from('<HHH', header, 28)
        name = f.read(length)
        f.seek(start + 46)
        f.write(name.replace(b'\\', b'/'))
        f.seek(extra + comment, 1)
print('Normalized ZIP entry separators:', len(infos))
