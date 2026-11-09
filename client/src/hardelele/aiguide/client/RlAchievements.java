package hardelele.aiguide.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minecraft 1.7.10's achievements (net.minecraft.stats.AchievementList) as a tree with no game in it: each with its
 * parent, its name in the game's ru_RU.lang, whether an agent can get it here (supported) and the game's condition
 * as the environment repeats it for an agent (an agent is no player: the game itself never gives one to it). An
 * agent gets an achievement only when it holds its parent (StatFileWriter.canUnlockAchievement); the Nether, the End,
 * rails, cake, fish, a saddle and giving to a player are not supported (the curriculum never sets them).
 * rl/achievements.json is written from here (RlSpec.main); RlEnv's Tracker gives them in the game.
 */
final class RlAchievements {
    private RlAchievements() { }

    static final class Ach {
        final String id, parent, ru, condition;
        final boolean supported;
        Ach(String id, String parent, String ru, boolean supported, String condition) {
            this.id = id; this.parent = parent; this.ru = ru; this.supported = supported; this.condition = condition;
        }
    }

    static final Map<String, Ach> ALL = Collections.unmodifiableMap(all());

    static Map<String, Ach> all() {
        Map<String, Ach> m = new LinkedHashMap<String, Ach>();
        add(m, "openInventory", null, "Проверить карманы", true, "at its first decision (an agent's bag is always open: the game's press of E)");
        add(m, "mineWood", "openInventory", "Нарубить дров", true, "a log or log2 comes into its bag (EntityItem pickup)");
        add(m, "buildWorkBench", "mineWood", "Рабочий стол", true, "it crafts a crafting_table");
        add(m, "buildPickaxe", "buildWorkBench", "Пора в шахту!", true, "it crafts any pickaxe");
        add(m, "buildFurnace", "buildPickaxe", "Горячая штучка", true, "it crafts a furnace");
        add(m, "acquireIron", "buildFurnace", "Куй железо пока...", true, "an iron_ingot comes out of a furnace for it (smelt)");
        add(m, "buildHoe", "buildWorkBench", "Дачный сезон!", true, "it crafts any hoe");
        add(m, "makeBread", "buildHoe", "Хлеб насущный", true, "it crafts bread");
        add(m, "bakeCake", "buildHoe", "Вот такие пироги!", false, "it crafts a cake (milk: no buckets here)");
        add(m, "buildBetterPickaxe", "buildPickaxe", "Обновка!", true, "it crafts a pickaxe not of wood");
        add(m, "cookFish", "buildFurnace", "Вкусная рыбка", false, "cooked fish out of a furnace (no fishing here)");
        add(m, "onARail", "acquireIron", "Стук колёс", false, "a minecart ride of 1000 m");
        add(m, "buildSword", "buildWorkBench", "К бою готов!", true, "it crafts any sword");
        add(m, "killEnemy", "buildSword", "Охотник на монстров", true, "it kills a monster (IMob): a hit or its arrow");
        add(m, "killCow", "buildSword", "Мясник", true, "leather comes into its bag");
        add(m, "flyPig", "killCow", "Жаль, что свиньи не летают...", false, "a fall of 5 on a saddled pig");
        add(m, "snipeSkeleton", "killEnemy", "Снайперская дуэль", true, "its arrow kills a skeleton 50 blocks away or more (horizontally)");
        add(m, "diamonds", "acquireIron", "АЛМАЗЫ!", true, "a diamond comes into its bag");
        add(m, "diamondsToYou", "diamonds", "Алмазы для вас!", false, "diamonds thrown to a player");
        add(m, "portal", "diamonds", "В самое пекло", false, "into the Nether");
        add(m, "ghast", "portal", "Вернуть отправителю", false, "a ghast killed by its own fireball");
        add(m, "blazeRod", "portal", "Пламенный привет", false, "a blaze rod");
        add(m, "potion", "blazeRod", "Юный алхимик", false, "a potion brewed");
        add(m, "theEnd", "blazeRod", "Конец?", false, "into the End");
        add(m, "theEnd2", "theEnd", "Конец.", false, "out of the End");
        add(m, "enchantments", "diamonds", "Заклинатель", true, "it crafts an enchanting_table");
        add(m, "overkill", "enchantments", "Смертельный удар", true, "one hit of its deals 18 or more");
        add(m, "bookcase", "enchantments", "Библиотекарь", true, "it crafts a bookshelf");
        add(m, "breedCow", "killCow", "Перенаселение", true, "two cows it fed make a calf");
        add(m, "spawnWither", "theEnd2", "Начало?", false, "a wither built");
        add(m, "killWither", "spawnWither", "Начало.", false, "a wither killed");
        add(m, "fullBeacon", "killWither", "Маяковский", false, "a full beacon");
        add(m, "exploreAllBiomes", "theEnd", "Время приключений", false, "every biome visited");
        return m;
    }

    static void add(Map<String, Ach> m, String id, String parent, String ru, boolean supported, String condition) {
        m.put(id, new Ach(id, parent, ru, supported, condition));
    }

    /** the supported ones, in the tree's order (the observation's ach_ features) */
    static List<String> supported() {
        List<String> l = new ArrayList<String>();
        for (Ach a : ALL.values()) if (a.supported) l.add(a.id);
        return l;
    }

    static int depth(String id) {
        int d = 0;
        for (Ach a = ALL.get(id); a != null && a.parent != null; a = ALL.get(a.parent)) d++;
        return d;
    }

    /** the game's rule: an achievement is got only with its parent */
    static boolean canGet(Set<String> got, String id) {
        Ach a = ALL.get(id);
        return a != null && !got.contains(id) && (a.parent == null || got.contains(a.parent));
    }

    /** the supported achievements one may get next (the parent got, it not) */
    static Set<String> available(Set<String> got) {
        Set<String> s = new LinkedHashSet<String>();
        for (Ach a : ALL.values()) if (a.supported && canGet(got, a.id)) s.add(a.id);
        return s;
    }

    static JsonObject json(int version) {
        JsonObject o = new JsonObject();
        o.addProperty("version", version);
        o.addProperty("note", "Generated from RlAchievements.java: Minecraft 1.7.10's achievements (AchievementList) with their parents and the game's "
                + "ru_RU names. An agent gets one only with its parent; 'supported': the environment gives it to agents (its condition as here), "
                + "the others never (the curriculum must not set them).");
        JsonArray a = new JsonArray();
        for (Ach x : ALL.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", x.id);
            if (x.parent != null) j.addProperty("parent", x.parent);
            j.addProperty("depth", depth(x.id));
            j.addProperty("name_ru", x.ru);
            j.addProperty("supported", x.supported);
            j.addProperty("condition", x.condition);
            a.add(j);
        }
        o.add("achievements", a);
        return o;
    }
}
