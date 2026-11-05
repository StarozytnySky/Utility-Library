package org.broken.arrow.library.menu.shared;

import org.broken.arrow.library.menu.utility.StableFillItems;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Value-level regression coverage for stable sparse shared storage. */
final class StableFillItemsTest {
    /** Removing an interior item must never relocate any later item. */
    @Test void deletingAnInteriorSlotDoesNotCompactItems() {
        StableFillItems<String> storage = new StableFillItems<>(new ArrayList<>(Arrays.asList("A", "B", "C")));
        storage.clearAt(1);
        assertEquals(Arrays.asList("A", "C"), storage.getFillItems().stream().filter(java.util.Objects::nonNull).collect(Collectors.toList()));
        assertNull(storage.getFillItem(1));
        assertEquals("C", storage.getFillItem(2));
        assertEquals(3, storage.size());
    }

    /** The last occupied slot determines page span even if most preceding slots are empty. */
    @Test void sparseHighIndexSurvivesUnrelatedDeletes() {
        StableFillItems<String> storage = new StableFillItems<>(null);
        storage.setAt(102, "diamond");
        storage.setAt(20, "stone");
        storage.clearAt(20);
        assertEquals("diamond", storage.getFillItem(102));
        assertEquals(103, storage.size());
        assertEquals(0, storage.firstEmptyIndex());
    }

    /** Writes and reads must not expose the mutable backing list. */
    @Test void callersCannotMutateSnapshotsOrInitialList() {
        List<String> source = new ArrayList<>(Collections.singletonList("A"));
        StableFillItems<String> storage = new StableFillItems<>(source);
        source.set(0, "B");
        assertEquals("A", storage.getFillItem(0));
        assertThrows(UnsupportedOperationException.class, () -> storage.getFillItems().set(0, "X"));
    }

    /** Clearing a trailing slot trims only the empty tail. */
    @Test void onlyTrailingHolesAreTrimmed() {
        StableFillItems<String> storage = new StableFillItems<>(null);
        storage.setAt(3, "last");
        storage.setAt(1, "middle");
        storage.clearAt(3);
        assertEquals(2, storage.size());
        assertNull(storage.getFillItem(0));
        assertEquals("middle", storage.getFillItem(1));
    }

    /** Reject invalid indices rather than silently succeeding on an invalid mutation. */
    @Test void negativeIndicesAreRejected() {
        StableFillItems<String> storage = new StableFillItems<>(null);
        assertThrows(IndexOutOfBoundsException.class, () -> storage.setAt(-1, "invalid"));
    }
}
