package com.minion.core.tools.artwork;

import com.google.gson.*;
import com.minion.core.tools.*;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** 本地固定格式绘图；没有外网请求，不自动把图片发送给不支持视觉的模型。 */
public final class ArtworkTool implements Tool {
    private final Workspace workspace;
    private final String tmp;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().create();
    public ArtworkTool(Workspace workspace,String tmp){this.workspace=workspace;this.tmp=tmp;}
    public String name(){return "Artwork";}
    public String description(){return "本地高质量排版绘图，不是AI照片生图：help/fonts；template创建可编辑JSON；inspect布局核验；render生成抗锯齿高清PNG+SVG+报告。支持封面/流程图/信息图、中文换行、渐变、透明背景及本地图片组合。无需Python/GPU/网络。";}
    public JsonObject schema(){return SchemaGenerator.objectSchema(description(),new String[]{"action","path","outputPath","template","title","subtitle","scale"},new String[]{"action"});}
    public static boolean readOnlyAction(String action){return Arrays.asList("help","fonts","inspect","status").contains(action);}
    public ToolResult execute(JsonObject args) {
        boolean writing=false;
        try {
            String action=ArtworkRenderer.string(args,"action","help");
            if("help".equals(action)||"status".equals(action))return ToolResult.success(help());
            if("fonts".equals(action))return ToolResult.success(fonts());
            Path path=resolve(ArtworkRenderer.string(args,"path",""));guard(path);
            if("template".equals(action)) {
                if(!path.toString().toLowerCase(Locale.ROOT).endsWith(".json"))throw new IllegalArgumentException("模板path须以.json结尾");
                if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("源稿已存在，不覆盖；请选择新文件名或用Read/Edit修改已有源稿");
                JsonObject source=ArtworkTemplates.create(ArtworkRenderer.string(args,"template","cover"),ArtworkRenderer.string(args,"title",""),ArtworkRenderer.string(args,"subtitle",""));
                writing=true;AtomicFiles.writeText(path,gson.toJson(source),StandardCharsets.UTF_8);
                return ToolResult.success("已创建本地绘图源稿: "+path+"\nRead/Edit定制文字和布局后，Artwork render(path=此JSON,outputPath=新的PNG文件名,scale=2)。模板示例不等于用户真实数据。");
            }
            if(!Arrays.asList("render","inspect").contains(action))throw new IllegalArgumentException("支持help/fonts/template/inspect/render");
            if(!Files.isRegularFile(path)||Files.size(path)>4*1024*1024)throw new IllegalArgumentException("源稿不存在或超过4MB");
            JsonObject source=JsonParser.parseString(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();
            Path output=null,svg=null,report=null;
            if("render".equals(action)) {
                output=resolve(ArtworkRenderer.string(args,"outputPath",""));guard(output);
                if(!output.toString().toLowerCase(Locale.ROOT).endsWith(".png"))throw new IllegalArgumentException("outputPath须以.png结尾");
                String base=output.getFileName().toString();base=base.substring(0,base.length()-4);
                svg=output.resolveSibling(base+".svg");report=output.resolveSibling(base+".artwork-report.json");
                for(Path dest:Arrays.asList(output,svg,report)){guard(dest);if(Files.exists(dest,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("输出已存在，不覆盖，请使用-v2等新文件名: "+dest);}
            }
            Map<String,BufferedImage> assets=assets(source,path.getParent());
            ArtworkRenderer.Result rendered;
            try {rendered=new ArtworkRenderer().render(source,assets,"inspect".equals(action)?1:ArtworkRenderer.integer(args,"scale",2),"render".equals(action));}
            finally {for(BufferedImage image:assets.values())image.flush();}
            if(output==null)return ToolResult.success(gson.toJson(rendered.report));
            rendered.report.addProperty("sourcePath",path.toString());rendered.report.addProperty("pngPath",output.toString());rendered.report.addProperty("svgPath",svg.toString());
            writing=true;AtomicFiles.writeBytes(output,rendered.png);AtomicFiles.writeText(svg,rendered.svg,StandardCharsets.UTF_8);AtomicFiles.writeText(report,gson.toJson(rendered.report),StandardCharsets.UTF_8);
            return ToolResult.success("本地绘图已导出（非AI照片生图）\n布局警告: "+rendered.report.getAsJsonArray("warnings").size()+" "+gson.toJson(rendered.report.get("warnings"))
                    +"\n有缺字/溢出/越界警告时先修源稿，不把导出成功当作设计验收通过。\nPNG: "+output+"\nSVG: "+svg+"\n可编辑JSON: "+path+"\n布局报告: "+report+"\n像素: "+rendered.report.get("pixelWidth").getAsInt()+"×"+rendered.report.get("pixelHeight").getAsInt()
                    +"\n尚未进行视觉审美验收；只在当前模型已确认支持看图时用ViewImage复核，不自动换模型。不支持看图则明确告知并让用户预览。继续修改JSON并导出新版本，不重画无关部分。");
        } catch(Exception e){return writing?ToolResult.error("本地绘图导出未全部完成，请检查目标文件后使用新文件名重试: "+e.getMessage()):ToolResult.errorWithoutSideEffects("本地绘图失败，未写输出: "+e.getMessage());}
    }
    private Path resolve(String raw){if(raw.trim().isEmpty())throw new IllegalArgumentException("缺少path/outputPath");return PathsGuard.resolve(workspace.cwd().toString(),raw).toAbsolutePath().normalize();}
    private void guard(Path p){ToolResult result=PathsGuard.errorIfOutside(workspace,null,tmp,p);if(result!=null)throw new IllegalArgumentException(result.output);}
    private Map<String,BufferedImage> assets(JsonObject scene,Path folder) throws Exception {
        Map<String,BufferedImage> assets=new HashMap<String,BufferedImage>();
        long totalPixels=0;
        try {
            if(scene.has("layers"))for(JsonElement element:scene.getAsJsonArray("layers")) {
                JsonObject n=element.getAsJsonObject();if(!"image".equals(ArtworkRenderer.string(n,"type","")))continue;
                String raw=ArtworkRenderer.string(n,"path","");if(assets.containsKey(raw))continue;
                if(raw.trim().isEmpty()||raw.contains("://")||raw.startsWith("data:"))throw new IllegalArgumentException("图片素材仅支持本地文件，不下载URL或嵌入脚本");
                Path p=folder.resolve(raw).normalize().toAbsolutePath();guard(p);
                if(!Files.isRegularFile(p)||Files.size(p)>20*1024*1024)throw new IllegalArgumentException("素材不存在或超过20MB: "+raw);
                try(ImageInputStream in=ImageIO.createImageInputStream(p.toFile())) {
                    Iterator<ImageReader> readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IllegalArgumentException("素材格式不支持，请用PNG/JPEG/BMP: "+raw);
                    ImageReader reader=readers.next();try {reader.setInput(in);totalPixels+=(long)reader.getWidth(0)*reader.getHeight(0);if(totalPixels>ArtworkRenderer.MAX_PIXELS||assets.size()>=32)throw new IllegalArgumentException("素材总量超过1600万像素/32张，请先裁剪缩小: "+raw);assets.put(raw,reader.read(0));}finally{reader.dispose();}
                }
            }
            return assets;
        } catch(Exception e){for(BufferedImage image:assets.values())image.flush();throw e;}
    }
    private String fonts(){StringBuilder text=new StringBuilder("可用中文字体（按本机实际检测，不联网下载）:\n");int count=0;for(String f:GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())if(new Font(f,Font.PLAIN,20).canDisplayUpTo("中文图表数据分析")<0){text.append(f).append('\n');if(++count==40)break;}return text.append("默认优先Microsoft YaHei/微软雅黑，缺失时回退其他可显示中文的字体；不代表已完成视觉检查。").toString();}
    private String help(){return description()+"\n模型只负责设计和代码，Java2D实际绘制，不接生图模型。"
            +"\n动作: template(path=design.json,template=cover/process/dashboard,title=标题,subtitle=副标题)；Read/Edit修改JSON；inspect(path=design.json)；render(path=design.json,outputPath=design-v1.png,scale=2)。fonts列本机中文字体。"
            +"\nJSON根: version=1,width,height,fontFamily,background,layers。background为#RRGGBB/transparent或{from:'#102037',to:'#264D62',direction:'diagonal'}。"
            +"\n图层通用: type,x,y,width,height,opacity(0..1)。rect:fill,radius,stroke,strokeWidth；ellipse同rect；line:x,y,x2,y2,stroke,strokeWidth,arrow。"
            +"\ntext:text,fontSize,bold,fontFamily,fill(纯色),lineHeight,align(left/center/right)，x/y是左上角，width控制自动换行，height必须足够，溢出会报警，不静默缩字号。"
            +"\nimage:path(相对JSON所在目录的本地PNG/JPEG/BMP),fit(contain/cover),radius。不得写URL、脚本或远程字体。"
            +"\n输出PNG+SVG+artwork-report.json；保留输入JSON。默认scale=2，最多1600万像素，可用1920×1080场景导出3840×2160。SVG放大不失真，但文字依赖查看机器字体。"
            +"\n统计图用真实数据的Python/Matplotlib，禁止用模板占位图形假装统计结果。不要用此工具声称生成写实人物、照片或扩散插画。";}
}
