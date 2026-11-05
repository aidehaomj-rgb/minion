package com.minion.core.tools.dev;

import com.google.gson.*;
import com.minion.core.tools.*;
import com.minion.core.tools.browser.BrowserSession;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** 可注册成果检查工具；不构建、不启动服务、不执行EXE，不凭文件存在认定业务正确。 */
public final class ArtifactVerificationTool implements Tool {
    private final Workspace workspace;
    private final Supplier<BrowserSession> browser;
    private final Predicate<URI> allowedHttp;
    public ArtifactVerificationTool(Workspace workspace) { this(workspace,null); }
    public ArtifactVerificationTool(Workspace workspace,Supplier<BrowserSession> browser) {
        this(workspace,browser,ArtifactVerifier::loopbackHttp);
    }
    /** 内网策略须由宿主可信配置注入，不能由工具参数自我授权。 */
    public ArtifactVerificationTool(Workspace workspace,Supplier<BrowserSession> browser,Predicate<URI> allowedHttp) {
        this.workspace=Objects.requireNonNull(workspace,"workspace");this.browser=browser;this.allowedHttp=Objects.requireNonNull(allowedHttp,"allowedHttp");
    }
    @Override public String name(){return "ArtifactVerification";}
    @Override public String description(){return "成果分层验证：file=稳定文件+SHA256（非业务正确/可运行）；image=PNG/SVG尺寸（字体/溢出unknown）；executable=目标头部（未运行）；http=实际受限GET（非浏览器）；webpage=启用浏览器实际导航、可见断言、可选带回执click及本次console/失败请求。默认仅loopback HTTP，不跟重定向。";}
    @Override public JsonObject schema(){
        JsonObject s=SchemaGenerator.objectSchema(description(),new String[]{"action","path","url","expectedText","sha256","platform","architecture"},new String[]{"action"});
        JsonObject p=s.getAsJsonObject("properties");
        for(String k:Arrays.asList("stableMs","timeoutMs","observeMs","width","height")){JsonObject x=new JsonObject();x.addProperty("type","integer");p.add(k,x);}
        JsonObject checks=new JsonObject();checks.addProperty("type","array");JsonObject check=SchemaGenerator.objectSchema("页面唯一可见断言",new String[]{"selector","frameSelector","expectedText"},new String[]{"selector"});checks.add("items",check);p.add("checks",checks);
        JsonObject interaction=SchemaGenerator.objectSchema("可选实际交互，仅用于已授权测试页面；先观察再click，已满足回执不重放",new String[]{"selector","frameSelector","receiptSelector","receiptText"},new String[]{"selector","receiptSelector"});p.add("interaction",interaction);return s;
    }
    /** 仅这些动作不接触网络/浏览器/进程，也不写文件。HTTP不自动加入只读子Agent白名单。 */
    public static boolean readOnlyAction(String action){return Arrays.asList("file","image","executable").contains(action);}
    @Override public boolean isHighRisk(JsonObject args){
        String action=text(args,"action");if("webpage".equals(action))return true;
        if("http".equals(action))try{return !ArtifactVerifier.loopbackHttp(URI.create(text(args,"url")));}catch(Exception e){return true;}
        return !readOnlyAction(action);
    }
    @Override public ToolResult execute(JsonObject args){
        JsonObject out=new JsonObject();out.addProperty("tool",name());out.addProperty("schemaVersion",1);
        out.addProperty("observedAt",System.currentTimeMillis());out.addProperty("businessVerified",false);out.addProperty("runtimeVerified",false);
        try{
            String action=text(args,"action");out.addProperty("action",action);JsonObject facts;
            if("http".equals(action)||"webpage".equals(action)){
                URI uri=URI.create(text(args,"url"));facts=ArtifactVerifier.http(uri,allowedHttp,text(args,"expectedText"));
                if(args.has("sha256")){String hash=text(args,"sha256");if(!hash.matches("(?i)[a-f0-9]{64}"))throw new IllegalArgumentException("sha256非法");boolean matched=hash.equalsIgnoreCase(text(facts,"sha256"));facts.addProperty("expectedHashMatched",matched);if(!matched)facts.addProperty("status","failed");}
                if("webpage".equals(action)&&"passed".equals(text(facts,"status")))facts=webpage(uri,args,facts);
            }else if(readOnlyAction(action)){
                if(text(args,"path").isEmpty())throw new IllegalArgumentException("缺少path");
                Path path=PathsGuard.resolve(workspace.cwd().toString(),text(args,"path")).toAbsolutePath().normalize();
                facts=ArtifactVerifier.stableFile(path,integer(args,"stableMs",500),integer(args,"timeoutMs",10000),this::allowedPath);
                if(args.has("sha256")){
                    String expected=text(args,"sha256");if(!expected.matches("(?i)[a-f0-9]{64}"))throw new IllegalArgumentException("sha256非法");
                    facts.addProperty("expectedHashMatched",expected.equalsIgnoreCase(text(facts,"sha256")));
                    if(!expected.equalsIgnoreCase(text(facts,"sha256")))facts.addProperty("status","failed");
                }
                if("image".equals(action)){
                    ArtifactVerifier.checkPath(path,this::allowedPath);JsonObject image=ArtifactVerifier.image(path);facts.add("image",image);facts.addProperty("scope","image_metadata_not_render_or_layout");
                    if(!args.has("width")||!args.has("height")){facts.addProperty("status","unknown");facts.addProperty("reason","未提供期望尺寸，仅记录元数据，字体/溢出未验证");}
                    else if(integer(args,"width",0)<=0||integer(args,"height",0)<=0||image.get("width").getAsDouble()!=args.get("width").getAsDouble()||image.get("height").getAsDouble()!=args.get("height").getAsDouble())facts.addProperty("status","failed");
                }
                if("executable".equals(action)){
                    ArtifactVerifier.checkPath(path,this::allowedPath);JsonObject executable=ArtifactVerifier.executable(path);facts.add("executable",executable);facts.addProperty("scope","target_header_not_runtime");
                    if(text(args,"platform").isEmpty()||text(args,"architecture").isEmpty()){facts.addProperty("status","unknown");facts.addProperty("reason","需指定platform/architecture；生成≠运行验证");}
                    else if(!text(args,"platform").equals(text(executable,"platform"))||!text(args,"architecture").equals(text(executable,"architecture"))||"unknown".equals(text(executable,"platform"))||"unknown".equals(text(executable,"architecture")))facts.addProperty("status","failed");
                }
                // 元数据读取后重新绑定版本，避免把更新前的哈希套到更新后的成果上。
                if(!ArtifactVerifier.sha256(path).equals(text(facts,"sha256")))throw new IllegalArgumentException("验证过程中成果发生变化，旧证据失效");
            }else throw new IllegalArgumentException("action支持file/image/executable/http/webpage；未执行任何构建/运行");
            out.add("facts",facts);out.addProperty("status",text(facts,"status"));
            out.addProperty("claimBoundary","passed仅证明scope；文件存在、稳定、HTTP或目标头部都不是业务正确或实际运行证明");
            return "passed".equals(text(facts,"status"))?ToolResult.success(out.toString()):ToolResult.error(out.toString());
        }catch(Exception e){out.addProperty("status","unknown");out.addProperty("reason",e.getClass().getSimpleName()+": "+e.getMessage());return ToolResult.error(out.toString());}
    }
    private JsonObject webpage(URI uri,JsonObject args,JsonObject http)throws Exception {
        BrowserSession session=browser==null?null:browser.get();
        if(session==null)throw new IllegalStateException("浏览器插件未启用/未配置，HTTP证据不能冒充浏览器验证");
        if(!args.has("checks")||!args.get("checks").isJsonArray()||args.getAsJsonArray("checks").size()==0)throw new IllegalArgumentException("webpage必须提供checks可见断言");
        if(args.getAsJsonArray("checks").size()>20)throw new IllegalArgumentException("最多20个页面断言");
        // 浏览器不能承诺阻断页面子资源的公网请求。策略仅约束导航入口，不是浏览器网络沙箱。
        synchronized(session){
            session.assertAutomaticAllowed();long cursor=session.diagnosticCursor();
            JsonObject out=new JsonObject();out.addProperty("scope","browser_navigation_visible_assertions_and_observation_window");out.add("http",http);
            JsonArray checks=new JsonArray();boolean passed=true;JsonObject interactionReceipt=null;
            try{
                session.open(uri.toString());
                if(args.has("interaction")){
                    JsonObject interaction=args.getAsJsonObject("interaction").deepCopy();
                    JsonObject before=session.visibleElement(text(interaction,"selector"),text(interaction,"frameSelector"),integer(args,"timeoutMs",10000));
                    interaction.addProperty("expectedVersion",text(before,"pageVersion"));interaction.addProperty("timeoutMs",integer(args,"timeoutMs",10000));
                    interactionReceipt=session.verifiedClick(interaction);out.add("interaction",interactionReceipt);
                }
                for(JsonElement e:args.getAsJsonArray("checks")){
                    JsonObject c=e.getAsJsonObject();if(text(c,"selector").isEmpty())throw new IllegalArgumentException("check缺少selector");
                    JsonObject observed=session.visibleElement(text(c,"selector"),text(c,"frameSelector"),integer(args,"timeoutMs",10000));
                    URI actual=URI.create(text(observed,"url"));if(!allowedHttp.test(actual))throw new IllegalArgumentException("页面/iframe导航离开授权HTTP范围");
                    boolean match=text(c,"expectedText").isEmpty()||text(observed,"text").contains(text(c,"expectedText"));observed.addProperty("expectedTextMatched",match);passed&=match;checks.add(observed);
                }
                ArtifactVerifier.pause(Math.max(100,Math.min(integer(args,"observeMs",500),5000)));
            }catch(Exception e){passed=false;out.addProperty("browserFailure",e.getMessage());}
            JsonObject diagnostics=session.diagnosticsSince(cursor);out.add("diagnostics",diagnostics);out.add("checks",checks);
            // 还需观察本次浏览器自身的HTTP响应，不能只把Java GET作为浏览器响应。
            boolean responseObserved=false;
            for(JsonElement e:diagnostics.getAsJsonArray("responses"))if(uri.toString().equals(text(e.getAsJsonObject(),"url")))responseObserved=true;
            out.addProperty("browserHttpResponseObserved",responseObserved);out.addProperty("browserVerified",passed&&responseObserved&&"passed".equals(text(diagnostics,"status")));
            out.addProperty("interactionVerified",interactionReceipt!=null&&"passed".equals(text(interactionReceipt,"status")));
            out.addProperty("status",!passed||"failed".equals(text(diagnostics,"status"))?"failed":!responseObserved||!"passed".equals(text(diagnostics,"status"))?"unknown":"passed");
            out.addProperty("networkBoundary","仅限制导航入口；浏览器页面脚本/子资源不受本工具OS级隔离，需宿主离线网络策略");return out;
        }
    }
    private boolean allowedPath(Path p){return PathsGuard.inside(workspace.workDir(),p);}
    private static String text(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():"";}
    private static int integer(JsonObject o,String k,int d){return o.has(k)?o.get(k).getAsInt():d;}
}
