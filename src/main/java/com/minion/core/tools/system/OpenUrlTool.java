package com.minion.core.tools.system;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import java.awt.Desktop;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Opens one HTTP(S) URL through the system browser without granting page-control access. */
public final class OpenUrlTool implements Tool {
    interface Opener { void open(URI uri) throws Exception; }
    private static final long REUSE_WINDOW_MS=10*60*1000L;
    private final Opener opener;
    private final Map<String,Long> opened=new LinkedHashMap<String,Long>();

    public OpenUrlTool(){this(new SystemOpener());}
    OpenUrlTool(Opener opener){this.opener=opener;}

    @Override public String name(){return "OpenUrl";}
    @Override public String description(){
        return "使用系统默认浏览器打开一个 HTTP/HTTPS 页面。同一会话十分钟内同一 URL 默认只打开一次；"
                + "若已配置 Browser 工具，页面读取、点击和登录态检查应使用 Browser，而不是重复启动浏览器。";
    }
    @Override public JsonObject schema(){
        JsonObject schema=SchemaGenerator.objectSchema(description(),new String[]{"url","force"},new String[]{"url"});
        JsonObject props=schema.getAsJsonObject("properties");
        props.getAsJsonObject("url").addProperty("description","完整 HTTP/HTTPS URL，最长 4096 字符");
        props.getAsJsonObject("force").addProperty("description","仅用户明确要求再次打开同一 URL 时设为 true；默认 false");
        return schema;
    }

    @Override public ToolResult execute(JsonObject args){
        try {
            URI uri=validated(args);
            String key=key(uri);
            boolean force=args!=null&&args.has("force")&&!args.get("force").isJsonNull()
                    &&args.get("force").getAsBoolean();
            synchronized(opened){
                long now=System.currentTimeMillis();
                prune(now);
                Long previous=opened.get(key);
                if(!force&&previous!=null&&now-previous<REUSE_WINDOW_MS)
                    return result("already_open",uri,"本会话已打开过该 URL，本次未重复启动浏览器");
                opener.open(uri);
                opened.put(key,now);
                return result("opened",uri,"已通过系统默认浏览器打开一次；该工具不提供页面读取或点击控制");
            }
        } catch(IllegalArgumentException invalid){
            return ToolResult.errorWithoutSideEffects(invalid.getMessage());
        } catch(Exception failed){
            return ToolResult.errorWithoutSideEffects("打开 URL 失败："+message(failed));
        }
    }

    static URI validated(JsonObject args){
        if(args==null||!args.has("url")||args.get("url").isJsonNull())
            throw new IllegalArgumentException("OpenUrl 需要 url 参数");
        String raw=args.get("url").getAsString().trim();
        if(raw.isEmpty())throw new IllegalArgumentException("OpenUrl 的 url 不能为空");
        if(raw.length()>4096)throw new IllegalArgumentException("OpenUrl 的 url 超过 4096 字符");
        try {
            URI uri=new URI(raw);
            String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
            if(!"http".equals(scheme)&&!"https".equals(scheme))
                throw new IllegalArgumentException("OpenUrl 只允许 http/https URL");
            if(uri.getHost()==null||uri.getHost().trim().isEmpty())
                throw new IllegalArgumentException("OpenUrl 的 URL 缺少有效主机名");
            if(uri.getUserInfo()!=null)throw new IllegalArgumentException("OpenUrl 不接受带用户名密码的 URL");
            return uri.normalize();
        } catch(IllegalArgumentException invalid){
            throw invalid;
        } catch(Exception invalid){
            throw new IllegalArgumentException("OpenUrl 的 URL 格式无效");
        }
    }

    static String key(URI uri){
        String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
        String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        String path=uri.getRawPath()==null||uri.getRawPath().isEmpty()?"/":uri.getRawPath();
        String query=uri.getRawQuery()==null?"":"?"+uri.getRawQuery();
        return scheme+"://"+host+(uri.getPort()<0?"":":"+uri.getPort())+path+query;
    }

    private void prune(long now){
        Iterator<Map.Entry<String,Long>> it=opened.entrySet().iterator();
        while(it.hasNext()&&now-it.next().getValue()>=REUSE_WINDOW_MS)it.remove();
    }
    private static ToolResult result(String status,URI uri,String message){
        JsonObject out=new JsonObject();
        out.addProperty("status",status);out.addProperty("url",uri.toASCIIString());
        out.addProperty("browser","system_default");out.addProperty("message",message);
        return ToolResult.success(out.toString());
    }
    private static String message(Throwable error){
        String value=error.getMessage();
        return value==null||value.trim().isEmpty()?error.getClass().getSimpleName():value;
    }

    private static final class SystemOpener implements Opener {
        @Override public void open(URI uri)throws Exception{
            if(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)){
                Desktop.getDesktop().browse(uri);return;
            }
            String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
            if(os.contains("win")){
                new ProcessBuilder("rundll32.exe","url.dll,FileProtocolHandler",uri.toString()).start();
            }else if(os.contains("mac")){
                new ProcessBuilder("open",uri.toString()).start();
            }else {
                new ProcessBuilder("xdg-open",uri.toString()).start();
            }
        }
    }
}
