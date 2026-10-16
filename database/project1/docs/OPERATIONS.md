# OPERATIONS.md — DBMS 性能评测项目 · 分步操作手册

> 读者假设：**从没用过命令行数据库工具**。每一步都能直接复制，每条命令都说明"跑完会发生什么"和"报错怎么办"。
> 所有命令在 **Windows PowerShell** 里执行，除非特别标注为"容器内"。

---

## 0. 先填一张表，后面全程用你填的值

在开始之前，先把下面这张表填好。后面所有命令中的 `<容器名>`、`<OG_PORT>` 都换成你填的内容。

| 占位符 | 含义 | 怎么查 | 你填的值 |
|---|---|---|---|
| `<容器名>` | openGauss 容器的名字 | `docker ps` 看 NAMES 列 | __________ |
| `<OG_PORT>` | openGauss 映射到 Windows 的端口 | `docker port <容器名>` | __________ |
| `<PG_USER>` | 本机 PostgreSQL 用户名 | 一般就是 `postgres` | __________ |
| `<PG_PWD>` | 本机 PostgreSQL 密码 | 安装时你自己设的 | __________ |
| `<OG_PWD>` | openGauss 的 omm 用户密码 | 启动容器时用 `GS_PASSWORD` 设的 | __________ |

**为什么要填这张表**：最常见的翻车原因就是把 openGauss 的端口当成 5432，结果连到了本机 PostgreSQL 上，实验数据全废。

---

## A. 环境确认

### A1. 检查本机 PostgreSQL

```powershell
# 1) 有没有 psql、什么版本
psql --version
```

**预期输出**：`psql (PostgreSQL) 16.x` 之类。

> **报错 "无法将 psql 项识别为 cmdlet"** → PostgreSQL 的 bin 目录没进 PATH。用完整路径，例如
> ```powershell
> & "C:\Program Files\PostgreSQL\16\bin\psql.exe" --version
> ```
> 下面凡是 `psql` 的地方都换成这个完整路径。

```powershell
# 2) 服务在不在跑
Get-Service *postgres*
```
**预期**：看到一个 `Status = Running` 的服务，名字类似 `postgresql-x64-16`。

```powershell
# 3) 端口在听吗（5432）
netstat -ano | Select-String ":5432"
```
**预期**：出现 `LISTENING`。

```powershell
# 4) 真正连一次
$env:PGPASSWORD = "<PG_PWD>"
psql -U <PG_USER> -d postgres -c "SELECT version();"
```
**预期**：打印一行版本字符串。

> **报错 "password authentication failed"** → 密码不对。重设：用 pgAdmin 或改 `pg_hba.conf`。
> **报错 "could not connect to server"** → 服务没起：`Start-Service postgresql-x64-16`。

**截图 ①**：`SELECT version();` 的输出（报告"实验环境"章节要用）。

---

### A2. 检查 openGauss 容器

```powershell
# 1) 容器在跑吗
docker ps
```
**预期**：看到一行 openGauss。把 NAMES 列抄到第 0 节的表里。

> **看不到 openGauss** → 没启动：`docker start <容器名>`。
> 如果 `docker ps` 报 "cannot connect to the Docker daemon" → 先打开 Docker Desktop 等它变成绿色。

```powershell
# 2) 端口映射
docker port <容器名>
```
**预期**：类似 `5432/tcp -> 0.0.0.0:5433`。**箭头右边那个 5433 才是 `<OG_PORT>`**。

> **如果它也是 5432** → 和你本机 PostgreSQL 撞了。改映射需要重建容器：
> ```powershell
> docker stop <容器名>; docker rm <容器名>
> docker run --name opengauss -d -e GS_PASSWORD='Enmo@123' -p 5433:5432 -m 8g enmotech/opengauss:5.0.0
> ```
> （镜像名以你实际用的为准；`-m 8g` 给容器 8 GB 内存，后面导入大文件要用。）

```powershell
# 3) 容器还有多少磁盘（很重要，导入会失败在磁盘上）
docker exec <容器名> df -h /tmp /var/lib/opengauss 2>$null
```
**预期**：`Avail` 列至少要有 15 GB。

```powershell
# 4) 进容器
docker exec -it <容器名> bash
```
进去以后，提示符会变成类似 `root@xxxx:/#`。**容器内**继续：

```bash
# 容器内
su - omm
```
提示符变成 `omm@xxxx:~$`。**容器内、omm 用户下**继续：

```bash
# 容器内 / omm
gsql -d postgres -p 5432 -c "SELECT version();"
```

**预期**：打印 openGauss 的版本，应该包含 `openGauss 5.0.0`。

> **报错 "gsql: command not found"** → 用完整路径：
> ```bash
> /usr/local/opengauss/bin/gsql -d postgres -p 5432 -c "SELECT version();"
> ```

```bash
# 容器内 / omm —— 数据目录在哪（后面调参数要用）
gsql -d postgres -p 5432 -c "SHOW data_directory;"
```
把输出抄下来，记为 `<OG_DATADIR>`，通常是 `/var/lib/opengauss/data`。

```bash
# 容器内 —— 顺便看看内存/线程池参数
gsql -d postgres -p 5432 -c "SHOW max_connections;"
gsql -d postgres -p 5432 -c "SHOW enable_thread_pool;"
```

退出容器：连按两次 `exit`。

**截图 ②**：`docker ps` 的输出。
**截图 ③**：容器内 `SELECT version();` 的输出。

---

### A3. Docker Desktop (WSL2) 的三个坑

| 坑 | 现象 | 处理 |
|---|---|---|
| **路径大小写与反斜杠** | `docker cp E:\data` 报路径不存在 | 一定要用**双引号**包住 Windows 路径：`docker cp "E:\DBMS-Project-Data\csv" <容器名>:/tmp/csv` |
| **跨盘复制很慢** | `docker cp` 几个 GB 要等好几分钟，看着像卡死 | 正常。E 盘到 WSL2 走 9p 文件系统，速度大概几十 MB/s。**不要 Ctrl+C**，耐心等 |
| **磁盘共享没开** | `docker cp` 报 permission denied | Docker Desktop → Settings → Resources → File sharing，把 E 盘加进去 |

> 另一个隐性坑：**容器磁盘空间**。openGauss 镜像默认的容器可写层可能只有十几 GB。导入 IMDb 的 3000 万行 credit 之前，务必先跑 A2 的第 3 条命令确认 `Avail`。

---

## B. 数据准备

### B1. 下载原始数据

```powershell
cd "E:\deepseek harness\database proj1\work"
python download_data.py
```

