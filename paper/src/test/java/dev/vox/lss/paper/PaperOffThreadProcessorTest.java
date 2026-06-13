package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import io.netty.handler.codec.EncoderException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pins {@link PaperOffThreadProcessor}'s known >256-char-dimension limitation AS IS:
 * {@code buildAndEnqueueColumnPayload} lets the writeUtf cap's {@link EncoderException}
 * propagate, which in the live pipeline aborts the WHOLE processing cycle (caught by
 * {@code OffThreadProcessor.processingLoop}'s Throwable guard and retried on the next
 * take) instead of dropping just that column with a warn + up-to-date answer. A source
 * guard is deliberately deferred — this test exists so the cycle-killing behavior cannot
 * change silently and the desired drop+warn+up_to_date stays on the books.
 */
class PaperOffThreadProcessorTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void over256CharDimensionThrowsOutOfBuildAndEnqueueAbortingTheCycle() {
        // Thread created but never start()ed (PaperPayloadEdgeTest harness pattern).
        var proc = new PaperOffThreadProcessor(new ConcurrentHashMap<>(), null, false, null, 1);
        var state = mock(PaperPlayerRequestState.class);
        String dim = "lss:" + "a".repeat(LSSConstants.MAX_DIMENSION_STRING_LENGTH - 3); // 257 chars
        byte[] sections = {1, 2, 3};

        assertThrows(EncoderException.class,
                () -> proc.buildAndEnqueueColumnPayload(state, 1, 2, dim, 42L, 7L, sections, 9),
                "current behavior: the encode throws on the processing thread — the method "
                        + "neither catches nor returns false, so the cycle aborts and retries");
        verify(state, never()).addReadyPayload(any());

        // The processor object survives the aborted cycle: the next cycle's columns enqueue.
        assertTrue(proc.buildAndEnqueueColumnPayload(
                state, 1, 2, "minecraft:overworld", 42L, 8L, sections, 9));
        verify(state).addReadyPayload(any());
    }
}
