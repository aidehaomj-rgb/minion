package com.minion.core.tools.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** On-demand, bounded lexical index. No daemon, persistent cache, LSP or semantic proof.
 * Each call re-reads content (even same-size/same-mtime edits invalidate prior results).
 * impact is a flat set of candidate occurrences, NOT a transitive call graph.
 */
public final class CodeIndexTool implements Tool {
    private final Workspace workspace;
    private final BooleanSupplier cancellation;
    private static final Set<String> SKIP = new HashSet<String>(Arrays.asList(
            ".git", ".minion", "target", "node_modules", "venv", ".venv", "env",
            "__pycache__", ".idea", ".gradle", "build", "dist", ".next", "vendor"));
    private static final String ID = "[\\p{L}_$][\\p{L}\\p{N}_$]*";
    private static final Pattern TOKEN = Pattern.compile(ID);
    private static final Pattern DECL = Pattern.compile("\\b(?:class|interface|enum|record|def|function|type|namespace)\\s+(" + ID + ")");
    private static final Pattern ASSIGN = Pattern.compile("(?m)^\\s*(" + ID + ")\\s*(?::[^=\\n]{1,100})?=(?!=)");
    private static final Pattern JAVA_FIELD = Pattern.compile("(?m)^\\s*(?:(?:public|private|protected|static|final|volatile|transient)\\s+)*(?!return\\b|throw\\b|package\\b|import\\b)" + ID + "(?:<[^;{}\\n]{1,100}>)?(?:\\[\\])?\\s+(" + ID + ")\\s*(?:=|;)");
    private static final Pattern VAR = Pattern.compile("\\b(?:const|let|var)\\s+(" + ID + ")");
    private static final Pattern JAVA_METHOD = Pattern.compile("(?:^|[;{}])\\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)*(?:" + ID + "(?:\\[\\])?(?:<[^;{}\\n]{1,120}>)?\\s+)(" + ID + ")\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[^;{}]+)?[;{]", Pattern.MULTILINE);

