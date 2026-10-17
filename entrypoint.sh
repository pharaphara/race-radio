#!/bin/sh
# Says what is configured and what only a human can do, then starts the service.
#
# Docker prints nothing from a container started detached, so this lands in the logs:
# "docker compose logs race-radio".
set -e

# Claude Code expects its first-run onboarding to have happened. In a container there is nobody to
# click through it, and a subscription token from "claude setup-token" is enough to authenticate.
if [ ! -f "$HOME/.claude.json" ]; then
    printf '{"hasCompletedOnboarding": true}\n' > "$HOME/.claude.json"
fi

ok() { if [ -n "$1" ]; then echo "ok"; else echo "MISSING"; fi; }

TRANSPORT="${RACE_RADIO_MODEL_TRANSPORT:-subscription}"
if [ "$TRANSPORT" = "subscription" ]; then
    MODEL_AUTH="CLAUDE_CODE_OAUTH_TOKEN $(ok "$CLAUDE_CODE_OAUTH_TOKEN")"
    if [ -n "$ANTHROPIC_API_KEY" ]; then
        MODEL_AUTH="$MODEL_AUTH, and ANTHROPIC_API_KEY is also set: unset it, the two conflict"
    fi
else
    MODEL_AUTH="ANTHROPIC_API_KEY $(ok "$ANTHROPIC_API_KEY")"
fi
if [ "${RACE_RADIO_DRY_RUN:-true}" = "false" ]; then
    WRITES="live: workouts, notes and reviews are written to Intervals.icu"
else
    WRITES="dry run: nothing is written to Intervals.icu (RACE_RADIO_DRY_RUN=false to go live)"
fi

cat <<BANNER

  race-radio
  A triathlon coach that talks to you on Telegram and shows its work.

  Telegram     TELEGRAM_TOKEN $(ok "$TELEGRAM_TOKEN"), TELEGRAM_ALLOWED_CHAT_ID $(ok "$TELEGRAM_ALLOWED_CHAT_ID")
  Intervals    INTERVALS_ATHLETE_ID $(ok "$INTERVALS_ATHLETE_ID"), INTERVALS_API_KEY $(ok "$INTERVALS_API_KEY")
  Model        $TRANSPORT, $MODEL_AUTH
  Writes       $WRITES
  Data         ${RACE_RADIO_DATA_DIR:-/data}

  Three things no software can do for you:
  - create the bot with @BotFather, and send it one message so it knows your chat;
  - run "claude setup-token" once on a machine with a browser, for a subscription token;
  - in Intervals.icu, enable sending planned workouts to Garmin, or they never reach the watch.

  Check without Telegram: docker compose run --rm race-radio --race-radio.once=morning

BANNER

exec java $JAVA_OPTS -jar /app/race-radio.jar "$@"
