package com.minion.core.agent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Bounded CSV/OOXML audit, no formula evaluation or macro execution. */
final class TableFacts {
    final List<String> headers=new ArrayList<String>();
    final List<List<String>> rows=new ArrayList<List<String>>();
    static TableFacts read(Path p,String sheet)throws Exception {
        TableFacts t=new TableFacts();String name=p.toString().toLowerCase(Locale.ROOT);
        if(name.endsWith(".csv")){if(Files.size(p)>10*1024*1024)throw new IOException("CSV超过10MB");parseCsv(new String(Files.readAllBytes(p),StandardCharsets.UTF_8),t);return t;}
        if(!name.endsWith(".xlsx"))throw new IOException("表格验收只支持UTF-8 CSV和XLSX");
        try(ZipFile zip=new ZipFile(p.toFile())){
            Document wb=xml(zip,"xl/workbook.xml");NodeList sheets=wb.getElementsByTagName("sheet");String rid=null;
            for(int i=0;i<sheets.getLength();i++){Element e=(Element)sheets.item(i);if(sheet.isEmpty()||sheet.equals(e.getAttribute("name"))){rid=e.getAttribute("r:id");break;}}
            if(rid==null)throw new IOException("找不到sheet");Document rel=xml(zip,"xl/_rels/workbook.xml.rels");NodeList links=rel.getElementsByTagName("Relationship");String target=null;
            for(int i=0;i<links.getLength();i++){Element e=(Element)links.item(i);if(rid.equals(e.getAttribute("Id"))){if("External".equals(e.getAttribute("TargetMode")))throw new IOException("不允许外部sheet");target=e.getAttribute("Target");}}
            if(target==null)throw new IOException("sheet关系缺失");target=target.startsWith("/")?target.substring(1):"xl/"+target;
            if(target.contains("..")||target.contains("\\"))throw new IOException("sheet路径非法");
            List<String> shared=new ArrayList<String>();if(zip.getEntry("xl/sharedStrings.xml")!=null){NodeList list=xml(zip,"xl/sharedStrings.xml").getElementsByTagName("si");for(int i=0;i<list.getLength();i++)shared.add(text((Element)list.item(i)));}
            NodeList rr=xml(zip,target).getElementsByTagName("row");for(int i=0;i<rr.getLength();i++){
                if(i>200000)throw new IOException("行数超过20万");NodeList cc=((Element)rr.item(i)).getElementsByTagName("c");List<String> row=new ArrayList<String>();
                for(int j=0;j<cc.getLength();j++){Element c=(Element)cc.item(j);String ref=c.getAttribute("r");int idx=0;for(char v:ref.toCharArray()){if(v<'A'||v>'Z')break;idx=idx*26+v-'A'+1;}if(idx<1||idx>16384)throw new IOException("单元格坐标非法");while(row.size()<idx)row.add("");
                    String value="";if("inlineStr".equals(c.getAttribute("t")))value=text(c);else {NodeList vs=c.getElementsByTagName("v");if(vs.getLength()>0)value=vs.item(0).getTextContent();if("s".equals(c.getAttribute("t"))&&!value.isEmpty())value=shared.get(Integer.parseInt(value));}
                    row.set(idx-1,value);
                }add(t,row);
            }
        }return t;
    }
    private static String text(Element e){StringBuilder b=new StringBuilder();NodeList list=e.getElementsByTagName("t");for(int i=0;i<list.getLength();i++)b.append(list.item(i).getTextContent());return b.toString();}
    private static Document xml(ZipFile zip,String path)throws Exception {
        ZipEntry e=zip.getEntry(path);if(e==null)throw new IOException("缺少OOXML: "+path);ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=zip.getInputStream(e)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>10*1024*1024)throw new IOException("XML解压超过10MB");out.write(b,0,n);}}
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);return f.newDocumentBuilder().parse(new ByteArrayInputStream(out.toByteArray()));
    }
    private static void add(TableFacts t,List<String> row){if(row.stream().allMatch(String::isEmpty))return;if(t.headers.isEmpty()){if(!row.isEmpty())row.set(0,row.get(0).replace("\uFEFF",""));t.headers.addAll(row);}else t.rows.add(row);}
    private static void parseCsv(String text,TableFacts t)throws IOException {
        List<String> row=new ArrayList<String>();StringBuilder cell=new StringBuilder();boolean quote=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='"'){if(quote&&i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quote=!quote;}
            else if(!quote&&(c==','||c=='\n'||c=='\r')){row.add(cell.toString());cell.setLength(0);if(c!=','){add(t,row);row=new ArrayList<String>();if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}}else cell.append(c);}
        if(quote)throw new IOException("CSV引号未闭合");if(cell.length()>0||!row.isEmpty()){row.add(cell.toString());add(t,row);}
    }
}
