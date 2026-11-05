package com.minion.core.tools.artwork;

import com.google.gson.*;
import java.awt.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** 同一场景同时生成位图和可编辑 SVG；不执行脚本、不依赖 JavaFX/网络/扩散模型。 */
public final class ArtworkRenderer {
    public static final long MAX_PIXELS=16000000L;
    public static final class Result {
        public byte[] png;
        public String svg;
        public JsonObject report;
    }
    private JsonArray warnings=new JsonArray(), texts=new JsonArray();
    private final StringBuilder defs=new StringBuilder(), body=new StringBuilder();
    private final Map<String,String> imageIds=new HashMap<String,String>();
    private int embeddedBytes;
    private String defaultFont;
    private int width,height,index;

    public Result render(JsonObject scene,Map<String,BufferedImage> assets,int scale,boolean pixels) throws Exception {
        warnings=new JsonArray();texts=new JsonArray();defs.setLength(0);body.setLength(0);index=0;
        imageIds.clear();embeddedBytes=0;
        width=integer(scene,"width",1600);height=integer(scene,"height",900);
        if(width<64||height<64||width>8192||height>8192||scale<1||scale>4
                ||(long)width*height*scale*scale>MAX_PIXELS)throw new IllegalArgumentException("画布/缩放超过限制：尺寸至少64，scale=1..4，最终PNG不超过1600万像素");
        JsonArray layers=scene.has("layers")?scene.getAsJsonArray("layers"):new JsonArray();
        if(layers.size()==0||layers.size()>1000)throw new IllegalArgumentException("layers须有1..1000个图层");
        defaultFont=string(scene,"fontFamily","Microsoft YaHei");
        BufferedImage bitmap=new BufferedImage(pixels?width*scale:1,pixels?height*scale:1,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=bitmap.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        if(pixels)g.scale(scale,scale);
        try {
            JsonObject background=new JsonObject();background.add("fill",scene.has("background")?scene.get("background"):new JsonPrimitive("#F5F7FA"));
            shape(g,new Rectangle2D.Double(0,0,width,height),"<rect width=\""+width+"\" height=\""+height+"\"",background,0,0,width,height);
            for(index=0;index<layers.size();index++) {
                JsonObject node=layers.get(index).getAsJsonObject();Graphics2D layer=(Graphics2D)g.create();
                float opacity=(float)number(node,"opacity",1);
                if(opacity<0||opacity>1)throw new IllegalArgumentException("图层opacity须为0..1");
                layer.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,opacity));
                body.append("<g opacity=\"").append(opacity).append("\">\n");
                try { draw(layer,node,assets); } finally {layer.dispose();body.append("</g>\n");}
            }
        } finally {g.dispose();}
        Result out=new Result();
        if(pixels){ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",bytes);out.png=bytes.toByteArray();}
        bitmap.flush();
        out.svg="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\""+width+"\" height=\""+height+"\" viewBox=\"0 0 "+width+" "+height+"\">\n<defs>"+defs+"</defs>\n"+body+"</svg>\n";
        out.report=new JsonObject();out.report.addProperty("renderer","Java2D-local-v1");
        out.report.addProperty("width",width);out.report.addProperty("height",height);out.report.addProperty("scale",scale);
        out.report.addProperty("pixelWidth",width*scale);out.report.addProperty("pixelHeight",height*scale);
        out.report.add("warnings",warnings);out.report.add("textLayout",texts);
        out.report.addProperty("visualReview","not_performed");
        out.report.addProperty("note","本地代码绘图，不是AI扩散生图；布局检查不等于视觉验收，SVG文字依赖查看机器字体");
        return out;
    }

