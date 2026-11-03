package hardelele.aiguide;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An assistant in the chat that knows the modpack: a player writes "? question" (or /guide question)
 * and gets an answer from a language model (any OpenAI-compatible API) that looks things up in the
 * running game: item names in the players' language, recipes of the crafting table, the furnace and
 * the machines, the player's inventory and the machines around them, the server's own guides
 * (config/aiguide/knowledge/*.md) and what it knows about the player (config/aiguide/players).
 * Server-side only: players need nothing installed.
 */
@Mod(modid = AIGuide.ID, name = "AI Guide", version = AIGuide.VERSION, acceptableRemoteVersions = "*")
public class AIGuide {
    public static final String ID = "aiguide", VERSION = "0.3";
    static final Logger LOG = LogManager.getLogger("AIGuide");

    GuideConfig config;
    Lang lang;
    RecipeIndex recipes;
    Knowledge knowledge;
    Profiles profiles;
    Tools tools;
    Assistant assistant;
    Storage storage;
    Actions actions;
    Errands errands;
    McpServer mcp;
    Learner learner;
    int ticks;
    Thread serverThread;
    final ConcurrentLinkedQueue<Runnable> mainQueue = new ConcurrentLinkedQueue<Runnable>();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        config = new GuideConfig(e.getModConfigurationDirectory());
        defaults();
        knowledge = new Knowledge(new File(config.dir, "knowledge"));
        profiles = new Profiles(new File(config.dir, "players"));
        tools = new Tools(this);
        assistant = new Assistant(this);
        storage = new Storage(this);
        actions = new Actions(this);
        errands = new Errands(this);
        mcp = new McpServer(this);
        learner = new Learner(this);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent e) {
        mcp.stop();
        errands.stopAll();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent e) {
        e.registerServerCommand(new GuideCommand(this));
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent e) {
        serverThread = Thread.currentThread();
        long t0 = System.currentTimeMillis();
        lang = new Lang(config.language);
        lang.load(new File(config.dir, "lang"));
        recipes = new RecipeIndex(lang);
        recipes.build(config.sources);
        tools.names = null;
        knowledge.load();
        LOG.info("ready in {} ms; ask with \"{} question\" or /guide", System.currentTimeMillis() - t0, config.prefix);
        if (config.mcpEnabled) mcp.start();
        if (Boolean.getBoolean(ID + ".selftest")) SelfTest.run(this);
    }

    /** writes the default prompt and readme into config/aiguide if they are not there */
    void defaults() {
        for (String f : new String[] { "system.md", "learn.md", "README.md" }) {
            File to = new File(config.dir, f);
            if (to.exists()) continue;
            try {
                InputStream in = AIGuide.class.getResourceAsStream("/assets/aiguide/" + f);
                Files.copy(in, to.toPath());
                in.close();
            } catch (Exception ex) {
                LOG.warn("default " + f + " not written: " + ex);
            }
        }
        new File(config.dir, "lang").mkdirs();
    }

    @SubscribeEvent
    public void chat(ServerChatEvent e) {
        String m = e.message.trim();
        String bare = m.startsWith(config.prefix) ? m.substring(config.prefix.length()) : m;
        final String name = e.player.func_70005_c_();
        final Boolean yes = Errands.reply(bare);
        if (yes != null && errands.waiting(name)) {   // "да" / "нет" to the body's question
            e.setCanceled(true);
            mainQueue.add(new Runnable() {
                @Override public void run() { errands.answer(name, yes); }
            });
            return;
        }
        if (!m.startsWith(config.prefix) || m.length() <= config.prefix.length()) return;
        e.setCanceled(true);   // a question to the assistant is not for everybody
        String q = m.substring(config.prefix.length()).trim();
        ask(e.player, q);
    }

    void ask(EntityPlayerMP p, String q) {
        String name = p.func_70005_c_();
        p.func_145747_a(new ChatComponentText("§7" + config.prefix + " " + q));
        if (!assistant.ask(name, q)) p.func_145747_a(new ChatComponentText("§7" + msg("busy")));
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        for (Runnable r; (r = mainQueue.poll()) != null; )
            try {
                r.run();
            } catch (Exception ex) {   // never takes the server down
                LOG.warn("guide task failed", ex);
            }
        if (++ticks % 10 == 0) actions.tick();
        errands.tick();
    }

    /** runs on the server thread and waits for the result (for tools that look at the world) */
    <T> T onMain(Callable<T> c) throws Exception {
        if (Thread.currentThread() == serverThread) return c.call();
        FutureTask<T> t = new FutureTask<T>(c);
        mainQueue.add(t);
        return t.get(10, TimeUnit.SECONDS);
    }

    /** players that are not in the player list: the self-test's fake one */
    static final Map<String, EntityPlayerMP> extraPlayers = new HashMap<String, EntityPlayerMP>();

    static EntityPlayerMP player(String name) {
        if (extraPlayers.containsKey(name)) return extraPlayers.get(name);
        MinecraftServer s = MinecraftServer.func_71276_C();
        return s == null ? null : s.func_71203_ab().func_152612_a(name);
    }

    /** sends an answer to a player, a chat line per line of text */
    void say(final String player, String text) {
        final String[] lines = format(text).split("\n");
        mainQueue.add(new Runnable() {
            @Override public void run() {
                EntityPlayerMP p = player(player);
                if (p == null || p.field_71135_a == null) {   // offline, or the self-test's fake player
                    LOG.info("answer for {} (offline): {}", player, String.join(" / ", lines));
                    return;
                }
                int n = 0;
                for (String l : lines) {
                    if (l.trim().isEmpty()) continue;
                    if (++n > config.maxLines) {
                        p.func_145747_a(new ChatComponentText("§7" + msg("cut")));
                        break;
                    }
                    p.func_145747_a(new ChatComponentText((n == 1 ? "§b[" + config.assistantName + "]§r " : "§r") + l));
                }
            }
        });
    }

    /** Markdown the chat cannot show, made plain */
    static String format(String t) {
        StringBuilder b = new StringBuilder();
        for (String l : t.replace("\r", "").split("\n")) {
            l = l.replaceAll("^#{1,6}\\s*", "§e").replaceAll("\\*\\*(.+?)\\*\\*", "§e$1§r")
                    .replaceAll("__(.+?)__", "$1").replace("`", "").replaceAll("^(\\s*)[-*]\\s+", "$1• ");
            b.append(l).append('\n');
        }
        return b.toString().trim();
    }

    static final Map<String, String[]> MESSAGES = new HashMap<String, String[]>();
    static {
        m("busy", "Подожди, я ещё отвечаю на прошлый вопрос.", "Wait, I am still answering your last question.");
        m("error", "Не получилось ответить:", "Could not answer:");
        m("nokey", "Ключ API не задан: впиши его в config/aiguide/api.key (или apiKey в config/aiguide.cfg).",
                "No API key: put it into config/aiguide/api.key (or apiKey in config/aiguide.cfg).");
        m("empty", "Модель ничего не ответила, спроси иначе.", "The model said nothing, ask another way.");
        m("cut", "(ответ длиннее, спроси подробнее про часть)", "(the answer is longer, ask about a part of it)");
        m("help", "Спроси: \"? вопрос\" или /guide вопрос. /guide topics — гайды, /guide read <тема> [страница], "
                + "/guide me <о себе> — что гид знает о тебе, /guide forget — забыть разговор.",
                "Ask: \"? question\" or /guide question. /guide topics — guides, /guide read <topic> [page], "
                + "/guide me <about you> — what the guide knows about you, /guide forget — forget the conversation.");
        m("topics", "Гиды:", "Guides:");
        m("notopic", "Нет такого гида. Список: /guide topics", "No such guide. The list: /guide topics");
        m("about", "Гид знает о тебе:", "The guide knows about you:");
        m("aboutset", "Запомнил.", "Saved.");
        m("noabout", "Пока ничего. Расскажи: /guide me <кто ты, что строишь, что нравится>",
                "Nothing yet. Tell it: /guide me <who you are, what you build, what you like>");
        m("forgot", "Разговор забыт (профиль остался).", "Conversation forgotten (the profile stays).");
        m("reloaded", "Гиды и промпт перечитаны.", "Guides and the prompt reloaded.");
        m("page", "страница", "page");
        m("took", "Беру у тебя:", "Taking from you:");
        m("brought", "Принёс:", "Brought:");
        m("handed", "Отдал тебе:", "Handed you:");
        m("crafted", "Скрафтил", "Crafted");
        m("notcrafted", "Не вышло скрафтить:", "Could not craft it:");
        m("nothing", "В сундуках этого уже не оказалось.", "It was not in the chests any more.");
        m("gaveup", "Бросил поручение, вещи вернул:", "Gave the errand up, the things went back:");
        m("left", "ты ушёл", "you left");
        m("notable", "верстак убрали", "the crafting table is gone");
        m("short", "по дороге чего-то не хватило", "something was missing on the way");
        m("nogrid", "верстак не принял раскладку", "the crafting table did not take the layout");
        m("ask", "Мне не хватает, а у тебя есть:", "I am short of what you carry:");
        m("askhow", "Взять у тебя? Напиши в чат «да» или «нет».", "May I take it? Say \"yes\" or \"no\" in chat.");
        m("declined", "Хорошо, не беру. Вещи вернул в сундуки; положи недостающее в сундук рядом и попроси снова.",
                "All right, I do not take it. The things went back to the chests; put what is missing into a chest nearby and ask again.");
        m("gochest", "Иди по искрам к сундуку", "Follow the sparks to the chest at");
        m("gotable", "Теперь по искрам к верстаку", "Now follow the sparks to the crafting table at");
        m("blocks", "бл.", "blocks");
        m("gotit", "Взял из сундука тебе:", "Took from the chest for you:");
        m("leaddone", "Готово, всё у тебя.", "Done, you have it all.");
        m("toolong", "слишком долго", "it took too long");
        m("oponly", "Это команда для операторов сервера.", "This command is for server operators.");
    }

    static void m(String k, String ru, String en) {
        MESSAGES.put(k, new String[] { ru, en });
    }

    String msg(String k) {
        String[] v = MESSAGES.get(k);
        return v == null ? k : v[config.ru() ? 0 : 1];
    }

    static String text(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), Knowledge.UTF8);
        } catch (Exception e) {
            return "";
        }
    }

    static String modList() {
        StringBuilder b = new StringBuilder();
        for (ModContainer m : Loader.instance().getActiveModList()) {
            String id = m.getModId();
            if (id.equals("mcp") || id.equals("FML") || id.equals("Forge") || id.equals(ID)) continue;
            if (b.length() > 0) b.append(", ");
            b.append(m.getName()).append(' ').append(m.getVersion());
        }
        return b.toString();
    }
}