**跑完会发生什么**：脚本在 `E:\DBMS-Project-Data\` 下建三个目录并下载：

| 目录 | 内容 | 实测体积 |
|---|---|---|
| `02_imdb\` | `title.basics.tsv.gz`、`name.basics.tsv.gz`、`title.principals.tsv.gz`、`akas_cn_hk_tw.tsv` | 1.27 GB |
| `03_flights\` | `bts_ontime_2023_01.zip` … `bts_ontime_2024_12.zip`（24 个） | 0.66 GB |
| `04_taobao\` | `TaobaoAd_x1.zip` | 2.33 GB |

> **本项目的下载已完成**（合计 4.26 GB），上面的命令留作复现与人读用途。BTS 航班服务器限速约 170 KB/s，24 个文件约需 50 分钟，脚本会自动重试。

**特点**：支持断点续传。中断了直接再跑一次，已经下好的文件会显示 `skip (exists)`。

> **只下某一部分**：`python download_data.py --only imdb`（可选 `imdb` / `flights` / `taobao`）。
> **网络很慢**：IMDb 那份 `akas_cn_hk_tw.tsv` 是边下边过滤的，会打印 `akas filtered: seen ... kept ...`，看到这个说明在正常工作。

---

### B2. 生成导入用的 TSV

```powershell
cd "E:\deepseek harness\database proj1\work"
python prepare_data.py --all
```

**跑完会发生什么**：在 `E:\DBMS-Project-Data\csv\` 下生成全部 TSV。脚本会打印每个文件的行数（下面是**本项目实测的真实输出**）：

```
[1/5] teacher filmdb  ->  csv\filmdb\
      countries.tsv             185 rows
      movies.tsv              9,204 rows
      people.tsv             16,489 rows
      credits.tsv            46,850 rows
      alt_titles.tsv          3,365 rows
[3/5] IMDb  ->  csv\imdb\
      movies.tsv         12,849,219 rows
      people.tsv         15,717,662 rows
      credits.tsv        30,000,000 rows      <- 封顶值
      alt_titles.tsv        150,353 rows      <- 中港台译名
[4/5] US BTS flights  ->  csv\flights\
      ... 24 个月，每月 50.3 – 63.5 万行 ...
      flights.tsv        13,926,960 rows total
[5/5] Taobao ad clicks  ->  csv\taobao\
      test.csv              3,099,515 rows
      train.csv             1,900,485 rows
      taobao_ad.tsv       5,000,000 rows total  (capped at TAOBAO_LIMIT)
```

> **注意 `--all` 会跑第 [2/5] 步「同源规模梯度」**（生成 ×10 / ×100 / ×1000 三套放大数据，约 2 GB、耗时十几分钟）。如果你只想先拿到基础数据，用 `python prepare_data.py --filmdb --imdb --flights --taobao`，规模梯度等后面单独跑 `python prepare_data.py --scale`。
>
> **淘宝为什么要封顶**：原始数据约 1,500 万行、解压后 17.8 GB，全量导入两个数据库要 60 GB 以上。脚本默认取前 500 万行（先 `test.csv` 再 `train.csv`，**按文件顺序取，不是随机抽样**），改上限用 `--taobao-rows` 或直接改脚本里的 `TAOBAO_LIMIT`。

### B3. 输出目录说明

```
E:\DBMS-Project-Data\csv\
├── filmdb\     老师给的数据集      (S0 档：基准 + 同源梯度的母本)
│     countries.tsv  movies.tsv  people.tsv  credits.tsv  alt_titles.tsv
├── scale\      同源放大            (S1/S2/S3 档，分布完全不变，只改数据量)
│     filmdb_x10\  filmdb_x100\  filmdb_x1000\
├── imdb\       IMDb 三表 + 中文片名
│     movies.tsv  people.tsv  credits.tsv  alt_titles.tsv
├── flights\    航班宽表（34 列）
│     flights.tsv
└── taobao\     淘宝广告点击（21 列）
      taobao_ad.tsv
```

**为什么要 `scale\` 这个目录**：这是本项目方法论的核心。用**同一份数据**按 10/100/1000 倍复制，只有"数据量"这一个变量在变，画出来的"耗时-数据量"曲线斜率才等于真实的算法复杂度。如果 S0 用老师数据、S3 用 IMDb，曲线里就混进了"数据分布不同"这个干扰项，结论不成立。

**约定的分隔格式**（三处必须一致，否则导入会错位）：

- 分隔符：**Tab**
- 空值：**`\N`**
- 编码：**UTF-8 无 BOM**

> **报错 "not found: C:\Users\HUAWEI\Desktop\filmdb(1).sql"** → 老师的数据集不在那个位置。打开 `prepare_data.py` 第 46 行，把 `FILMDB_SQL` 改成你的实际路径。

**截图 ④**：`prepare_data.py --all` 的完整输出（报告"数据集"章节要用行数）。

---

### B4. 校验行数（两条命令，结果必须一致）

> ⚠️ **不要用 `Get-Content ... | Measure-Object -Line` 数大文件**：它会把整个文件读进内存，3.6 GB 的 `taobao_ad.tsv` 会让 PowerShell 卡死甚至 OOM。用下面这个流式计数器。

把下面内容存成 `work\count_lines.py`，或者直接用 `python -c`：

```powershell
cd "E:\deepseek harness\database proj1\work"
python -c "import sys,os;[print(f'{sum(1 for _ in open(os.path.join(r,f),encoding=\"utf-8\",errors=\"replace\")):>12,}  {f}') for r,_,fs in os.walk(r'E:\DBMS-Project-Data\csv') for f in fs if f.endswith('.tsv')]"
```

期望输出（本项目实测值）：

| 文件 | 行数 |
|---|---|
| `filmdb\countries.tsv` | 185 |
| `filmdb\movies.tsv` | 9,204 |
| `filmdb\people.tsv` | 16,489 |
| `filmdb\credits.tsv` | 46,850 |
| `filmdb\alt_titles.tsv` | 3,365 |
| `imdb\movies.tsv` | 12,849,219 |
| `imdb\people.tsv` | 15,717,662 |
| `imdb\credits.tsv` | 30,000,000 |
| `imdb\alt_titles.tsv` | 150,353 |
| `flights\flights.tsv` | 13,926,960 |
| `taobao\taobao_ad.tsv` | 5,000,000 |

等数据库导入完，用 A 里的 SQL 查 `count(*)`，两边必须一样。**不一样就说明导入出错了，别往下做实验。**

---

## C. 把数据弄进 openGauss

### C1. 复制数据进容器

```powershell
# 1) 建目录并复制 CSV（这一步最慢，可能几分钟，不要中断）
docker exec <容器名> mkdir -p /tmp/csv
docker cp "E:\DBMS-Project-Data\csv\." <容器名>:/tmp/csv