    public CodeIndexTool(Workspace workspace) { this(workspace, () -> false); }
    public CodeIndexTool(Workspace workspace, BooleanSupplier cancellation) {
        this.workspace = Objects.requireNonNull(workspace);
        this.cancellation = Objects.requireNonNull(cancellation);
    }
    @Override public String name() { return "CodeIndex"; }
    @Override public String description() {
        return "按需 Java/Python/JS/TS 词法近似索引 symbols/references/impact；非 LSP/语义证明，impact 非传递调用图。返回行号、SHA-256、片段及截断/取消状态。";
    }
    @Override public JsonObject schema() {
        return SchemaGenerator.objectSchema(description(), new String[]{"action", "path", "symbol",
                "max_files", "max_bytes", "max_file_bytes", "max_results", "max_entries"}, new String[]{"action"});
    }
    @Override public ToolResult execute(JsonObject args) throws Exception {
        try {
            String action = text(args, "action");
            if (!Arrays.asList("symbols", "references", "impact").contains(action))
                return ToolResult.error("action must be symbols/references/impact");
            String symbol = text(args, "symbol");
            if ((!symbol.isEmpty() && (symbol.length() > 256 || !TOKEN.matcher(symbol).matches()))
                    || (!"symbols".equals(action) && symbol.isEmpty()))
                return ToolResult.error("symbol must be one literal identifier (required for references/impact)");
            String boundary = workspace.workDir(); // fixed authorization snapshot for this invocation
            Path base = Paths.get(boundary).toRealPath();
            // 双路径身份：工作区可能经符号链接别名访问（本机 C:\Users\...\Documents → D:\codex\...）。
            // base 是真实路径，而 walkFileTree 给到的是逻辑路径——只用 base 做 startsWith 会把别名
            // 路径下的所有文件误判为越界（实测根因）。两侧都接受：逻辑基用于别名访问，真实基用于
            // 真实路径访问；符号链接文件本身仍被 allowed() 的 isSymbolicLink 拒绝。
            Path logicalBase = Paths.get(boundary).toAbsolutePath().normalize();
            String raw = text(args, "path");
            Path root = (raw.isEmpty() ? workspace.cwd() : PathsGuard.resolve(workspace.cwd().toString(), raw)).toAbsolutePath().normalize();
            ToolResult guard = PathsGuard.errorIfOutside(boundary, root);
            if (guard != null) return guard;
            if (!Files.exists(root)) return ToolResult.error("path does not exist: " + root);
            final Scan scan = new Scan(base, logicalBase, boundary, action, symbol, args);
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 64, scan);
            return ToolResult.success(scan.output().toString());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid CodeIndex arguments: " + e.getMessage());
        }
    }
    private final class Scan extends SimpleFileVisitor<Path> {
        final Path base; final Path logicalBase; final String boundary, action, symbol;
        final int maxFiles, maxBytes, maxFileBytes, maxResults, maxEntries;
        int files, bytes, entries, skipped, errors;
        boolean truncated, cancelled;
        final Set<String> reasons = new LinkedHashSet<String>();
        final JsonArray hits = new JsonArray();
        Scan(Path base, Path logicalBase, String boundary, String action, String symbol, JsonObject args) {
            this.base=base; this.logicalBase=logicalBase; this.boundary=boundary; this.action=action; this.symbol=symbol;
            maxFiles=limit(args,"max_files",500,5000);
            maxBytes=limit(args,"max_bytes",8*1024*1024,32*1024*1024);
            maxFileBytes=limit(args,"max_file_bytes",512*1024,2*1024*1024);
            maxResults=limit(args,"max_results",200,2000);
            maxEntries=limit(args,"max_entries",10000,50000);
        }
        boolean stop() {
            if (Thread.currentThread().isInterrupted() || cancellation.getAsBoolean()) {
                cancelled=true; reasons.add("cancelled"); return true;
            }
            return false;
        }
        void cut(String reason) { truncated=true; reasons.add(reason); }
        boolean allowed(Path p) {
            if (!PathsGuard.inside(boundary,p) || Files.isSymbolicLink(p)) return false;
            Path absolute=p.toAbsolutePath().normalize();
            Path anchor = anchor(absolute);
            if (anchor == null) return false;
            for (Path part: anchor.relativize(absolute))
                if (SKIP.contains(part.toString().toLowerCase(Locale.ROOT))) return false;
            return true;
        }
        Path anchor(Path absolute) {
            return absolute.startsWith(logicalBase) ? logicalBase
                    : (absolute.startsWith(base) ? base : null);
        }
        FileVisitResult enter(Path p) {
            if (stop()) return FileVisitResult.TERMINATE;
            if (entries>=maxEntries) { cut("max_entries"); return FileVisitResult.TERMINATE; }
            entries++;
            return null;
        }
        @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) {
            FileVisitResult r=enter(p); if(r!=null)return r;
            if(!allowed(p)){skipped++;return FileVisitResult.SKIP_SUBTREE;}
            return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
            FileVisitResult r=enter(p); if(r!=null)return r;
            if(a.isDirectory()){cut("max_depth");return FileVisitResult.CONTINUE;}
            if(!a.isRegularFile() || !allowed(p)){skipped++;return FileVisitResult.CONTINUE;}
            String name=p.getFileName().toString().toLowerCase(Locale.ROOT);
            if(!name.matches(".*\\.(java|py|js|jsx|ts|tsx|mjs|cjs)$"))return FileVisitResult.CONTINUE;
            if(files>=maxFiles){cut("max_files");return FileVisitResult.TERMINATE;}
            if(a.size()>maxFileBytes){skipped++;cut("max_file_bytes");return FileVisitResult.CONTINUE;}
            if(bytes>=maxBytes){cut("max_bytes");return FileVisitResult.TERMINATE;}
            files++;
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            // Streaming bounds remain valid if the file grows after the attribute check.
            try(InputStream in=Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer=new byte[4096]; int n;
                while(true){
                    if(stop())return FileVisitResult.TERMINATE;
                    int remaining=Math.min(maxFileBytes-out.size(),maxBytes-bytes);
                    if(remaining==0){
                        cut(bytes>=maxBytes?"max_bytes":"max_file_bytes");skipped++;return FileVisitResult.CONTINUE;
                    }
                    n=in.read(buffer,0,Math.min(buffer.length,remaining));if(n<0)break;
                    out.write(buffer,0,n);bytes+=n;
                }
            } catch(IOException e){errors++;return FileVisitResult.CONTINUE;}
            byte[] data=out.toByteArray();
            for(byte b:data)if(b==0){skipped++;return FileVisitResult.CONTINUE;}
            String source=new String(data,StandardCharsets.UTF_8);
            String code=mask(source,name.endsWith(".py"));
            Map<Integer,String> definitions=new HashMap<Integer,String>();
            definitions(code,DECL,definitions); definitions(code,VAR,definitions);
            if(name.endsWith(".java")){definitions(code,JAVA_METHOD,definitions);definitions(code,JAVA_FIELD,definitions);}
            if(name.endsWith(".py"))definitions(code,ASSIGN,definitions);
            String hash=sha256(data);
            Matcher tokens=TOKEN.matcher(code);int line=1, cursor=0;
            while(tokens.find()){
                if(stop())return FileVisitResult.TERMINATE;
                String value=tokens.group();if(value.length()>256)continue;boolean definition=definitions.containsKey(tokens.start());
                if(!symbol.isEmpty()&&!symbol.equals(value))continue;
                if("symbols".equals(action)&&!definition)continue;
                if("references".equals(action)&&definition)continue;
                if(hits.size()>=maxResults){cut("max_results");return FileVisitResult.TERMINATE;}
                while(cursor<tokens.start())if(source.charAt(cursor++)=='\n')line++;
                int start=source.lastIndexOf('\n',tokens.start()-1)+1;
                int end=source.indexOf('\n',tokens.start());if(end<0)end=source.length();
                // Snippet around occurrence, not an unbounded source line.
                int from=Math.max(start,tokens.start()-100), to=Math.min(end,tokens.end()+160);
                Path absolute=p.toAbsolutePath().normalize();
                JsonObject hit=new JsonObject();
                hit.addProperty("file",anchor(absolute).relativize(absolute).toString().replace('\\','/'));
                hit.addProperty("line",line);hit.addProperty("column",tokens.start()-start+1);
                hit.addProperty("symbol",value);hit.addProperty("kind",definition?"definition":"reference");
                hit.addProperty("sha256",hash);hit.addProperty("snippet",source.substring(from,to));hits.add(hit);
            }
            return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFileFailed(Path p, IOException e){
            FileVisitResult r=enter(p);errors++;return r==null?FileVisitResult.CONTINUE:r;
        }
        @Override public FileVisitResult postVisitDirectory(Path p, IOException e){
            if(e!=null)errors++;return stop()?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;
        }
        JsonObject output(){
            JsonObject o=new JsonObject();o.addProperty("action",action);
            o.addProperty("approximate",true);o.addProperty("notice","Lexical approximation, not LSP or semantic proof; impact is non-transitive candidate occurrences. UTF-8 only; comments/strings excluded; overloads/scopes/import aliases unresolved.");
            o.addProperty("truncated",truncated);o.addProperty("cancelled",cancelled);
            o.addProperty("complete",!truncated&&!cancelled&&errors==0);
            o.addProperty("files_scanned",files);o.addProperty("bytes_read",bytes);o.addProperty("entries_visited",entries);
            o.addProperty("skipped",skipped);o.addProperty("errors",errors);
            JsonArray why=new JsonArray();for(String s:reasons)why.add(s);o.add("reasons",why);o.add("results",hits);return o;
        }
    }
    private static void definitions(String code,Pattern pattern,Map<Integer,String> out){
        Matcher m=pattern.matcher(code);while(m.find())out.put(m.start(1),m.group(1));
    }
    /** Preserve offsets/newlines while masking common strings and comments. Regex literals and
     * template interpolation are deliberately not parsed (templates are masked in full). */
    private static String mask(String source,boolean python){
        char[] c=source.toCharArray();int i=0;
        while(i<c.length){
            int start=i;char ch=c[i];
            if((python&&ch=='#')||(!python&&ch=='/'&&i+1<c.length&&c[i+1]=='/')){
                while(i<c.length&&c[i]!='\n')i++;
            }else if(!python&&ch=='/'&&i+1<c.length&&c[i+1]=='*'){
                i+=2;while(i+1<c.length&&!(c[i]=='*'&&c[i+1]=='/'))i++;
                i=Math.min(c.length,i+2);
            }else if(ch=='\''||ch=='"'||(!python&&ch=='`')){
                boolean triple=python&&i+2<c.length&&c[i+1]==ch&&c[i+2]==ch;
                i+=triple?3:1;
                while(i<c.length){
                    if(c[i]=='\\'){i=Math.min(c.length,i+2);continue;}
                    if(c[i]==ch&&(!triple||(i+2<c.length&&c[i+1]==ch&&c[i+2]==ch))){i+=triple?3:1;break;}
                    i++;
                }
            }else{i++;continue;}
            for(int j=start;j<i;j++)if(c[j]!='\n'&&c[j]!='\r')c[j]=' ';
        }
        return new String(c);
    }
    private static String sha256(byte[] bytes){
        try{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String text(JsonObject o,String key){return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():"";}
    private static int limit(JsonObject o,String key,int fallback,int ceiling){
        String raw=text(o,key);if(raw.isEmpty())return fallback;
        int value=Integer.parseInt(raw);if(value<1)throw new IllegalArgumentException(key+" must be positive");return Math.min(value,ceiling);
    }
}



