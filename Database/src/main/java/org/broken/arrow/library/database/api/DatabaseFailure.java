package org.broken.arrow.library.database.api;

/** An explicit storage failure; callers must never treat it as a missing record. */
public class DatabaseFailure extends RuntimeException {
    /** Categorizes failures without exposing driver-dependent message parsing. */
    public enum Reason {
        CONNECTION, CONFLICT, UNSUPPORTED, OVERLOADED, OPERATION, COMMIT_OUTCOME_UNKNOWN, CLOSED
    }

    private final Reason reason;

    /**
     * Creates an actionable failure without embedding connection credentials.
     *
     * @param reason machine-readable category of database failure
     * @param message safe error message without sensitive connection values
     */
    public DatabaseFailure(Reason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates a failure retaining the original cause.
     *
     * @param reason machine-readable category of database failure
     * @param message safe error message without sensitive connection values
     * @param cause original failure retained for error reporting
     */
    public DatabaseFailure(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the normalized failure category.
     *
     * @return classified reason for the failure
     */
    public Reason reason() { return reason; }
}
