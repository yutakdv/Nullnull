package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.slot.SlotEvaluateResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-042-T7: every {@code CandidateMatchResult.state} accounts for itself.
 *
 * <p>The device is {@code CrowdQualityFlagCoverageIT}'s, applied to a second vocabulary: each
 * published value must either be producible, or be shown to have no producer, or be registered as
 * waiting for a named slice. Without it a state can be implemented, published to the Frontend, and
 * never reachable - the shape of defect this repository keeps finding, and one a passing suite says
 * nothing about.
 *
 * <p>Two of the five have no producer today, for two different reasons, and both are measured rather
 * than argued:
 *
 * <ul>
 * <li>{@code SIMILAR} is absent from {@code SlotEvaluateResponse.State}. The service that computes
 *     the answer cannot return it at all, so no hydration on this side could produce it - the public
 *     vocabulary and the internal one genuinely differ. That DTO's own javadoc already says "P0 never
 *     answers SIMILAR", so this is not a discovery; what was missing is that a sentence in a comment
 *     stops being true silently, and this fails when it does.</li>
 * <li>{@code CHECKING} is a different kind of absent, and this test first got it wrong by treating
 *     the two the same. It IS in the service's vocabulary; what is missing is the input that makes
 *     it reachable - {@code SlotEvaluateRequest.checking}, which its own contract defines as "true
 *     only while a real verification job runs for this candidate", and P0 registers no such job. So
 *     the evidence for it is not an enum but a call site, and it is proved where the call is made:
 *     {@code CandidateMatchIT} asserts the outgoing request always carries false.</li>
 * </ul>
 *
 * <p>Neither is removed from the contract: dropping a value a client may already branch on is a
 * breaking change, and both become reachable the day their producer exists. What must not happen is
 * that they quietly look implemented.
 */
@DisplayName("BA-042-T7 candidate match state coverage")
class CandidateMatchStateCoverageTest {

    /**
     * States the computing service cannot express at all, with the reason.
     *
     * <p>Only structural absence belongs here, because that is what this test can check: the entry
     * is cross-examined against {@code SlotEvaluateResponse.State}, so a note that outlived its gap
     * fails rather than lingering.
     */
    private static final Map<String, String> NOT_IN_THE_SERVICE_VOCABULARY = new LinkedHashMap<>(Map.of(
            "SIMILAR", "SlotEvaluateResponse.State does not declare it, so apps/ai cannot answer with it"));

    /**
     * States the service can express but nothing in P0 asks for, with where that is proved.
     *
     * <p>These cannot be checked against an enum - the value is there - so each names the test that
     * shows the input never arrives. A reason with no such test would be an assertion about the
     * system written in a comment.
     */
    private static final Map<String, String> NO_INPUT_PRODUCES_THEM = new LinkedHashMap<>(Map.of(
            "CHECKING", "needs SlotEvaluateRequest.checking=true, which needs a candidate verification"
                    + " job; P0 registers none, and CandidateMatchIT asserts the request carries false"));

    /**
     * States this server answers itself, without asking the computing service, with where that is proved.
     *
     * <p>A third shape, and the one adding {@code NOT_ACTIVE} exposed: the two registers above both
     * describe values NOTHING produces. This one describes a value {@code apps/ai} cannot produce and
     * Spring can - which is ADR-0006's boundary showing through the vocabulary. Putting it in
     * {@code NOT_IN_THE_SERVICE_VOCABULARY} would satisfy the structural check and state something
     * false: that no hydration on this side could produce it.
     *
     * <p>Checked in both directions below - a value here must be absent from the service vocabulary,
     * or the claim is confused - and each entry names the test that shows the answer really is served.
     */
    private static final Map<String, String> ANSWERED_BY_THIS_SERVER = new LinkedHashMap<>(Map.of(
            "NOT_ACTIVE", "CandidateMatchService answers it for a candidate whose status is not ACTIVE,"
                    + " without calling apps/ai at all; CandidateMatchIT's BA-042-T10 reaches it the way a"
                    + " traveller does, by scheduling the candidate through addTripItem"));

    private static final Pattern STATE_ENUM = Pattern.compile(
            "(?s)CandidateMatchResult:.*?state:.*?enum: \\[([^\\]]+)\\]");

