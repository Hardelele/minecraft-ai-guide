package hardelele.aiguide;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import net.minecraftforge.common.config.Configuration;

/** config/aiguide.cfg, plus the files in config/aiguide. */
class GuideConfig {
    static final String[] SOURCES = {
        // IndustrialCraft 2
        "ic2.api.recipe.Recipes#macerator=blockMacerator",
        "ic2.api.recipe.Recipes#extractor=blockExtractor",
        "ic2.api.recipe.Recipes#compressor=blockCompressor",
        "ic2.api.recipe.Recipes#centrifuge=blockCentrifuge",
        "ic2.api.recipe.Recipes#blockcutter=blockBlockCutter",
        "ic2.api.recipe.Recipes#blastfurance=blockBlastFurnace",
        "ic2.api.recipe.Recipes#oreWashing=blockOreWashingPlant",
        "ic2.api.recipe.Recipes#metalformerExtruding=blockMetalFormer|MetalFormer.gui.switch.Extruding",
        "ic2.api.recipe.Recipes#metalformerRolling=blockMetalFormer|MetalFormer.gui.switch.Rolling",
        "ic2.api.recipe.Recipes#metalformerCutting=blockMetalFormer|MetalFormer.gui.switch.Cutting",
        "ic2.api.recipe.Recipes#cannerBottle=blockCanner|Canner.gui.switch.BottleSolid",
        "ic2.api.recipe.Recipes#cannerEnrich=blockCanner|Canner.gui.switch.EnrichLiquid",
        // Thermal Expansion
        "cofh.thermalexpansion.util.crafting.PulverizerManager#getRecipeList=tile.thermalexpansion.machine.pulverizer.name",
        "cofh.thermalexpansion.util.crafting.SmelterManager#getRecipeList=tile.thermalexpansion.machine.smelter.name",
        "cofh.thermalexpansion.util.crafting.SawmillManager#getRecipeList=tile.thermalexpansion.machine.sawmill.name",
        "cofh.thermalexpansion.util.crafting.FurnaceManager#getRecipeList=tile.thermalexpansion.machine.furnace.name",
        "cofh.thermalexpansion.util.crafting.TransposerManager#getFillRecipeList=tile.thermalexpansion.machine.transposer.name|fill|fluid:in",
        "cofh.thermalexpansion.util.crafting.TransposerManager#getExtractionRecipeList=tile.thermalexpansion.machine.transposer.name|extract|fluid:out",
        "cofh.thermalexpansion.util.crafting.CrucibleManager#getRecipeList=tile.thermalexpansion.machine.crucible.name",
        "cofh.thermalexpansion.util.crafting.InsolatorManager#getRecipeList=tile.thermalexpansion.machine.insolator.name",
        "cofh.thermalexpansion.util.crafting.ChargerManager#getRecipeList=tile.thermalexpansion.machine.charger.name",
        "cofh.thermalexpansion.util.crafting.PrecipitatorManager#getRecipeList=tile.thermalexpansion.machine.precipitator.name",
        "cofh.thermalexpansion.util.crafting.ExtruderManager#getRecipeList=tile.thermalexpansion.machine.extruder.name",
        // EnderIO
        "crazypants.enderio.machine.crusher.CrusherRecipeManager#getInstance#getRecipes=tile.blockSagMill.name",
        "crazypants.enderio.machine.alloy.AlloyRecipeManager#getInstance#getRecipes=tile.blockAlloySmelter.name",
        "crazypants.enderio.machine.vat.VatRecipeManager#getInstance#getRecipes=tile.blockVat.name",
        "crazypants.enderio.machine.slicensplice.SliceAndSpliceRecipeManager#getInstance#getRecipes=tile.blockSliceAndSplice.name",
        // Railcraft
        "mods.railcraft.api.crafting.RailcraftCraftingManager#cokeOven=tile.railcraft.machine.alpha.coke.oven.name",
        "mods.railcraft.api.crafting.RailcraftCraftingManager#blastFurnace=tile.railcraft.machine.alpha.blast.furnace.name",
        "mods.railcraft.api.crafting.RailcraftCraftingManager#rockCrusher=tile.railcraft.machine.alpha.rock.crusher.name",
        "mods.railcraft.api.crafting.RailcraftCraftingManager#rollingMachine#getRecipeList=tile.railcraft.machine.alpha.rolling.machine.name",
    };

    final File dir;
    String baseUrl, model, apiKey, apiKeyFile, proxy, prefix, language, assistantName, extraBody;
    int timeoutSeconds, maxToolRounds, maxTokens, historyMessages, scanRadius, maxNotes, maxLines;
    double temperature;
    boolean logTools, actions, mcpEnabled, areaBrief, labelUnknown;
    int areaRadius;
    int storageRadius, fetchRadius, mcpPort, learnMaxChars, learnMaxTokens;
    String mcpHost, mcpTokenFile, learnModel, learnExtraBody;
    List<String> sources;

