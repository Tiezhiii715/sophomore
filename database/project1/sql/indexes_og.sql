-- =============================================================================
--  indexes_og.sql  --  openGauss 5.0.0
--
--  ⚠️ 运行时机：必须在"无索引状态"的实验全部测完之后再执行本文件。
--
--  与 indexes_pg.sql 的差异：
--    1. 没有 pg_trgm 扩展（openGauss 不提供），所以没有 trigram 索引
--    2. 反转索引表用 FROM 隐式 LATERAL 写法（openGauss 基于 PG 9.2，
--       不支持显式 LATERAL 关键字）
--    3. 不建 flights / taobao 上的索引 —— 那两张表在本项目里只用于聚合，
--       建了也用不上，白白浪费时间
--
--  执行（宿主机 psql 直连容器端口）：
--    psql -h 127.0.0.1 -p 5433 -U gaussdb -d bench -f indexes_og.sql
-- =============================================================================

\timing on
SET client_min_messages = warning;

-- ---------------------------------------------------------------------------
-- 第一部分：常规 B-tree 索引（与 PostgreSQL 侧同口径）
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_imdb_movies_title    ON imdb.movies (title);
CREATE INDEX IF NOT EXISTS idx_imdb_credits_movie   ON imdb.credits (movieid);
CREATE INDEX IF NOT EXISTS idx_imdb_credits_people  ON imdb.credits (peopleid);
CREATE INDEX IF NOT EXISTS idx_imdb_people_surname  ON imdb.people (surname);
CREATE INDEX IF NOT EXISTS idx_imdb_alt_movie       ON imdb.alt_titles (movieid);

CREATE INDEX IF NOT EXISTS idx_filmdb_movies_title   ON filmdb.movies (title);
CREATE INDEX IF NOT EXISTS idx_filmdb_credits_movie  ON filmdb.credits (movieid);
CREATE INDEX IF NOT EXISTS idx_filmdb_credits_people ON filmdb.credits (peopleid);

-- ---------------------------------------------------------------------------
-- 第二部分：手工反转索引表（词 -> 电影 id）
--   对应老师数据集里那张空的 movie_title_ft_index2。
--   ⚠️ 很慢（要拆 1,285 万个标题），预计十分钟以上。
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS imdb.title_words;
CREATE TABLE imdb.title_words (
    word    VARCHAR(200) NOT NULL,
    movieid BIGINT       NOT NULL
);

-- openGauss 基于 PG 9.2，不支持 "FROM t, func(t.col)" 这种隐式 LATERAL 写法，
-- 改成把集返回函数放在 SELECT 列表里（PG 9.2 支持这种形式）。
INSERT INTO imdb.title_words (word, movieid)
SELECT lower(w), movieid
  FROM (SELECT movieid,
               regexp_split_to_table(title, '[^A-Za-z0-9]+') AS w
          FROM imdb.movies) s
 WHERE length(w) >= 3;

CREATE INDEX IF NOT EXISTS idx_title_words_word  ON imdb.title_words (word);
CREATE INDEX IF NOT EXISTS idx_title_words_movie ON imdb.title_words (movieid);

-- 记录反转索引的体积，报告 E5 要用
SELECT pg_size_pretty(pg_total_relation_size('imdb.title_words')) AS inverted_index_size;

-- ---------------------------------------------------------------------------
-- 第三部分：重新收集统计信息
--   ⚠️ openGauss 不允许 ANALYZE 和多条语句写在同一串里，
--      所以下面每条单独一行，用 psql 执行时是分开的语句，没有问题。
-- ---------------------------------------------------------------------------
ANALYZE imdb.movies;
ANALYZE imdb.people;
ANALYZE imdb.credits;
ANALYZE imdb.alt_titles;
ANALYZE imdb.title_words;
ANALYZE filmdb.movies;
ANALYZE filmdb.people;
ANALYZE filmdb.credits;
