package io.nullnull.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REC-CI-4, the {@code apps/api} half of the manifest integrity check (DX-004).
 *
 * <p>{@code apps/ai/tests/recommendation/manifest.json} registers the safety IDs that count as
 * implemented, and a row is only evidence while the test file it names still exists. The Python
 * suite resolves its own {@code pytest} rows, but it cannot resolve a {@code gradle:*} row: the
 * image built from {@code apps/ai/Dockerfile} ships {@code apps/ai} alone, so every path under
 * {@code apps/api} is missing there regardless of the repository's real state. This suite owns
 * that half, because {@code apps/api/Dockerfile} copies both the manifest and {@code apps/api/src}
 * into the same layout the checkout has.
 */
@DisplayName("manifest gradle:* rows name test files that exist")
class ManifestTestPathParityTest {

    private static JsonNode manifest;
    private static Path repositoryRoot;

    @BeforeAll
    static void loadManifest() throws IOException {
        String property = System.getProperty("nullnull.ai.manifest.path");
        assertThat(property).as("system property nullnull.ai.manifest.path").isNotBlank();

        Path manifestPath = Path.of(property).toAbsolutePath().normalize();
        assertThat(manifestPath).as("apps/ai manifest").isRegularFile();
        manifest = JsonMapper.builder().build().readTree(Files.readString(manifestPath));

        // tests/recommendation/manifest.json -> tests -> apps/ai -> apps -> repository root. The
        // container keeps this relationship: /workspace/apps/ai/tests/... sits beside /workspace/apps/api.
        repositoryRoot = manifestPath.getParent().getParent().getParent().getParent().getParent();
    }

    @Test
    void theResolvedRootIsTheOneHoldingTheModuleTheRowsPointAt() {
        // Without this the suite could reproduce the defect it exists to prevent: a root resolved one
        // level off makes every row "missing" and reads as a deleted test rather than a broken path.
        assertThat(repositoryRoot.resolve("apps/api"))
                .as("repository root derived from nullnull.ai.manifest.path (%s)", repositoryRoot)
                .isDirectory();
    }

    @Test
    void everyGradleRowNamesAFileThatExists() {
        List<String> declared = new ArrayList<>();
        for (JsonNode entry : manifest.get("implementedTestIds")) {
            String suite = entry.get("suite").asString();
            if (!suite.startsWith("gradle:")) {
                continue;
            }
            JsonNode path = entry.get("path");
            List<String> paths = new ArrayList<>();
            if (path.isArray()) {
                path.forEach(node -> paths.add(node.asString()));
            } else {
                paths.add(path.asString());
            }
            assertThat(paths).as("%s declares no path", entry.get("id").asString()).isNotEmpty();
            for (String relative : paths) {
                assertThat(repositoryRoot.resolve(relative))
                        .as("%s names a missing %s", entry.get("id").asString(), relative)
                        .isRegularFile();
            }
            declared.addAll(paths);
        }
        // An empty loop would pass while the Python side has already handed these rows away, so the
        // floor is pinned here the same way the implemented registry is pinned in test_manifest.py.
        assertThat(declared).as("gradle:* rows in the manifest").isNotEmpty();
    }
}
