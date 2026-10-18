package io.github.pharaphara.raceradio.icu;

import io.github.pharaphara.raceradio.core.intervals.Block;
import io.github.pharaphara.raceradio.core.workout.Workout;
import io.github.pharaphara.raceradio.icu.model.CalendarEvent;
import io.github.pharaphara.raceradio.icu.workout.WorkoutDoc;
import io.github.pharaphara.raceradio.icu.workout.WorkoutText;
import tools.jackson.core.type.TypeReference;

import java.net.http.HttpRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only class allowed to change anything on Intervals.icu.
 *
 * <p>Three guarantees live here, so that no other code has to remember them:
 * <ul>
 *   <li><b>Dry run.</b> A write of a kind not allowed ({@link Write}) is described to the journal
 *       and not sent; {@link Mode#DRY_RUN} allows none.</li>
 *   <li><b>Ownership.</b> Every calendar entry created carries {@code external_id} starting with
 *       {@value CalendarEvent#OWN_PREFIX}, and an entry without that prefix is never modified or
 *       deleted: workouts from other tools and entries made by hand are out of reach.</li>
 *   <li><b>A workout is published only if the watch will get what was meant.</b> Publishing is
 *       refused while the athlete's profile does not push workouts to Garmin; and once created,
 *       the calendar's own reading of the workout is compared with the intent, step by step. On
 *       any disagreement the entry is deleted and an exception says why, rather than leaving a
 *       workout that would arrive mangled, or not at all.</li>
 * </ul>
 */
public final class IntervalsWriter {

    public enum Mode {
        LIVE,
        DRY_RUN
    }

    /**
     * What may be written, so writes can be opened one kind at a time: workouts can reach the
     * watch while rewriting an activity's intervals stays a dry run until it has been checked.
     */
    public enum Write {
        /** Workouts on the calendar, and lightening or removing the coach's own. */
        WORKOUTS,
        /** The morning verdict as a calendar note. */
        NOTES,
        /** Reviews posted under activities, and activity descriptions. */
        COMMENTS,
        /** An activity's intervals replaced by the detected blocks. */
        INTERVALS,
        /** Two activities merged into one. */
        MERGE;

        /** Parses "workouts,notes" (any case, any spacing); "all" means every kind, blank none. */
        public static java.util.Set<Write> parse(String list) {
            java.util.Set<Write> out = java.util.EnumSet.noneOf(Write.class);
            if (list == null) {
                return out;
            }
            for (String s : list.split("[,;\\s]+")) {
                if (s.isBlank()) {
                    continue;
                }
                if (s.equalsIgnoreCase("all")) {
                    return java.util.EnumSet.allOf(Write.class);
                }
                out.add(Write.valueOf(s.strip().toUpperCase(java.util.Locale.ROOT)));
            }
            return out;
        }
    }

    /** The calendar misread a workout; the entry was removed. */
    public static final class WorkoutNotUnderstood extends RuntimeException {
        private final List<String> disagreements;

        WorkoutNotUnderstood(List<String> disagreements) {
            super("the calendar misread the workout and it was removed: " + String.join("; ", disagreements));
            this.disagreements = List.copyOf(disagreements);
        }

        public List<String> disagreements() {
            return disagreements;
        }
    }

    private final IntervalsClient client;
    private final java.util.Set<Write> allowed;
    private final Consumer<String> journal;

    public IntervalsWriter(IntervalsClient client, Mode mode, Consumer<String> journal) {
        this(client, mode == Mode.LIVE ? java.util.EnumSet.allOf(Write.class) : java.util.EnumSet.noneOf(Write.class), journal);
    }

    /** @param allowed  the kinds actually sent; every other write is only described, as in dry run */
    public IntervalsWriter(IntervalsClient client, java.util.Set<Write> allowed, Consumer<String> journal) {
        this.client = client;
        this.allowed = java.util.Set.copyOf(allowed);
        this.journal = journal;
    }

    /** LIVE only when every kind of write is sent. */
    public Mode mode() {
        return allowed.size() == Write.values().length ? Mode.LIVE : Mode.DRY_RUN;
    }

    /** Whether this kind of write is actually sent. */
    public boolean allows(Write kind) {
        return allowed.contains(kind);
    }

    public java.util.Set<Write> allowed() {
        return allowed;
    }

    /**
     * Publishes a planned workout on {@code day}. Returns the created entry, or empty in dry run.
     *
     * @throws IllegalStateException  when the profile does not push workouts to the watch
     * @throws WorkoutNotUnderstood   when the calendar's reading differs from the intent
     */
    public Optional<CalendarEvent> publishWorkout(LocalDate day, Workout workout) {
        String text = WorkoutText.render(workout);
        if (!client.athlete().pushesWorkoutsToGarmin()) {
            throw new IllegalStateException("Intervals.icu does not push workouts to Garmin for this athlete "
                    + "(icu_garmin_upload_workouts is off): a published workout would never reach the watch");
        }
        Map<String, Object> body = event(day, "WORKOUT", workout.sport(), workout.name(), text);
        Optional<CalendarEvent> created = postEvent(Write.WORKOUTS, body);
        if (created.isEmpty()) {
            return created;
        }
        CalendarEvent reread = client.get(client.athletePath("events/" + created.get().id()), new TypeReference<CalendarEvent>() { });
        List<String> disagreements = WorkoutDoc.disagreements(workout, reread.workoutDoc());
        if (!disagreements.isEmpty()) {
            deleteOwnEvent(reread.id());
            throw new WorkoutNotUnderstood(disagreements);
        }
        return Optional.of(reread);
    }

    /**
     * Publishes a workout from text that the calendar already understood once, such as an original
     * restored after an adjustment is undone. Kept only if the calendar understands it again.
     */
    public Optional<CalendarEvent> publishText(LocalDate day, String sport, String name, String description) {
        Optional<CalendarEvent> created = postEvent(Write.WORKOUTS, event(day, "WORKOUT", sport, name, description));
        if (created.isEmpty()) {
            return created;
        }
        CalendarEvent reread = client.get(client.athletePath("events/" + created.get().id()), new TypeReference<CalendarEvent>() { });
        if (!reread.wasUnderstoodAsAWorkout()) {
            deleteOwnEvent(reread.id());
            throw new WorkoutNotUnderstood(List.of("the calendar did not understand the restored workout"));
        }
        return Optional.of(reread);
    }

    /** A note on the calendar, such as the morning verdict and its reasons. */
    public Optional<CalendarEvent> publishNote(LocalDate day, String title, String text) {
        return postEvent(Write.NOTES, event(day, "NOTE", null, title, text));
    }

    /**
     * Deletes a calendar entry the coach created. Anything else is refused, and only that entry is
     * deleted: {@code others=false} is sent explicitly, because the API would otherwise also delete
     * entries created together with it.
     */
    public void deleteOwnEvent(long eventId) {
        CalendarEvent e = client.get(client.athletePath("events/" + eventId), new TypeReference<CalendarEvent>() { });
        if (!e.ownedByRaceRadio()) {
            throw new IllegalStateException("refusing to delete calendar entry " + eventId + ": not created by race-radio");
        }
        write(e.category() != null && e.category().equals("NOTE") ? Write.NOTES : Write.WORKOUTS,
                "DELETE", client.athletePath("events/" + eventId + "?others=false"), null);
    }

    /** Replaces an activity's intervals with the blocks found, so the calendar shows them too. */
    public void replaceIntervals(String activityId, List<Block> blocks, List<String> labels) {
        if (labels.size() != blocks.size()) {
            throw new IllegalArgumentException("one label per block");
        }
        List<Map<String, Object>> body = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Map<String, Object> iv = new LinkedHashMap<>();
            iv.put("start_index", blocks.get(i).start());
            iv.put("end_index", blocks.get(i).end());
            iv.put("type", "WORK");
            iv.put("label", labels.get(i));
            body.add(iv);
        }
        write(Write.INTERVALS, "PUT", "activity/" + activityId + "/intervals", body);
    }

    /**
     * Replaces an activity's streams, which makes Intervals.icu recompute everything derived from
     * them. Latitude and longitude travel together as one {@code latlng} stream, latitudes in
     * {@code data} and longitudes in {@code data2}. A missing reading is sent as null: JSON has no NaN.
     */
    public void replaceStreams(String activityId, Map<String, double[]> columns) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (Map.Entry<String, double[]> e : columns.entrySet()) {
            if (e.getKey().equals("lat") || e.getKey().equals("lng")) {
                continue;
            }
            Map<String, Object> stream = new LinkedHashMap<>();
            stream.put("type", e.getKey());
            stream.put("data", nullable(e.getValue()));
            body.add(stream);
        }
        if (columns.containsKey("lat") && columns.containsKey("lng")) {
            Map<String, Object> latlng = new LinkedHashMap<>();
            latlng.put("type", "latlng");
            latlng.put("data", nullable(columns.get("lat")));
            latlng.put("data2", nullable(columns.get("lng")));
            body.add(latlng);
        }
        write(Write.MERGE, "PUT", "activity/" + activityId + "/streams", body);
    }

    /**
     * Deletes an activity. Activities come from the watch and carry no ownership mark, so this is
     * offered only to the merge, after the athlete confirmed it on Telegram, and only once its
     * content lives on in the activity it was merged into.
     */
    public void deleteMergedActivity(String activityId, String mergedInto) {
        if (activityId.equals(mergedInto)) {
            throw new IllegalArgumentException("an activity cannot be merged into itself");
        }
        write(Write.MERGE, "DELETE", "activity/" + activityId, null);
    }

    private static List<Double> nullable(double[] v) {
        List<Double> out = new ArrayList<>(v.length);
        for (double x : v) {
            out.add(Double.isNaN(x) ? null : x);
        }
        return out;
    }

    /** Posts the session review under the activity, where it stays next to the data. */
    public void comment(String activityId, String text) {
        write(Write.COMMENTS, "POST", "activity/" + activityId + "/messages", Map.of("content", text));
    }

    /** Sets an activity's description, for instance the workout read from a photo of the board. */
    public void describe(String activityId, String description) {
        write(Write.COMMENTS, "PUT", "activity/" + activityId, Map.of("description", description));
    }

    private Map<String, Object> event(LocalDate day, String category, String type, String name, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", category);
        body.put("start_date_local", day + "T00:00:00");
        if (type != null) {
            body.put("type", type);
        }
        body.put("name", name);
        body.put("description", description);
        body.put("external_id", CalendarEvent.OWN_PREFIX + UUID.randomUUID());
        return body;
    }

    private Optional<CalendarEvent> postEvent(Write kind, Map<String, Object> body) {
        String response = write(kind, "POST", client.athletePath("events"), body);
        return response == null ? Optional.empty()
                : Optional.of(IntervalsClient.json().readValue(response, CalendarEvent.class));
    }

    /** Sends one write, or in dry run only describes it. Returns the response body, or null in dry run. */
    private String write(Write kind, String method, String path, Object body) {
        String json = body == null ? "" : IntervalsClient.json().writeValueAsString(body);
        if (!allowed.contains(kind)) {
            journal.accept("DRY RUN " + method + " " + path + (json.isEmpty() ? "" : " " + json));
            return null;
        }
        journal.accept(method + " " + path);
        HttpRequest.BodyPublisher publisher = json.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        return client.send(client.request(path).header("Content-Type", "application/json").method(method, publisher));
    }
}
