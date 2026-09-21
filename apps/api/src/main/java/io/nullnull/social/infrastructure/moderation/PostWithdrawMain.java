package io.nullnull.social.infrastructure.moderation;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.social.application.PostWithdrawalService;
import io.nullnull.social.application.PostWithdrawalService.Withdrawal;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The {@code withdraw-post} operator task (BA-082-T16): takes one published post back, as a decision
 * the owner approved.
 *
 * <p>Before this, nothing in the product could take a post back. A-058 publishes an uploaded post as
 * soon as it passes the automatic checks, with no person in between, and those checks look at what
 * a file IS, never at what it shows - so the only answer to a post that must come down was switching
 * the whole authoring capability off. This is the per-post answer. It is a task rather than an
 * endpoint because there is no moderator role to authorise one (#56).
 *
 * <p>The approval is checked here as well as by the staging operator, the way the KTO mains check
 * theirs: {@code NULLNULL_POST_WITHDRAW_APPROVED=true} must be in this process's environment, and the
 * post id must be a canonical UUID, before any database is opened. The operator records who approved
 * ({@code --owner-approval}); this main only refuses to run without the approval being stated.
 *
 * <p>It prints exactly one line. {@code post_withdrawn post=<id> outcome=WITHDRAWN} or
 * {@code outcome=ALREADY_HIDDEN} ends with exit 0 - a rerun of an approved withdrawal finds the post
 * already where it should be. Anything else prints {@code post_withdraw_failed reason=<CODE>} and
 * exits non-zero, including an id that names no post (a typo must not read as a withdrawal) and a
 * draft (never shown, so nothing to take back).
 *
 * <p>The cover image is not deleted - see {@link PostWithdrawalService}. The operator says so on
 * every success.
 */
public final class PostWithdrawMain {

    static final String APPROVAL = "NULLNULL_POST_WITHDRAW_APPROVED";
    static final String POST_ID = "NULLNULL_WITHDRAW_POST_ID";

    /** Lower-case canonical form only: the operator passes it verbatim and echoes it back in its check. */
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private PostWithdrawMain() {
    }

    public static void main(String[] args) {
        run(System.getenv(), System.out);
    }

    /**
     * The whole command. A failure prints one line the staging operator's log allowlist passes and is
     * then rethrown, so the process exits non-zero: a refusal that returned normally would read as a
     * withdrawal to anything that looks only at the exit code.
     */
    static void run(Map<String, String> environment, PrintStream out) {
        try {
            UUID postId = request(environment);
            Withdrawal outcome;
            try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
                outcome = context.getBean(PostWithdrawalService.class).withdraw(postId);
            }
            out.println(report(postId, outcome));
        } catch (RuntimeException failure) {
            out.println(failureLine(failure));
            throw failure;
        }
    }

    /** The post to withdraw, read only once the approval is stated. */
    static UUID request(Map<String, String> environment) {
        if (!"true".equals(environment.get(APPROVAL))) {
            throw new Refused("APPROVAL_NOT_SET", APPROVAL + " must be true to withdraw a post");
        }
        String id = environment.get(POST_ID);
        if (id == null || !CANONICAL_UUID.matcher(id).matches()) {
            throw new Refused("POST_ID_INVALID", POST_ID + " must be a lower-case canonical UUID");
        }
        return UUID.fromString(id);
    }

    /** The success line, or the refusal a withdrawal that changed nothing it was asked to is. */
    static String report(UUID postId, Withdrawal outcome) {
        return switch (outcome) {
            case WITHDRAWN, ALREADY_HIDDEN -> "post_withdrawn post=" + postId + " outcome=" + outcome.name();
            case NOT_FOUND, NOT_PUBLISHED -> throw new Refused(outcome.name(), "the post was not withdrawn");
        };
    }

    static String failureLine(Throwable failure) {
        String reason = failure instanceof Refused refused ? refused.reason() : OperationsPlan.failureReason(failure);
        return "post_withdraw_failed reason=" + reason;
    }

    /** A refusal this main decides, carrying the code it prints instead of the exception's name. */
    static final class Refused extends IllegalStateException {

        private final String reason;

        Refused(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
