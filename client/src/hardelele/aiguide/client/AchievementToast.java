package hardelele.aiguide.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;

/**
 * An agent's new achievement shown in a game with a window (for the film): the game's own toast «Новое достижение!»
 * with the achievement's name, and a chat line with the agent's name. On a dedicated server (the learning
 * environment) nothing: the client's classes are touched only on the client's side (Shown).
 */
final class AchievementToast {
    private AchievementToast() { }

    static void show(String agent, String id) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        try {
            Shown.show(agent, id);
        } catch (Throwable t) {
            Events.log("rl: the toast of " + id + " not shown: " + t);
        }
    }

    /** the client's part (loaded only on the client) */
    static final class Shown {
        static void show(final String agent, final String id) {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null) return;
            mc.addScheduledTask(new Runnable() {
                public void run() {
                    for (Object o : net.minecraft.stats.AchievementList.achievementList) {
                        net.minecraft.stats.Achievement a = (net.minecraft.stats.Achievement) o;
                        if (!a.statId.equals("achievement." + id)) continue;
                        mc.guiAchievement.displayAchievement(a);
                        if (mc.thePlayer != null) {
                            RlAchievements.Ach x = RlAchievements.ALL.get(id);
                            mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(agent + " получил достижение «" + (x == null ? id : x.ru) + "»"));
                        }
                        return;
                    }
                }
            });
        }
    }
}
