package io.nullnull.crowd.domain;

import java.util.Map;
import java.util.Objects;

/**
 * The published name of a crowd metric, in the language the sentence is written in.
 *
 * <p>Not a decision made here. {@code docs/data/SOURCE_CATALOG.md} §3 names this metric and spends a
 * paragraph on what it is not: "방문자 수가 아닌 상대 집중률 예측. 가장 붐비는 시기를 100으로 둔 날짜
 * 단위 상대값이며 인원·수용률·시간대 예측이 아니다". So the label is 상대 집중률, and calling it
 * 혼잡도 or "crowd level" would publish exactly the misreading the catalog exists to prevent - in a
 * sentence a traveller reads, because {@code metricLabel} is substituted into the explanation
 * verbatim and apps/ai's validator checks that it appears there.
 *
 * <p>Here rather than in {@code source_registry}: a label belongs to the metric, and a source that
 * one day publishes two metrics would not fit a column on the source. Here rather than in a
 * migration: this is text a person may need to correct, and an applied migration cannot be.
 *
 * <p>One language per lookup because apps/ai picks its template by locale and drops this string into
 * it unchanged; a Korean label in an English sentence would read "lowers 상대 집중률 from 80 to 20".
 */
public final class CrowdMetricLabel {

    /** The metric the concentration forecast publishes (SOURCE_CATALOG §3, "unit"). */
    public static final String CONCENTRATION_INDEX = "KTO_RELATIVE_CONCENTRATION_INDEX";

    private static final Map<String, Map<String, String>> LABELS = Map.of(
            CONCENTRATION_INDEX, Map.of(
                    "ko", "상대 집중률",
                    "en", "relative concentration"));

    private CrowdMetricLabel() {
    }

    /**
     * @throws IllegalArgumentException when the metric or the locale has no published label. Refused
     *     rather than defaulted: a sentence naming a metric by a name nobody approved is worse than
     *     no sentence, and the caller can fail the run instead.
     */
    public static String of(String metricCode, String locale) {
        Map<String, String> byLocale = LABELS.get(Objects.requireNonNull(metricCode, "metricCode"));
        if (byLocale == null) {
            throw new IllegalArgumentException("no published label for metric " + metricCode);
        }
        String label = byLocale.get(Objects.requireNonNull(locale, "locale"));
        if (label == null) {
            throw new IllegalArgumentException("no " + locale + " label for metric " + metricCode);
        }
        return label;
    }

    /** The metrics that can be named in an explanation at all. */
    public static java.util.Set<String> published() {
        return LABELS.keySet();
    }
}
