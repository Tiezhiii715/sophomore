# 第 3 节：怎样准确挑出记录——条件、日期、NULL 与 CASE

> 课件：`CS213-DB-03.pdf`，79 页。<br>
> 更新：2026-10-26。页码为 PDF 物理页码。<br>
> [返回分节目录](README.md) · [大总结](../数据库原理复习总结.md) · [上一节](02-把规则写进数据库：建表、约束与插入.md) · [下一节](04-统计为什么算错：去重、聚合与连接.md)

这一节只查一张表，但已经能展示 SQL 最常见的错误：**句子能运行，筛出来的却不是自己以为的那批记录。** 关键是把中文条件翻译准确，并知道日期和 NULL 怎样参与比较。

## 1. 表很大，只想看其中一部分怎么办（p2–14）

先用三句话读查询：从哪里取、留下哪些行、每行显示什么。

```sql
SELECT movieid, title, year_released
FROM movies
WHERE country = 'us';
```

`FROM movies` 指定来源，`WHERE` 决定一行是否留下，`SELECT` 决定显示哪些列。`SELECT *` 临时看表很方便，程序里明确列名更稳定，也避免无用传输。

课件先演示“筛美国片，放到一个虚拟表里，再筛 1940 年代”：

```sql
SELECT *
FROM (SELECT * FROM movies WHERE country = 'us') us_movies
WHERE year_released BETWEEN 1940 AND 1949;
```

它表达了分步骤思考，简单场景可合成一个 WHERE：

```sql
SELECT movieid, title, year_released
FROM movies
WHERE country = 'us'
  AND year_released BETWEEN 1940 AND 1949;
```

不是说每次都该写嵌套，而是为后面复杂查询建立方法：先弄清中间结果是什么，再对它继续操作。`BETWEEN` 含两端，也不会自动交换写反的上下界。

## 2. 中文里“或”和“并且”很自然，SQL 却要明确括号（p15–24）

情况：找“1940 年代的美国片或英国片”。

```sql
WHERE country = 'us'
   OR country = 'gb' AND year_released BETWEEN 1940 AND 1949
```

由于 AND 的优先级高于 OR，这相当于“所有美国片，加上 1940 年代英国片”。一部 1990 年的美国片也会混进来。

解决：先把国家选择圈起来：

```sql
WHERE (country = 'us' OR country = 'gb')
  AND year_released BETWEEN 1940 AND 1949
```

或用 IN：

```sql
WHERE country IN ('us', 'gb')
  AND year_released BETWEEN 1940 AND 1949
```

如果需求是“1940 年代中国片，或者 1950 年代美国片”，两个时间范围不能拆成独立国家集合与年份集合，应保留两组配对：

```sql
WHERE (country = 'cn' AND year_released BETWEEN 1940 AND 1949)
   OR (country = 'us' AND year_released BETWEEN 1950 AND 1959)
```

自己检验的方法：拿“中国 1955”“美国 1945”这样的边界反例问它会不会被错误选中。只看一个应该选中的例子，不足以证明条件正确。

## 3. 比较之前，先确认两边是什么类型（p21–27）

数字 2 比 10 小；字符串 `'2'` 和 `'10'` 按某些字符排序规则比较时，可能反而在后面。`'2-JUN-1883'` 也不是天然可以按字符顺序比较的时间。

解决：让数值按数值比、日期按日期比；必要时明确 CAST 或相应转换函数。不要依赖系统猜测隐式转换方向。

标题不要求完全相同，可以使用 LIKE：

| 模式 | 意思 |
| --- | --- |
| `'Star%'` | 以 Star 开头，后面可以没有字符 |
| `'%Star%'` | 任意位置包含 Star |
| `'_at'` | 恰好一个字符后接 at |

`%` 是任意数量字符，`_` 是恰好一个字符。它们是模式符号，真正要找下划线等字面字符，需要按产品语法转义。大小写是否相等由比较规则决定。

课件写 `UPPER(title)` 可以统一比较，但提醒它可能影响索引利用。应理解成“普通列索引未必直接匹配转换后的表达式”，不是“SQL 一出现函数就一定慢”；表达式索引等情况要结合执行计划分析。

## 4. 查“某一天”，为什么只查到午夜那条（p28–44）

情况：`issued` 是时间戳，保存 `2026-10-26 14:23:52`。你写：

```sql
WHERE issued = DATE '2026-10-26'
```

当日期按午夜与时间戳比较，它只对应 `2026-10-26 00:00:00`，不会自动表示“这一天的所有时刻”。

解决：用左闭右开区间，PostgreSQL 示例：

```sql
WHERE issued >= TIMESTAMP '2026-10-26 00:00:00'
  AND issued <  TIMESTAMP '2026-10-27 00:00:00'
```

边界手算：26 日零点留下，26 日最后一小段时间留下，27 日零点排除。

为什么不写到 `23:59:59`？因为时间列可能还有小数秒，`23:59:59.500` 仍属于当天，写“下一天零点之前”不用猜精度。连续日期范围同理：包含 12 日到 16 日，结束写 17 日零点，不是 16 日零点。

