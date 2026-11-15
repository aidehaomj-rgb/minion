package com.minion.core.tools.dev;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** OOXML assertions only. Never opens Office or evaluates formulas. */
final class OfficeArtifactVerifier {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String S="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String P="http://schemas.openxmlformats.org/package/2006/relationships";
    private OfficeArtifactVerifier() { }
    static JsonObject verify(Path path, String action, JsonObject args) throws Exception {
        Map<String,byte[]> parts=read(path);
        JsonArray checks=args.has("officeChecks")?args.getAsJsonArray("officeChecks"):new JsonArray();
        if(checks.size()==0||checks.size()>100)throw new IOException("officeChecks requires 1..100 assertions");
        JsonObject out=new JsonObject(); JsonArray results=new JsonArray(); boolean passed=true;
        Document doc=xml(parts,"docx".equals(action)?"word/document.xml":"xl/workbook.xml");
        if(!("docx".equals(action)?W:S).equals(doc.getDocumentElement().getNamespaceURI()))throw new IOException("Unsupported OOXML namespace");
        for(JsonElement e:checks){
            JsonObject c=e.getAsJsonObject(), result=new JsonObject(); boolean match;
            if("docx".equals(action)){
                Node target=doc;
                if(c.has("table")){
                    target=at(doc.getElementsByTagNameNS(W,"tbl"),index(c,"table"));
                    target=at(children(target,W,"tr"),index(c,"row"));
                    target=at(children(target,W,"tc"),index(c,"column"));
                }
                String actual=textNodes(target,W,"t");
                String expected=required(c,"expectedText"); match=actual.contains(expected);
                result.addProperty("textMatched",match);
            }else{
                String sheet=required(c,"sheet"), address=required(c,"cell");
                if(!address.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))throw new IOException("Invalid cell address");
                Element sh=null;
                NodeList sheets=doc.getElementsByTagNameNS(S,"sheet");
                for(int i=0;i<sheets.getLength();i++)if(sheet.equals(((Element)sheets.item(i)).getAttribute("name")))sh=(Element)sheets.item(i);
                if(sh==null)throw new IOException("Sheet not found: "+sheet);
                Document rels=xml(parts,"xl/_rels/workbook.xml.rels"); String target=null;
                NodeList rs=rels.getElementsByTagNameNS(P,"Relationship");
                for(int i=0;i<rs.getLength();i++){
                    Element rel=(Element)rs.item(i);
                    if(sh.getAttributeNS(R,"id").equals(rel.getAttribute("Id"))){
                        if("External".equals(rel.getAttribute("TargetMode"))||!rel.getAttribute("Type").equals(R+"/worksheet"))throw new IOException("Not an internal worksheet relationship");
                        target=rel.getAttribute("Target");
                    }
                }
                if(target==null||target.contains("\\")||target.contains(":"))throw new IOException("Invalid worksheet relationship");
                String part=(target.startsWith("/")?target.substring(1):"xl/"+target);
                part=java.net.URI.create(part).normalize().getPath();
                if(!part.startsWith("xl/")||part.contains(".."))throw new IOException("Worksheet escapes xl");
                Document ws=xml(parts,part); Element cell=null; NodeList cells=ws.getElementsByTagNameNS(S,"c");
                for(int i=0;i<cells.getLength();i++)if(address.equals(((Element)cells.item(i)).getAttribute("r"))){if(cell!=null)throw new IOException("Duplicate cell");cell=(Element)cells.item(i);}
                boolean formula=cell!=null&&cell.getElementsByTagNameNS(S,"f").getLength()>0;
                String value=cell==null?"":textNodes(cell,S,"v");
                String cache=!formula?"not_formula":cell.getElementsByTagNameNS(S,"v").getLength()==0?"missing":"present_unverified";
                if(cell!=null&&"inlineStr".equals(cell.getAttribute("t")))value=textNodes(cell,S,"t");
                if(cell!=null&&"s".equals(cell.getAttribute("t"))){
                    Document strings=xml(parts,"xl/sharedStrings.xml");
                    value=textNodes(at(strings.getElementsByTagNameNS(S,"si"),Integer.parseInt(value)),S,"t");
                }
                if(!c.has("expectedValue")&&!c.has("formulaCache"))throw new IOException("Cell assertion requires expectedValue or formulaCache");
                match=cell!=null;
                if(c.has("expectedValue"))match&=required(c,"expectedValue").equals(value);
                if(c.has("formulaCache")){
                    String expected=required(c,"formulaCache");
                    if(!Arrays.asList("not_formula","missing","present_unverified").contains(expected))throw new IOException("Invalid formulaCache");
                    match&=expected.equals(cache);
                }
                result.addProperty("sheet",sheet);result.addProperty("cell",address);result.addProperty("value",value);
                result.addProperty("formulaCache",cache);result.addProperty("formula",cell==null?"":textNodes(cell,S,"f"));
                result.addProperty("valueType",cell==null?"missing":cell.getAttribute("t"));
            }
            result.addProperty("matched",match);results.add(result);passed&=match;
        }
        out.addProperty("status",passed?"passed":"failed");out.add("assertions",results);
        out.addProperty("scope","ooxml_structure_and_assertions_not_office_render_or_recalculation");
        out.addProperty("paginationVerified",false);out.addProperty("recalculationVerified",false);
        out.addProperty("formulaCacheBoundary","present_unverified is stored evidence only; freshness and Excel recalculation unknown");
        return out;
    }
    private static String required(JsonObject o,String k)throws IOException{if(!o.has(k)||!o.get(k).isJsonPrimitive())throw new IOException("Missing "+k);return o.get(k).getAsString();}
    private static int index(JsonObject o,String k)throws IOException{int i=Integer.parseInt(required(o,k));if(i<0)throw new IOException("Indices are zero based");return i;}
    private static Node at(NodeList list,int i)throws IOException{if(i<0||i>=list.getLength())throw new IOException("Index not found: "+i);return list.item(i);}
    private static List<Node> children(Node node,String ns,String name){List<Node> out=new ArrayList<Node>();for(Node n=node.getFirstChild();n!=null;n=n.getNextSibling())if(ns.equals(n.getNamespaceURI())&&name.equals(n.getLocalName()))out.add(n);return out;}
    private static Node at(List<Node> list,int i)throws IOException{if(i>=list.size())throw new IOException("Table index not found");return list.get(i);}
    private static String textNodes(Node node,String ns,String name){NodeList list=node instanceof Document?((Document)node).getElementsByTagNameNS(ns,name):((Element)node).getElementsByTagNameNS(ns,name);StringBuilder b=new StringBuilder();for(int i=0;i<list.getLength();i++)b.append(list.item(i).getTextContent());return b.toString();}
    private static Document xml(Map<String,byte[]> parts,String name)throws Exception{
        byte[] bytes=parts.get(name);if(bytes==null)throw new IOException("Missing OOXML part: "+name);
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static Map<String,byte[]> read(Path path)throws IOException{
        if(Files.size(path)>ArtifactVerifier.MAX_FILE_BYTES)throw new IOException("Office file exceeds 256MB");
        Map<String,byte[]> parts=new HashMap<String,byte[]>();Set<String> names=new HashSet<String>();long total=0;int count=0;
        try(ZipInputStream in=new ZipInputStream(Files.newInputStream(path))){ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=in.getNextEntry())!=null){
                String name=entry.getName();if(++count>2048||!names.add(name)||name.startsWith("/")||name.contains("..")||name.contains("\\"))throw new IOException("Unsafe/duplicate ZIP entry or too many entries");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();int n;long size=0;
                while((n=in.read(buffer))!=-1){size+=n;total+=n;if(size>4L*1024*1024||total>32L*1024*1024)throw new IOException("Office ZIP expanded size limit");if(name.endsWith(".xml")||name.endsWith(".rels"))bytes.write(buffer,0,n);}
                if(bytes.size()>0)parts.put(name,bytes.toByteArray());
                in.closeEntry();
            }
        }return parts;
    }
}
