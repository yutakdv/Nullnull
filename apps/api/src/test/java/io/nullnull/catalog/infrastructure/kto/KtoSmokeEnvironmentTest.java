package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The precedence in {@link KtoSmokeEnvironment#load} is correct and invisible: a process environment
 * variable beats .env.local, so editing the file and watching the run keep the old value gives no
 * hint that the file was read at all. That cost an operator half an hour of a real smoke attempt.
 */
class KtoSmokeEnvironmentTest {

    @TempDir Path directory;

    @Test
    void saysWhichSourceWonWhenBothCarryTheSameSetting() throws Exception {
        Path dotenv = directory.resolve(".env.local");
        Files.writeString(dotenv, """
                SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5434/nullnull_local
                KTO_BASE_URL=https://apis.data.go.kr/B551011/KorService2
                """);

        List<String> report = KtoSmokeEnvironment.sources(
                Map.of("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:9999/other"), dotenv);

        assertThat(report).contains("SPRING_DATASOURCE_URL <- process env (overrides .env.local)");
        assertThat(report).contains("KTO_BASE_URL <- .env.local");
        assertThat(report).contains("KTO_SERVICE_KEY <- absent");
        // The report must agree with what load() actually resolved, or it is a second story.
        assertThat(KtoSmokeEnvironment.load(
                Map.of("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:9999/other"), dotenv))
                .containsEntry("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:9999/other");
    }

    /**
     * KTO_SERVICE_KEY and SPRING_DATASOURCE_PASSWORD are both in the allow list. A diagnostic that
     * leaked either would be worse than the confusion it removes.
     */
    @Test
    void namesTheSettingsWithoutEverPrintingAValue() throws Exception {
        Path dotenv = directory.resolve(".env.local");
        Files.writeString(dotenv, """
                KTO_SERVICE_KEY=file-secret-must-not-appear
                SPRING_DATASOURCE_PASSWORD=file-password-must-not-appear
                """);

        List<String> report = KtoSmokeEnvironment.sources(
                Map.of("KTO_SERVICE_KEY", "process-secret-must-not-appear"), dotenv);

        assertThat(report).contains("KTO_SERVICE_KEY <- process env (overrides .env.local)",
                "SPRING_DATASOURCE_PASSWORD <- .env.local");
        assertThat(String.join("\n", report))
                .doesNotContain("file-secret-must-not-appear", "process-secret-must-not-appear",
                        "file-password-must-not-appear");
    }

    @Test
    void reportsEveryAllowedSettingSoAnAbsentOneIsVisibleToo() {
        List<String> report = KtoSmokeEnvironment.sources(Map.of(), directory.resolve("missing.env"));

        assertThat(report).hasSize(12).allSatisfy(line -> assertThat(line).endsWith("<- absent"));
        assertThat(report).isSorted();
    }
}