有时区的时间列还应把业务的“当天”解释为指定时区下的日期边界，这是补充，课件没有展开时区机制。

另外，“加一个月”与“加 30 天”不等价，月份长度和闰年会变化。课件展示各产品不同的日期运算函数，如 PostgreSQL 的 `date_col + INTERVAL '1 month'`、MySQL 的 `DATE_ADD`、SQL Server 的 `DATEADD`、Oracle 的 `ADD_MONTHS`。应按运行环境选择一种方言，不能混写。

## 5. NULL 为什么不能用等号查（p45–51）

假设某人的死亡年没有记录，`died` 为 NULL。

你问 `died = NULL`，相当于“它等于一个我不知道的值吗？”无法判断，所以结果是 UNKNOWN。`died <> NULL` 同样无法判断。WHERE 只留下 TRUE，FALSE 和 UNKNOWN 都不保留。

解决：问“有没有值”，用专门语法：

```sql
WHERE died IS NULL
WHERE died IS NOT NULL
```

不要把 SQL NULL 当作 C/Java 的空指针值去理解；此处是缺失标记。课件把 `died IS NULL` 当作健在人物的简化筛选，但现实数据也可能只是漏录死亡信息。

补充一个以后会用上的三值逻辑小表：

| 表达式 | 结果 | 原因 |
| --- | --- | --- |
| TRUE AND UNKNOWN | UNKNOWN | 另一半不明，无法保证全部为真 |
| FALSE AND UNKNOWN | FALSE | 已有一半为假，整体不可能全真 |
| TRUE OR UNKNOWN | TRUE | 已有一个为真就够了 |
| FALSE OR UNKNOWN | UNKNOWN | 是否成立取决于未知那部分 |
| NOT UNKNOWN | UNKNOWN | 不知道正面，也就不知道反面 |

这能解释为什么 `WHERE NOT (died = 2000)` 不会把 NULL 自动选出来。若需求包含未知死亡年，需要明确写 `died <> 2000 OR died IS NULL`。

## 6. 不必把所有显示结果原样存起来（p52–68）

情况：想显示“某电影于某年上映”，或想显示人物年龄。没有必要为每种显示形式建一列。

解决：查询时用函数和表达式计算：

```sql
-- PostgreSQL
SELECT title || ' was released in ' || CAST(year_released AS VARCHAR)
       AS movie_release
FROM movies;
```

别名 `movie_release` 为结果列命名，外层查询也可以引用这个名字。拼接符因产品不同而不同，课件展示 `||`、`CONCAT()` 等。

课件人物表只存出生年份，因此 `当前年 - born` 只是粗略年龄，不知道生日有没有过，不能声称是精确周岁。年龄会随时间变化，通常由出生信息计算；若要保留某历史时点的年龄，应同时说明那个时点。

常用转换和清洗：UPPER/LOWER、TRIM、REPLACE、SUBSTR、CAST。数值处理注意 ROUND 是按规则舍入，TRUNC 是截断，FLOOR/CEILING 是向下/向上取整。负数时，截断与向下取整可能不同。

## 7. 输出内容随条件变化，怎样表达（p69–79）

### 7.1 已有一个代码，给它换成说明

假设扩展表里 `color` 用 Y/N 记录：

```sql
CASE color
    WHEN 'Y' THEN '彩色'
    WHEN 'N' THEN '黑白'
    ELSE '未知'
END
```

这是简单 CASE，像按一个值查分支。缺 ELSE 且没匹配时，结果为 NULL。

### 7.2 判断的是条件，就使用另一种 CASE

```sql
SELECT peopleid, surname,
       CASE
           WHEN died IS NULL THEN '未记录死亡年'
           ELSE '已记录死亡年'
       END AS status
FROM people;
```

这是搜索 CASE，每个 WHEN 后面是完整条件。为什么不写 `CASE died WHEN NULL`？因为简单 CASE 做的是值匹配，仍然不能用普通相等比较匹配 NULL。

CASE 从前往后选择第一个满足的分支，所以多个区间可能重叠时，顺序要想清楚。补充常见防除零写法：`numerator / NULLIF(denominator, 0)`，分母为 0 时得到 NULL；随后如何显示还由业务决定，不要随意把缺失都解释为 0。

## 8. 自测

1. “英美片并且 1940 年代”应先圈哪部分？<br>
   答：先圈国家 OR，再与时间范围 AND。
2. `BETWEEN 1949 AND 1940` 会自动交换吗？<br>
   答：不会，它不是自动排序两个端点的操作。
3. `died <> 2000` 会选中死亡年未知的人吗？<br>
   答：不会，NULL 参与普通比较得到 UNKNOWN。
4. 时间戳查一天，为什么结束边界写下一天？<br>
   答：避免漏掉最后一秒的小数部分，并能明确排除下一天。

做题时把条件翻译回中文，再拿不该出现的一行检查。很多错误就在这一步被发现。
