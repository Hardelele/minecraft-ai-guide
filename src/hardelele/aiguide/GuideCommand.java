package hardelele.aiguide;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

/**
 * /guide (/гид): a question to the assistant, or
 * topics | read <topic> [page] | me [about you] | forget | reload (operators).
 */
class GuideCommand extends CommandBase {
    static final int PAGE = 15;   // lines of a guide per page
    final AIGuide mod;

    GuideCommand(AIGuide mod) {
        this.mod = mod;
    }

    @Override public String func_71517_b() { return "guide"; }

    @Override public List func_71514_a() { return Arrays.asList("гид", "aiguide"); }

    @Override public String func_71518_a(ICommandSender s) { return "/guide <question> | topics | read <topic> [page] | me [text] | forget"; }

    @Override public int func_82362_a() { return 0; }

    @Override public boolean func_71519_b(ICommandSender s) { return true; }

    @Override public int compareTo(Object o) {
        return o instanceof net.minecraft.command.ICommand ? func_71517_b().compareTo(((net.minecraft.command.ICommand) o).func_71517_b()) : 0;
    }

    static void tell(ICommandSender s, String text) {
        for (String l : text.split("\n")) s.func_145747_a(new ChatComponentText(l));
    }

    @Override public void func_71515_b(ICommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        String rest = a.length > 1 ? join(a, 1) : "";
        if (sub.isEmpty() || sub.equals("help")) {
            tell(s, "§b" + mod.msg("help"));
        } else if (sub.equals("topics")) {
            StringBuilder b = new StringBuilder("§b" + mod.msg("topics") + "§r");
            for (Map.Entry<String, String> e : mod.knowledge.titles.entrySet())
                b.append("\n§e").append(e.getKey()).append("§r — ").append(e.getValue());
            tell(s, b.toString());
        } else if (sub.equals("read") && a.length > 1) {
            String topic = mod.knowledge.find(a[1]);
            String text = topic == null ? null : mod.knowledge.read(topic);
            if (text == null) { tell(s, mod.msg("notopic")); return; }
            String[] lines = AIGuide.format(text).split("\n");
            int pages = (lines.length + PAGE - 1) / PAGE;
            int page = 1;
            try {
                if (a.length > 2) page = Math.max(1, Math.min(pages, Integer.parseInt(a[2])));
            } catch (NumberFormatException e) {
                // the first page
            }
            StringBuilder b = new StringBuilder();
            for (int i = (page - 1) * PAGE; i < Math.min(lines.length, page * PAGE); i++) b.append(lines[i]).append('\n');
            b.append("§7").append(mod.msg("page")).append(' ').append(page).append('/').append(pages)
                    .append(page < pages ? "  /guide read " + topic + " " + (page + 1) : "");
            tell(s, b.toString());
        } else if (sub.equals("me")) {
            String name = s.func_70005_c_();
            if (rest.isEmpty()) {
                String about = mod.profiles.read(name);
                tell(s, about.isEmpty() ? mod.msg("noabout") : "§b" + mod.msg("about") + "§r\n" + about);
            } else {
                mod.profiles.setAbout(name, rest);
                tell(s, "§b" + mod.msg("aboutset"));
            }
        } else if (sub.equals("forget")) {
            mod.assistant.forget(s.func_70005_c_());
            tell(s, "§b" + mod.msg("forgot"));
        } else if (sub.equals("reload") && (!(s instanceof EntityPlayerMP) || s.func_70003_b(2, "guide"))) {
            mod.knowledge.load();
            tell(s, "§b" + mod.msg("reloaded"));
        } else if (sub.equals("learn") && (!(s instanceof EntityPlayerMP) || s.func_70003_b(2, "guide"))) {
            // /guide learn [all | <mod id or name>] [force]
            String which = a.length > 1 && !a[1].equalsIgnoreCase("force") ? a[1] : "all";
            boolean force = a[a.length - 1].equalsIgnoreCase("force");
            mod.learner.start(which, force, s instanceof EntityPlayerMP ? s.func_70005_c_() : null);
            if (!(s instanceof EntityPlayerMP)) tell(s, "learning in the background, see the log");
        } else if ((sub.equals("learn") || sub.equals("reload")) && a.length <= 3) {
            tell(s, "§7" + mod.msg("oponly"));   // not a question to the model
        } else if (s instanceof EntityPlayerMP) {
            mod.ask((EntityPlayerMP) s, join(a, 0));
        }
    }

    static String join(String[] a, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < a.length; i++) b.append(i > from ? " " : "").append(a[i]);
        return b.toString();
    }
}
