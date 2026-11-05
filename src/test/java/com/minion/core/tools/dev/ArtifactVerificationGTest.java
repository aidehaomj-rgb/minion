package com.minion.core.tools.dev;

import com.google.gson.*;
import com.minion.core.tools.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.io.*;
import java.util.Arrays;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.Assert.*;

public class ArtifactVerificationGTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private JsonObject args(String json){return JsonParser.parseString(json).getAsJsonObject();}
    private ArtifactVerificationTool tool(){return new ArtifactVerificationTool(new Workspace(tmp.getRoot().toString()));}
    private JsonObject facts(ToolResult r){return JsonParser.parseString(r.output).getAsJsonObject().getAsJsonObject("facts");}
    @Test public void fileReportsStableHashButNeverRuntimeOrBusiness()throws Exception{
        Path p=tmp.newFile("中文 空格.txt").toPath();Files.write(p,"完成".getBytes(StandardCharsets.UTF_8));
        JsonObject a=args("{action:'file',path:'中文 空格.txt',stableMs:100}");ToolResult r=tool().execute(a);assertTrue(r.output,r.ok);
        JsonObject o=JsonParser.parseString(r.output).getAsJsonObject();assertFalse(o.get("runtimeVerified").getAsBoolean());assertFalse(o.get("businessVerified").getAsBoolean());assertEquals(ArtifactVerifier.sha256(p),facts(r).get("sha256").getAsString());
    }
    @Test public void hashMismatchFails()throws Exception{tmp.newFile("a");JsonObject a=args("{action:'file',path:'a',stableMs:100}");a.addProperty("sha256",String.format("%064d",0));assertFalse(tool().execute(a).ok);}
    @Test public void outsidePathIsRejectedBeforeRead()throws Exception{Path p=Files.createTempFile("g-outside",".txt");try{JsonObject a=args("{action:'file'}");a.addProperty("path",p.toString());ToolResult r=tool().execute(a);assertFalse(r.ok);assertTrue(r.output.contains("未获授权"));}finally{Files.deleteIfExists(p);}}
    @Test public void temporaryDownloadCannotPass()throws Exception{tmp.newFile("a.crdownload");assertFalse(tool().execute(args("{action:'file',path:'a.crdownload',stableMs:100}")).ok);}
    @Test public void fileChangeRestartsStabilityWindow()throws Exception{
        Path p=tmp.newFile("changing").toPath();Files.write(p,new byte[]{1});Thread writer=new Thread(()->{try{Thread.sleep(70);Files.write(p,new byte[]{2,3,4});}catch(Exception e){throw new RuntimeException(e);}});writer.start();
        JsonObject out=ArtifactVerifier.stableFile(p,200,2000,x->true);writer.join();assertEquals(3,out.get("size").getAsInt());assertEquals(ArtifactVerifier.sha256(p),out.get("sha256").getAsString());
    }
    @Test public void interruptedCheckCancels()throws Exception{tmp.newFile("a");Thread.currentThread().interrupt();try{assertFalse(tool().execute(args("{action:'file',path:'a'}")).ok);assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}}
    @Test public void pngSizeChecksAreNotRenderChecks()throws Exception{ImageIO.write(new BufferedImage(30,20,BufferedImage.TYPE_INT_RGB),"png",tmp.newFile("a.png"));ToolResult r=tool().execute(args("{action:'image',path:'a.png',width:30,height:20,stableMs:100}"));assertTrue(r.output,r.ok);assertFalse(facts(r).getAsJsonObject("image").get("renderVerified").getAsBoolean());assertEquals("unknown",facts(r).getAsJsonObject("image").get("overflow").getAsString());assertFalse(tool().execute(args("{action:'image',path:'a.png',width:31,height:20,stableMs:100}")).ok);}
    @Test public void svgMetadataListsFontsButDoesNotClaimTheyExist()throws Exception{Path p=tmp.newFile("a.svg").toPath();Files.write(p,"<svg xmlns='http://www.w3.org/2000/svg' width='128px' height='64' viewBox='0 0 128 64'><text font-family='Absent Font'>x</text></svg>".getBytes(StandardCharsets.UTF_8));JsonObject image=ArtifactVerifier.image(p);assertEquals(128,image.get("width").getAsInt());assertEquals("Absent Font",image.getAsJsonArray("declaredFonts").get(0).getAsString());assertEquals("unknown",image.get("fontAvailability").getAsString());}
    @Test public void svgExternalEntitiesAreNotResolved()throws Exception{Path p=tmp.newFile("bad.svg").toPath();Files.write(p,"<!DOCTYPE svg [<!ENTITY x SYSTEM 'http://127.0.0.1:1/secret'>]><svg xmlns='http://www.w3.org/2000/svg'>&x;</svg>".getBytes(StandardCharsets.UTF_8));try{ArtifactVerifier.image(p);fail();}catch(Exception expected){assertNotNull(expected.getMessage());}}
    public static void pe(Path p)throws Exception{byte[] bytes=new byte[256];bytes[0]='M';bytes[1]='Z';bytes[60]=(byte)128;bytes[128]='P';bytes[129]='E';bytes[132]=0x64;bytes[133]=(byte)0x86;Files.write(p,bytes);}
    @Test public void peTargetIsNotAnExecutionReceipt()throws Exception{Path p=tmp.newFile("app.exe").toPath();pe(p);ToolResult r=tool().execute(args("{action:'executable',path:'app.exe',platform:'windows',architecture:'x64',stableMs:100}"));assertTrue(r.output,r.ok);JsonObject e=facts(r).getAsJsonObject("executable");assertFalse(e.get("runtimeVerified").getAsBoolean());assertFalse(e.get("buildLogVerified").getAsBoolean());assertFalse(tool().execute(args("{action:'executable',path:'app.exe',platform:'windows',architecture:'arm64',stableMs:100}")).ok);}
    @Test public void executableWithoutTargetIsUnknown()throws Exception{Path p=tmp.newFile("a.exe").toPath();pe(p);ToolResult r=tool().execute(args("{action:'executable',path:'a.exe',stableMs:100}"));assertFalse(r.ok);assertEquals("unknown",facts(r).get("status").getAsString());}
    @Test public void machOIsOnlyHeaderEvidence()throws Exception{Path p=tmp.newFile("app").toPath();try(DataOutputStream d=new DataOutputStream(Files.newOutputStream(p))){d.writeInt(0xfeedfacf);d.writeInt(0x0100000c);}JsonObject o=ArtifactVerifier.executable(p);assertEquals("macos",o.get("platform").getAsString());assertEquals("arm64",o.get("architecture").getAsString());assertFalse(o.get("runtimeVerified").getAsBoolean());}
    @Test public void actualHttpGetAndExpectedContentAreRequired()throws Exception{try(MockWebServer s=new MockWebServer()){s.enqueue(new MockResponse().setBody("<h1>READY</h1>"));s.start();JsonObject a=args("{action:'http',expectedText:'READY'}");a.addProperty("url",s.url("/site").newBuilder().host("127.0.0.1").build().toString());ToolResult r=tool().execute(a);assertTrue(r.output,r.ok);assertEquals("GET",s.takeRequest().getMethod());assertEquals(200,facts(r).get("httpStatus").getAsInt());assertFalse(facts(r).get("browserVerified").getAsBoolean());}}
    @Test public void serverErrorAndMissingContentFail()throws Exception{try(MockWebServer s=new MockWebServer()){s.enqueue(new MockResponse().setResponseCode(500).setBody("bad"));s.enqueue(new MockResponse().setBody("wrong"));s.start();JsonObject a=args("{action:'http',expectedText:'READY'}");a.addProperty("url",s.url("/").newBuilder().host("127.0.0.1").build().toString());assertFalse(tool().execute(a).ok);assertFalse(tool().execute(a).ok);}}
    @Test public void redirectIsNotFollowed()throws Exception{try(MockWebServer s=new MockWebServer()){s.enqueue(new MockResponse().setResponseCode(302).addHeader("Location","https://example.invalid/"));s.start();JsonObject a=args("{action:'http'}");a.addProperty("url",s.url("/").newBuilder().host("127.0.0.1").build().toString());ToolResult r=tool().execute(a);assertFalse(r.ok);assertFalse(facts(r).get("redirectFollowed").getAsBoolean());assertEquals(1,s.getRequestCount());}}
    @Test public void publicAndCredentialUrlsFailClosed(){assertFalse(ArtifactVerifier.loopbackHttp(URI.create("http://127.0.0.1.attacker.invalid/")));assertFalse(ArtifactVerifier.loopbackHttp(URI.create("http://u:p@localhost/")));assertFalse(tool().execute(args("{action:'http',url:'https://example.invalid/'}")).ok);}
    @Test public void largeResponseCannotClaimSuccess()throws Exception{try(MockWebServer s=new MockWebServer()){char[] chars=new char[2*1024*1024+1];Arrays.fill(chars,'x');s.enqueue(new MockResponse().setBody(new String(chars)));s.start();JsonObject a=args("{action:'http'}");a.addProperty("url",s.url("/").newBuilder().host("127.0.0.1").build().toString());assertFalse(tool().execute(a).ok);}}
    @Test public void webpageWithoutBrowserCannotClaimBrowserVerification()throws Exception{try(MockWebServer s=new MockWebServer()){s.enqueue(new MockResponse().setBody("ready"));s.start();JsonObject a=args("{action:'webpage',checks:[{selector:'#ok'}]}");a.addProperty("url",s.url("/").newBuilder().host("127.0.0.1").build().toString());ToolResult r=tool().execute(a);assertFalse(r.ok);assertTrue(r.output.contains("HTTP证据不能冒充"));}}
    @Test public void readOnlyAndRiskClassificationIsConservative(){assertTrue(ArtifactVerificationTool.readOnlyAction("file"));assertFalse(ArtifactVerificationTool.readOnlyAction("http"));assertFalse(ArtifactVerificationTool.readOnlyAction("webpage"));assertFalse(tool().isHighRisk(args("{action:'image'}")));assertTrue(tool().isHighRisk(args("{action:'webpage'}")));assertEquals("array",tool().schema().getAsJsonObject("properties").getAsJsonObject("checks").get("type").getAsString());}
}