# 2) 复制 SQL 脚本
docker cp "E:\deepseek harness\database proj1\deliverables\init_og.sql" <容器名>:/tmp/init_og.sql
```

**跑完会发生什么**：`docker cp` 会逐文件打印进度；结束后没有任何输出就是成功了。

> **路径里的 `.` 很关键**：`"E:\...\csv\."` 表示"把这个目录**里面的内容**复制到 /tmp/csv"，而不是把 csv 目录本身套一层变成 `/tmp/csv/csv`。写错了就会导致后面 `\copy` 全部报 "No such file or directory"。

```powershell
# 3) 确认文件到了
docker exec <容器名> ls /tmp/csv
```
**预期**：看到 `filmdb  flights  imdb  scale  taobao` 五个目录。

### C2. 改文件属主（**这一步绝对不能省**）

```powershell
docker exec -u root <容器名> chown -R omm:omm /tmp/csv /tmp/init_og.sql
```

**为什么**：`docker cp` 是以 **root** 身份写文件的，而 openGauss 数据库进程以 **omm** 身份运行。omm 读不了 root 的文件，于是每一个 `\copy` 都会失败：

```
ERROR:  could not open file "/tmp/csv/imdb/movies.tsv" for reading: Permission denied
```

**这就是初学者最常卡住的第一个地方。**

```powershell
# 验证属主改好了
docker exec <容器名> ls -l /tmp/csv
```
**预期**：每一行的 owner 是 `omm omm`（不是 `root root`）。

### C3. 建库（可选但推荐）

```powershell
docker exec -it <容器名> bash
```
```bash
# 容器内
su - omm
gsql -d postgres -p 5432 -c "CREATE DATABASE bench WITH ENCODING='UTF8' DBCOMPATIBILITY='PG' TEMPLATE=template0;"
```

**为什么要 `DBCOMPATIBILITY='PG'`**：openGauss 默认兼容模式是 `'A'`（Oracle 风格），在那个模式下**空字符串会被当成 NULL**，从 PostgreSQL 移植过来的导入脚本行为会不一致。用 `'PG'` 就和 PostgreSQL 一致了。

> **报错 "permission denied to create database"** → 用默认库 `postgres` 就行，把后面所有 `-d bench` 换成 `-d postgres`。
> **报错语法不支持** → 同理，直接用默认库。

### C4. 执行导入脚本

```bash
# 容器内 / omm
gsql -d bench -p 5432 -f /tmp/init_og.sql 2>&1 | tee /tmp/init_og.log
```

**跑完会发生什么**（大概 20–60 分钟，取决于数据量和容器磁盘速度）：

1. 建 4 个 schema、9 张行存表
2. 用 `\copy` 逐张导入，每张前面会打印 `COPY 9204` 这样的行数
3. 建索引、`ANALYZE`
4. 建 3 张**列存表**并从行存表灌数据（E7 用）
5. 建 1 张 **Ustore 表**（E10 用）
6. 最后是实验 SQL，从 E1 到 E11 —— **这一段默认也会被执行**，如果你只想建库，先执行到 `SECTION 7` 之前，或者在 gsql 里用 `\i` 分段执行

**检查结果**：

```bash
gsql -d bench -p 5432 -c "SELECT 'imdb.movies' t, count(*) FROM imdb.movies
  UNION ALL SELECT 'imdb.people',      count(*) FROM imdb.people
  UNION ALL SELECT 'imdb.credits',     count(*) FROM imdb.credits
  UNION ALL SELECT 'flights.flights',  count(*) FROM flights.flights
  UNION ALL SELECT 'taobao.ad_clicks', count(*) FROM taobao.ad_clicks;"
