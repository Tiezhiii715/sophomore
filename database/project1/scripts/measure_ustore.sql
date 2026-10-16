\timing on
SET client_min_messages = warning;

\echo '=== Ustore 表：初始体积 ==='
SELECT pg_size_pretty(pg_relation_size('imdb.people_ustore')) AS 表体,
       pg_size_pretty(pg_total_relation_size('imdb.people_ustore')) AS 含索引;

\echo '=== 第 1 轮 ==='
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
SELECT pg_size_pretty(pg_relation_size('imdb.people_ustore')) AS 表体;

\echo '=== 第 2 轮 ==='
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
SELECT pg_size_pretty(pg_relation_size('imdb.people_ustore')) AS 表体;

\echo '=== 第 3 轮 ==='
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
SELECT pg_size_pretty(pg_relation_size('imdb.people_ustore')) AS 表体;

\echo '=== 第 5 轮 ==='
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
SELECT pg_size_pretty(pg_relation_size('imdb.people_ustore')) AS 表体;

\echo '=== 第 6-10 轮 ==='
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
UPDATE imdb.people_ustore SET first_name = replace(first_name,'T','Ttt'), surname = replace(surname,'T','Ttt') WHERE first_name LIKE '%T%' OR surname LIKE '%T%';

\echo '=== 10 轮后体积 ==='
SELECT pg_size_pretty(pg_relation_size('imdb.people_ustore')) AS 表体,
       pg_size_pretty(pg_total_relation_size('imdb.people_ustore')) AS 含索引;
