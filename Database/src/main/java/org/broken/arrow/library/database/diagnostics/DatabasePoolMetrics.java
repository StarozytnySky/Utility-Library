package org.broken.arrow.library.database.diagnostics;

/**
 * Read-only point-in-time HikariCP connection pool observation.
 *
 * <p>Values may change while the snapshot is assembled and are not a transactional
 * view of the pool. Absent for database providers that do not use HikariCP.</p>
 *
 * @param active connections currently borrowed by callers
 * @param idle available pooled connections
 * @param total total physical connections owned by the pool
 * @param waiting threads waiting to acquire a pooled connection
 * @param maximum configured maximum physical connections
 */
public record DatabasePoolMetrics(int active, int idle, int total,
                                  int waiting, int maximum) { }
