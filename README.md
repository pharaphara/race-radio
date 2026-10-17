# race-radio

A self-hosted triathlon coach that talks to you on Telegram and shows its work.

Every morning it decides whether today is a day to train, to ease off or to recover, and says
why. After every session it finds the intervals you actually did, compares them with the plan,
and tells you what went well, one thing to fix, and what it means next, with charts. It plans
your weeks towards a goal, lightens the day when your body says so, reads the photo of a gym board,
and answers questions. It reads everything from [Intervals.icu](https://intervals.icu) and writes
its decisions back there, so nothing it decides is invisible.

## What makes it different

- **The model never does arithmetic.** Every number is computed in plain Java, in a module that
  cannot reach the network or the language model. The model only words numbers it is handed, and
  must name the signal behind each recommendation.
- **The science is written down.** `knowledge/` holds the rules the coach follows, each labelled as
  a formula, a finding or a coaching convention, with sources checked at the source.
- **Decisions leave a trace.** The morning verdict is a note on your calendar, each review is posted
  on its activity, and every call to the model is recorded: tokens, cost, and exactly what was sent.
- **It measured before deciding.** Readiness thresholds were replayed on a year of real data, the
  interval detector on real sessions indoors and out, the model's cost on real calls.

## Features

| | |
|---|---|
| Morning call | Green, yellow or red from heart rate variability against your own baseline, resting heart rate, sleep, form, recent load, how you feel, and illness marked on your calendar. Never changes the day on a single reading. |
| Session review | Structure from your watch's laps when it ran the workout, detected from power or pace otherwise; comparison with the plan that locates a skipped rep; power, W′ balance, zones, decoupling, distance per stroke; two charts. |
| Planning | Goals (a dated race, hours a week, or fitness), your available days, sports in season. Base, specific work with bricks, a two-week taper, a recovery week every fourth. A feasibility check stated in what can be computed. |
| Adjusting | On yellow or red mornings, its own hard workouts become easier ones, with an undo button. Workouts from you or another tool are never touched. |
| Three modes | Plan and publish; only adjust; or analyse what another tool such as Garmin Coach planned. |
| Conversation | Free questions answered with today's data, notes it keeps until you withdraw them. |
| Board photos | A photo of a Crossfit board is transcribed into the day's session. |
| Accounting | `/cout` and `/dernierenvoi`: tokens by kind, cost at list price, a monthly cap, and what was sent. |

## Running it

You need: an Intervals.icu account synced with your watch, a Telegram account, and either a Claude
subscription or an Anthropic API key.

1. **Create the bot.** Talk to [@BotFather](https://t.me/BotFather), `/newbot`, keep the token.
   Send your bot one message, then open `https://api.telegram.org/bot<token>/getUpdates` and note
   `message.chat.id`: only that chat will ever be answered.
2. **Intervals.icu key.** Settings, Developer settings, API key. Your athlete id starts with `i`.
3. **Claude.** With a subscription, run `claude setup-token` once on a machine with a browser and
   keep the token. With the API, an `ANTHROPIC_API_KEY`. Set one, not both.
4. **Watch sync.** In Intervals.icu, enable uploading planned workouts to Garmin. Without it a
   published workout never reaches the watch, and race-radio refuses to publish.
5. **Configure and start.**

   ```bash
   curl -O https://raw.githubusercontent.com/pharaphara/race-radio/main/compose.yaml
   curl -o .env https://raw.githubusercontent.com/pharaphara/race-radio/main/.env.example
   # fill in .env
   docker compose up -d
   docker compose logs race-radio    # what is configured, what is missing
   ```

6. **Check before trusting it.** Nothing is written to Intervals.icu until `RACE_RADIO_DRY_RUN=false`.
   One-off jobs print their result and exit, without Telegram:

   ```bash
   docker compose run --rm race-radio --race-radio.once=morning
   docker compose run --rm race-radio --race-radio.once=review:<activity id>
   docker compose run --rm race-radio --race-radio.once=fitness
   ```

## On Telegram

| Command | |
|---|---|
| `/forme` | this morning's verdict, computed, with the fitness chart |
| `/aujourdhui` | the morning message, worded |
| `/pourquoi` | the evidence behind the last decision |
| `/ressenti 4 3 4 4 5` | sleep, energy, soreness, mood, appetite, 1 to 5 |
| `/objectif` | goals: `/objectif course 2027-05-23 A triathlon M`, `/objectif volume 6 h`, `/objectif forme` |
| `/dispo lun 1h, mer 45min, sam 2h` | available time |
| `/sports natation off` | sports in season |
| `/mode planifier` · `ajuster` · `analyser` | how much the coach does with the calendar |
| `/plan` | next week and what your hours can buy |
| `/note …` · `/notes` · `/oubli n` | facts the coach keeps in mind |
| `/cout` · `/dernierenvoi` | what the model cost, what was sent |
| a photo | a gym board, added to the day's session |
| anything else | a question, answered with today's data |

## Modules

| Module | Job |
|---|---|
| `core` | Readiness, zones, load, W′ balance, decoupling, interval detection and plan alignment, the planner. No dependencies. |
| `icu` | Intervals.icu client, and the single guarded writer: dry run, ownership, workouts checked after publication. |
| `charts` | Charts written as SVG, rasterised to PNG with Batik. |
| `bot` | The service: Telegram, schedules, the model, the ledger and the journal. |

## Building

Java 21 and Maven. `mvn verify` runs everything offline; `mvn test -Dlive=true` adds read-only
checks against your own account using a local `.env`.

## Vocabulary

The usual abbreviations for these metrics are trademarks. The code and the messages say *weighted
average power*, *intensity*, *load*, *fitness*, *fatigue* and *form*. A test enforces it.

## Credits

See [NOTICE](NOTICE). MIT licensed.
