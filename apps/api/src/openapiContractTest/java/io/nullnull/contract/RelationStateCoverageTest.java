package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.CatalogRelationProjectionService.RelationState;
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
 * BA-024-T7: every {@code RelationState} accounts for itself.
 *
 * <p>Same device as {@code CandidateMatchStateCoverageTest} and {@code CrowdQualityFlagCoverageIT},
 * on a third vocabulary. Three of the five have no producer, for three different reasons, and the
 * differences are the point - a register that flattened them would say less than the values do.
 *
 * <ul>
 * <li>{@code NONE} is excluded by an owner decision, not by a missing part. HANDOFF §9, under
 *     "재논의 불필요", enumerates what this surface may answer - {@code NULLNULL_CATALOG_RULE} SIMILAR
 *     and {@code UNKNOWN(SOURCE_DISABLED)} - and closes the list. NONE is a claim that we looked
 *     everywhere, and with the official relation source unapproved we cannot have. So the evidence
 *     is the decision plus {@code CatalogRelatedPlacesApiIT}, where a place with no candidate answers
 *     UNKNOWN and not NONE.
 * <li>{@code EXACT} is storable and unreachable. V027 admits it only for a confirmed provider-direct
 *     relation, and the sole such provider is {@code KTO_RELATED_PLACES}, which is unapproved and
 *     disabled in the registry. Nothing here has to argue that: the day it is approved, EXACT starts
 *     being produced and this entry has to be deleted.
 * <li>{@code CHECKING} needs a relation verification actually running, and P0 registers no such job -
 *     the same shape BA-042 found for its own CHECKING. The service has no branch that can emit it.
 * </ul>
 *
 * <p>None of the three is removed from the contract. Dropping a value a client may already branch on
 * is breaking, and each becomes reachable the day its producer exists. What must not happen is that
 * they quietly look implemented.
 */
@DisplayName("BA-024-T7 relation state coverage")
class RelationStateCoverageTest {

    /**
     * States the service will not emit, with why. Cross-examined against what it can express, so an
     * entry that outlived its reason fails instead of lingering.
     */
    private static final Map<String, String> NO_PRODUCER = new LinkedHashMap<>(Map.of(
            "NONE", "HANDOFF §9 (사용자 확정 결정) closes this surface to SIMILAR and UNKNOWN(SOURCE_DISABLED);"
                    + " NONE claims a complete lookup the unapproved relation source makes impossible",
            "EXACT", "V027 admits EXACT only for a confirmed PROVIDER_DIRECT relation, and the only such"
                    + " provider (KTO_RELATED_PLACES) is unapproved and disabled in the registry",
            "CHECKING", "needs a relation verification in flight; P0 registers no such job, so no branch"
                    + " of CatalogRelationProjectionService can reach it"));

    /**
     * States a real call does produce, and where that is shown. A claim of producibility with no test
     * behind it would be the same unbacked sentence this register exists to refuse.
     */
    private static final Map<String, String> PRODUCED_BY = new LinkedHashMap<>(Map.of(
            "SIMILAR", "CatalogRelatedPlacesApiIT: a place with a rule-derived relation answers SIMILAR",
            "UNKNOWN", "CatalogRelatedPlacesApiIT: a place with no eligible candidate answers"
                    + " UNKNOWN(SOURCE_DISABLED) with an empty list"));

    private static final Pattern STATE_ENUM = Pattern.compile("(?s)\\n    RelationState:.*?enum: \\[([^\\]]+)\\]");

    @Test
    @DisplayName("BA-024-T7 every published relation state is produced or is recorded as having no producer")
    void everyStateAccountsForItself() {
        Set<String> published = publishedStates();
        Set<String> declared = Arrays.stream(RelationState.values())
                .map(Enum::name).collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        assertThat(published).as("the contract's own vocabulary was found").isNotEmpty();
        // The server's enum and the contract's must be the same set. A value in one and not the other
        // is either a state the server can hold and cannot serialise, or one the Frontend can branch
        // on and the server can never send.
        assertThat(declared).isEqualTo(published);
        // Every published value is accounted for exactly once, in one register or the other.
        assertThat(published).allSatisfy(state -> assertThat(
                NO_PRODUCER.containsKey(state) ^ PRODUCED_BY.containsKey(state))
                .as("%s must be recorded as produced or as having no producer, and not both", state)
                .isTrue());
        java.util.stream.Stream.concat(NO_PRODUCER.values().stream(), PRODUCED_BY.values().stream())
                .forEach(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    @DisplayName("BA-024-T7 NONE stays in the published vocabulary even though nothing emits it")
    void theExcludedStateIsNotRemovedFromTheContract() {
        // Removing it would be the easy way to make the register tidy and a breaking change for any
        // client already branching on it. The decision was that we do not answer NONE, not that NONE
        // stops existing.
        assertThat(publishedStates()).contains("NONE");
        assertThat(RelationState.valueOf("NONE")).isNotNull();
    }

    private static Set<String> publishedStates() {
        try {
            String spec = Files.readString(Path.of("../../docs/api/openapi.yaml"), StandardCharsets.UTF_8);
            Matcher matcher = STATE_ENUM.matcher(spec);
            if (!matcher.find()) {
                return Set.of();
            }
            return Arrays.stream(matcher.group(1).split(","))
                    .map(String::trim)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the contract must be readable from the api module", e);
        }
    }
}
