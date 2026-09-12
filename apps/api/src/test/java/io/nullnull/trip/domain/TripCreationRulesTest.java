package io.nullnull.trip.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * BA-030-T1: the rules createTrip applies before anything is written. These types are pure, so the
 * rules are tested here rather than through HTTP - a reversed range is arithmetic, not a controller
 * concern, and proving it at this level is what lets the integration tests stay about persistence.
 */
@DisplayName("BA-030-T1 trip creation rules")
class TripCreationRulesTest {

    @Nested
    @DisplayName("date range")
    class DateRange {

        @Test
        void rejectsAReversedRange() {
            assertThatThrownBy(() -> TripDateRange.of(LocalDate.of(2026, 10, 7),
                    LocalDate.of(2026, 10, 4), "Asia/Seoul"))
                    .isInstanceOf(TripValidationException.class)
                    .extracting(failure -> ((TripValidationException) failure).violations().get(0).field())
                    .isEqualTo("endDate");
        }

        @Test
        void acceptsASingleDayTrip() {
            TripDateRange range = TripDateRange.of(LocalDate.of(2026, 10, 4),
                    LocalDate.of(2026, 10, 4), "Asia/Seoul");
            assertThat(range.dayCount()).isOne();
            assertThat(range.days()).containsExactly(LocalDate.of(2026, 10, 4));
        }

        @Test
        void acceptsExactlyThirtyDaysAndRejectsThirtyOne() {
            LocalDate start = LocalDate.of(2026, 10, 1);
            assertThatCode(() -> TripDateRange.of(start, start.plusDays(29), "Asia/Seoul"))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> TripDateRange.of(start, start.plusDays(30), "Asia/Seoul"))
                    .isInstanceOf(TripValidationException.class)
                    .extracting(failure -> ((TripValidationException) failure).violations().get(0).code())
                    .isEqualTo("DateRangeTooLong");
        }

