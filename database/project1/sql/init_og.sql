-- =============================================================================
--  init_og.sql   --  openGauss 5.0.0 (Docker container, WSL2 backend)
--  DBMS Performance Evaluation Project
--
--  This file is the openGauss twin of init_pg.sql.  The four schemas and every
--  column keep EXACTLY the same name, type and order as the PostgreSQL version,
--  so that one Java program and one set of SQL statements can drive both DBMSs.
--
-- =============================================================================
--  HOW TO RUN  (all commands are typed on the WINDOWS host, in PowerShell)
-- =============================================================================
--
--  STEP 0  find the container name (in the output, look at the NAMES column)
--      docker ps
--
--  STEP 1  copy the data AND this script INTO the container
--      docker cp "E:\DBMS-Project-Data\csv"                    <容器名>:/tmp/csv
--      docker cp "E:\deepseek harness\database proj1\deliverables\init_og.sql" <容器名>:/tmp/init_og.sql
--
--  STEP 2  give the files to the database OS user (THIS STEP IS MANDATORY)
--          docker cp writes as root; the database runs as "omm" and cannot read
--          root-owned files.  Without this chown every \copy fails with
--          "could not open file ... Permission denied".
--      docker exec -u root <容器名> chown -R omm:omm /tmp/csv /tmp/init_og.sql
--
--  STEP 3  enter the container and switch to the database OS user
--      docker exec -it <容器名> bash
--      su - omm
--
--  STEP 4  (optional) create a dedicated database
--          Run this ONCE, from gsql, connected to the default database:
--      gsql -d postgres -p 5432 -c "CREATE DATABASE bench WITH ENCODING='UTF8' DBCOMPATIBILITY='PG' TEMPLATE=template0;"
--          If it errors, just use the default "postgres" database instead and
--          replace -d bench by -d postgres in every later command.
--
--  STEP 5  run this script
--      gsql -d bench -p 5432 -f /tmp/init_og.sql 2>&1 | tee /tmp/init_og.log
--          (if gsql is not on PATH:  /usr/local/opengauss/bin/gsql ...)
--
--  STEP 6  check that everything arrived
--      gsql -d bench -p 5432 -c "SELECT 'filmdb.movies' t, count(*) FROM filmdb.movies
--        UNION ALL SELECT 'imdb.movies',      count(*) FROM imdb.movies
--        UNION ALL SELECT 'imdb.people',      count(*) FROM imdb.people
--        UNION ALL SELECT 'imdb.credits',     count(*) FROM imdb.credits
--        UNION ALL SELECT 'flights.flights',  count(*) FROM flights.flights
--        UNION ALL SELECT 'taobao.ad_clicks', count(*) FROM taobao.ad_clicks;"
--
-- =============================================================================
--  openGauss vs PostgreSQL : the differences handled in this file
-- =============================================================================
--   #  item                     PostgreSQL                 openGauss 5.0.0
--  --+------------------------+--------------------------+---------------------------
--   1  substring index         pg_trgm + GIN            NOT AVAILABLE.  Replaced by
--                                                       B-tree + an inverted-index
--                                                       TABLE (see STEP 4b).
--   2  data files             client side, any path     must live INSIDE the
--                                                       container; we use /tmp/csv
--   3  COPY ownership          service account reads    gsql \copy reads as "omm",
--                                                       hence the mandatory chown
--   4  storage orientation     row only (heap)          ROW (heap) / COLUMN / USTORE
--   5  UPDATE on column tables n/a                      OFF BY DEFAULT.  A delta
--                                                       table path exists; E7 is
--                                                       read only on purpose.
--   6  EXPLAIN options         ANALYZE, BUFFERS         SAME spelling works:
--                                                       EXPLAIN (ANALYZE, BUFFERS)
--                                                       written-out booleans are
--                                                       TRUE/FALSE, never ON/OFF
--   7  parallelism             parallel query ON by     query_dop defaults to 1
--                              default                  (single threaded)
--   8  constraints             PRIMARY KEY anywhere     row tables: any constraint.
--                                                       column tables: PRIMARY KEY
--                                                       and UNIQUE yes, FOREIGN KEY
--                                                       no.  Our _col tables carry
--                                                       none on purpose (see STEP 5)
--   9  empty string            '' is a real value       with DBCOMPATIBILITY='A'
--                                                       '' becomes NULL -> we create
--                                                       the database with 'PG'
--  10  default DB/user         postgres / postgres      postgres / omm
--
--  Measurement warning (put this in the report):
--    openGauss runs with query_dop = 1 by default while PostgreSQL 16 executes
--    large scans in parallel.  Section 0 below pins query_dop = 1 and this is the
--    FAIR single-threaded baseline.  Section 7 repeats the aggregation with
--    query_dop = 4 so you can also report the parallel numbers separately.
-- =============================================================================

\timing on
SET client_min_messages = warning;
SET maintenance_work_mem = '512MB';

-- =============================================================================
-- SECTION 0  session parameters that affect the comparison
-- =============================================================================
-- intra-query parallelism.  1 = single threaded (openGauss factory default).
SET query_dop = 1;
-- SET query_dop = 4;      -- used later, in SECTION 7, for the parallel run

