/* Win7 x64 native entry. No .NET, UCRT, shell command, network or model access. */
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <wchar.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CAP 32768
#define APPID L"Minion.AI.Desktop"
static wchar_t self[CAP],root[CAP],java[CAP],jar[CAP],command[CAP];
typedef struct { unsigned long a; unsigned short b,c; unsigned char d[8]; } MG;
static MG clsLink={0x00021401,0,0,{0xc0,0,0,0,0,0,0,0x46}};
static MG iidLink={0x000214f9,0,0,{0xc0,0,0,0,0,0,0,0x46}};
static MG iidFile={0x0000010b,0,0,{0xc0,0,0,0,0,0,0,0x46}};
static MG iidStore={0x886d8eeb,0x8cf2,0x4446,{0x8d,0x02,0xcd,0xba,0x1d,0xbd,0xcf,0x99}};
typedef struct {MG fmt;unsigned long pid;} PK;
static PK keyID={{0x9f4c2855,0x9f79,0x4b39,{0xa8,0xd0,0xe1,0xd4,0x2d,0xe1,0xd5,0xf3}},5};
typedef struct {unsigned short vt,a,b,c; union {wchar_t *text;struct {unsigned long n;void *p;}blob;}v;} PV;
static void **vt(void *p){return *(void ***)p;}
static HRESULT qi(void*p,MG*g,void**out){return ((HRESULT(WINAPI*)(void*,MG*,void**))vt(p)[0])(p,g,out);}
static void release(void*p){if(p)((ULONG(WINAPI*)(void*))vt(p)[2])(p);}
static HRESULT put(void*p,int slot,const wchar_t*s){return ((HRESULT(WINAPI*)(void*,const wchar_t*))vt(p)[slot])(p,s);}
static int regular(const wchar_t*p){DWORD a=GetFileAttributesW(p);return a!=INVALID_FILE_ATTRIBUTES&&!(a&FILE_ATTRIBUTE_DIRECTORY);}
static int join(wchar_t*out,const wchar_t*a,const wchar_t*b){if(wcslen(a)+wcslen(b)+2>=CAP)return 0;wcscpy(out,a);wcscat(out,L"\\");wcscat(out,b);return 1;}
static int append(const wchar_t*s){if(wcslen(command)+wcslen(s)>=CAP)return 0;wcscat(command,s);return 1;}
/* Windows CRT argument quoting: quotes and trailing backslashes must round-trip. */
static int arg(const wchar_t*s){int n=0;const wchar_t*p=s;if(!append(L"\""))return 0;
    for(;;p++){if(*p==L'\\'){n++;continue;}if(*p==L'\"'||!*p){int k=n*2+(*p==L'\"');while(k--)if(!append(L"\\"))return 0;}else while(n--)if(!append(L"\\"))return 0;
        n=0;if(!*p)break;wchar_t one[2]={*p,0};if(!append(one))return 0;}
    return append(L"\" ");
}
static int fail(const wchar_t*msg,int quiet){if(!quiet)MessageBoxW(NULL,msg,L"MINION",MB_OK|MB_ICONERROR);return 1;}
static int findjava(void){wchar_t temp[CAP],base[CAP];
    if(join(java,root,L"jdk8\\bin\\javaw.exe")&&regular(java))return 1;
    if(join(java,root,L"jre\\bin\\javaw.exe")&&regular(java))return 1;
    DWORD n=GetEnvironmentVariableW(L"MINION_JAVA",temp,CAP);
    if(n&&n<CAP){if(temp[0]==L'\"'&&temp[n-1]==L'\"'){temp[n-1]=0;memmove(temp,temp+1,(n-1)*sizeof(wchar_t));}
        if(regular(temp)){wcscpy(java,temp);wchar_t*slash=wcsrchr(java,L'\\');if(slash){wcscpy(slash+1,L"javaw.exe");if(regular(java))return 1;}wcscpy(java,temp);return 1;}}
    n=GetEnvironmentVariableW(L"JAVA_HOME",base,CAP);if(n&&n<CAP&&join(java,base,L"bin\\javaw.exe")&&regular(java))return 1;
    return 0;
}
static HRESULT shortcut(const wchar_t*dir){
    HMODULE ole=LoadLibraryW(L"ole32.dll");if(!ole)return E_FAIL;
    HRESULT(WINAPI*init)(void*,DWORD)=(void*)GetProcAddress(ole,"CoInitializeEx");
    HRESULT(WINAPI*create)(MG*,void*,DWORD,MG*,void**)=(void*)GetProcAddress(ole,"CoCreateInstance");
    void(WINAPI*done)(void)=(void*)GetProcAddress(ole,"CoUninitialize");
    HRESULT h=init(NULL,2);int uninit=h>=0;void*link=NULL,*store=NULL,*file=NULL;wchar_t path[CAP];PV val;
    if(h<0)goto end;
    h=create(&clsLink,NULL,1,&iidLink,&link);if(h<0)goto end;
    if((h=put(link,20,self))<0||(h=put(link,9,root))<0||(h=put(link,7,L"MINION AI assistant"))<0)goto end;
    h=((HRESULT(WINAPI*)(void*,const wchar_t*,int))vt(link)[17])(link,self,0);if(h<0)goto end;
    h=qi(link,&iidStore,&store);if(h<0)goto end;
    memset(&val,0,sizeof(val));val.vt=31;val.v.text=APPID;
    h=((HRESULT(WINAPI*)(void*,PK*,PV*))vt(store)[6])(store,&keyID,&val);if(h<0)goto end;
    h=((HRESULT(WINAPI*)(void*))vt(store)[7])(store);if(h<0)goto end;
    h=qi(link,&iidFile,&file);if(h<0)goto end;
    if(!join(path,dir,L"MINION.lnk")){h=E_FAIL;goto end;}
    /* Do not replace an unrelated user's shortcut. Installation can be repeated after deleting its own .lnk. */
    if(GetFileAttributesW(path)!=INVALID_FILE_ATTRIBUTES){h=HRESULT_FROM_WIN32(ERROR_FILE_EXISTS);goto end;}
    h=((HRESULT(WINAPI*)(void*,const wchar_t*,BOOL))vt(file)[6])(file,path,TRUE);
end:release(file);release(store);release(link);if(uninit)done();FreeLibrary(ole);return h;
}
int WINAPI WinMain(HINSTANCE hi,HINSTANCE prev,LPSTR ignored,int show){
    DWORD n=GetModuleFileNameW(NULL,self,CAP);if(!n||n>=CAP)return fail(L"Installation path is too long.",0);
    wcscpy(root,self);wchar_t*last=wcsrchr(root,L'\\');if(!last)return 1;*last=0;
    HMODULE shell=LoadLibraryW(L"shell32.dll");if(!shell)return 1;
    wchar_t**(WINAPI*parse)(const wchar_t*,int*)=(void*)GetProcAddress(shell,"CommandLineToArgvW");
    int argc=0;wchar_t**argv=parse(GetCommandLineW(),&argc);if(!argv)return 1;
    int quiet=argc>1&&!wcscmp(argv[1],L"--check");
    for(wchar_t*p=root;*p;p++)if(*p==L'!'&&(!p[1]||p[1]==L'\\'||p[1]==L'/'))return fail(L"Java 8 cannot read JAR resources when a folder name ends in !. Please rename that folder.",quiet);
    join(jar,root,L"minion-0.1.0.jar");if(!regular(jar))return fail(L"Place Minion.exe beside minion-0.1.0.jar in the existing installation.",quiet);
    if(argc==3&&!wcscmp(argv[1],L"--shortcut"))return shortcut(argv[2])<0?2:0;
    if(argc==2&&!wcscmp(argv[1],L"--install-shortcuts")){
        HRESULT(WINAPI*folder)(HWND,int,HANDLE,DWORD,wchar_t*)=(void*)GetProcAddress(shell,"SHGetFolderPathW");wchar_t dir[CAP];
        if(!folder||folder(NULL,0x10,NULL,0,dir)<0||shortcut(dir)<0)return fail(L"Desktop shortcut already exists or could not be created. Remove only an old MINION shortcut and retry.",0);
        if(folder(NULL,2,NULL,0,dir)<0||shortcut(dir)<0)return fail(L"Desktop shortcut created; Start menu shortcut already exists or could not be created.",0);
        MessageBoxW(NULL,L"MINION shortcuts created. Right-click MINION and choose Pin to taskbar.",L"MINION",MB_OK);return 0;
    }
    if(!findjava())return fail(L"Java 8 was not found. Keep jdk8 beside Minion.exe, or set MINION_JAVA / JAVA_HOME. No download is required by this launcher.",quiet);
    command[0]=0;wchar_t prop[CAP];
    if(!arg(java)||!arg(L"-Dfile.encoding=UTF-8"))return 1;
    if(wcslen(self)+30>=CAP)return 1;wcscpy(prop,L"-Dminion.launcher.path=");wcscat(prop,self);
    if(!arg(prop)||!arg(L"-jar")||!arg(jar))return 1;
    for(int i=1;i<argc&&!quiet;i++)if(!arg(argv[i]))return 1;
    if(quiet){if(argc!=3)return 2;HANDLE f=CreateFileW(argv[2],GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);if(f==INVALID_HANDLE_VALUE)return 2;
        DWORD written;WORD bom=0xfeff;WriteFile(f,&bom,2,&written,NULL);WriteFile(f,command,(DWORD)(wcslen(command)*2),&written,NULL);CloseHandle(f);return 0;}
    STARTUPINFOW si;PROCESS_INFORMATION pi;memset(&si,0,sizeof(si));memset(&pi,0,sizeof(pi));si.cb=sizeof(si);
    if(!CreateProcessW(java,command,NULL,NULL,FALSE,CREATE_NO_WINDOW,NULL,root,&si,&pi))return fail(L"Could not start Java. Check the existing Java 8 installation and file permissions.",0);
    CloseHandle(pi.hThread);CloseHandle(pi.hProcess);LocalFree(argv);FreeLibrary(shell);return 0;
}
