package io.nullnull.shared.cursor;

import io.nullnull.shared.problem.ProblemCode;

/** A cursor the caller may not use. The two published outcomes are kept distinct on purpose. */
public class CursorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProblemCode problem;

    public CursorException(ProblemCode problem) {
        super(problem.name());
        if (problem != ProblemCode.CURSOR_INVALID && problem != ProblemCode.CURSOR_EXPIRED) {
            throw new IllegalArgumentException("cursor problems are CURSOR_INVALID or CURSOR_EXPIRED");
        }
        this.problem = problem;
    }

    public ProblemCode problem() {
        return problem;
    }
}
