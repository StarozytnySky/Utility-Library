package org.broken.arrow.library.database.session;

/** Database work that may raise checked exceptions without swallowing the failure. */
@FunctionalInterface
public interface DatabaseWork<S, T> {
    /** Runs within a borrowed session; do not retain the session after this method returns. */
    T execute(S session) throws Exception;
}
