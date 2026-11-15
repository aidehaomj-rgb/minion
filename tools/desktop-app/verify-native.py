"""Isolated native launcher QA. Never creates real desktop pins or user shortcuts."""
import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time
import uuid

ROOT=Path(__file__).resolve().parents[2]
PROBE=r'''
#define UNICODE
#include <windows.h>
#include <wchar.h>
int WINAPI WinMain(HINSTANCE a,HINSTANCE b,LPSTR c,int d){wchar_t file[32768],cwd[32768];GetModuleFileNameW(NULL,file,32768);wchar_t*p=wcsrchr(file,L'\\');wcscpy(p+1,L"probe.txt");GetCurrentDirectoryW(32768,cwd);HANDLE f=CreateFileW(file,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);if(f==INVALID_HANDLE_VALUE)return 2;DWORD n;WORD bom=0xfeff;WriteFile(f,&bom,2,&n,NULL);WriteFile(f,cwd,wcslen(cwd)*2,&n,NULL);WriteFile(f,L"\n",2,&n,NULL);WriteFile(f,GetCommandLineW(),wcslen(GetCommandLineW())*2,&n,NULL);CloseHandle(f);return 0;}
'''


def parse_command(s):
    shell=ctypes.WinDLL('shell32');fn=shell.CommandLineToArgvW
    fn.argtypes=[wintypes.LPCWSTR,ctypes.POINTER(ctypes.c_int)];fn.restype=ctypes.POINTER(ctypes.c_wchar_p)
    count=ctypes.c_int();array=fn(s,ctypes.byref(count))
    try:return [array[i] for i in range(count.value)]
    finally:
        kernel=ctypes.WinDLL('kernel32');kernel.LocalFree.argtypes=[ctypes.c_void_p];kernel.LocalFree(array)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--compiler',required=True);parser.add_argument('--exe',required=True);a=parser.parse_args()
    work=ROOT/('target/desktop-native-qa-'+uuid.uuid4().hex);work.mkdir()
    install=work/'测试 空格 & ! 路径';install.mkdir();exe=install/'Minion.exe';shutil.copyfile(a.exe,str(exe))
    env=os.environ.copy();env.pop('JAVA_HOME',None);env.pop('MINION_JAVA',None)
    result=subprocess.run([str(exe),'--check',str(work/'missing.txt')],env=env,timeout=10);assert result.returncode!=0
    (install/'minion-0.1.0.jar').write_bytes(b'isolated fixture, not application data')
    assert subprocess.run([str(exe),'--check',str(work/'missing-java.txt')],env=env,timeout=10).returncode!=0
    source=work/'probe.c';source.write_text(PROBE,encoding='utf-8')
    java=install/'jdk8/bin/javaw.exe';java.parent.mkdir(parents=True)
    subprocess.run([a.compiler,'-Wl,-subsystem=windows','-o',str(java),str(source)],check=True)
    report=work/'check.txt';subprocess.run([str(exe),'--check',str(report)],check=True,env=env,timeout=10)
    check=parse_command(report.read_text(encoding='utf-16'));assert check[0]==str(java)
    assert check[-2:]==['-jar',str(install/'minion-0.1.0.jar')]
    forwarded=['hello world','a"b','ends\\','& | > ! %PATH%','中文参数']
    subprocess.run([str(exe)]+forwarded,check=True,env=env,timeout=10)
    proof=java.with_name('probe.txt');deadline=time.monotonic()+10
    while not proof.exists() and time.monotonic()<deadline:time.sleep(.025)
    assert proof.exists(),'No child process receipt'
    cwd,line=proof.read_text(encoding='utf-16').split('\n',1);args=parse_command(line)
    assert cwd==str(install),'Child cwd must equal installation, never launcher caller cwd'
    assert args[-len(forwarded):]==forwarded,'Arguments must not be evaluated as shell commands'
    assert '-Dminion.launcher.path='+str(exe) in args
    links=work/'shortcuts';links.mkdir()
    subprocess.run([str(exe),'--shortcut',str(links)],check=True,timeout=10)
    link=links/'MINION.lnk';before=link.read_bytes();assert before
    assert subprocess.run([str(exe),'--shortcut',str(links)],timeout=10).returncode!=0
    assert link.read_bytes()==before,'Existing user shortcut must not be replaced'
    script=work/'read-shortcut.ps1'
    script.write_text('''param([string]$Link)
$w=New-Object -ComObject WScript.Shell
$s=$w.CreateShortcut($Link)
$sh=New-Object -ComObject Shell.Application
$f=$sh.Namespace((Split-Path -LiteralPath $Link))
$item=$f.ParseName([IO.Path]::GetFileName($Link))
@{target=$s.TargetPath;cwd=$s.WorkingDirectory;icon=$s.IconLocation;appId=$item.ExtendedProperty('System.AppUserModel.ID')} | ConvertTo-Json -Compress
''',encoding='utf-8-sig')
    read=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(script),str(link)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
    assert read.returncode==0,read.stderr.decode('utf-8','replace')
    # Windows PowerShell redirected stdout uses the console OEM code page.
    metadata=json.loads(read.stdout.decode('utf-8-sig',errors='replace'))
    if metadata['target']!=str(exe):
        metadata=json.loads(read.stdout.decode('gbk',errors='strict').lstrip('\ufeff'))
    assert metadata['target']==str(exe) and metadata['cwd']==str(install),metadata
    assert metadata['appId']=='Minion.AI.Desktop' and 'Minion.exe' in metadata['icon'],metadata
    shell=ctypes.WinDLL('shell32',use_last_error=True);extract=shell.ExtractIconExW
    extract.argtypes=[wintypes.LPCWSTR,ctypes.c_int,ctypes.POINTER(wintypes.HANDLE),ctypes.POINTER(wintypes.HANDLE),wintypes.UINT];extract.restype=wintypes.UINT
    large=wintypes.HANDLE();small=wintypes.HANDLE();assert extract(str(Path(a.exe).resolve()),0,ctypes.byref(large),ctypes.byref(small),1)>=1
    assert large.value and small.value,'Both real Shell icon sizes must be extractable'
    user=ctypes.WinDLL('user32');user.DestroyIcon.argtypes=[wintypes.HANDLE];user.DestroyIcon(large);user.DestroyIcon(small)
    kernel=ctypes.WinDLL('kernel32');kernel.LoadLibraryExW.argtypes=[wintypes.LPCWSTR,wintypes.HANDLE,wintypes.DWORD];kernel.LoadLibraryExW.restype=wintypes.HANDLE
    module=kernel.LoadLibraryExW(str(Path(a.exe).resolve()),None,0x22);assert module
    try:
        buf=ctypes.create_unicode_buffer(64);user.LoadStringW.argtypes=[wintypes.HANDLE,wintypes.UINT,wintypes.LPWSTR,ctypes.c_int]
        assert user.LoadStringW(module,101,buf,64)==6 and buf.value=='MINION'
    finally:
        kernel.FreeLibrary.argtypes=[wintypes.HANDLE];kernel.FreeLibrary(module)
    # PE imports: only OS DLLs; no CLR/.NET or extra compiler runtime.
    b=Path(a.exe).read_bytes();pe=struct.unpack_from('<I',b,60)[0];opt=pe+24;count=struct.unpack_from('<H',b,pe+6)[0]
    section_table=opt+struct.unpack_from('<H',b,pe+20)[0]
    sections=[struct.unpack_from('<IIII',b,section_table+i*40+8) for i in range(count)]
    def offset(rva):
        for virtual,base,rawsize,raw in sections:
            if base<=rva<base+max(virtual,rawsize):return raw+rva-base
        raise AssertionError('RVA outside image')
    imports=[];rva=struct.unpack_from('<I',b,opt+112+8)[0];at=offset(rva)
    while any(b[at:at+20]):
        name=offset(struct.unpack_from('<I',b,at+12)[0]);imports.append(b[name:b.index(b'\0',name)].decode('ascii').lower());at+=20
    assert set(imports)<=set(['kernel32.dll','user32.dll','msvcrt.dll']),imports
    assert struct.unpack_from('<I',b,opt+112+14*8)[0]==0,'Must not have a CLR header'
    result={'passed':True,'nativeImports':imports,'noDotNet':True,'unicodeAndSpaces':True,'argumentsRoundTrip':True,
            'workingDirectoryBound':True,'shortcutTargetAndAppIdVerified':True,'existingShortcutPreserved':True,
            'embeddedLargeSmallIconsAndRelaunchTitleVerified':True,
            'realDesktopOrTaskbarChanged':False,'work':str(work)}
    (ROOT/'target/desktop-native-validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=True,indent=2))


if __name__=='__main__':main()
