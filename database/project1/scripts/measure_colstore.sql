\timing on
SET client_min_messages = warning;

\echo ''
\echo '===== 先把 query_dop 复位 ====='
ALTER SYSTEM SET query_dop = 1;
SET query_dop = 1;
SHOW query_dop;

\echo ''
\echo '===== A) 行存 flights.flights，dop=1 ====='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT airline, avg(arr_delay) AS avg_delay, count(*) AS n
  FROM flights.flights GROUP BY airline ORDER BY avg_delay DESC;

\echo ''
\echo '===== B) 列存 flights.flights_col，dop=1 ====='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT airline, avg(arr_delay) AS avg_delay, count(*) AS n
  FROM flights.flights_col GROUP BY airline ORDER BY avg_delay DESC;

\echo ''
\echo '===== C) 列存 flights.flights_col，dop=4 ====='
SET query_dop = 4;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT airline, avg(arr_delay) AS avg_delay, count(*) AS n
  FROM flights.flights_col GROUP BY airline ORDER BY avg_delay DESC;
