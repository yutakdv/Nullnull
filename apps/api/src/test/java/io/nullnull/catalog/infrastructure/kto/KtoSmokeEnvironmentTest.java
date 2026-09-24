package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.OperationsContext;
import io.nullnull.catalog.application.KtoGatewayException;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
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

        // 13 since BA-086 added KTO_ENG_BASE_URL for the English commands.
        assertThat(report).hasSize(13).allSatisfy(line -> assertThat(line).endsWith("<- absent"));
        assertThat(report).isSorted();
    }

    @Test
    @DisplayName("#226 a loaded setting beats the yaml default that would otherwise blank it")
    void loadedSettingsOutrankTheYamlDefault() {
        // application.yaml:173 is `service-key: ${KTO_SERVICE_KEY:}`, so with no such process
        // variable it resolves to an empty string - and SpringApplicationBuilder.properties() puts
        // our loaded value in defaultProperties, the LOWEST precedence there is. The file was read,
        // reported as read, and then overridden by a blank. This is that precedence, in miniature.
        org.springframework.core.env.StandardEnvironment environment =
                new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource(
                "application.yaml", java.util.Map.of("nullnull.kto.service-key", "")));
        org.springframework.context.support.GenericApplicationContext context =
                new org.springframework.context.support.GenericApplicationContext();
        context.setEnvironment(environment);

        KtoSmokeEnvironment.applying(java.util.Map.of("KTO_SERVICE_KEY", "a-real-key"))
                .initialize(context);

        assertThat(environment.getProperty("nullnull.kto.service-key")).isEqualTo("a-real-key");
    }

    @Test
    @DisplayName("#227 a failure that is not the provider's says so instead of naming a code it did not check")
    void aNonProviderFailureIsNotReportedAsAProviderCode() {
        // The adapter's own vocabulary is passed through untouched.
        assertThat(KtoSmokeEnvironment.failureCode(new IllegalStateException("wrapped",
                new KtoGatewayException(KtoGatewayException.Code.KTO_QUOTA_EXHAUSTED))))
                .isEqualTo("KTO_QUOTA_EXHAUSTED");

        // An unanticipated failure keeps its type, which is what separates "the provider refused us"
        // from "this process could not start". The old code answered a bare UNEXPECTED_FAILURE and a
        // Spring startup failure was indistinguishable from a provider one.
        assertThat(KtoSmokeEnvironment.failureCode(
                new IllegalArgumentException("Invalid boolean value []")))
                .isEqualTo("UNEXPECTED_FAILURE (IllegalArgumentException)")
                // and it never carries the message, which can hold a configuration value
                .doesNotContain("Invalid boolean value");

        // The catch-all's own code, carrying what it caught.
        assertThat(KtoSmokeEnvironment.failureCode(new KtoGatewayException(
                KtoGatewayException.Code.KTO_INTERNAL_FAILURE, NullPointerException.class)))
                .isEqualTo("KTO_INTERNAL_FAILURE (NullPointerException)");
    }

    @Test
    @DisplayName("#183 a refusal from OperationsContext is named by its code, even wrapped by the context start")
    void anOperationsRefusalKeepsItsCode() {
        for (OperationsContext.Refused.Code code : OperationsContext.Refused.Code.values()) {
            assertThat(KtoSmokeEnvironment.failureCode(new IllegalStateException("context start",
                    new OperationsContext.Refused(code, "environment=staging: postgresql://db.internal:5432/x"))))
                    .isEqualTo(code.name());
        }
    }
}
