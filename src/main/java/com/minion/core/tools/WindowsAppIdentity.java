package com.minion.core.tools;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.file.*;
import java.util.Locale;

/** Stable Win7 Shell identity; no registry edits, pinning, scanning or new runtime dependency. */
public final class WindowsAppIdentity {
    public static final String APP_ID="Minion.AI.Desktop";
    private static boolean attempted,ready;
    private static String error="";
    private WindowsAppIdentity(){}
    public interface Shell extends StdCallLibrary {
        int SetCurrentProcessExplicitAppUserModelID(WString id);
        int GetCurrentProcessExplicitAppUserModelID(PointerByReference id);
        int SHGetPropertyStoreForWindow(Pointer hwnd,Pointer iid,PointerByReference store);
    }
    public interface Ole extends StdCallLibrary {
        int CoInitializeEx(Pointer reserved,int mode);
        void CoUninitialize();
        void CoTaskMemFree(Pointer p);
        int PropVariantClear(Pointer p);
    }
    private static class Api {
        static final Shell SHELL=Native.load("shell32",Shell.class);
        static final Ole OLE=Native.load("ole32",Ole.class);
    }
    public static synchronized boolean initialize(){
        if(attempted)return ready;attempted=true;
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows"))return false;
        try {NativeLibraryBootstrap.prepare();check(Api.SHELL.SetCurrentProcessExplicitAppUserModelID(new WString(APP_ID)));ready=true;}
        catch(Exception|LinkageError e){error=e.toString();}
        return ready;
    }
    public static synchronized String lastError(){return error;}
    public static Path launcher(){
        String explicit=System.getProperty("minion.launcher.path","");
        try {Path p=explicit.isEmpty()?com.minion.core.config.Config.jarDir().resolve("Minion.exe"):Paths.get(explicit);
            return Files.isRegularFile(p)?p.toAbsolutePath().normalize():null;
        }catch(Exception bad){return null;}
    }
    /** Set relaunch metadata on the actual JavaFX HWND, so pinning never targets generic javaw. */
    public static boolean bindWindow(long hwnd){
        Path exe=launcher();if(hwnd==0||exe==null||!initialize())return false;
        Pointer store=null;int initialized=Api.OLE.CoInitializeEx(null,2);
        try {
            Memory iid=new Memory(16);iid.setInt(0,0x886d8eeb);iid.setShort(4,(short)0x8cf2);iid.setShort(6,(short)0x4446);
            iid.write(8,new byte[]{(byte)0x8d,2,(byte)0xcd,(byte)0xba,0x1d,(byte)0xbd,(byte)0xcf,(byte)0x99},0,8);
            PointerByReference result=new PointerByReference();check(Api.SHELL.SHGetPropertyStoreForWindow(new Pointer(hwnd),iid,result));store=result.getValue();
            set(store,2,"\""+exe+"\"");set(store,3,exe+",0");set(store,4,"@"+exe+",-101");set(store,5,APP_ID);
            check(call(store,7,store));return true;
        }catch(Exception|LinkageError e){synchronized(WindowsAppIdentity.class){error=e.toString();}return false;}
        finally{if(store!=null)call(store,2,store);if(initialized>=0)Api.OLE.CoUninitialize();}
    }
    private static void set(Pointer store,int pid,String text){
        Memory key=propertyKey(pid),value=new Memory(Native.POINTER_SIZE==8?24:16),string=new Memory((text.length()+1L)*Native.WCHAR_SIZE);
        value.clear();string.setWideString(0,text);value.setShort(0,(short)31);value.setPointer(8,string);
        check(call(store,6,store,key,value));
    }
    public static Memory propertyKey(int pid){Memory key=new Memory(20);key.setInt(0,0x9f4c2855);key.setShort(4,(short)0x9f79);key.setShort(6,(short)0x4b39);
        key.write(8,new byte[]{(byte)0xa8,(byte)0xd0,(byte)0xe1,(byte)0xd4,0x2d,(byte)0xe1,(byte)0xd5,(byte)0xf3},0,8);key.setInt(16,pid);return key;}
    public static int call(Pointer store,int slot,Object... args){return Function.getFunction(store.getPointer(0).getPointer((long)slot*Native.POINTER_SIZE),Function.ALT_CONVENTION).invokeInt(args);}
    private static void check(int hr){if(hr<0)throw new IllegalStateException("Windows Shell HRESULT 0x"+Integer.toHexString(hr));}
}
