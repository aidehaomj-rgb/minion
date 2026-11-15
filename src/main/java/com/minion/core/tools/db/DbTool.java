package com.minion.core.tools.db;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.agent.RuntimeJobCancellation;
import com.minion.core.agent.RuntimeJobRegistry;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.plugin.DbConfig;

import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 只读数据库工具（按 DbType 参数化，三处实例化：DbMysql / DbPostgres / DbOracle）。
 * description 每轮由 AgentLoop 重新取，故动态拼接「当前数据源」——设置页切下拉框后无需重建工具即生效。
 * 只用当前选中数据源，不向模型暴露 datasource 参数；查询不弹高危确认窗。
 */
public class DbTool implements Tool, AutoCloseable {

    /** query 的 full 参数容错解析：布尔原值 / 字符串 "true"（大小写、首尾空白容忍），缺省或畸形一律 false */
    static boolean fullOf(JsonObject args) {
        if (args == null || !args.has("full")) return false;
        try {
            JsonElement e = args.get("full");
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            return Boolean.parseBoolean(e.getAsString().trim());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** PostgreSQL 的 schema/describe 固定提示：不建连接直接返回 */
    static final String PG_SCHEMA_HINT =
            "postgreSQL 数据源不支持列出表名（当前环境查询系统表会报错），"
                    + "请直接使用 query，例如 SELECT * FROM 表名 LIMIT 1 推断结构";
    static final String PG_DESCRIBE_HINT =
            "postgreSQL 数据源不支持表结构查询，"
                    + "请用 query 执行 SELECT * FROM 表名 LIMIT 1 自行推断字段";

    private final DbType type;
    private final DbConfig config;
    private final DbExecutor executor;
    private final Path tmpDir;
    private final DbAsyncJobs async;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DbTool(DbType type, DbConfig config, Path tmpDir) {
        this(type, config, tmpDir, new DbExecutor(tmpDir));
    }

    /** 包内注入点仅供测试；生产与异步均执行同一个 DbExecutor.query。 */
    DbTool(DbType type, DbConfig config, Path tmpDir, DbExecutor executor) {
        this.type = type;
        this.config = config;
        this.executor = executor;
        this.tmpDir = tmpDir;
        this.async = new DbAsyncJobs(type, executor, tmpDir);
    }

    /** 新 session 使用独立执行器/活句柄，沿用插件配置引用和已授权 tmp；不继承绑定。 */
    public DbTool freshForSession() { return new DbTool(type, config, tmpDir); }

    /** 首次 submit 前绑定。宿主仍须在新用户 revision 调 registry.bindTask；共享 registry 不由本工具关闭。 */
    public void bind(RuntimeJobRegistry registry, Supplier<String> taskId, LongSupplier revision) {
        async.bind(registry, taskId, revision);
    }

    /** 非阻塞 UI 取消入口；返回数为已请求取消的前台作业数，不是物理停止证明。 */
    public CompletableFuture<Integer> cancelForeground(String taskId, long revision, String reason) {
        return async.cancelForeground(taskId, revision, reason);
    }

    /** 明确用户停止的同步 JDBC 补偿；非阻塞、可重复调用，不关闭工具，不保证物理停止。 */
    public int cancelSynchronousQueries() { return executor.requestCancelActive(); }

    /** 有界事件等待；取消 token 只取消等待，停止作业须 cancel/cancelForeground。 */
    public RuntimeJobRegistry.WaitResult await(String jobId, long timeoutMillis, RuntimeJobCancellation cancellation)
            throws IOException, InterruptedException {
        return async.await(jobId, timeoutMillis, cancellation);
    }

    public String controlError() { return async.controlError(); }

    public void shutdown() { if (closed.compareAndSet(false, true)) async.shutdown(); }
    @Override public void close() { shutdown(); }

    @Override
    public String name() { return type.toolName(); }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.displayName()).append(" 只读查询（");
        DataSourceConfig ds = config == null ? null : config.currentDataSource();
        if (ds == null) {
            sb.append("当前未选择数据源，调用会返回提示，请在 设置 → 工具 中选择数据源");
        } else {
            sb.append("当前数据源: ").append(DbSecrets.redact(ds.name, ds)).append("，")
              .append(DbSecrets.redact(ds.url, ds)).append("；用户查询上限 ")
              .append(ds.effectiveQueryTimeoutSeconds()).append(" 秒（模型不可提高）");
        }
        sb.append("）。action=query 执行只读 SQL 返回 Markdown 表格（最多 ")
          .append(DbExecutor.MAX_ROWS).append(" 行）");
        sb.append("。大字段(CLOB/TEXT/LONGTEXT/JSON 等)默认截断 120 字符并标注完整长度；")
          .append("解析大字段全文请加 full=true，并将 SQL 限定到单行/少行（配合 WHERE/LIMIT），")
          .append("单个字段最多 inline ").append(DbExecutor.FULL_CELL_MAX)
          .append(" 字符，超出自动落盘并附文件路径");
        if (type.supports("schema")) {
            sb.append("；action=schema 列出表与视图；action=describe 查看表字段");
        } else {
            sb.append("。同步读取仅支持 action=query；schema/describe 在本类型下不可用，会返回提示，")
              .append("表结构请用 SELECT * FROM 表名 LIMIT 1 自行推断");
        }
        sb.append("。仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN，禁止多语句与写操作。");
        sb.append("慢查询用 action=submit（sql/full 同 query），立即返回 jobId；")
          .append("统一注册前台 one-shot=true，由宿主等待完成事件，不要反复 status 或重复 submit。")
          .append("action=status/wait/cancel 用 jobId；wait 的 timeoutMillis 默认30000、最多86400000，")
          .append("只限制本次等待，不提高数据源上限；超时/取消不保证服务端物理停止。结果只归档到授权 tmp。");
        return sb.toString();
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = SchemaGenerator.objectSchema(type.displayName() + " 只读数据库操作；query/schema/describe/submit/status/wait/cancel",
                new String[]{"action", "sql", "table", "full", "jobId", "id", "timeoutMillis"}, new String[]{"action"});
        JsonObject props = schema.getAsJsonObject("properties");
        props.getAsJsonObject("full").addProperty("type", "boolean");
        JsonObject timeout = props.getAsJsonObject("timeoutMillis");
        timeout.addProperty("type", "integer");
        timeout.addProperty("minimum", 0);
        timeout.addProperty("maximum", RuntimeJobRegistry.MAX_WAIT_MILLIS);
        timeout.addProperty("description", "仅 wait；默认30000毫秒，不改变用户数据源查询超时，禁止计时轮询");
        props.getAsJsonObject("jobId").addProperty("description", "submit 返回的 jobId；status/wait/cancel 必填（兼容 id 别名）");
        return schema;
    }