-- =============================================================================
-- STEP 1  DDL  --  four schemas, one unified table shape
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
    surname    VARCHAR(200) NOT NULL,
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
    title         VARCHAR(1000) NOT NULL,   -- IMDb 实测最长标题 482 字符
    country       CHAR(2),
    year_released INTEGER,
    runtime       INTEGER
);
CREATE TABLE IF NOT EXISTS imdb.people (
    peopleid   BIGINT       PRIMARY KEY,
    first_name VARCHAR(200),
    surname    VARCHAR(200),          -- 可空：IMDb 有单名人物，老师数据有空串
    born       INTEGER,
    died       INTEGER,
    gender     CHAR(1)
);
CREATE TABLE IF NOT EXISTS imdb.credits (
    movieid     BIGINT NOT NULL,
    peopleid    BIGINT NOT NULL,
    credited_as CHAR(1) NOT NULL
);
-- Chinese / HK / TW alternative titles (UTF-8, 3-byte characters).
-- Used by E1 to compare a 1-byte-per-character alphabet with a 3-byte one.
CREATE TABLE IF NOT EXISTS imdb.alt_titles (
    titleid  BIGINT PRIMARY KEY,
    movieid  BIGINT,
    title    VARCHAR(400) NOT NULL,
    region   CHAR(2),
    language VARCHAR(20)
);

-- ---------- flights : one wide table (34 columns) ---------------------------
-- Identical column list to flights.flights in init_pg.sql.  NUMERIC and DATE are
-- written exactly as in PostgreSQL; openGauss accepts both spellings.
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

-- ---------- taobao : ad click log (21 columns) ------------------------------
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
-- STEP 2  IMPORT via \copy   (CLIENT side -- no docker cp, no chown needed)
-- =============================================================================
-- \copy is a CLIENT side meta-command: the psql/gsql process opens the file
-- itself and streams the rows to the server over the wire.  The database
-- therefore never touches the Windows file system, and NO `docker cp` plus
-- `chown -R omm:omm` dance is required.
--
-- Run it from the HOST with the PostgreSQL client:
--   psql -h 127.0.0.1 -p <宿主机端口> -U gaussdb -d bench -f init_og.sql
-- (that is how this project imported openGauss; the paths below are Windows paths)
--
-- If instead you prefer to run gsql INSIDE the container, copy the data first
--   docker cp "E:\DBMS-Project-Data\csv" opengauss-lab:/tmp/csv
--   docker exec -u root opengauss-lab chown -R omm:omm /tmp/csv
-- and then replace the "E:/DBMS-Project-Data/csv" prefix below with "/tmp/csv".

-- ---- teacher filmdb ---------------------------------------------------------
\copy filmdb.countries  FROM 'E:/DBMS-Project-Data/csv/filmdb/countries.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy filmdb.movies     FROM 'E:/DBMS-Project-Data/csv/filmdb/movies.tsv'     WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy filmdb.people     FROM 'E:/DBMS-Project-Data/csv/filmdb/people.tsv'     WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy filmdb.credits    FROM 'E:/DBMS-Project-Data/csv/filmdb/credits.tsv'    WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy filmdb.alt_titles FROM 'E:/DBMS-Project-Data/csv/filmdb/alt_titles.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- ---- IMDb -------------------------------------------------------------------
\copy imdb.movies     FROM 'E:/DBMS-Project-Data/csv/imdb/movies.tsv'     WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy imdb.people     FROM 'E:/DBMS-Project-Data/csv/imdb/people.tsv'     WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy imdb.credits    FROM 'E:/DBMS-Project-Data/csv/imdb/credits.tsv'    WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
\copy imdb.alt_titles FROM 'E:/DBMS-Project-Data/csv/imdb/alt_titles.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- ---- flights ---------------------------------------------------------------
\copy flights.flights FROM 'E:/DBMS-Project-Data/csv/flights/flights.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- ---- taobao ----------------------------------------------------------------
\copy taobao.ad_clicks FROM 'E:/DBMS-Project-Data/csv/taobao/taobao_ad.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- ---- same-source scale ladder (filmdb x10 / x100 / x1000) ------------------
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
-- if "LIKE" is rejected by your build, replace each line by the explicit column
-- list copied from filmdb.movies / filmdb.people / filmdb.credits above.

-- uncomment when you want the ladder (x1000 takes a long time to import)
-- \copy scale10.movies  FROM '/tmp/csv/scale/filmdb_x10/movies.tsv'   WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale10.people  FROM '/tmp/csv/scale/filmdb_x10/people.tsv'   WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale10.credits FROM '/tmp/csv/scale/filmdb_x10/credits.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale100.movies  FROM '/tmp/csv/scale/filmdb_x100/movies.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale100.people  FROM '/tmp/csv/scale/filmdb_x100/people.tsv'  WITH (FORMAT text, DELIMITER E'\t', NULL '\N')
-- \copy scale100.credits FROM '/tmp/csv/scale/filmdb_x100/credits.tsv' WITH (FORMAT text, DELIMITER E'\t', NULL '\N')

-- =============================================================================
-- STEP 3  refresh planner statistics once, right after the import
-- =============================================================================
ANALYZE filmdb.movies;   ANALYZE filmdb.people;   ANALYZE filmdb.credits;
ANALYZE imdb.movies;     ANALYZE imdb.people;     ANALYZE imdb.credits;
ANALYZE imdb.alt_titles; ANALYZE flights.flights; ANALYZE taobao.ad_clicks;

-- =============================================================================
-- STEP 4  INDEXES  --  run this ONLY AFTER the "no index" measurements
-- =============================================================================
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

