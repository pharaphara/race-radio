-- Every call to the language model: what it cost and why it was made.
CREATE TABLE IF NOT EXISTS llm_call (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    at                  TEXT    NOT NULL,
    purpose             TEXT    NOT NULL,
    requested_model     TEXT    NOT NULL,
    answering_model     TEXT,
    input_tokens        INTEGER NOT NULL DEFAULT 0,
    output_tokens       INTEGER NOT NULL DEFAULT 0,
    cache_write_tokens  INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens   INTEGER NOT NULL DEFAULT 0,
    cost_usd            REAL    NOT NULL DEFAULT 0,
    latency_ms          INTEGER NOT NULL DEFAULT 0,
    stop_reason         TEXT,
    prompt_version      TEXT    NOT NULL,
    journal_ref         TEXT,
    -- api: billed per token. subscription: drawn from a Claude subscription's limits, cost shown at list price.
    billing             TEXT    NOT NULL DEFAULT 'api'
);
CREATE INDEX IF NOT EXISTS llm_call_at ON llm_call (at);

-- Every decision the coach took, with the numbers and the rules behind it.
CREATE TABLE IF NOT EXISTS decision (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    at              TEXT NOT NULL,
    kind            TEXT NOT NULL,
    day             TEXT NOT NULL,
    verdict         TEXT,
    model           TEXT NOT NULL,
    evidence_json   TEXT NOT NULL,
    message         TEXT,
    prompt_version  TEXT
);
CREATE INDEX IF NOT EXISTS decision_day ON decision (day);

-- What the athlete reported, five dimensions from 1 to 5.
CREATE TABLE IF NOT EXISTS feel (
    day       TEXT PRIMARY KEY,
    sleep     INTEGER NOT NULL,
    energy    INTEGER NOT NULL,
    soreness  INTEGER NOT NULL,
    mood      INTEGER NOT NULL,
    appetite  INTEGER NOT NULL,
    at        TEXT    NOT NULL
);

-- Activities already reviewed, so a restart never reviews one twice.
CREATE TABLE IF NOT EXISTS reviewed_activity (
    activity_id  TEXT PRIMARY KEY,
    at           TEXT NOT NULL
);

-- The conversation, turn by turn: the dated journal of what was said.
CREATE TABLE IF NOT EXISTS conversation (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    at    TEXT NOT NULL,
    role  TEXT NOT NULL,
    text  TEXT NOT NULL
);

-- Notes the athlete asked the coach to keep: current facts such as an injury or a constraint.
CREATE TABLE IF NOT EXISTS note (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    at      TEXT NOT NULL,
    text    TEXT NOT NULL,
    active  INTEGER NOT NULL DEFAULT 1
);

-- What the training is for.
CREATE TABLE IF NOT EXISTS objective (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    kind      TEXT NOT NULL,
    sport     TEXT NOT NULL,
    day       TEXT,
    priority  TEXT NOT NULL,
    target    TEXT NOT NULL,
    active    INTEGER NOT NULL DEFAULT 1,
    at        TEXT NOT NULL
);

-- Small settings the athlete changes from Telegram: planning mode, sports in season, availability.
CREATE TABLE IF NOT EXISTS setting (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);

-- Workouts the coach lightened on a yellow or red morning, kept so the change can be undone.
CREATE TABLE IF NOT EXISTS adaptation (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    at               TEXT NOT NULL,
    day              TEXT NOT NULL,
    original_json    TEXT NOT NULL,
    replacement_id   INTEGER,
    undone           INTEGER NOT NULL DEFAULT 0
);

-- The local archive. Activities and wellness days are kept exactly as Intervals.icu sends them,
-- every field, so any figure over any period is a local query and never a new call.
CREATE TABLE IF NOT EXISTS archive_activity (
    id                TEXT PRIMARY KEY,
    day               TEXT NOT NULL,
    type              TEXT NOT NULL,
    json              TEXT NOT NULL,
    fetched_at        TEXT NOT NULL,
    -- StreamMetrics.VERSION the computed figures were made with, 0 until computed.
    computed_version  INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS archive_activity_day ON archive_activity (day);

CREATE TABLE IF NOT EXISTS archive_wellness (
    day         TEXT PRIMARY KEY,
    json        TEXT NOT NULL,
    fetched_at  TEXT NOT NULL
);

-- Only the figures Intervals.icu does not provide, computed once from the archived streams.
CREATE TABLE IF NOT EXISTS computed_metric (
    activity_id  TEXT NOT NULL,
    metric       TEXT NOT NULL,
    value        REAL NOT NULL,
    PRIMARY KEY (activity_id, metric)
);

-- Strava, harvested before the subscription ends, kept whole: activities with every segment
-- crossed, segments with their GPS trace. Nothing here needs Strava once harvested.
CREATE TABLE IF NOT EXISTS strava_activity (
    id          INTEGER PRIMARY KEY,
    day         TEXT NOT NULL,
    sport       TEXT NOT NULL,
    summary     TEXT NOT NULL,
    detail      TEXT,
    fetched_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS strava_activity_day ON strava_activity (day);

CREATE TABLE IF NOT EXISTS strava_segment (
    id          INTEGER PRIMARY KEY,
    name        TEXT NOT NULL,
    detail      TEXT,
    fetched_at  TEXT
);

CREATE TABLE IF NOT EXISTS strava_effort (
    id               INTEGER PRIMARY KEY,
    segment_id       INTEGER NOT NULL,
    activity_id      INTEGER NOT NULL,
    day              TEXT NOT NULL,
    elapsed_seconds  INTEGER NOT NULL,
    moving_seconds   INTEGER,
    average_watts    REAL,
    average_hr       REAL,
    pr_rank          INTEGER,
    json             TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS strava_effort_segment ON strava_effort (segment_id, day);
