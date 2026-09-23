package io.nullnull.social.infrastructure.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.nullnull.OperationsContext;
import io.nullnull.social.application.PostLockTimeoutException;
import io.nullnull.social.application.PostWithdrawalService.Withdrawal;
import io.nullnull.social.application.PostWithdrawalService.WithdrawalResult;
import io.nullnull.social.infrastructure.storage.PublishedCoverRemoval;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
                    + " cover=(DELETED|ALREADY_ABSENT|NOT_USER_UPLOAD) versions=[0-9]{1,9}"
                    + "|post_withdraw_failed reason=COVER_CLEANUP_FAILED cover_cleanup=pending"
                    + "|post_withdraw_failed reason=[A-Za-z_]{1,80})$");

    private static final String POST = "0192f3a4-5b6c-7d8e-9f01-23456789abcd";
    private static final String COVER = "https://nullnull.test/covers/user/" + POST + ".jpg";

    @Test
    void cleanupRunsOnlyAfterAnEligibleWithdrawalAndReportsTheExactOutcome() {
        List<String> cleaned = new ArrayList<>();
        var deleted = PostWithdrawMain.finish(
                new WithdrawalResult(Withdrawal.WITHDRAWN, Optional.of(COVER)), url -> {
                    cleaned.add(url);
                    return new PublishedCoverRemoval.CoverCleanup(
                            PublishedCoverRemoval.Status.DELETED, 2);
                });
        assertThat(cleaned).containsExactly(COVER);
        assertThat(PostWithdrawMain.report(UUID.fromString(POST), deleted))
                .isEqualTo("post_withdrawn post=" + POST
                        + " outcome=WITHDRAWN cover=DELETED versions=2");

        var absent = PostWithdrawMain.finish(
                new WithdrawalResult(Withdrawal.ALREADY_HIDDEN, Optional.of(COVER)), url ->
                        new PublishedCoverRemoval.CoverCleanup(
                                PublishedCoverRemoval.Status.ALREADY_ABSENT, 0));
        assertThat(PostWithdrawMain.report(UUID.fromString(POST), absent))
                .endsWith("outcome=ALREADY_HIDDEN cover=ALREADY_ABSENT versions=0");

        var curated = PostWithdrawMain.finish(
                new WithdrawalResult(Withdrawal.WITHDRAWN, Optional.empty()), url -> {
                    throw new AssertionError("curated cover must not reach S3");
                });
        assertThat(PostWithdrawMain.report(UUID.fromString(POST), curated))
                .endsWith("outcome=WITHDRAWN cover=NOT_USER_UPLOAD versions=0");
        // A USER_UPLOAD row with an unfamiliar URL is not the same as a curated post with no
        // user-upload cover. Reporting NOT_USER_UPLOAD here would hide an incomplete cleanup.
        assertThatThrownBy(() -> PostWithdrawMain.finish(
                new WithdrawalResult(Withdrawal.WITHDRAWN,
                        Optional.of("https://nullnull.test/covers/first-party.jpg")), url ->
                        new PublishedCoverRemoval.CoverCleanup(
                                PublishedCoverRemoval.Status.NOT_USER_UPLOAD, 0)))
                .isInstanceOf(PublishedCoverRemoval.InvalidCoverUrl.class);
        for (Withdrawal refused : List.of(Withdrawal.NOT_FOUND, Withdrawal.NOT_PUBLISHED)) {
            assertThatThrownBy(() -> PostWithdrawMain.finish(
                    new WithdrawalResult(refused, Optional.of(COVER)), url -> {
                        throw new AssertionError("refused post must not reach S3");
                    })).isInstanceOf(PostWithdrawMain.Refused.class);
        }
    }

    @Test
    void failedCoverCleanupIsAVisibleIncompleteWithdrawalWithoutLeakingItsUrl() {
        var failure = new PublishedCoverRemoval.CleanupFailed();
        assertThat(PostWithdrawMain.failureLine(failure))
                .isEqualTo("post_withdraw_failed reason=COVER_CLEANUP_FAILED cover_cleanup=pending");
        assertThat(PostWithdrawMain.failureLine(failure)).doesNotContain(COVER);
    }

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

        assertThat(PostWithdrawMain.report(post, completed(Withdrawal.WITHDRAWN)))
                .isEqualTo("post_withdrawn post=" + POST
                        + " outcome=WITHDRAWN cover=NOT_USER_UPLOAD versions=0");
        assertThat(PostWithdrawMain.report(post, completed(Withdrawal.ALREADY_HIDDEN)))
                .isEqualTo("post_withdrawn post=" + POST
                        + " outcome=ALREADY_HIDDEN cover=NOT_USER_UPLOAD versions=0");
        // A typo'd id and a draft change nothing the owner asked for, so they fail rather than print
        // a line the operator could count.
        for (Withdrawal refused : List.of(Withdrawal.NOT_FOUND, Withdrawal.NOT_PUBLISHED)) {
            assertThatThrownBy(() -> completed(refused))
                    .satisfies(failure -> assertThat(PostWithdrawMain.failureLine(failure))
                            .isEqualTo("post_withdraw_failed reason=" + refused.name()));
        }
    }

    @Test
    @DisplayName("an approved run withdraws exactly the named post and prints what the withdrawal did")
    void anApprovedRunWithdrawsTheNamedPost() {
        for (Withdrawal done : List.of(Withdrawal.WITHDRAWN, Withdrawal.ALREADY_HIDDEN)) {
            List<UUID> asked = new ArrayList<>();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            PostWithdrawMain.run(approved(), new PrintStream(bytes, true, StandardCharsets.UTF_8), id -> {
                asked.add(id);
                return completed(done);
            });

            assertThat(asked).as("the service is asked once, about this post").containsExactly(UUID.fromString(POST));
            assertThat(bytes.toString(StandardCharsets.UTF_8).lines().toList())
                    .containsExactly("post_withdrawn post=" + POST + " outcome=" + done.name()
                            + " cover=NOT_USER_UPLOAD versions=0");
        }
    }

    @Test
    @DisplayName("a withdrawal that refuses or fails ends the run with its reason and a non-zero exit")
    void aRefusalOrFailureIsNotASuccess() {
        Map<Withdrawal, String> refusals = Map.of(Withdrawal.NOT_FOUND, "NOT_FOUND",
                Withdrawal.NOT_PUBLISHED, "NOT_PUBLISHED");
        refusals.forEach((outcome, reason) -> assertThat(run(approved(), id -> completed(outcome)))
                .as("%s", outcome).containsExactly("post_withdraw_failed reason=" + reason));
        // A row lock the withdrawal could not get in time, or anything else: the exception's name,
        // never its message.
        assertThat(run(approved(), id -> {
            throw new PostLockTimeoutException("Timed out waiting for the post's row lock.", null);
        })).containsExactly("post_withdraw_failed reason=PostLockTimeoutException");
        assertThat(run(approved(), id -> {
            throw new IllegalStateException("jdbc:postgresql://secret-host/db refused");
        })).containsExactly("post_withdraw_failed reason=IllegalStateException");
        assertThat(run(approved(), id -> PostWithdrawMain.finish(
                new WithdrawalResult(Withdrawal.WITHDRAWN, Optional.of(COVER)), url -> {
                    throw new PublishedCoverRemoval.CleanupFailed();
                }))).containsExactly(
                        "post_withdraw_failed reason=COVER_CLEANUP_FAILED cover_cleanup=pending");
    }

    @Test
    @DisplayName("the withdrawal opens the application as a writing tool, which is what makes staging name its database")
    void theApplicationIsOpenedForWriting() {
        // Read from the compiled class rather than asserted by running it: starting the application
        // needs a database this source set does not have. A READ context would skip
        // OperationsContext's requirement that NULLNULL_OPERATIONS_TARGET name the database.
        List<String> accessed = new ClassFileImporter().importClass(PostWithdrawMain.class)
                .getFieldAccessesFromSelf().stream()
                .filter(access -> access.getTargetOwner().isEquivalentTo(OperationsContext.Access.class))
                .map(JavaFieldAccess::getName)
                .collect(Collectors.toList());

        assertThat(accessed).containsExactly("WRITE");
    }

    @Test
    @DisplayName("every line it prints passes the staging operator's log allowlist")
    void everyLineItPrintsIsEchoed() {
        UUID post = UUID.fromString(POST);
        List<String> lines = List.of(
                PostWithdrawMain.report(post, completed(Withdrawal.WITHDRAWN)),
                PostWithdrawMain.report(post, completed(Withdrawal.ALREADY_HIDDEN)),
                PostWithdrawMain.failureLine(new PostWithdrawMain.Refused("NOT_FOUND", "x")),
                PostWithdrawMain.failureLine(new PostWithdrawMain.Refused("NOT_PUBLISHED", "x")),
                PostWithdrawMain.failureLine(new PostWithdrawMain.Refused("APPROVAL_NOT_SET", "x")),
                PostWithdrawMain.failureLine(new PostWithdrawMain.Refused("POST_ID_INVALID", "x")),
                // Anything else carries the exception's name, never its message.
                PostWithdrawMain.failureLine(new IllegalStateException("jdbc:postgresql://secret-host/db")),
                PostWithdrawMain.failureLine(new PublishedCoverRemoval.CleanupFailed()));

        assertThat(lines).allSatisfy(line -> assertThat(OPS_LOG_LINE.matcher(line).matches())
                .as(line).isTrue());
        assertThat(lines.getLast())
                .isEqualTo("post_withdraw_failed reason=COVER_CLEANUP_FAILED cover_cleanup=pending");
    }

    /** Runs a command that must be refused before any withdrawal, and returns what it printed. */
    private static List<String> refusal(Map<String, String> environment) {
        return run(environment, id -> {
            throw new AssertionError("a refused run must not reach the withdrawal");
        });
    }

    /** Runs the command, asserting that it failed, and returns what it printed. */
    private static List<String> run(Map<String, String> environment,
            Function<UUID, PostWithdrawMain.Completed> withdraw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> PostWithdrawMain.run(environment, out, withdraw))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(AssertionError.class);
        return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static Map<String, String> approved() {
        return Map.of(PostWithdrawMain.APPROVAL, "true", PostWithdrawMain.POST_ID, POST);
    }

    private static PostWithdrawMain.Completed completed(Withdrawal outcome) {
        return PostWithdrawMain.finish(new WithdrawalResult(outcome, Optional.empty()), url -> {
            throw new AssertionError("no user cover should call S3");
        });
    }
}