    private void draw(Graphics2D g,JsonObject n,Map<String,BufferedImage> assets) throws Exception {
        String type=string(n,"type","");double x=number(n,"x",0),y=number(n,"y",0);
        if("line".equals(type)) {
            double x2=number(n,"x2",x),y2=number(n,"y2",y),weight=number(n,"strokeWidth",3);
            if(weight<=0||weight>200)throw new IllegalArgumentException("strokeWidth须为0..200");
            Color c=color(string(n,"stroke","#64748B"));g.setColor(c);g.setStroke(new BasicStroke((float)weight,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x,y,x2,y2));
            body.append("<line x1=\"").append(x).append("\" y1=\"").append(y).append("\" x2=\"").append(x2).append("\" y2=\"").append(y2).append("\" stroke=\"").append(rgb(c)).append("\" stroke-opacity=\"").append(alpha(c)).append("\" stroke-width=\"").append(weight).append("\" stroke-linecap=\"round\"/>\n");
            if(n.has("arrow")&&n.get("arrow").getAsBoolean()) {
                double a=Math.atan2(y2-y,x2-x),size=Math.max(10,weight*4);
                double ax=x2-size*Math.cos(a-.45),ay=y2-size*Math.sin(a-.45),bx=x2-size*Math.cos(a+.45),by=y2-size*Math.sin(a+.45);
                Path2D p=new Path2D.Double();p.moveTo(x2,y2);p.lineTo(ax,ay);p.lineTo(bx,by);p.closePath();g.fill(p);
                body.append("<polygon points=\"").append(x2).append(',').append(y2).append(' ').append(ax).append(',').append(ay).append(' ').append(bx).append(',').append(by).append("\" fill=\"").append(rgb(c)).append("\" fill-opacity=\"").append(alpha(c)).append("\"/>\n");
            }
            bounds(x,y,x2-x,y2-y);return;
        }
        double w=number(n,"width",width-x),h=number(n,"height",height-y);
        if(w<=0||h<=0)throw new IllegalArgumentException("图层宽高必须大于0");
        bounds(x,y,w,h);
        double radius=number(n,"radius",0);if(radius<0)throw new IllegalArgumentException("radius不能为负");radius=Math.min(radius,Math.min(w,h)/2);
        if("rect".equals(type)) {
            Shape s=new RoundRectangle2D.Double(x,y,w,h,radius*2,radius*2);
            shape(g,s,"<rect x=\""+x+"\" y=\""+y+"\" width=\""+w+"\" height=\""+h+"\" rx=\""+radius+"\"",n,x,y,w,h);
        } else if("ellipse".equals(type)) {
            shape(g,new Ellipse2D.Double(x,y,w,h),"<ellipse cx=\""+(x+w/2)+"\" cy=\""+(y+h/2)+"\" rx=\""+(w/2)+"\" ry=\""+(h/2)+"\"",n,x,y,w,h);
        } else if("text".equals(type)) text(g,n,x,y,w,h);
        else if("image".equals(type)) {
            String path=string(n,"path","");BufferedImage image=assets.get(path);
            if(image==null)throw new IllegalArgumentException("未加载本地图片: "+path);
            String fit=string(n,"fit","contain");if(!Arrays.asList("cover","contain").contains(fit))throw new IllegalArgumentException("image.fit须为cover/contain");
            double factor="cover".equals(fit)?Math.max(w/image.getWidth(),h/image.getHeight()):Math.min(w/image.getWidth(),h/image.getHeight());
            g.clip(new RoundRectangle2D.Double(x,y,w,h,radius*2,radius*2));
            AffineTransform transform=new AffineTransform();transform.translate(x+(w-image.getWidth()*factor)/2,y+(h-image.getHeight()*factor)/2);transform.scale(factor,factor);g.drawImage(image,transform,null);
            String clip="image-clip-"+index;defs.append("<clipPath id=\"").append(clip).append("\"><rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(w).append("\" height=\"").append(h).append("\" rx=\"").append(radius).append("\"/></clipPath>");
            String imageId=imageIds.get(path);
            if(imageId==null) {
                ByteArrayOutputStream b=new ByteArrayOutputStream();ImageIO.write(image,"png",b);embeddedBytes+=b.size();
                if(embeddedBytes>12*1024*1024)throw new IllegalArgumentException("SVG内嵌图片超过12MB，请缩小素材像素或分图制作");
                imageId="asset-"+imageIds.size();imageIds.put(path,imageId);
                defs.append("<image id=\"").append(imageId).append("\" width=\"").append(image.getWidth()).append("\" height=\"").append(image.getHeight()).append("\" xlink:href=\"data:image/png;base64,").append(Base64.getEncoder().encodeToString(b.toByteArray())).append("\"/>");
            }
            body.append("<g clip-path=\"url(#").append(clip).append(")\"><use xlink:href=\"#").append(imageId).append("\" transform=\"translate(").append(x+(w-image.getWidth()*factor)/2).append(' ').append(y+(h-image.getHeight()*factor)/2).append(") scale(").append(factor).append(")\"/></g>\n");
        } else throw new IllegalArgumentException("不支持的图层类型: "+type+"；可用rect/ellipse/text/line/image");
    }

