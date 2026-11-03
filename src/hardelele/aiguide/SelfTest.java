package hardelele.aiguide;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;
import net.minecraftforge.oredict.OreDictionary;

/**
 * -Daiguide.selftest=true: runs the tools once the server is up and logs what they return.
 * -Daiguide.ask="question" also sends the question to the API as the player "selftest" and logs the answer.
 */
class SelfTest {
    static void run(AIGuide mod) {
        String[][] calls = {
            { "find_items", "{\"query\":\"стержень 10к\"}" },
            { "how_to_make", "{\"item\":\"Охлаждающий стержень 10к\"}" },
            { "how_to_make", "{\"item\":\"Капсула хладагента\"}" },
            { "what_uses", "{\"item\":\"Лазуритовая пыль\"}" },
            { "how_to_make", "{\"item\":\"Оловянная пластина\"}" },
            { "how_to_make", "{\"item\":\"ME-контроллер\"}" },
            { "find_items", "{\"query\":\"Измельчитель\"}" },
            { "how_to_make", "{\"item\":\"Медная пыль\"}" },
            { "read_guide", "{\"topic\":\"nothing-here\"}" },
        };
        for (String[] c : calls) AIGuide.LOG.info("selftest {} {}:\n{}", c[0], c[1], mod.tools.call(c[0], c[1], "selftest"));
        hands(mod);
        String learn = System.getProperty(AIGuide.ID + ".learn");
        if (learn != null && !learn.isEmpty()) mod.learner.start(learn, true, null);
        String asks = System.getProperty(AIGuide.ID + ".ask");
        if (asks == null || asks.isEmpty()) return;
        // the questions go off the server thread, as in the game, so the tools that need it get it
        final String[] qs = asks.replace('_', ' ').split("\\|\\|");   // "_" for spaces, "||" between questions
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                ask(mod, qs);
            }
        }, "AIGuide-selftest");
        t.setDaemon(true);
        t.start();
    }

    static void ask(AIGuide mod, String[] qs) {
        for (String q : qs) {
            try {
                mod.assistant.forget("selftest");
                AIGuide.LOG.info("selftest answer to \"{}\":\n{}", q, mod.assistant.answer("selftest", q));
            } catch (Exception e) {
                AIGuide.LOG.warn("selftest question failed", e);
            }
        }
        AIGuide.LOG.info("selftest questions done");
    }

    static void log(String what, String result) {
        AIGuide.LOG.info("selftest {}:\n{}", what, result);
    }

    /** a fake player "selftest" at the spawn with a chest beside it: storage, planning and hands */
    static void hands(AIGuide mod) {
        WorldServer w = MinecraftServer.func_71276_C().field_71305_c[0];
        ChunkCoordinates sp = w.func_72861_E();
        int x = sp.field_71574_a, z = sp.field_71573_c, y = w.func_72825_h(x, z) + 2;
        EntityPlayerMP p = FakePlayerFactory.getMinecraft(w);
        p.func_70107_b(x + 0.5, y, z + 0.5);
        AIGuide.extraPlayers.put("selftest", p);
        w.func_147449_b(x + 2, y, z, Blocks.field_150486_ae);
        IInventory chest = (IInventory) w.func_147438_o(x + 2, y, z);
        ItemStack plate = OreDictionary.getOres("plateTin").get(0).func_77946_l();
        plate.field_77994_a = 16;
        chest.func_70299_a(0, plate);
        chest.func_70299_a(1, new ItemStack(Item.func_150898_a(Blocks.field_150344_f), 10));
        Item cell = GameRegistry.findItem("IC2", "itemFluidCell");
        ItemStack full = new ItemStack(cell, 1);
        ((IFluidContainerItem) cell).fill(full, new FluidStack(FluidRegistry.getFluid("ic2coolant"), 1000), true);
        p.field_71071_by.field_70462_a[0] = full;
        log("where_is tin plate", mod.tools.call("where_is", "{\"item\":\"Оловянная пластина\"}", "selftest"));
        log("storage_overview", mod.tools.call("storage_overview", "{}", "selftest"));
        log("plan 2 coolant cells", mod.tools.call("plan_crafting", "{\"item\":\"IC2:reactorCoolantSimple@1\",\"count\":\"2\"}", "selftest"));
        log("plan a Macerator", mod.tools.call("plan_crafting", "{\"item\":\"Дробитель\",\"count\":\"1\"}", "selftest"));
        log("fetch 4 plates", mod.tools.call("fetch", "{\"item\":\"Оловянная пластина\",\"count\":4}", "selftest"));
        log("craft the coolant cell", mod.tools.call("craft", "{\"item\":\"IC2:reactorCoolantSimple@1\",\"count\":1}", "selftest"));
        log("craft sticks (none carried)", mod.tools.call("craft", "{\"item\":\"Палка\",\"count\":4}", "selftest"));
        log("fetch 2 planks", mod.tools.call("fetch", "{\"item\":\"minecraft:planks@0\",\"count\":2}", "selftest"));
        log("craft sticks", mod.tools.call("craft", "{\"item\":\"minecraft:stick@0\",\"count\":4}", "selftest"));
        log("mark the chest", mod.tools.call("mark", "{\"x\":" + (x + 2) + ",\"y\":" + y + ",\"z\":" + z + ",\"seconds\":10}", "selftest"));
        log("player_status after", mod.tools.call("player_status", "{}", "selftest"));
        log("chest after", mod.tools.call("where_is", "{\"item\":\"Оловянная пластина\"}", "selftest"));
    }
}
