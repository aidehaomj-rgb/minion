package com.minion.core.tools.data;

import com.minion.core.tools.ToolResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 参数数组调用本地Tesseract，无Shell；有界输出/超时，不安装依赖。 */
final class LocalOcr {
    static ToolResult status(String exe) {
        return run(Arrays.asList(exe,"--version"),10);
    }
    static ToolResult read(String exe,String language,Path image,int psm) {
        if(language==null||language.length()>80||!language.matches("[A-Za-z0-9_]+(\\+[A-Za-z0-9_]+)*"))
            return ToolResult.error("OCR语言仅允许如chi_sim+eng的语言代码");
        if(psm<3||psm>13) return ToolResult.error("OCR psm须为3到13");
        // 不依赖安装包中的configs/tsv文件：有些离线安装缺该文件却返回exit=0并降级成普通文本。
        ToolResult r=run(Arrays.asList(exe,image.toString(),"stdout","-l",language,"--psm",Integer.toString(psm),
                "-c","tessedit_create_tsv=1","-c","tessedit_create_txt=0"),90);
        if(r.ok&&(r.output==null||!r.output.contains("left\ttop\twidth\theight\tconf\ttext")))
            return ToolResult.error("OCR未返回可用的TSV坐标，不视为识别成功。请检查Tesseract版本/语言包: "+r.output);
        return r.ok?ToolResult.success("OCR来源: "+image+"；语言: "+language
            +"\n以下为TSV，left/top/width/height是原图像素坐标，conf是引擎分数而非事实正确率。没有识别文字不代表无品牌/型号，需视觉或人工复核。\n"+r.output):r;
    }
    private static ToolResult run(List<String> command,int timeout) {
        Process process=null;
        try {
            process=new ProcessBuilder(command).start();final Process p=process;
            final StringBuilder out=new StringBuilder(),err=new StringBuilder();
            final boolean[] truncated={false};
            Thread a=drain(p.getInputStream(),out,truncated),b=drain(p.getErrorStream(),err,truncated);
            boolean done=p.waitFor(timeout,TimeUnit.SECONDS);
            if(!done){p.destroyForcibly();p.waitFor(3,TimeUnit.SECONDS);return ToolResult.error("本地OCR超时，已终止；请缩小图片范围");}
            a.join(2000);b.join(2000);
            String result; synchronized(out){result=out.toString();} synchronized(err){if(err.length()>0)result+="\n[OCR诊断]\n"+err;}
            synchronized(truncated){if(truncated[0])result+="\n[OCR输出达到60000字符上限，未全部显示；请裁剪或分批，不可视为全图已核验]";}
            return p.exitValue()==0?ToolResult.success(result):ToolResult.error("本地OCR失败: "+result);
        } catch(InterruptedException e) {Thread.currentThread().interrupt();return ToolResult.error("OCR已中断");}
        catch(Exception e) {return ToolResult.error("OCR不可用，请在设置配置Tesseract及语言包: "+e.getMessage());}
        finally {if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
    private static Thread drain(final InputStream in,final StringBuilder out,final boolean[] truncated) {
        Thread t=new Thread(()->{
            try(Reader reader=new InputStreamReader(in,StandardCharsets.UTF_8)) {
                char[] buf=new char[2048];int n;
                while((n=reader.read(buf))!=-1) synchronized(out) {
                    int kept=Math.min(n,Math.max(0,60000-out.length()));out.append(buf,0,kept);
                    if(kept<n)synchronized(truncated){truncated[0]=true;}
                }
            }catch(IOException ignored){}
        },"minion-ocr-output");t.setDaemon(true);t.start();return t;
    }
}
