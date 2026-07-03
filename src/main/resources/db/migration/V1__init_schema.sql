-- Contextual News Retrieval System — initial schema (design §3)

CREATE EXTENSION IF NOT EXISTS postgis;

-- ---------------------------------------------------------------------------
-- articles : source of truth (relational + geo + full-text)
-- ---------------------------------------------------------------------------
CREATE TABLE articles (
    id                UUID PRIMARY KEY,
    title             TEXT NOT NULL,
    description       TEXT NOT NULL,
    url               TEXT,
    publication_date  TIMESTAMP,
    source_name       TEXT,
    categories        TEXT[],                  -- original labels (returned in API)
    categories_norm   TEXT[],                  -- lowercased copy (used for filtering)
    relevance_score   DOUBLE PRECISION,
    latitude          DOUBLE PRECISION,
    longitude         DOUBLE PRECISION,
    geog              GEOGRAPHY(Point, 4326),  -- null when lat/lon null or (0,0)
    llm_summary       TEXT,                    -- lazily persisted
    search_tsv        TSVECTOR GENERATED ALWAYS AS
                        (to_tsvector('english',
                           coalesce(title,'') || ' ' || coalesce(description,''))) STORED
);

CREATE INDEX idx_articles_geog        ON articles USING GIST (geog);
CREATE INDEX idx_articles_tsv         ON articles USING GIN (search_tsv);
CREATE INDEX idx_articles_categories  ON articles USING GIN (categories_norm);
CREATE INDEX idx_articles_source      ON articles (lower(source_name));
-- composite btree indexes serve keyset pagination (rank_key, id)
CREATE INDEX idx_articles_score       ON articles (relevance_score DESC, id DESC);
CREATE INDEX idx_articles_pubdate     ON articles (publication_date DESC, id DESC);

-- ---------------------------------------------------------------------------
-- user_events : simulated event stream feeding the trending feed (design §3.2)
-- ---------------------------------------------------------------------------
CREATE TABLE user_events (
    event_id    UUID PRIMARY KEY,
    user_id     UUID,
    article_id  UUID REFERENCES articles(id),
    event_type  TEXT,                 -- view | click | share | dwell
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    geohash     TEXT,                 -- precision-7, prefix-queried
    created_at  TIMESTAMP
);

CREATE INDEX idx_events_geohash ON user_events (geohash text_pattern_ops);
CREATE INDEX idx_events_article ON user_events (article_id);
CREATE INDEX idx_events_created ON user_events (created_at DESC);