-- -----------------------------------------------------------------------------
-- STEP 4b  REPLACEMENT FOR THE POSTGRESQL TRIGRAM INDEX
-- -----------------------------------------------------------------------------
-- init_pg.sql ends with
--     CREATE EXTENSION pg_trgm;
--     CREATE INDEX ... USING gin (title gin_trgm_ops);
-- which makes  LIKE '%word%'  fast in PostgreSQL.
--
-- openGauss 5.0.0 does NOT ship the pg_trgm extension, so that trick is not
-- available here.  Two things replace it, and BOTH are part of the experiment:
--
--   (a) an ordinary B-tree index -- it only helps PREFIX searches
--       (title LIKE 'Love%'), never a leading-wildcard search (title LIKE '%love%').
--       This is exactly the point of E1: see for yourself that the index changes
--       nothing until the query shape lets the planner use it.
--   (b) an inverted-index TABLE built by us (the "reorganise the data into a
--       different format for faster retrieval" idea from the project hints, and
--       the same shape as the teacher's empty movie_title_ft_index2 table).
--
-- There is also an optional full-text route (STEP 4c) that IS available in
-- openGauss: GIN over to_tsvector('english', title).  It is token based, so it
-- helps word searches but not arbitrary substrings.

CREATE TABLE IF NOT EXISTS imdb.title_words (
    word    VARCHAR(60) NOT NULL,
    movieid BIGINT      NOT NULL
);

-- Populate it.  Two equivalent statements are given because LATERAL support
-- varies between openGauss builds: try (b) first, it is a bit faster.
-- (a) plain correlated set-returning function in FROM
INSERT INTO imdb.title_words (word, movieid)
SELECT lower(w), m.movieid
  FROM imdb.movies m,
       regexp_split_to_table(
           regexp_replace(lower(m.title), '[^a-z0-9]+', ' ', 'g'), ' ') AS w
 WHERE length(w) >= 2;

-- (b) explicit LATERAL form -- same result, different plan
-- INSERT INTO imdb.title_words (word, movieid)
-- SELECT lower(w), m.movieid
--   FROM imdb.movies m
--   CROSS JOIN LATERAL regexp_split_to_table(
--           regexp_replace(lower(m.title), '[^a-z0-9]+', ' ', 'g'), ' ') AS w
--  WHERE length(w) >= 2;

CREATE INDEX IF NOT EXISTS idx_title_words_word ON imdb.title_words (word);
CREATE INDEX IF NOT EXISTS idx_title_words_movie ON imdb.title_words (movieid);
ANALYZE imdb.title_words;

-- sanity check: the two paths must return the same number of DISTINCT movies
--   SELECT count(DISTINCT movieid) FROM imdb.movies     WHERE title LIKE '%love%';
--   SELECT count(DISTINCT movieid) FROM imdb.title_words WHERE word = 'love';

-- -----------------------------------------------------------------------------
-- STEP 4c  OPTIONAL full-text index (available in openGauss, unlike pg_trgm)
-- -----------------------------------------------------------------------------
-- Only  'word'  searches, not substrings.  Keep it out of the headline numbers
-- unless you decide to publish it as a separate row in the E1 table.
--   CREATE INDEX IF NOT EXISTS idx_imdb_movies_title_fts
--       ON imdb.movies USING gin (to_tsvector('english', title));

-- There is deliberately NO index for Chinese text: the CJK branch of E1 uses
--     SELECT ... FROM imdb.alt_titles WHERE title LIKE '%爱%'
-- and openGauss has no CJK-aware full-text configuration for it.  On both DBMSs
-- this query is a full scan, which is precisely the comparison we want.

ANALYZE imdb.movies; ANALYZE imdb.credits; ANALYZE flights.flights; ANALYZE taobao.ad_clicks;

-- =============================================================================
-- STEP 5  COLUMN-STORE TABLES   (E7 part 2: row store vs column store)
-- =============================================================================
-- PostgreSQL 16 has NO native column store.  openGauss does, and this is the
-- single clearest "specialisation advantage" of openGauss we can measure: an
-- analytical aggregation reads only the columns it mentions.
--
-- RESTRICTIONS (checked against the openGauss 5.0 CREATE TABLE reference):
--   * a COLUMN table DOES support PRIMARY KEY and UNIQUE, both as a column
--     constraint and as a table constraint.  It does NOT support a FOREIGN KEY.
--     The DDL below nevertheless declares bare columns on purpose: E7 measures
--     the pure scan / aggregate cost, and any key would add an index that
--     changes the very plan we want to observe.
--   * UPDATE / DELETE on a column table is OFF by default.  openGauss 5.0 added
--     a "delta table" path for it (enable_delta_store plus deltarow_threshold),
--     but that is a different storage mode from the one E7 compares.  E7 is
--     therefore READ-ONLY: never point the E2 / E10 update statements at a
--     *_col table.
--   * COPY into a column table works, but INSERT ... SELECT ... is faster for a
--     bulk fill, which is what we use below.
--   * COMPRESSION for a column table accepts YES / NO / LOW / MIDDLE / HIGH, and
--     LOW is the default, so the clause below can simply be deleted if a build
--     rejects it -- ORIENTATION alone already gives you a column store.

-- ------ flights.flights_col : the wide table, the strongest case -------------
CREATE TABLE IF NOT EXISTS flights.flights_col (
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
) WITH (ORIENTATION = COLUMN, COMPRESSION = LOW);
-- COMPRESSION = LOW keeps the comparison honest (least CPU spent on decompress).
-- Use COMPRESSION = HIGH in a side note if you also want to report space savings.
-- If your build rejects COMPRESSION, delete that clause; ORIENTATION is enough.

-- ------ imdb.movies_col : same rows as imdb.movies, columnar layout ----------
CREATE TABLE IF NOT EXISTS imdb.movies_col (
    movieid       BIGINT,
    title         VARCHAR(400) NOT NULL,
    country       CHAR(2),
    year_released INTEGER,
    runtime       INTEGER
) WITH (ORIENTATION = COLUMN, COMPRESSION = LOW);

-- ------ taobao.ad_clicks_col : 21 columns, three of them long text -----------
-- The widest table in the project: a row-store scan must read the three *_his
-- TEXT columns even when the query only wants userid and clk, a column store
-- does not.  This is where the gap should be the largest.
CREATE TABLE IF NOT EXISTS taobao.ad_clicks_col (
    row_id               BIGINT,
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
) WITH (ORIENTATION = COLUMN, COMPRESSION = LOW);

-- ---- fill the column tables from the row tables ----------------------------
-- \timing on (set at the top of this file) prints how long each one takes.
INSERT INTO imdb.movies_col       SELECT * FROM imdb.movies;
INSERT INTO flights.flights_col   SELECT * FROM flights.flights;
INSERT INTO taobao.ad_clicks_col  SELECT * FROM taobao.ad_clicks;

ANALYZE imdb.movies_col; ANALYZE flights.flights_col; ANALYZE taobao.ad_clicks_col;

-- ---- E7 part 2, measurement B: physical size, row store vs column store ----
-- Run this and write both numbers into the report:
--   SELECT 'imdb.movies row'        AS object, pg_relation_size('imdb.movies')        AS bytes
--   UNION ALL SELECT 'imdb.movies col',        pg_relation_size('imdb.movies_col')
--   UNION ALL SELECT 'flights.flights row',    pg_relation_size('flights.flights')
--   UNION ALL SELECT 'flights.flights col',    pg_relation_size('flights.flights_col')
--   UNION ALL SELECT 'taobao.ad_clicks row',   pg_relation_size('taobao.ad_clicks')
--   UNION ALL SELECT 'taobao.ad_clicks col',   pg_relation_size('taobao.ad_clicks_col');

-- =============================================================================
-- STEP 6  USTORE TABLE   (experiment E10: table bloat after repeated UPDATE)
-- =============================================================================
-- PostgreSQL's heap keeps every dead row version in the page until VACUUM runs,
-- so ten full-table UPDATEs make the table visibly fatter and slower.
-- openGauss ships a second row-store engine, USTORE (in-place update), designed
-- to remove exactly that bloat.
--
-- SYNTAX: WITH (STORAGE_TYPE = USTORE) is the spelling documented for 5.0.
-- The block below is therefore written to run as-is; the note afterwards tells
-- you what to do if your particular build rejects it.
CREATE TABLE IF NOT EXISTS imdb.people_ustore (
    peopleid   BIGINT       PRIMARY KEY,
    first_name VARCHAR(200),
    surname    VARCHAR(200) NOT NULL,
    born       INTEGER,
    died       INTEGER,
    gender     CHAR(1)
) WITH (STORAGE_TYPE = USTORE);

--   If the statement above fails with
--       ERROR:  unrecognized parameter "storage_type"
--   then your build simply does not carry the USTORE engine.  Do NOT invent a
--   workaround: record the exact error in the report, skip every people_ustore
--   statement, and run E10 as heap-before-VACUUM versus heap-after-VACUUM only.
--   The bloat experiment stays valid, it just loses the engine contrast.
--
--   The older "WITH (ORIENTATION=ROW) ... WITH (STORAGE_TYPE=USTORE)" combined
--   spelling that some blog posts show is NOT valid in 5.0.0 ; do not use it.
--
--   USTORE PREREQUISITE (from the reference): track_counts and track_activities
--   must be ON for a ustore table, otherwise it bloats for reasons that have
--   nothing to do with the update pattern and the E10 comparison loses meaning.
--       SHOW track_counts;      -- expect on
--       SHOW track_activities;  -- expect on
--   Both default to on; switch them on and restart if not.
--   Also: a ustore table rejects DEFERRABLE / INITIALLY DEFERRED, and it cannot
--   be a global temporary table.

-- HOW TO VERIFY that you really got a ustore table (run all three):
--   1) SELECT relname, reloptions FROM pg_class
--        WHERE relname IN ('people','people_ustore');
--      the ustore row must show reloptions = {storage_type=ustore} and the heap
--      row must show nothing.  An empty reloptions means you did NOT get one.
--   2) SELECT amname FROM pg_am WHERE amname IN ('ustore','ubtree');
--      confirms the engine and its index method exist in this build.
--   3) \d+ imdb.people_ustore        -- gsql prints the storage engine