    private void shape(Graphics2D g,Shape shape,String tag,JsonObject n,double x,double y,double w,double h) {
        JsonElement fill=n.has("fill")?n.get("fill"):new JsonPrimitive("#FFFFFF");String svgFill;
        if(fill.isJsonObject()) {
            JsonObject f=fill.getAsJsonObject();Color from=color(string(f,"from","#2563EB")),to=color(string(f,"to","#0F172A"));
            String direction=string(f,"direction","diagonal");double ex="vertical".equals(direction)?x:x+w,ey="horizontal".equals(direction)?y:y+h;
            g.setPaint(new GradientPaint((float)x,(float)y,from,(float)ex,(float)ey,to));
            String id="gradient-"+defs.length();defs.append("<linearGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" x1=\"").append(x).append("\" y1=\"").append(y).append("\" x2=\"").append(ex).append("\" y2=\"").append(ey).append("\"><stop offset=\"0\" stop-color=\"").append(rgb(from)).append("\" stop-opacity=\"").append(alpha(from)).append("\"/><stop offset=\"1\" stop-color=\"").append(rgb(to)).append("\" stop-opacity=\"").append(alpha(to)).append("\"/></linearGradient>");
            svgFill=" fill=\"url(#"+id+")\"";
        } else {Color c=color(fill.getAsString());g.setColor(c);svgFill=" fill=\""+rgb(c)+"\" fill-opacity=\""+alpha(c)+"\"";}
        g.fill(shape);body.append(tag).append(svgFill);
        if(n.has("stroke")) {
            Color c=color(n.get("stroke").getAsString());float stroke=(float)number(n,"strokeWidth",1);
            if(stroke<=0||stroke>200)throw new IllegalArgumentException("strokeWidth须为0..200");
            g.setColor(c);g.setStroke(new BasicStroke(stroke));g.draw(shape);
            body.append(" stroke=\"").append(rgb(c)).append("\" stroke-opacity=\"").append(alpha(c)).append("\" stroke-width=\"").append(stroke).append('"');
        }
        body.append("/>\n");
    }

