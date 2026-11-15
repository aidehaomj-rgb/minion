package com.minion.core.tools.db;

import com.minion.core.tools.ToolResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 只读 SQL 执行器：**每次调用新建 JDBC 连接、用完即关（禁用连接池）**。
 * 三层只读防护的第 2、3 层在这里：Connection.setReadOnly(true) + 只用 executeQuery
 * （第 1 层 SqlGuard 由 DbTool 在调用前执行）。另加 setMaxRows 限流与 setQueryTimeout 防卡死。
 * 所有失败都转成 ToolResult.error（文案口径见设计文档第 5 节），不向调用方抛异常。
 */
public class DbExecutor implements AutoCloseable {

    /** 返回给模型的最大行数；setMaxRows 用 MAX_ROWS+1 探测是否被截断 */
    public static final int MAX_ROWS = 100;
    /** full 全文模式下单元格 inline 上限（普通模式 CELL_MAX=120；单值查询在 30000 总预算内不触发落盘） */
    public static final int FULL_CELL_MAX = 20000;
    public static final int QUERY_TIMEOUT_SECONDS = 300;
    public static final int LOGIN_TIMEOUT_SECONDS = 10;
    /** schema 动作（表清单）上限 */
    public static final int SCHEMA_MAX_ROWS = 500;