    /** 只读查询不打断心流：不弹确认窗 */
    @Override
    public boolean isHighRisk(JsonObject args) { return false; }

    @Override
    public ToolResult execute(JsonObject args) {
        try { return dispatch(args); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("JDBC 等待已中断；未重提作业，停止查询需显式 cancel");
        } catch (IOException | RuntimeException e) {
            // 控制错误不回显任意驱动/路径配置，status 也不序列化 DataSourceConfig。
            DataSourceConfig ds = config == null ? null : config.currentDataSource();
            String message = DbExecutor.firstLine(DbSecrets.redact(e.getMessage(), ds));
            if (e instanceof IOException) return ToolResult.error("JDBC 控制/归档不可用: " + message);
            // 这里也可能是执行后驱动抛出的 RuntimeException，不能宣称没有外部副作用。
            return ToolResult.error("JDBC 参数、jobId 或执行异常: " + message);
        }
    }

    private ToolResult dispatch(JsonObject args) throws IOException, InterruptedException {
        if (args == null || !args.has("action")) return ToolResult.error("缺少 action 参数");
        String action = args.get("action").getAsString().trim();
        if ("status".equals(action)) return ToolResult.success(async.status(jobId(args)));
        if ("cancel".equals(action)) return async.cancel(jobId(args), "JDBC explicit cancel");
        if ("wait".equals(action)) {
            String id = jobId(args);
            long timeout = args.has("timeoutMillis") ? args.get("timeoutMillis").getAsBigDecimal().longValueExact() : 30000L;
            RuntimeJobRegistry.WaitResult result = await(id, timeout, null);
            return ToolResult.success("waitReason=" + result.reason + "\ncanResumeTask=" + result.canResumeTask
                    + "\n" + async.status(id) + "\n超时/中断等待不重提查询；成功仍须读取真实结果。");
        }
        if (closed.get()) return ToolResult.errorWithoutSideEffects("数据库工具已关闭");
        boolean submit = "submit".equals(action);
        if (!submit && !"query".equals(action) && !"schema".equals(action) && !"describe".equals(action)) {
            // 保留同步旧接口错误文案；新增动作在 schema/description 中声明。
            return ToolResult.error("未知 action: " + action + "（支持 " + type.actionsText() + "）");
        }
        if (args.has("queryTimeoutSeconds") || args.has("timeoutSeconds") || args.has("resultPath")
                || args.has("timeoutMillis"))
            return ToolResult.errorWithoutSideEffects("查询上限只取用户数据源 queryTimeoutSeconds，模型不可覆盖；结果只写授权 tmp，timeoutMillis 仅供 wait");
        DataSourceConfig ds = config == null ? null : config.currentDataSource();
        if (ds == null) {
            return ToolResult.error(type.displayName() + " 插件未选择数据源，请在 设置 → 工具 → "
                    + type.displayName() + " 中选择");
        }
        if ("query".equals(action) || submit) {
            if (!args.has("sql")) return ToolResult.error(action + " 需要 sql 参数");
            String sql = args.get("sql").getAsString();
            String why = SqlGuard.check(sql);       // 第一层防护
            if (why != null) return ToolResult.error(why);
            return submit ? async.submit(ds, sql, fullOf(args)) : executor.query(ds, type, sql, fullOf(args));
        }
        if ("schema".equals(action)) {
            if (!type.supports("schema")) return ToolResult.error(PG_SCHEMA_HINT);
            return executor.listTables(ds, type);
        }
        if (!type.supports("describe")) return ToolResult.error(PG_DESCRIBE_HINT);
        if (!args.has("table")) return ToolResult.error("describe 需要 table 参数");
        String table = args.get("table").getAsString().trim();
        if (table.isEmpty()) return ToolResult.error("describe 需要 table 参数");
        return executor.describe(ds, type, table);
    }

    private static String jobId(JsonObject args) {
        JsonElement id = args.has("jobId") ? args.get("jobId") : args.get("id");
        if (id == null || id.isJsonNull() || id.getAsString().trim().isEmpty()) throw new IllegalArgumentException("缺少 jobId");
        return id.getAsString().trim();
    }
}
