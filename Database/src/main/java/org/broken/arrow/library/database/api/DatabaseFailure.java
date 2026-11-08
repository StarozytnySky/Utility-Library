package org.broken.arrow.library.database.api;

/** An explicit storage failure; callers must never treat it as a missing record. */
public class DatabaseFailure extends RuntimeException {
    /** Categorizes failures without exposing driver-dependent message parsing. */
    public enum Reason {
        CONNECTION, CONFLICT, UNSUPPORTED, OVERLOADED, OPERATION, COMMIT_OUTCOME_UNKNOWN, CLOSED
    }

    private final Reason reason;

    /** Creates an actionable failure without embedding connection credentials. */
    public DatabaseFailure(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /** Creates a failure retaining the original cause. */
    public DatabaseFailure(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /** Returns the normalized failure category. */
    public Reason reason() { return reason; }
}
