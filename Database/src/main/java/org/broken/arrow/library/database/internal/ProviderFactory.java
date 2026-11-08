package org.broken.arrow.library.database.internal;

import org.broken.arrow.library.database.api.DatabaseConfiguration;
import org.broken.arrow.library.database.api.DatabaseProvider;
import org.broken.arrow.library.database.jdbc.JdbcProvider;
import org.broken.arrow.library.database.mongo.MongoProvider;

import java.util.Objects;

/** Resolves database-specific implementation once during bootstrap, never inside repositories. */
public final class ProviderFactory {
    private ProviderFactory() { }

    /** Creates and validates exactly one owned backend per database client. */
    public static DatabaseProvider open(DatabaseConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return switch (configuration.kind()) {
            case MONGODB -> new MongoProvider(configuration);
            case SQLITE, H2, MYSQL, MARIADB, POSTGRESQL -> new JdbcProvider(configuration);
        };
    }
}
