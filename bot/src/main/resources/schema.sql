-- race-radio, one database for every athlete.
--
-- Every row of an athlete's data starts with athlete_id, which leads its primary key and its indexes,
-- and references athlete(id) with ON DELETE CASCADE: removing an athlete is one DELETE on athlete,
-- and the database removes the rest. Foreign keys are switched on by the connection
-- (foreign_keys=true). Only three tables hold no athlete's data: athlete itself, the invitations and
-- check-ins of people not yet in, and the segment library, which is road, not anyone's data.
--
-- Applied by Database, never by Spring: it reads user_version first, so an older database is
-- refused with an explanation rather than half changed.

PRAGMA user_version = 1;

-- The athletes. The first one, the administrator, is created from the service's own configuration.
CREATE TABLE IF NOT EXISTS athlete (
    id                    INTEGER PRIMARY KEY,
    chat_id               INTEGER UNIQUE,
    name                  TEXT,
    -- admin manages invitations and the segment library; coach is reserved for a club mode.
    role                  TEXT    NOT NULL DEFAULT 'athlete' CHECK (role IN ('admin', 'athlete', 'coach')),
    intervals_athlete_id  TEXT,
    -- Encrypted with the service's master key. Null for the administrator, whose key is in the configuration.
    intervals_key         TEXT,
    -- subscription: the administrator's Claude subscription. free: a free model, checked by the program.
    model_route           TEXT    NOT NULL DEFAULT 'subscription' CHECK (model_route IN ('subscription', 'free')),
    -- 1 when a failure of the free model may fall back to the subscription.
    model_fallback        INTEGER NOT NULL DEFAULT 0,
    monthly_budget_usd    REAL,
    -- Kinds of writes sent to Intervals.icu, as RACE_RADIO_WRITES spells them.
    writes                TEXT    NOT NULL DEFAULT 'workouts,notes,comments',
    status                TEXT    NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused')),
    created_at            TEXT    NOT NULL
);

-- Invitations, single use. Only a hash of the code is kept.
CREATE TABLE IF NOT EXISTS invitation (
    code_hash    TEXT PRIMARY KEY,
    model_route  TEXT NOT NULL DEFAULT 'subscription' CHECK (model_route IN ('subscription', 'free')),
    created_at   TEXT NOT NULL,
    expires_at   TEXT NOT NULL,
    used_by      INTEGER REFERENCES athlete (id) ON DELETE SET NULL,
    used_at      TEXT
);

-- Where a newcomer's check-in stands, so a restart does not lose it. Keyed by chat: no athlete yet.
CREATE TABLE IF NOT EXISTS checkin (
    chat_id     INTEGER PRIMARY KEY,
    step        TEXT NOT NULL,
    data        TEXT NOT NULL DEFAULT '{}',
    updated_at  TEXT NOT NULL
);

-- The segment library, shared: Strava segments (positive ids) and segments drawn on an activity
-- (negative ids), with their GPS trace once fetched. Who crossed them, and how fast, is per athlete.
CREATE TABLE IF NOT EXISTS segment (
    id          INTEGER PRIMARY KEY,
    origin      TEXT    NOT NULL CHECK (origin IN ('strava', 'custom')),
    name        TEXT    NOT NULL,
    detail      TEXT,
    fetched_at  TEXT,
    added_by    INTEGER REFERENCES athlete (id) ON DELETE SET NULL
);

