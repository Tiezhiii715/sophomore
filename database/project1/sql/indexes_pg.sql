-- =============================================================================
--  indexes_pg.sql  --  PostgreSQL 16/18
--
--  ⚠️ 运行时机：必须在"无索引状态"的 E1–E11 全部测完之后再执行本文件。
--     早了会把对照组建掉，实验作废。
--
--  执行：psql -d bench -f indexes_pg.sql
-- =============================================================================

\timing on
SET client_min_messages = warning;

-- ---------------------------------------------------------------------------
-- 第一部分：常规 B-tree 索引（两个数据库都能建）
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_imdb_movies_title    ON imdb.movies (title);
CREATE INDEX IF NOT EXISTS idx_imdb_credits_movie   ON imdb.credits (movieid);
CREATE INDEX IF NOT EXISTS idx_imdb_credits_people  ON imdb.credits (peopleid);
CREATE INDEX IF NOT EXISTS idx_imdb_people_surname  ON imdb.people (surname);
CREATE INDEX IF NOT EXISTS idx_imdb_alt_movie       ON imdb.alt_titles (movieid);

CREATE INDEX IF NOT EXISTS idx_filmdb_movies_title   ON filmdb.movies (title);
CREATE INDEX IF NOT EXISTS idx_filmdb_credits_movie  ON filmdb.credits (movieid);
CREATE INDEX IF NOT EXISTS idx_filmdb_credits_people ON filmdb.credits (peopleid);

CREATE INDEX IF NOT EXISTS idx_flights_airline ON flights.flights (airline);
CREATE INDEX IF NOT EXISTS idx_flights_date    ON flights.flights (flight_date);
CREATE INDEX IF NOT EXISTS idx_taobao_user     ON taobao.ad_clicks (userid);
CREATE INDEX IF NOT EXISTS idx_taobao_cate     ON taobao.ad_clicks (cate_id);

-- ---------------------------------------------------------------------------
-- 第二部分：trigram 索引（PostgreSQL 独有，openGauss 没有 pg_trgm）
-- 这是 PostgreSQL 的生态优势，报告里要如实写；为了公平，下面第三部分
-- 两个数据库都提供手工反转索引，那一行的对比才是同口径的。
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_imdb_movies_title_trgm
    ON imdb.movies USING gin (title gin_trgm_ops);
ANALYZE imdb.movies;

-- ---------------------------------------------------------------------------
-- 第三部分：手工反转索引表（词 -> 电影 id）
-- 这正是老师数据集里那张空的 movie_title_ft_index2 所对应的思路。
-- ⚠️ 这一步很慢（要拆 1285 万个标题），预计数分钟到十几分钟。
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS imdb.title_words;
CREATE TABLE imdb.title_words (
    word    VARCHAR(200) NOT NULL,   -- 实测最长词条 29 字符，但留足余量
    movieid BIGINT       NOT NULL
);

INSERT INTO imdb.title_words (word, movieid)
SELECT lower(w), m.movieid
  FROM imdb.movies m,
       LATERAL regexp_split_to_table(m.title, '[^A-Za-z0-9]+') AS w
 WHERE length(w) >= 3;

CREATE INDEX IF NOT EXISTS idx_title_words_word  ON imdb.title_words (word);
CREATE INDEX IF NOT EXISTS idx_title_words_movie ON imdb.title_words (movieid);
ANALYZE imdb.title_words;

-- 记录反转索引的体积，报告 E7 要用
SELECT pg_size_pretty(pg_total_relation_size('imdb.title_words')) AS inverted_index_size;

-- ---------------------------------------------------------------------------
-- 重新收集统计信息，让优化器知道索引的存在
-- ---------------------------------------------------------------------------
ANALYZE filmdb.movies;  ANALYZE filmdb.people;   ANALYZE filmdb.credits;
ANALYZE imdb.movies;    ANALYZE imdb.people;     ANALYZE imdb.credits;
ANALYZE imdb.alt_titles;
ANALYZE flights.flights; ANALYZE taobao.ad_clicks;
