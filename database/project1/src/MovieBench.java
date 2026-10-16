/*
 * ============================================================================
 *  MovieBench.java   --   DBMS Performance Evaluation, single-file benchmark
 * ============================================================================
 *
 *  用途 / PURPOSE
 *  ---------------------------------------------------------------------------
 *  同一套逻辑操作，分别在三种"存储"上执行，并打印机器可读的结果行，方便直接
 *  粘贴进报告表格：
 *
 *      target = file   磁盘上的纯文本文件（TSV），以及手工构建的磁盘布局
 *      target = pg     PostgreSQL
 *      target = og     openGauss
 *
 *  每个测量输出一行，格式固定（前两个字段就是 <模式> 和 <目标>）：
 *
 *      RESULT|<mode>|<target>|<scope>|<metric>|<value>|<unit>|<hits or -1>
 *
 *  例：
 *      RESULT|search|pg|imdb.movies|median_ms|123.456|ms|2067
 *      RESULT|update|file|imdb.people|median_ms|812.300|ms|1287
 *      RESULT|batch|pg|size=1000|tps|48231.500|rows/s|200000
 *
 *  此外每个模式启动时会先打一行**实验编号标签**，把结果行和报告里的 E 编号对上：
 *      RESULT|update|file|-|exp_id|E2b|label|-1
 *  （这一行的 value 字段是标签而不是数字，其余各行的 value 都是数字）
 *
 *  编译 / COMPILE （JDK 8 及以上，JDBC 驱动只在运行时需要，编译期不需要）
 *  ---------------------------------------------------------------------------
 *      javac -encoding UTF-8 MovieBench.java
 *
 *  运行 / RUN
 *  ---------------------------------------------------------------------------
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar;opengauss-jdbc.jar" ^
 *           MovieBench <目标> <模式> [参数...]
 *
 *      Windows 的 classpath 分隔符是分号，Linux/macOS 是冒号。
 *
 *  命令行接口 / CLI （与 OPERATIONS.md 严格一致）
 *  ---------------------------------------------------------------------------
 *      目标 target : file | pg | og
 *
 *      search <词> [lang]          关键词检索。lang 默认 en，检索 imdb.movies；
 *                                  lang=zh 时检索 imdb.alt_titles 的中文译名。
 *                                       MovieBench pg   search love
 *                                       MovieBench file search 爱 zh
 *
 *      update <旧串> <新串>        字符替换更新（老师指定的更新实验）。
 *                                  长度不同 -> 变长更新，文件侧必须整体重写；
 *                                  长度相同 -> 等长对照，文件侧原地覆写。
 *                                       MovieBench pg   update T Ttt     (变长)
 *                                       MovieBench file update T X       (等长)
 *
 *      point <id>                  按主键 movieid 点查。
 *                                  file 侧同时测三种实现：顺序扫描 / 内存散列 /
 *                                  排序+二分+seek；pg/og 侧测主键索引。
 *
 *      join <年> <类别>            三表连接 movies JOIN credits JOIN people，
 *                                  统计"起始年份之后、指定类别"的作品数 Top20。
 *                                  类别 D=导演，A=演员。
 *                                       MovieBench pg join 2000 D
 *
 *      agg <维度> [--table 表名]   聚合统计。维度取值 year | airline | cate
 *                                      year    -> 默认表 imdb.movies   （E5）
 *                                      airline -> 默认表 flights.flights（E8）
 *                                      cate    -> 默认表 taobao.ad_clicks（E8）
 *                                  指定 --table 时用它替换默认表名，用于 E7 part 2 的
 *                                  行存 / 列存对比（openGauss 侧会传 flights_col 等）：
 *                                       MovieBench og agg airline --table flights_col
 *
 *      conc <线程数> [秒数] [词]   并发检索。线程数 8 / 32 / 128；秒数默认 10。
 *                                  输出总 QPS、P95、平均延迟、错误数。
 *
 *      tx kill                     中断写入：开启事务写入后**不提交**，打印本进程
 *                                  PID，然后挂起等待外部强杀。用于演示原子性。
 *                                  file 侧：重写 TSV 到 30% 后挂起，被强杀后留下
 *                                  一个残缺的新文件。
 *                                  pg/og 侧：UPDATE 执行完但不 COMMIT，进程被杀
 *                                  后数据库自动回滚，数据完好。
 *                                       MovieBench pg tx kill
 *                                  然后在另一个窗口执行：
 *                                       taskkill /F /PID <程序打印出来的 PID>
 *
 *      bloat <轮数>                连续 N 轮全表更新，每轮记录 pg_relation_size()
 *                                  和一次检索耗时，观察表膨胀。
 *
 *      layout csv|fixed|mmap|invidx [词]
 *                                  文件侧四种存储布局的构建与检索（只对 file 有效）。
 *                                      csv    普通 TSV 顺序扫描
 *                                      fixed  定长二进制 256 字节/条 + seek 直接定位
 *                                      mmap   FileChannel.map() 内存映射
 *                                      invidx 反转索引（词 -> 电影 id 列表）
 *
 *      batch <批量大小> [总条数]   批量写入与事务大小（总条数默认 200000）。
 *                                  pg/og 侧：PreparedStatement.addBatch()/executeBatch()，
 *                                  配合 setAutoCommit(false) 每 N 条 commit()，
 *                                  于是"事务大小 = 批量大小"；
 *                                  file 侧：追加写 + FileDescriptor.sync() 每 N 条落盘一次。
 *                                  插入目标是一张**专用测试表**，不碰任何实验数据表。
 *                                       MovieBench pg   batch 1
 *                                       MovieBench pg   batch 1000
 *                                       MovieBench file batch 1000 200000
 *
 *  模式清单（共 10 个）/ MODES
 *  ---------------------------------------------------------------------------
 *      search   update   point   join   agg   conc   tx   bloat   layout   batch
 *
 *  实验编号对照 / EXPERIMENT NUMBERING
 *  ---------------------------------------------------------------------------
 *  只影响代码注释和 RESULT 里的 exp_id 标签行，不影响命令行。
 *
 *      E1  = search                     E2 / E2b = update（变长 / 等长）
 *      E3  = point                      E4  = join
 *      E5  = agg year                   E6  = conc
 *      E7  = layout                     E8  = agg airline | agg cate
 *      E9  = batch                      E10 = bloat
 *      E11 = tx kill
 *
 *  连接与路径参数 / CONFIGURATION
 *  ---------------------------------------------------------------------------
 *  一律通过**环境变量**读取，缺省时回落到代码里的默认常量，并在启动时把最终使用
 *  的配置整行打印出来，便于排查：
 *
 *      MOVIEBENCH_PG_URL / MOVIEBENCH_PG_USER / MOVIEBENCH_PG_PWD
 *      MOVIEBENCH_OG_URL / MOVIEBENCH_OG_USER / MOVIEBENCH_OG_PWD
 *      MOVIEBENCH_CSV           TSV 根目录，默认 E:\DBMS-Project-Data\csv
 *
 *  另外保留 -D 系统属性作为第二层覆盖（可选，方便临时试）：
 *      -Dpg.driver / -Dog.driver     指定 JDBC 驱动类
 *      -Dwarmup=3 -Drepeat=5         预热 / 正式测量次数
 *      -Dfile.limit=0                文件扫描最多处理多少行，0 = 全部
 *      -Dreset=1                     强制重建数据副本
 *      -Drebuild=1                   强制重建 layout 的磁盘缓存
 *
 *  数据 / DATA （制表符分隔，NULL 写作 \N）
 *  ---------------------------------------------------------------------------
 *      filmdb/movies.tsv     movieid,title,country,year_released,runtime     (9,204)
 *      filmdb/people.tsv     peopleid,first_name,surname,born,died,gender   (16,489)
 *      filmdb/credits.tsv    movieid,peopleid,credited_as                   (46,850)
 *      imdb/movies.tsv       同 filmdb.movies     (~11.7M)
 *      imdb/people.tsv       同 filmdb.people     (~14.5M)
 *      imdb/credits.tsv      3 列                 (3000 万行上限)
 *      imdb/alt_titles.tsv   titleid,movieid,title,region,language  (中港台片名)
 *      flights/flights.tsv   34 列，顺序与 init_pg.sql 中 flights.flights 一致
 *      taobao/taobao_ad.tsv  row_id + 21 列，顺序与 taobao.ad_clicks 一致
 * ============================================================================
 */

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class MovieBench {

    // ========================================================================
    // 0. 配置：环境变量优先，其次 -D 属性，最后是代码里的默认常量
    // ========================================================================

    /** 记录哪些环境变量真的被用上了，启动时打印出来方便排查。 */
    static final Set<String> ENV_USED = new HashSet<String>();

    static String envOr(String name, String dflt) {
        String v = System.getenv(name);
        if (v != null && v.trim().length() > 0) {
            ENV_USED.add(name);
            return v.trim();
        }
        return dflt;
    }

    /** TSV 数据根目录。 */
    static final String DATA_ROOT = envOr("MOVIEBENCH_CSV",
            System.getProperty("data.root", "E:/DBMS-Project-Data/csv"));
    /** 一次性工作目录（update 的重写产物、等长对照副本、被强杀的半成品文件）。 */
    static final String WORK_DIR = System.getProperty("work.dir", DATA_ROOT + "/_work");
    /** layout 模式的磁盘布局缓存目录。 */
    static final String LAYOUT_DIR = System.getProperty("layout.dir", DATA_ROOT + "/_layouts");

    // ---- PostgreSQL：环境变量 MOVIEBENCH_PG_* ----
    static final String PG_URL = envOr("MOVIEBENCH_PG_URL",
            System.getProperty("pg.url", "jdbc:postgresql://127.0.0.1:5432/bench"));
    static final String PG_USER = envOr("MOVIEBENCH_PG_USER",
            System.getProperty("pg.user", "postgres"));
    static final String PG_PWD = envOr("MOVIEBENCH_PG_PWD",
            System.getProperty("pg.pwd", "postgres"));
    /** 驱动类：PostgreSQL 用 org.postgresql.Driver。 */
    static final String PG_DRIVER = System.getProperty("pg.driver", "org.postgresql.Driver");

    // ---- openGauss：环境变量 MOVIEBENCH_OG_* ----
    static final String OG_URL = envOr("MOVIEBENCH_OG_URL",
            System.getProperty("og.url", "jdbc:opengauss://127.0.0.1:5432/bench"));
    static final String OG_USER = envOr("MOVIEBENCH_OG_USER",
            System.getProperty("og.user", "gaussdb"));
    static final String OG_PWD = envOr("MOVIEBENCH_OG_PWD",
            System.getProperty("og.pwd", "Gauss@123"));
    /**
     * 驱动类：openGauss 官方驱动是 org.opengauss.Driver。
     * 也可以用 PostgreSQL 驱动连 openGauss：
     *     -Dog.driver=org.postgresql.Driver -Dog.url=jdbc:postgresql://host:port/db
     */
    static final String OG_DRIVER = System.getProperty("og.driver", "org.opengauss.Driver");

    // ---- 运行时开关 ----
    /** 预热次数：3 次。 */
    static int WARMUP = Integer.parseInt(System.getProperty("warmup", "3"));
    /** 正式测量次数：5 次，取中位数。 */
    static int REPEAT = Integer.parseInt(System.getProperty("repeat", "5"));
    /** 文件扫描行数上限，0 = 全部（冒烟测试用）。 */
    static long FILE_LIMIT = Long.parseLong(System.getProperty("file.limit", "0"));

    // 表名常量，避免 SQL 里拼错
    static final String T_IMDB_MOVIES = "imdb.movies";
    static final String T_IMDB_PEOPLE = "imdb.people";
    static final String T_IMDB_CREDITS = "imdb.credits";
    static final String T_IMDB_ALT = "imdb.alt_titles";
    static final String T_FILM_MOVIES = "filmdb.movies";
    static final String T_FILM_PEOPLE = "filmdb.people";
    static final String T_FLIGHTS = "flights.flights";
    static final String T_TAOBAO = "taobao.ad_clicks";

    // ========================================================================
    // 1. 输出与计时基础设施
    // ========================================================================

    static void log(String s) {
        System.out.println(s);
    }

    static void hr(String title) {
        log("");
        log("==================== " + title + " ====================");
    }

    /**
     * 机器可读结果行。
     * 格式：RESULT|<模式>|<目标>|<scope>|<metric>|<value>|<unit>|<hits or -1>
     */
    static void res(String mode, String target, String scope, String metric,
                    double value, String unit, long hits) {
        System.out.printf(Locale.ROOT, "RESULT|%s|%s|%s|%s|%.3f|%s|%d%n",
                mode, target, scope, metric, value, unit, hits);
    }

    /**
     * 实验编号标签行：把结果和报告里的 E 编号对应起来。
     * 格式与普通结果行一致，只是 value 字段放的是标签而不是数字。
     *     RESULT|update|file|-|exp_id|E2b|label|-1
     */
    static void expId(String mode, String target, String label) {
        System.out.println("RESULT|" + mode + "|" + target + "|-|exp_id|" + label + "|label|-1");
    }

    /** 一个被测量的操作：返回命中/影响行数（没有这个概念时返回 -1）。 */
    interface Op {
        long call() throws Exception;
    }

    /** 一次实验的原始测量结果。 */
    static class Meas {
        final long[] ns;
        final long hits;

        Meas(long[] ns, long hits) {
            this.ns = ns;
            this.hits = hits;
        }

        private long[] sorted() {
            long[] c = ns.clone();
            Arrays.sort(c);
            return c;
        }

        double medianMs() {
            long[] c = sorted();
            return c.length == 0 ? 0 : c[c.length / 2] / 1e6;
        }

        double minMs() {
            long[] c = sorted();
            return c.length == 0 ? 0 : c[0] / 1e6;
        }

        double maxMs() {
            long[] c = sorted();
            return c.length == 0 ? 0 : c[c.length - 1] / 1e6;
        }

        double meanMs() {
            long s = 0;
            for (long v : ns) s += v;
            return ns.length == 0 ? 0 : s / (double) ns.length / 1e6;
        }
    }

    /** 预热 WARMUP 次（不计时），再正式测 REPEAT 次。 */
    static Meas measure(Op op) throws Exception {
        long hits = -1;
        for (int i = 0; i < WARMUP; i++) hits = op.call();
        long[] ns = new long[REPEAT];
        for (int i = 0; i < REPEAT; i++) {
            long t0 = System.nanoTime();
            hits = op.call();
            ns[i] = System.nanoTime() - t0;
        }
        return new Meas(ns, hits);
    }

    /** 每轮重置操作的平均耗时(ms)，由 measureWithReset 填充。 */
    static double resetAvgMs = 0;

    /**
     * 需要"每次测量前把数据恢复原状"的实验（update、等长对照）用这个。
     * reset 的耗时不计入被测操作，只单独输出。
     */
    static Meas measureWithReset(Op op, Op reset) throws Exception {
        long hits = -1;
        long resetNs = 0;
        for (int i = 0; i < WARMUP; i++) {
            reset.call();
            hits = op.call();
        }
        long[] ns = new long[REPEAT];
        for (int i = 0; i < REPEAT; i++) {
            long r0 = System.nanoTime();
            reset.call();
            resetNs += System.nanoTime() - r0;
            long t0 = System.nanoTime();
            hits = op.call();
            ns[i] = System.nanoTime() - t0;
        }
        resetAvgMs = resetNs / (double) REPEAT / 1e6;
        return new Meas(ns, hits);
    }

    /** 打印一次测量的标准指标组。 */
    static void emit(String mode, String target, String scope, Meas m) {
        res(mode, target, scope, "median_ms", m.medianMs(), "ms", m.hits);
        res(mode, target, scope, "min_ms", m.minMs(), "ms", m.hits);
        res(mode, target, scope, "max_ms", m.maxMs(), "ms", m.hits);
        res(mode, target, scope, "mean_ms", m.meanMs(), "ms", m.hits);
        res(mode, target, scope, "hits", m.hits, "count", m.hits);
    }

    /** 当前 JVM 进程号，tx kill 模式要打印出来让用户去杀。 */
    static long currentPid() {
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = n.indexOf('@');
            return Long.parseLong(at > 0 ? n.substring(0, at) : n);
        } catch (Throwable t) {
            return -1L;
        }
    }

    static String mask(String s) {
        if (s == null || s.length() == 0) return "(空)";
        if (s.length() <= 2) return "**";
        return s.charAt(0) + "***" + s.charAt(s.length() - 1);
    }

    /** 启动时把最终生效的连接串和路径全部打印出来，出问题好排查。 */
    static void printConfig() {
        log("---- 最终生效的配置 ----");
        log("  PostgreSQL : " + PG_URL + "    user=" + PG_USER + "  pwd=" + mask(PG_PWD)
                + "  driver=" + PG_DRIVER);
        log("  openGauss  : " + OG_URL + "    user=" + OG_USER + "  pwd=" + mask(OG_PWD)
                + "  driver=" + OG_DRIVER);
        log("  数据根目录 : " + DATA_ROOT);
        log("  预热/正式  : warmup=" + WARMUP + "  repeat=" + REPEAT
                + "  file.limit=" + FILE_LIMIT);
        log("  来自环境变量: " + (ENV_USED.isEmpty() ? "(无，全部使用代码默认值)" : ENV_USED.toString()));
        log("------------------------");
    }

    // ========================================================================
    // 2. 文件侧通用工具
    // ========================================================================

    static void mkdirs(String path) {
        new File(path).mkdirs();
    }

    static boolean sysFlag(String name) {
        String v = System.getProperty(name, "0");
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    static BufferedReader openReader(String path) throws IOException {
        return new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8), 1 << 20);
    }

    /**
     * 取出制表符分隔行里第 index 个字段。
     * 比 String.split 快很多——文件侧动辄要扫上千万行，split 会产生大量临时对象。
     */
    static String fieldAt(String line, int index) {
        int start = 0;
        for (int i = 0; i < index; i++) {
            int t = line.indexOf('\t', start);
            if (t < 0) return null;
            start = t + 1;
        }
        int end = line.indexOf('\t', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    /** 把 \N 当成 NULL。 */
    static boolean isNull(String v) {
        return v == null || "\\N".equals(v);
    }

    static long parseLongOr(String v, long dflt) {
        if (isNull(v)) return dflt;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static double parseDoubleOr(String v, double dflt) {
        if (isNull(v)) return dflt;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static String joinTabs(String[] a) {
        StringBuilder sb = new StringBuilder(a.length * 16);
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append('\t');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    /** 数一个字符串里某个字符出现几次：update 模式用它统计"到底改了多少个字符"。 */
    static long countChar(String s, char c) {
        if (s == null) return 0;
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    /** 把 UTF-8 字符串截断到不超过 max 字节，且不切断多字节字符。 */
    static byte[] fitUtf8(String s, int max) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) return b;
        int n = max;
        while (n > 0 && (b[n] & 0xC0) == 0x80) n--;
        byte[] r = new byte[n];
        System.arraycopy(b, 0, r, 0, n);
        return r;
    }

    /**
     * 逐行读文件，并且给出每一行在文件中的**起始字节偏移**。
     * point 模式的"排序 + 二分 + seek"和 layout fixed 都依赖精确偏移。
     */
    static class OffsetLineReader implements Closeable {
        private final InputStream in;
        private final byte[] buf = new byte[1 << 20];
        private int pos = 0, len = 0;
        private long base = 0;
        private final ByteArrayOutputStream cur = new ByteArrayOutputStream(512);
        private long nextLineStart = 0;
        long lineStart = 0;

        OffsetLineReader(String path) throws IOException {
            this.in = new BufferedInputStream(new FileInputStream(path), 1 << 20);
        }

        String readLine() throws IOException {
            long start = nextLineStart;
            cur.reset();
            boolean any = false;
            while (true) {
                if (pos >= len) {
                    base += len;
                    len = in.read(buf);
                    pos = 0;
                    if (len <= 0) {
                        len = 0;
                        if (!any) return null;
                        break;
                    }
                }
                byte b = buf[pos++];
                any = true;
                if (b == '\n') break;
                if (b == '\r') continue;
                cur.write(b);
            }
            lineStart = start;
            nextLineStart = base + pos;
            return new String(cur.toByteArray(), StandardCharsets.UTF_8);
        }

        public void close() throws IOException {
            in.close();
        }
    }

    // ------------------------------------------------------------------
    // 2.1 两个自己写的紧凑散列表
    //     用 long[] 当键、数组当桶，比 HashMap<Long,String> 省一半以上内存：
    //     join 模式要在内存里装下 1450 万个人名，用 JDK 的 HashMap 很容易 OOM。
    // ------------------------------------------------------------------

    static final long EMPTY_KEY = Long.MIN_VALUE;

    static int mix(long k) {
        return (int) ((k * 0x9E3779B97F4A7C15L) >>> 40);
    }

    /** 开放寻址的 long -> String 映射。 */
    static class LongStrMap {
        long[] keys;
        String[] vals;
        int mask;
        int size;

        LongStrMap(int expected) {
            int n = 1 << 4;
            while (n < expected * 2L) n <<= 1;
            keys = new long[n];
            vals = new String[n];
            Arrays.fill(keys, EMPTY_KEY);
            mask = n - 1;
        }

        void put(long k, String v) {
            int i = mix(k) & mask;
            while (keys[i] != EMPTY_KEY) {
                if (keys[i] == k) {
                    vals[i] = v;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = k;
            vals[i] = v;
            size++;
        }

        String get(long k) {
            int i = mix(k) & mask;
            while (keys[i] != EMPTY_KEY) {
                if (keys[i] == k) return vals[i];
                i = (i + 1) & mask;
            }
            return null;
        }
    }

    /** 开放寻址的 long -> int 计数器，支持自动扩容。 */
    static class LongIntMap {
        long[] keys;
        int[] vals;
        int mask;
        int size;
        int limit;

        LongIntMap(int expected) {
            int n = 1 << 4;
            while (n < expected * 2L) n <<= 1;
            alloc(n);
        }

        private void alloc(int n) {
            keys = new long[n];
            vals = new int[n];
            Arrays.fill(keys, EMPTY_KEY);
            mask = n - 1;
            limit = (int) (n * 0.7);
        }

        void add(long k, int delta) {
            if (size >= limit) grow();
            int i = mix(k) & mask;
            while (keys[i] != EMPTY_KEY) {
                if (keys[i] == k) {
                    vals[i] += delta;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = k;
            vals[i] = delta;
            size++;
        }

        int get(long k) {
            int i = mix(k) & mask;
            while (keys[i] != EMPTY_KEY) {
                if (keys[i] == k) return vals[i];
                i = (i + 1) & mask;
            }
            return 0;
        }

        private void grow() {
            long[] ok = keys;
            int[] ov = vals;
            int on = keys.length;
            alloc(on << 1);
            size = 0;
            for (int i = 0; i < on; i++) {
                if (ok[i] != EMPTY_KEY) add(ok[i], ov[i]);
            }
        }
    }

    /** 可增长的 long 列表：conc 模式记录每一次请求的延迟。 */
    static class LongList {
        long[] a = new long[1 << 12];
        int n = 0;

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n << 1);
            a[n++] = v;
        }

        int size() {
            return n;
        }

        long[] sortedArray() {
            long[] r = Arrays.copyOf(a, n);
            Arrays.sort(r);
            return r;
        }
    }

    /** 最近秩百分位（输入必须已排序），返回毫秒。 */
    static double percentileMs(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(p * sorted.length) - 1;
        if (i < 0) i = 0;
        if (i >= sorted.length) i = sorted.length - 1;
        return sorted[i] / 1e6;
    }

    // ========================================================================
    // 3. 数据库侧通用工具
    // ========================================================================

    static class DbCfg {
        final String key, url, user, pwd, driver;

        DbCfg(String key, String url, String user, String pwd, String driver) {
            this.key = key;
            this.url = url;
            this.user = user;
            this.pwd = pwd;
            this.driver = driver;
        }
    }

    static DbCfg cfgOf(String target) {
        if ("pg".equals(target)) return new DbCfg("pg", PG_URL, PG_USER, PG_PWD, PG_DRIVER);
        if ("og".equals(target)) return new DbCfg("og", OG_URL, OG_USER, OG_PWD, OG_DRIVER);
        throw new IllegalArgumentException("未知目标: " + target + "（应为 file / pg / og）");
    }

    static Connection connect(DbCfg c) throws Exception {
        try {
            Class.forName(c.driver);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "找不到 JDBC 驱动类 " + c.driver + "，请把它加到 classpath，"
                            + "或用 -D" + c.key + ".driver=... 指定。", e);
        }
        Connection con = DriverManager.getConnection(c.url, c.user, c.pwd);
        con.setAutoCommit(true);
        return con;
    }

    static void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    static long queryLong(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    static boolean tableExists(Connection con, String schema, String table) throws SQLException {
        try (ResultSet rs = con.getMetaData().getTables(null, schema, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /** SQL 字符串字面量转义。 */
    static String q(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** 把用户输入的关键词变成 LIKE 模式，转义 % _ \ 三个特殊字符。 */
    static String likePattern(String word) {
        StringBuilder sb = new StringBuilder("%");
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '%' || c == '_' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    /**
     * 极简连接池：conc 模式用。没有引第三方库，就是一个阻塞队列。
     * 连接数取 min(线程数, 64)，因为 PostgreSQL 默认 max_connections=100，
     * 直接按 128 开连接会被拒绝。
     */
    static class SimplePool {
        final BlockingQueue<Connection> queue = new LinkedBlockingQueue<Connection>();
        final List<Connection> all = new ArrayList<Connection>();

        SimplePool(DbCfg cfg, int size) throws Exception {
            for (int i = 0; i < size; i++) {
                Connection c = connect(cfg);
                queue.add(c);
                all.add(c);
            }
        }

        Connection borrow() throws InterruptedException {
            return queue.take();
        }

        void giveBack(Connection c) {
            queue.offer(c);
        }

        void close() {
            for (Connection c : all) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // 关闭失败不影响实验结论
                }
            }
        }
    }

    // ========================================================================
    // 4. 数据文件路径
    // ========================================================================

    static String moviesPath(String scope) {
        return "filmdb".equals(scope) ? DATA_ROOT + "/filmdb/movies.tsv" : DATA_ROOT + "/imdb/movies.tsv";
    }

    static String peoplePath(String scope) {
        return "filmdb".equals(scope) ? DATA_ROOT + "/filmdb/people.tsv" : DATA_ROOT + "/imdb/people.tsv";
    }

    static String creditsPath(String scope) {
        return "filmdb".equals(scope) ? DATA_ROOT + "/filmdb/credits.tsv" : DATA_ROOT + "/imdb/credits.tsv";
    }

    static boolean requireFile(String path) {
        if (new File(path).exists()) return true;
        log("!! 数据文件不存在，跳过: " + path);
        log("!! 请先运行数据预处理脚本，或用 MOVIEBENCH_CSV 指定正确的根目录。");
        return false;
    }

    // ========================================================================
    // 5. search —— 关键词检索（老师指定的检索实验）
    // ========================================================================

    static void search(String target, String word, String lang) throws Exception {
        boolean zh = "zh".equalsIgnoreCase(lang);
        hr("search 关键词检索 [E1]  word=[" + word + "]  lang=" + (zh ? "zh" : "en"));
        expId("search", target, "E1");
        if ("file".equals(target)) searchFile(word, zh);
        else searchDb(target, word, zh);
    }

    static void searchFile(final String word, boolean zh) throws Exception {
        final String path = zh ? DATA_ROOT + "/imdb/alt_titles.tsv" : DATA_ROOT + "/imdb/movies.tsv";
        final int idx = zh ? 2 : 1;
        final String scale = zh ? "imdb.alt_titles" : "imdb.movies";
        if (!requireFile(path)) return;
        log("file 侧：逐行流式扫描 " + path + "，取第 " + idx + " 列做 String.contains 判断");

        Meas m = measure(new Op() {
            public long call() throws Exception {
                long hits = 0;
                long n = 0;
                BufferedReader r = openReader(path);
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (FILE_LIMIT > 0 && ++n > FILE_LIMIT) break;
                        String f = fieldAt(line, idx);
                        if (f != null && f.contains(word)) hits++;
                    }
                } finally {
                    r.close();
                }
                return hits;
            }
        });
        emit("search", "file", scale, m);
        log(String.format(Locale.ROOT, "file 侧：中位耗时 %.3f ms，命中 %,d 行", m.medianMs(), m.hits));
    }

    static void searchDb(String target, String word, boolean zh) throws Exception {
        final String table = zh ? T_IMDB_ALT : T_IMDB_MOVIES;
        final String scale = zh ? "imdb.alt_titles" : "imdb.movies";
        final String sql = "SELECT count(*) FROM " + table + " WHERE title LIKE " + q(likePattern(word));
        log("DB 侧 SQL: " + sql);

        DbCfg cfg = cfgOf(target);
        Connection con = connect(cfg);
        try {
            final Statement[] st = new Statement[1];
            final ResultSet[] rs = new ResultSet[1];
            Meas m = measure(new Op() {
                public long call() throws Exception {
                    if (rs[0] != null) {
                        rs[0].close();
                        st[0].close();
                    }
                    st[0] = con.createStatement();
                    rs[0] = st[0].executeQuery(sql);
                    return rs[0].next() ? rs[0].getLong(1) : -1L;
                }
            });
            if (rs[0] != null) {
                rs[0].close();
                st[0].close();
            }
            emit("search", target, scale, m);
            log(String.format(Locale.ROOT, "%s 侧：中位耗时 %.3f ms，命中 %,d 行",
                    target, m.medianMs(), m.hits));
        } finally {
            con.close();
        }
    }

    // ========================================================================
    // 6. update —— 字符替换更新（老师指定的更新实验）
    // ========================================================================

    /** 上一次文件侧更新替换掉的字符数，供日志使用。 */
    static long changedCharsOut = 0;

    static void update(String target, String oldStr, String newStr) throws Exception {
        // 长度相同 -> 等长对照，文件侧可以原地覆写；
        // 长度不同 -> 变长更新，文件侧无法原地完成，必须整体重写。
        boolean equal = oldStr.length() == newStr.length();
        hr("update 字符替换 [E2" + (equal ? "b" : "") + "]  '" + oldStr + "' -> '" + newStr + "'   "
                + (equal ? "【等长】文件侧可原地覆写" : "【变长】文件侧必须整体重写"));
        expId("update", target, equal ? "E2b" : "E2");
        if ("file".equals(target)) {
            if (equal) updateFileInPlace(oldStr, newStr);
            else updateFileRewrite(oldStr, newStr);
        } else {
            updateDb(target, oldStr, newStr);
        }
    }

    /** 变长更新：读原文件 -> 替换 -> 写**新文件**。这是文件系统的死穴。 */
    static void updateFileRewrite(final String oldStr, final String newStr) throws Exception {
        final String src = peoplePath("imdb");
        if (!requireFile(src)) return;
        mkdirs(WORK_DIR);
        final String dst = WORK_DIR + "/people_updated.tsv";
        log("file 侧：读 " + src + " -> 替换 -> 写新文件 " + dst);

        Meas m = measure(new Op() {
            public long call() throws Exception {
                long fields = 0;
                long chars = 0;
                BufferedReader r = openReader(src);
                BufferedWriter w = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(dst), StandardCharsets.UTF_8), 1 << 20);
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] c = line.split("\t", -1);
                        for (int k = 1; k <= 2 && k < c.length; k++) {
                            if (!"\\N".equals(c[k]) && c[k].indexOf(oldStr) >= 0) {
                                chars += countOccurrences(c[k], oldStr);
                                c[k] = c[k].replace(oldStr, newStr);
                                fields++;
                            }
                        }
                        w.write(joinTabs(c));
                        w.write('\n');
                    }
                } finally {
                    r.close();
                    w.close();
                }
                changedCharsOut = chars;
                return fields;
            }
        });
        emit("update", "file", "imdb.people", m);
        long bytes = new File(dst).length();
        res("update", "file", "imdb.people", "output_bytes", bytes, "bytes", m.hits);
        res("update", "file", "imdb.people", "chars_replaced", changedCharsOut, "count", m.hits);
        log(String.format(Locale.ROOT,
                "file 侧：中位耗时 %.3f ms，改写字段 %,d 个（替换 %,d 处），新文件 %,d 字节",
                m.medianMs(), m.hits, changedCharsOut, bytes));
    }

    /** 统计 substr 在 s 中不重叠出现的次数。 */
    static long countOccurrences(String s, String sub) {
        if (s == null || sub == null || sub.length() == 0) return 0;
        long n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /** 保证工作副本存在（一次性拷贝，不计时）。 */
    static void ensureCopy(String src, String dst) throws IOException {
        File d = new File(dst);
        File s = new File(src);
        if (d.exists() && !sysFlag("reset") && d.length() == s.length()) return;
        mkdirs(new File(dst).getParent());
        log("    复制原始数据 " + src + " -> " + dst + "（一次性，不计时）");
        Files.copy(Paths.get(src), Paths.get(dst), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 等长对照：长度不变，可以用 RandomAccessFile 在**原地**覆写，完全不必重写文件。
     * 这组实验唯一的目的，就是把"记录变长"这个变量单独隔离出来，
     * 用来回答"文件侧到底慢在写得太多，还是慢在记录变长"。
     */
    static void updateFileInPlace(final String oldStr, final String newStr) throws Exception {
        final String src = peoplePath("imdb");
        if (!requireFile(src)) return;
        final String scratch = WORK_DIR + "/people_eq.tsv";
        ensureCopy(src, scratch);
        final byte oldB = (byte) oldStr.charAt(0);
        final byte newB = (byte) newStr.charAt(0);
        log("file 侧：内存映射（FileChannel.map）原地覆写 " + scratch);
        log("每轮测量前把原始文件重新拷回（重置耗时单独输出，不计入覆写时间）。");

        Meas m = measureWithReset(new Op() {
            public long call() throws Exception {
                long chars = 0, fields = 0;
                final long CHUNK = 64L << 20;   // 一次映射 64 MB，避免整文件一次性映射
                int col = 0;                    // 0=peopleid，1=first_name，2=surname，3=born，4=died，5=gender
                boolean hit1 = false, hit2 = false;
                try (FileChannel ch = FileChannel.open(Paths.get(scratch),
                        StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    final long size = ch.size();
                    for (long pos = 0; pos < size; pos += CHUNK) {
                        long len = Math.min(CHUNK, size - pos);
                        MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_WRITE, pos, len);
                        int n = (int) len;
                        for (int i = 0; i < n; i++) {
                            byte b = mb.get(i);
                            if (b == '\t') {
                                col++;
                                continue;
                            }
                            if (b == '\n') {
                                if (hit1) fields++;
                                if (hit2) fields++;
                                hit1 = hit2 = false;
                                col = 0;
                                continue;
                            }
                            // 'T' 只可能出现在第 2、3 列（first_name / surname）
                            if (b == oldB && (col == 1 || col == 2)) {
                                mb.put(i, newB);
                                chars++;
                                if (col == 1) hit1 = true; else hit2 = true;
                            }
                        }
                    }
                    if (hit1) fields++;
                    if (hit2) fields++;
                }
                changedCharsOut = chars;
                return fields;
            }
        }, new Op() {
            public long call() throws Exception {
                Files.copy(Paths.get(src), Paths.get(scratch), StandardCopyOption.REPLACE_EXISTING);
                return -1;
            }
        });
        emit("update", "file", "imdb.people", m);
        res("update", "file", "imdb.people", "file_bytes", new File(scratch).length(), "bytes", m.hits);
        res("update", "file", "imdb.people", "chars_replaced", changedCharsOut, "count", m.hits);
        res("update", "file", "imdb.people", "reset_ms", resetAvgMs, "ms", -1);
        log(String.format(Locale.ROOT,
                "file 侧：中位耗时 %.3f ms，原地覆写 %,d 个字段（%,d 处），文件大小不变 %d 字节；"
                        + "每轮重置平均 %.0f ms",
                m.medianMs(), m.hits, changedCharsOut, new File(scratch).length(), resetAvgMs));
    }

    static String bakName(String schema) {
        return schema + ".people_bak";
    }

    static void ensureBackup(Connection con, String schema, String tbl) throws SQLException {
        String bak = bakName(schema);
        if (tableExists(con, schema, "people_bak")) return;
        long t0 = System.nanoTime();
        exec(con, "CREATE TABLE " + bak + " AS SELECT * FROM " + tbl);
        log(String.format(Locale.ROOT, "    已创建备份表 %s（一次性，%.0f ms，不计入统计）",
                bak, (System.nanoTime() - t0) / 1e6));
    }

    /**
     * 从备份表恢复原始数据。因为替换**不是幂等的**（'Ttt' 里还含 'T'），
     * 每次测量之前都必须恢复，否则第二轮就变成 'Ttttt' 了。
     */
    static void resetFromBackup(Connection con, String tbl, String bak) throws SQLException {
        boolean ac = con.getAutoCommit();
        con.setAutoCommit(false);
        try (Statement st = con.createStatement()) {
            st.execute("TRUNCATE TABLE " + tbl);
            st.execute("INSERT INTO " + tbl + " SELECT * FROM " + bak);
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(ac);
        }
    }

    static void updateDb(String target, final String oldStr, final String newStr) throws Exception {
        final String tbl = T_IMDB_PEOPLE;
        final String bak = bakName("imdb");
        DbCfg cfg = cfgOf(target);
        Connection con = connect(cfg);
        try {
            ensureBackup(con, "imdb", tbl);
            final String sql = "UPDATE " + tbl + " SET "
                    + "first_name = replace(first_name," + q(oldStr) + "," + q(newStr) + "), "
                    + "surname = replace(surname," + q(oldStr) + "," + q(newStr) + ") "
                    + "WHERE first_name LIKE " + q("%" + oldStr + "%")
                    + " OR surname LIKE " + q("%" + oldStr + "%");
            log("DB 侧 SQL: " + sql);
            log("每轮测量前从 " + bak + " 恢复原始数据（恢复耗时单独输出，不计入 UPDATE 时间）");

            final PreparedStatement[] ps = new PreparedStatement[1];
            Meas m = measureWithReset(new Op() {
                public long call() throws Exception {
                    if (ps[0] != null) ps[0].close();
                    ps[0] = con.prepareStatement(sql);
                    return ps[0].executeUpdate();
                }
            }, new Op() {
                public long call() throws Exception {
                    resetFromBackup(con, tbl, bak);
                    return -1;
                }
            });
            if (ps[0] != null) ps[0].close();

            emit("update", target, tbl, m);
            res("update", target, tbl, "reset_ms", resetAvgMs, "ms", -1);
            log(String.format(Locale.ROOT,
                    "%s 侧：UPDATE 中位耗时 %.3f ms，影响 %,d 行；每轮重置平均 %.0f ms",
                    target, m.medianMs(), m.hits, resetAvgMs));
        } finally {
            con.close();
        }
    }

    // ========================================================================
    // 7. point —— 主键点查
    // ========================================================================

    static void point(String target, long id) throws Exception {
        hr("point 主键点查 [E3]  movieid=" + id);
        expId("point", target, "E3");
        if ("file".equals(target)) pointFile(id);
        else pointDb(target, id);
    }

    static void pointFile(long id) throws Exception {
        final String path = moviesPath("imdb");
        if (!requireFile(path)) return;
        log("file 侧三种实现：顺序扫描 / 内存散列 / 排序后二分 + seek");
        final int lookups = 200;

        // ---- (1) 顺序扫描：每次查询都把文件从头读到尾 ----
        Meas mScan = measure(new Op() {
            public long call() throws Exception {
                long found = 0;
                for (int q = 0; q < lookups; q++) {
                    BufferedReader r = openReader(path);
                    try {
                        String line;
                        while ((line = r.readLine()) != null) {
                            String f = fieldAt(line, 0);
                            if (f != null && parseLongOr(f, Long.MIN_VALUE) == id) {
                                found++;
                                break;
                            }
                        }
                    } finally {
                        r.close();
                    }
                }
                return found;
            }
        });
        res("point", "file", "imdb.movies", "us_scan", mScan.medianMs() * 1000.0 / lookups, "us", mScan.hits);

        // ---- (2) 内存散列：装载耗时单独统计，查询时纯内存 ----
        long t0 = System.nanoTime();
        LongStrMap map = new LongStrMap(1 << 24);
        long rows = 0;
        OffsetLineReader r2 = new OffsetLineReader(path);
        try {
            String line;
            while ((line = r2.readLine()) != null) {
                String f = fieldAt(line, 0);
                if (f == null) continue;
                long k = parseLongOr(f, Long.MIN_VALUE);
                if (k == Long.MIN_VALUE) continue;
                String t = fieldAt(line, 1);
                map.put(k, t == null ? "" : t);
                rows++;
                if (FILE_LIMIT > 0 && rows >= FILE_LIMIT) break;
            }
        } finally {
            r2.close();
        }
        double loadMs = (System.nanoTime() - t0) / 1e6;
        res("point", "file", "imdb.movies", "load_ms_hashmap", loadMs, "ms", rows);
        Meas mHash = measure(new Op() {
            public long call() throws Exception {
                long found = 0;
                for (int q = 0; q < lookups; q++) {
                    if (map.get(id) != null) found++;
                }
                return found;
            }
        });
        res("point", "file", "imdb.movies", "us_hashmap", mHash.medianMs() * 1000.0 / lookups, "us", mHash.hits);

        // ---- (3) 排序 + 二分 + seek ----
        t0 = System.nanoTime();
        long[] packed = new long[1 << 20];
        int n = 0;
        OffsetLineReader r3 = new OffsetLineReader(path);
        try {
            String line;
            while ((line = r3.readLine()) != null) {
                String f = fieldAt(line, 0);
                if (f == null) continue;
                long k = parseLongOr(f, Long.MIN_VALUE);
                if (k == Long.MIN_VALUE) continue;
                if (n == packed.length) packed = Arrays.copyOf(packed, n << 1);
                // 高 32 位放 id，低 32 位放行起始字节偏移（本数据集文件 < 4GB，够用）
                packed[n++] = (k << 32) | (r3.lineStart & 0xFFFFFFFFL);
                if (FILE_LIMIT > 0 && n >= FILE_LIMIT) break;
            }
        } finally {
            r3.close();
        }
        final long[] idxArr = Arrays.copyOf(packed, n);
        Arrays.sort(idxArr);
        double loadMs2 = (System.nanoTime() - t0) / 1e6;
        res("point", "file", "imdb.movies", "load_ms_sorted", loadMs2, "ms", n);

        Meas mBin = measure(new Op() {
            public long call() throws Exception {
                long found = 0;
                RandomAccessFile raf = new RandomAccessFile(path, "r");
                try {
                    for (int q = 0; q < lookups; q++) {
                        int lo = 0, hi = idxArr.length - 1, hit = -1;
                        while (lo <= hi) {
                            int mid = (lo + hi) >>> 1;
                            long midId = idxArr[mid] >>> 32;
                            if (midId == id) {
                                hit = mid;
                                break;
                            }
                            if (midId < id) lo = mid + 1;
                            else hi = mid - 1;
                        }
                        if (hit >= 0) {
                            raf.seek(idxArr[hit] & 0xFFFFFFFFL);
                            raf.readLine();
                            found++;
                        }
                    }
                } finally {
                    raf.close();
                }
                return found;
            }
        });
        res("point", "file", "imdb.movies", "us_sorted_seek",
                mBin.medianMs() * 1000.0 / lookups, "us", mBin.hits);
        log(String.format(Locale.ROOT,
                "file 侧单次查询：顺序扫描 %.1f us / 内存散列 %.1f us / 排序+seek %.1f us（每次 %d 次查询）",
                mScan.medianMs() * 1000.0 / lookups, mHash.medianMs() * 1000.0 / lookups,
                mBin.medianMs() * 1000.0 / lookups, lookups));
    }

    static void pointDb(String target, long id) throws Exception {
        final String sql = "SELECT title FROM " + T_IMDB_MOVIES + " WHERE movieid = " + id;
        log("DB 侧 SQL: " + sql);
        DbCfg cfg = cfgOf(target);
        Connection con = connect(cfg);
        try {
            Meas m = measure(new Op() {
                public long call() throws Exception {
                    try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                        return rs.next() ? 1 : 0;
                    }
                }
            });
            res("point", target, T_IMDB_MOVIES, "us_pk", m.medianMs() * 1000.0, "us", m.hits);
            log(String.format(Locale.ROOT, "%s 侧主键点查：中位 %.1f us，命中 %d",
                    target, m.medianMs() * 1000.0, m.hits));
        } finally {
            con.close();
        }
    }

    // ========================================================================
    // 8. join —— 三表连接
    // ========================================================================

    static void join(String target, int year, String cat) throws Exception {
        final String category = (cat == null || cat.trim().length() == 0)
                ? "D" : cat.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        hr("join 三表连接 [E4]  year>=" + year + "  credited_as=" + category
                + "（" + ("D".equals(category) ? "导演" : ("A".equals(category) ? "演员" : "其他")) + "）");
        expId("join", target, "E4");
        if ("file".equals(target)) joinFile(year, category);
        else joinDb(target, year, category);
    }

    static void joinFile(int year, String category) throws Exception {
        final String mp = moviesPath("imdb");
        final String cp = creditsPath("imdb");
        final String pp = peoplePath("imdb");
        if (!requireFile(mp) || !requireFile(cp) || !requireFile(pp)) return;
        final String scale = "imdb.join3";
        final char cat = category.charAt(0);

        // ---- 装载两张维度表：movies 的 id 位图 + people 的 id -> 姓名 ----
        // 位图比 HashSet<Long> 省两个数量级内存；people 用自写的紧凑散列表。
        long t0 = System.nanoTime();
        final BitSet movieIds = new BitSet(1 << 25);
        long movies = 0;
        BufferedReader rm = openReader(mp);
        try {
            String line;
            while ((line = rm.readLine()) != null) {
                String f = fieldAt(line, 0);
                if (f == null) continue;
                long k = parseLongOr(f, -1);
                if (k > 0 && k < (1L << 31)) movieIds.set((int) k);
                movies++;
                if (FILE_LIMIT > 0 && movies >= FILE_LIMIT) break;
            }
        } finally {
            rm.close();
        }
        final LongStrMap people = new LongStrMap(1 << 25);
        long peopleRows = 0;
        BufferedReader rp = openReader(pp);
        try {
            String line;
            while ((line = rp.readLine()) != null) {
                String f0 = fieldAt(line, 0);
                if (f0 == null) continue;
                long k = parseLongOr(f0, -1);
                if (k < 0) continue;
                String fn = fieldAt(line, 1);
                String sn = fieldAt(line, 2);
                people.put(k, (isNull(fn) ? "" : fn) + " " + (isNull(sn) ? "" : sn));
                peopleRows++;
            }
        } finally {
            rp.close();
        }
        double loadMs = (System.nanoTime() - t0) / 1e6;
        res("join", "file", scale, "load_ms", loadMs, "ms", peopleRows);
        log(String.format(Locale.ROOT, "装载完成：movies %,d 行，people %,d 人，耗时 %.0f ms（不计入查询耗时）",
                movies, peopleRows, loadMs));

        Meas m = measure(new Op() {
            public long call() throws Exception {
                LongIntMap counts = new LongIntMap(1 << 20);
                BufferedReader rc = openReader(cp);
                try {
                    String line;
                    while ((line = rc.readLine()) != null) {
                        String c0 = fieldAt(line, 0);
                        String c1 = fieldAt(line, 1);
                        String c2 = fieldAt(line, 2);
                        if (c0 == null || c1 == null || c2 == null) continue;
                        if (c2.length() == 0 || c2.charAt(0) != cat) continue;
                        long mid = parseLongOr(c0, -1);
                        long pid = parseLongOr(c1, -1);
                        if (mid < 0 || pid < 0) continue;
                        // 与 movies 的半连接：credits 里的电影必须在 movies 里
                        if (mid < (1L << 31) && !movieIds.get((int) mid)) continue;
                        if (people.get(pid) == null) continue;
                        counts.add(pid, 1);
                    }
                } finally {
                    rc.close();
                }
                return counts.size;
            }
        });
        emit("join", "file", scale, m);
        log(String.format(Locale.ROOT, "file 侧手写散列连接：中位耗时 %.3f ms，有作品的 %s %,d 人",
                m.medianMs(), "D".equals(category) ? "导演" : "演员", m.hits));
    }

    static void joinDb(String target, int year, String cat) throws Exception {
        // 年份过滤放在 movies 上，类别过滤放在 credits 上，
        // 这样三张表都真正参与了运算，而不是放宽成两表连接。
        final String sql = "SELECT p.peopleid, p.first_name, p.surname, count(DISTINCT m.movieid) AS works "
                + "FROM " + T_IMDB_MOVIES + " m "
                + "JOIN " + T_IMDB_CREDITS + " c ON c.movieid = m.movieid "
                + "JOIN " + T_IMDB_PEOPLE + " p ON p.peopleid = c.peopleid "
                + "WHERE m.year_released >= " + year + " AND c.credited_as = " + q(cat)
                + " GROUP BY p.peopleid, p.first_name, p.surname "
                + "ORDER BY works DESC LIMIT 20";
        log("DB 侧 SQL: " + sql);
        DbCfg cfg = cfgOf(target);
        Connection con = connect(cfg);
        try {
            final Statement[] st = new Statement[1];
            final ResultSet[] rs = new ResultSet[1];
            Meas m = measure(new Op() {
                public long call() throws Exception {
                    if (rs[0] != null) {
                        rs[0].close();
                        st[0].close();
                    }
                    st[0] = con.createStatement();
                    rs[0] = st[0].executeQuery(sql);
                    long n = 0;
                    while (rs[0].next()) n++;
                    return n;
                }
            });
            emit("join", target, "imdb.join3", m);
            log(String.format(Locale.ROOT, "%s 侧三表连接：中位耗时 %.3f ms，返回 %,d 行",
                    target, m.medianMs(), m.hits));
            if (rs[0] != null) {
                rs[0].close();
                st[0].close();
            }
            // 顺带把 Top20 打出来，方便和 file 侧核对正确性
            try (Statement st2 = con.createStatement(); ResultSet rs2 = st2.executeQuery(sql)) {
                log("Top 20（用于正确性核对）:");
                while (rs2.next()) {
                    log(String.format(Locale.ROOT, "    %-10d %-30s %d",
                            rs2.getLong(1), rs2.getString(2) + " " + rs2.getString(3), rs2.getLong(4)));
                }
            }
        } finally {
            con.close();
        }
    }

    // ========================================================================
    // 9. agg —— 聚合统计
    // ========================================================================

    /** flights.tsv 的列序与 init_pg.sql 中 flights.flights 完全一致。 */
    static final int FL_DATE = 0, FL_AIRLINE = 1, FL_ARR_DELAY = 20;

    /** taobao_ad.tsv = row_id + 21 列，与 taobao.ad_clicks 一致。 */
    static final int TB_CLK = 1, TB_CATE = 15;

    static void agg(String target, String dim, String tableOverride) throws Exception {
        boolean isYear = "year".equalsIgnoreCase(dim);
        boolean isAirline = "airline".equalsIgnoreCase(dim);
        boolean isCate = "cate".equalsIgnoreCase(dim);
        if (!isYear && !isAirline && !isCate) {
            log("!! 未知维度: " + dim + "（可选 year / airline / cate）");
            return;
        }
        // 默认表名按维度决定；指定 --table 时用它替换，用于 openGauss 列存表对比
        //     MovieBench og agg airline --table flights_col
        String defTable = isYear ? T_IMDB_MOVIES : (isAirline ? T_FLIGHTS : T_TAOBAO);
        String table = (tableOverride == null || tableOverride.trim().length() == 0)
                ? defTable : tableOverride.trim();
        boolean overridden = !table.equals(defTable);

        hr("agg 聚合统计 [" + (isYear ? "E5" : "E8") + "]  维度=" + dim
                + "  表=" + table + (overridden ? "（--table 覆盖，默认 " + defTable + "）" : ""));
        expId("agg", target, isYear ? "E5" : "E8");
        if (overridden && "file".equals(target)) {
            log("提示：--table 只对 pg / og 有意义；file 侧固定读 TSV 文件，本参数被忽略。");
        }

        if (isYear) {
            aggYear(target, table);
        } else if (isAirline) {
            aggOne(target, "airline",
                    "SELECT airline, avg(arr_delay) AS avg_delay, count(*) AS n FROM " + table
                            + " GROUP BY airline ORDER BY avg_delay DESC",
                    DATA_ROOT + "/flights/flights.tsv", scaleName(target, table, defTable),
                    FL_AIRLINE, FL_ARR_DELAY);
        } else {
            aggOne(target, "cate",
                    "SELECT cate_id, count(*) AS impressions, sum(clk) AS clicks FROM " + table
                            + " GROUP BY cate_id ORDER BY impressions DESC",
                    DATA_ROOT + "/taobao/taobao_ad.tsv", scaleName(target, table, defTable),
                    TB_CATE, TB_CLK);
        }
    }

    /** file 侧读的是固定 TSV，所以 --table 不该影响它的 scope 标签。 */
    static String scaleName(String target, String table, String defTable) {
        return "file".equals(target) ? defTable : table;
    }

    static void aggYear(String target, String table) throws Exception {
        final String scale = "file".equals(target) ? T_IMDB_MOVIES : table;
        if ("file".equals(target)) {
            final String path = moviesPath("imdb");
            if (!requireFile(path)) return;
            log("file 侧：逐行读 " + path + "，HashMap<Integer,Integer> 手工累加，"
                    + "等价于 GROUP BY year_released");
            Meas m = measure(new Op() {
                public long call() throws Exception {
                    HashMap<Integer, Integer> g = new HashMap<Integer, Integer>(1024);
                    BufferedReader r = openReader(path);
                    try {
                        String line;
                        while ((line = r.readLine()) != null) {
                            String y = fieldAt(line, 3);
                            if (isNull(y)) continue;
                            int k;
                            try {
                                k = Integer.parseInt(y.trim());
                            } catch (NumberFormatException e) {
                                continue;
                            }
                            Integer cur = g.get(k);
                            g.put(k, cur == null ? 1 : cur + 1);
                        }
                    } finally {
                        r.close();
                    }
                    return g.size();
                }
            });
            emit("agg", "file", scale, m);
            log(String.format(Locale.ROOT, "file 侧：中位耗时 %.3f ms，分组数 %,d", m.medianMs(), m.hits));
            return;
        }
        aggDb(target, "year",
                "SELECT year_released, count(*) FROM " + table
                        + " GROUP BY year_released ORDER BY year_released", scale);
    }

    /** 同一个聚合：DB 侧走 GROUP BY，file 侧走 HashMap 手工累加。 */
    static void aggOne(String target, String tag, final String sql,
                       final String path, String scale, final int keyIdx, final int valIdx) throws Exception {
        if ("file".equals(target)) {
            if (!requireFile(path)) return;
            Meas m = measure(new Op() {
                public long call() throws Exception {
                    HashMap<String, double[]> g = new HashMap<String, double[]>(1 << 12);
                    BufferedReader r = openReader(path);
                    try {
                        String line;
                        while ((line = r.readLine()) != null) {
                            String k = fieldAt(line, keyIdx);
                            if (isNull(k)) continue;
                            double[] acc = g.get(k);
                            if (acc == null) {
                                acc = new double[3];
                                g.put(k, acc);
                            }
                            acc[0] += 1;
                            if (valIdx >= 0) acc[1] += parseDoubleOr(fieldAt(line, valIdx), 0);
                        }
                    } finally {
                        r.close();
                    }
                    return g.size();
                }
            });
            emit("agg", "file", scale, m);
            log(String.format(Locale.ROOT, "file 侧 %s：中位耗时 %.3f ms，分组数 %,d",
                    tag, m.medianMs(), m.hits));
            return;
        }
        aggDb(target, tag, sql, scale);
    }

    static void aggDb(String target, String tag, final String sql, String scale) throws Exception {
        log("DB 侧 SQL: " + sql);
        DbCfg cfg = cfgOf(target);
        Connection con = connect(cfg);
        try {
            final Statement[] st = new Statement[1];
            final ResultSet[] rs = new ResultSet[1];
            Meas m = measure(new Op() {
                public long call() throws Exception {
                    if (rs[0] != null) {
                        rs[0].close();
                        st[0].close();
                    }
                    st[0] = con.createStatement();
                    rs[0] = st[0].executeQuery(sql);
                    long n = 0;
                    while (rs[0].next()) n++;
                    return n;
                }
            });
            emit("agg", target, scale, m);
            log(String.format(Locale.ROOT, "%s 侧 %s：中位耗时 %.3f ms，分组数 %,d",
                    target, tag, m.medianMs(), m.hits));
            if (rs[0] != null) {
                rs[0].close();
                st[0].close();
            }
        } finally {
            con.close();
        }
    }

    // ========================================================================
    // 10. conc —— 并发检索
    // ========================================================================

    static void conc(String target, final int threads, final int seconds, final String word)
            throws Exception {
        hr("conc 并发检索 [E6]  线程数=" + threads + "  时长=" + seconds + "s  关键词=[" + word + "]");
        expId("conc", target, "E6");
        if (threads > 64 && !"file".equals(target)) {
            log("提示：数据库连接池上限设为 64（PostgreSQL 默认 max_connections=100），"
                    + "超出的线程会排队等连接。");
        }
        final long deadline = System.nanoTime() + seconds * 1000000000L;
        final AtomicLong totalOps = new AtomicLong();
        final AtomicLong totalErrors = new AtomicLong();
        final LongList[] latencies = new LongList[threads];

        if ("file".equals(target)) {
            final String path = DATA_ROOT + "/imdb/movies.tsv";
            if (!requireFile(path)) return;
            log("file 侧：每个线程各自打开 " + path + " 并反复做同一检索");
            Thread[] ts = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                final int ti = t;
                latencies[t] = new LongList();
                ts[t] = new Thread(new Runnable() {
                    public void run() {
                        LongList lat = latencies[ti];
                        try {
                            while (System.nanoTime() < deadline) {
                                long t0 = System.nanoTime();
                                BufferedReader r = openReader(path);
                                try {
                                    String line;
                                    while ((line = r.readLine()) != null) {
                                        String f = fieldAt(line, 1);
                                        if (f != null && f.contains(word)) {
                                            // 命中即可，这里只统计耗时
                                        }
                                    }
                                } finally {
                                    r.close();
                                }
                                lat.add(System.nanoTime() - t0);
                                totalOps.incrementAndGet();
                            }
                        } catch (Throwable e) {
                            totalErrors.incrementAndGet();
                            log("    线程 " + ti + " 异常: " + e);
                        }
                    }
                });
                ts[t].start();
            }
            for (Thread t : ts) t.join();
        } else {
            DbCfg cfg = cfgOf(target);
            int poolSize = Math.min(threads, 64);
            final SimplePool pool = new SimplePool(cfg, poolSize);
            final String sql = "SELECT count(*) FROM " + T_IMDB_MOVIES
                    + " WHERE title LIKE " + q(likePattern(word));
            log("DB 侧 SQL（每次请求执行一次）: " + sql);
            log("连接池大小 = " + poolSize);
            Thread[] ts = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                final int ti = t;
                latencies[t] = new LongList();
                ts[t] = new Thread(new Runnable() {
                    public void run() {
                        LongList lat = latencies[ti];
                        try {
                            while (System.nanoTime() < deadline) {
                                Connection c = pool.borrow();
                                long t0 = System.nanoTime();
                                try {
                                    try (Statement st = c.createStatement();
                                         ResultSet rs = st.executeQuery(sql)) {
                                        while (rs.next()) {
                                            rs.getLong(1);
                                        }
                                    }
                                    lat.add(System.nanoTime() - t0);
                                    totalOps.incrementAndGet();
                                } finally {
                                    pool.giveBack(c);
                                }
                            }
                        } catch (Throwable e) {
                            totalErrors.incrementAndGet();
                            log("    线程 " + ti + " 异常: " + e);
                        }
                    }
                });
                ts[t].start();
            }
            for (Thread t : ts) t.join();
            pool.close();
        }

        LongList all = new LongList();
        for (int t = 0; t < threads; t++) {
            if (latencies[t] == null) continue;
            for (int i = 0; i < latencies[t].size(); i++) all.add(latencies[t].a[i]);
        }
        long[] sorted = all.sortedArray();
        long ops = totalOps.get();
        double qps = ops / (double) seconds;
        double p95 = percentileMs(sorted, 0.95);
        double avg = 0;
        for (long v : sorted) avg += v;
        avg = sorted.length == 0 ? 0 : avg / sorted.length / 1e6;

        res("conc", target, "imdb.movies", "qps", qps, "ops/s", ops);
        res("conc", target, "imdb.movies", "p95_ms", p95, "ms", ops);
        res("conc", target, "imdb.movies", "avg_ms", avg, "ms", ops);
        res("conc", target, "imdb.movies", "threads", threads, "count", ops);
        res("conc", target, "imdb.movies", "wall_seconds", seconds, "s", ops);
        res("conc", target, "imdb.movies", "errors", totalErrors.get(), "count", ops);
        log(String.format(Locale.ROOT,
                "%s 侧：%d 线程 %d 秒共完成 %,d 次请求，QPS=%.1f，P95=%.3f ms，平均=%.3f ms，错误=%d",
                target, threads, seconds, ops, qps, p95, avg, totalErrors.get()));
    }

    // ========================================================================
    // 11. tx kill —— 中断写入：开启事务后不提交，等待外部强杀
    // ========================================================================

    static void tx(String target, String action) throws Exception {
        if (!"kill".equalsIgnoreCase(action)) {
            log("!! tx 目前只支持: tx kill");
            return;
        }
        hr("tx kill  中断写入 [E11]  target=" + target);
        expId("tx", target, "E11");
        long pid = currentPid();
        res("tx", target, "-", "pid", pid, "pid", -1);

        if ("file".equals(target)) {
            txFileKill(pid);
        } else {
            txDbKill(target, pid);
        }
    }

    /**
     * 文件侧：一边把 people.tsv 重写成新文件，一边在写到大约 30% 时挂起。
     * 外部把这台 JVM 强杀之后，新文件就是一个**残缺的半成品**——
     * 这正是文件系统缺少原子性的直接证据。
     */
    static void txFileKill(long pid) throws Exception {
        final String src = peoplePath("imdb");
        if (!requireFile(src)) return;
        mkdirs(WORK_DIR);
        final String dst = WORK_DIR + "/people_killed_halfway.tsv";
        long totalBytes = new File(src).length();
        long stopAt = (long) (totalBytes * 0.30);

        log("file 侧：把 " + src + " 重写到 " + dst);
        log("写到源文件约 30%（约 " + stopAt + " 字节）时挂起，等待被强杀。");
        BufferedReader r = openReader(src);
        BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(dst), StandardCharsets.UTF_8), 1 << 20);
        long bytes = 0;
        long lines = 0;
        String line;
        while ((line = r.readLine()) != null) {
            String[] c = line.split("\t", -1);
            for (int k = 1; k <= 2 && k < c.length; k++) {
                if (!"\\N".equals(c[k])) c[k] = c[k].replace("T", "Ttt");
            }
            String out = joinTabs(c);
            w.write(out);
            w.write('\n');
            w.flush();
            bytes += out.length() + 1;
            lines++;
            if (bytes >= stopAt) break;
        }
        log("已写出 " + lines + " 行 / " + bytes + " 字节，现在挂起，**不关闭文件**。");
        log("now rewriting, kill me");
        printKillHint(pid, dst);
        hangForever();
    }

    /**
     * 数据库侧：BEGIN 之后执行一条大 UPDATE，**故意不 COMMIT**，然后挂起。
     * 进程被强杀后连接断开，数据库自动回滚，表里的数据完好无损。
     */
    static void txDbKill(String target, long pid) throws Exception {
        final String tbl = T_IMDB_PEOPLE;
        DbCfg cfg = cfgOf(target);
        Connection con = connect(cfg);
        con.setAutoCommit(false);
        final String sql = "UPDATE " + tbl + " SET "
                + "first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') "
                + "WHERE first_name LIKE '%T%' OR surname LIKE '%T%'";
        log("DB 侧 SQL（事务已开启，下面这条执行完**不会** COMMIT）:");
        log("    " + sql);
        long t0 = System.nanoTime();
        long affected;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            affected = ps.executeUpdate();
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        res("tx", target, tbl, "update_ms", ms, "ms", affected);
        res("tx", target, tbl, "affected_rows", affected, "count", affected);
        log(String.format(Locale.ROOT, "UPDATE 已执行：影响 %,d 行，耗时 %.0f ms。事务**尚未提交**。",
                affected, ms));
        log("进程被强杀后，重新连接数据库执行下面的语句，应当一条都没变：");
        log("    SELECT count(*) FROM " + tbl + " WHERE first_name LIKE '%Ttt%' OR surname LIKE '%Ttt%';");
        log("transaction open, not committed -- now rewriting, kill me");
        printKillHint(pid, "（数据库事务）");
        try {
            hangForever();
        } finally {
            // 正常退出路径（没人杀我们）时显式回滚，避免留下一把长事务锁住表
            try {
                con.rollback();
                log("未收到强杀信号，已主动 ROLLBACK。");
            } catch (SQLException ignored) {
                // 忽略
            }
            con.close();
        }
    }

    static void printKillHint(long pid, String artifact) {
        log("");
        log("############ 现在请到**另一个命令行窗口**强杀本进程 ############");
        log("    方式一（cmd / PowerShell，推荐）:");
        log("        taskkill /F /PID " + pid);
        log("    方式二（PowerShell，按进程名杀，OPERATIONS.md 用的就是这条）:");
        log("        Get-Process java | Stop-Process -Force");
        log("    方式三（WSL / Git Bash）:");
        log("        kill -9 " + pid);
        log("    本进程 PID = " + pid);
        log("###############################################################");
        log("");
        log("被强杀后请检查: " + artifact);
        log("进程挂起中……");
    }

    /** 一直挂起，等外部强杀。 */
    static void hangForever() {
        while (true) {
            try {
                Thread.sleep(3600000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ========================================================================
    // 12. bloat —— 表膨胀观测
    // ========================================================================

    static void bloat(String target, int rounds) throws Exception {
        hr("bloat 表膨胀观测 [E10]  轮数=" + rounds);
        expId("bloat", target, "E10");
        if ("file".equals(target)) {
            log("!! bloat 依赖 pg_relation_size()，只对 pg / og 有意义。");
            log("!! 文件侧的【写放大】请用: MovieBench file update T Ttt 与 file update T X 对照。");
            return;
        }
        final String tbl = T_IMDB_PEOPLE;
        final String bak = bakName("imdb");
        final String updateSql = "UPDATE " + tbl + " SET "
                + "first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') "
                + "WHERE first_name LIKE '%T%' OR surname LIKE '%T%'";
        final String searchSql = "SELECT count(*) FROM " + tbl + " WHERE surname LIKE '%tt%'";
        log("每轮 SQL: " + updateSql);
        log("观测 SQL: " + searchSql);
        log("注意：这一组实验**故意不做重置**，膨胀正是要观测的对象。");

        DbCfg cfg = cfgOf(target);
        Connection con = connect(cfg);
        try {
            ensureBackup(con, "imdb", tbl);
            log("先恢复到原始数据一次（不计时）");
            resetFromBackup(con, tbl, bak);

            long size0 = queryLong(con, "SELECT pg_relation_size('" + tbl + "')");
            res("bloat", target, tbl, "round0_size_bytes", size0, "bytes", -1);
            log(String.format(Locale.ROOT, "初始表大小 %,d 字节", size0));

            for (int r = 1; r <= rounds; r++) {
                long t0 = System.nanoTime();
                long affected;
                try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                    affected = ps.executeUpdate();
                }
                double updMs = (System.nanoTime() - t0) / 1e6;

                long t1 = System.nanoTime();
                long hits;
                try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(searchSql)) {
                    hits = rs.next() ? rs.getLong(1) : -1;
                }
                double searchMs = (System.nanoTime() - t1) / 1e6;

                long sizeBytes = queryLong(con, "SELECT pg_relation_size('" + tbl + "')");
                long totalBytes = queryLong(con, "SELECT pg_total_relation_size('" + tbl + "')");

                res("bloat", target, tbl, "round" + r + "_update_ms", updMs, "ms", affected);
                res("bloat", target, tbl, "round" + r + "_search_ms", searchMs, "ms", hits);
                res("bloat", target, tbl, "round" + r + "_size_bytes", sizeBytes, "bytes", -1);
                res("bloat", target, tbl, "round" + r + "_total_bytes", totalBytes, "bytes", -1);
                log(String.format(Locale.ROOT,
                        "第 %2d 轮：UPDATE %.0f ms（影响 %,d 行），检索 %.0f ms，表大小 %,d 字节，含索引 %,d 字节",
                        r, updMs, affected, searchMs, sizeBytes, totalBytes));
            }
            log("提示：如需回收膨胀空间，PostgreSQL 执行 VACUUM FULL " + tbl
                    + "；openGauss 可执行 VACUUM FULL，或改用 Ustore 存储引擎对比。");
        } finally {
            con.close();
        }
    }

    // ========================================================================
    // 13. layout —— 文件侧四种存储布局
    // ========================================================================

    static final int FIXED_RECORD = 256;
    static final int FIXED_TITLE = 200;

    static void layout(String target, String kind, String word) throws Exception {
        hr("layout 文件存储布局 [E7]  kind=" + kind + "  word=[" + word + "]");
        expId("layout", target, "E7");
        if (!"file".equals(target)) {
            log("!! layout 只对 file 有意义（数据库侧的索引由 DBMS 自己管理）。");
            log("!! 对照请用: MovieBench pg agg <维度>");
            return;
        }
        mkdirs(LAYOUT_DIR);
        final String src = DATA_ROOT + "/imdb/movies.tsv";
        if (!requireFile(src)) return;
        if ("csv".equalsIgnoreCase(kind)) layoutCsv(src, word);
        else if ("fixed".equalsIgnoreCase(kind)) layoutFixed(src, word);
        else if ("mmap".equalsIgnoreCase(kind)) layoutMmap(src, word);
        else if ("invidx".equalsIgnoreCase(kind)) layoutInvIdx(src, word);
        else log("!! 未知布局: " + kind + "（可选 csv / fixed / mmap / invidx）");
    }

    /** csv：普通 TSV 顺序扫描（基线）。 */
    static void layoutCsv(final String src, final String word) throws Exception {
        log("");
        log("--- layout csv：普通 TSV 顺序扫描 ---");
        final String scale = "imdb.movies.csv";
        res("layout", "file", scale, "size_bytes", new File(src).length(), "bytes", -1);
        res("layout", "file", scale, "build_ms", 0, "ms", -1);
        Meas m = measure(new Op() {
            public long call() throws Exception {
                long hits = 0;
                BufferedReader r = openReader(src);
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String f = fieldAt(line, 1);
                        if (f != null && f.contains(word)) hits++;
                    }
                } finally {
                    r.close();
                }
                return hits;
            }
        });
        res("layout", "file", scale, "search_ms", m.medianMs(), "ms", m.hits);
        log(String.format(Locale.ROOT, "csv：检索中位 %.3f ms，命中 %,d", m.medianMs(), m.hits));
    }

    /** fixed：定长二进制 256 字节/条，可用 seek 直接定位第 N 条。 */
    static void layoutFixed(final String src, final String word) throws Exception {
        log("");
        log("--- layout fixed：定长二进制 256 字节/条 + RandomAccessFile.seek ---");
        final String scale = "imdb.movies.fixed256";
        final String path = LAYOUT_DIR + "/movies_fixed.dat";
        if (!new File(path).exists() || sysFlag("rebuild")) {
            long t0 = System.nanoTime();
            long written = 0;
            BufferedReader r = openReader(src);
            java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(
                    new FileOutputStream(path), 1 << 20);
            byte[] rec = new byte[FIXED_RECORD];
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(rec);
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    Arrays.fill(rec, (byte) 0);
                    long id = parseLongOr(fieldAt(line, 0), 0);
                    String title = fieldAt(line, 1);
                    byte[] tb = fitUtf8(title == null ? "" : title, FIXED_TITLE);
                    bb.clear();
                    bb.putLong(id);
                    bb.position(8 + FIXED_TITLE);
                    bb.putShort((short) 0);
                    System.arraycopy(tb, 0, rec, 8, tb.length);
                    out.write(rec);
                    written++;
                }
            } finally {
                r.close();
                out.close();
            }
            double buildMs = (System.nanoTime() - t0) / 1e6;
            res("layout", "file", scale, "build_ms", buildMs, "ms", written);
            log(String.format(Locale.ROOT, "fixed 构建：%,d 条，耗时 %.0f ms", written, buildMs));
        } else {
            res("layout", "file", scale, "build_ms", 0, "ms", -1);
            log("fixed 已存在，跳过构建（-Drebuild=1 可强制重建）");
        }
        res("layout", "file", scale, "size_bytes", new File(path).length(), "bytes", -1);

        Meas m = measure(new Op() {
            public long call() throws Exception {
                long hits = 0;
                RandomAccessFile raf = new RandomAccessFile(path, "r");
                try {
                    long total = raf.length() / FIXED_RECORD;
                    byte[] rec = new byte[FIXED_RECORD];
                    for (long i = 0; i < total; i++) {
                        raf.seek(i * FIXED_RECORD);
                        raf.readFully(rec);
                        int n = 0;
                        while (n < FIXED_TITLE && rec[8 + n] != 0) n++;
                        String t = new String(rec, 8, n, StandardCharsets.UTF_8);
                        if (t.contains(word)) hits++;
                    }
                } finally {
                    raf.close();
                }
                return hits;
            }
        });
        res("layout", "file", scale, "search_ms", m.medianMs(), "ms", m.hits);

        // 直接定位第 N 条记录：这才是定长布局真正的卖点，O(1)
        final int probes = 10000;
        Meas mp = measure(new Op() {
            public long call() throws Exception {
                RandomAccessFile raf = new RandomAccessFile(path, "r");
                try {
                    long total = raf.length() / FIXED_RECORD;
                    byte[] rec = new byte[FIXED_RECORD];
                    for (int i = 0; i < probes; i++) {
                        long n = (i * 7919L) % total;
                        raf.seek(n * FIXED_RECORD);
                        raf.readFully(rec);
                    }
                } finally {
                    raf.close();
                }
                return probes;
            }
        });
        res("layout", "file", scale, "point_us", mp.medianMs() * 1000.0 / probes, "us", mp.hits);
        log(String.format(Locale.ROOT, "fixed：检索中位 %.3f ms，命中 %,d；直接定位单次 %.2f us（%,d 次探针）",
                m.medianMs(), m.hits, mp.medianMs() * 1000.0 / probes, probes));
    }

    /** mmap：内存映射，直接在映射缓冲区上做字节级匹配，不产生中间字符串。 */
    static void layoutMmap(final String src, final String word) throws Exception {
        log("");
        log("--- layout mmap：FileChannel.map() 内存映射 ---");
        final String scale = "imdb.movies.mmap";
        final byte[] needle = word.getBytes(StandardCharsets.UTF_8);
        res("layout", "file", scale, "size_bytes", new File(src).length(), "bytes", -1);
        res("layout", "file", scale, "build_ms", 0, "ms", -1);
        try {
            Meas m = measure(new Op() {
                public long call() throws Exception {
                    FileChannel ch = FileChannel.open(Paths.get(src));
                    try {
                        long size = ch.size();
                        if (size > Integer.MAX_VALUE - 8) {
                            throw new IOException("文件超过 2GB，单块映射放不下: " + size);
                        }
                        MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
                        long hits = 0;
                        int fieldStart = -1;
                        int nFields = 0;
                        for (int i = 0; i < size; i++) {
                            byte b = mb.get(i);
                            if (b == '\t') {
                                nFields++;
                                if (nFields == 1) fieldStart = i + 1;
                                else if (nFields == 2 && containsBytes(mb, fieldStart, i, needle)) hits++;
                            } else if (b == '\n') {
                                if (nFields == 1 && fieldStart >= 0
                                        && containsBytes(mb, fieldStart, i, needle)) hits++;
                                nFields = 0;
                                fieldStart = -1;
                            }
                        }
                        return hits;
                    } finally {
                        ch.close();
                    }
                }
            });
            res("layout", "file", scale, "search_ms", m.medianMs(), "ms", m.hits);
            log(String.format(Locale.ROOT, "mmap：检索中位 %.3f ms，命中 %,d", m.medianMs(), m.hits));
        } catch (Exception e) {
            log("mmap 失败: " + e);
        }
    }

    /** 在 MappedByteBuffer 的 [from,to) 区间里找字节串。 */
    static boolean containsBytes(MappedByteBuffer mb, int from, int to, byte[] needle) {
        int last = to - needle.length;
        outer:
        for (int i = from; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (mb.get(i + j) != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** invidx：反转索引（词 -> 电影 id 列表）+ 一个"词 -> 偏移/长度"的小索引文件。 */
    static void layoutInvIdx(final String src, final String word) throws Exception {
        log("");
        log("--- layout invidx：反转索引（词 -> 电影 id 列表）---");
        final String scale = "imdb.movies.invidx";
        final String postings = LAYOUT_DIR + "/invert_postings.txt";
        final String index = LAYOUT_DIR + "/invert_index.txt";
        if (!new File(postings).exists() || !new File(index).exists() || sysFlag("rebuild")) {
            long t0 = System.nanoTime();
            HashMap<String, StringBuilder> inv = new HashMap<String, StringBuilder>(1 << 22);
            BufferedReader r = openReader(src);
            long terms = 0;
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    long id = parseLongOr(fieldAt(line, 0), -1);
                    if (id < 0) continue;
                    String title = fieldAt(line, 1);
                    if (title == null) continue;
                    List<String> ws = words(title);
                    // 同一部电影内先把重复词去掉（例如标题 "Love Love"）。
                    // 去重之后，下面的追加就再也不需要回头检查尾部，
                    // 避免了旧实现里 sb.toString() 那种 O(n) 的整串复制。
                    if (ws.size() > 1) {
                        Set<String> uniq = new HashSet<String>(ws);
                        if (uniq.size() != ws.size()) ws = new ArrayList<String>(uniq);
                    }
                    for (String w : ws) {
                        StringBuilder sb = inv.get(w);
                        if (sb == null) {
                            sb = new StringBuilder(64);
                            inv.put(w, sb);
                        }
                        if (sb.length() > 0) sb.append(',');
                        sb.append(id);
                        terms++;
                    }
                }
            } finally {
                r.close();
            }
            String[] keys = inv.keySet().toArray(new String[0]);
            Arrays.sort(keys);
            java.io.BufferedOutputStream po = new java.io.BufferedOutputStream(
                    new FileOutputStream(postings), 1 << 20);
            BufferedWriter io = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(index), StandardCharsets.UTF_8), 1 << 20);
            long off = 0;
            try {
                for (String k : keys) {
                    byte[] line = (k + "\t" + inv.get(k) + "\n").getBytes(StandardCharsets.UTF_8);
                    po.write(line);
                    io.write(k + "\t" + off + "\t" + line.length + "\n");
                    off += line.length;
                }
            } finally {
                po.close();
                io.close();
            }
            double buildMs = (System.nanoTime() - t0) / 1e6;
            res("layout", "file", scale, "build_ms", buildMs, "ms", keys.length);
            log(String.format(Locale.ROOT, "invidx 构建：%,d 个词条，%,d 条 posting，耗时 %.0f ms",
                    keys.length, terms, buildMs));
        } else {
            res("layout", "file", scale, "build_ms", 0, "ms", -1);
            log("invidx 已存在，跳过构建（-Drebuild=1 可强制重建）");
        }
        res("layout", "file", scale, "size_bytes",
                new File(postings).length() + new File(index).length(), "bytes", -1);

        // 装载小索引（词 -> 偏移/长度）：这一步算装载时间，不算检索时间
        long t0 = System.nanoTime();
        final HashMap<String, long[]> idx = new HashMap<String, long[]>(1 << 22);
        BufferedReader ir = openReader(index);
        try {
            String line;
            while ((line = ir.readLine()) != null) {
                int t1 = line.indexOf('\t');
                if (t1 < 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                if (t2 < 0) continue;
                idx.put(line.substring(0, t1), new long[]{
                        Long.parseLong(line.substring(t1 + 1, t2)),
                        Long.parseLong(line.substring(t2 + 1))});
            }
        } finally {
            ir.close();
        }
        double loadMs = (System.nanoTime() - t0) / 1e6;
        res("layout", "file", scale, "load_ms", loadMs, "ms", idx.size());
        log(String.format(Locale.ROOT, "invidx：小索引装载 %,d 个词条，耗时 %.0f ms", idx.size(), loadMs));

        final String lower = word.toLowerCase(Locale.ROOT);
        Meas m = measure(new Op() {
            public long call() throws Exception {
                long[] range = idx.get(lower);
                if (range == null) return 0;
                RandomAccessFile raf = new RandomAccessFile(postings, "r");
                try {
                    raf.seek(range[0]);
                    byte[] buf = new byte[(int) range[1]];
                    raf.readFully(buf);
                    long ids = 0;
                    for (byte b : buf) {
                        if (b == '\t') {
                            ids = 0;
                            continue;
                        }
                        if (b == ',') ids++;
                    }
                    return ids + 1;
                } finally {
                    raf.close();
                }
            }
        });
        res("layout", "file", scale, "search_ms", m.medianMs(), "ms", m.hits);
        log(String.format(Locale.ROOT, "invidx：检索中位 %.4f ms，命中 %,d", m.medianMs(), m.hits));
    }

    /** 判断 StringBuilder 是否以 ",<id>" 或 "<id>" 结尾。 */
    /** 把标题切成小写词条（字母/数字，长度 >= 2）。 */
    static List<String> words(String title) {
        List<String> out = new ArrayList<String>(8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            } else {
                if (sb.length() >= 2) out.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() >= 2) out.add(sb.toString());
        return out;
    }

    // ========================================================================
    // 14. batch —— 批量写入与事务大小 [E9]
    // ========================================================================

    /**
     * 事务大小对写入吞吐的影响。
     *
     * 数据库侧：PreparedStatement.addBatch() / executeBatch() 负责"批量"，
     *           Connection.setAutoCommit(false) + 每 N 条 commit() 负责"事务大小"。
     *           两者都设成同一个 N，于是这一组实验控制的就是"一次提交写多少行"。
     *
     * 文件侧：追加写，每 N 条调用一次 FileDescriptor.sync()，
     *         用 fsync 模拟"数据真正落到磁盘"。N=1 时每写一条就落盘一次，
     *         这才是文件系统要获得"持久性"必须付出的代价。
     *
     * 目标表是专用测试表，不碰任何实验数据表；程序启动时若表不存在就建表，
     * 结束时不删（方便复核条数）。
     */
    static void batch(String target, int batchSize, long rows) throws Exception {
        // 专用测试表，位于独立的 bench schema，绝不碰 filmdb/imdb/flights/taobao 里的任何表
        final String schema = System.getProperty("batch.schema", "bench");
        final String table = schema + ".batch_target";
        hr("batch 批量写入与事务大小 [E9]  批量大小=" + batchSize + "  总条数=" + rows);
        expId("batch", target, "E9");

        if ("file".equals(target)) {
            batchFile(batchSize, rows);
            return;
        }

        DbCfg cfg = cfgOf(target);
        Connection con = connect(cfg);
        try {
            // schema 不存在就先建；表也是不存在才建。结束时不删，方便复核条数。
            exec(con, "CREATE SCHEMA IF NOT EXISTS " + schema);
            exec(con, "CREATE TABLE IF NOT EXISTS " + table
                    + " (id BIGINT, batch_size INTEGER, payload VARCHAR(64))");
            log("插入目标表: " + table + "（专用测试表，结束时保留）");

            con.setAutoCommit(false);
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO " + table + " (id, batch_size, payload) VALUES (?,?,?)");

            long t0 = System.nanoTime();
            int inBatch = 0;
            for (long i = 0; i < rows; i++) {
                ps.setLong(1, i);
                ps.setInt(2, batchSize);
                ps.setString(3, "row-" + i);
                ps.addBatch();
                inBatch++;
                if (inBatch >= batchSize) {
                    ps.executeBatch();
                    con.commit();          // 事务大小 = 批量大小
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                ps.executeBatch();
                con.commit();
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            ps.close();
            con.setAutoCommit(true);

            long inserted = queryLong(con,
                    "SELECT count(*) FROM " + table + " WHERE batch_size = " + batchSize);
            double tps = rows / (ms / 1000.0);

            // 契约要求的那一行，格式固定：
            // RESULT|batch|<目标>|size=<批量大小>|tps|<值>|rows/s|<插入条数>
            System.out.printf(Locale.ROOT, "RESULT|batch|%s|size=%d|tps|%.3f|rows/s|%d%n",
                    target, batchSize, tps, rows);
            res("batch", target, "size=" + batchSize, "total_ms", ms, "ms", rows);
            res("batch", target, "size=" + batchSize, "rows_this_run", rows, "count", rows);
            res("batch", target, "size=" + batchSize, "rows_in_table", inserted, "count", rows);
            log(String.format(Locale.ROOT,
                    "%s 侧：批量大小=%d，插入 %,d 行，总耗时 %.0f ms，TPS=%.0f（表内该批量共 %,d 行）",
                    target, batchSize, rows, ms, tps, inserted));
        } finally {
            con.close();
        }
    }

    /** 文件侧的批量写入：追加写 + 每 N 条 FileDescriptor.sync() 强制落盘。 */
    static void batchFile(int batchSize, long rows) throws Exception {
        mkdirs(WORK_DIR);
        final String path = WORK_DIR + "/bench_insert_test.tsv";
        log("追加写入文件: " + path + "（每 " + batchSize + " 条调用一次 FileDescriptor.sync()）");
        if (batchSize == 1 && rows > 20000) {
            log("提醒：批量大小 = 1 表示每写一条就 fsync 一次，在机械/普通 SSD 上会非常慢，");
            log("      200000 条可能要跑十几分钟。想先试试可以传小一点的总条数，例如：");
            log("      MovieBench file batch 1 20000");
        }

        FileOutputStream fos = new FileOutputStream(path, true);
        BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(fos, StandardCharsets.UTF_8), 1 << 16);
        long t0 = System.nanoTime();
        int inBatch = 0;
        int syncs = 0;
        try {
            for (long i = 0; i < rows; i++) {
                w.write(Long.toString(i));
                w.write('\t');
                w.write(Integer.toString(batchSize));
                w.write("\trow-");
                w.write(Long.toString(i));
                w.write('\n');
                inBatch++;
                if (inBatch >= batchSize) {
                    w.flush();
                    fos.getFD().sync();     // 真正的落盘，而不是只写进页缓存
                    syncs++;
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                w.flush();
                fos.getFD().sync();
                syncs++;
            }
        } finally {
            w.close();
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        double tps = rows / (ms / 1000.0);

        // 契约要求的那一行，格式固定：
        // RESULT|batch|<目标>|size=<批量大小>|tps|<值>|rows/s|<插入条数>
        System.out.printf(Locale.ROOT, "RESULT|batch|file|size=%d|tps|%.3f|rows/s|%d%n",
                batchSize, tps, rows);
        res("batch", "file", "size=" + batchSize, "total_ms", ms, "ms", rows);
        res("batch", "file", "size=" + batchSize, "fsync_count", syncs, "count", rows);
        res("batch", "file", "size=" + batchSize, "file_bytes", new File(path).length(), "bytes", rows);
        log(String.format(Locale.ROOT,
                "file 侧：批量大小=%d，写入 %,d 行，fsync %d 次，总耗时 %.0f ms，TPS=%.0f",
                batchSize, rows, syncs, ms, tps));
    }

    // ========================================================================
    // 15. 命令行入口
    // ========================================================================

    static void usage() {
        log("MovieBench —— DBMS 性能对比实验程序");
        log("");
        log("用法: java -Xmx4g -cp \".;postgresql-42.2.5.jar;opengauss-jdbc.jar\" "
                + "MovieBench <目标> <模式> [参数...]");
        log("");
        log("  目标: file | pg | og");
        log("");
        log("  search <词> [lang]            关键词检索，lang = en(默认) | zh");
        log("  update <旧串> <新串>          字符替换更新；等长对照用 update T X");
        log("  point <id>                    按主键点查");
        log("  join <年> <类别>              三表连接，类别 D=导演 / A=演员");
        log("  agg <维度> [--table 表名]     聚合统计，维度 = year | airline | cate");
        log("                                默认表: imdb.movies / flights.flights / taobao.ad_clicks");
        log("                                列存对比: agg airline --table flights_col");
        log("  conc <线程数> [秒数] [词]     并发检索，线程数 8 / 32 / 128");
        log("  tx kill                       中断写入（另开窗口 taskkill /F /PID <pid>）");
        log("  bloat <轮数>                  连续 N 轮全表更新，观察表膨胀");
        log("  layout csv|fixed|mmap|invidx [词]   文件侧四种存储布局（只对 file）");
        log("  batch <批量大小> [总条数]     批量写入与事务大小，总条数默认 200000");
        log("");
        log("模式清单（共 10 个）: search update point join agg conc tx bloat layout batch");
        log("实验编号对照       : E1=search  E2/E2b=update  E3=point  E4=join");
        log("                     E5=agg year  E6=conc  E7=layout  E8=agg airline|cate");
        log("                     E9=batch  E10=bloat  E11=tx kill");
        log("");
        log("环境变量:");
        log("  MOVIEBENCH_PG_URL / MOVIEBENCH_PG_USER / MOVIEBENCH_PG_PWD");
        log("  MOVIEBENCH_OG_URL / MOVIEBENCH_OG_USER / MOVIEBENCH_OG_PWD");
        log("  MOVIEBENCH_CSV   (TSV 根目录，默认 E:\\DBMS-Project-Data\\csv)");
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                usage();
                return;
            }
            String target = args[0].toLowerCase(Locale.ROOT);
            String mode = args[1].toLowerCase(Locale.ROOT);

            // 允许在任意位置用 repeat=N / warmup=N 覆盖统计参数，以及 agg 的 --table
            String tableOverride = null;
            List<String> rest = new ArrayList<String>();
            for (int i = 2; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("repeat=")) {
                    REPEAT = Integer.parseInt(a.substring(7));
                } else if (a.startsWith("warmup=")) {
                    WARMUP = Integer.parseInt(a.substring(7));
                } else if ("--table".equals(a)) {
                    if (i + 1 >= args.length) {
                        log("!! --table 后面需要跟一个表名，例如: --table flights_col");
                        return;
                    }
                    tableOverride = args[++i];
                } else {
                    rest.add(a);
                }
            }
            String a0 = rest.size() > 0 ? rest.get(0) : null;
            String a1 = rest.size() > 1 ? rest.get(1) : null;
            String a2 = rest.size() > 2 ? rest.get(2) : null;

            log("MovieBench   target=" + target + "  mode=" + mode
                    + "  warmup=" + WARMUP + "  repeat=" + REPEAT);
            printConfig();

            if ("search".equals(mode)) {
                if (a0 == null) {
                    log("!! search 需要一个关键词，例如: MovieBench file search love");
                    return;
                }
                search(target, a0, a1 == null ? "en" : a1);

            } else if ("update".equals(mode)) {
                if (a0 == null || a1 == null) {
                    log("!! update 需要两个参数，例如: MovieBench pg update T Ttt");
                    return;
                }
                update(target, a0, a1);

            } else if ("point".equals(mode)) {
                if (a0 == null) {
                    log("!! point 需要一个 id，例如: MovieBench pg point 12345");
                    return;
                }
                point(target, Long.parseLong(a0.trim()));

            } else if ("join".equals(mode)) {
                if (a0 == null) {
                    log("!! join 需要年份，例如: MovieBench pg join 2000 D");
                    return;
                }
                join(target, Integer.parseInt(a0.trim()), a1 == null ? "D" : a1);

            } else if ("agg".equals(mode)) {
                if (a0 == null) {
                    log("!! agg 需要一个维度，例如: MovieBench pg agg year");
                    return;
                }
                agg(target, a0, tableOverride);

            } else if ("conc".equals(mode)) {
                if (a0 == null) {
                    log("!! conc 需要线程数，例如: MovieBench pg conc 32");
                    return;
                }
                int threads = Integer.parseInt(a0.trim());
                int seconds = a1 == null ? 10 : Integer.parseInt(a1.trim());
                conc(target, threads, seconds, a2 == null ? "love" : a2);

            } else if ("tx".equals(mode)) {
                tx(target, a0 == null ? "kill" : a0);

            } else if ("bloat".equals(mode)) {
                bloat(target, a0 == null ? 10 : Integer.parseInt(a0.trim()));

            } else if ("layout".equals(mode)) {
                layout(target, a0 == null ? "csv" : a0, a1 == null ? "love" : a1);

            } else if ("batch".equals(mode)) {
                int bs = a0 == null ? 1000 : Integer.parseInt(a0.trim());
                long rows = a1 == null ? 200000L : Long.parseLong(a1.trim());
                batch(target, bs, rows);

            } else {
                log("!! 未知模式: " + mode);
                usage();
            }
        } catch (Throwable t) {
            log("");
            log("!!!! 执行失败: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }
}

/*
 * ============================================================================
 *  命令示例清单 / COMMAND CHEAT SHEET
 * ============================================================================
 *
 *  0) 环境准备（每次开新窗口都要设一次）
 *      Windows cmd:
 *        set MOVIEBENCH_PG_URL=jdbc:postgresql://127.0.0.1:5432/bench
 *        set MOVIEBENCH_PG_USER=postgres
 *        set MOVIEBENCH_PG_PWD=你的密码
 *        set MOVIEBENCH_OG_URL=jdbc:opengauss://127.0.0.1:5432/bench
 *        set MOVIEBENCH_OG_USER=gaussdb
 *        set MOVIEBENCH_OG_PWD=你的密码
 *        set MOVIEBENCH_CSV=E:\DBMS-Project-Data\csv
 *      PowerShell:
 *        $env:MOVIEBENCH_PG_URL="jdbc:postgresql://127.0.0.1:5432/bench"
 *        （其余同理）
 *
 *  1) 编译
 *      cd E:\deepseek harness\database proj1\deliverables
 *      javac -encoding UTF-8 MovieBench.java
 *
 *  2) 关键词检索（老师指定实验一）
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench file search love
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg   search love
 *      java -Xmx4g -cp ".;opengauss-jdbc.jar"    MovieBench og   search love
 *      java -Xmx4g -cp . MovieBench file search 爱 zh
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg search 爱 zh
 *
 *  3) 字符替换更新（老师指定实验二）
 *      java -Xmx4g -cp . MovieBench file update T Ttt
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg update T Ttt
 *      java -Xmx4g -cp ".;opengauss-jdbc.jar"    MovieBench og update T Ttt
 *      —— 等长对照（隔离"记录变长"这个变量）——
 *      java -Xmx4g -cp . MovieBench file update T X
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg update T X
 *
 *  4) 主键点查
 *      java -Xmx5g -cp . MovieBench file point 12345
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg point 12345
 *
 *  5) 三表连接（千万级要给足堆内存）
 *      java -Xmx5g -cp . MovieBench file join 2000 D
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg join 2000 D
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg join 2000 A
 *
 *  6) 聚合统计
 *      java -Xmx4g -cp . MovieBench file agg year
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg agg year
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg agg airline
 *      java -Xmx4g -cp ".;opengauss-jdbc.jar"    MovieBench og agg cate
 *      —— E7 part 2：行存 vs 列存，用 --table 指向列存表 ——
 *      java -Xmx4g -cp ".;opengauss-jdbc.jar" MovieBench og agg airline --table flights_col
 *      java -Xmx4g -cp ".;opengauss-jdbc.jar" MovieBench og agg cate    --table ad_clicks_col
 *      java -Xmx4g -cp ".;opengauss-jdbc.jar" MovieBench og agg year    --table movies_col
 *
 *  7) 并发检索
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg conc 8
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg conc 32
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg conc 128
 *      java -Xmx4g -cp . MovieBench file conc 32 10 love
 *
 *  8) 中断写入：先启动，程序会打印 PID 并挂起；再开一个窗口强杀
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg tx kill
 *      taskkill /F /PID <上面打印出来的 PID>
 *      —— 然后验证数据一条都没变 ——
 *      psql -d bench -c "SELECT count(*) FROM imdb.people WHERE first_name LIKE '%Ttt%';"
 *
 *  9) 表膨胀
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg bloat 10
 *      java -Xmx4g -cp ".;opengauss-jdbc.jar"    MovieBench og bloat 10
 *
 * 10) 文件侧四种存储布局
 *      java -Xmx6g -cp . MovieBench file layout csv    love
 *      java -Xmx6g -cp . MovieBench file layout fixed  love
 *      java -Xmx6g -cp . MovieBench file layout mmap   love
 *      java -Xmx6g -cp . MovieBench file layout invidx love
 *
 * 11) 批量写入与事务大小 [E9] —— 插入到 bench.batch_target（专用测试表）
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg batch 1
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg batch 100
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg batch 1000
 *      java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg batch 10000
 *      java -Xmx4g -cp ".;opengauss-jdbc.jar"    MovieBench og batch 1000
 *      —— 文件侧：追加写 + 每 N 条 fsync ——
 *      java -Xmx4g -cp . MovieBench file batch 1     20000
 *      java -Xmx4g -cp . MovieBench file batch 1000  200000
 *      —— 输出里的关键一行 ——
 *      RESULT|batch|pg|size=1000|tps|48231.500|rows/s|200000
 *
 * 12) 把结果收集起来
 *      java ... MovieBench pg search love > results\search_pg_love.txt
 *      findstr /B "RESULT|" results\*.txt > results\all_results.txt
 * ============================================================================
 */