-- Every call to the language model: what it cost and why it was made.
CREATE TABLE IF NOT EXISTS llm_call (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    athlete_id          INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
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
CREATE INDEX IF NOT EXISTS llm_call_athlete_at ON llm_call (athlete_id, at);

-- Every decision the coach took, with the numbers and the rules behind it.
CREATE TABLE IF NOT EXISTS decision (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    athlete_id      INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    at              TEXT NOT NULL,
    kind            TEXT NOT NULL,
    day             TEXT NOT NULL,
    verdict         TEXT,
    model           TEXT NOT NULL,
    evidence_json   TEXT NOT NULL,
    message         TEXT,
    prompt_version  TEXT
);
CREATE INDEX IF NOT EXISTS decision_athlete_day ON decision (athlete_id, day);

-- What the athlete reported, five dimensions from 1 to 5.
CREATE TABLE IF NOT EXISTS feel (
    athlete_id  INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    day         TEXT    NOT NULL,
    sleep       INTEGER NOT NULL,
    energy      INTEGER NOT NULL,
    soreness    INTEGER NOT NULL,
    mood        INTEGER NOT NULL,
    appetite    INTEGER NOT NULL,
    at          TEXT    NOT NULL,
    PRIMARY KEY (athlete_id, day)
);

-- Activities already reviewed, so a restart never reviews one twice.
CREATE TABLE IF NOT EXISTS reviewed_activity (
    athlete_id   INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    activity_id  TEXT    NOT NULL,
    at           TEXT    NOT NULL,
    PRIMARY KEY (athlete_id, activity_id)
);

-- The conversation, turn by turn: the dated journal of what was said.
CREATE TABLE IF NOT EXISTS conversation (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    athlete_id  INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    at          TEXT NOT NULL,
    role        TEXT NOT NULL,
    text        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS conversation_athlete ON conversation (athlete_id, id);

-- Notes the athlete asked the coach to keep: current facts such as an injury or a constraint.
CREATE TABLE IF NOT EXISTS note (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    athlete_id  INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    at          TEXT    NOT NULL,
    text        TEXT    NOT NULL,
    active      INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS note_athlete ON note (athlete_id, active);

-- What the training is for.
CREATE TABLE IF NOT EXISTS objective (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    athlete_id  INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    kind        TEXT    NOT NULL,
    sport       TEXT    NOT NULL,
    day         TEXT,
    priority    TEXT    NOT NULL,
    target      TEXT    NOT NULL,
    active      INTEGER NOT NULL DEFAULT 1,
    at          TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS objective_athlete ON objective (athlete_id, active);

-- Small settings the athlete changes from Telegram: planning mode, sports in season, availability.
CREATE TABLE IF NOT EXISTS setting (
    athlete_id  INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    key         TEXT    NOT NULL,
    value       TEXT    NOT NULL,
    PRIMARY KEY (athlete_id, key)
);

-- Workouts the coach lightened on a yellow or red morning, kept so the change can be undone.
CREATE TABLE IF NOT EXISTS adaptation (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    athlete_id       INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    at               TEXT    NOT NULL,
    day              TEXT    NOT NULL,
    original_json    TEXT    NOT NULL,
    replacement_id   INTEGER,
    undone           INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS adaptation_athlete ON adaptation (athlete_id, at);

-- The local archive. Activities and wellness days are kept exactly as Intervals.icu sends them,
-- every field, so any figure over any period is a local query and never a new call.
CREATE TABLE IF NOT EXISTS archive_activity (
    athlete_id        INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    id                TEXT    NOT NULL,
    day               TEXT    NOT NULL,
    type              TEXT    NOT NULL,
    json              TEXT    NOT NULL,
    fetched_at        TEXT    NOT NULL,
    -- StreamMetrics.VERSION the computed figures were made with, 0 until computed.
    computed_version  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (athlete_id, id)
);
CREATE INDEX IF NOT EXISTS archive_activity_athlete_day ON archive_activity (athlete_id, day);

CREATE TABLE IF NOT EXISTS archive_wellness (
    athlete_id  INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    day         TEXT    NOT NULL,
    json        TEXT    NOT NULL,
    fetched_at  TEXT    NOT NULL,
    PRIMARY KEY (athlete_id, day)
);

-- Only the figures Intervals.icu does not provide, computed once from the archived streams.
CREATE TABLE IF NOT EXISTS computed_metric (
    athlete_id   INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    activity_id  TEXT    NOT NULL,
    metric       TEXT    NOT NULL,
    value        REAL    NOT NULL,
    PRIMARY KEY (athlete_id, activity_id, metric)
);

-- What is planned on the calendar, cached at each archive refresh and after each publication,
-- so a prescription knows what is already there without asking Intervals.icu again.
CREATE TABLE IF NOT EXISTS archive_event (
    athlete_id  INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    id          INTEGER NOT NULL,
    day         TEXT    NOT NULL,
    json        TEXT    NOT NULL,
    fetched_at  TEXT    NOT NULL,
    PRIMARY KEY (athlete_id, id)
);
CREATE INDEX IF NOT EXISTS archive_event_athlete_day ON archive_event (athlete_id, day);

-- Strava, harvested from the administrator's own Strava application before the subscription ends:
-- activities with every segment crossed, and the efforts on them. The segments themselves go to the
-- shared library.
CREATE TABLE IF NOT EXISTS strava_activity (
    athlete_id  INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    id          INTEGER NOT NULL,
    day         TEXT    NOT NULL,
    sport       TEXT    NOT NULL,
    summary     TEXT    NOT NULL,
    detail      TEXT,
    fetched_at  TEXT    NOT NULL,
    PRIMARY KEY (athlete_id, id)
);
CREATE INDEX IF NOT EXISTS strava_activity_athlete_day ON strava_activity (athlete_id, day);

CREATE TABLE IF NOT EXISTS strava_effort (
    athlete_id       INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    id               INTEGER NOT NULL,
    segment_id       INTEGER NOT NULL,
    activity_id      INTEGER NOT NULL,
    day              TEXT    NOT NULL,
    elapsed_seconds  INTEGER NOT NULL,
    moving_seconds   INTEGER,
    average_watts    REAL,
    average_hr       REAL,
    pr_rank          INTEGER,
    json             TEXT    NOT NULL,
    PRIMARY KEY (athlete_id, id)
);
CREATE INDEX IF NOT EXISTS strava_effort_athlete_segment ON strava_effort (athlete_id, segment_id, day);

-- Passages over segments timed locally from the GPS track, without Strava.
CREATE TABLE IF NOT EXISTS local_effort (
    athlete_id   INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    activity_id  TEXT    NOT NULL,
    segment_id   INTEGER NOT NULL,
    start_index  INTEGER NOT NULL,
    day          TEXT    NOT NULL,
    seconds      REAL    NOT NULL,
    watts        REAL,
    heart_rate   REAL,
    PRIMARY KEY (athlete_id, activity_id, segment_id, start_index)
);
CREATE INDEX IF NOT EXISTS local_effort_athlete_segment ON local_effort (athlete_id, segment_id, day);

-- Which activities were scanned for segments, and against how many segments.
CREATE TABLE IF NOT EXISTS segment_scan (
    athlete_id   INTEGER NOT NULL REFERENCES athlete (id) ON DELETE CASCADE,
    activity_id  TEXT    NOT NULL,
    segments     INTEGER NOT NULL,
    scanned_at   TEXT    NOT NULL,
    PRIMARY KEY (athlete_id, activity_id)
);
