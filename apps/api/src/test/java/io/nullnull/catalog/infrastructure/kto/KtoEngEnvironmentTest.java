package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("BA-086 operator settings for the English commands")
class KtoEngEnvironmentTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("a local .env.local configures the English base and host like the Korean ones")
    void localDotenvCarriesTheEnglishBaseAndHost() throws Exception {
        Path dotenv = temporaryDirectory.resolve(".env.local");
        Files.writeString(dotenv, """
                KTO_SERVICE_KEY='test-decoding-key'
                KTO_ENG_BASE_URL=https://apis.data.go.kr/B551011/EngService2
                KTO_ALLOWED_HOST=127.0.0.1
                """);

        Map<String, Object> gateway = KtoSmokeEnvironment.gatewayProperties(KtoSmokeEnvironment.load(Map.of(), dotenv));

        assertThat(gateway).containsEntry("nullnull.kto.eng-base-url", "https://apis.data.go.kr/B551011/EngService2")
                .containsEntry("nullnull.sources.KTO_ENG_SERVICE.allowed-hosts[0]", "127.0.0.1")
                .containsEntry("nullnull.sources.KTO_KOR_SERVICE_2.allowed-hosts[0]", "127.0.0.1");
    }
}
