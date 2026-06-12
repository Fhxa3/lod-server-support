package dev.vox.lss.paper;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PaperWorldHandler}'s reflection-based extraction: every supported method
 * shape (blockList/getBlocks/getBlock/getLocation/getChunk) marks the right chunk in the
 * right dimension. These paths fail silently in production (a wrong world-key string kills
 * ALL dirty broadcasts; a broken {@code >>4} marks the wrong chunk for negative coords),
 * and no Paper soak harness exists yet to backstop them. JDK proxies stand in for the
 * Bukkit interfaces; the captured EventExecutor is the same lambda Bukkit would invoke.
 */
class PaperWorldHandlerTest {

    private static final String OVERWORLD = "minecraft:overworld";

    private record Registration(Class<?> eventClass, Listener listener, EventExecutor executor) {}

    private DirtyColumnTracker tracker;
    private PaperWorldHandler handler;
    private final List<Registration> registrations = new ArrayList<>();
    private World overworld;

    @BeforeEach
    void setup() {
        tracker = new DirtyColumnTracker();
        registrations.clear();
        handler = new PaperWorldHandler(pluginProxy(), tracker);
        overworld = world(OVERWORLD);
    }

    // ---- proxy plumbing ----

    private static <T> T proxy(Class<T> iface, InvocationHandler h) {
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h));
    }

    /** Exact boxed default per return type (a proxy returning Integer for byte would throw). */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return 0;
    }

    private static InvocationHandler defaults() {
        return (p, m, args) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            case "toString" -> "proxy";
            default -> defaultValue(m.getReturnType());
        };
    }

    /** Plugin whose PluginManager records every registerEvent call. */
    private Plugin pluginProxy() {
        PluginManager pm = proxy(PluginManager.class, (p, m, args) -> {
            if ("registerEvent".equals(m.getName()) && args != null && args.length == 6) {
                registrations.add(new Registration(
                        (Class<?>) args[0], (Listener) args[1], (EventExecutor) args[3]));
            }
            return defaults().invoke(p, m, args);
        });
        Server server = proxy(Server.class, (p, m, args) ->
                "getPluginManager".equals(m.getName()) ? pm : defaults().invoke(p, m, args));
        return proxy(Plugin.class, (p, m, args) ->
                "getServer".equals(m.getName()) ? server : defaults().invoke(p, m, args));
    }

    private static World world(String key) {
        NamespacedKey nsKey = Objects.requireNonNull(NamespacedKey.fromString(key));
        return proxy(World.class, (p, m, args) ->
                "getKey".equals(m.getName()) ? nsKey : defaults().invoke(p, m, args));
    }

    private static Block block(World w, int x, int z) {
        return proxy(Block.class, (p, m, args) -> switch (m.getName()) {
            case "getWorld" -> w;
            case "getX" -> x;
            case "getZ" -> z;
            default -> defaults().invoke(p, m, args);
        });
    }

    private static BlockState blockState(World w, int x, int z) {
        return proxy(BlockState.class, (p, m, args) -> switch (m.getName()) {
            case "getWorld" -> w;
            case "getX" -> x;
            case "getZ" -> z;
            default -> defaults().invoke(p, m, args);
        });
    }

    private static Chunk chunk(World w, int cx, int cz) {
        return proxy(Chunk.class, (p, m, args) -> switch (m.getName()) {
            case "getWorld" -> w;
            case "getX" -> cx;
            case "getZ" -> cz;
            default -> defaults().invoke(p, m, args);
        });
    }

    /** Register the event's own class and deliver it through the captured executor. */
    private void registerAndFire(Event event) throws Exception {
        handler.registerUpdateListeners(List.of(event.getClass().getName()));
        var reg = registrations.getLast();
        fire(reg, event);
    }

    private static void fire(Registration reg, Event event) throws Exception {
        reg.executor().execute(reg.listener(), event);
    }

    private void assertDirty(String dimension, long... expected) {
        long[] drained = tracker.drainDirty(dimension);
        assertNotNull(drained, "expected dirty positions in " + dimension);
        Arrays.sort(drained);
        Arrays.sort(expected);
        assertArrayEquals(expected, drained);
    }

    // ---- synthetic events covering the discovery-method matrix ----

    public static class BlockListEvent extends Event {
        private final List<Block> blocks;
        public BlockListEvent(List<Block> blocks) { this.blocks = blocks; }
        public List<Block> blockList() { return blocks; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class BlockStatesEvent extends Event {
        private final List<BlockState> states;
        public BlockStatesEvent(List<BlockState> states) { this.states = states; }
        public List<BlockState> getBlocks() { return states; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class SingleBlockEvent extends Event {
        private final Block block;
        public SingleBlockEvent(Block block) { this.block = block; }
        public Block getBlock() { return block; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class LocationEvent extends Event {
        private final Location location;
        public LocationEvent(Location location) { this.location = location; }
        public Location getLocation() { return location; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class ChunkEvent extends Event {
        private final Chunk chunk;
        public ChunkEvent(Chunk chunk) { this.chunk = chunk; }
        public Chunk getChunk() { return chunk; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    /** Mirrors BlockExplodeEvent: has BOTH blockList() and getBlock(). */
    public static class ExplosionLikeEvent extends Event {
        private final List<Block> destroyed;
        private final Block source;
        public ExplosionLikeEvent(List<Block> destroyed, Block source) {
            this.destroyed = destroyed;
            this.source = source;
        }
        public List<Block> blockList() { return destroyed; }
        public Block getBlock() { return source; }
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    public static class OpaqueEvent extends Event {
        @Override public HandlerList getHandlers() { return new HandlerList(); }
    }

    // ---- extraction matrix ----

    @Test
    void blockListEventMarksEachBlocksChunk() throws Exception {
        registerAndFire(new BlockListEvent(List.of(
                block(overworld, 0, 0), block(overworld, 17, 33))));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(1, 2));
    }

    @Test
    void getBlocksEventUsesBlockStateBranch() throws Exception {
        registerAndFire(new BlockStatesEvent(List.of(blockState(overworld, 16, -16))));
        assertDirty(OVERWORLD, PositionUtil.packPosition(1, -1));
    }

    @Test
    void getBlockEventMarksSingleChunk() throws Exception {
        registerAndFire(new SingleBlockEvent(block(overworld, 31, 47)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(1, 2));
    }

    @Test
    void getLocationEventFloorsAndShifts() throws Exception {
        // blockX = floor(-1.5) = -2 -> chunk -1; blockZ = -17 -> chunk -2
        registerAndFire(new LocationEvent(new Location(overworld, -1.5, 64.0, -17.0)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(-1, -2));
    }

    @Test
    void getChunkEventUsesChunkCoordsWithoutShift() throws Exception {
        registerAndFire(new ChunkEvent(chunk(overworld, 5, -7)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(5, -7));
    }

    @Test
    void negativeBlockCoordsUseArithmeticShift() throws Exception {
        // -1>>4 = -1 and -16>>4 = -1; integer division (-1/16 = 0) would mark chunk (0, -1)
        registerAndFire(new BlockListEvent(List.of(
                block(overworld, -1, -16), block(overworld, -17, 15))));
        assertDirty(OVERWORLD,
                PositionUtil.packPosition(-1, -1), PositionUtil.packPosition(-2, 0));
    }

    @Test
    void customWorldKeyBecomesTheDimensionString() throws Exception {
        registerAndFire(new SingleBlockEvent(block(world("lsstest:custom_world"), 8, 8)));
        assertDirty("lsstest:custom_world", PositionUtil.packPosition(0, 0));
        assertNull(tracker.drainDirty(OVERWORLD), "nothing leaks into the overworld bucket");
    }

    @Test
    void blockListWinsOverGetBlockInDiscoveryOrder() throws Exception {
        // For explosion-shaped events the list IS the destruction; falling back to getBlock()
        // would mark only the source chunk and miss every destroyed chunk.
        registerAndFire(new ExplosionLikeEvent(
                List.of(block(overworld, 0, 0), block(overworld, 160, 160)),
                block(overworld, 320, 320)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(10, 10));
    }

    @Test
    void locationWithNullWorldIsIgnored() throws Exception {
        registerAndFire(new LocationEvent(new Location(null, 5.0, 64.0, 5.0)));
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void eventWithoutExtractorMarksNothingEvenWhenRefired() throws Exception {
        var event = new OpaqueEvent();
        registerAndFire(event);
        fire(registrations.getLast(), event); // second fire exercises the negative-cache path
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void cachedMethodPathStillExtractsOnSecondFire() throws Exception {
        registerAndFire(new SingleBlockEvent(block(overworld, 0, 0)));
        fire(registrations.getLast(), new SingleBlockEvent(block(overworld, 16, 0)));
        assertDirty(OVERWORLD, PositionUtil.packPosition(0, 0), PositionUtil.packPosition(1, 0));
    }

    // ---- registration resilience and defaults ----

    @Test
    void badClassNamesAreSkippedAndRegistrationContinues() {
        handler.registerUpdateListeners(List.of(
                "com.example.DoesNotExist",          // ClassNotFoundException branch
                "java.lang.String",                  // not an Event -> generic catch branch
                BlockListEvent.class.getName()));
        assertEquals(1, registrations.size(), "only the valid class registers; bad names do not abort the loop");
        assertEquals(BlockListEvent.class, registrations.get(0).eventClass());
    }

    @Test
    void defaultUpdateEventsAllResolveAndExcludeChunkPopulate() {
        List<String> defaults = new PaperConfig().updateEvents;
        assertFalse(defaults.contains("org.bukkit.event.world.ChunkPopulateEvent"),
                "ChunkPopulateEvent must not be a default: it re-marks every LSS-generated chunk");
        assertTrue(defaults.contains("org.bukkit.event.block.BlockBreakEvent"));
        handler.registerUpdateListeners(defaults);
        assertEquals(defaults.size(), registrations.size(),
                "every default event class name resolves on the Paper API (catches typos in defaults)");
    }
}
