package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A plan reaches an operations tool from a file or inline, and is imported only as the exact approved bytes. */
class OperationsPlanTest {

    private static final OperationsPlan.Source SOURCE =
            new OperationsPlan.Source("PLAN_PATH", "PLAN_INLINE", "PLAN_SHA256", "test plan");
    private static final byte[] PLAN = "{\"places\":[{\"name\":\"경복궁\"}]}".getBytes(StandardCharsets.UTF_8);

    @TempDir Path directory;

    @Test
    void aFileIsReadAsItsExactBytes() throws IOException {
        Path file = directory.resolve("plan.json");
        Files.write(file, PLAN);

        OperationsPlan.Text text = OperationsPlan.read(Map.of("PLAN_PATH", file.toString()), null, SOURCE);

        assertThat(text.json()).isEqualTo(new String(PLAN, StandardCharsets.UTF_8));
        assertThat(text.sha256()).isEqualTo(OperationsPlan.sha256(PLAN));
        assertThat(text.bytes()).isEqualTo(PLAN.length);
        assertThat(text.origin()).isEqualTo("file");
        // An argument outranks the variable, as the Gradle task and the documented command use it.
        assertThat(OperationsPlan.read(Map.of("PLAN_PATH", "/nowhere"), new String[] {file.toString()}, SOURCE).sha256())
                .isEqualTo(OperationsPlan.sha256(PLAN));
    }

    @Test
    void inlineBytesAreImportedOnlyWhenTheyAreTheApprovedOnes() throws IOException {
        String sha = OperationsPlan.sha256(PLAN);

        OperationsPlan.Text text = OperationsPlan.read(Map.of("PLAN_INLINE", gzipBase64(PLAN), "PLAN_SHA256", sha), null, SOURCE);

        assertThat(text.json()).isEqualTo(new String(PLAN, StandardCharsets.UTF_8));
        assertThat(text.sha256()).isEqualTo(sha);
        assertThat(text.origin()).isEqualTo("inline");
        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_INLINE", gzipBase64(PLAN), "PLAN_SHA256", "0".repeat(64)), null, SOURCE))
                .withMessageStartingWith("the test plan is not the approved one: sha256 " + sha);
        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_INLINE", gzipBase64(PLAN)), null, SOURCE))
                .withMessage("PLAN_INLINE needs PLAN_SHA256");
    }

    @Test
    void aFileNamedWithAnApprovedShaMustBeThatFileToo() throws IOException {
        Path file = directory.resolve("plan.json");
        Files.write(file, PLAN);

        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_PATH", file.toString(), "PLAN_SHA256", "f".repeat(64)), null, SOURCE))
                .withMessageStartingWith("the test plan is not the approved one");
        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_PATH", file.toString(), "PLAN_SHA256", "ABC"), null, SOURCE))
                .withMessage("PLAN_SHA256 must be a lowercase hex sha256");
    }

    @Test
    void exactlyOneSourceIsAccepted() throws IOException {
        Path file = directory.resolve("plan.json");
        Files.write(file, PLAN);
        String sha = OperationsPlan.sha256(PLAN);

        // Both: an inline value set alongside a file is refused rather than one quietly winning.
        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_PATH", file.toString(), "PLAN_INLINE", gzipBase64(PLAN),
                        "PLAN_SHA256", sha), null, SOURCE))
                .withMessage("exactly one of PLAN_PATH or PLAN_INLINE must give the test plan");
        assertThatIllegalStateException().isThrownBy(() -> OperationsPlan.read(Map.of(), null, SOURCE))
                .withMessage("exactly one of PLAN_PATH or PLAN_INLINE must give the test plan");
        assertThatIllegalStateException().isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_PATH", " "), new String[] {" "}, SOURCE))
                .withMessage("exactly one of PLAN_PATH or PLAN_INLINE must give the test plan");
    }

    @Test
    void aDamagedInlineValueFailsInsteadOfDecodingToSomethingElse() throws IOException {
        String sha = OperationsPlan.sha256(PLAN);
        String encoded = gzipBase64(PLAN);
        // A line break inside: the MIME decoder would skip it and decode the plan; the strict one refuses.
        String broken = encoded.substring(0, 8) + "\n" + encoded.substring(8);

        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_INLINE", broken, "PLAN_SHA256", sha), null, SOURCE))
                .withMessage("PLAN_INLINE is not base64");
        String plain = Base64.getEncoder().encodeToString(PLAN);
        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_INLINE", plain, "PLAN_SHA256", sha), null, SOURCE))
                .withMessage("PLAN_INLINE is not gzip");
    }

    @Test
    void inlineBytesAreCappedWhileInflatingAndMustBeUtf8() throws IOException {
        byte[] huge = new byte[OperationsPlan.MAX_BYTES + 1];
        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_INLINE", gzipBase64(huge),
                        "PLAN_SHA256", OperationsPlan.sha256(huge)), null, SOURCE))
                .withMessage("the inline test plan inflates past " + OperationsPlan.MAX_BYTES + " bytes");
        byte[] notUtf8 = {'{', (byte) 0xC3, '(', '}'};
        assertThatIllegalStateException()
                .isThrownBy(() -> OperationsPlan.read(Map.of("PLAN_INLINE", gzipBase64(notUtf8),
                        "PLAN_SHA256", OperationsPlan.sha256(notUtf8)), null, SOURCE))
                .withMessage("the test plan is not UTF-8");
    }

    @Test
    void aFailureIsReportedByTheRefusalsCodeOrTheExceptionsName() {
        OperationsContext.Refused refused = new OperationsContext.Refused(
                OperationsContext.Refused.Code.OPERATIONS_TARGET_NOT_CONFIRMED, "a message with a path /tmp/x");

        assertThat(OperationsPlan.failureReason(refused)).isEqualTo("OPERATIONS_TARGET_NOT_CONFIRMED");
        assertThat(OperationsPlan.failureReason(new IllegalStateException("wrapped", refused)))
                .isEqualTo("OPERATIONS_TARGET_NOT_CONFIRMED");
        assertThat(OperationsPlan.failureReason(new IllegalArgumentException("no place /tmp/x"))).isEqualTo("IllegalArgumentException");
    }

    private static String gzipBase64(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
