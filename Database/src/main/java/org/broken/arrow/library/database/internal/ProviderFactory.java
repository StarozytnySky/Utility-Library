package org.broken.arrow.library.database.internal;

import java.util.Objects;

import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.jdbc.JdbcProvider;
import org.broken.arrow.library.database.mongo.MongoProvider;

import org.broken.arrow.library.database.spi.DatabaseProvider;

/** Resolves database-specific implementation once during bootstrap, never inside repositories. */
public final class ProviderFactory {
    private ProviderFactory() { }

    /**
     * Creates and validates exactly one owned backend per database client.
     *
     * @param configuration validated database configuration used to open the client
     * @return new database client with a validated provider and worker pool
     */
    public static DatabaseProvider open(DatabaseConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return switch (configuration.kind()) {
            case MONGODB -> new MongoProvider(configuration);
            case SQLITE, H2, MYSQL, MARIADB, POSTGRESQL -> new JdbcProvider(configuration);
        };
    }
}