```

**预期**：每一行的数字都和第 B4 步 Windows 侧数出来的一致。

> **报错 "could not open file ... Permission denied"** → 回到 C2 重做 chown。
> **报错 "invalid input syntax for type numeric"** → TSV 里的空值不是 `\N`。用 `head -3` 看一眼那个文件，确认空列写的是 `\N`。
> **导入中途卡住不动** → 大概率是磁盘满了。另开一个 PowerShell 窗口跑 `docker exec <容器名> df -h /tmp`。
> **容器被 OOM kill**（`docker ps` 里容器消失）→ 容器内存不够。给 Docker Desktop 分配 ≥ 8 GB 内存后重建容器。

**截图 ⑤**：导入完成后的 `count(*)` 结果。

### C5. WSL2 路径注意事项

| 事项 | 说明 |
|---|---|
| **容器路径 ≠ Windows 路径** | `\copy` 里的 `/tmp/csv/...` 是**容器内部**的路径。写 `E:\...` 一定会失败 |
| **别用 WSL 的 `/mnt/e/...`** | 虽然 openGauss 跑在 Docker 里，但数据是通过 `docker cp` 复制进容器的，不是挂载。用 `/tmp/csv` |
| **重新 `docker cp` 会覆盖** | 再次执行会把文件重新拷一遍，比较慢。只想更新一个文件就 `docker cp 单文件 <容器名>:/tmp/csv/imdb/` |
| **容器重建后 `/tmp/csv` 消失** | 容器被 `docker rm` 后，`/tmp/csv` 和数据库一起没了，需要从 C1 重来。**建议给数据目录做 volume 挂载**，但那是可选的优化 |

---

## D. 把数据弄进本机 PostgreSQL

### D1. 建库

```powershell
$env:PGPASSWORD = "<PG_PWD>"
createdb -U <PG_USER> bench
```
> **报错 "database bench already exists"** → 已经有了，跳过。

### D2. 导入

```powershell
cd "E:\deepseek harness\database proj1\deliverables"
psql -U <PG_USER> -d bench -f init_pg.sql 2>&1 | Tee-Object -FilePath "E:\DBMS-Project-Data\init_pg.log"
```

**跑完会发生什么**：和 C4 类似，但快很多（本机磁盘，没有 9p 开销）。

> **`\copy` 报 "No such file or directory"** → 检查 `init_pg.sql` 里的数据根路径
> `\set csv 'E:/DBMS-Project-Data/csv'` 是否是**正斜杠**。Windows 上 psql 的 `\copy` 用正斜杠更稳。
> **导入中文乱码** → 确认文件是 UTF-8 无 BOM，且 `psql` 会话编码是 UTF8：`psql -c "SHOW client_encoding;"` 应为 `UTF8`。

### D3. `\copy` 和 `COPY` 的区别（**必须理解，否则一定踩坑**）

| | `COPY table FROM 'file'` | `\copy table FROM 'file'` |
|---|---|---|
| 谁读文件 | **数据库服务进程** | **psql 客户端** |
| 文件要在哪 | 数据库服务器能访问的路径 | 你运行 psql 的那台机器 |
| 需要的权限 | 需要超级用户 + `pg_read_server_files` | 普通用户即可 |
| 本机 Windows 上 | 常报 "Permission denied"，因为 PostgreSQL 服务账户读不了你的用户目录 | **不会**有权限问题 |

**本项目的所有导入一律用 `\copy`。** 原因就是第三行：Windows 上 PostgreSQL 服务以 `NETWORK SERVICE` 账户运行，读不到 `C:\Users\你的名字\...` 下的文件，而 `\copy` 是由 psql 自己读文件，绕过了这个问题。

### D4. 校验

```powershell
psql -U <PG_USER> -d bench -c "SELECT 'imdb.movies' t, count(*) FROM imdb.movies UNION ALL SELECT 'imdb.people', count(*) FROM imdb.people UNION ALL SELECT 'imdb.credits', count(*) FROM imdb.credits UNION ALL SELECT 'flights.flights', count(*) FROM flights.flights UNION ALL SELECT 'taobao.ad_clicks', count(*) FROM taobao.ad_clicks;"
```

**三处数字必须一致**：Windows 文件行数、PostgreSQL `count(*)`、openGauss `count(*)`。一致才说明三方看的是同一份数据，对比才有意义。

**截图 ⑥**：三方 `count(*)` 的对照（可以做成一张小表截图）。

---

## E. DataGrip 配置

> 本节的小节编号是 **E-1 / E-2 / E-3 / E-4**（DataGrip 的配置步骤），与实验编号 **E1–E11** 无关，别混。

### E-1. 建 PostgreSQL 数据源

1. DataGrip 左侧 `Database` 面板 → `+` → `Data Source` → **PostgreSQL**
2. 填：

| 字段 | 值 |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | `bench` |
| User | `<PG_USER>` |
| Password | `<PG_PWD>` |

3. 点 **Test Connection**，出现绿色 `Succeeded` 才算成功
4. `Apply` → `OK`

### E-2. 建 openGauss 数据源

openGauss 用 PostgreSQL 的协议，所以**驱动选 PostgreSQL 类型、但把 jar 换成 openGauss 官方的**。

1. 下载 openGauss JDBC 驱动：华为官网 openGauss → "下载" → 选 5.0.0 → JDBC，文件名类似 `opengauss-jdbc-5.0.0.jar`
   把 jar 放到 `E:\DBMS-Project-Data\jdbc\`
2. DataGrip → `+` → `Data Source` → `PostgreSQL`
3. **Driver 那一栏点 `Go to Driver`** → 在 `Driver files` 里 `+` → 选 `opengauss-jdbc-5.0.0.jar` → 把 `postgresql` 那个 jar 移除
4. 回到数据源，填：

| 字段 | 值 |
|---|---|
| Host | `localhost` |
| Port | **`<OG_PORT>`**（不是 5432！） |
| Database | `bench`（或 `postgres`） |
| User | `omm`（有些镜像是 `gaussdb`） |
| Password | `<OG_PWD>` |

5. **Advanced 标签** → 加一个参数 `sslmode` = `disable`
6. Test Connection

> **报错 "FATAL: Forbid remote connection with initial user"** 或 **"Invalid username/password"**
> → openGauss 对密码强度和加密方式要求很严。在容器内用 gsql 重设一次：
> ```bash
> gsql -d postgres -p 5432 -c "ALTER USER omm IDENTIFIED BY 'Enmo@1234' REPLACE '<旧密码>';"
> ```
> 密码规则：**至少 8 位，且必须包含大写字母、小写字母、数字、特殊字符中的至少三类**，不能和用户名相同或倒序。
>
> **报错 "no pg_hba.conf entry for host"** → 容器需要允许远程连接。在容器内：
> ```bash
> gs_guc reload -D <OG_DATADIR> -h "host all all 0.0.0.0/0 sha256"
> ```

**截图 ⑦**：DataGrip 里两个数据源都连通的界面（能看到两棵表树）。

---

### E-3. 怎么看 SQL 执行耗时（**三种时间，含义不一样**）

| # | 在哪看 | 是什么时间 | 用在哪 |
|---|---|---|---|
| **1** | 结果面板**底部**的一行小字 `Executed in 123 ms` | **客户端端到端**：网络往返 + 服务端执行 + 结果传输 + 客户端渲染 | 报告里的 `T_client`，仅作参考 |
| **2** | 查询前加 `EXPLAIN (ANALYZE, BUFFERS)` | **服务端纯执行**：输出末尾的 `Execution Time: xx ms` | 报告里的 **`T_server`，这是主指标** |
| **3** | Java 程序里的 `System.nanoTime()` | Java 侧端到端（排除 JVM 启动） | 报告里的 `T_java`，文件侧和 DB 侧都测 |

**为什么它们不一样**（报告里必须解释这一点）：

- `T_client` 比 `T_server` 大，因为中间多了 JDBC 网络往返、结果集序列化传输、以及 DataGrip 把几万行渲染成表格的时间
- **查询返回 100 万行时，`T_client` 可能是 `T_server` 的几十倍**，差异几乎全在传输和渲染上
- **所以数据库之间的横向对比一律用 `T_server`**，`T_client` 只在"用户真实感受"这一节里单独讨论

**用 EXPLAIN 的正确姿势**（两边的写法**其实一样**，这点我核对过官方文档）：

```sql
-- PostgreSQL
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';

-- openGauss：完全相同
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';

-- 如果要显式写布尔值，openGauss 用的是 TRUE / FALSE，不是 ON / OFF
EXPLAIN (ANALYZE TRUE, BUFFERS TRUE) SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';
```

在 DataGrip 里执行后，结果面板会多出一个 **Plan** 页签，点开能看到树形执行计划；`Execution Time` 在文本输出的最后一行。

**要记录的几个数字**（报告 E1 表格用）：

| 字段 | 从哪读 |
|---|---|
| `Execution Time` | EXPLAIN 输出最后一行 |
| `Planning Time` | 倒数第二行 |
| `Scan` 类型 | Plan 里是 `Seq Scan` 还是 `Index Scan` |
| `shared hit` / `shared read` | `BUFFERS` 给出，命中缓冲池 vs 真的读磁盘 |
| 实际返回行数 | `rows=` 后面的数字 |

> **`EXPLAIN ANALYZE` 会真的执行语句，包括 UPDATE 和 DELETE！**
> 对 `UPDATE` 做 EXPLAIN ANALYZE 会**真的改数据**。openGauss 官方推荐的安全写法是把它包在事务里再回滚：
> ```sql
> START TRANSACTION;
> EXPLAIN ANALYZE UPDATE filmdb.people SET surname = replace(surname,'T','X') WHERE surname LIKE '%T%';
> ROLLBACK;    -- 计划已经量到了，数据改回来了
> ```
> 正式跑数据的那一次不能用这招，因为那一次就是要让改动生效。

**截图 ⑧**：一个带 EXPLAIN ANALYZE 的查询 + Plan 页签 + 底部的耗时。
**截图 ⑨**：openGauss 侧同样的查询（用于说明语法差异）。

---

### E-4. 导出结果

1. 结果集面板右上角有个**下载箭头图标** → `Export to File`
2. 格式选 `CSV`，勾上 `First row is header`
3. 存到 `E:\DBMS-Project-Data\results\`

**建议**：每个实验导出一份 CSV，文件名带实验号和平台，例如 `E1_search_og_index.csv`。报告里的表格直接从这些 CSV 汇总，避免手抄出错。

---

## F. Java 程序运行

### F1. 驱动 jar 从哪来

| 数据库 | jar | 来源 |
|---|---|---|
| PostgreSQL | `postgresql-42.2.5.jar` | 老师提供的 `code and data` 目录里已有 |
| openGauss | `opengauss-jdbc-5.0.0.jar` | 华为官网 openGauss 下载页 → 5.0.0 → JDBC |

把两个 jar 都放到 `E:\DBMS-Project-Data\jdbc\`：

```powershell
Copy-Item "C:\Users\HUAWEI\Desktop\code and data(1)\code and data\postgresql-42.2.5.jar" "E:\DBMS-Project-Data\jdbc\"
```

> **注意**：`postgresql-42.2.5.jar` 是 2018 年的老驱动，连 PostgreSQL 16 一般没问题；如果报 `Unsupported class file version` 之类，去 https://jdbc.postgresql.org/download/ 下一个 42.7.x。
> **openGauss 用 PostgreSQL 驱动也能连**，但拿不到 openGauss 特有的错误信息，建议用官方驱动。

### F2. 编译

```powershell
cd "E:\deepseek harness\database proj1\deliverables"

