# 数据库原理期末复习总结（持续更新）

> 课程：CS213 Principles of Database Systems (H)  
> 本次范围：Chapter 1–6，6 份课件、453 页  
> 更新日期：2026-10-21  
> 目标：先理解“为什么”，再会写 SQL，最后能检查答案有没有悄悄出错。

这份笔记按已有课件整理，不代表老师公布的完整期末考纲。课件没有展开的事务、索引实现、恢复、并发控制等内容，等后续课件更新后再补。文中“补充”用于解释课件省略的细节，不代表老师已经讲过。SQL 例子统一使用下面的电影数据库；一般查询尽量采用通用写法，日期例子采用 PostgreSQL 风格并标明差异。

## 目录

- [0. 怎么使用这份笔记](#0-怎么使用这份笔记)
- [1. 数据库、关系、键与建模](#1-数据库关系键与建模)
- [2. SQL 建表、类型、约束与插入](#2-sql-建表类型约束与插入)
- [3. 单表查询、日期、NULL 与 CASE](#3-单表查询日期null-与-case)
- [4. 去重、聚合、分组与内连接](#4-去重聚合分组与内连接)
- [5. 外连接、集合运算与子查询](#5-外连接集合运算与子查询)
- [6. 排序、分页、层次结构与窗口函数](#6-排序分页层次结构与窗口函数)
- [7. 做 SQL 题的固定步骤](#7-做-sql-题的固定步骤)
- [8. 自测题与参考答案](#8-自测题与参考答案)
- [9. 考前速查与复习安排](#9-考前速查与复习安排)
- [10. 课件索引、勘误与更新记录](#10-课件索引勘误与更新记录)

## 0. 怎么使用这份笔记

第一遍按 1–6 章阅读，遇到 SQL 先用中文说出“一行代表什么”。第二遍重点看 NULL、连接后的行数、外连接条件位置、窗口函数的筛选时机。第三遍盖住答案做第 8 节。

**整门课目前的一条主线：把事实存清楚，再把查询结果的含义算清楚。**

课件反复提醒的两个问题是：有没有重复？有没有 NULL？很多 SQL 能正常执行，却在这两处算错。

### 全文统一的四张表

```text
countries(country_code, country_name, continent)
movies(movieid, title, country, year_released)
people(peopleid, first_name, surname, born, died)
credits(movieid, peopleid, credited_as)
```

| 表 | 一行表示什么 | 主键 |
| --- | --- | --- |
| countries | 一个国家/地区代码及其说明 | country_code |
| movies | 一部电影 | movieid |
| people | 一个人 | peopleid |
| credits | 某人在某电影中担任某种身份 | (movieid, peopleid, credited_as) |

`movies.country` 引用 `countries.country_code`。`credits.movieid`、`credits.peopleid` 分别引用电影和人物。`credited_as='A'` 表示演员，`'D'` 表示导演，后面可扩展 `'P'` 表示制片人。`born`、`died` 在课件里存年份，不是完整日期。

为便于课堂练习，本模型一部电影只记录一个国家/地区。现实中的合拍片、多地上映日期、不同剪辑版本需要继续拆表，不能指望一个字段解决所有业务。

## 1. 数据库、关系、键与建模

### 1.1 数据库为什么比一堆文件方便

数据库（Database）是有组织、相互关联的数据集合。DBMS 是负责存储、查询、控制访问等工作的软件。应用程序通过 DBMS 使用数据。

如果每个程序各自维护文件，容易出现：同一个人信息重复存、改了一处漏改另一处；查询时要临时写程序；数据散在不同文件；非法数据混进去；转账只完成一半；多人同时修改互相覆盖；权限难统一控制。

数据库把这些共性工作集中管理。课件这里只引出这些问题，事务和并发机制尚未展开。

### 1.2 关系模型与 SQL 表

- 表的一行叫记录、元组（tuple），一列叫属性（attribute）。
- 关系模式（schema）描述有哪些列、列是什么类型、有什么约束；表里当前的数据是一个实例。
- 数学上的关系是集合，元组不能重复，不依赖行顺序。
- SQL 默认允许重复结果，是“保留重复的集合”（bag/multiset）语义。要靠键、约束以及必要时的 `DISTINCT` 控制重复。
- 没有 `ORDER BY`，返回顺序没有保证。列通过名字表达含义，但 SQL 的插入列表和集合运算仍然需要按位置对应。

关系代数里的 selection 是**选行**，projection 是**选列**，join 是把相关记录连接起来。SQL 的 `SELECT` 语句能同时承担选列、过滤、连接等工作，别把两种语境的 select 混为一谈。

### 1.3 各种键，最重要的是“唯一且尽量少”

| 名词 | 通俗理解 | 例子 |
| --- | --- | --- |
| 超键 superkey | 足以唯一确定一行的一组列，允许多带列 | (movieid, title) |
| 候选键 candidate key | 唯一确定一行，而且去掉任何一列就不够了 | movieid |
| 主键 primary key | 从候选键中选定的主要标识 | movieid |
| 复合键 composite key | 由多列组成的键 | credits 的三列 |
| 外键 foreign key | 保证这里引用的对象在另一张表中存在 | credits.movieid |

候选键要符合业务长期规则，不能因为当前样本碰巧没重复就认定为键。电影会重拍，标题未必唯一；同一导演也可能重拍自己的电影。课件采用 `(title, country, year_released)` 的唯一性属于模型假设，不能推广成现实世界定律。

数字 ID 便于引用，但加了 ID 不会自动防止“同一部电影被换个 ID 重复录入”。确实成立的业务唯一规则还应另加 `UNIQUE`。

**为什么 credits 主键要带身份？** 同一个人可以既导演又出演同一部电影。若只用 `(movieid, peopleid)`，第二条身份就存不下。

### 1.4 一对多、多对多与中间表

一个国家/地区可对应多部电影，一部电影在简化模型中属于一个国家/地区，这是“一对多”。一部电影可有多人参与，一个人可参与多部电影，这是“多对多”。

多对多用中间表 `credits`。不要设计 `actor1, actor2, actor3`：人数没法预先定死，查演员时要检查多列，扩展还要改表。

```text
countries 1 —— 多 movies
movies    1 —— 多 credits
people    1 —— 多 credits
```

E/R 图表达实体、联系和基数。实体有自己的身份，例如电影、人物；联系表达实体如何关联，例如某人出演某电影。还要问“可以一个都没有吗”：电影可能暂未录入任何演职员，国家表也可能有暂未收录电影的国家。**最小数量 0/1 和最大数量 1/多要分别看。** 不同图法符号不同，要看图例，别只背一个 `(m,n)`。

演员和导演应共用 `people`，身份写在 `credits`。如果分成两个独立人物表，同一个人的生日就可能改了一处、漏改另一处。

### 1.5 规范化：避免同一事实重复保存

先理解三种异常：

| 异常 | 例子 |
| --- | --- |
| 更新异常 | 某人的出生年在十部电影记录中出现，修改时漏改一条 |
| 插入异常 | 没有电影时，无法单独保存一个新人物 |
| 删除异常 | 删除某人唯一一部电影，连这个人的信息也一起丢失 |

**函数依赖 `X → Y`：相同的 X 必须对应相同的 Y。** 例如 `peopleid → surname, born`。这是业务规则，不是看两行数据猜规律。

**1NF：一个格子存当前模型中的一个值。** 不要把演员列表写成 `'张三,李四,王五'`。这里“不可再分”取决于用途：姓名是否拆姓和名、地址是否拆省市，都应由业务决定；统一大小写是数据规范，不能直接当作 1NF 的数学定义。

**2NF：在 1NF 上，非主属性不能只依赖某个候选键的一部分。**

```text
Enrollment(studentid, courseid, student_name, course_name, grade)
候选键：(studentid, courseid)
studentid → student_name
courseid  → course_name
(studentid, courseid) → grade
```

学生姓名只需要学号就能确定，没必要每选一门课重复存一次。拆成：

```text
Students(studentid, student_name)
Courses(courseid, course_name)
Enrollment(studentid, courseid, grade)
```

**3NF：进一步处理非键属性之间的传递依赖。**

```text
Movies(movieid, title, country_code, country_name)
movieid → country_code → country_name
```

把国家名称放进 `Countries(country_code, country_name)`，电影只存代码。

课件口诀：非键属性描述“键、整个键、仅仅是键”。用于理解很有效。补充严谨定义：对于非平凡依赖 `X → A`，3NF 要求 X 是超键，或者 A 是主属性（属于某个候选键）。有多个候选键时，要依据全部候选键分析，不能只盯选定主键。

**拆表之后要能正确连回来。** 规范化不是表越多越好，也不是统一字母大小写就完成了。考试答题应写清依赖、异常、如何分解及分解后如何关联。

## 2. SQL 建表、类型、约束与插入

### 2.1 DDL 与 DML

| 类别 | 作用 | 常见命令 |
| --- | --- | --- |
| DDL | 定义和改变结构 | CREATE、ALTER、DROP |
| DML | 查询和修改数据 | SELECT、INSERT、UPDATE、DELETE |

有的教材把 SELECT 单列为 DQL，按老师分类作答即可。`DROP TABLE` 删除整张表的结构和数据；`DELETE FROM` 删除表中的行。

SQL 关键字通常不区分大小写。表名、列名、字符串比较是否区分大小写，受产品、引号规则、排序规则等影响，不能一概而论。例子使用小写下划线标识符，字符串统一用单引号。

### 2.2 类型怎么选

| 类型 | 理解 | 注意 |
| --- | --- | --- |
| CHAR(n) | 定长字符串 | 适合固定代码，可能补空格 |
| VARCHAR(n) | 变长字符串 | 人名、标题等 |
| INT | 整数 | ID、数量 |
| NUMERIC(p,s) / DECIMAL(p,s) | 精确小数 | p 为总位数，s 为小数位数 |
| FLOAT | 近似数 | 不适合要求精确的金额计算 |
| DATE / TIMESTAMP | 日期、日期时间 | 各 DBMS 的具体语义有差异 |
| CLOB / TEXT | 大文本 | 名称和限制因产品而异 |
| BLOB / BYTEA 等 | 二进制数据 | 图片、文件等 |

`NUMERIC(6,2)` 是总共 6 位、其中 2 位小数，例如 `1234.56`。年龄会随时间变化，通常存出生日期再算年龄。课件只存出生年，用今年减出生年得到的是按年份估算的年龄。

### 2.3 约束：把规则写进数据库

| 约束 | 保证什么 | 误区 |
| --- | --- | --- |
| NOT NULL | 必须有值 | 不保证字符串不是空串 |
| PRIMARY KEY | 标识不能重复且不能为 NULL | 一张表一个主键，但主键可有多列 |
| UNIQUE | 某列或列组合不重复 | 含 NULL 时的唯一性规则因产品而异 |
| CHECK | 数据符合条件 | 不能靠它代替必要的 NOT NULL |
| FOREIGN KEY | 引用对象存在 | 外键列可以重复，也不自动变成必填 |

下面是全文使用的教学建表结构。姓名不作为唯一约束，因为现实中存在同名人物；这与课件针对电影人物做的简化假设有所区别。

```sql
CREATE TABLE countries (
    country_code CHAR(2) PRIMARY KEY,
    country_name VARCHAR(60) NOT NULL UNIQUE,
    continent VARCHAR(30) NOT NULL
);

CREATE TABLE movies (
    movieid INT PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    country CHAR(2) NOT NULL,
    year_released INT NOT NULL,
    CONSTRAINT movies_year_ck CHECK (year_released >= 1895),
    CONSTRAINT movies_business_uk UNIQUE (title, country, year_released),
    CONSTRAINT movies_country_fk FOREIGN KEY (country)
        REFERENCES countries(country_code)
);

CREATE TABLE people (
    peopleid INT PRIMARY KEY,
    first_name VARCHAR(40),
    surname VARCHAR(60) NOT NULL,
    born INT NOT NULL,
    died INT,
    CHECK (died IS NULL OR died >= born)
);

CREATE TABLE credits (
    movieid INT NOT NULL,
    peopleid INT NOT NULL,
    credited_as CHAR(1) NOT NULL,
    PRIMARY KEY (movieid, peopleid, credited_as),
    FOREIGN KEY (movieid) REFERENCES movies(movieid),
    FOREIGN KEY (peopleid) REFERENCES people(peopleid),
    CHECK (credited_as IN ('A', 'D', 'P'))
);
```

先建被引用的表，再建引用它的表。插入时同理：先有电影和人物，才能插入演职记录。

外键通常引用主键或满足引用要求的唯一键。默认情况下，仍被引用的父记录不能随便删除。若显式设置 `ON DELETE CASCADE`，删除会向子表传播；`SET NULL` 则要求子列允许为空。看题目指定的规则，不要默认“一定级联删除”。

补充：`CHECK (born > 0)` 通常允许 born 为 NULL，因为结果 UNKNOWN 不算违反 CHECK；若必须填写，还要 `NOT NULL`。约束只保证声明的规则，不保证姓名、年份等资料一定符合现实。

### 2.4 INSERT 与基本修改

```sql
INSERT INTO countries (country_code, country_name, continent)
VALUES ('it', 'Italy', 'EUROPE');

INSERT INTO movies (movieid, title, country, year_released)
VALUES (123, 'L''Avventura', 'it', 1960);
```

列和值一一对应。字符串里的单引号写两次，实际存一个。省略列时，数据库通常使用默认值，没有默认值则尝试 NULL；因此必填列如果有可用默认值，也可以省略。

课件介绍了 UPDATE、DELETE 的用途，以下只补最基本写法：

```sql
UPDATE people SET surname = 'Welles' WHERE peopleid = 2;
DELETE FROM credits WHERE movieid = 123;
ALTER TABLE movies ADD COLUMN note VARCHAR(200);
```

执行修改前先用同样的 WHERE 做 SELECT，确认会影响哪些行。没有 WHERE 的 UPDATE/DELETE 通常会影响全表。

## 3. 单表查询、日期、NULL 与 CASE

### 3.1 SELECT / FROM / WHERE

```sql
SELECT movieid, title, year_released
FROM movies
WHERE country IN ('us', 'gb')
  AND year_released BETWEEN 1940 AND 1949;
```

FROM 找数据来源，WHERE 筛行，SELECT 决定返回什么。程序里尽量写明列名，避免 `SELECT *` 因表结构变化多取数据或破坏依赖列位置的代码。

`BETWEEN a AND b` 包含两端，相当于 `>=a AND <=b`，不会自动替你调换上下界。

`AND` 优先于 `OR`，`NOT` 优先于 AND。想查“40 年代的美国或英国电影”，要写 `(country='us' OR country='gb') AND ...`。若不加括号，可能变成“所有美国电影，加上 40 年代英国电影”。复杂条件主动加括号。

### 3.2 LIKE 与类型转换

- `%`：零个或任意多个字符。
- `_`：恰好一个字符。
- `LIKE 'A%'`：A 开头；`LIKE '%A%'`：包含 A。
- LIKE 不是完整正则表达式。匹配字面量 `%`、`_` 时需用适用产品的转义方式。

数字 `2 < 10`，字符串 `'2'` 和 `'10'` 则按文本比较。不要把日期格式化成字符串后再做时间比较。

```sql
SELECT CAST(year_released AS VARCHAR(10)) AS release_text
FROM movies;
```

`CAST` 显式转换类型。尽量避免依赖隐式转换，尤其是比较条件。把函数施加到筛选列上可能妨碍普通索引的使用，但表达式索引和优化器重写会影响具体结果，不能断言一定全表扫描。

### 3.3 日期区间：左闭右开最省心

以下假设有 `forum_posts.post_date` 日期时间列，采用 PostgreSQL 风格日期字面量：

```sql
SELECT postid, post_date
FROM forum_posts
WHERE post_date >= DATE '2026-03-12'
  AND post_date < DATE '2026-03-17';
```

查的是 3 月 12 日到 16 日全天。若上界写 `<= DATE '2026-03-16'`，通常只包含 16 日零点，漏掉白天。写 `23:59:59` 又可能漏掉带小数秒的记录。

日期显示格式和日期内部值是两件事。`03/04/2026` 有地区歧义。输入时用明确日期格式、类型转换或参数，别依赖机器默认设置。Oracle 的 `TO_DATE`、SQL Server 的 `DATEADD`、MySQL 的 `DATE_ADD` 等语法不同，不能混写。加一个月也不等于加 30 天。

### 3.4 NULL：未知不等于 0 或空字符串

NULL 表示缺失、不知道或不适用。普通比较遇到 NULL 通常得到 UNKNOWN。

```sql
-- 错：无法判断“等于一个未知值”
SELECT peopleid FROM people WHERE died = NULL;
-- 对：判断是否缺失
SELECT peopleid FROM people WHERE died IS NULL;
```

`died <> NULL` 同样不会筛出你想要的数据。WHERE 只保留 TRUE，FALSE 和 UNKNOWN 都丢弃。

| 表达式 | 结果 |
| --- | --- |
| TRUE AND UNKNOWN | UNKNOWN |
| FALSE AND UNKNOWN | FALSE |
| TRUE OR UNKNOWN | TRUE |
| FALSE OR UNKNOWN | UNKNOWN |
| NOT UNKNOWN | UNKNOWN |

`NULL = NULL` 仍是 UNKNOWN。`NOT(x=5)` 也不会把 x 为 NULL 的行变成 TRUE。NULL 参与通常的算术运算，结果一般仍为 NULL。

```sql
SELECT surname, COALESCE(first_name, '(未记录名字)') AS given_name
FROM people;
```

COALESCE 返回从左向右第一个非 NULL 的参数。把缺失数量显示为 0 很常见，但“资料未知”和“实际为零”是不同含义，替换前要想清楚。

课件把 `died IS NULL` 用作“在世”的条件，这是数据模型约定。现实中它也可能只是没有录入死亡信息。

### 3.5 函数与 CASE

常见标量函数：`UPPER/LOWER` 改大小写，`TRIM` 去两端空白，`REPLACE` 替换文本，`SUBSTR/SUBSTRING` 截取，`ROUND` 四舍五入，`FLOOR/CEILING` 向下/向上取整。函数名称、参数和字符串拼接写法因 DBMS 而异。课件的 `TRUNC` 表示截断，尤其别把负数截断和向下取整混淆。

CASE 有两种常见形式：

```sql
SELECT movieid,
       CASE country
           WHEN 'us' THEN '美国'
           WHEN 'gb' THEN '英国'
           ELSE '其他'
       END AS country_label
FROM movies;

SELECT peopleid, surname,
       CASE
           WHEN died IS NULL THEN '未记录死亡年份'
           WHEN died - born >= 80 THEN '寿命至少80个年份差'
           ELSE '其他'
       END AS status
FROM people;
```

第一种用等值匹配，第二种逐个判断条件。匹配到第一个 TRUE 分支就使用它。没有匹配且省略 ELSE 时返回 NULL。`CASE died WHEN NULL THEN ...` 无法判断缺失，应写 `WHEN died IS NULL`。

## 4. 去重、聚合、分组与内连接

### 4.1 DISTINCT 到底对什么去重

```sql
SELECT DISTINCT country, year_released FROM movies;
```

它对**整组输出列**去重，因此一行代表“一个国家/地区在一个年份出现过电影”。不是 country、year_released 各自单独去重。

先问“一行是什么”，再决定去重。列出导演名单，可能要一人一行；列出演职记录，则一个人出现很多次本来就合理。两个人同名时，只返回姓名再 DISTINCT 会合并两个人，最好保留 peopleid。

### 4.2 GROUP BY：把多行压成一组一行

```sql
SELECT country, COUNT(*) AS movie_count,
       MIN(year_released) AS earliest_year,
       MAX(year_released) AS latest_year
FROM movies
GROUP BY country;
```

结果每个国家一行。`GROUP BY country, year_released` 则每个“国家+年份”一行。通用考试写法：SELECT 中非聚合的列放进 GROUP BY，不要依赖某些产品允许的宽松规则。

| 写法 | 含义 |
| --- | --- |
| COUNT(*) | 输入有多少行 |
| COUNT(died) | died 非 NULL 的行有多少 |
| COUNT(DISTINCT country) | 有多少种非 NULL 的国家代码 |
| SUM(x)、AVG(x)、MIN(x)、MAX(x) | 对非 NULL 的 x 计算 |

假设 x 为 `10, NULL, 20`：COUNT(*)=3，COUNT(x)=2，SUM(x)=30，AVG(x)=15。

空输入且没有 GROUP BY 时，COUNT 返回 0，SUM/AVG/MIN/MAX 返回 NULL；带 GROUP BY 且没有形成任何分组时返回零行。COUNT(*) 数行，即使所有输出列均为 NULL，那个行也可以被计数。

### 4.3 WHERE 与 HAVING

```sql
SELECT country, COUNT(*) AS movie_count
FROM movies
WHERE year_released >= 2000
GROUP BY country
HAVING COUNT(*) >= 10;
```

先取 2000 年以后的电影，再按国家分组，最后保留至少 10 部的国家。WHERE 筛原始行，HAVING 筛组。不能把 `COUNT(*)>=10` 直接放到同层 WHERE。

“只看美国电影”这种行条件优先写 WHERE。聚合可能采用排序或哈希，不保证必然排序，GROUP BY 也不保证输出顺序。

### 4.4 JOIN：先想每条记录能配上几条

```sql
SELECT m.movieid, m.title, c.country_name
FROM movies m
JOIN countries c ON c.country_code = m.country;
```

每部电影通过代码找到一个国家名称。可把内连接理解为“所有配对中，保留 ON 条件为 TRUE 的配对”。这是逻辑理解，实际执行通常不会先真的生成巨大笛卡尔积。

外键约束管数据合法性，JOIN 管如何查询。没有外键也能 JOIN，JOIN 也不会自动替你使用外键条件。

```sql
SELECT m.movieid, m.title, p.peopleid, p.first_name, p.surname
FROM movies m
JOIN credits c ON c.movieid = m.movieid
JOIN people p ON p.peopleid = c.peopleid
WHERE m.country = 'gb' AND c.credited_as = 'D';
```

这里一行代表“一部英国电影的一位导演”。同一部电影有两位导演，就应该有两行。若只问电影数量，直接 COUNT(*) 会数成导演记录数量。

**连接乘法反例：** 一部电影有 3 位演员、2 位导演，同时连接演员记录与导演记录，可能得到 3×2=6 行。再 SUM 电影票房会算 6 次。正确方式通常是先分别汇总到“一部电影一行”再连接，或只做存在性判断。

### 4.5 自连接和连接写法

同一张表可以通过别名扮演两个角色。假设 people 另有 fatherid：

```sql
SELECT child.peopleid, child.surname, father.surname AS father_surname
FROM people child
JOIN people father ON father.peopleid = child.fatherid;
```

`JOIN ... ON` 清楚分开连接条件与业务过滤。旧式逗号列表加 WHERE 也能写内连接，但容易漏条件。NATURAL JOIN 根据同名列自动匹配，新增一个同名列可能改变结果，复习和实际编程都优先显式写 ON。

## 5. 外连接、集合运算与子查询

### 5.1 LEFT JOIN 为什么能保留缺失记录

INNER JOIN 只保留匹配成功的组合。LEFT JOIN 保留左表所有记录，右边无匹配时补 NULL。左边一行配到多个右行仍会产生多行。

| 连接 | 保留哪些未匹配行 |
| --- | --- |
| INNER JOIN | 都不保留 |
| LEFT JOIN | 左表 |
| RIGHT JOIN | 右表，可交换两表改成 LEFT JOIN |
| FULL OUTER JOIN | 两边都保留，支持情况看产品 |
| CROSS JOIN | 不加匹配条件，产生所有组合 |

### 5.2 必须会：有导演就显示，没有也保留电影

```sql
SELECT m.movieid, m.title, p.peopleid, p.surname
FROM movies m
LEFT JOIN credits c
  ON c.movieid = m.movieid
 AND c.credited_as = 'D'
LEFT JOIN people p ON p.peopleid = c.peopleid
WHERE m.country = 'gb';
```

`credited_as='D'` 放在 ON，表示“只把导演记录当成可匹配对象”。没有导演时，电影仍留下。

如果放在 WHERE，无匹配时补出的 NULL 无法满足 `'D'`，电影就被筛掉。即便改成 `WHERE credited_as='D' OR credited_as IS NULL`，也救不了“只录了演员、没录导演”的电影：它配上演员后没有补 NULL，演员又不是 D，最终整部电影消失。

后面 people 也用 LEFT JOIN，否则前面刚保留的 NULL 行又会因内连接失败而消失。另一种正确写法是先在子查询里把 credits 限定为导演，再 LEFT JOIN。

### 5.3 必须会：每个国家的电影数，包括零

```sql
SELECT c.country_code, c.country_name, COUNT(m.movieid) AS movie_count
FROM countries c
LEFT JOIN movies m ON m.country = c.country_code
GROUP BY c.country_code, c.country_name;
```

为什么不用 COUNT(*)？没有电影的国家也有一条右侧补 NULL 的结果行，COUNT(*) 会算成 1。COUNT(m.movieid) 不统计补出的 NULL，才是 0。

也可以先在 movies 中 GROUP BY，再 LEFT JOIN countries，最后 `COALESCE(汇总值,0)`。

### 5.4 UNION、INTERSECT、EXCEPT

| 运算 | 作用 | 默认是否去重 |
| --- | --- | --- |
| UNION | 合并两边结果 | 是 |
| UNION ALL | 拼接两边全部行 | 否 |
| INTERSECT | 两边共有的行 | 是 |
| EXCEPT | 左边有、右边没有的行 | 是 |

Oracle 课件写 MINUS 表示差集。支持的运算和 ALL 变体因版本而异。上下查询列数相同，对应位置的类型兼容，**业务含义也必须对应**。名字相同不是必要条件，靠列的位置对齐。

```sql
SELECT country_code FROM countries
EXCEPT
SELECT country FROM movies;
```

得到“尚无电影的国家”。JOIN 横向组合列，UNION 纵向拼接行。

**UNION ALL 不只是性能选项。** 去年某电影观看数 100，今年也是 100。两行虽然看起来相同，却代表不同年份。UNION 会删掉一行，求和变成 100；UNION ALL 保留两行才是 200。判断是否该去重，要看事实含义。

### 5.5 三种位置的子查询

| 位置 | 当作什么使用 | 要检查什么 |
| --- | --- | --- |
| FROM (...) x | 临时结果表/派生表 | 一行含义、别名、列名 |
| SELECT (SELECT ...) | 一个标量值 | 一列且最多一行 |
| WHERE ... | 过滤依据 | IN、EXISTS、比较、NULL |

```sql
SELECT m.movieid, m.title,
       (SELECT c.country_name
        FROM countries c
        WHERE c.country_code = m.country) AS country_name
FROM movies m;
```

子查询引用了外层 m.country，叫**相关子查询**。没有外层引用的是非相关子查询。标量子查询在通用 SQL / PostgreSQL 中：零行变 NULL，一行取值，多行报错；不要把“很多行”理解为自动取第一行。这里国家主键保证最多匹配一行。

相关性描述逻辑依赖，不等于数据库一定逐行重复执行。优化器可能重写，性能要结合执行计划判断。

### 5.6 IN 查成员资格，EXISTS 查是否存在

欧洲电影：

```sql
SELECT movieid, title
FROM movies
WHERE country IN (
    SELECT country_code FROM countries WHERE continent = 'EUROPE'
);
```

IN 的子查询中重复某个值不会让外层行复制，但改写成 JOIN 后，右表重复键可能导致外层行增多。

至少有一位 1970 年及以后出生演员的电影：

```sql
SELECT m.movieid, m.title
FROM movies m
WHERE EXISTS (
    SELECT 1
    FROM credits c
    JOIN people p ON p.peopleid = c.peopleid
    WHERE c.movieid = m.movieid
      AND c.credited_as = 'A'
      AND p.born >= 1970
);
```

EXISTS 只关心有没有行，不关心 `SELECT 1` 的 1。写 `SELECT NULL` 也可以，返回一行 NULL 仍然是“存在一行”。只判断有没有匹配时，用 EXISTS 很直观，也不会因为多人匹配而复制外层电影。

### 5.7 NOT IN 与 NULL 的坑

```text
x NOT IN ('a', 'b', NULL)
等价于 x <> 'a' AND x <> 'b' AND x <> NULL
```

x='c' 时得到 TRUE AND TRUE AND UNKNOWN，最终 UNKNOWN，被 WHERE 丢弃。x='a' 时则是 FALSE，也会丢弃。因此子查询里混入 NULL，NOT IN 往往让你预期的“其他值”全部消失。

可排除子查询中的 NULL，但外层 x 为 NULL 时的含义仍要另想。查“没有导演记录的电影”更适合：

```sql
SELECT m.movieid, m.title
FROM movies m
WHERE NOT EXISTS (
    SELECT 1 FROM credits c
    WHERE c.movieid = m.movieid AND c.credited_as = 'D'
);
```

这表示数据库中没有相应记录，不能推断现实里电影没有导演。

## 6. 排序、分页、层次结构与窗口函数

### 6.1 ORDER BY 与分页

```sql
SELECT movieid, title, year_released
FROM movies
ORDER BY year_released DESC, movieid ASC
LIMIT 10 OFFSET 20;
```

先按年份从新到旧，同年再按 ID 升序，跳过前 20 行取 10 行，即第 21–30 行。LIMIT/OFFSET 适用于 PostgreSQL、MySQL、SQLite 等。课件还介绍 `FETCH FIRST ... ROWS ONLY` 和 SQL Server 的 TOP，不能把几种方言混写。

分页必须有明确排序，同值时增加唯一键使顺序稳定。没有排序的前 10 行不等于“最早/最新 10 行”。在数据并发变化时，即便确定排序，普通 OFFSET 分页仍可能跨页变化。

NULL 默认排前还是排后依 DBMS 而异。需要明确时使用支持的 `NULLS FIRST/LAST`，或用 CASE 辅助排序。日期按原日期列排，格式化后的 `'MM/DD/YYYY'` 文本不具有同样的时间顺序。

自定义“导演、制片人、演员”顺序：

```sql
SELECT movieid, peopleid, credited_as
FROM credits
ORDER BY CASE credited_as
             WHEN 'D' THEN 1 WHEN 'P' THEN 2 WHEN 'A' THEN 3 ELSE 4
         END,
         movieid, peopleid;
```

Collation 是文本的比较和排序规则，会影响大小写、重音、中文排序等。编码顺序、拼音顺序和笔画顺序不是同一回事。

### 6.2 层次结构：课件先引出问题

论坛回复、组织架构、零件组成可能形成树状关系。只按发布时间排序，会把回复和被回复对象分开。

课件介绍 materialized path（物化路径）：每条记录存祖先路径，再按“路径+自身 ID”排序。数字 ID 必须采取恰当的定宽格式，否则字符串 `'10'` 可能排在 `'2'` 前。路径方案还需考虑深度、更新成本等。

Oracle 的 `START WITH ... CONNECT BY ... PRIOR ...` 从根开始沿父子关系找后代，`ORDER SIBLINGS BY` 排同级节点。Chapter 6 只预告后续会讲递归查询，本版不把递归 CTE 算作已完成内容。

### 6.3 窗口函数：保留明细，同时看到整组情况

普通聚合会把一个国家的多部电影压成一行；窗口函数可以让每部电影旁边带上它所属国家的统计结果。

```sql
SELECT movieid, country, title, year_released,
       MIN(year_released) OVER (PARTITION BY country) AS earliest_year,
       COUNT(*) OVER (PARTITION BY country) AS country_movie_count
FROM movies;
```

输入有多少电影，输出仍有多少行。PARTITION BY 划分计算范围，不像 GROUP BY 那样把明细合并。`OVER ()` 没有分区，计算当前查询处理到的全部行。

查每个国家最早上映的电影，保留所有并列：

```sql
SELECT x.movieid, x.country, x.title, x.year_released
FROM (
    SELECT movieid, country, title, year_released,
           MIN(year_released) OVER (PARTITION BY country) AS earliest_year
    FROM movies
) x
WHERE x.year_released = x.earliest_year;
```

不能在同层 WHERE 中筛刚计算出的窗口结果。通用写法是包一层子查询；某些产品支持 QUALIFY，但这里不依赖它。

另一种方法是先按国家 MIN，再按“国家+最早年份”连回 movies。只查 `MIN(year_released), title` 而没有正确处理分组，不能可靠取得对应标题。

### 6.4 筛选在窗口里面还是外面，会改变题意

假设某国只有 A(1940)、B(1950)。

- 先在内层 WHERE 排除 A，再算 MIN，得到 B：**剩余电影里最早的**。
- 先算所有电影的 MIN，再在外层排除 A 并要求等于 MIN，得到零行：**原本最早的电影中，排除 A**。

WHERE 会先影响窗口输入范围。GROUP BY/HAVING 若出现，窗口函数看到的是分组后留下的行，不能一律理解为原始表所有行。

### 6.5 电影占比与 CROSS JOIN

```sql
SELECT q.country_code, q.movie_count,
       ROUND(100.0 * q.movie_count /
             NULLIF(SUM(q.movie_count) OVER (), 0), 2) AS percentage
FROM (
    SELECT c.country_code, COUNT(m.movieid) AS movie_count
    FROM countries c
    LEFT JOIN movies m ON m.country = c.country_code
    GROUP BY c.country_code
) q
ORDER BY q.country_code;
```

SUM(...) OVER() 在每一行旁边给出总数。`100.0` 避免某些数据库的整数除法截断；`NULLIF(分母,0)` 让总数为零时返回 NULL，避免除零。

也可 `CROSS JOIN (SELECT COUNT(*) AS total FROM movies) t`，把一行总数附加到每个国家后面。CROSS JOIN 两边若分别是 n 行、1 行，结果 n 行；若都是多行，就产生乘法。

### 6.6 三种排名函数必须分清

按年份降序，年份为 2024、2024、2022、2020：

| 年份 | ROW_NUMBER | RANK | DENSE_RANK |
| --- | --- | --- | --- |
| 2024 | 1 或 2 | 1 | 1 |
| 2024 | 2 或 1 | 1 | 1 |
| 2022 | 3 | 3 | 2 |
| 2020 | 4 | 4 | 3 |

- ROW_NUMBER：每行一个不同序号，要确定同年份先后需加唯一键。
- RANK：并列名次相同，后面跳号。
- DENSE_RANK：并列名次相同，后面不跳号。

每国最新两部电影，最多两行：

```sql
SELECT x.movieid, x.country, x.title, x.year_released
FROM (
    SELECT movieid, country, title, year_released,
           ROW_NUMBER() OVER (
               PARTITION BY country
               ORDER BY year_released DESC, movieid
           ) AS rn
    FROM movies
) x
WHERE x.rn <= 2
ORDER BY x.country, x.rn;
```

若题目要“最新的两个不同上映年份的全部电影”，改成 `DENSE_RANK() OVER (PARTITION BY country ORDER BY year_released DESC)`。此时**不要再把 movieid 放进窗口排序**，否则同一年会被拆成不同名次。

窗口内 ORDER BY 决定计算顺序，不保证最终展示顺序。最终输出顺序还要最外层 ORDER BY。

补充边界：给 SUM/MIN 等窗口聚合加上 ORDER BY，可能引入默认窗口帧，统计范围不再是整个分区。课件中的全组最值、总数示例不需要窗口 ORDER BY；不要为“让结果有序”随手加进去。

## 7. 做 SQL 题的固定步骤

1. **翻译题意。** 是“至少一个”“没有一个”，还是“统计数量”“各组前几名”？有没有要求保留零和缺失？
2. **确定一行代表什么。** 一部电影、一个人，还是一个“电影+人物+身份”？需要哪些 ID 保住身份？
3. **找表和路径。** 标题在 movies，姓名在 people，两者通过 credits 联系。
4. **先想连接会变几行。** 一对一、一对多、多对多？单纯问存在性优先考虑 EXISTS。
5. **区分条件位置。** WHERE 筛原始行，HAVING 筛组，LEFT JOIN 的右边匹配条件通常写 ON，窗口结果再包一层筛。
6. **检查 NULL 与重复。** COUNT(*) 是否数到了补空行？NOT IN 子查询会不会有 NULL？DISTINCT 会不会合并不同对象？
7. **最后处理排序、并列和分页。** 题目要两条记录、两个名次，还是两个年份？

常见查询的逻辑理解顺序：

```text
FROM / JOIN（ON 匹配、外连接补行）
→ WHERE
→ GROUP BY / 聚合
→ HAVING
→ 窗口函数
→ SELECT 输出表达式
→ DISTINCT
→ ORDER BY
→ LIMIT / OFFSET / FETCH
```

这是理解语义的顺序，不是优化器一定采用的物理执行步骤。

## 8. 自测题与参考答案

先自己写，再展开答案。下面题目都沿用前文四表，除特别说明外按基本教学数据模型理解。

### 题 1：判断两个查询的区别

A：`country='us' OR country='gb' AND year_released>=2000`。  
B：`country IN ('us','gb') AND year_released>=2000`。

<details><summary>答案</summary>

A 包含所有美国电影，以及 2000 年起的英国电影。B 只包含 2000 年起的美国和英国电影。原因是 AND 优先于 OR。

</details>

### 题 2：统计“有过电影的年份数至少为 3”的国家

<details><summary>答案</summary>

```sql
SELECT country, COUNT(DISTINCT year_released) AS year_count
FROM movies
GROUP BY country
HAVING COUNT(DISTINCT year_released) >= 3;
```

COUNT(*) 数电影，COUNT(DISTINCT year_released) 数不同年份。想清楚统计对象，别被“数量”两个字带偏。

</details>

### 题 3：哪些人既当过演员又当过导演，不要求同一部电影

<details><summary>答案</summary>

```sql
SELECT p.peopleid, p.first_name, p.surname
FROM people p
JOIN (
    SELECT peopleid
    FROM credits
    WHERE credited_as IN ('A', 'D')
    GROUP BY peopleid
    HAVING COUNT(DISTINCT credited_as) = 2
) x ON x.peopleid = p.peopleid;
```

一定先限定 A、D，并数不同身份。一个人出演十部电影但没导演过，COUNT(*) 很大，也不符合题意。若要求同一部电影既演又导，应按 `(movieid,peopleid)` 分组。

</details>

### 题 4：列出所有国家及其 2000 年以来电影数，包括零

<details><summary>答案</summary>

```sql
SELECT c.country_code, c.country_name, COUNT(m.movieid) AS cnt
FROM countries c
LEFT JOIN movies m
  ON m.country = c.country_code AND m.year_released >= 2000
GROUP BY c.country_code, c.country_name;
```

年份条件在 ON，计数用非空主键。若年份放 WHERE，无符合条件电影的国家会消失。

</details>

### 题 5：列出没有任何演员记录的电影

<details><summary>答案</summary>

```sql
SELECT m.movieid, m.title
FROM movies m
WHERE NOT EXISTS (
    SELECT 1 FROM credits c
    WHERE c.movieid = m.movieid AND c.credited_as = 'A'
);
```

既包括“只有导演记录”的电影，也包括“完全没有 credits”的电影。

</details>

### 题 6：统计至少有一位 1970 年及以后出生演员的电影数

<details><summary>答案</summary>

```sql
SELECT COUNT(*) AS movie_count
FROM movies m
WHERE EXISTS (
    SELECT 1
    FROM credits c
    JOIN people p ON p.peopleid = c.peopleid
    WHERE c.movieid = m.movieid
      AND c.credited_as = 'A' AND p.born >= 1970
);
```

外层 movies 一部电影一行，EXISTS 不会因为匹配到三名演员就数三次。如果用 JOIN，可考虑 `COUNT(DISTINCT m.movieid)`。

</details>

### 题 7：每个国家最早上映的电影标题，保留并列

<details><summary>答案</summary>

```sql
SELECT m.movieid, m.country, m.title, m.year_released
FROM movies m
JOIN (
    SELECT country, MIN(year_released) AS earliest_year
    FROM movies GROUP BY country
) x ON x.country = m.country AND x.earliest_year = m.year_released;
```

连接必须同时匹配国家和年份。只连年份，会把别国同年电影也连进来。窗口 MIN 的写法见第 6 节。

</details>

### 题 8：无输入行时，COUNT(*)、SUM(born) 是什么

<details><summary>答案</summary>

不带 GROUP BY 的聚合查询仍返回一行：COUNT(*) 是 0，SUM(born) 是 NULL。若按 surname 分组，没有输入就没有分组，返回零行。

</details>

### 题 9：为什么 UNION 会把两年的总观看次数算少

<details><summary>答案</summary>

若去年和今年恰好都得到 `(movieid=1, count=100)`，UNION 对整行去重，只留下一个 100。需要 UNION ALL 保留两条事实，再按 movieid 求和得到 200。也可显式增加年份列保留事实身份，但不能无理由抹掉时期信息。

</details>

### 题 10：最新两部与最新两个年份有什么区别

<details><summary>答案</summary>

假设同一国家有 A(2024)、B(2024)、C(2022)、D(2020)。ROW_NUMBER<=2 得 A、B。DENSE_RANK 按年份降序且 <=2，得 A、B、C。RANK<=2 则只得 A、B，因为 C 的名次是 3。

</details>

### 题 11：为什么 COUNT(*)=2 不一定表示“既演又导”

<details><summary>答案</summary>

同一个人演过两部电影，也有两条 credits。应先针对 `(peopleid,credited_as)` 去重后计数，或者限定 A、D 后用 COUNT(DISTINCT credited_as)=2。去重的对象与业务含义必须一致。

</details>

### 题 12：一部电影只录了演员、没录导演，怎样仍显示它

<details><summary>答案</summary>

从 movies 出发，在 LEFT JOIN credits 的 ON 中限定 credited_as='D'，再 LEFT JOIN people。WHERE 只筛电影本身条件。不要尝试用 `WHERE credited_as='D' OR credited_as IS NULL` 补救，因为存在演员匹配时不会产生那条补空记录。

</details>

## 9. 考前速查与复习安排

### 9.1 看到题目词语，先想到什么

| 题目表述 | 优先考虑 |
| --- | --- |
| 至少存在一个 | EXISTS |
| 一个都没有 | NOT EXISTS，或 LEFT JOIN 后检查右表非空键 IS NULL |
| 每个国家多少部 | GROUP BY country + COUNT |
| 包括没有电影的国家 | countries 为左表的 LEFT JOIN |
| 各组总数同时保留电影标题 | 聚合窗口函数 |
| 各组最新 K 部 | ROW_NUMBER + PARTITION BY + 外层过滤 |
| 各组最新 K 个不同年份 | DENSE_RANK，窗口排序只按年份 |
| 两批事实合计 | UNION ALL 再汇总 |
| 只列不同国家 | DISTINCT country |
| 一天内/一段时间内 | 日期时间的左闭右开范围 |

### 9.2 最容易丢分的检查清单

- [ ] 用 `IS NULL`，没有写 `= NULL`。
- [ ] AND/OR 混用时括号符合题意。
- [ ] 计数对象明确：行、电影、人、年份还是身份。
- [ ] LEFT JOIN 后没有用右表 WHERE 条件误删补空行。
- [ ] 外连接后统计子记录用右表非空键，不盲目 COUNT(*)。
- [ ] 聚合结果没有随便带一个未分组的 title。
- [ ] EXISTS、IN 改写为 JOIN 后检查行数是否膨胀。
- [ ] NOT IN 子查询的 NULL 已处理，外层 NULL 的题意也想清楚。
- [ ] DISTINCT 没有把同名不同人、同名不同电影合并。
- [ ] UNION 与 UNION ALL 根据事实是否应保留来选。
- [ ] 标量子查询有“最多一行”的保证。
- [ ] 分页有确定排序，OFFSET 20 对应从第 21 行开始。
- [ ] 排名并列规则符合题意，窗口排序和最终输出排序分清。
- [ ] 窗口函数输入是筛选后的集合，内外层条件位置正确。
- [ ] 百分比考虑小数除法和分母为零。

### 9.3 六次复习建议

这是按现有课件安排的练习路线，不是老师公布的题型或分值预测。

1. 画四表关系，解释每个键和三种范式，做一次拆表题。
2. 默写四表 CREATE TABLE，尝试解释每种非法插入为何失败。
3. 练条件、日期区间、NULL 真值、CASE，能手算筛选结果。
4. 练 DISTINCT、GROUP BY、HAVING，连接之前先预计行数。
5. 专练 LEFT JOIN、NOT EXISTS、NOT IN 的反例，不只背正确模板。
6. 练窗口最值、占比、分组 Top K，再闭卷完成第 8 节。

判断自己是否学会：换成学生选课、订单商品等表名后，仍能说清楚“一行是什么，为什么这样连接和计数”。

## 10. 课件索引、勘误与更新记录

### 10.1 本次实际阅读范围

页码使用 PDF 阅读器中的物理页码，从 1 开始。原始文件保留在本地 `E:/chatgpt/database/ppt`，本目录上传整理后的笔记。

| 原始文件 | 页数 | 主要内容与页码 |
| --- | --- | --- |
| CS213-DB-01.pdf | 84 | 数据库介绍 7–21；键 22–36；规范化 37–74；实体联系与基数 75–83 |
| CS213-DB-02(1).pdf | 66 | SQL/DDL/DML 2–21；建表与类型 22–38；约束 39–56；INSERT 57–66 |
| CS213-DB-03.pdf | 79 | SELECT/条件 2–27；日期 28–44；NULL 45–51；函数 52–68；CASE 69–79 |
| CS213-DB-04.pdf | 69 | DISTINCT 2–7；聚合/GROUP BY/HAVING 8–33；连接 34–54；自连接、多表连接 55–69 |
| CS213-DB-05(1).pdf | 88 | 外连接 2–29；集合运算 30–52；子查询、IN/NOT IN/EXISTS 53–88 |
| CS213-DB-06(1).pdf | 67 | 排序、分页、层次结构 2–35；窗口聚合与筛选 36–53；排名与分组 Top K 54–67 |

课件中的人物历史、电影海报和 SQL 发展背景用于辅助理解。本笔记保留与关系模型、查询正确性相关的知识，没有逐张复述这些插图和人物简介。

### 10.2 阅读课件时需要校正的说法

这些说明用于防止把教学简化当成通用规则。涉及产品行为时，以课程指定环境与对应版本文档为准。

| 课件位置/说法 | 本笔记采用的准确理解 |
| --- | --- |
| Ch1：规范化也涉及大小写统一 | 大小写统一是数据清洗规则；范式主要依据属性原子性、键与函数依赖分析 |
| Ch2 p48：MySQL 不执行 CHECK | 这是旧版本行为。MySQL 从 8.0.16 起支持执行 CHECK，且可声明 NOT ENFORCED。见 [MySQL 官方文档](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html) |
| Ch2：名称或字符串大小写的概括 | 标识符规则、字符串比较与 collation 要分别讨论，不能仅按 DBMS 名称下结论 |
| Ch4 p19：聚合需要排序 | 聚合也可能用哈希等策略，GROUP BY 不保证排序 |
| Ch4 p26：一行至少一个非空值 | SQL 可以产生全为 NULL 的一行；COUNT(*) 仍数这行。它数行，不是找非空列 |
| Ch5 p36：UNION ALL 表示知道不会重复 | UNION ALL 的语义是保留所有行，也适用于重复外观的记录本来就必须保留的场景 |
| Ch5 p61：FROM 子查询不能相关 | 普通派生表不能随意引用同级表，PostgreSQL 等支持通过 LATERAL 显式引用前面的 FROM 项。见 [PostgreSQL 表表达式文档](https://www.postgresql.org/docs/14/queries-table-expressions.html#QUERIES-LATERAL) |
| Ch5 p81：绝不要关联 IN | 这是课件的写法建议，不是语法禁令。相关 IN 可以合法，存在性题用 EXISTS 更好表达意图 |
| Ch5：NOT IN 遇到 NULL 条件总为 NULL | 更精确地说，匹配已有值时可为 FALSE，其他值可能为 UNKNOWN，WHERE 都不会保留。见 [PostgreSQL 子查询文档](https://www.postgresql.org/docs/18/functions-subquery.html) |
| Ch6 p37：SQLite 不支持窗口函数 | SQLite 从 3.25.0（2018-09-15）起支持。见 [SQLite 官方文档](https://www.sqlite.org/windowfunctions.html) |
| Ch6：窗口函数只能在 SELECT | 在 PostgreSQL 中也可以出现在 ORDER BY；本笔记使用子查询筛窗口结果。窗口帧细节见 [PostgreSQL 窗口函数文档](https://www.postgresql.org/docs/current/functions-window.html) |
| 多处：子查询/函数/去重的固定性能判断 | 教学直觉有帮助，但“相关必慢”“提前 DISTINCT 必快”“窗口几乎无成本”都不是通则。实际取决于数据、索引和执行计划 |

课件还偶有列名不一致，例如 Ch5 p59 的 movies.country_code、Ch6 p6–7 的 people.birth_year。本笔记统一成前文结构的 `movies.country`、`people.born`。

### 10.3 后续怎样更新

继续把新课件放进本地 `ppt` 文件夹，说明“按新增课件更新总结”即可。更新时先比较文件名和内容，再补到对应章节，保留已有解释与练习，修正新旧内容冲突，并更新此处的覆盖范围。

维护约定：

- GitHub 固定入口为 `database/ppt/README.md`，后续更新同一个文件。
- 课件里的定义、用于理解的补充、版本差异分别说明。
- 新知识配一个可理解的小例子，有典型陷阱时补反例和自测题。
- 不根据项目文件或一般教材，擅自声称老师已经讲过未提供的章节。
- 对外部文档的核对日期随实际更新填写，不自动把日期刷新当作内容更新。

| 日期 | 覆盖范围 | 更新内容 |
| --- | --- | --- |
| 2026-10-21 | Chapter 1–6，453 页 | 首版：四表统一模型、通俗解释、SQL 模板、易错点、12 道自测题、版本勘误 |
