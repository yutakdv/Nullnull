package io.nullnull.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.importer.domain.ItineraryParser;
import io.nullnull.importer.domain.ItineraryParser.ParsedLine.Kind;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ItineraryParserTest {

    @Test
    void fullDateAndPlaceOnOneLineKeepTheWrittenDate() {
        var line = ItineraryParser.parse("2026-10-03 Gyeongbokgung Palace").getFirst();
        assertThat(line.kind()).isEqualTo(Kind.PLACE);
        assertThat(line.date()).isEqualTo(LocalDate.parse("2026-10-03"));
        assertThat(line.lookup()).isEqualTo("Gyeongbokgung Palace");
        assertThat(line.label()).isNull();
    }

    @Test
    void yearlessDateWithPlaceKeepsTheYearAsAnOpenQuestionAndNeverEchoesThePlace() {
        var line = ItineraryParser.parse("10월 3일 경복궁").getFirst();
        assertThat(line.kind()).isEqualTo(Kind.AMBIGUOUS_DATE);
        assertThat(line.date()).isNull();
        assertThat(line.label()).isEqualTo("10월 3일");
        assertThat(line.lookup()).isEqualTo("경복궁");
    }
}
