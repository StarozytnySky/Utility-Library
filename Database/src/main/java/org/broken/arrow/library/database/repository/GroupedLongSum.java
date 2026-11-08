package org.broken.arrow.library.database.repository;

import java.util.Objects;

/** Group key and exact sum returned from a bounded database-side aggregation. */
public record GroupedLongSum(Object group, long sum) {
    /** Requires a non-null grouping value so results can be mapped consistently. */
    public GroupedLongSum {
        Objects.requireNonNull(group, "group");
    }
}
