package org.broken.arrow.library.database.schema;

import java.util.regex.Pattern;

/** Validates identifiers, which cannot be represented by prepared-statement value parameters. */
public final class Identifiers {
    private static final Pattern VALID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Identifiers() { }

    /** Returns a validated table, collection or field name, or throws. */
    public static String require(String name) {
        if (name == null || !VALID.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid database identifier");
        }
        return name;
    }
}
