package org.broken.arrow.library.database.session;

/** Database work that may raise checked exceptions without swallowing the failure. */
@FunctionalInterface
public interface DatabaseWork<S, T> {
    /**
     * Runs within a borrowed session; do not retain the session after this method returns.
     *
     * @param session active database session sharing the current transaction
     * @return value produced by the database operation
     */
    T execute(S session) throws Exception;
}
