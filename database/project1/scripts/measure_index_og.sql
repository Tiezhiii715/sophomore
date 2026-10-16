\timing on
SET client_min_messages = warning;

CREATE INDEX IF NOT EXISTS idx_title_words_word  ON imdb.title_words (word);
CREATE INDEX IF NOT EXISTS idx_title_words_movie ON imdb.title_words (movieid);

\echo '=== 反转索引表体积 ==='
SELECT pg_size_pretty(pg_total_relation_size('imdb.title_words')) AS 反转索引总大小;

\echo '=== 1) 强制顺序扫描（模拟"无索引"基线）==='
SET enable_indexscan = off;
SET enable_bitmapscan = off;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';

\echo '=== 2) 正常执行（允许用索引）==='
SET enable_indexscan = on;
SET enable_bitmapscan = on;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';

\echo '=== 3) 反转索引表查找 ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM imdb.title_words WHERE word = 'love';

\echo '=== 4) 反转索引表 join 回 movies ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM imdb.title_words w JOIN imdb.movies m ON m.movieid = w.movieid
 WHERE w.word = 'love';
