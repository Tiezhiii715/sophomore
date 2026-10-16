# DBMS 性能评测：文件程序 vs PostgreSQL vs openGauss

课程项目 **Project 1: DBMS Performance Evaluation**。

在同一台机器上、用同一份数据，对比三种数据管理方式在 **12 组负载**下的表现，回答两个问题：

1. **与直接操作文件相比，DBMS 的独特优势是什么？**
2. **PostgreSQL 与 openGauss 哪个更好，依据什么标准？**

---

## 结论速览

### 1. 数据库的优势不在速度，而在数据的维护与正确性

速度上数据库确实常常领先（E3 点查比文件的顺序扫描快 **1,681 倍**），但那靠的是**索引、并行、列存这些具体机制**——而机制文件也能自己实现：文件手工建的反转索引反而比两个数据库小 **6–8 倍**、快 **330–640 倍**（E5）。

真正追不平的是：

| | 数据库 | 文件 |
|---|---|---|
| 索引预处理 | **0 秒**，建表时一次建好 | **14.177 秒**，每次启动重建 |
| 数据改动后 | 自动同步，查到的永远最新 | 索引作废，得重建 |
| 程序关掉后 | 索引还在 | 索引没了 |
| 被强杀中断 | **自动回滚，数据一字未变** | 留下一个 **27% 的残缺文件** |

### 2. 按默认配置比 PostgreSQL 更好；各自调到最优时 openGauss 上限更高

openGauss 默认关闭并行（`query_dop=1`）、也没有 `pg_trgm` 扩展，于是在扫描、连接、聚合、批量写入上全面落后 **1.89–23 倍**。但它真正的上限来自**换存储形态**：

- 换 **列存**（`ORIENTATION = COLUMN`）→ E9 聚合从 6,864.947 ms 降到 **166.090 ms**，比 PostgreSQL 快 **10.7 倍**
- 换 **Ustore** 引擎 → E6 表膨胀从 **+101% 降到 +48%**

**代价**：这些优势都要求使用者先判断负载、再选引擎，选错了就退回落后状态。

---

## 12 组实验

| 编号 | 实验 | 关键结果 | 报告 |
|---|---|---|---|
| **E1** | 标题关键词检索 | openGauss 无索引无并行时比读 CSV 的 Java 程序**慢 6.4%** | [4.1](report/ProjectReport.md) |
| **E2** | 人名变长更新 `T`→`Ttt` | **文件赢了 6.72 倍**（6,105.888 vs 41,054.052 ms） | 4.2 |
| **E2b** | 等长更新对照 `T`→`X` | 文件侧变长比等长**慢 6.45 倍**；数据库侧无差别（±17%） | 4.3 |
| **E3** | 主键点查 | 文件哈希表快 2,891 倍，但预处理要 **14.177 秒**；数据库预处理 **0** | 4.4 |
| **E4** | 多表连接 JOIN | 算上装载，PostgreSQL 反超文件 **1.95 倍** | 4.5 |
| **E5** | 存储布局与索引 | 文件手工反转索引反超数据库 **330–640 倍** | 4.6 |
| **E6** | 表膨胀与空间回收 | heap 表都涨约一倍；**Ustore 只涨 48%** | 4.7 |
| **E7** | 聚合统计 | PostgreSQL 快文件 **2.60 倍**，openGauss 慢 1.6% | 4.8 |
| **E8** | 并发检索 | **openGauss 三个并发度 P95 都最低** | 4.9 |
| **E9** | 宽表聚合 | **列存 166.090 ms**，比自家行存快 41 倍、比 PostgreSQL 快 10.7 倍 | 4.10 |
| **E10** | 批量写入与事务大小 | 批量 ≥1,000 时**文件反超两个数据库** | 4.11 |
| **E11** | 中断写入 | 数据库自动回滚；文件只留下 **27%** 的残缺产物 | 4.12 |

**分析讨论见报告第 5 章。**

---

## 目录结构

```
.
├── report/
│   ├── ProjectReport.md      # 实验报告全文（含 14 张运行截图）
│   └── image*.png            # 报告中引用的截图
├── src/
│   └── MovieBench.java       # 全部实验的测量程序（单文件、零第三方依赖）
├── sql/
│   ├── init_pg.sql           # PostgreSQL 建表 + \copy 导入
│   ├── init_og.sql           # openGauss 建表 + 导入（含列存表、Ustore 表）
│   ├── indexes_pg.sql        # PostgreSQL 建索引（B-tree / trigram / 反转索引表）
│   └── indexes_og.sql        # openGauss 建索引
├── scripts/
│   ├── measure_*.sql         # 补充实验的 EXPLAIN 测量脚本
│   └── run_E5_file.ps1       # E5 文件侧四种布局的批处理
└── docs/
    └── OPERATIONS.md         # 环境搭建与操作手册（含 19 张操作截图说明）
```

