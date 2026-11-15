import com.minion.core.tools.WindowsAppIdentity;
import com.minion.gui.icon.AppIcons;
import com.minion.gui.theme.Theme;
import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import java.nio.file.*;
import java.util.*;

/** Actual HWND/property-store assertions; no model, settings, private data or taskbar pin modifications. */
public class DesktopShellSmoke extends Application {
    static Throwable failure;
    static WindowsAppIdentity.Shell shell;
    static WindowsAppIdentity.Ole ole;
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static String read(long hwnd,int pid){
        Memory iid=new Memory(16);iid.setInt(0,0x886d8eeb);iid.setShort(4,(short)0x8cf2);iid.setShort(6,(short)0x4446);
        iid.write(8,new byte[]{(byte)0x8d,2,(byte)0xcd,(byte)0xba,0x1d,(byte)0xbd,(byte)0xcf,(byte)0x99},0,8);
        PointerByReference ref=new PointerByReference();require(shell.SHGetPropertyStoreForWindow(new Pointer(hwnd),iid,ref)==0,"Get window store");Pointer store=ref.getValue();
        Memory value=new Memory(Native.POINTER_SIZE==8?24:16);value.clear();try{
            require(WindowsAppIdentity.call(store,5,store,WindowsAppIdentity.propertyKey(pid),value)==0,"Get window property");
            require(value.getShort(0)==31,"Missing wide-string property "+pid+"; "+WindowsAppIdentity.lastError());return value.getPointer(8).getWideString(0);
        }finally{ole.PropVariantClear(value);WindowsAppIdentity.call(store,2,store);}
    }
    public void start(Stage stage){Alert dialog=null;try{
        shell=Native.load("shell32",WindowsAppIdentity.Shell.class);ole=Native.load("ole32",WindowsAppIdentity.Ole.class);
        PointerByReference id=new PointerByReference();require(shell.GetCurrentProcessExplicitAppUserModelID(id)==0,"Process identity not set");
        try{require(WindowsAppIdentity.APP_ID.equals(id.getValue().getWideString(0)),"Wrong process ID");}finally{ole.CoTaskMemFree(id.getValue());}
        stage.setTitle("MINION desktop identity QA");stage.setScene(new Scene(new Label("MINION"),320,160));AppIcons.install(stage);stage.show();
        require(stage.getIcons().size()==7,"Seven icon sizes required");long hwnd=AppIcons.nativeHandle(stage);require(hwnd!=0,"No actual HWND");
        require(WindowsAppIdentity.APP_ID.equals(read(hwnd,5)),"Wrong window identity");
        String exe=Paths.get(System.getProperty("minion.launcher.path")).toAbsolutePath().toString();
        require(("\""+exe+"\"").equals(read(hwnd,2)),"Relaunch must point to native EXE");require((exe+",0").equals(read(hwnd,3)),"Wrong relaunch icon");
        require(("@"+exe+",-101").equals(read(hwnd,4)),"Wrong title resource");
        dialog=new Alert(Alert.AlertType.INFORMATION,"QA");dialog.initOwner(stage);Theme.style(dialog);dialog.show();
        require(((Stage)dialog.getDialogPane().getScene().getWindow()).getIcons().size()==7,"Owned dialog must inherit selected icon");
        System.out.println("PASS: native process + actual HWND AppID/relaunch/icon/title; seven icon sizes; themed dialog; no taskbar layout changed");
    }catch(Throwable e){failure=e;e.printStackTrace();}finally{if(dialog!=null)dialog.close();stage.close();javafx.application.Platform.exit();}}
    public static void main(String[] args)throws Exception{
        require(WindowsAppIdentity.initialize(),WindowsAppIdentity.lastError());launch(args);
        if(args.length>0)Files.write(Paths.get(args[0]),(failure==null?"PASS native EXE -> real Java8 -> JavaFX HWND/identity/icons":"FAIL "+failure).getBytes("UTF-8"));
        if(failure!=null)System.exit(1);
    }
}
