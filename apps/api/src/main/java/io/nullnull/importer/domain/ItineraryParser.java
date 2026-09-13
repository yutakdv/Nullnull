package io.nullnull.importer.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a pasted itinerary into lines the rest of the module can act on, and nothing else.
 *
 * <p><b>What this may hand out is the whole design.</b> A parsed line carries a line number, a kind,
 * and - only when a strict pattern matched - the fragment that matched it. It never carries the line.
 * The one place free text still exists is {@link ParsedLine#lookup()}, which is passed to the catalog
 * as a LIKE parameter and is not stored, logged or echoed; everything that reaches a draft or a
 * response is either a line number, a pattern-matched fragment, or a value read back from the catalog.
 *
 * <p>That is stricter than "allowlist the characters". A memo line is all Hangul and short, so a
 * character allowlist would pass "엄마한테 전화" straight into a label - the exact content the card's
 * safety boundary names. A pattern allowlist cannot: a fragment that matched {@link #DATE_ONLY} or
 * {@link #LEADING_TIME} is digits and separators by construction, so there is no memo it could be.
 * A place name has no such pattern, so a place token's label stays EMPTY and {@code line} is what
 * locates it - which is what #223 settled.
 *
 * <p>Pure: no clock, no catalog, no logging. A year-less date is not completed against today, because
 * completing it would be the parser deciding a fact on the traveller's behalf (AGENTS.md 원칙 3).
 */
public final class ItineraryParser {

    /** {@code 2026-03-15}, {@code 2026.3.15}, {@code 2026/3/15}, {@code 2026년 3월 15일}. */
    private static final Pattern FULL_DATE = Pattern.compile(
            "^\\s*(\\d{4})\\s*[-./년]\\s*(\\d{1,2})\\s*[-./월]\\s*(\\d{1,2})\\s*일?\\s*$");

    /** {@code 3/15}, {@code 3.15}, {@code 3월 15일} - a day with no year, which nobody completes. */
    private static final Pattern DATE_ONLY = Pattern.compile(
            "^\\s*(\\d{1,2})\\s*[-./월]\\s*(\\d{1,2})\\s*일?\\s*$");

    /** A leading {@code 09:00} or {@code 9:00}, followed by the rest of the line. */
    private static final Pattern LEADING_TIME = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s+(.+)$");

    /**
     * A leading bare hour in Korean - {@code 3시}, optionally with a meridiem - and the rest of the
     * line. Without a meridiem an hour of 12 or less names two moments of the day and this parser
     * picks neither; with one it names a single moment and is read.
     */
    private static final Pattern LEADING_HOUR = Pattern.compile(
            "^\\s*(오전|오후)?\\s*(\\d{1,2})\\s*시\\s*(.*)$");

    /** The catalog's own search bound. A longer line is not a place name, so it is never looked up. */
    public static final int MAX_LOOKUP = 100;

    private ItineraryParser() {
    }

    /** What one non-blank line turned out to be. */
    public record ParsedLine(int line, Kind kind, String label, LocalDate date, LocalTime startTime,
            String lookup) {

        public enum Kind {
            /** A full date; every following place belongs to it until the next one. */
            DATE_HEADER,
            /** A date with no year. Nobody completes it, so it becomes a DATE token. */
            AMBIGUOUS_DATE,
            /** An hour that names two moments of the day. Nobody picks one, so it becomes a TIME token. */
            AMBIGUOUS_TIME,
            /** Everything else: a candidate the catalog is asked about. */
            PLACE
        }

        public ParsedLine {
            Objects.requireNonNull(kind, "kind");
            if (line < 1) {
                throw new IllegalArgumentException("lines are 1-based");
            }
            if (label != null && label.length() > UnresolvedToken.MAX_LABEL) {
                throw new IllegalArgumentException("a label is a matched fragment, not a line");
            }
        }
    }

    /**
     * @param rawText the paste. It is read here and nowhere else; nothing returned contains it.
     */
    public static List<ParsedLine> parse(String rawText) {
        Objects.requireNonNull(rawText, "rawText");
        List<ParsedLine> parsed = new ArrayList<>();
        String[] lines = rawText.split("\r\n|\r|\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty()) {
                continue;
            }
            parsed.add(classify(index + 1, line));
        }
        return List.copyOf(parsed);
    }

    private static ParsedLine classify(int number, String line) {
        Matcher full = FULL_DATE.matcher(line);
        if (full.matches()) {
            LocalDate date = date(Integer.parseInt(full.group(1)), Integer.parseInt(full.group(2)),
                    Integer.parseInt(full.group(3)));
            return date == null
                    ? new ParsedLine(number, ParsedLine.Kind.PLACE, null, null, null, lookup(line))
                    : new ParsedLine(number, ParsedLine.Kind.DATE_HEADER, null, date, null, null);
        }
        Matcher dayOnly = DATE_ONLY.matcher(line);
        if (dayOnly.matches()) {
            // The fragment is safe to echo BECAUSE it matched: digits and one separator, nothing else.
            return new ParsedLine(number, ParsedLine.Kind.AMBIGUOUS_DATE, line, null, null, null);
        }
        Matcher timed = LEADING_TIME.matcher(line);
        if (timed.matches()) {
            LocalTime time = time(Integer.parseInt(timed.group(1)), Integer.parseInt(timed.group(2)));
            if (time != null) {
                return new ParsedLine(number, ParsedLine.Kind.PLACE, null, null, time,
                        lookup(timed.group(3).strip()));
            }
        }
        Matcher hour = LEADING_HOUR.matcher(line);
        if (hour.matches()) {
            String meridiem = hour.group(1);
            int value = Integer.parseInt(hour.group(2));
            String rest = hour.group(3).strip();
            if (meridiem == null && value <= 12) {
                // Two moments, and the paste does not say which. The fragment echoed here is digits
                // and the character 시 by construction, so it cannot be carrying anything else.
                return new ParsedLine(number, ParsedLine.Kind.AMBIGUOUS_TIME, value + "시", null, null,
                        rest.isEmpty() ? null : lookup(rest));
            }
            LocalTime read = time(meridiem == null || value == 12
                    ? value
                    : "오후".equals(meridiem) ? value + 12 : value, 0);
            if (read != null && !rest.isEmpty()) {
                return new ParsedLine(number, ParsedLine.Kind.PLACE, null, null, read, lookup(rest));
            }
        }
        return new ParsedLine(number, ParsedLine.Kind.PLACE, null, null, null, lookup(line));
    }

    /** Null when the line is too long to be a place name; the caller then has nothing to look up. */
    private static String lookup(String line) {
        return line.length() > MAX_LOOKUP ? null : line;
    }

    private static LocalDate date(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException impossible) {
            // 2026-13-40 is not a date; it is a line that looked like one. It goes back to the
            // place path rather than being corrected into a date nobody wrote.
            return null;
        }
    }

    private static LocalTime time(int hour, int minute) {
        try {
            return LocalTime.of(hour, minute);
        } catch (java.time.DateTimeException impossible) {
            return null;
        }
    }
}
