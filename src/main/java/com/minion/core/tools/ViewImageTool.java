package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.llm.ImagePart;
import com.minion.core.tools.confirm.ConfirmGate;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

/** 本地图片像素读取；路径授权由 ReadTool 完成，原文件始终只读。 */
public final class ViewImageTool implements Tool {
    private static final long MAX_SOURCE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_DECODE_PIXELS = 24000000L;
    private static final int TILE_TRIGGER = 1600;
    private final ReadTool reader;

    public ViewImageTool(Workspace w, String skills, String tmp, ConfirmGate gate) {
        reader = new ReadTool(w, skills, tmp, gate);
    }
    public String name() { return "ViewImage"; }
    public String description() { return "将本地图片真实像素送入视觉模型；默认保留原图，并对大图附局部清晰视图。detail=overview仅原图，detail=tiles强制分区。支持rotate=0/90/180/270（顺时针），裁剪x/y/width/height为原图像素；小字、铭牌请按坐标裁剪复核。"; }
    public JsonObject schema() { return SchemaGenerator.objectSchema(description(),
        new String[]{"path","rotate","x","y","width","height","detail"},new String[]{"path"}); }
    public ToolResult execute(JsonObject args) throws Exception {
        String path = args.has("path") ? args.get("path").getAsString() : "";
        if (!isImage(Paths.get(path))) return ToolResult.error("ViewImage仅支持PNG/JPEG/GIF/BMP/WebP图片");
        return reader.execute(args);
    }
    static boolean isImage(Path p) {
        return p.toString().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|bmp|webp)$");
    }

    /** 输入框选图也用相同的解码/格式校验，但不增加额外局部图。 */
    public static ToolResult loadOverview(Path p) {
        JsonObject args = new JsonObject();
        args.addProperty("detail", "overview");
        return load(p, args);
    }

    public static ToolResult load(Path p, JsonObject args) {
        try {
            long sourceBytes = Files.size(p);
            if (sourceBytes > MAX_SOURCE_BYTES) return ToolResult.error("图片超过20MB；请先裁剪或压缩副本，原图不修改");
            String detail = args.has("detail") ? args.get("detail").getAsString().toLowerCase(Locale.ROOT) : "auto";
            if (!"auto".equals(detail) && !"overview".equals(detail) && !"tiles".equals(detail))
                return ToolResult.error("detail须为auto/overview/tiles");
            try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
                if (in == null) return ToolResult.error("无法打开图片");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) return ToolResult.error("不支持或损坏的图片格式；WebP需安装ImageIO解码器或先转PNG");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    int originalW = reader.getWidth(0), originalH = reader.getHeight(0);
                    int x = number(args,"x",0), y = number(args,"y",0);
                    int w = number(args,"width",originalW-x), h = number(args,"height",originalH-y);
                    if (x<0 || y<0 || w<=0 || h<=0 || (long)x+w>originalW || (long)y+h>originalH)
                        return ToolResult.error("裁剪范围超出原图");
                    int rotate = number(args,"rotate",0);
                    if (rotate!=0 && rotate!=90 && rotate!=180 && rotate!=270)
                        return ToolResult.error("rotate须为0/90/180/270");
                    boolean whole = x==0 && y==0 && w==originalW && h==originalH && rotate==0;
                    boolean raw = whole && sourceBytes<=ImagePart.MAX_FILE_BYTES
                            && ("jpeg".equals(format) || "jpg".equals(format) || "png".equals(format));
                    List<ImagePart> parts = new ArrayList<ImagePart>();
                    StringBuilder note = new StringBuilder("图片来源: ").append(p).append("；原图 ")
                            .append(originalW).append('x').append(originalH);
                    if (raw) {
                        parts.add(part(p.getFileName().toString(), "png".equals(format)?"image/png":"image/jpeg",
                                Files.readAllBytes(p)));
                        note.append("；原文件字节已作为视觉图像发送（与输入框附图同一路径）");
                    } else {
                        if ((long)w*h > MAX_DECODE_PIXELS)
                            return ToolResult.error("图片区域超过2400万像素；请指定x/y/width/height裁剪，或用小于5MB的PNG/JPEG原图");
                        BufferedImage image = readRegion(reader,x,y,w,h);
                        byte[] encoded = encode(image,rotate);
                        if (encoded == null) return ToolResult.error("编码图像超过5MB；请缩小裁剪范围");
                        parts.add(part(p.getFileName().toString()+"#"+x+","+y+","+w+","+h,
                                isPng(encoded)?"image/png":"image/jpeg",encoded));
                        note.append("；裁剪 ").append(x).append(',').append(y).append(',').append(w).append(',').append(h)
                            .append("；顺时针旋转 ").append(rotate).append("；局部像素已附入视觉上下文");
                    }
                    if (whole && !"overview".equals(detail) && ("tiles".equals(detail) || Math.max(w,h)>TILE_TRIGGER)
                            && (long)originalW*originalH<=MAX_DECODE_PIXELS) {
                        int rows = h>=w*1.4?2:1, cols = w>=h*1.4?2:1;
                        if (rows==1 && cols==1) { rows=2; cols=2; }
                        int added=0;
                        for (int row=0;row<rows;row++) for(int col=0;col<cols;col++) {
                            int left=Math.max(0,col*w/cols-48), top=Math.max(0,row*h/rows-48);
                            int right=Math.min(w,(col+1)*w/cols+48), bottom=Math.min(h,(row+1)*h/rows+48);
                            BufferedImage tile=readRegion(reader,left,top,right-left,bottom-top);
                            byte[] encoded=encode(tile,0);
                            if(encoded==null) { note.append("；局部图").append(row+1).append('-').append(col+1).append("超过5MB未发送"); continue; }
                            parts.add(part(p.getFileName().toString()+"#局部("+left+","+top+","+(right-left)+","+(bottom-top)+")",
                                    isPng(encoded)?"image/png":"image/jpeg",encoded));
                            added++;
                        }
                        if(added>0) note.append("；另附").append(added).append("张有坐标的局部清晰视图，先看全图再核对局部小字");
                    } else if(whole && (long)originalW*originalH>MAX_DECODE_PIXELS && !"overview".equals(detail)) {
                        note.append("；原图过大，未自动分区，请用ViewImage按坐标裁剪复核小字");
                    }
                    note.append("。GIF仅首帧；读不清须标待核对，不得猜测品牌型号。");
                    ToolResult result = ToolResult.success(note.toString());
                    result.images = parts;
                    return result;
                } finally { reader.dispose(); }
            }
        } catch(Exception e) { return ToolResult.error("读图失败: "+e.getClass().getSimpleName()+": "+e.getMessage()); }
    }

    private static BufferedImage readRegion(ImageReader reader,int x,int y,int w,int h) throws Exception {
        ImageReadParam param=reader.getDefaultReadParam();
        param.setSourceRegion(new java.awt.Rectangle(x,y,w,h));
        return reader.read(0,param);
    }
    private static byte[] encode(BufferedImage source,int rotate) throws Exception {
        int w=source.getWidth(), h=source.getHeight();
        BufferedImage out=new BufferedImage(rotate%180==0?w:h,rotate%180==0?h:w,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=out.createGraphics();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,out.getWidth(),out.getHeight());
            if(rotate==90){g.translate(h,0);g.rotate(Math.PI/2);}
            if(rotate==180){g.translate(w,h);g.rotate(Math.PI);}
            if(rotate==270){g.translate(0,w);g.rotate(-Math.PI/2);}
            g.drawImage(source,0,0,null);
        } finally { g.dispose(); }
        ByteArrayOutputStream png=new ByteArrayOutputStream();
        if(ImageIO.write(out,"png",png) && png.size()<=ImagePart.MAX_FILE_BYTES) return png.toByteArray();
        for(float quality:new float[]{0.94f,0.86f,0.76f}) {
            Iterator<ImageWriter> writers=ImageIO.getImageWritersByFormatName("jpeg");
            if(!writers.hasNext()) break;
            ImageWriter writer=writers.next();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(javax.imageio.stream.ImageOutputStream stream=ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(stream);
                ImageWriteParam param=writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                writer.write(null,new IIOImage(out,null,null),param);
            } finally { writer.dispose(); }
            if(bytes.size()<=ImagePart.MAX_FILE_BYTES) return bytes.toByteArray();
        }
        return null;
    }
    private static boolean isPng(byte[] bytes) { return bytes.length>=8 && (bytes[0]&255)==137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71; }
    private static ImagePart part(String name,String mime,byte[] bytes) {
        ImagePart part=new ImagePart();part.name=name;part.mime=mime;
        part.base64=Base64.getEncoder().encodeToString(bytes);return part;
    }
    private static int number(JsonObject a,String key,int fallback) {return ToolArguments.integer(a,key,fallback);}
}