        @Test
        @DisplayName("a DST transition inside the range changes no day")
        void daylightSavingDoesNotChangeTheDayCount() {
            // 2026-03-08 loses an hour in New York and 2026-11-01 gains one. Days here are calendar
            // dates, so neither is 23 or 25 hours of anything - both are exactly one date. Deriving
            // days from instants is what would have made the count depend on the offset.
            TripDateRange spring = TripDateRange.of(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 9),
                    "America/New_York");
            TripDateRange autumn = TripDateRange.of(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 2),
                    "America/New_York");
            assertThat(spring.dayCount()).isEqualTo(3);
            assertThat(autumn.dayCount()).isEqualTo(3);
            assertThat(spring.days()).containsExactly(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8),
                    LocalDate.of(2026, 3, 9));
        }

        @Test
        void daysAreAscendingAndCoverTheWholeRange() {
            List<LocalDate> days = TripDateRange.of(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2),
                    "Asia/Seoul").days();
            assertThat(days).containsExactly(LocalDate.of(2026, 12, 30), LocalDate.of(2026, 12, 31),
                    LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
        }

        @Test
        void rejectsATimezoneTheRuntimeCannotResolve() {
            for (String unknown : List.of("Mars/Olympus", "", "   ", "Asia/Seoul ")) {
                assertThatThrownBy(() -> TripDateRange.zone(unknown))
                        .as("timezone %s", unknown)
                        .isInstanceOf(TripValidationException.class);
            }
            // The message must not echo what was sent back to the caller.
            assertThatThrownBy(() -> TripDateRange.zone("Mars/Olympus"))
                    .hasMessageNotContaining("Mars");
        }
    }

    @Nested
    @DisplayName("default title")
    class DefaultTitle {

        @Test
        void koreanOwnersGetTheKoreanDefaultAndEveryoneElseEnglish() {
            assertThat(TripTitles.defaultTitle("ko-KR")).isEqualTo(TripTitles.KOREAN_DEFAULT);
            assertThat(TripTitles.defaultTitle("ko")).isEqualTo(TripTitles.KOREAN_DEFAULT);
            assertThat(TripTitles.defaultTitle("en-US")).isEqualTo(TripTitles.ENGLISH_DEFAULT);
            // Japanese and Chinese are disabled in P0, so they fall back rather than getting a
            // language the product does not actually ship.
            assertThat(TripTitles.defaultTitle("ja-JP")).isEqualTo(TripTitles.ENGLISH_DEFAULT);
            assertThat(TripTitles.defaultTitle(null)).isEqualTo(TripTitles.ENGLISH_DEFAULT);
        }

        @Test
        void aBlankTitleIsAbsentRatherThanAOneCharacterTitle() {
            // The schema's minLength 1 counts a space, so " " would pass validation and then render
            // as an empty heading. Treating blank as absent is what gives it the locale default.
            assertThat(TripTitles.resolve("   ", "ko-KR")).isEqualTo(TripTitles.KOREAN_DEFAULT);
            assertThat(TripTitles.resolve(null, "ko-KR")).isEqualTo(TripTitles.KOREAN_DEFAULT);
            assertThat(TripTitles.resolve("  가을 여행  ", "ko-KR")).isEqualTo("가을 여행");
        }

        @Test
        void rejectsATitleLongerThanTheSchemaAllows() {
            assertThatThrownBy(() -> TripTitles.resolve("x".repeat(101), "en-US"))
                    .isInstanceOf(TripValidationException.class);
            assertThatCode(() -> TripTitles.resolve("x".repeat(100), "en-US"))
                    .doesNotThrowAnyException();
        }

        @Test
        void theSameRequestAlwaysProducesTheSameTitle() {
            // Determinism is not decoration: the idempotency guard replays a stored response, so a
            // title that varied between attempts would make a retry disagree with the original.
            assertThat(TripTitles.resolve(null, "ko-KR")).isEqualTo(TripTitles.resolve(null, "ko-KR"));
        }
    }

    @Nested
    @DisplayName("interests")
    class Interests {

        @Test
        void emptyIsValid() {
            assertThat(TripInterest.validated(List.of())).isEmpty();
            assertThat(TripInterest.validated(null)).isEmpty();
        }

        @Test
        @DisplayName("the same code twice is rejected even when the weights differ")
        void rejectsARepeatedCode() {
            // This is the pair the contract's uniqueItems lets through - the objects differ - and
            // the ERD's primary key (trip_id, interest_code) forbids.
            assertThatThrownBy(() -> TripInterest.validated(List.of(
                    new TripInterest("FOOD", 1), new TripInterest("FOOD", 5))))
                    .isInstanceOf(TripValidationException.class)
                    .extracting(failure -> ((TripValidationException) failure).violations().get(0).code())
                    .isEqualTo("Duplicate");
        }

        @Test
        void rejectsWeightsOutsideTheContractRange() {
            assertThatThrownBy(() -> new TripInterest("FOOD", 0)).isInstanceOf(TripValidationException.class);
            assertThatThrownBy(() -> new TripInterest("FOOD", 6)).isInstanceOf(TripValidationException.class);
            assertThatCode(() -> new TripInterest("FOOD", 1)).doesNotThrowAnyException();
            assertThatCode(() -> new TripInterest("FOOD", 5)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the whole vocabulary fits under the size cap, so the cap never refuses a full screen")
        void theWholeVocabularyFitsUnderTheSizeCap() {
            // MAX_INTERESTS (20) can no longer be reached: every code must be one of the thirteen and a
            // code may appear once, so a valid list tops out at thirteen. The cap stays because the
            // contract declares maxItems 20, but the real bound is the vocabulary - and the invariant
            // worth holding is that selecting EVERY chip is still accepted. Adding an eighth style code
            // is fine; adding eight would silently make a full selection unsubmittable.
            List<TripInterest> everyChip = InterestVocabulary.codes().stream()
                    .map(code -> new TripInterest(code, InterestVocabulary.NEUTRAL_WEIGHT)).toList();
            assertThat(everyChip).hasSize(13);
            assertThat(everyChip.size()).isLessThanOrEqualTo(TripInterest.MAX_INTERESTS);
            assertThatCode(() -> TripInterest.validated(everyChip)).doesNotThrowAnyException();

            // The size guard itself still holds for a list that reaches it.
            List<TripInterest> tooMany = new java.util.ArrayList<>(everyChip);
            while (tooMany.size() <= TripInterest.MAX_INTERESTS) {
                tooMany.add(everyChip.get(0));
            }
            assertThatThrownBy(() -> TripInterest.validated(tooMany))
                    .isInstanceOf(TripValidationException.class);
        }

        @Test
        @DisplayName("only the thirteen FCR-020 codes are accepted, exactly as the chips spell them")
        void enforcesTheVocabularyFcr020Settled() {
            // Until FCR-020 was answered any non-blank string was accepted, because inventing an
            // allowlist would have produced either codes no chip can send or chips the server rejects.
            assertThatThrownBy(() -> new TripInterest("anything-at-all", 3))
                    .isInstanceOf(TripValidationException.class)
                    .extracting(failure -> ((TripValidationException) failure).violations().get(0).code())
                    .isEqualTo("Unsupported");
            assertThatThrownBy(() -> new TripInterest("  ", 3)).isInstanceOf(TripValidationException.class);
            // Case is part of the code. Normalising it here would be a silent fallback: the ERD key
            // is (trip_id, interest_code), so "food" and "FOOD" would be two rows for one chip.
            assertThatThrownBy(() -> new TripInterest("food", 3))
                    .isInstanceOf(TripValidationException.class);
            assertThatCode(() -> new TripInterest("FOOD", 3)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the rejection never echoes the value the caller sent")
        void doesNotEchoTheRejectedCode() {
            // A code arrives from a client and lands in a Problem detail that is logged and shown.
            assertThatThrownBy(() -> new TripInterest("<script>alert(1)</script>", 3))
                    .isInstanceOf(TripValidationException.class)
                    .satisfies(failure -> assertThat(
                            ((TripValidationException) failure).violations().get(0).message())
                            .doesNotContain("script").contains("13"));
        }
    }

    @Nested
    @DisplayName("aggregate")
    class Aggregate {

        private Trip trip(long version, TripStatus status, Instant archivedAt) {
            Instant now = Instant.parse("2026-09-10T00:00:00Z");
            return new Trip(UUID.randomUUID(), UUID.randomUUID(), "새 여행",
                    TripDateRange.of(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 7), "Asia/Seoul"),
                    PlanningLevel.NOTHING, status, version, List.of(), now, now, archivedAt);
        }

        @Test
        void versionStartsAtOneAndIsTheQuotedEntityTag() {
            assertThat(trip(1, TripStatus.DRAFT, null).entityTag()).isEqualTo("\"1\"");
            assertThat(trip(42, TripStatus.DRAFT, null).entityTag()).isEqualTo("\"42\"");
            assertThatThrownBy(() -> trip(0, TripStatus.DRAFT, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void archivedAtAndStatusCannotDisagree() {
            Instant when = Instant.parse("2026-09-11T00:00:00Z");
            assertThatThrownBy(() -> trip(1, TripStatus.ARCHIVED, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> trip(1, TripStatus.DRAFT, when))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatCode(() -> trip(1, TripStatus.ARCHIVED, when)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a created trip seeds one empty day per date, which is the contract's example")
        void daysAreDerivedNotStored() {
            assertThat(trip(1, TripStatus.DRAFT, null).days())
                    .containsExactly(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5),
                            LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7));
        }
    }
}
