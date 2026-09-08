package io.nullnull.operations.application;

/**
 * An enqueue that cannot be honoured: no handler is registered for the type, the request asks for more
 * attempts than {@code nullnull.jobs.max-attempts} allows, or the deduplication key already belongs to
 * a job of another type. All three are programming errors in the calling slice, not user input.
 *
 * <p>A dedicated type rather than {@link IllegalArgumentException} for a concrete reason: the store is
 * a {@code @Repository}, so Spring's persistence exception translation rewrites {@code IllegalArgument}
 * and {@code IllegalState} into {@code InvalidDataAccessApiUsageException}. A caller would then have to
 * catch a data-access exception to recognise a validation failure, and the message would look like a
 * database problem. This one passes through the translator unchanged.
 */
public class JobEnqueueException extends RuntimeException {

    public JobEnqueueException(String message) {
        super(message);
    }
}