INSERT INTO imdb.people_ustore SELECT * FROM imdb.people;
ANALYZE imdb.people_ustore;

-- =============================================================================
-- SECTION 7  EXPERIMENT SQL  (E1 .. E11)  --  copy the statements you need
-- =============================================================================
-- CANONICAL EXPERIMENT NUMBERING.  init_og.sql, init_pg.sql, MovieBench.java and
-- ProjectReport.md all use this table; the report tables are keyed on it, so if
-- you renumber anything you must renumber it in all four places.
--
--   E1   标题关键词检索  (teacher's requirement)   MovieBench: search <词> [en|zh]
--   E2   人名变长更新 T -> Ttt (teacher's req.)    MovieBench: update T Ttt
--   E2b  等长更新对照 T -> X                      MovieBench: update T X
--   E3   主键点查                                 MovieBench: point <id>
--   E4   三表连接 JOIN                            MovieBench: join <年> <类别>
--   E5   聚合统计                                 MovieBench: agg year
--   E6   并发检索                                 MovieBench: conc <线程数>
--   E7   存储布局与索引机制                        MovieBench: layout csv|fixed|mmap|invidx
--          part 1  four FILE layouts             -> 纯文件侧，本文件里没有 SQL
--          part 2  row store vs column store     -> 下面的 E7.1 .. E7.5
--   E8   宽表聚合（航班 / 淘宝）                   MovieBench: agg airline | agg cate
--   E9   批量写入与事务大小                        MovieBench: batch <批量大小>
--   E10  更新后表膨胀与空间回收                    MovieBench: bloat <轮数>
--   E11  中断写入：原子性与持久性                  MovieBench: tx kill
--
-- Conventions used below
--   * TIMING.  "\timing on" (already set) prints the client side elapsed time.
--     For the server side number always wrap the statement in EXPLAIN.
--         EXPLAIN (ANALYZE, BUFFERS) SELECT ... ;
--     openGauss accepts exactly the same spelling as PostgreSQL.  If you prefer
--     the boolean written out, it is TRUE / FALSE -- never ON / OFF:
--         EXPLAIN (ANALYZE TRUE, BUFFERS TRUE) SELECT ... ;
--     Record BOTH numbers; the report explains why they differ.
--   * DML UNDER EXPLAIN.  ANALYZE really executes the statement, UPDATE included.
--     The official openGauss recipe for measuring an UPDATE without keeping the
--     change is:
--         START TRANSACTION;
--         EXPLAIN ANALYZE UPDATE ... ;
--         ROLLBACK;
--     Use it for every rehearsal; for the recorded run you still want the real
--     change to persist, so there you execute the UPDATE normally.
--   * REPEATS.  Every measurement = 3 warm-up runs + 5 recorded runs, and you
--     report the MEDIAN.  See OPERATIONS.md section G.
--   * FAIRNESS.  query_dop is pinned to 1 in SECTION 0.  Run everything once at
--     query_dop = 1, then repeat the aggregations at query_dop = 4 in SECTION 7.6.

-- -----------------------------------------------------------------------------
-- E1  标题 / 文本 关键词检索   (teacher's requirement)
--     对比：文件顺序扫描  vs  无索引  vs  B-tree  vs  反转索引表  vs  中文文本
-- -----------------------------------------------------------------------------
-- E1.1 English substring, no index yet  (expect a full sequential scan)
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';
SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';

-- E1.2 the same query AFTER STEP 4 -- still a sequential scan on openGauss,
--      because a leading wildcard cannot use a B-tree.  This is a finding.
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';

-- E1.3 prefix search IS helped by the B-tree (contrast with E1.2)
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM imdb.movies WHERE title LIKE 'Love%';

-- E1.4 the inverted-index table, the openGauss answer to the missing trigram index
EXPLAIN (ANALYZE, BUFFERS) SELECT count(DISTINCT movieid) FROM imdb.title_words WHERE word = 'love';

-- E1.5 selectivity sweep: the same query shape at three hit ratios.  This is the
--      data behind the "elapsed time vs hit ratio" curve inside report table 3.
SELECT count(*) FROM imdb.movies WHERE title LIKE '%the%';    -- very common
SELECT count(*) FROM imdb.movies WHERE title LIKE '%love%';   -- medium
SELECT count(*) FROM imdb.movies WHERE title LIKE '%zorro%';  -- rare

-- E1.6 CJK text: 3 bytes per character in UTF-8.  Full scan on BOTH DBMSs.
--      MovieBench equivalent: search 爱 zh
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM imdb.alt_titles WHERE title LIKE '%爱%';
SELECT count(*) FROM imdb.alt_titles WHERE title LIKE '%爱%';

-- E1.7 the teacher's own table, for the baseline dataset
SELECT count(*) FROM filmdb.movies WHERE title LIKE '%love%';

-- -----------------------------------------------------------------------------
-- E2  人名中的 'T' -> 'Ttt'   (teacher's requirement -- a LENGTH-CHANGING update)
--     E2b is the length-preserving control that isolates the "records grow"
--     variable as the single difference between the two runs.
-- -----------------------------------------------------------------------------
-- How many rows the statement will touch, on the baseline dataset:
SELECT count(*) FROM filmdb.people WHERE first_name LIKE '%T%' OR surname LIKE '%T%';
-- On the IMDb table it is millions of rows, so measure it, do not guess:
--   SELECT count(*) FROM imdb.people WHERE first_name LIKE '%T%' OR surname LIKE '%T%';

-- !! WARNING !!  This statement is NOT idempotent: 'Ttt' still contains a 'T',
-- so a second execution turns 'Ttt' into 'Tttttt'.  Before EVERY measured run you
-- must restore the original rows, e.g.
--     INSERT INTO imdb.people SELECT * FROM imdb.people_backup;   -- see SECTION 8
-- or simply re-run the \copy line from STEP 2.  Use filmdb.people for the first
-- rehearsal, it is 16k rows instead of 14M.

-- E2.1 the measured variable-length update   (MovieBench: update T Ttt)
EXPLAIN (ANALYZE, BUFFERS)
UPDATE filmdb.people
   SET first_name = replace(first_name, 'T', 'Ttt'),
       surname    = replace(surname,    'T', 'Ttt')
 WHERE first_name LIKE '%T%' OR surname LIKE '%T%';

-- E2.2 the same update on the big table (restore afterwards!)
-- EXPLAIN (ANALYZE, BUFFERS)
-- UPDATE imdb.people
--    SET first_name = replace(first_name, 'T', 'Ttt'),
--        surname    = replace(surname,    'T', 'Ttt')
--  WHERE first_name LIKE '%T%' OR surname LIKE '%T%';

-- E2b  LENGTH-PRESERVING CONTROL   (MovieBench: update T X)
--      Same number of rows, same number of characters touched, but every
--      replacement is exactly one character long.  Compare against E2.1.
UPDATE filmdb.people
   SET first_name = replace(first_name, 'T', 'X'),
       surname    = replace(surname,    'T', 'X')
 WHERE first_name LIKE '%T%' OR surname LIKE '%T%';

-- E2.3 verify
SELECT count(*) FROM filmdb.people WHERE surname LIKE '%Ttt%';

-- E2.4 the file side does the same thing in MovieBench.java (mode `update T Ttt`);
--      because the records grow, the CSV has to be rewritten in full.

-- -----------------------------------------------------------------------------
-- E3  主键点查 (single-row primary-key lookup)   MovieBench: point <id>
-- -----------------------------------------------------------------------------
-- E3.1 the primary key IS the storage order, so this is already fast; use the
--      SQL form below to see which plan the optimiser picks.
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM imdb.movies WHERE movieid = 1234567;

-- E3.2 a non-key column, before STEP 4 -- sequential scan
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM imdb.movies WHERE title = 'Love Actually';

-- E3.3 the same query after STEP 4 -- index scan
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM imdb.movies WHERE title = 'Love Actually';

-- E3.4 batch of point lookups, the shape a Java client really produces
SELECT * FROM taobao.ad_clicks WHERE userid = 123456;

-- -----------------------------------------------------------------------------
-- E4  三表连接 JOIN   MovieBench: join <年> <类别>
--     (the teacher's lab code already joins movies x countries; here the same
--      idea is pushed to a 30-million-row fact table)
-- -----------------------------------------------------------------------------
-- E4.1 the teacher's two-table join, on the baseline dataset
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.title, c.country_name, c.continent, m.runtime
  FROM filmdb.movies m
  JOIN filmdb.countries c ON m.country = c.country_code
 WHERE m.runtime BETWEEN 90 AND 100
 ORDER BY m.runtime;

-- E4.2 the three-table join at scale: movies x credits x people
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
  FROM imdb.credits cr
  JOIN imdb.movies  m ON m.movieid  = cr.movieid
  JOIN imdb.people  p ON p.peopleid = cr.peopleid
 WHERE m.year_released BETWEEN 2000 AND 2010
   AND m.title LIKE '%love%'
   AND cr.credited_as = 'A';

-- E4.3 the same join, returning rows instead of a count (this is what the Java
--      file-side program has to reimplement by hand with a hash join)
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.movieid, m.title, p.surname, p.first_name
  FROM imdb.credits cr
  JOIN imdb.movies  m ON m.movieid  = cr.movieid
  JOIN imdb.people  p ON p.peopleid = cr.peopleid
 WHERE m.year_released >= 2015
   AND cr.credited_as = 'D'
 LIMIT 200;

-- E4.4 run E4.2 and E4.3 BEFORE and AFTER STEP 4 to show the join algorithms move
--      from hash join / nested loop over a sequential scan to index nested loop.

-- -----------------------------------------------------------------------------
-- E5  聚合统计 GROUP BY   MovieBench: agg year
-- -----------------------------------------------------------------------------
-- E5.1 small table, baseline
SELECT c.continent, count(*) AS countries
  FROM filmdb.countries c
 GROUP BY c.continent
 ORDER BY countries DESC;

-- E5.2 IMDb: films per year
EXPLAIN (ANALYZE, BUFFERS)
SELECT year_released, count(*) AS films
  FROM imdb.movies
 WHERE year_released IS NOT NULL
 GROUP BY year_released
 ORDER BY films DESC
 LIMIT 20;

-- E5.3 IMDb: cast size distribution, a low-cardinality group over 30M rows
EXPLAIN (ANALYZE, BUFFERS)
SELECT cast_size, count(*) AS movies
  FROM (SELECT movieid, count(*) AS cast_size FROM imdb.credits GROUP BY movieid) t
 GROUP BY cast_size
 ORDER BY cast_size
 LIMIT 25;

-- -----------------------------------------------------------------------------
-- E6  并发检索   MovieBench: conc <线程数>
-- -----------------------------------------------------------------------------
-- This experiment is driven by the Java client, which opens N threads against one
-- database; the statements below are what the server side needs while it runs.
--
-- E6.1 how many sessions are attached right now (run while the Java client runs)
SELECT count(*) AS sessions, state
  FROM pg_stat_activity
 GROUP BY state;

-- E6.2 is the thread-pool model switched on?  openGauss defaults to a
--      process-per-connection model just like PostgreSQL; the thread pool is the
--      specialisation we want to test.  It is a POSTMASTER parameter, so it can
--      only be turned on in the config file and a container restart follows.
SHOW enable_thread_pool;
SHOW max_connections;
-- to enable it, inside the container as omm:
--     gs_guc set -D $GAUSSHOME/../data -c "enable_thread_pool = on"
--     gs_ctl restart -D $GAUSSHOME/../data
-- (adjust the -D path to your data directory; see OPERATIONS.md section A)

-- E6.3 long-running queries currently in flight
SELECT pid, now() - query_start AS runtime, state, left(query, 60) AS query
  FROM pg_stat_activity
 WHERE state <> 'idle'
 ORDER BY runtime DESC;

-- -----------------------------------------------------------------------------
-- E7  存储布局与索引机制   MovieBench: layout csv|fixed|mmap|invidx
-- -----------------------------------------------------------------------------
-- PART 1 -- the four FILE layouts (CSV / fixed-length binary / mmap / CSV plus an
-- inverted-index file) is purely a file-side experiment and contains no SQL at
-- all; it lives entirely in MovieBench.java.
--
-- The SQL below is the database-side counterpart of the same question: how much
-- does the index actually cost, and does physical layout (row vs column) change
-- the answer?  This is the "reorganise the data into a different format for
-- faster retrieval" idea from the project hints.
--
-- How much space does each acceleration structure really cost?
SELECT pg_relation_size('imdb.movies')                        AS heap_bytes,
       pg_relation_size('idx_imdb_movies_title')              AS btree_bytes,
       pg_relation_size('imdb.title_words')                   AS invtable_bytes,
       pg_relation_size('idx_title_words_word')               AS invindex_bytes;

-- PART 2 -- row store vs column store.  openGauss only: PostgreSQL has no native
-- column store.  This is the clearest specialisation advantage we can measure.

-- E7.1 row store
EXPLAIN (ANALYZE, BUFFERS)
SELECT airline, count(*) AS n, round(avg(dep_delay),2) AS avg_dep_delay
  FROM flights.flights GROUP BY airline;

-- E7.2 column store -- same rows, same query, different physical layout
EXPLAIN (ANALYZE, BUFFERS)
SELECT airline, count(*) AS n, round(avg(dep_delay),2) AS avg_dep_delay
  FROM flights.flights_col GROUP BY airline;

-- E7.3 the widest table: E8.3 again, row store then column store
EXPLAIN (ANALYZE, BUFFERS)
SELECT cate_id, count(*) AS impressions, round(avg(clk),6) AS ctr
  FROM taobao.ad_clicks GROUP BY cate_id ORDER BY impressions DESC LIMIT 20;

EXPLAIN (ANALYZE, BUFFERS)
SELECT cate_id, count(*) AS impressions, round(avg(clk),6) AS ctr
  FROM taobao.ad_clicks_col GROUP BY cate_id ORDER BY impressions DESC LIMIT 20;

-- E7.4 IMDb, the small case: column storage does NOT always win
EXPLAIN (ANALYZE, BUFFERS) SELECT country, count(*) FROM imdb.movies GROUP BY country;
EXPLAIN (ANALYZE, BUFFERS) SELECT country, count(*) FROM imdb.movies_col GROUP BY country;

-- E7.5 space comparison (see also STEP 5)
SELECT 'flights row' AS object, pg_relation_size('flights.flights')     AS bytes
UNION ALL SELECT 'flights col',  pg_relation_size('flights.flights_col')
UNION ALL SELECT 'taobao row',   pg_relation_size('taobao.ad_clicks')
UNION ALL SELECT 'taobao col',   pg_relation_size('taobao.ad_clicks_col');

-- -----------------------------------------------------------------------------
-- E8  宽表聚合（航班 / 淘宝）   MovieBench: agg airline | agg cate
-- -----------------------------------------------------------------------------
-- The two wide tables of the project.  Every query here is also run against the
-- matching column-store table in E7, so keep the numbers side by side.

-- E8.1 flights: which airline is late the most -- the headline question
EXPLAIN (ANALYZE, BUFFERS)
SELECT airline,
       count(*)                          AS flights,
       round(avg(dep_delay), 2)          AS avg_dep_delay,
       round(avg(case when dep_delay > 15 then 1 else 0 end), 4) AS late_rate
  FROM flights.flights
 GROUP BY airline
 ORDER BY avg_dep_delay DESC;

-- E8.2 flights: delay by origin state
EXPLAIN (ANALYZE, BUFFERS)
SELECT origin_state, count(*) AS flights, round(avg(arr_delay),2) AS avg_arr_delay
  FROM flights.flights
 GROUP BY origin_state
 ORDER BY flights DESC
 LIMIT 20;

-- E8.3 taobao: click-through rate by product category -- the Chinese dataset
EXPLAIN (ANALYZE, BUFFERS)
SELECT cate_id,
       count(*)                AS impressions,
       round(avg(clk), 6)      AS ctr,
       round(avg(price), 4)    AS avg_price
  FROM taobao.ad_clicks
 GROUP BY cate_id
 ORDER BY impressions DESC
 LIMIT 20;

-- -----------------------------------------------------------------------------
-- E9  批量写入与事务大小   MovieBench: batch <批量大小>
-- -----------------------------------------------------------------------------
-- The client reads a fixed number of rows out of imdb.people and inserts them
-- into a dedicated staging table using JDBC batches, committing once per batch.
-- Four batch sizes are compared: 1 / 100 / 1000 / 10000.  The measured quantity
-- is transactions per second, plus the wall time for the whole fixed row count.
--
-- Why a dedicated table: the run has to be repeatable, so it truncates its own
-- target and never touches a table that another experiment measures.
--
-- What this isolates: on the file side, "batch size" has no equivalent at all --
-- a BufferedWriter flushed every row and one flushed every 10 000 rows differ
-- only in how often the OS is asked to write, with no transactional guarantee in
-- either case.  Here the DBMS pays a WAL flush and an fsync per COMMIT, so batch
-- size directly buys durability-per-transaction down.  That trade-off is the
-- finding.

CREATE SCHEMA IF NOT EXISTS bench;

CREATE TABLE IF NOT EXISTS bench.batch_target (
    seq        BIGINT,
    peopleid   BIGINT,
    first_name VARCHAR(200),
    surname    VARCHAR(200),
    born       INTEGER,
    gender     CHAR(1)
);

-- E9.0 before EVERY run (the Java client issues this itself; here so you can also
--      drive the experiment by hand)
TRUNCATE bench.batch_target;

-- E9.1 after every run the row count must be IDENTICAL for all four batch sizes,
--      otherwise you are comparing different amounts of work
--   SELECT count(*) FROM bench.batch_target;

-- E9.2 commit cost of one batch, measured server side.  Change LIMIT to match the
--      batch size you are running.
--   EXPLAIN (ANALYZE, BUFFERS)
--   INSERT INTO bench.batch_target
--   SELECT 1, peopleid, first_name, surname, born, gender
--     FROM imdb.people LIMIT 1000;

-- E9.3 write amplification: the staging table grows, and so does the WAL.  This is
--      the bridge from E9 to E10.
--   SELECT pg_relation_size('bench.batch_target') AS staging_bytes;
--   SELECT pg_current_xlog_location();       -- openGauss 5.0 name
--   -- SELECT pg_current_wal_lsn();          -- if your build renamed it

-- E9.4 durability knob worth reporting next to the TPS numbers.  With
--      synchronous_commit = off the COMMIT returns before the WAL is flushed:
--      much higher TPS, and a crash can lose the last transactions.  Run the sweep
--      twice, once with it on (the default, and what the headline table should
--      use) and once with it off, and label both.
--   SHOW synchronous_commit;
--   SET synchronous_commit = off;   -- only for the labelled second sweep
--   SET synchronous_commit = on;

-- -----------------------------------------------------------------------------
-- E10 反复整表更新之后的表膨胀 + 空间回收   MovieBench: bloat <轮数>
-- -----------------------------------------------------------------------------
-- The toggling statement below is length preserving the first time and
-- length-changing the next, so ten rounds beat the table up realistically while
-- staying repeatable.  Run it against imdb.people (heap) and imdb.people_ustore
-- so the two storage engines can be compared directly.
--
-- Take a snapshot BEFORE the loop:
SELECT 'people heap'  AS tbl, pg_relation_size('imdb.people')        AS bytes
UNION ALL SELECT 'people ustore', pg_relation_size('imdb.people_ustore')
UNION ALL SELECT 'taobao heap',  pg_relation_size('taobao.ad_clicks');
SELECT relname, n_live_tup, n_dead_tup, last_vacuum, last_autovacuum
  FROM pg_stat_user_tables
 WHERE relname IN ('people', 'people_ustore');

-- round k (k = 0..9) :  toggle every row whose peopleid ends in k
EXPLAIN (ANALYZE, BUFFERS)
UPDATE imdb.people
   SET surname = CASE WHEN substr(surname, length(surname), 1) = 'x'
                      THEN substr(surname, 1, length(surname) - 1)
                      ELSE surname || 'x' END
 WHERE peopleid % 10 = 0;      -- repeat with % 10 = 1 .. 9

-- the same rounds on the ustore table
EXPLAIN (ANALYZE, BUFFERS)
UPDATE imdb.people_ustore
   SET surname = CASE WHEN substr(surname, length(surname), 1) = 'x'
                      THEN substr(surname, 1, length(surname) - 1)
                      ELSE surname || 'x' END
 WHERE peopleid % 10 = 0;

-- snapshot AFTER the loop (compare with the numbers above)
SELECT 'people heap'  AS tbl, pg_relation_size('imdb.people')        AS bytes
UNION ALL SELECT 'people ustore', pg_relation_size('imdb.people_ustore');
SELECT relname, n_live_tup, n_dead_tup, last_vacuum, last_autovacuum
  FROM pg_stat_user_tables
 WHERE relname IN ('people', 'people_ustore');

-- space reclamation, and how long it takes (this is the second half of E10)
-- The keyword form VACUUM FULL ANALYZE works on every openGauss version; the
-- parenthesised form VACUUM (FULL, ANALYZE) also exists but is not universal.
\timing on
VACUUM FULL ANALYZE imdb.people;
VACUUM FULL ANALYZE imdb.people_ustore;
VACUUM FULL ANALYZE taobao.ad_clicks;

-- and confirm the table shrank back
SELECT 'people heap' AS tbl, pg_relation_size('imdb.people') AS bytes
UNION ALL SELECT 'people ustore', pg_relation_size('imdb.people_ustore');

-- -----------------------------------------------------------------------------
-- E11 中断写入：原子性与持久性   MovieBench: tx kill
-- -----------------------------------------------------------------------------
-- Protocol (details in OPERATIONS.md section G):
--   1. the Java program runs the block below and is KILLED before COMMIT;
--   2. reconnect and run E11.3 -- the answer must be 0;
--   3. the file-side program is killed in the middle of rewriting the CSV and
--      the CSV is left truncated.  That contrast is the deliverable.
--
-- E11.1 to be executed by the Java client, transaction left OPEN on purpose
BEGIN;
UPDATE imdb.people
   SET surname = replace(surname, 'T', 'Ttt')
 WHERE peopleid <= 100000;
-- ... and the process is killed here, before COMMIT ...

-- E11.2 the same, but committed -- the control case
BEGIN;
UPDATE imdb.people
   SET surname = replace(surname, 'T', 'Ttt')
 WHERE peopleid <= 100000;
COMMIT;

-- E11.3 verification after the kill: must report 0 changed rows
SELECT count(*) FROM imdb.people WHERE peopleid <= 100000 AND surname LIKE '%Ttt%';

-- E11.4 durability: how much write-ahead log did the committed version produce?
--       Run immediately after E11.2, then again after E11.1 + kill, and compare.
SELECT pg_current_xlog_location();
-- openGauss 5.0 keeps the old name; on a build that renamed it use
--      SELECT pg_current_wal_lsn();

-- -----------------------------------------------------------------------------
-- SECTION 7.6  the parallel run, for a second table in the report
-- -----------------------------------------------------------------------------
-- Everything above ran with query_dop = 1.  Repeat the E5 and E8 aggregations
-- with four-way parallel execution and report them as a separate row, never
-- mixed into the single-threaded numbers.
--     SET query_dop = 4;
--     EXPLAIN (ANALYZE, BUFFERS) SELECT airline, count(*) FROM flights.flights GROUP BY airline;
--     SET query_dop = 1;

-- =============================================================================
-- SECTION 8  housekeeping
-- =============================================================================
-- Restore a table to its imported state without re-importing everything:
--     CREATE TABLE IF NOT EXISTS imdb.people_backup AS SELECT * FROM imdb.people;  -- once, right after STEP 2
--     TRUNCATE imdb.people;
--     INSERT INTO imdb.people SELECT * FROM imdb.people_backup;
-- (Run this before every E2 / E10 / E11 round, otherwise the data drifts.  E9
--  needs no restore: it writes to its own staging table.)
--
-- Fresh statistics after any bulk change:
--     ANALYZE imdb.people; ANALYZE imdb.people_ustore;
--
-- Session quick check at the very end:
--     SELECT current_database(), version();
-- =============================================================================
