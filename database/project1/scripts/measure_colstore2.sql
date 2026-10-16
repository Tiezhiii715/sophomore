\timing on
SET client_min_messages = warning;
SET query_dop = 1;

\echo '===== 行存 x3 ====='
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights GROUP BY airline ORDER BY 2 DESC;
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights GROUP BY airline ORDER BY 2 DESC;
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights GROUP BY airline ORDER BY 2 DESC;

\echo '===== 列存 dop=1 x3 ====='
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights_col GROUP BY airline ORDER BY 2 DESC;
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights_col GROUP BY airline ORDER BY 2 DESC;
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights_col GROUP BY airline ORDER BY 2 DESC;

\echo '===== 列存 dop=4 x3 ====='
SET query_dop = 4;
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights_col GROUP BY airline ORDER BY 2 DESC;
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights_col GROUP BY airline ORDER BY 2 DESC;
EXPLAIN (ANALYZE, COSTS OFF) SELECT airline, avg(arr_delay), count(*) FROM flights.flights_col GROUP BY airline ORDER BY 2 DESC;
