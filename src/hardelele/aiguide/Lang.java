package hardelele.aiguide;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.item.ItemStack;

/**
 * Item names in the players' language. A dedicated server only knows en_US, so the
 * language files are read straight from the mod jars (any "<lang>.lang" or
 * "<lang>.properties" under a "lang" folder) and from config/aiguide/lang
 * (vanilla's own translations live only in the client's assets: put them there).
 */
class Lang {
    final String lang;
    final Map<String, String> names = new HashMap<String, String>();

    Lang(String lang) {
        this.lang = lang;
    }

    void load(File extra) {
        Set<File> seen = new HashSet<File>();
        for (ModContainer m : Loader.instance().getModList()) {
            File f = m.getSource();
            if (f == null || !f.isFile() || !seen.add(f)) continue;
            try {
                ZipFile z = new ZipFile(f);
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    if (isLang(en.getName())) read(z.getInputStream(en));
                }
                z.close();
            } catch (Exception ex) {
                AIGuide.LOG.warn("no language files read from " + f.getName() + ": " + ex);
            }
        }
        File[] files = extra.listFiles();
        if (files != null)
            for (File f : files)
                if (f.getName().toLowerCase().endsWith(lang.toLowerCase() + ".lang"))
                    try {
                        read(new FileInputStream(f));
                    } catch (Exception ex) {
                        AIGuide.LOG.warn("not read: " + f + ": " + ex);
                    }
        AIGuide.LOG.info("{} names in {}", names.size(), lang);
    }

    boolean isLang(String path) {
        String p = path.toLowerCase();
        String l = lang.toLowerCase();
        return p.contains("lang/") && (p.endsWith("/" + l + ".lang") || p.endsWith("/" + l + ".properties"));
    }

    void read(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String line; (line = r.readLine()) != null; ) {
            line = line.trim();
            if (line.startsWith("﻿")) line = line.substring(1);
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            if (!v.isEmpty() && !names.containsKey(k)) names.put(k, v);
        }
        r.close();
    }

    /** the name of a stack in this language, else the server's (English) one */
    String name(ItemStack s) {
        if (s == null || s.func_77973_b() == null) return "?";
        String u;
        try {
            u = s.func_77977_a();
        } catch (RuntimeException e) {
            return "?";
        }
        if (u != null) {
            String[] keys = { u + ".name", u, strip(u) + ".name", strip(u) };
            for (String k : keys) {
                String v = names.get(k);
                if (v != null) return v;
            }
        }
        try {
            return s.func_82833_r();
        } catch (RuntimeException e) {
            return u == null ? "?" : u;
        }
    }

    /** "ic2.reactorCoolantSimple" -> "reactorCoolantSimple" (IC2 keeps its keys without the prefix) */
    static String strip(String u) {
        int d = u.indexOf('.');
        return d < 0 ? u : u.substring(d + 1);
    }

    /** a translated key, or the key itself (machine names in the recipe sources) */
    String key(String k) {
        String v = names.get(k);
        return v == null ? k : v;
    }
}
