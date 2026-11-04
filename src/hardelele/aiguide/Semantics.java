package hardelele.aiguide;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import com.google.gson.JsonArray;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * What a block is, in words the model can plan with: wood, leaves, plant, ore, valuable, stone, ground, water,
 * liquid, danger, unbreakable, machine, storage, pipe, building, decor. Rules first (ore dictionary, material,
 * hardness, tile entity); what the rules cannot tell ("?") may come from config/aiguide/labels-llm.json, a one-time
 * pass of a cheap model over the names of the leftovers.
 */
class Semantics {
    final AIGuide mod;
    final Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<String, String>();
    final Map<String, String> llm = new java.util.concurrent.ConcurrentHashMap<String, String>();
    File file;
    static Field hardness;
    static final String LABELS = "wood, leaves, plant, ore, valuable, stone, ground, water, liquid, danger, unbreakable, machine, storage, pipe, building, decor";

    Semantics(AIGuide mod) {
        this.mod = mod;
    }

    /**
     * Once per modpack (on the server thread at start): the blocks the rules cannot tell go to the model by their names,
     * 60 a request, off the server thread; what it says is kept in the file, so the next start asks nothing.
     */
    void labelUnknown() {
        if (mod.config.key().isEmpty()) return;
        final List<String[]> todo = new ArrayList<String[]>();   // key, name, English name
        Set<String> seen = new HashSet<String>();
        for (ItemStack s : mod.recipes.stacks.values()) {
            Block b = Block.func_149634_a(s.func_77973_b());
            if (b == null || b.func_149688_o() == Material.field_151579_a) continue;
            int meta = (s.func_77960_j() == RecipeIndex.WILD ? 0 : s.func_77960_j()) & 15;
            String k = key(b, meta);
            if (!seen.add(k) || llm.containsKey(k) || !rule(b, meta).equals("?")) continue;
            String en;
            try {
                en = s.func_82833_r();
            } catch (RuntimeException e) {
                en = "";
            }
            todo.add(new String[] { k, mod.lang.name(s), en });
        }
        if (todo.isEmpty()) return;
        AIGuide.LOG.info("asking the model what {} blocks are", todo.size());
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int got = 0;
                for (int i = 0; i < todo.size(); i += 60) {
                    StringBuilder lines = new StringBuilder();
                    for (String[] x : todo.subList(i, Math.min(todo.size(), i + 60))) lines.append(x[0]).append(" | ").append(x[1]).append(" | ").append(x[2]).append('\n');
                    JsonArray messages = new JsonArray();
                    messages.add(Assistant.message("system", "You label Minecraft 1.7.10 modded blocks for a navigation and planning assistant. "
                            + "Labels: " + LABELS + ". ore = a natural ore block that is mined for resources; valuable = rare (gems, precious); "
                            + "stone = natural rock or cobble; building = a crafted building block; decor = decorative or small things; "
                            + "machine = does work; danger = hurts the player; unbreakable = cannot be mined at all (bedrock-like; hard blocks like obsidian are not). "
                            + "Answer a JSON object {key: \"label[,label]\"} for every key, nothing else."));
                    messages.add(Assistant.message("user", lines.toString()));
                    try {
                        JsonObject extra = new JsonParser().parse(mod.config.extraBody).getAsJsonObject();
                        JsonObject format = new JsonObject();
                        format.addProperty("type", "json_object");
                        extra.add("response_format", format);
                        JsonObject m = mod.assistant.request(messages, null, mod.config.model, 4000, extra, mod.config.timeoutSeconds * 2);
                        JsonObject o = new JsonParser().parse(m.get("content").getAsString()).getAsJsonObject();
                        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                            String l = e.getValue().getAsString().replace(" ", "").toLowerCase();
                            if (l.matches("[a-z,]+")) {
                                llm.put(e.getKey(), l);
                                got++;
                            }
                        }
                    } catch (Exception e) {
                        AIGuide.LOG.warn("block labels: " + e);
                    }
                }
                cache.clear();
                save();
                AIGuide.LOG.info("{} blocks labelled by the model, kept in {}", got, file);
            }
        }, "AIGuide labels");
        t.setDaemon(true);
        t.start();
    }

    void save() {
        if (file == null) return;
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : new java.util.TreeMap<String, String>(llm).entrySet()) o.addProperty(e.getKey(), e.getValue());
        try {
            java.nio.file.Files.write(file.toPath(), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o).getBytes(Knowledge.UTF8));
        } catch (Exception e) {
            AIGuide.LOG.warn("labels not written to " + file + ": " + e);
        }
    }

    void load(File f) {
        file = f;
        llm.clear();
        cache.clear();
        if (!f.isFile()) return;
        try {
            JsonObject o = new JsonParser().parse(AIGuide.text(f)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) llm.put(e.getKey(), e.getValue().getAsString());
            AIGuide.LOG.info("{} block labels from {}", llm.size(), f);
        } catch (Exception e) {
            AIGuide.LOG.warn("labels not read from " + f + ": " + e);
        }
    }

    static String key(Block b, int meta) {
        return Block.field_149771_c.func_148750_c(b) + "@" + meta;
    }

    /** the labels of a block with this meta, comma separated; "air" for air */
    String labels(Block b, int meta) {
        String k = key(b, meta);
        String l = cache.get(k);
        if (l == null) {
            l = rule(b, meta);
            if (l.equals("?")) {
                String g = llm.get(k);
                if (g == null) g = llm.get(Block.field_149771_c.func_148750_c(b) + "@*");
                if (g != null) l = g;
            }
            cache.put(k, l);
        }
        return l;
    }

    static float hardness(Block b) {
        try {
            if (hardness == null) {
                hardness = Block.class.getDeclaredField("field_149782_v");
                hardness.setAccessible(true);
            }
            return hardness.getFloat(b);
        } catch (Exception e) {
            return 1;
        }
    }

    static ItemStack stack(Block b, int meta) {
        Item i = Item.func_150898_a(b);
        if (i == null) return null;
        try {
            return new ItemStack(i, 1, i.func_77614_k() ? b.func_149692_a(meta) : 0);
        } catch (RuntimeException e) {
            return new ItemStack(i, 1, 0);
        }
    }

    static Set<String> ores(ItemStack s) {
        Set<String> out = new LinkedHashSet<String>();
        if (s == null || s.func_77973_b() == null) return out;
        try {
            for (int id : OreDictionary.getOreIDs(s)) out.add(OreDictionary.getOreName(id));
        } catch (RuntimeException e) {
            // a broken stack
        }
        return out;
    }

    static final String[] PRECIOUS = { "Diamond", "Emerald", "Gold", "Uranium", "Iridium", "Platinum", "Silver", "Ruby", "Sapphire", "Peridot",
        "Cinnabar", "Osmium", "Mithril", "Amber" };

    /** the rules; "?" when they cannot tell */
    String rule(Block b, int meta) {
        Material m = b.func_149688_o();
        if (m == Material.field_151579_a) return "air";
        String id = String.valueOf(Block.field_149771_c.func_148750_c(b)).toLowerCase();
        ItemStack s = stack(b, meta);
        Set<String> ore = ores(s);
        Set<String> l = new LinkedHashSet<String>();
        for (String o : ore) {
            if (o.startsWith("ore")) {
                l.add("ore");
                for (String p : PRECIOUS) if (o.equals("ore" + p)) l.add("valuable");
            }
            if (o.startsWith("gem") || o.startsWith("block")) for (String p : PRECIOUS) if (o.endsWith(p)) l.add("valuable");
            if (o.equals("logWood") || o.startsWith("woodRubber") || o.startsWith("log")) l.add("wood");
            if (o.equals("treeLeaves")) l.add("leaves");
            if (o.equals("treeSapling")) l.add("plant");
            if (o.equals("stone") || o.equals("cobblestone") || o.startsWith("stone")) l.add("stone");
            if (o.equals("plankWood") || o.startsWith("slabWood") || o.startsWith("stairWood")) l.add("building");
            if (o.equals("sand") || o.equals("dirt") || o.equals("gravel") || o.equals("grass")) l.add("ground");
            if (o.startsWith("block") && !l.contains("valuable")) l.add("building");
        }
        if (m == Material.field_151587_i || m == Material.field_151581_o || m == Material.field_151570_A || m == Material.field_151590_u
                || m == Material.field_151569_G || id.contains("spawner")) l.add("danger");
        if (m == Material.field_151586_h) l.add("water");
        else if (m.func_76224_d()) l.add("liquid");
        if (hardness(b) < 0) l.add("unbreakable");
        boolean te = false;
        try {
            te = (Boolean) Block.class.getMethod("hasTileEntity", int.class).invoke(b, meta);
        } catch (Throwable e) {
            // some blocks want a world
        }
        if (te && !l.contains("ore") && !l.contains("danger")) {
            if (id.matches(".*(stair|slab|wall|lantern|post).*")) l.add("building");
            else if (id.matches(".*(track|rail).*")) l.add("decor");
            else if (id.matches(".*(pipe|cable|duct|conduit|wire|transport).*")) l.add("pipe");
            else if (id.matches(".*(chest|barrel|drawer|crate|storage|tank|jabba|safe|strongbox).*")) l.add("storage");
            else l.add("machine");
        }
        if (l.isEmpty()) {
            if (m == Material.field_151575_d && id.contains("log") || id.contains("rubwood") || id.contains("rubberwood.log")) l.add("wood");
            else if (m == Material.field_151584_j) l.add("leaves");
            else if (m == Material.field_151585_k || m == Material.field_151582_l || m == Material.field_151572_C || m == Material.field_151589_v)
                l.add("plant");
            else if (m == Material.field_151577_b || m == Material.field_151578_c || m == Material.field_151595_p || m == Material.field_151571_B
                    || m == Material.field_151597_y || m == Material.field_151596_z) l.add("ground");
            else if (m == Material.field_151592_s || m == Material.field_151588_w || m == Material.field_151598_x || m == Material.field_151594_q
                    || m == Material.field_151580_n || m == Material.field_151593_r || m == Material.field_151591_t) l.add("decor");
            else if (id.matches(".*(slab|stair|brick|plank|fence|wall|door|pillar|glass|tile|lamp).*")) l.add("building");
            else if (m == Material.field_151576_e && id.matches(".*(stone|cobble|granite|marble|basalt|slate|andesite|diorite|limestone).*")) l.add("stone");
            else if (m == Material.field_151575_d) l.add("building");
        }
        if (l.isEmpty()) return "?";
        StringBuilder r = new StringBuilder();
        for (String x : l) r.append(r.length() > 0 ? "," : "").append(x);
        return r.toString();
    }

    /** can a player's feet or head be in this block */
    static boolean passable(Block b) {
        Material m = b.func_149688_o();
        if (m == Material.field_151587_i || m == Material.field_151581_o || m == Material.field_151570_A) return false;
        if (!m.func_76230_c()) return true;
        String id = String.valueOf(Block.field_149771_c.func_148750_c(b)).toLowerCase();
        return id.contains("door") && !id.contains("trap") || id.contains("fence_gate");
    }

    /** can a player stand on it */
    static boolean floor(Block b) {
        Material m = b.func_149688_o();
        return m.func_76220_a() || m == Material.field_151586_h;
    }
}