    private final Path tmpDir;
    private final Set<DbQueryControl> active = new HashSet<DbQueryControl>();
    private final ThreadPoolExecutor cancellations = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(32), r -> {
                Thread t = new Thread(r, "minion-jdbc-cancel"); t.setDaemon(true); return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;

    public DbExecutor(Path tmpDir) { this.tmpDir = tmpDir; }

    public DbExecutor(String tmpDir) { this(tmpDir == null ? null : Paths.get(tmpDir)); }

    DbQueryControl control(Path outputDir) { return new DbQueryControl(cancellations, outputDir); }

    private DbQueryControl synchronousControl() { return new DbQueryControl(cancellations, tmpDir, true); }

    /**
     * 明确用户停止时的非阻塞入口，仅请求取消本实例的同步 query/schema/describe。
     * 返回值是请求数，不是物理停止数；不关执行器/连接，异步作业须先经共享账本取消围栏。
     */
    public int requestCancelActive() {
        List<DbQueryControl> controls;
        synchronized (this) { controls = new ArrayList<DbQueryControl>(active); }
        int count = 0;
        for (DbQueryControl control : controls) if (control.synchronous) { control.cancel(); count++; }
        return count;
    }

    private synchronized boolean begin(DbQueryControl control) {
        if (closed) return false;
        active.add(control);
        return true;
    }

    private synchronized void end(DbQueryControl control) {
        control.finished();
        active.remove(control);
    }

    /** 可替换连接工厂供无网络测试；生产路径仍显式加载已有 JDBC 驱动。 */
    protected Connection connect(DataSourceConfig ds, DbType type) throws SQLException, ClassNotFoundException {
        Class.forName(type.driverClass());
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        return DriverManager.getConnection(ds.url, ds.user, ds.password);
    }

    /** 连接结果：conn 非 null 即成功，否则 error 是可直接返回的失败结果 */
    private static final class Opened {
        final Connection conn;
        final ToolResult error;
        Opened(Connection conn, ToolResult error) { this.conn = conn; this.error = error; }
    }

    /** 显式 Class.forName（不依赖 shade 后的服务发现）+ 每次新建连接 */
    private Opened open(DataSourceConfig ds, DbType type, DbQueryControl control) {
        Connection c = null;
        try {
            control.check();
            c = connect(ds, type);
            control.attach(c);
            c.setAutoCommit(true);
            try {
                c.setReadOnly(true);
            } catch (Throwable ignored) {
                // 部分驱动/数据库不强制 readOnly；写操作已由 SqlGuard 拦在前面
            }
            return new Opened(c, null);
        } catch (ClassNotFoundException | LinkageError e) {
            close(c);
            return new Opened(null, ToolResult.error(type.displayName() + " 驱动加载失败（请检查 jar 完整性）"));
        } catch (SQLException | RuntimeException e) {
            close(c);
            return new Opened(null, ToolResult.error("连接数据源「" + ds.name + "」失败: "
                    + firstLine(DbSecrets.redact(e.getMessage(), ds)) + "（请检查 URL/账号/网络/白名单）"));
        }
    }

    /** 执行只读 SQL，结果渲染成 Markdown 表格；单元格被截断（超 cellMax）或总长超 30000 时，
     *  整表**未截断全量**落盘并附路径；超过 {@link DbExport#EXPORT_MIN_CHARS} 字符的大字段
     *  另存为**原样文件**（未转义、未截断）并在表尾给出导出清单；full=true 时单元格截断线放宽到 FULL_CELL_MAX */
    public ToolResult query(DataSourceConfig ds, DbType type, String sql, boolean full) {
        return query(ds.snapshot(), type, sql, full, synchronousControl());
    }

    /** 异步适配层必须调用此真实 JDBC 路径；控制句柄只属于本次执行。 */
    ToolResult query(DataSourceConfig ds, DbType type, String sql, boolean full, DbQueryControl control) {
        String why = SqlGuard.check(sql);
        if (why != null) return ToolResult.errorWithoutSideEffects(why);
        if (!begin(control)) return ToolResult.errorWithoutSideEffects("数据库执行器已关闭");
        Opened opened = open(ds, type, control);
        if (opened.conn == null) { end(control); return opened.error; }
        long t0 = System.currentTimeMillis();
        Statement st = null;
        ResultSet rs = null;
        try {
            st = opened.conn.createStatement();
            control.attach(st);
            st.setMaxRows(MAX_ROWS + 1);
            st.setQueryTimeout(ds.effectiveQueryTimeoutSeconds());
            control.check();
            rs = st.executeQuery(sql);
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<String> names = new ArrayList<String>();
            for (int i = 1; i <= cols; i++) {
                String label = md.getColumnLabel(i);
                names.add(label == null || label.isEmpty() ? md.getColumnName(i) : label);
            }
            int cellMax = full ? FULL_CELL_MAX : MarkdownTable.CELL_MAX;
            List<List<String>> rows = new ArrayList<List<String>>();      // 展示（单元格可能含截断标注）
            List<List<String>> fullRows = new ArrayList<List<String>>();  // 未截断全量（落盘口径）
            List<DbExport.Entry> pending = new ArrayList<DbExport.Entry>();  // 待原样导出的大字段
            boolean truncated = false;
            while (rs.next()) {
                control.check();
                if (rows.size() == MAX_ROWS) { truncated = true; break; }
                List<String> row = new ArrayList<String>();
                List<String> fullRow = new ArrayList<String>();
                int rowNum = rows.size() + 1;   // 1-based，与清单/文件名一致
                for (int i = 1; i <= cols; i++) {
                    control.check();
                    Object v = rs.getObject(i);
                    // 一次转换出字符串口径（CLOB 显式读内容）；null 保持 null，既有「NULL」文案逐字符不变
                    String raw = v == null ? null : DbExport.stringOf(v);
                    boolean exported = DbExport.shouldExport(raw);
                    row.add(MarkdownTable.cell(raw, cellMax, exported));
                    fullRow.add(MarkdownTable.escape(raw));
                    if (exported) {
                        pending.add(new DbExport.Entry(rowNum, i, names.get(i - 1), raw, null));
                    }
                }
                rows.add(row);
                fullRows.add(fullRow);
            }
            long elapsed = System.currentTimeMillis() - t0;
            String head = MarkdownTable.header(ds.name, elapsed, rows.size(), truncated, MAX_ROWS);
            if (rows.isEmpty()) return ToolResult.success(head + "\n\n查询成功，0 行结果");
            control.check();
            String exportHint = DbExport.dump(control.outputDir, pending);   // 大字段原样导出（失败自动降级）
            return ToolResult.success(MarkdownTable.fit(
                    head + "\n\n" + MarkdownTable.render(names, rows),
                    head + "\n\n" + MarkdownTable.render(names, fullRows), control.outputDir, exportHint));
        } catch (SQLException e) {
            return ToolResult.error(DbSecrets.redact(sqlError(e, ds.effectiveQueryTimeoutSeconds()), ds));
        } finally {
            close(rs);
            close(st);
            close(opened.conn);
            end(control);
        }
    }

    /** 列出表与视图（走 DatabaseMetaData，不拼 SQL） */
    public ToolResult listTables(DataSourceConfig ds, DbType type) {
        DbQueryControl control = synchronousControl();
        if (!begin(control)) return ToolResult.errorWithoutSideEffects("数据库执行器已关闭");
        Opened opened = open(ds, type, control);
        if (opened.conn == null) { end(control); return opened.error; }
        long t0 = System.currentTimeMillis();
        ResultSet rs = null;
        try {
            DatabaseMetaData meta = opened.conn.getMetaData();
            rs = meta.getTables(catalogOf(opened.conn, type), schemaOf(meta, type), "%",
                    new String[]{"TABLE", "VIEW"});
            attachMetadataStatement(control, rs, ds.effectiveQueryTimeoutSeconds());
            List<List<String>> rows = new ArrayList<List<String>>();
            boolean truncated = false;
            while (rs.next()) {
                control.check();
                if (rows.size() == SCHEMA_MAX_ROWS) { truncated = true; break; }
                rows.add(Arrays.asList(
                        MarkdownTable.cell(rs.getString("TABLE_NAME")),
                        MarkdownTable.cell(rs.getString("TABLE_TYPE")),
                        MarkdownTable.cell(remark(rs))));
            }
            long elapsed = System.currentTimeMillis() - t0;
            String head = MarkdownTable.header(ds.name, elapsed, rows.size(), truncated, SCHEMA_MAX_ROWS);
            if (rows.isEmpty()) return ToolResult.success(head + "\n\n未发现任何表或视图");
            StringBuilder sb = new StringBuilder(head).append("\n\n").append(MarkdownTable.render(
                    Arrays.asList("表名", "类型", "注释"), rows));
            if (truncated) {
                sb.append("\n\n…（已达 ").append(SCHEMA_MAX_ROWS).append(" 行上限，可用 query 精确检索）");
            }
            return ToolResult.success(MarkdownTable.fit(sb.toString(), tmpDir));
        } catch (SQLException e) {
            return ToolResult.error(DbSecrets.redact(sqlError(e, ds.effectiveQueryTimeoutSeconds()), ds));
        } finally {
            close(rs);
            close(opened.conn);
            end(control);
        }
    }

    /** 查看表字段（走 DatabaseMetaData；tablePattern 含 % / _ 时按模式匹配多表） */
    public ToolResult describe(DataSourceConfig ds, DbType type, String tablePattern) {
        DbQueryControl control = synchronousControl();
        if (!begin(control)) return ToolResult.errorWithoutSideEffects("数据库执行器已关闭");
        Opened opened = open(ds, type, control);
        if (opened.conn == null) { end(control); return opened.error; }
        long t0 = System.currentTimeMillis();
        ResultSet rs = null;
        try {
            DatabaseMetaData meta = opened.conn.getMetaData();
            rs = meta.getColumns(catalogOf(opened.conn, type), schemaOf(meta, type), tablePattern, "%");
            attachMetadataStatement(control, rs, ds.effectiveQueryTimeoutSeconds());
            List<List<String>> rows = new ArrayList<List<String>>();
            boolean truncated = false;
            while (rs.next()) {
                control.check();
                if (rows.size() == MAX_ROWS) { truncated = true; break; }
                rows.add(Arrays.asList(
                        MarkdownTable.cell(rs.getString("TABLE_NAME")),
                        MarkdownTable.cell(rs.getString("COLUMN_NAME")),
                        MarkdownTable.cell(rs.getString("TYPE_NAME")),
                        MarkdownTable.cell(sizeText(rs)),
                        MarkdownTable.cell(nullableText(rs.getInt("NULLABLE"))),
                        MarkdownTable.cell(defaultText(rs)),
                        MarkdownTable.cell(remark(rs))));
            }
            long elapsed = System.currentTimeMillis() - t0;
            if (rows.isEmpty()) return ToolResult.error("未找到表: " + tablePattern);
            String head = MarkdownTable.header(ds.name, elapsed, rows.size(), truncated, MAX_ROWS);
            StringBuilder sb = new StringBuilder(head).append("\n\n").append(MarkdownTable.render(
                    Arrays.asList("表名", "列名", "类型", "长度", "可空", "默认值", "注释"), rows));
            if (truncated) {
                sb.append("\n\n…（已达 ").append(MAX_ROWS).append(" 行上限，可用更精确的表名）");
            }
            return ToolResult.success(MarkdownTable.fit(sb.toString(), tmpDir));
        } catch (SQLException e) {
            return ToolResult.error(DbSecrets.redact(sqlError(e, ds.effectiveQueryTimeoutSeconds()), ds));
        } finally {
            close(rs);
            close(opened.conn);
            end(control);
        }
    }

    /** 元数据语句可用时也接受源超时；不提供 Statement 的老驱动保持兼容。 */
    private static void attachMetadataStatement(DbQueryControl control, ResultSet rs, int timeoutSeconds) throws SQLException {
        Statement statement = null;
        try { statement = rs.getStatement(); }
        catch (SQLException ignored) { /* 部分 metadata ResultSet 不提供语句，保留同步兼容性。 */ }
        control.check();
        if (statement != null) {
            control.attach(statement);
            try { statement.setQueryTimeout(timeoutSeconds); }
            catch (java.sql.SQLFeatureNotSupportedException ignored) { }
        }
    }

    /** MySQL 用当前库作 catalog；其余不限定（Oracle 靠 schema 限定） */
    private static String catalogOf(Connection c, DbType type) throws SQLException {
        return type == DbType.MYSQL ? c.getCatalog() : null;
    }

    /** Oracle 不限定 schema 会拉出 SYS/SYSTEM 海量对象，用当前登录用户限定 */
    private static String schemaOf(DatabaseMetaData meta, DbType type) throws SQLException {
        return type == DbType.ORACLE ? meta.getUserName() : null;
    }

    /** REMARKS 在部分驱动/未开 useInformationSchema 时取不到，按空处理 */
    private static String remark(ResultSet rs) {
        try {
            String r = rs.getString("REMARKS");
            return r == null ? "" : r;
        } catch (SQLException e) {
            return "";
        }
    }

    private static String defaultText(ResultSet rs) {
        try {
            String d = rs.getString("COLUMN_DEF");
            return d == null ? "" : d;
        } catch (SQLException e) {
            return "";
        }
    }

    /** 长度列：COLUMN_SIZE；小数位有效时附精度（如 10,2） */
    private static String sizeText(ResultSet rs) throws SQLException {
        int size = rs.getInt("COLUMN_SIZE");
        int digits = rs.getInt("DECIMAL_DIGITS");
        if (rs.wasNull()) return String.valueOf(size);
        return digits > 0 ? size + "," + digits : String.valueOf(size);
    }

    private static String nullableText(int nullable) {
        if (nullable == DatabaseMetaData.columnNoNulls) return "否";
        if (nullable == DatabaseMetaData.columnNullable) return "是";
        return "未知";
    }

    private static String sqlError(SQLException e, int timeoutSeconds) {
        if (isTimeout(e)) {
            return "驱动报告查询超时/取消（数据源上限 " + timeoutSeconds
                    + "s）；不据此保证服务端物理停止，请缩小查询范围";
        }
        return "查询失败 [SQLState " + e.getSQLState() + " / 错误码 " + e.getErrorCode() + "]: "
                + firstLine(e.getMessage());
    }

    /** 超时判定：PG 57014 / Oracle 01013 / MySQL 70100，兜底看 message 里的 timeout 字样 */
    static boolean isTimeout(SQLException e) {
        String state = e.getSQLState();
        if ("57014".equals(state) || "01013".equals(state) || "70100".equals(state)) return true;
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("timeout") || msg.contains("query execution was interrupted");
    }

    /** 驱动异常首行（多行堆栈式消息只取首行），超 200 字符截断；DbPlugin 测试连接复用同一口径 */
    public static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        String first = (i < 0 ? s : s.substring(0, i)).trim();
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }

    private static void close(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) { }
    }

    /** 非阻塞关闭：取消活语句并异步尝试释放连接；驱动不响应时只保留有界 daemon。 */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (DbQueryControl control : active) {
            control.cancel();
            control.closeConnection();
        }
        cancellations.shutdown();
    }
}
