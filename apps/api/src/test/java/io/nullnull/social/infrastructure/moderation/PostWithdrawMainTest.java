package io.nullnull.social.infrastructure.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.social.application.PostWithdrawalService.Withdrawal;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an operator touches of the {@code withdraw-post} task: the approval, the id, and the one line
 * it prints. The writer itself is PostWithdrawalIT's.
 *
 * <p>The refusals are driven through {@link PostWithdrawMain#run}, not only through the helpers,
 * because the property is about order: they must happen before a database is opened. This test
 * source set has no database, so a refusal that came after the context started would surface as a
 * different failure, not as the line asserted here.
 */
@DisplayName("BA-082 withdraw-post entry point")
class PostWithdrawMainTest {

    /**
     * The withdraw-post half of scripts/aws/staging_operator.py OPS_LOG_LINE (mirrored, as
     * CuratedPostImportMainTest mirrors the curated-posts half). The Python test feeds the lines this
     * class prints, verbatim, to the real pattern.
     */
    private static final Pattern OPS_LOG_LINE = Pattern.compile(
            "^(post_withdrawn post=[0-9a-f-]{36} outcome=(WITHDRAWN|ALREADY_HIDDEN)"
                    + "|post_withdraw_failed reason=[A-Za-z_]{1,80})$");

    private static final String POST = "0192f3a4-5b6c-7d8e-9f01-23456789abcd";

    @Test
    @DisplayName("without the stated approval nothing is opened and the refusal is printed")
    void theApprovalIsRequired() {
        for (String value : new String[] {null, "", "false", "TRUE", "yes"}) {
            Map<String, String> environment = new HashMap<>();
            environment.put(PostWithdrawMain.POST_ID, POST);
            if (value != null) {
                environment.put(PostWithdrawMain.APPROVAL, value);
            }

            assertThat(refusal(environment)).as("approval=%s", value)
                    .containsExactly("post_withdraw_failed reason=APPROVAL_NOT_SET");
        }
    }

    @Test
    @DisplayName("a post id that is not a lower-case canonical UUID is refused before anything is opened")
    void thePostIdIsCanonical() {
        for (String id : new String[] {null, "", "not-a-uuid", POST.toUpperCase(), POST + "0", " " + POST,
                "0192f3a45b6c7d8e9f0123456789abcd"}) {
            Map<String, String> environment = new HashMap<>();
            environment.put(PostWithdrawMain.APPROVAL, "true");
            if (id != null) {
                environment.put(PostWithdrawMain.POST_ID, id);
            }

            assertThat(refusal(environment)).as("id=%s", id)
                    .containsExactly("post_withdraw_failed reason=POST_ID_INVALID");
        }
        assertThat(PostWithdrawMain.request(Map.of(PostWithdrawMain.APPROVAL, "true", PostWithdrawMain.POST_ID, POST)))
                .isEqualTo(UUID.fromString(POST));
    }

    @Test
    @DisplayName("only a post left withdrawn is reported as withdrawn")
    void onlyAWithdrawnPostSucceeds() {
        UUID post = UUID.fromString(POST);

        assertThat(PostWithdrawMain.report(post, Withdrawal.WITHDRAWN))
                .isEqualTo("post_withdrawn post=" + POST + " outcome=WITHDRAWN");
        assertThat(PostWithdrawMain.report(post, Withdrawal.ALREADY_HIDDEN))
                .isEqualTo("post_withdrawn post=" + POST + " outcome=ALREADY_HIDDEN");
        // A typo'd id and a draft change nothing the owner asked for, so they fail rather than print
        // a line the operator could count.
        for (Withdrawal refused : List.of(Withdrawal.NOT_FOUND, Withdrawal.NOT_PUBLISHED)) {
            assertThatThrownBy(() -> PostWithdrawMain.report(post, refused))
                    .satisfies(failure -> assertThat(PostWithdrawMain.failureLine(failure))
                            .isEqualTo("post_withdraw_failed reason=" + refused.name()));
        }
    }

    @Test
    @DisplayName("every line it prints passes the staging operator's log allowlist")
    void everyLineItPrintsIsEchoed() {
        UUID post = UUID.fromString(POST);
        List<String> lines = List.of(
                PostWithdrawMain.report(post, Withdrawal.WITHDRAWN),
                PostWithdrawMain.report(post, Withdrawal.ALREADY_HIDDEN),
                PostWithdrawMain.failureLine(new PostWithdrawMain.Refused("NOT_FOUND", "x")),
                PostWithdrawMain.failureLine(new PostWithdrawMain.Refused("NOT_PUBLISHED", "x")),
                PostWithdrawMain.failureLine(new PostWithdrawMain.Refused("APPROVAL_NOT_SET", "x")),
                PostWithdrawMain.failureLine(new PostWithdrawMain.Refused("POST_ID_INVALID", "x")),
                // Anything else carries the exception's name, never its message.
                PostWithdrawMain.failureLine(new IllegalStateException("jdbc:postgresql://secret-host/db")));

        assertThat(lines).allSatisfy(line -> assertThat(OPS_LOG_LINE.matcher(line).matches())
                .as(line).isTrue());
        assertThat(lines.getLast()).isEqualTo("post_withdraw_failed reason=IllegalStateException");
    }

    /** Runs the command and returns what it printed, asserting that it failed. */
    private static List<String> refusal(Map<String, String> environment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> PostWithdrawMain.run(environment, out))
                .isInstanceOf(PostWithdrawMain.Refused.class);
        return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }
}
