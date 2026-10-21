# race-radio: working notes

A self-hosted triathlon coach on Telegram, backed by Intervals.icu. Public repository, MIT.

## Rules that break the project if ignored

0. **Never ignore a source folder.** Ignore patterns are anchored to the root; an unanchored `llm/`
   once hid a whole source package. A test fails if git ignores anything under `src/` or `knowledge/`.
1. **`core` computes, and only computes.** No Spring, no HTTP, no LLM, no runtime dependency.
   Every number the coach states comes from here. The model interprets; it never calculates.
2. **Public repository.** No athlete id, token, key, GPS trace or real threshold enters git.
   Test data is a fictional athlete (threshold 250 W, LTHR 170, W′ 18 kJ). `RepositoryHygieneTest`
   scans exactly what git would publish and fails on ids, tokens and any `.env` value.
3. **Vocabulary.** Weighted average power, intensity, load, fitness, fatigue, form. The trademarked
   abbreviations are allowed only under `knowledge/`. Enforced by the same test.
4. **Only `IntervalsWriter` writes to Intervals.icu**, honours `--dry-run`, and only modifies
   calendar entries whose `external_id` starts with `race-radio-`.
5. **Name the model.** Any figure that depends on a modelling choice (W′ balance, zones) carries the
   model's name, and figures from different models are never compared.
6. **No athlete's data without its athlete.** Every table of an athlete's data starts with
   `athlete_id` (cascading from `athlete`), every class that keeps such data is built with an
   `AthleteDb`, and every query names `athlete_id`. `AthleteScopeTest` checks the schema, the tables a
   reset copies, and every SQL statement in the code. Only `athlete`, `invitation`, `checkin` and the
   `segment` library are shared. The schema is applied by `Database`, never by Spring, and a schema
   change bumps `Database.VERSION` with an explicit move, never an `ALTER` in place.
7. **Logs name athletes by number only.** Work for an athlete runs inside `AthleteLog.run`, which puts
   `#<id>` in the logging context and the pattern prints it on every line; messages never carry a
   name, a key, a token or what an athlete wrote. Prompts and answers belong in the journal, not the
   logs. `LogTest` checks a whole check-in for a key or a name.

## Facts established against the real API

- Fitness and fatigue use exponential decay `e^(-1/n)` with n = 42 and 7. Replaying real loads, this
  reproduces Intervals.icu's fatigue exactly and its fitness within 0.1; the linear `1/n` form
  drifts by close to 3. Ramp is fitness today minus fitness 7 days earlier.
- Streams come from `streams.csv`: it starts with a UTF-8 BOM, and it carries `lat` and `lng` as two
  columns. The JSON `latlng` stream holds latitudes in `data` and longitudes in `data2`.
- Planned workouts reach the watch only if the athlete's `icu_garmin_upload_workouts` is true, and
  only if Intervals.icu filled `workout_doc`, which means it understood the description.
- There is no webhook in the API: new activities are found by polling.
- A workout is understood only if `workout_doc` exists **and** holds steps. An empty description
  yields a document that is present but empty, which reaches the watch as an empty workout.
- `DELETE events/{id}` takes `others`, which also deletes entries created at the same time.
  `IntervalsWriter` always sends `others=false`.
- `POST download-workout{ext}` (stateless conversion to .zwo) answers 500 with an API key: it needs
  an OAuth session. Validation therefore happens after publication, by re-reading `workout_doc`;
  a created event can be downloaded as .zwo through `events/{id}/download.zwo`.
- Distances are written `400mtr` in workout text, because `m` means minutes.
- Claude Code through a subscription: never `--bare` (API key only). Without `--disable-slash-commands`,
  `--setting-sources ""` and `--strict-mcp-config`, a one-word call wrote 30,711 tokens to cache;
  with them, 573. The model may read anything in its working directory and is refused elsewhere, so
  a photo is read from a directory holding only that photo.
- Readiness was replayed on a year of real wellness: about 80% green, under 5% red, both recorded
  illnesses red on their first morning. Change a threshold only after replaying it.
- The interval detector was judged on seven real sessions. Laps cut by a structured workout are the
  truth when half the planned steps match exactly; detection is for sessions without them.
- Docker: built and published by CI to ghcr.io/pharaphara/race-radio. There is no Docker on the
  development PC; the CI build is the check that the image builds.
