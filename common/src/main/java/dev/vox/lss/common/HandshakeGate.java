package dev.vox.lss.common;

/**
 * Pure decision ladder for the C2S handshake, shared by the Fabric
 * ({@code LSSServerNetworking}) and Paper ({@code LSSPaperPlugin}) receivers so
 * the reply/registration policy cannot drift between platforms.
 *
 * <p>Ladder order is load-bearing: the version check must run first because a
 * mismatched client must receive NO reply at all, and the capability check
 * outranks the enabled check so a consumer-less client is classified (and
 * logged) the same way whether or not LSS is enabled.
 */
public final class HandshakeGate {
    private HandshakeGate() {}

    /** Why the gate settled on its decision; call sites key their log lines off this. */
    public enum Outcome {
        /**
         * Protocol skew — must not reply: a mismatched client's SessionConfig codec
         * has a different field layout on the same channel id, so any frame we send
         * decodes as a DecoderException and kicks the player. Sending nothing leaves
         * its LSS disabled (no LodRequestManager is created).
         */
        VERSION_MISMATCH,
        /**
         * No LOD consumer on the client — reply with the session config, but never
         * register: registering would only create a zombie state that ignores every
         * request.
         */
        NO_CONSUMER,
        /** LSS off (config disabled or service absent) — advertise disabled, no registration. */
        DISABLED,
        /** Compatible, capable, enabled — reply and register. */
        REGISTER
    }

    /**
     * @param outcome          classification driving call-site logging
     * @param effectiveEnabled the enabled flag to advertise in the session config
     */
    public record Decision(Outcome outcome, boolean effectiveEnabled) {
        /** Whether any reply may be sent at all (false only on version skew). */
        public boolean sendSessionConfig() {
            return outcome != Outcome.VERSION_MISMATCH;
        }

        /** Whether to register the player for LOD request processing. */
        public boolean registerPlayer() {
            return outcome == Outcome.REGISTER;
        }
    }

    /**
     * Evaluates the handshake against {@link LSSConstants#PROTOCOL_VERSION}.
     *
     * @param clientProtocolVersion protocol version the client handshook with
     * @param clientCapabilities    client capabilities bitmask
     * @param configEnabled         server config {@code enabled} flag
     * @param servicePresent        whether the request processing service is running
     */
    public static Decision evaluate(int clientProtocolVersion, int clientCapabilities,
                                    boolean configEnabled, boolean servicePresent) {
        if (clientProtocolVersion != LSSConstants.PROTOCOL_VERSION) {
            return new Decision(Outcome.VERSION_MISMATCH, false);
        }
        boolean effectiveEnabled = configEnabled && servicePresent;
        if ((clientCapabilities & LSSConstants.CAPABILITY_VOXEL_COLUMNS) == 0) {
            return new Decision(Outcome.NO_CONSUMER, effectiveEnabled);
        }
        if (!effectiveEnabled) {
            return new Decision(Outcome.DISABLED, false);
        }
        return new Decision(Outcome.REGISTER, true);
    }
}
