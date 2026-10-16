-- =============================================================================
--  init_pg.sql   --  PostgreSQL 16 (native Windows install)
--  DBMS Performance Evaluation Project
--
--  HOW TO RUN
--    1) create the database once:      createdb bench
--    2) then:                          psql -d bench -f init_pg.sql
--    (the \copy lines are CLIENT side, so no PostgreSQL service permission issue)
--
--  FOUR SCHEMAS, ONE UNIFIED TABLE SHAPE
--    filmdb.*   teacher supplied dataset (baseline + same-source scale ladder)
--    imdb.*     IMDb bulk dataset (11.7M movies / 14.5M people / 30M credits)
--    flights.*  US BTS on-time performance (wide table, 34 columns)
--    taobao.*   Alibaba Taobao ad click log (21 columns)
--
--  Order of work:
--    STEP 1  DDL          -> tables WITHOUT indexes (the "no index" baseline)
--    STEP 2  IMPORT       -> \copy from E:/DBMS-Project-Data/csv/...
--    STEP 3  EXPERIMENTS on the un-indexed tables
--    STEP 4  INDEXES      -> run section 4, then repeat the same experiments
-- =============================================================================

\timing on
SET client_min_messages = warning;

-- =============================================================================
-- STEP 1  DDL
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS filmdb;
CREATE SCHEMA IF NOT EXISTS imdb;
CREATE SCHEMA IF NOT EXISTS flights;
CREATE SCHEMA IF NOT EXISTS taobao;

-- ---------- filmdb / imdb : identical relational shape -----------------------
CREATE TABLE IF NOT EXISTS filmdb.movies (
    movieid       BIGINT       PRIMARY KEY,
    title         VARCHAR(400) NOT NULL,
    country       CHAR(2),
    year_released INTEGER,
    runtime       INTEGER
);
CREATE TABLE IF NOT EXISTS filmdb.people (
    peopleid   BIGINT       PRIMARY KEY,
    first_name VARCHAR(200),
    surname    VARCHAR(200),          -- nullable: the source dump contains empty names
    born       INTEGER,
    died       INTEGER,
    gender     CHAR(1)
);
CREATE TABLE IF NOT EXISTS filmdb.credits (
    movieid     BIGINT NOT NULL,
    peopleid    BIGINT NOT NULL,
    credited_as CHAR(1) NOT NULL
);
CREATE TABLE IF NOT EXISTS filmdb.countries (
    country_code CHAR(2)     PRIMARY KEY,
    country_name VARCHAR(60) NOT NULL,
    continent    VARCHAR(20) NOT NULL
);
CREATE TABLE IF NOT EXISTS filmdb.alt_titles (
    titleid BIGINT PRIMARY KEY,
    movieid BIGINT,
    title   VARCHAR(400) NOT NULL
);

CREATE TABLE IF NOT EXISTS imdb.movies (
    movieid       BIGINT        PRIMARY KEY,
    title         VARCHAR(1000) NOT NULL,   -- IMDb 实测最长标题 482 字符，留足余量
    country       CHAR(2),
    year_released INTEGER,
    runtime       INTEGER
);
CREATE TABLE IF NOT EXISTS imdb.people (
    peopleid   BIGINT       PRIMARY KEY,
    first_name VARCHAR(200),
    surname    VARCHAR(200),          -- nullable: some IMDb names have a single token
    born       INTEGER,
    died       INTEGER,
    gender     CHAR(1)
);
CREATE TABLE IF NOT EXISTS imdb.credits (
    movieid     BIGINT NOT NULL,
    peopleid    BIGINT NOT NULL,
    credited_as CHAR(1) NOT NULL
);
-- Chinese / HK / TW alternative titles (UTF-8, 3-byte characters)
CREATE TABLE IF NOT EXISTS imdb.alt_titles (
    titleid  BIGINT PRIMARY KEY,
    movieid  BIGINT,
    title    VARCHAR(400) NOT NULL,
    region   CHAR(2),
    language VARCHAR(20)
);

-- ---------- flights : one wide table ----------------------------------------
CREATE TABLE IF NOT EXISTS flights.flights (
    flight_date     DATE,
    airline         VARCHAR(10),
    tail_num        VARCHAR(12),
    flight_num      INTEGER,
    origin          VARCHAR(6),
    origin_city     VARCHAR(60),
    origin_state    VARCHAR(30),
    dest            VARCHAR(6),
    dest_city       VARCHAR(60),
    dest_state      VARCHAR(30),
    crs_dep_time    VARCHAR(6),
    dep_time        VARCHAR(6),
    dep_delay       NUMERIC(8,2),
    dep_delay_min   NUMERIC(8,2),
    taxi_out        NUMERIC(8,2),
    wheels_off      VARCHAR(6),
    wheels_on       VARCHAR(6),
    taxi_in         NUMERIC(8,2),
    crs_arr_time    VARCHAR(6),
    arr_time        VARCHAR(6),
    arr_delay       NUMERIC(8,2),
    arr_delay_min   NUMERIC(8,2),
    cancelled       NUMERIC(2,1),
    cancellation_code VARCHAR(2),
    diverted        NUMERIC(2,1),
    crs_elapsed     NUMERIC(8,2),
    actual_elapsed  NUMERIC(8,2),
    air_time        NUMERIC(8,2),
    distance        NUMERIC(10,2),
    carrier_delay   NUMERIC(8,2),
    weather_delay   NUMERIC(8,2),
    nas_delay       NUMERIC(8,2),
    security_delay  NUMERIC(8,2),
    late_aircraft_delay NUMERIC(8,2)
);