---

## 复现

### 1. 环境

| 项 | 版本 |
|---|---|
| 操作系统 | Windows 11 家庭中文版 25H2 |
| PostgreSQL | 18.6，本机原生安装，端口 5432 |
| openGauss | 5.0.0，Docker Desktop + WSL2，宿主机端口 5433 |
| JDK | 24 |
| JDBC 驱动 | PostgreSQL JDBC 42.2.5（openGauss 也走这个驱动，以 PostgreSQL 协议连接） |

### 2. 数据

四份真实公开数据（详见报告第 3 章）：

| 数据集 | 规模 | 来源 |
|---|---|---|
| IMDb 官方数据集 | 1,285 万作品 / 1,572 万人 / 3,000 万条参与记录 | <https://datasets.imdbws.com/> |
| 美国 BTS 航班准点数据 | 1,393 万条 × 34 列 | <https://transtats.bts.gov/PREZIP/> |
| 课程提供 filmdb | 9,204 电影 / 16,489 人 | 课程提供 |
| 淘宝广告点击数据 | 500 万条 × 21 列 | <https://huggingface.co/datasets/reczoo/TaobaoAd_x1> |

### 3. 建库

```bash
psql -h 127.0.0.1 -p 5432 -U postgres -d bench -f sql/init_pg.sql
psql -h 127.0.0.1 -p 5433 -U gaussdb  -d bench -f sql/init_og.sql
```

> ⚠️ `sql/indexes_*.sql` **必须在所有"查询用不上索引"的实验做完之后**才能执行，否则"无索引"的对照组建不起来。

### 4. 编译与运行

```bash
javac -encoding UTF-8 src/MovieBench.java

# 检索（E1）
java -Xmx4g -cp . MovieBench file search love
java -Xmx4g -cp ".;postgresql-42.2.5.jar" MovieBench pg search love
java -Xmx4g "-Dog.driver=org.postgresql.Driver" -cp ".;postgresql-42.2.5.jar" MovieBench og search love

# 更新（E2 / E2b）
java -Xmx4g -cp . MovieBench file update T Ttt      # 变长，必须整体重写
java -Xmx4g -cp . MovieBench file update T X        # 等长，原地覆写

# 点查（E3） / 连接（E4） / 聚合（E7） / 并发（E8） / 批量写入（E10）
java -Xmx6g -cp . MovieBench file point 1234567
java -Xmx6g -cp . MovieBench file join 2015 D
java -Xmx4g -cp . MovieBench file agg year
java -Xmx4g -cp . MovieBench file conc 32
java -Xmx3g -cp . MovieBench file batch 1000 200000

# 存储布局（E5） / 表膨胀（E6） / 中断写入（E11）
java -Xmx6g -cp . MovieBench file layout invidx love
java -Xmx3g -cp ".;postgresql-42.2.5.jar" MovieBench pg bloat 10
java -Xmx3g -cp ".;postgresql-42.2.5.jar" MovieBench pg tx kill
```

数据库连接通过**环境变量**配置，缺省时回落到代码内的默认值：

```bash
set MOVIEBENCH_PG_URL=jdbc:postgresql://127.0.0.1:5432/bench
set MOVIEBENCH_PG_USER=postgres
set MOVIEBENCH_PG_PWD=你的密码
set MOVIEBENCH_OG_URL=jdbc:postgresql://127.0.0.1:5433/bench
set MOVIEBENCH_OG_USER=gaussdb
set MOVIEBENCH_OG_PWD=你的密码
set MOVIEBENCH_CSV=D:\data\csv
```

### 5. 测量方法

- 每组**预热 3 次、正式测量 5 次取中位数**
- 数据库侧同时采集**客户端端到端时间**与 `EXPLAIN (ANALYZE, BUFFERS)` **服务端执行时间**，并用执行计划归因
- 文件侧与数据库侧走**同一个程序、同一套测量代码**，唯一自变量是"数据存在哪"

---

## 已知局限

1. **部署不对等**：PostgreSQL 本机原生，openGauss 跑在 Docker 容器里，I/O 栈不同（对 openGauss 不利）
2. **内存参数不对等**：openGauss `shared_buffers` 1 GB，PostgreSQL 只有 128 MB（对 openGauss 有利）
3. **内核代差**：openGauss 5.0.0 源自 PostgreSQL 9.2
4. **列存与 Ustore 是"换引擎后"的数据**，属于上限对照，不是默认配置对比
5. 线程池模型未测；E7 的列存对比未做

---

## 许可

代码与报告仅供课程学习使用。数据集版权归各自来源所有，使用请遵守对应许可（IMDb 为非商业用途）。
