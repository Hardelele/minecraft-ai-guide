package hardelele.aiguide;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/**
 * /guide learn: writes the knowledge base by itself. For each mod it gathers what the game knows,
 * its items in the players' language, the descriptions and tooltips of its language file, how its blocks
 * are made and the server's config of it, and has a (stronger) model write a guide from that.
 */
class Learner {
    final AIGuide mod;
    volatile boolean running;

    Learner(AIGuide mod) {
        this.mod = mod;
    }

    /** the mods worth a guide: with items, without a guide yet (or all of them when forced) */
    List<ModContainer> targets(String which, boolean force) {
        List<ModContainer> out = new ArrayList<ModContainer>();
        for (ModContainer m : Loader.instance().getActiveModList()) {
            String id = m.getModId();
            if (!which.equals("all") && !id.equalsIgnoreCase(which) && !m.getName().equalsIgnoreCase(which)) continue;
            if (id.equals("mcp") || id.equals("FML") || id.equals("Forge") || id.equals(AIGuide.ID)) continue;
            if (!force && hasGuide(m)) continue;
            if (which.equals("all") && items(m).size() < 5) continue;
            out.add(m);
        }
        return out;
    }

    /** a guide of its own, or one that covers it: the mod (or its family, "BuildCraft|Builders" -> buildcraft) named in a guide's opening */
    boolean hasGuide(ModContainer m) {
        if (new File(mod.knowledge.dir, file(m)).isFile()) return true;
        String id = m.getModId().toLowerCase().split("\\|")[0].replaceAll("[^a-z0-9]", "");
        String name = m.getName().toLowerCase().replaceAll("^bc ", "buildcraft ").replaceAll("[^a-z0-9]", "");
        for (String topic : mod.knowledge.titles.keySet()) {
            String head = mod.knowledge.read(topic);
            if (head == null) continue;
            head = head.substring(0, Math.min(400, head.length())).toLowerCase().replaceAll("[^a-z0-9]", "");
            if ((id.length() > 3 && head.contains(id)) || (name.length() > 3 && head.contains(name))) return true;
        }
        return false;
    }

    static String file(ModContainer m) {
        return m.getModId().toLowerCase().replaceAll("[^a-z0-9_\\-]", "_") + ".md";
    }

