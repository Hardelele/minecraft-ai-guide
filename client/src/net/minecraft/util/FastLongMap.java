package net.minecraft.util;

import java.lang.reflect.Field;

/**
 * LongHashMap with the same contents and answers, quicker for chunk keys (docs/rl/ENGINE.md): the game hashes a key
 * by x ^ z of the chunk (LongHashMap.getHashedKey), so the loaded chunks of a square round the spawn share few buckets
 * along its diagonals and every World.getBlock walks a chain. Here: open addressing, the key mixed (MurmurHash3's
 * finaliser). Put in a chunk provider's place by RlEngine (-Daiguide.rl.engine=true); what it holds is copied from the
 * game's map entry by entry (its entries by reflection: the deobfuscated jar does not declare them nested).
 */
public class FastLongMap extends LongHashMap {
    long[] keys = new long[2048];
    Object[] vals = new Object[2048];
    int size;

    static int slot(long k, int mask) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) k & mask;
    }

    @Override
    public int getNumHashElements() { return size; }

    @Override
    public Object getValueByKey(long key) {
        int m = keys.length - 1;
        for (int i = slot(key, m); vals[i] != null; i = (i + 1) & m) if (keys[i] == key) return vals[i];
        return null;
    }

    @Override
    public boolean containsItem(long key) { return getValueByKey(key) != null; }

    @Override
    public void add(long key, Object value) {
        if (value == null) { remove(key); return; }   // (the game's keeps a null; nothing puts one in a chunk map)
        int m = keys.length - 1;
        int i = slot(key, m);
        for (; vals[i] != null; i = (i + 1) & m)
            if (keys[i] == key) { vals[i] = value; return; }
        keys[i] = key;
        vals[i] = value;
        if (++size * 2 > keys.length) resize(keys.length * 2);
    }

    @Override
    public Object remove(long key) {
        int m = keys.length - 1;
        int i = slot(key, m);
        for (; vals[i] != null; i = (i + 1) & m) if (keys[i] == key) break;
        if (vals[i] == null) return null;
        Object old = vals[i];
        vals[i] = null;
        size--;
        // the run after the hole put back where a lookup finds it (linear probing's deletion)
        for (int j = (i + 1) & m; vals[j] != null; j = (j + 1) & m) {
            long k = keys[j];
            Object v = vals[j];
            vals[j] = null;
            int t = slot(k, m);
            while (vals[t] != null) t = (t + 1) & m;
            keys[t] = k;
            vals[t] = v;
        }
        return old;
    }

    void resize(int n) {
        long[] ok = keys;
        Object[] ov = vals;
        keys = new long[n];
        vals = new Object[n];
        int m = n - 1;
        for (int i = 0; i < ok.length; i++) {
            if (ov[i] == null) continue;
            int t = slot(ok[i], m);
            while (vals[t] != null) t = (t + 1) & m;
            keys[t] = ok[i];
            vals[t] = ov[i];
        }
    }

    /** a map with what the game's holds, entry by entry */
    public static FastLongMap copyOf(LongHashMap old) throws Exception {
        Field f;
        try { f = LongHashMap.class.getDeclaredField("field_76169_a"); } catch (NoSuchFieldException e) { f = LongHashMap.class.getDeclaredField("hashArray"); }
        f.setAccessible(true);
        FastLongMap m = new FastLongMap();
        Class<?> ec = Class.forName("net.minecraft.util.LongHashMap$Entry", false, LongHashMap.class.getClassLoader());
        Field key = entryField(ec, "field_76150_a", "key"), value = entryField(ec, "field_76148_b", "value"), next = entryField(ec, "field_76149_c", "nextEntry");
        for (Object o : (Object[]) f.get(old))
            for (Object e = o; e != null; e = next.get(e)) m.add(key.getLong(e), value.get(e));
        if (m.getNumHashElements() != old.getNumHashElements())
            throw new IllegalStateException("copied " + m.getNumHashElements() + " of " + old.getNumHashElements());
        return m;
    }

    static Field entryField(Class<?> c, String srg, String mcp) throws Exception {
        Field f;
        try { f = c.getDeclaredField(srg); } catch (NoSuchFieldException e) { f = c.getDeclaredField(mcp); }
        f.setAccessible(true);
        return f;
    }
}
