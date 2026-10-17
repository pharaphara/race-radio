# race-radio: working notes

A self-hosted triathlon coach on Telegram, backed by Intervals.icu. Public repository, MIT.

## Rules that break the project if ignored

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

## Facts established against the real API

- Fitness and fatigue use exponential decay `e^(-1/n)` with n = 42 and 7. Replaying real loads, this
  reproduces Intervals.icu's fatigue exactly and its fitness within 0.1; the linear `1/n` form
  drifts by close to 3. Ramp is fitness today minus fitness 7 days earlier.
- Streams come from `streams.csv`: it starts with a UTF-8 BOM, and it carries `lat` and `lng` as two
  columns, whereas the filtered JSON `latlng` stream was observed to return latitudes only.
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
