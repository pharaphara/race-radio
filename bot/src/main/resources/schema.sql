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