    GuideConfig(File configDir) {
        dir = new File(configDir, "aiguide");
        dir.mkdirs();
        Configuration c = new Configuration(new File(configDir, "aiguide.cfg"));
        c.load();
        baseUrl = c.getString("baseUrl", "api", "https://api.deepseek.com", "An OpenAI-compatible API (DeepSeek, OpenAI, OpenRouter, a local server...): <baseUrl>/chat/completions");
        model = c.getString("model", "api", "deepseek-flash", "The model; it must support tool calls");
        apiKey = c.getString("apiKey", "api", "", "The API key. Better leave it empty and use apiKeyFile or the AIGUIDE_API_KEY environment variable");
        apiKeyFile = c.getString("apiKeyFile", "api", "config/aiguide/api.key", "A file holding the API key (one line)");
        proxy = c.getString("proxy", "api", "", "host:port of an HTTP proxy, if the API is only reachable through one");
        timeoutSeconds = c.getInt("timeoutSeconds", "api", 60, 5, 600, "How long to wait for one answer of the API");
        maxToolRounds = c.getInt("maxToolRounds", "api", 8, 1, 30, "Lookups the assistant may make for one question");
        maxTokens = c.getInt("maxTokens", "api", 2000, 100, 32000, "The longest answer, in tokens");
        temperature = c.get("api", "temperature", 0.3, "Randomness of the answers").getDouble();
        extraBody = c.getString("extraBody", "api", "{\"thinking\":{\"type\":\"disabled\"}}",
                "JSON merged into every request, for options of your API. The default turns DeepSeek's thinking off (much faster answers); "
                + "set {} for APIs that do not know it");
        logTools = c.getBoolean("logTools", "api", false, "Log every lookup the assistant makes (to see why an answer is wrong)");
        prefix = c.getString("prefix", "chat", "?", "A chat message starting with this is a question to the assistant (and is not shown to others)");
        language = c.getString("language", "chat", "ru_RU", "The players' language: item names come from the mods' files for it, and the assistant answers in it");
        assistantName = c.getString("assistantName", "chat", "", "The assistant's name in chat (empty: Гид for ru_*, Guide otherwise)");
        historyMessages = c.getInt("historyMessages", "chat", 12, 0, 100, "Messages of the conversation the assistant remembers per player");
        maxLines = c.getInt("maxLines", "chat", 25, 3, 200, "The longest answer in chat lines");
        scanRadius = c.getInt("scanRadius", "chat", 12, 0, 64, "How far around the player the assistant looks for machines");
        maxNotes = c.getInt("maxNotes", "chat", 30, 0, 500, "Notes the assistant keeps about each player");
        storageRadius = c.getInt("storageRadius", "world", 48, 0, 256, "How far around the player the assistant looks into chests, barrels, drawers and AE2 networks");
        fetchRadius = c.getInt("fetchRadius", "world", 16, 0, 64, "How far the assistant may bring things from (0: it never takes anything)");
        actions = c.getBoolean("actions", "world", true, "Let the assistant act for the player: bring things from inventories near them, craft from what they carry, mark blocks");
        areaBrief = c.getBoolean("areaBrief", "world", true, "Give the assistant a short description of the area around the player with every question "
                + "(trees, ores, dangers, the way out, what is built; with distances on foot). Answers about the world get much better");
        areaRadius = c.getInt("areaRadius", "world", 48, 16, 64, "How far around the player that description looks");
        labelUnknown = c.getBoolean("labelUnknown", "world", true, "Once, ask the model what the blocks are that the rules cannot tell (ore, wood, machine...), "
                + "by their names; kept in config/aiguide/labels-llm.json. Costs a fraction of a cent for a modpack");
        mcpEnabled = c.getBoolean("enabled", "mcp", false, "Serve the assistant's tools over MCP (Streamable HTTP), for agents outside the game");
        mcpHost = c.getString("host", "mcp", "127.0.0.1", "Where MCP listens: 127.0.0.1 for this computer only, 0.0.0.0 for everyone (then keep the token secret)");
        mcpPort = c.getInt("port", "mcp", 25580, 1, 65535, "The MCP port; the endpoint is http://<host>:<port>/mcp");
        mcpTokenFile = c.getString("tokenFile", "mcp", "config/aiguide/mcp.token", "The bearer token clients must send; made at first start if missing");
        learnModel = c.getString("learnModel", "learn", "deepseek-v4-pro", "The model /guide learn writes guides with (a stronger one is worth it: it runs once)");
        learnExtraBody = c.getString("learnExtraBody", "learn", "{}", "JSON merged into /guide learn requests");
        learnMaxTokens = c.getInt("maxTokens", "learn", 32000, 1000, 200000, "The longest answer of the learn model, thinking included");
        learnMaxChars = c.getInt("maxChars", "learn", 60000, 5000, 500000, "How much of a mod's data /guide learn sends to the model at most");
        sources = Arrays.asList(c.getStringList("sources", "recipes", SOURCES,
                "Where machine recipes are: Class#member[#member...]=machine name (or its language key)[|label][|fluid:in or fluid:out]. "
                + "The value is a map, an array or a list of recipes, or has getRecipes()."));
        if (assistantName.isEmpty()) assistantName = ru() ? "Гид" : "Guide";
        if (c.hasChanged()) c.save();
    }

    boolean ru() {
        return language.toLowerCase().startsWith("ru");
    }

    String key() {
        if (!apiKey.isEmpty()) return apiKey;
        String env = System.getenv("AIGUIDE_API_KEY");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        try {
            return new String(Files.readAllBytes(new File(apiKeyFile).toPath()), Knowledge.UTF8).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
