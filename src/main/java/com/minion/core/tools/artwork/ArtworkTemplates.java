package com.minion.core.tools.artwork;

import com.google.gson.*;

/** 有留白和清晰层级的起点；示例文案不是用户数据/最终交付。 */
public final class ArtworkTemplates {
    private ArtworkTemplates(){ }
    public static JsonObject create(String kind,String title,String subtitle) {
        JsonObject scene=new JsonObject();scene.addProperty("version",1);scene.addProperty("width",1600);scene.addProperty("height",900);
        scene.addProperty("fontFamily","Microsoft YaHei");scene.addProperty("background","#F3F6FA");
        JsonArray layers=new JsonArray();scene.add("layers",layers);
        scene.addProperty("usageNote","这是可编辑的本地排版起点；示例数据必须替换为用户真实资料，不是扩散生图。");
        if("cover".equals(kind)) {
            scene.add("background",gradient("#102037","#264D62"));
            ellipse(layers,1080,100,360,"#3BA59C",.13);ellipse(layers,1220,450,220,"#9CE0C2",.09);
            rect(layers,100,128,68,7,"#72D5C2",3);
            text(layers,100,170,1260,50,"OFFLINE / VISUAL DESIGN",22,"#91C8D0",false);
            text(layers,100,290,1190,210,title.isEmpty()?"让信息更清晰\n让表达更有力量":title,76,"#FFFFFF",true);
            text(layers,105,545,1100,105,subtitle.isEmpty()?"可编辑源稿 · 高清 PNG · 矢量 SVG\n请替换为本次任务的标题与说明":subtitle,29,"#BFCDD7",false);
            line(layers,105,744,1495,744,"#476879",1,false);
            text(layers,105,778,1200,40,"本地绘图 / 设计源稿可持续修改",20,"#9AB5C1",false);
        } else if("process".equals(kind)) {
            text(layers,88,66,1400,70,title.isEmpty()?"从资料到交付，一条清晰的路径":title,49,"#10243A",true);
            text(layers,90,153,1400,65,subtitle.isEmpty()?"先整理事实，再选择表达；保留证据与可编辑源稿。":subtitle,25,"#64748B",false);
            String[] heads={"明确目标","整理证据","构建设计","核对交付"};
            String[] notes={"确定对象、尺寸和用途\n列出必须保留的内容","核实数据与来源\n区分事实与推断","统一字体、配色与留白\n生成清晰图层","检查文字、数值和边界\n交付源稿与成图"};
            for(int i=0;i<4;i++) {
                int x=88+i*372;
                rect(layers,x,300,312,342,"#FFFFFF",22);rect(layers,x+28,330,58,58,i==3?"#DDF1E9":"#E9EFFA",15);
                text(layers,x+37,340,45,40,"0"+(i+1),25,i==3?"#168466":"#2D5FAE",true);
                text(layers,x+28,420,260,50,heads[i],32,"#152B41",true);
                text(layers,x+28,498,260,100,notes[i],22,"#69798B",false);
                if(i<3)line(layers,x+328,470,x+358,470,"#9CAFC0",3,true);
            }
            rect(layers,88,716,1428,92,"#E4EDF4",18);
            text(layers,118,744,1360,48,"示例流程：按当前任务改写步骤，不把模板步骤自动当作新的待办。",23,"#536B80",false);
        } else if("dashboard".equals(kind)) {
            text(layers,84,58,1400,70,title.isEmpty()?"数据概览 / 用证据支撑判断":title,46,"#11283E",true);
            text(layers,86,145,1400,50,subtitle.isEmpty()?"以下均为占位示例；使用前必须替换真实数据、口径与来源。":subtitle,24,"#748292",false);
            String[] labels={"关键指标一","关键指标二","关键指标三"};
            for(int i=0;i<3;i++) {
                int x=84+i*490;rect(layers,x,246,458,210,"#FFFFFF",20);
                text(layers,x+30,272,390,45,labels[i],23,"#748292",false);
                text(layers,x+30,333,390,72,"待填数据",48,i==0?"#126C69":"#18354C",true);
            }
            rect(layers,84,504,948,308,"#FFFFFF",20);
            text(layers,114,532,820,44,"趋势 / 分布区域",27,"#18354C",true);
            line(layers,124,738,980,738,"#DBE4EB",1,false);
            for(int i=0;i<5;i++){int bar=42+i*20;rect(layers,150+i*155,738-bar,82,bar,i==4?"#3F9D92":"#D5E4EA",7);}
            text(layers,120,758,840,38,"占位示意，不代表统计结果；真实图表优先用 Matplotlib。",18,"#81909D",false);
            rect(layers,1064,504,458,308,"#183F50",20);
            text(layers,1094,538,388,48,"阅读提示",30,"#EAF6F5",true);
            text(layers,1094,611,388,150,"先说结论与分析口径\n再看指标与趋势\n最终回到原始资料核对",25,"#C0D8DE",false);
        } else throw new IllegalArgumentException("template支持cover/process/dashboard");
        return scene;
    }
    private static JsonObject node(String type,int x,int y,int w,int h){JsonObject n=new JsonObject();n.addProperty("type",type);n.addProperty("x",x);n.addProperty("y",y);n.addProperty("width",w);n.addProperty("height",h);return n;}
    private static void rect(JsonArray a,int x,int y,int w,int h,String fill,int radius){JsonObject n=node("rect",x,y,w,h);n.addProperty("fill",fill);n.addProperty("radius",radius);a.add(n);}
    private static void ellipse(JsonArray a,int x,int y,int size,String fill,double opacity){JsonObject n=node("ellipse",x,y,size,size);n.addProperty("fill",fill);n.addProperty("opacity",opacity);a.add(n);}
    private static void text(JsonArray a,int x,int y,int w,int h,String text,int size,String fill,boolean bold){JsonObject n=node("text",x,y,w,h);n.addProperty("text",text);n.addProperty("fontSize",size);n.addProperty("fill",fill);n.addProperty("bold",bold);a.add(n);}
    private static void line(JsonArray a,int x,int y,int x2,int y2,String color,int stroke,boolean arrow){JsonObject n=node("line",x,y,1,1);n.addProperty("x2",x2);n.addProperty("y2",y2);n.addProperty("stroke",color);n.addProperty("strokeWidth",stroke);n.addProperty("arrow",arrow);a.add(n);}
    private static JsonObject gradient(String from,String to){JsonObject o=new JsonObject();o.addProperty("from",from);o.addProperty("to",to);return o;}
}