    List<ItemStack> items(ModContainer m) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        String prefix = m.getModId().toLowerCase() + ":";
        for (Map.Entry<String, ItemStack> e : mod.recipes.stacks.entrySet())
            if (e.getKey().toLowerCase().startsWith(prefix)) out.add(e.getValue());
        return out;
    }

    /** starts in the background; progress goes to the log and to the player who asked (if any) */
    void start(final String which, final boolean force, final String player) {
        if (running) { report(player, "/guide learn is already running."); return; }
        final List<ModContainer> mods = targets(which, force);
        if (mods.isEmpty()) { report(player, "Nothing to learn: every mod with items has a guide (add 'force' to rewrite)."); return; }
        running = true;
        report(player, "Learning " + mods.size() + " mod(s) with " + mod.config.learnModel + ": " + names(mods));
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int done = 0;
                try {
                    for (ModContainer m : mods) {
                        try {
                            String text = write(m);
                            Files.write(new File(mod.knowledge.dir, file(m)).toPath(), (text.trim() + "\n").getBytes(Knowledge.UTF8));
                            done++;
                            report(player, "Guide written: " + file(m) + " (" + done + "/" + mods.size() + ")");
                        } catch (Exception e) {
                            AIGuide.LOG.warn("guide for " + m.getModId() + " failed", e);
                            report(player, "Guide for " + m.getName() + " failed: " + e.getMessage());
                        }
                    }
                } finally {
                    mod.knowledge.load();
                    running = false;
                    report(player, "Learning done: " + done + " guide(s). /guide topics");
                }
            }
        }, "AIGuide-learn");
        t.setDaemon(true);
        t.start();
    }

    static String names(List<ModContainer> l) {
        StringBuilder b = new StringBuilder();
        for (ModContainer m : l) b.append(b.length() > 0 ? ", " : "").append(m.getName());
        return b.toString();
    }

    void report(String player, String text) {
        AIGuide.LOG.info(text);
        if (player != null) mod.say(player, text);
    }

    String write(ModContainer m) throws Exception {
        String data = dossier(m);
        String prompt = AIGuide.text(new File(mod.config.dir, "learn.md"));
        prompt = prompt.replace("{language}", mod.config.language).replace("{mod}", m.getName()).replace("{mods}", AIGuide.modList())
                .replace("{guides}", mod.knowledge.index());
        JsonArray messages = new JsonArray();
        messages.add(Assistant.message("system", prompt));
        messages.add(Assistant.message("user", data));
        JsonObject extra;
        try {
            extra = new JsonParser().parse(mod.config.learnExtraBody).getAsJsonObject();
        } catch (Exception e) {
            extra = new JsonObject();
        }
        long t0 = System.currentTimeMillis();
        JsonObject msg = mod.assistant.request(messages, null, mod.config.learnModel, mod.config.learnMaxTokens, extra, 1200);
        String text = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
        int thought = msg.has("reasoning_content") && !msg.get("reasoning_content").isJsonNull() ? msg.get("reasoning_content").getAsString().length() : 0;
        AIGuide.LOG.info("guide for {}: {} chars of data, {} of thinking, {} written, {} s", m.getModId(), data.length(), thought, text.length(),
                (System.currentTimeMillis() - t0) / 1000);
        if (text.trim().length() < 200) throw new Exception("the model wrote almost nothing (" + thought + " chars of thinking: raise learn.maxTokens)");
        return text;
    }

    /** everything the game knows about a mod, as text, cut to the configured size */
    String dossier(ModContainer m) {
        StringBuilder b = new StringBuilder();
        b.append("# Mod: ").append(m.getName()).append(' ').append(m.getVersion()).append(" (id ").append(m.getModId()).append(")\n");
        try {
            String d = m.getMetadata().description;
            if (d != null && !d.isEmpty()) b.append("Its own description: ").append(d).append('\n');
        } catch (Exception e) {
            // no metadata
        }
        List<ItemStack> items = items(m);
        b.append("\n## Its items and blocks (").append(items.size()).append("), as players see them\n");
        int n = 0;
        for (ItemStack s : items) {
            if (n++ >= 500) { b.append("... and ").append(items.size() - 500).append(" more\n"); break; }
            b.append(mod.lang.name(s)).append(s.func_77973_b() instanceof ItemBlock ? " (block)" : "").append('\n');
        }
        b.append("\n## Descriptions and tooltips from its language file\n");
        int tips = 0;
        for (Map.Entry<String, String> e : langOf(m).entrySet()) {
            String k = e.getKey().toLowerCase(), v = e.getValue();
            if (v.length() < 25 || !(k.contains("desc") || k.contains("tooltip") || k.contains("info") || k.contains("lore") || k.contains("tip")
                    || k.contains("manual") || k.contains("book") || k.contains("guide") || k.contains("help") || k.contains("hint"))) continue;
            b.append(v.replaceAll("§.", "")).append('\n');
            if (++tips >= 300) break;
        }
        b.append("\n## How its blocks are made (machines and the like)\n");
        n = 0;
        for (ItemStack s : items) {
            if (!(s.func_77973_b() instanceof ItemBlock) || mod.recipes.producing(s).isEmpty()) continue;
            String r = mod.tools.recipes(RecipeIndex.key(s), true);
            b.append(r.length() > 600 ? r.substring(0, 600) + "...\n" : r);
            if (++n >= 40 || b.length() > mod.config.learnMaxChars * 2 / 3) break;
        }
        b.append("\n## What its items are used for (counts of recipes using them)\n");
        n = 0;
        for (ItemStack s : items) {
            int uses = mod.recipes.using(s).size();
            if (uses < 3) continue;
            b.append(mod.lang.name(s)).append(": ").append(uses).append('\n');
            if (++n >= 60) break;
        }
        b.append("\n## The server's config of it\n");
        b.append(configOf(m, mod.config.learnMaxChars - b.length() - 1000));
        String s = b.toString();
        return s.length() > mod.config.learnMaxChars ? s.substring(0, mod.config.learnMaxChars) : s;
    }

    Map<String, String> langOf(ModContainer m) {
        Lang l = new Lang(mod.config.language);
        File f = m.getSource();
        if (f != null && f.isFile())
            try {
                ZipFile z = new ZipFile(f);
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    if (l.isLang(en.getName())) l.read(z.getInputStream(en));
                }
                z.close();
            } catch (Exception e) {
                // no language file
            }
        return l.names;
    }

    /** config files named after the mod */
    static String configOf(ModContainer m, int budget) {
        StringBuilder b = new StringBuilder();
        String id = m.getModId().toLowerCase().replaceAll("[^a-z0-9]", ""), name = m.getName().toLowerCase().replaceAll("[^a-z0-9]", "");
        List<File> files = new ArrayList<File>();
        collect(new File("config"), files, 0);
        for (File f : files) {
            String fn = f.getPath().toLowerCase().replaceAll("[^a-z0-9]", "");
            if (!(fn.contains(id) || (name.length() > 3 && fn.contains(name)))) continue;
            String t = AIGuide.text(f);
            b.append("### ").append(f.getPath()).append('\n').append(t.length() > 12000 ? t.substring(0, 12000) + "\n..." : t).append('\n');
            if (b.length() > budget) break;
        }
        return b.length() > Math.max(0, budget) ? b.substring(0, Math.max(0, budget)) : b.toString();
    }

    static void collect(File dir, List<File> out, int depth) {
        File[] l = dir.listFiles();
        if (l == null || depth > 3) return;
        for (File f : l) {
            if (f.isDirectory()) { if (!f.getName().equals("aiguide")) collect(f, out, depth + 1); }
            else if (f.getName().matches("(?i).*\\.(cfg|conf|ini|txt|json|properties)$") && f.length() < 200000) out.add(f);
        }
    }
}