javac -encoding UTF-8 -cp "E:\DBMS-Project-Data\jdbc\postgresql-42.2.5.jar;E:\DBMS-Project-Data\jdbc\opengauss-jdbc-5.0.0.jar" MovieBench.java
```

**跑完会发生什么**：目录里多出一堆 `.class` 文件，没有任何输出就代表成功。

> **`-encoding UTF-8` 不能省**：源码里有中文注释，Windows 默认编码是 GBK，不加这个参数会报 "unmappable character"。
> **Windows 的 `-cp` 分隔符是分号 `;`**，Linux/Mac 是冒号 `:`。这是经典错误。
> **报错 "package xxx does not exist"** → 说明 jar 路径写错了，或者 jar 文件名不对。用 `dir E:\DBMS-Project-Data\jdbc` 确认。

### F3. 运行

约定接口：`MovieBench <目标> <模式> [参数]`

| 目标 | 含义 |
|---|---|
| `file` | 原生 CSV 文件程序（基线） |
| `pg` | 本机 PostgreSQL |
| `og` | openGauss 容器 |

```powershell
# 连接参数通过环境变量传，避免把密码写死在命令行
$env:MOVIEBENCH_PG_URL  = "jdbc:postgresql://localhost:5432/bench"
$env:MOVIEBENCH_PG_USER = "<PG_USER>"
$env:MOVIEBENCH_PG_PWD  = "<PG_PWD>"
$env:MOVIEBENCH_OG_URL  = "jdbc:opengauss://localhost:<OG_PORT>/bench"
$env:MOVIEBENCH_OG_USER = "omm"
$env:MOVIEBENCH_OG_PWD  = "<OG_PWD>"
$env:MOVIEBENCH_CSV     = "E:\DBMS-Project-Data\csv"
```

```powershell
# 完整的 java 命令模板（-cp 里必须同时有点号和两个 jar）
java -Xmx4g -cp ".;E:\DBMS-Project-Data\jdbc\postgresql-42.2.5.jar;E:\DBMS-Project-Data\jdbc\opengauss-jdbc-5.0.0.jar" MovieBench <目标> <模式> [参数]
```

> `-Xmx4g` 固定堆上限，保证文件侧实验不会因为 JVM 自动扩堆导致计时抖动。报告里要写明这个参数。
> `.` 表示当前目录（`MovieBench.class` 所在处），**不能漏**。

### F4. 每个实验的启动命令清单

下表省略了命令前缀 `java -Xmx4g -cp ".;<两个驱动 jar>"`，实际执行时按 F3 补上。

| 实验 | 目标 | 命令 |
|---|---|---|
| **E1** 关键词检索 | 文件 | `MovieBench file search love` ／ 中文：`MovieBench file search 爱 zh` |
| | PostgreSQL | `MovieBench pg search love` |
| | openGauss | `MovieBench og search love` |
| **E2** 变长更新 T→Ttt | 文件 | `MovieBench file update T Ttt` |
| | PostgreSQL | `MovieBench pg update T Ttt` |
| | openGauss | `MovieBench og update T Ttt` |
| **E2b** 等长更新 T→X | 文件 | `MovieBench file update T X` |
| **E3** 主键点查 | 文件 | `MovieBench file point 1234567` |
| | PostgreSQL | `MovieBench pg point 1234567` |
| | openGauss | `MovieBench og point 1234567` |
| **E4** 三表连接 JOIN | 文件 | `MovieBench file join 2015 D` |
| | PostgreSQL | `MovieBench pg join 2015 D` |
| | openGauss | `MovieBench og join 2015 D` |
| **E5** 聚合统计 | 文件 | `MovieBench file agg year` |
| | PostgreSQL | `MovieBench pg agg year` |
| | openGauss | `MovieBench og agg year` |
| **E6** 并发检索 | 文件 | `MovieBench file conc 32`（`32` 是线程数） |
| | PostgreSQL | `MovieBench pg conc 32` |
| | openGauss | `MovieBench og conc 32` |
| **E7** 存储布局与索引机制 | 文件（part 1） | `MovieBench file layout csv` ／ `layout fixed` ／ `layout mmap` ／ `layout invidx` |
| | openGauss（part 2 列存） | `MovieBench og agg airline --table flights_col` |
| **E8** 宽表聚合 | 文件 | `MovieBench file agg airline` ／ `agg cate` |
| | PostgreSQL | `MovieBench pg agg airline` ／ `agg cate` |
| | openGauss | `MovieBench og agg airline` ／ `agg cate` |
| **E9** 批量写入与事务大小 | 文件 | `MovieBench file batch 1000` |
| | PostgreSQL | `MovieBench pg batch 1000`（依次换成 1 / 100 / 1000 / 10000） |
| | openGauss | `MovieBench og batch 1000` |
| **E10** 表膨胀 | PostgreSQL | `MovieBench pg bloat 10`（10 轮） |
| | openGauss | `MovieBench og bloat 10` |
| **E11** 中断写入 | 文件 | `MovieBench file tx kill`（程序会自己等你去杀它） |
| | PostgreSQL | `MovieBench pg tx kill` |
| | openGauss | `MovieBench og tx kill` |

> 如果 `MovieBench.java` 里的参数名与上表不一致，**以源码顶部的 usage 注释为准**。上表是本项目约定的接口。

---

## G. 实验执行顺序

### G1. 总流程（每个数据库都要走一遍）

```
① 建表、导入、ANALYZE          ← init_*.sql 的 STEP 1–3
② 跑全部实验，记录"无索引"数据   ← 这一步最关键，别跳
③ 建索引                        ← init_*.sql 的 STEP 4
④ 跑同样全部实验，记录"有索引"数据
⑤ 把 ② 和 ④ 放进同一张表对比
```

**为什么要先测无索引**：很多人的结论是"数据库比文件快 100 倍"，但说不清为什么。先测无索引，你就会看到**数据库在小数据量上甚至输给文件程序**——这个反直觉的结果恰恰是最有价值的发现，它证明"数据库的优势来自索引、缓冲池、优化器这些机制，不是免费的"。

**怎么只执行一部分**：`init_pg.sql` 里 STEP 4 之前的内容是建表+导入+ANALYZE。用 DataGrip 打开，**选中 STEP 1 到 STEP 3 的行 → Ctrl+Enter 只执行选中部分**；实验做完再选中 STEP 4 执行。

> 但导入建议用 **psql 命令行**（D2）而不是 DataGrip，因为 DataGrip 对 psql 的 `\copy` 元命令支持不稳定。

### G2. 重复次数与缓存控制

| 项目 | 做法 | 理由 |
|---|---|---|
| 预热 | 先跑 **3 次**，结果丢掉 | JVM 的 JIT 编译和数据库的缓冲池都需要预热，第一次的数字没有代表性 |
| 正式 | 跑 **5 次**，取**中位数** | 中位数比平均值抗离群点 |
| 报告什么 | 中位数 + 最小值 + 最大值 | 只报一个数字会被质疑 |
| **热缓存** | 连续跑，中间不清缓存 | 代表"反复查同一批数据"的真实场景 |
| **冷缓存** | 每组开始前重启一次 | 代表"服务刚启动"的场景 |

**冷缓存具体怎么清**：

| 对象 | 怎么清 | 注意 |
|---|---|---|
| PostgreSQL 缓冲池 | `SELECT pg_stat_reset();` + 重启服务 `Restart-Service postgresql-x64-16` | 重启最彻底 |
| openGauss 缓冲池 | 重启容器 `docker restart <容器名>` | 重启后要等 30 秒再连 |
| **Windows 文件系统缓存** | **清不掉** | 这是文件侧实验的固有局限，必须在报告 Limitations 里写明 |

> **Windows 清不了文件缓存**这件事很重要。学长的报告里也提到"Windows 不支持缓存清除，必须重启"。**我们不做重启整机的操作**，而是在报告里说明：文件侧的"冷"数据通过"第一次打开文件"来近似，并在 Limitations 里承认这个偏差。

### G3. 每个实验的具体步骤

**E1 关键词检索**

1. 无索引状态下，对三个词各测一轮：`the`（高频）、`love`（中频）、`zorro`（低频）
2. 每个目标（file / pg / og）重复同样的三步：预热 3 次 → 正式 5 次 → 记中位数
3. 执行 STEP 4 建索引
4. 完全重复第 1–2 步
5. 单独测一次 `LIKE 'Love%'`（前缀查询），说明 B-tree 只对前缀有效
6. 单独测中文 `title LIKE '%爱%'`，说明 3 字节字符的影响

**E2 变长更新**

1. **先在 `filmdb.people`（1.6 万行）上练手**，别一上来就动 IMDb 的 1400 万行
2. 记下执行前的行数：`SELECT count(*) FROM filmdb.people WHERE first_name LIKE '%T%' OR surname LIKE '%T%';`
3. 先用 `START TRANSACTION; EXPLAIN ANALYZE UPDATE ...; ROLLBACK;` 演练一次，确认语句正确且量到了时间；再用 `EXPLAIN (ANALYZE, BUFFERS)` 正式跑一次（这次改动会保留）
4. **每次测量前必须恢复数据**（这条更新不是幂等的！`Ttt` 里还有 `T`）：
   ```sql
   -- 导入完立刻做一次备份
   CREATE TABLE IF NOT EXISTS imdb.people_backup AS SELECT * FROM imdb.people;
   -- 每次实验前恢复
   TRUNCATE imdb.people;
   INSERT INTO imdb.people SELECT * FROM imdb.people_backup;
   ```
5. 等长对照：跑 `replace(x, 'T', 'X')`，同样导出数据。这一步用来隔离"记录变长"这个变量

**E3 / E4 / E5** 同上：无索引一轮、有索引一轮，各 5 次取中位数。

**E6 并发**
1. 从 1 线程开始，逐步加到 8 / 32 / 128
2. 每个并发度跑 30 秒，记录**吞吐量（QPS）**和 **P95 延迟**
3. 记录过程中在两台数据库上跑 `SELECT count(*) FROM pg_stat_activity;`，看连接数
4. 跑完后重启一次数据库，再做冷启动下的同一组

**E7 存储布局与索引机制**

- **part 1（纯文件侧，没有 SQL）**：用 `MovieBench file layout csv|fixed|mmap|invidx` 跑四种布局，每种记录三组数字——**检索耗时、建索引耗时、文件占用**。这一步回答的是"数据库快，是因为它是数据库，还是因为它有索引"
- **part 2（行存 vs 列存，仅 openGauss）**：
  1. 在行存表上跑聚合，记录 `Execution Time` 和 `shared read`
  2. 在列存表上跑同一条 SQL，记录同样的指标
  3. 同时记录 `pg_relation_size()` 的两组数字
  4. **不要把列存表的数字混进行存表那一列**，报告里分成两行
  5. 顺带说明一句：openGauss 列存表**是支持主键和唯一约束的**（只是不支持外键）。我们的列存表刻意不加约束，是为了让 E7 量到纯粹的扫描/聚合代价，而不是被索引干扰

**E8 宽表聚合（航班 / 淘宝）**
1. 三条聚合 SQL（航班按航司、航班按州、淘宝按品类）分别跑 file / pg / og
2. 每条同时记录服务端 `Execution Time`、`shared read` 和返回行数
3. 航班按航司、淘宝按品类这两条在 `init_og.sql` 的 E7（part 2）里还要对列存表再跑一遍，**行存/列存两组数字必须并排放在报告里**

**E9 批量写入与事务大小**
1. 先清空暂存表：`TRUNCATE bench.batch_target;`
2. 依次跑 `MovieBench pg batch 1` / `100` / `1000` / `10000`，每个批量跑完都确认写入行数**完全一致**（不一致就是在比不同的工作量）
3. 记录 **TPS** 和总墙钟时间，画"批量大小 vs TPS"曲线
4. 再做一轮 `SET synchronous_commit = off;` 的对照，看"用持久性换吞吐量"值多少钱，**报告里必须标明哪一轮关掉了持久性**
5. 文件侧跑 `MovieBench file batch <同样的批量大小>`：文件没有"事务"这个概念，flush 频率只影响系统调用次数，**这个不对称本身就是结论**

**E10 表膨胀**
1. 记录初始大小：`SELECT pg_relation_size('imdb.people'), pg_relation_size('imdb.people_ustore');`
2. 用 `init_og.sql` 第 E10 节的 toggling 语句跑 10 轮
3. 再记录一次大小，画"轮次 vs 表大小"曲线
4. 回收空间并计时（两种写法都行，关键字写法在所有版本上都能用）：
   ```sql
   VACUUM FULL ANALYZE imdb.people;
   VACUUM FULL ANALYZE imdb.people_ustore;
   ```
5. 比较 heap 表和 ustore 表的膨胀曲线

**E11 中断写入**
1. 先跑文件侧：启动 `MovieBench file tx kill`，等它打印 "now rewriting, kill me"，立刻在另一个窗口：
   ```powershell
   Get-Process java | Stop-Process -Force
   ```
2. 检查 CSV：应该被截断，或者新旧文件都在，数据处于不一致状态。**截图**
3. 再跑数据库侧：`MovieBench pg tx kill`，同样强杀
4. 重连数据库：`SELECT count(*) FROM imdb.people WHERE peopleid <= 100000 AND surname LIKE '%Ttt%';` → **应该是 0**
5. **注意：杀的是 Java 客户端进程，不是数据库进程。数据库不会受损。**

> **先验证 ustore 真的建成了**：
> ```sql
> SELECT relname, reloptions FROM pg_class WHERE relname = 'people_ustore';
> ```
> 如果 `reloptions` 是空的，说明你这个构建不支持 ustore，那么在报告里如实记录，并退化为"膨胀前 / VACUUM 后"的对比。

---

## H. 结果怎么填进报告

每个实验导出一份 CSV 存到 `E:\DBMS-Project-Data\results\`，然后填进报告里对应编号的表格：

| 报告表格 | 内容 | 数据来源 |
|---|---|---|
| **表 1** | 实验环境（CPU / 内存 / 磁盘 / 三方版本号 / 关键参数） | A1、A2 的截图 |
| **表 2** | 数据集规模（4 份数据的行数、列数、磁盘占用） | B2 输出 + `count(*)` |
| **表 3** | **E1 检索**：3 个词 × 3 个目标 × 有/无索引（含中文字符） | `E1_*.csv` |
| **表 4** | **E2 更新**：变长 vs 等长，3 个目标 | `E2_*.csv` |
| **表 5** | **E3 点查**：有索引 vs 无索引 | `E3_*.csv` |
| **表 6** | **E4 JOIN**：两表 vs 三表，有/无索引 | `E4_*.csv` |
| **表 7** | **E5 聚合**：3 条聚合 SQL × 3 个目标 | `E5_*.csv` |
| **表 8** | **E6 并发**：并发度 × 吞吐量 × P95 | `E6_*.csv` |
| **表 9** | **E7 存储布局与索引机制**：四种文件布局 + 行存/列存（时间和空间两组） | `E7_*.csv` |
| **表 10** | **E8 宽表聚合**：航班 / 淘宝 × 行存/列存 × 3 个目标 | `E8_*.csv` |
| **表 11** | **E9 批量写入**：批量 1/100/1000/10000 的 TPS（含 synchronous_commit 对照） | `E9_*.csv` |
| **表 12** | **E10 表膨胀**：轮次 vs 大小，heap vs ustore | `E10_*.csv` |
| **表 13** | **E11 原子性**：中断后三方的数据完整状态 | 截图 + 查询结果 |
| **表 14** | **综合裁决**：5 个标准下 PG 与 openGauss 的胜负 | 由表 3–13 汇总 |

**填表四条铁律**：

1. **只填真实跑出来的数字**，一个都不要编
2. 每个格子填**中位数**，脚注写清重复次数和是否冷/热缓存
3. 单位统一用 **ms**，超过 1000 ms 的就写成 **s**，同一张表内不要混用
4. 数字后面标**加速比**（例如"文件 1200 ms / DB 40 ms = 30×"），这比裸数字有说服力

---

## I. 截图清单（19 条）

按顺序做，一条一张图。存到 `E:\DBMS-Project-Data\results\shots\`，命名 `01_xxx.png`。

| # | 截图内容 | 在哪个界面 | 报告里用来说明什么 |
|---|---|---|---|
| 1 | `psql --version` + `Get-Service *postgres*` | PowerShell | 本机 PostgreSQL 版本与服务状态 |
| 2 | `docker ps` 输出 | PowerShell | openGauss 容器在运行、端口映射 |
| 3 | 容器内 `SELECT version();` | 容器内 gsql | openGauss 版本 = 5.0.0 |
| 4 | `prepare_data.py --all` 的完整输出 | PowerShell | 每份数据的真实行数 |
| 5 | openGauss 导入完成后的 `count(*)` | 容器内 gsql | 导入完整性 |
| 6 | PostgreSQL 导入完成后的 `count(*)` | psql | 三方行数一致，证明数据同源 |
| 7 | **两个数据源都连通的 DataGrip 界面** | DataGrip | 实验环境 |
| 8 | **一条带 `EXPLAIN ANALYZE` 的查询 + Plan 页签 + 底部耗时** | DataGrip | 计时方法（服务端 vs 客户端） |
| 9 | openGauss 侧同一条 SQL 的执行计划 + 底部耗时 | DataGrip | 同一个查询在两个 DBMS 上的计划差异（扫描方式、并行度、代价估算） |
| 10 | **`Seq Scan` 的执行计划** | DataGrip Plan 页签 | E1 无索引基线，**必须有的图** |
| 11 | **`Index Scan` 的执行计划** | DataGrip Plan 页签 | E1 建索引后，**与图 10 形成对照** |
| 12 | 列存表的建表语句 + `\d+` 输出 | gsql | E7，openGauss 的特化能力 |
| 13 | 行存与列存同一聚合查询的执行计划并排 | DataGrip | E7 核心证据 |
| 14 | `pg_relation_size` 行存/列存/索引的体积对照 | DataGrip | 空间维度的权衡 |
| 15 | **文件程序中途被 kill 后残缺的 CSV** | 记事本或 `Get-Content` 末尾 | E11，文件系统的原子性缺陷，**最有冲击力的一张** |
| 16 | E11 数据库侧事务回滚后 `count(*) = 0` | DataGrip | E11，与图 15 对照 |
| 17 | E10 膨胀前后的 `pg_relation_size` 与 `n_dead_tup` | DataGrip | 表膨胀证据 |
| 18 | `query_dop` 或 `enable_thread_pool` 的设置与生效 | gsql | 说明并行/线程池参数如何影响公平性 |
| 19 | **批量大小 vs TPS 的曲线** + `bench.batch_target` 的体积 | DataGrip / 自绘 | E9，"事务大小换吞吐量"的证据 |

**另外建议补 2 张图（不是截图，是自己画）**：

- **耗时-数据量曲线图**（S0→S3 四档 × 3 个目标），这张图直接支撑"谁的增长曲线更平"这个核心论点
- **DBMS 架构图标注图**：在架构图上标出每个实验打在哪一层（缓冲池 / 索引 / 优化器 / 存储引擎 / 事务），让读者一眼看懂实验布局。学长那份报告做过类似的图，是加分项

---

## J. 常见错误排查

### J1. 编码问题

| 现象 | 原因 | 解决 |
|---|---|---|
| 中文变成 `????` 或乱码 | 客户端编码不是 UTF8 | `psql -c "SHOW client_encoding;"`，不是 UTF8 就先 `SET client_encoding='UTF8';` |
| `javac` 报 "unmappable character" | 源码有中文注释且没指定编码 | 编译时加 `-encoding UTF-8` |
| `java` 输出中文乱码 | Windows 控制台默认 GBK | 运行时加 `-Dfile.encoding=UTF-8`，或先执行 `chcp 65001` |
| TSV 里出现乱码 | 源文件带了 BOM | 用 `prepare_data.py` 重新生成（脚本已处理）；手工文件用记事本"另存为 UTF-8 无 BOM" |

### J2. 路径问题（**含空格的路径是重灾区**）

本项目的路径里确实有空格：`E:\deepseek harness\database proj1\`。规则：

| 场景 | 写法 |
|---|---|
| PowerShell 里传给 docker / java | **双引号包住**：`docker cp "E:\deepseek harness\..." ...` |
| Java 的 `-cp` | 整个参数用双引号包住，里面用 `;` 分隔 |
| SQL 里的 `\copy` 路径 | **正斜杠**，且不要有空格最稳：`/tmp/csv/...` |
| DataGrip 的 CSV 导出 | 用 `E:\DBMS-Project-Data\results\`（无空格） |

> **建议**：所有数据、结果、jar 都放在 `E:\DBMS-Project-Data\`（无空格、无中文），只把源码留在原来的工作目录。这样 90% 的路径问题自动消失。

### J3. openGauss 密码问题

| 报错 | 解决 |
|---|---|
| `Invalid username/password` 但密码没错 | openGauss 的密码加密方式和旧驱动不兼容。重设密码：`ALTER USER omm IDENTIFIED BY 'Enmo@1234' REPLACE '<旧密码>';` |
| `Password must contain at least three kinds of characters` | 密码要含大写、小写、数字、特殊字符中的**至少三类**，且 ≥ 8 位 |
| `Forbid remote connection with initial user` | 不能用初始用户远程连接，改用 `omm`，或修改 `pg_hba.conf` |
| 连接超时 | 端口填错了。`docker port <容器名>` 再看一次 |

### J4. 容器内存/磁盘导致 COPY 回滚

**现象**：`\copy` 跑到一半报错，或者容器直接消失（`docker ps` 里没了）。

**原因**：默认容器内存太小，导入大表时 OOM。

**处理**：

```powershell
# 看容器资源
docker stats <容器名> --no-stream

