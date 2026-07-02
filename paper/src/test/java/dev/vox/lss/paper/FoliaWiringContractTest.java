package dev.vox.lss.paper;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folia wiring pins, enforced at the constant-pool level: the legacy BukkitScheduler family
 * throws UnsupportedOperationException on Folia, so ANY reference to it in the classes that
 * run there is a Folia-fatal regression; and LSSPaperPlugin must route lifecycle ingress
 * through the mailbox (enqueue*), never the pump-only direct methods (on Folia its callers
 * run on region threads). A minimal constant-pool read resolves method references to
 * (owner, name) pairs, so a same-named method on another type (e.g.
 * {@code HandshakeGate.Decision#registerPlayer()}) cannot false-positive, and javadoc
 * {@code @link} mentions never reach the pool at all.
 */
class FoliaWiringContractTest {

    private record MethodRef(String owner, String name) {}

    /** Owner classes + method names of every Methodref/InterfaceMethodref in the class. */
    private static List<MethodRef> methodRefs(String binaryName) throws IOException {
        String res = "/" + binaryName.replace('.', '/') + ".class";
        try (InputStream raw = FoliaWiringContractTest.class.getResourceAsStream(res)) {
            if (raw == null) throw new IOException("class resource not found: " + res);
            var in = new DataInputStream(raw);
            if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file: " + res);
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            int[] classNameIdx = new int[cpCount];
            int[] natNameIdx = new int[cpCount];
            record Ref(int classIdx, int natIdx) {}
            var refs = new ArrayList<Ref>();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNameIdx[i] = in.readUnsignedShort();
                    case 9, 11 -> refs.add(new Ref(in.readUnsignedShort(), in.readUnsignedShort()));
                    case 10 -> refs.add(new Ref(in.readUnsignedShort(), in.readUnsignedShort()));
                    case 12 -> { natNameIdx[i] = in.readUnsignedShort(); in.readUnsignedShort(); }
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    case 3, 4, 17, 18 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; } // long/double take two pool slots
                    default -> throw new IOException("unexpected constant tag " + tag + " in " + res);
                }
            }
            var out = new ArrayList<MethodRef>();
            for (var r : refs) {
                String owner = utf8[classNameIdx[r.classIdx()]];
                String name = utf8[natNameIdx[r.natIdx()]];
                if (owner != null && name != null) out.add(new MethodRef(owner, name));
            }
            return out;
        }
    }

    @Test
    void noLegacySchedulerReferencesInFoliaCriticalClasses() throws IOException {
        for (String name : new String[] {
                "dev.vox.lss.paper.LSSPaperPlugin",
                "dev.vox.lss.paper.PaperChunkGenerationService",
                "dev.vox.lss.paper.soak.PaperSoakScenarioDriver"}) {
            for (var ref : methodRefs(name)) {
                assertFalse(ref.owner().startsWith("org/bukkit/scheduler/"),
                        name + " references " + ref.owner() + "#" + ref.name()
                                + " (legacy scheduler throws on Folia)");
            }
        }
    }

    @Test
    void pluginRoutesLifecycleThroughTheMailbox() throws IOException {
        var refs = methodRefs("dev.vox.lss.paper.LSSPaperPlugin");
        String service = "dev/vox/lss/paper/PaperRequestProcessingService";
        assertTrue(refs.contains(new MethodRef(service, "enqueueRegister")),
                "handshake registrar must enqueue, not register directly");
        assertTrue(refs.contains(new MethodRef(service, "enqueueRemove")),
                "quit listener must enqueue, not remove directly");
        assertFalse(refs.contains(new MethodRef(service, "registerPlayer")),
                "LSSPaperPlugin must not call the pump-only registerPlayer");
        assertFalse(refs.contains(new MethodRef(service, "removePlayer")),
                "LSSPaperPlugin must not call the pump-only removePlayer");
    }
}
