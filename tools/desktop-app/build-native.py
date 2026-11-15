"""Build-only Python3.7 stdlib. Native EXE has no Python/.NET/compiler dependency."""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
from pathlib import Path
import struct
import subprocess

ROOT=Path(__file__).resolve().parents[2]


def record(kind,name,data):
    header=struct.pack('<IIHHHHIHHII',len(data),32,65535,kind,65535,name,0,0x1030,0x409,0,0)
    return header+data+b'\0'*((-len(data))%4)


def resources(icon):
    data=icon.read_bytes();reserved,kind,count=struct.unpack_from('<HHH',data)
    assert reserved==0 and kind==1 and count==7
    out=struct.pack('<IIHHHHIHHII',0,32,65535,0,65535,0,0,0,0,0,0)
    group=struct.pack('<HHH',0,1,count)
    for i in range(count):
        w,h,c,r,planes,bits,length,offset=struct.unpack_from('<BBBBHHII',data,6+i*16)
        out+=record(3,i+1,data[offset:offset+length])
        group+=struct.pack('<BBBBHHIH',w,h,c,r,planes,bits,length,i+1)
    out+=record(14,1,group)
    # String resource101, referenced by Shell relaunch display name.
    table=b''
    for i in range(16):
        value='MINION' if i==5 else ''
        table+=struct.pack('<H',len(value))+value.encode('utf-16le')
    out+=record(6,7,table)
    manifest=('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
              '<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">'
              '<assemblyIdentity version="1.0.0.0" processorArchitecture="amd64" name="Minion.Desktop" type="win32"/>'
              '<description>MINION AI Desktop</description>'
              '<trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges>'
              '<requestedExecutionLevel level="asInvoker" uiAccess="false"/>'
              '</requestedPrivileges></security></trustInfo></assembly>').encode('utf-8')
    return out+record(24,1,manifest)


def main():
    p=argparse.ArgumentParser();p.add_argument('--compiler',required=True);p.add_argument('--output',required=True);a=p.parse_args()
    out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
    res=out/'minion.res';res.write_bytes(resources(ROOT/'src/resource/app/minion.ico'))
    exe=out/'Minion.exe'
    subprocess.run([a.compiler,'-Wl,-subsystem=windows','-o',str(exe),str(ROOT/'tools/desktop-app/launcher.c'),'-luser32'],check=True)
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    begin=kernel.BeginUpdateResourceW;begin.argtypes=[wintypes.LPCWSTR,wintypes.BOOL];begin.restype=wintypes.HANDLE
    update=kernel.UpdateResourceW;update.argtypes=[wintypes.HANDLE,ctypes.c_void_p,ctypes.c_void_p,wintypes.WORD,ctypes.c_void_p,wintypes.DWORD];update.restype=wintypes.BOOL
    end=kernel.EndUpdateResourceW;end.argtypes=[wintypes.HANDLE,wintypes.BOOL];end.restype=wintypes.BOOL
    handle=begin(str(exe),False)
    if not handle:raise ctypes.WinError(ctypes.get_last_error())
    complete=False
    try:
        blob=res.read_bytes();offset=0
        while offset<len(blob):
            length,header=struct.unpack_from('<II',blob,offset)
            kind=struct.unpack_from('<H',blob,offset+10)[0];name=struct.unpack_from('<H',blob,offset+14)[0]
            language=struct.unpack_from('<H',blob,offset+22)[0];data=blob[offset+header:offset+header+length]
            if length:
                buffer=ctypes.create_string_buffer(data)
                if not update(handle,kind,name,language,buffer,length):raise ctypes.WinError(ctypes.get_last_error())
            offset+=(header+length+3)&~3
        complete=True
    finally:
        if not end(handle,not complete):raise ctypes.WinError(ctypes.get_last_error())
    b=exe.read_bytes();assert b[:2]==b'MZ';pe=struct.unpack_from('<I',b,60)[0]
    assert b[pe:pe+4]==b'PE\0\0' and struct.unpack_from('<H',b,pe+4)[0]==0x8664
    assert struct.unpack_from('<H',b,pe+24+68)[0]==2,'Must be a GUI executable, not console'
    print('Built native AMD64 GUI Minion.exe: %d bytes SHA256 %s'%(len(b),hashlib.sha256(b).hexdigest()))


if __name__=='__main__':main()
