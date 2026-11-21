package net.vulkanmod.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.world.level.ChunkPos;

public class ChunkStatusMap {
    public static final byte DATA_READY = 0b1;
    public static final byte LIGHT_READY = 0b10;
    public static final byte NEIGHBOURS_READY = 0b100;
    public static final byte CHUNK_READY = DATA_READY | LIGHT_READY;
    public static final byte ALL_FLAGS = CHUNK_READY | NEIGHBOURS_READY;

    public static ChunkStatusMap INSTANCE;

    public static void createInstance(int renderDistance) {
        INSTANCE = new ChunkStatusMap(renderDistance);
    }

    private final Long2ByteOpenHashMap map;

    /**
     * 2026-11-21 (the user's 1% lows, JFR): the shadow casters and the edge cover ask chunkRenderReady for every section they walk,
     * and the hash lookups were about 12% of the render thread's samples. A direct-mapped memo of the answers (chunk x and z, MEMO_BITS
     * low bits each) is dropped as a whole by a new version whenever a status changes, so it answers as the map does.
     */
    static final int MEMO_BITS = 7, MEMO_MASK = (1 << MEMO_BITS) - 1;
    private final long[] memoKey = new long[1 << (2 * MEMO_BITS)];
    private final int[] memoVersion = new int[1 << (2 * MEMO_BITS)];//0: empty
    private final boolean[] memoReady = new boolean[1 << (2 * MEMO_BITS)];
    private int version = 1;

    public ChunkStatusMap(int renderDistance) {
        int diameter = renderDistance * 2 + 1;
        map = new Long2ByteOpenHashMap(diameter * diameter);
        map.defaultReturnValue((byte) 0);
    }

    public void updateDistance(int renderDistance) {
        int diameter = renderDistance * 2 + 1;
        this.map.ensureCapacity(diameter * diameter);
    }

    /** A status may change: every memo slot is stale from now on. */
    private void changed() {
        if (++this.version == 0) {
            java.util.Arrays.fill(this.memoVersion, 0);
            this.version = 1;
        }
    }

    public void setChunkStatus(int x, int z, byte flag) {
        this.changed();
        long l = ChunkPos.asLong(x, z);

        byte current = map.get(l);
        current |= flag;
        map.put(l, current);

        if ((current & CHUNK_READY) == CHUNK_READY) {
            updateNeighbours(x, z);
            scheduleGraphUpdateIfRendererExists();
        }
    }

    public void resetChunkStatus(int x, int z, byte flag) {
        this.changed();
        long l = ChunkPos.asLong(x, z);

        byte current = map.get(l);
        current = (byte) (current & ~flag);
        map.put(l, current);

        updateNeighbours(x, z);
    }

    public void updateNeighbours(int x, int z) {
        this.changed();
        for (int x1 = x - 1; x1 <= x + 1; ++x1) {
            for (int z1 = z - 1; z1 <= z + 1; ++z1) {
                if (checkNeighbours(x1, z1)) {
                    map.put(ChunkPos.asLong(x1, z1), ALL_FLAGS);
                }
                else {
                    long l = ChunkPos.asLong(x1, z1);

                    byte current = map.get(l);
                    byte n = (byte) (current & ~NEIGHBOURS_READY);

                    if (current == 0b0)
                        map.remove(l);
                    else if (current != n)
                        map.put(l, n);
                }
            }
        }
    }

    public boolean checkNeighbours(int x, int z) {
        byte flags = CHUNK_READY;
        for (int x1 = x - 1; x1 <= x + 1; ++x1) {
            for (int z1 = z - 1; z1 <= z + 1; ++z1) {
                flags &= map.get(ChunkPos.asLong(x1, z1));

                if (flags != CHUNK_READY)
                    return false;
            }
        }
        return true;

    }

    public boolean chunkRenderReady(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        int slot = ((x & MEMO_MASK) << MEMO_BITS) | (z & MEMO_MASK);
        if (this.memoVersion[slot] == this.version && this.memoKey[slot] == key) return this.memoReady[slot];
        boolean ready = map.get(key) == ALL_FLAGS;
        this.memoKey[slot] = key;
        this.memoVersion[slot] = this.version;
        this.memoReady[slot] = ready;
        return ready;
    }

    /** Tests: the memo's version, to reach its wrap to 0. */
    void versionForTest(int v) {
        this.version = v;
    }

    /** Debug (the pilot's chunks line): how many tracked chunks have data, light, both, and all flags. */
    public String describe() {
        int data = 0, light = 0, ready = 0, all = 0;
        for (byte s : this.map.values()) {
            if ((s & DATA_READY) != 0) data++;
            if ((s & LIGHT_READY) != 0) light++;
            if ((s & CHUNK_READY) == CHUNK_READY) ready++;
            if (s == ALL_FLAGS) all++;
        }
        return "status " + this.map.size() + " tracked, data " + data + ", light " + light + ", ready " + ready + ", render-ready " + all;
    }

    public void reset() {

    }

    private static void scheduleGraphUpdateIfRendererExists() {
        WorldRenderer worldRenderer = WorldRenderer.getInstance();
        if (worldRenderer != null && worldRenderer.getSectionGrid() != null) {
            worldRenderer.scheduleGraphUpdate();
        }
    }

}