-- ---------- taobao : ad click log -------------------------------------------
CREATE TABLE IF NOT EXISTS taobao.ad_clicks (
    row_id               BIGINT PRIMARY KEY,
    clk                  SMALLINT,
    btag_his             TEXT,
    cate_his             TEXT,
    brand_his            TEXT,
    userid               BIGINT,
    cms_segid            INTEGER,
    cms_group_id         INTEGER,
    final_gender_code    SMALLINT,
    age_level            SMALLINT,
    pvalue_level         SMALLINT,
    shopping_level       SMALLINT,
    occupation           SMALLINT,
    new_user_class_level SMALLINT,
    adgroup_id           BIGINT,
    cate_id              BIGINT,
    campaign_id          BIGINT,
    customer             BIGINT,
    brand                BIGINT,
    price                NUMERIC(14,6),
    pid                  VARCHAR(10),
    btag                 VARCHAR(10)
);

-- =============================================================================
-- STEP 2  IMPORT   (adjust the root path if you installed elsewhere)
-- =============================================================================
\set csv 'E:/DBMS-Project-Data/csv'

-- ---- teacher filmdb ---------------------------------------------------------
\copy filmdb.countries  FROM 'E:/DBMS-Project-Data/csv/filmdb/countries.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy filmdb.movies     FROM 'E:/DBMS-Project-Data/csv/filmdb/movies.tsv'     WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy filmdb.people     FROM 'E:/DBMS-Project-Data/csv/filmdb/people.tsv'     WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy filmdb.credits    FROM 'E:/DBMS-Project-Data/csv/filmdb/credits.tsv'    WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy filmdb.alt_titles FROM 'E:/DBMS-Project-Data/csv/filmdb/alt_titles.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- ---- IMDb (comment the credits line out if you want a smaller dataset) ------
\copy imdb.movies     FROM 'E:/DBMS-Project-Data/csv/imdb/movies.tsv'     WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy imdb.people     FROM 'E:/DBMS-Project-Data/csv/imdb/people.tsv'     WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy imdb.credits    FROM 'E:/DBMS-Project-Data/csv/imdb/credits.tsv'    WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy imdb.alt_titles FROM 'E:/DBMS-Project-Data/csv/imdb/alt_titles.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- ---- flights ---------------------------------------------------------------
\copy flights.flights FROM 'E:/DBMS-Project-Data/csv/flights/flights.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- ---- taobao ----------------------------------------------------------------
\copy taobao.ad_clicks FROM 'E:/DBMS-Project-Data/csv/taobao/taobao_ad.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- ---- same-source scale ladder (filmdb x10 / x100 / x1000) -------------------
CREATE SCHEMA IF NOT EXISTS scale10;
CREATE SCHEMA IF NOT EXISTS scale100;
CREATE SCHEMA IF NOT EXISTS scale1000;
CREATE TABLE IF NOT EXISTS scale10.movies   (LIKE filmdb.movies);
CREATE TABLE IF NOT EXISTS scale10.people   (LIKE filmdb.people);
CREATE TABLE IF NOT EXISTS scale10.credits  (LIKE filmdb.credits);
CREATE TABLE IF NOT EXISTS scale100.movies  (LIKE filmdb.movies);
CREATE TABLE IF NOT EXISTS scale100.people  (LIKE filmdb.people);
CREATE TABLE IF NOT EXISTS scale100.credits (LIKE filmdb.credits);
CREATE TABLE IF NOT EXISTS scale1000.movies  (LIKE filmdb.movies);
CREATE TABLE IF NOT EXISTS scale1000.people  (LIKE filmdb.people);
CREATE TABLE IF NOT EXISTS scale1000.credits (LIKE filmdb.credits);

-- uncomment when you want the ladder (x1000 takes a long time to import)
-- \copy scale10.movies  FROM 'E:/DBMS-Project-Data/csv/scale/filmdb_x10/movies.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale10.people  FROM 'E:/DBMS-Project-Data/csv/scale/filmdb_x10/people.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale10.credits FROM 'E:/DBMS-Project-Data/csv/scale/filmdb_x10/credits.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale100.movies  FROM 'E:/DBMS-Project-Data/csv/scale/filmdb_x100/movies.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale100.people  FROM 'E:/DBMS-Project-Data/csv/scale/filmdb_x100/people.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale100.credits FROM 'E:/DBMS-Project-Data/csv/scale/filmdb_x100/credits.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- refresh planner statistics once, right after import
ANALYZE filmdb.movies;  ANALYZE filmdb.people;  ANALYZE filmdb.credits;
ANALYZE imdb.movies;    ANALYZE imdb.people;    ANALYZE imdb.credits;
ANALYZE flights.flights; ANALYZE taobao.ad_clicks;

-- =============================================================================
-- 到这里为止，数据库是"无索引"状态 —— 这是 E1/E3/E4/E7 的对照组基线。
--
-- 索引已经拆到单独的文件 indexes_pg.sql，**必须在无索引实验全部跑完之后**再执行：
--     psql -h 127.0.0.1 -p 5432 -U postgres -d bench -f indexes_pg.sql
--
-- 这样可以保证：
--   ① 先测"没有索引"时的耗时  → 对照组
--   ② 建索引，记录建索引本身花的时间（这本身也是一个指标）
--   ③ 再测同一批查询          → 实验组
-- =============================================================================

