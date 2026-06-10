package dev.vox.lss.networking.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Regression for a server-tick crash found by the dimension-trip soak: all-air columns
 * (the End void) serialize to NULL section bytes, and the filter hashed them unguarded.
 */
class DirtyContentFilterTest {

    @Test
    void seedToleratesAllAirNullBytes() {
        var filter = new DirtyContentFilter();
        assertDoesNotThrow(() -> {
            filter.seed("minecraft:the_end", 10, 0, null);
            filter.seed("minecraft:the_end", 10, 0, null);
            filter.seed("minecraft:the_end", 10, 0, new byte[0]);
            filter.seed("minecraft:the_end", 11, 0, new byte[]{1, 2, 3});
        });
    }
}
