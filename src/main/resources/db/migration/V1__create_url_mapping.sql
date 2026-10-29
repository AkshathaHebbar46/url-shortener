-- MILESTONE 2 — first Flyway migration.
--
-- Flyway runs this once and records a checksum. After it has run anywhere,
-- NEVER edit this file; add V2__... instead. Editing an applied migration is
-- the single most common way teams break their deploy.

CREATE TABLE url_mapping (
    id          BIGINT        PRIMARY KEY,          -- assigned by the app, not the DB
    short_code  VARCHAR(10)   NOT NULL UNIQUE,      -- the UNIQUE is load-bearing
    long_url    VARCHAR(2048) NOT NULL,             -- matches @Size(max = 2048) on the DTO
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,                        -- nullable = never expires
    click_count BIGINT        NOT NULL DEFAULT 0
);

-- The id sequence read by SequenceIdAllocator (M2). RedisBlockIdAllocator (M5)
-- replaces it but does not drop it: it stays as the fallback when Redis is
-- unreachable, and as the control group for benchmarks.
--
-- Seeded at 62^6 = 56800235584 so the very first code is already 7 characters.
-- Starting at 1 would hand out codes like "1" and "a", which look broken and
-- would collide with any reserved path added later.
--
-- CACHE 50 lets Postgres hand out blocks of 50 from memory instead of touching
-- the catalog on every nextval — the same trick M5 does one layer up, which is
-- worth noticing: the database already solved this problem.
CREATE SEQUENCE url_mapping_id_seq
    START WITH 56800235584
    INCREMENT BY 1
    CACHE 50;

-- No index on short_code here, deliberately. In Postgres a UNIQUE constraint
-- is implemented BY a unique btree index, so the lookup in findByShortCode is
-- already index-backed. A second index would double the write cost and the
-- planner would never use it. See docs/decisions.md.