    private void text(Graphics2D g,JsonObject n,double x,double y,double w,double h) {
        String value=string(n,"text","");if(value.length()>20000)throw new IllegalArgumentException("单图层文字超过20000字符，请拆分");
        double size=number(n,"fontSize",24),lineHeight=number(n,"lineHeight",size*1.35);
        if(size<6||size>1000||lineHeight<size*.8||lineHeight>2000)throw new IllegalArgumentException("fontSize/lineHeight不合法");
        Font font=font(string(n,"fontFamily",defaultFont),value,n.has("bold")&&n.get("bold").getAsBoolean(),(float)size);
        if(font.canDisplayUpTo(value.replace("\n","").replace("\r",""))>=0)warn("missing_glyph","当前字体缺少部分字符，请先Artwork fonts核对，不能当作文字已清晰呈现");
        Color ink=color(string(n,"fill","#0F172A"));g.setColor(ink);g.clip(new Rectangle2D.Double(x,y,w,h));
        String clip="text-clip-"+index;defs.append("<clipPath id=\"").append(clip).append("\"><rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(w).append("\" height=\"").append(h).append("\"/></clipPath>");
        body.append("<g clip-path=\"url(#").append(clip).append(")\"><text font-family=\"").append(xml(font.getFamily())).append("\" font-size=\"").append(size).append("\" font-weight=\"").append(font.isBold()?"700":"400").append("\" fill=\"").append(rgb(ink)).append("\" fill-opacity=\"").append(alpha(ink)).append("\" xml:space=\"preserve\">");
        double top=y;int lines=0;boolean overflow=false;String align=string(n,"align","left");
        if(!Arrays.asList("left","center","right").contains(align))throw new IllegalArgumentException("align须为left/center/right");
        for(String paragraph:value.replace("\r","").split("\n",-1)) {
            if(paragraph.isEmpty()){top+=lineHeight;lines++;continue;}
            AttributedString a=new AttributedString(paragraph);a.addAttribute(TextAttribute.FONT,font);
            LineBreakMeasurer measurer=new LineBreakMeasurer(a.getIterator(),BreakIterator.getLineInstance(Locale.CHINA),g.getFontRenderContext());
            while(measurer.getPosition()<paragraph.length()) {
                int start=measurer.getPosition();TextLayout layout=measurer.nextLayout((float)w);
                if(layout==null)throw new IllegalArgumentException("文字框过窄，无法放入单个字符");
                double dx="center".equals(align)?(w-layout.getAdvance())/2:"right".equals(align)?w-layout.getAdvance():0;
                double baseline=top+layout.getAscent();
                if(baseline+layout.getDescent()>y+h+.5||layout.getAdvance()>w+.5)overflow=true;
                layout.draw(g,(float)(x+dx),(float)baseline);
                body.append("<tspan x=\"").append(x+dx).append("\" y=\"").append(baseline).append("\">").append(xml(paragraph.substring(start,measurer.getPosition()))).append("</tspan>");
                top+=lineHeight;lines++;
            }
        }
        body.append("</text></g>\n");
        if(overflow)warn("text_overflow","文字超过图层高度/宽度，保留完整源文但成图会被裁切；请调字号、宽高或拆分文案");
        JsonObject item=new JsonObject();item.addProperty("layer",index);item.addProperty("font",font.getFamily());item.addProperty("lines",lines);item.addProperty("overflow",overflow);texts.add(item);
    }
    private void bounds(double x,double y,double w,double h) {if(Math.min(x,x+w)<0||Math.min(y,y+h)<0||Math.max(x,x+w)>width+.1||Math.max(y,y+h)>height+.1)warn("outside_canvas","图层超出画布，边缘可能被裁切");}
    private void warn(String code,String text){JsonObject w=new JsonObject();w.addProperty("layer",index);w.addProperty("code",code);w.addProperty("message",text);warnings.add(w);}
    public static Font font(String preferred,String text,boolean bold,float size) {
        for(String family:new String[]{preferred,"Microsoft YaHei","微软雅黑","SimHei","Noto Sans CJK SC","Dialog"}) {
            Font f=new Font(family,bold?Font.BOLD:Font.PLAIN,1).deriveFont(size);
            if(f.canDisplayUpTo(text.replace("\n","").replace("\r",""))<0)return f;
        }
        return new Font("Dialog",bold?Font.BOLD:Font.PLAIN,1).deriveFont(size);
    }
    public static String string(JsonObject o,String k,String d){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():d;}
    public static int integer(JsonObject o,String k,int d){double n=number(o,k,d);if(n!=Math.rint(n))throw new IllegalArgumentException(k+"须为整数");return (int)n;}
    public static double number(JsonObject o,String k,double d){double n=o.has(k)?o.get(k).getAsDouble():d;if(Double.isNaN(n)||Double.isInfinite(n)||Math.abs(n)>1000000)throw new IllegalArgumentException(k+"不是有效数值");return n;}
    private static Color color(String v) {
        if("none".equals(v)||"transparent".equals(v))return new Color(0,0,0,0);
        if("white".equals(v))return Color.WHITE;if("black".equals(v))return Color.BLACK;
        if(v.matches("#[0-9a-fA-F]{3}"))v="#"+v.charAt(1)+v.charAt(1)+v.charAt(2)+v.charAt(2)+v.charAt(3)+v.charAt(3);
        if(v.matches("#[0-9a-fA-F]{6}"))return new Color(Integer.parseInt(v.substring(1),16));
        if(v.matches("#[0-9a-fA-F]{8}")){long rgba=Long.parseLong(v.substring(1),16);return new Color((int)(rgba>>24)&255,(int)(rgba>>16)&255,(int)(rgba>>8)&255,(int)rgba&255);}
        throw new IllegalArgumentException("颜色须为#RGB/#RRGGBB/#RRGGBBAA/transparent: "+v);
    }
    private static String rgb(Color c){return String.format(Locale.ROOT,"#%02x%02x%02x",c.getRed(),c.getGreen(),c.getBlue());}
    private static double alpha(Color c){return c.getAlpha()/255.0;}
    private static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
}