# 看磁盘
docker exec <容器名> df -h /tmp
```

**预防**：

1. 重建容器时给足内存：`docker run ... -m 8g ...`
2. Docker Desktop → Settings → Resources → Memory 调到 **8 GB 以上**（WSL2 后端）
3. 分批导入：先只导 `imdb.movies` 和 `imdb.people`，`imdb.credits` 那 3000 万行单独导
4. 导入前在 gsql 里临时放宽内存参数：
   ```sql
   SET maintenance_work_mem = '1GB';
   SET max_process_memory = '6GB';   -- openGauss 专用，需要重启才生效的用 gs_guc
   ```

### J5. DataGrip 大结果集卡死

| 现象 | 解决 |
|---|---|
| 查一张 3000 万行的表，DataGrip 转圈几十分钟 | 在查询末尾加 `LIMIT 1000`；要行数就写 `SELECT count(*)`，**不要 `SELECT *`** |
| 结果集太大把内存吃光 | DataGrip → Settings → Database → Data Editor → 把 `Page size` 调小（如 500），并关闭自动获取全部行 |
| Plan 页签一直不出来 | 把 `EXPLAIN` 换成 `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)`，先看文本输出 |
| 执行 EXPLAIN ANALYZE 后数据被改了 | 对 UPDATE/DELETE 做 EXPLAIN ANALYZE **会真的执行**。先用 `BEGIN; ... ROLLBACK;` 包起来 |

### J6. 索引没生效

**现象**：建了索引，执行计划还是 `Seq Scan`，耗时没变。

**这是正常的，而且是有价值的发现**，不是 bug。原因：

1. **`LIKE '%word%'` 的前导通配符用不上 B-tree** —— 必须从左往右匹配才能用索引。这一条要写进报告
2. 表太小的时候优化器觉得全表扫描更便宜（这是对的）
3. 统计信息过期：跑一次 `ANALYZE 表名;`
4. 列上没有索引，或者索引建在别的列上：`\d+ 表名` 看

**排查顺序**：
```sql
ANALYZE imdb.movies;                                 -- 1. 更新统计
EXPLAIN (ANALYZE, BUFFERS) SELECT ... ;              -- 2. 看计划（两边写法一样）
\d+ imdb.movies;                                     -- 3. 确认索引存在
SET enable_seqscan = off;                            -- 4. 强制走索引，看耗时差多少（只用于诊断）
```

> 第 4 条是诊断手段，**不能用于正式实验数据**。报告里要写明正式测量时用的是默认设置。

### J7. 三方数字对不上

**现象**：Windows 文件行数 ≠ PostgreSQL `count(*)` ≠ openGauss `count(*)`。

**排查**：

1. 检查 TSV 里有没有半行（最后一行缺列）：`Get-Content xxx.tsv -Tail 1`
2. 检查有没有字段里带 Tab 或换行（`prepare_data.py` 已经做了替换，手工改动过的文件要重新检查）
3. 检查空值是不是 `\N`：`Get-Content xxx.tsv -TotalCount 5`
4. 检查 `\copy` 有没有报 `ERROR` 但被后面大量的 `COPY n` 刷掉了 —— 看日志文件 `init_pg.log` / `/tmp/init_og.log`，搜索 `ERROR`

---

## 附：一次性检查清单

做完每个阶段，回来打勾：

- [ ] A. 本机 PostgreSQL 能连、版本已记录
- [ ] A. openGauss 容器能连、端口已记录
- [ ] B. `E:\DBMS-Project-Data\csv\` 五个目录都生成
- [ ] B. 三处行数一致（文件 / PG / openGauss）
- [ ] C/D. 两个库都导入完成、`ANALYZE` 已跑
- [ ] E. DataGrip 两个数据源都绿色
- [ ] F. `javac` 无报错，`java file search love` 能出结果
- [ ] G. **无索引**基线数据已全部记录
- [ ] G. 索引已建
- [ ] G. **有索引**数据已全部记录
- [ ] G. E7 part 2 列存 / E10 ustore 已验证生效（或如实记录不支持）
- [ ] G. E9 的暂存表已建好（`\dt bench.*` 能看到 `batch_target`）
- [ ] H. 14 张表格的数据都齐了
- [ ] I. 19 张截图都存好了
- [ ] 报告里所有数字都来自真实运行，没有一个编造
