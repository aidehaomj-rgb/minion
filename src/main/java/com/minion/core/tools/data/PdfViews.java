package com.minion.core.tools.data;

import com.google.gson.JsonObject;
import com.minion.core.tools.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.io.MemoryUsageSetting;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** 已授权文件的分页/视觉读取；权限检查必须由调用者先完成。 */
final class PdfViews {
    private static final long MAX_FILE = 50L*1024*1024;
    private static final long MAX_PIXELS = 12000000L;
    private static final int MAX_CHARS = 60000;
    private static final Map<String,String> TEXT_CACHE = new LinkedHashMap<String,String>(32,0.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<String,String> e) {return size()>32;}
    };
    static ToolResult read(Path input,JsonObject args,boolean visual,Path tmp) throws Exception {
        if(Files.size(input)>MAX_FILE) return ToolResult.error("PDF超过50MB，请先拆分");
        int start=number(args,"startPage",1),end=number(args,"endPage",visual?start:start+4);
        if(start<1||end<start||end-start>=20) return ToolResult.error("页码从1开始，每次最多20页；pdf_view一次只看1页");
        if(visual&&end!=start) return ToolResult.error("pdf_view一次只看1页，请逐页调用");
        byte[] snapshot;
        try(java.io.InputStream stream=Files.newInputStream(input);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[32768];int count;
            while((count=stream.read(buffer))!=-1){if((long)bytes.size()+count>MAX_FILE)return ToolResult.error("PDF读取时超过50MB上限");bytes.write(buffer,0,count);}
            snapshot=bytes.toByteArray();
        }
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(snapshot);
        StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format("%02x",b&255));
        String version=hex.toString();
        try(PDDocument doc=PDDocument.load(new java.io.ByteArrayInputStream(snapshot),MemoryUsageSetting.setupTempFileOnly())) {
            if(start>doc.getNumberOfPages()) return ToolResult.error("起始页超过总页数: "+doc.getNumberOfPages());
            end=Math.min(end,doc.getNumberOfPages());
            String header="PDF: "+input+"\n版本SHA256: "+version+"\n总页数: "+doc.getNumberOfPages()+"；本次页码: "+start+"-"+end;
            if(!visual) {
                StringBuilder body=new StringBuilder(header);int last=start-1;
                for(int page=start;page<=end;page++) {
                    String key=version+":"+page,text;
                    synchronized(TEXT_CACHE) {text=TEXT_CACHE.get(key);}
                    if(text==null) {
                        PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
                        stripper.setStartPage(page);stripper.setEndPage(page);text=stripper.getText(doc);
                        // 不缓存极端大页，保留可重复核对路径。
                        if(text.length()<=MAX_CHARS) synchronized(TEXT_CACHE) {TEXT_CACHE.put(key,text);}
                    }
                    if(text.length()+body.length()>MAX_CHARS) {
                        return ToolResult.success(body+"\n[第"+page+"页文字超出单次预算，未全部显示；请用pdf_view视觉读取，或拆分后处理。已完整读取至第"+last+"页，不能据此认定全表/全文已读。]");
                    }
                    body.append("\n[第").append(page).append("页]\n").append(text.trim().isEmpty()?"[无文字层，请用pdf_view看扫描页，不代表空白页]":text);
                    last=page;
                }
                body.append("\n已读至第").append(last).append("页；剩余").append(doc.getNumberOfPages()-last).append("页。");
                if(last<doc.getNumberOfPages()) body.append("下一次startPage=").append(last+1);
                return ToolResult.success(body.toString());
            }
            int dpi=number(args,"dpi",120);
            if(dpi<72||dpi>200) return ToolResult.error("dpi须在72到200之间，小字请裁剪局部复核");
            org.apache.pdfbox.pdmodel.common.PDRectangle box=doc.getPage(start-1).getCropBox();
            double width=box.getWidth()*dpi/72.0,height=box.getHeight()*dpi/72.0;
            if(!Double.isFinite(width)||!Double.isFinite(height)||width<1||height<1||width*height>MAX_PIXELS)
                return ToolResult.error("PDF页面过大，请降低dpi或先拆分处理");
            BufferedImage image=new PDFRenderer(doc).renderImageWithDPI(start-1,dpi,org.apache.pdfbox.rendering.ImageType.RGB);
            if(!PathsGuard.inside(tmp.getParent().getParent().toString(),tmp))return ToolResult.error("PDF临时目录指向工作目录外");
            Files.createDirectories(tmp);
            Path png=Files.createTempFile(tmp,"pdf-page-",".png");
            try {
                ImageIO.write(image,"png",png.toFile());
                ToolResult result=ViewImageTool.load(png,args);
                if(!result.ok) return result;
                result.images.get(0).name=input.getFileName()+"#page="+start;
                ToolResult out=ToolResult.success(header+"\n页面图像已附入模型上下文（dpi="+dpi+"）。裁剪/旋转参数: "+args
                        +"；坐标按此DPI渲染的页面像素计算；需其他页面请继续pdf_view。");
                out.images=result.images;return out;
            } finally {Files.deleteIfExists(png);}
        }
    }
    static int number(JsonObject args,String key,int fallback) {return args.has(key)?args.get(key).getAsInt():fallback;}
}
