package com.minion.gui.icon;

import com.minion.core.tools.WindowsAppIdentity;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;

/** One selected brand asset for main/owned dialogs and Shell, lazily loaded on the FX thread. */
public final class AppIcons {
    private static final String INSTALLED="minion.app.icons.installed";
    private static List<Image> cached;
    private AppIcons(){}
    private static List<Image> images(){if(cached==null){List<Image> out=new ArrayList<Image>();
        for(int size:new int[]{16,24,32,48,64,128,256})try(InputStream in=AppIcons.class.getResourceAsStream("/app/minion-"+size+".png")){
            if(in!=null){Image image=new Image(in);if(!image.isError())out.add(image);}
        }catch(Exception ignored){}cached=Collections.unmodifiableList(out);}return cached;}
    public static void install(Stage stage){
        if(stage.getProperties().containsKey(INSTALLED))return;stage.getProperties().put(INSTALLED,Boolean.TRUE);
        stage.getIcons().setAll(images());
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN,e->bind(stage));if(stage.isShowing())bind(stage);
    }
    public static void watch(Node node){
        if(node.getProperties().containsKey(INSTALLED))return;node.getProperties().put(INSTALLED,Boolean.TRUE);
        if(node.getScene()!=null)watchScene(node.getScene());
        node.sceneProperty().addListener((o,old,scene)->{if(scene!=null)watchScene(scene);});
    }
    private static void watchScene(Scene scene){
        apply(scene.getWindow());scene.windowProperty().addListener((o,old,window)->apply(window));
    }
    private static void apply(Window window){if(window instanceof Stage)install((Stage)window);}
    private static void bind(Stage stage){long handle=nativeHandle(stage);if(handle!=0)WindowsAppIdentity.bindWindow(handle);}
    /** JavaFX8 reflective bridge only; fail softly on other toolkits instead of touching unrelated HWNDs. */
    public static long nativeHandle(Stage stage){try {
        Object peer=stage.impl_getPeer();if(peer==null)return 0;
        Method platform=peer.getClass().getDeclaredMethod("getPlatformWindow");platform.setAccessible(true);Object window=platform.invoke(peer);
        return ((Number)window.getClass().getMethod("getNativeWindow").invoke(window)).longValue();
    }catch(Exception e){return 0;}}
}
