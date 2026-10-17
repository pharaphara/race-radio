# race-radio

A self-hosted triathlon coach that talks to you on Telegram.

Every morning it decides whether today is a day to train or to recover, and says why. After every
session it detects the intervals you actually rode, ran or swam, compares them with what was
planned, and tells you one thing that went well, one thing to fix, and what it means for the next
session. It reads everything from [Intervals.icu](https://intervals.icu) and writes its decisions
back there, so nothing it decides is invisible.

> **Status: early.** The calculation core and a read-only Intervals.icu client exist and are
> tested. The Telegram bot, the planner and the charts do not exist yet. See *Roadmap*.

## Why another AI coach

Several open-source coaches already chat about training. This one is built around three
commitments the others do not make together:

- **The model never does arithmetic.** Every number is computed in plain Java, in a module that
  cannot reach the network or the language model. The model only interprets numbers it is handed,
  and must name the signal behind each recommendation.
- **Decisions leave a trace.** The morning verdict is written to your Intervals.icu calendar as a
  note, each session review is posted on the activity, and every call to the model is logged so it
  can be replayed.
- **Swim, bike and run are each treated as their own sport**, with their own thresholds (critical
  swim speed, threshold power, threshold pace) and their own technique indicators.

## Modules

| Module | Job |
|---|---|
| `core` | Zones, load, fitness and fatigue, W′ balance, aerobic decoupling, critical swim speed, distance per stroke. No dependencies. |
| `icu`  | Intervals.icu client on the JDK's HTTP client. Reads profile, wellness, activities, calendar, intervals and streams. |

## Build

Java 21 and Maven.

```bash
mvn test                      # offline tests
mvn test -Dlive=true          # also read-only checks against your own account (needs .env)
```

Copy `.env.example` to `.env` and fill it in. `.env` is ignored by git, and a test fails the build
if any of its values appears in a file git would publish.

## Vocabulary

The usual abbreviations for these metrics are trademarks. The code and the messages say
*weighted average power*, *intensity*, *load*, *fitness*, *fatigue* and *form*. A test enforces it.

## Roadmap

1. Morning readiness verdict, published as a calendar note.
2. Interval detection that describes what you did, informed by the planned workout.
3. Session reviews: did it go well, one thing to fix, what it means next.
4. Charts, rendered once as SVG and served both to Telegram and to a web page.
5. Goals, sports switched on and off by season, and a planner with a feasibility check.
6. Recurring segments, harvested from Strava while access lasts, then matched locally by GPS.

## Credits

See [NOTICE](NOTICE). MIT licensed.