    @Test
    @DisplayName("BA-042-T7 every published state is producible or is recorded as having no producer")
    void everyStateAccountsForItself() {
        Set<String> published = publishedStates();
        Set<String> computable = Arrays.stream(SlotEvaluateResponse.State.values())
                .map(Enum::name).collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        assertThat(published).as("the contract's own vocabulary was found").isNotEmpty();
        // Every published value is either something apps/ai can answer with, or is written down here
        // with the reason it cannot be. A new value in either place therefore has to be accounted for
        // before this passes again.
        assertThat(published).allSatisfy(state ->
                assertThat(computable.contains(state) || NOT_IN_THE_SERVICE_VOCABULARY.containsKey(state)
                                || ANSWERED_BY_THIS_SERVER.containsKey(state))
                        .as("%s is neither computable, nor recorded as outside the service vocabulary, nor"
                                + " recorded as answered by this server", state)
                        .isTrue());
        // A note that outlived its gap fails here: claiming the service cannot express something it
        // can is the direction that would let this register go quietly stale.
        assertThat(NOT_IN_THE_SERVICE_VOCABULARY.keySet())
                .as("a state recorded as outside the service vocabulary must really be outside it")
                .doesNotContainAnyElementsOf(computable);
        // The other register is the opposite shape: these ARE in the vocabulary, which is why they
        // cannot be checked against it.
        assertThat(NO_INPUT_PRODUCES_THEM.keySet()).allSatisfy(state ->
                assertThat(computable).as("%s is recorded as needing an input, so it must be a value"
                        + " the service can return", state).contains(state));
        // A value this server answers itself must not also be one apps/ai can return: if it were, the
        // entry would be hiding which of the two produced it, and the reason would stop being checkable.
        assertThat(ANSWERED_BY_THIS_SERVER.keySet())
                .as("a state recorded as answered by this server must be outside the service vocabulary")
                .doesNotContainAnyElementsOf(computable);
        assertThat(published).containsAll(NOT_IN_THE_SERVICE_VOCABULARY.keySet());
        assertThat(published).containsAll(NO_INPUT_PRODUCES_THEM.keySet());
        assertThat(published).containsAll(ANSWERED_BY_THIS_SERVER.keySet());
        java.util.stream.Stream.of(NOT_IN_THE_SERVICE_VOCABULARY.values().stream(),
                        NO_INPUT_PRODUCES_THEM.values().stream(), ANSWERED_BY_THIS_SERVER.values().stream())
                .flatMap(values -> values)
                .forEach(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    @DisplayName("BA-042-T7 the internal vocabulary is a subset of the published one, never the reverse")
    void theServiceCannotAnswerWithSomethingUnpublishable() {
        // The asymmetry that matters: apps/ai answering with a value the contract does not publish
        // would leave the server holding a state it cannot serialise. The gap found here runs the
        // other way - the contract publishes SIMILAR and the service has no such value - which is
        // safe to serve and unsafe to believe.
        assertThat(publishedStates())
                .containsAll(Arrays.stream(SlotEvaluateResponse.State.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("BA-042-T7 an opening window cannot say UNKNOWN on the path that hydrates it")
    void unknownOpeningStateHasNoProducerEither() {
        // OpeningWindowIn keeps three states and the catalog type behind it has two, so "we looked
        // and could not tell" cannot be constructed from our data - the only way to express it is to
        // leave the date out entirely. Pinned so that adding a source which CAN say it is a
        // deliberate change rather than a silent one.
        assertThat(OpeningWindowIn.OpeningState.values())
                .containsExactly(OpeningWindowIn.OpeningState.OPEN, OpeningWindowIn.OpeningState.CLOSED,
                        OpeningWindowIn.OpeningState.UNKNOWN);
    }

    private static Set<String> publishedStates() {
        Matcher matcher = STATE_ENUM.matcher(contract());
        if (!matcher.find()) {
            throw new IllegalStateException("CandidateMatchResult.state was not found in the contract");
        }
        return Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static String contract() {
        String property = System.getProperty("nullnull.openapi.path");
        Path file = property == null || property.isBlank()
                ? Path.of("../../docs/api/openapi.yaml") : Path.of(property);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException("cannot read the contract", missing);
        }
    }

}
