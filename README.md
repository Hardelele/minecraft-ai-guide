# AI Guide

A Forge 1.7.10 server mod: an assistant in the chat that knows your modpack.

*По-русски:* серверный мод для Minecraft 1.7.10 (Forge): ИИ-помощник в чате, который знает вашу сборку.
Пишете `? как сделать охлаждающий стержень`, и он отвечает, сверяясь с самой игрой: названия предметов,
рецепты верстака и машин, ваш инвентарь и сундуки вокруг, гайды сервера. Может принести вещи из ваших
сундуков, скрафтить и показать блок. Отдаёт те же инструменты по MCP для внешних агентов. Язык, модель и API
настраиваются (по умолчанию русский и DeepSeek).

A player writes `? how do I make a 10k coolant cell` (or `/guide ...`) and gets an answer from a language
model that looks things up in the running game instead of guessing:

- **items** by name, in the players' language (names are read from the mods' own language files);
- **recipes** of the crafting table, the furnace and the machines: IndustrialCraft 2, Thermal Expansion,
  EnderIO and Railcraft out of the box; other mods by a line in the config (see *Recipe sources*);
- **the player**: inventory, armor, position and the machines around them, so "what do I do next?" gets an
  answer that fits where they are;
- **storage**: what lies in the chests, barrels, drawers, machines and AE2 networks around the player, with
  coordinates ("where is my copper?");
- **planning**: "do I have enough for two of these?" unfolds the recipes down to what the player has and lists
  the steps in order and what is missing;
- **hands**, only for what the player could do themselves and only when asked. The guide has a body: a
  villager with its name (players see it with nothing installed) that walks to the chests near the player,
  opens them, carries things to a crafting table, crafts one at a time and hands the result over. It takes
  nothing from the player's inventory without asking ("да" / "yes" in chat). It can also show a block with a
  column of sparks;
- **AI Guide Client** (optional, `AIGuideClient-<version>.jar` in the players' `mods`): an autopilot that walks the
  player's own character where the guide leads, opens the chests and the crafting table itself (the crafting
  happens in the table's window), and builds plans: the guide shows blocks to place and dig as see-through
  blocks, asks, and with a "yes" walks there and places and digs them. Any movement key gives the controls back.
  Without it the guide leads with a trail of sparks and the player walks;
- **your guides**: Markdown files you write for your server (`config/aiguide/knowledge`);
- **the player's profile**: what they told about themselves (`/guide me ...`) and notes the assistant keeps.

Questions and answers are private: other players do not see them. The mod is server-side only; players
install nothing.

Any OpenAI-compatible API with tool calls works: DeepSeek (the default), OpenAI, OpenRouter, a local
server (llama.cpp, vLLM, Ollama's OpenAI endpoint). A fast, cheap model is enough: the assistant reads the
facts from the game, the model only has to put them into words.

## Setup

1. Put `AIGuide-<version>.jar` into the server's `mods` folder and start the server once.
2. Put the API key into `config/aiguide/api.key` (one line), or set `AIGUIDE_API_KEY` in the server's
   environment. Choose the API and the model in `config/aiguide.cfg` (`baseUrl`, `model`).
3. Set `language` in `config/aiguide.cfg` (default `ru_RU`; `en_US` for English).
4. A dedicated server has no translations of vanilla items. For their names in your language, copy
   `minecraft/lang/<language>.lang` from a client's assets (`assets/indexes/1.7.10.json` tells which file in
   `assets/objects` it is) into `config/aiguide/lang/`.
5. Write guides for your server into `config/aiguide/knowledge/*.md` (optional, but they make the answers
   much better: how the mods work, where to start, what is changed on your server). The first line,
   `# Title — what it is about`, is what the assistant sees in its list.

## In game

| | |
|---|---|
| `? question` | ask (the prefix is `prefix` in the config) |
| `/guide question` | the same |
| `/guide topics` | the guides of the server |
| `/guide read <topic> [page]` | read a guide in chat, no AI involved |
| `/guide me <text>` | tell the assistant who you are, what you build, how you like answers |
| `/guide me` | what the assistant knows about you |
| `/guide forget` | forget the conversation (the profile stays) |
| `/guide reload` | reread the guides (operators) |
| `/guide learn [mod] [force]` | write guides for the mods that have none, from what the game knows about them (operators) |

## Guides written by the guide itself

`/guide learn` gathers for each mod what the game knows: its items in the players' language, the descriptions and
tooltips of its language file, how its blocks are made, what its items are used for and the server's config of it,
and has a model (`learnModel`, a stronger one: it runs once) write a guide into `config/aiguide/knowledge`. Read
and fix them: they are a start, not the truth. The prompt is `config/aiguide/learn.md`.

## MCP

With `enabled=true` in the `mcp` section, the same tools (plus `list_players` and `tell`) are served over MCP
(Streamable HTTP, JSON responses) at `http://<host>:<port>/mcp`, for agents outside the game: Claude Code, Cursor,
your own scripts. Clients send `Authorization: Bearer <token>`; the token is made at first start in
`config/aiguide/mcp.token`. The tools that are about a player take the player's name. By default it listens on
127.0.0.1 only. For Claude Code:

    claude mcp add --transport http minecraft http://127.0.0.1:25580/mcp --header "Authorization: Bearer <token>"

## Config

`config/aiguide.cfg`:

- `api`: `baseUrl`, `model`, `apiKey` / `apiKeyFile`, `proxy` (`host:port`), `timeoutSeconds`,
  `maxToolRounds`, `maxTokens`, `temperature`, `extraBody` (JSON merged into each request; the default turns
  DeepSeek's thinking off, set `{}` for other APIs), `logTools` (log each lookup).
- `chat`: `prefix`, `language`, `assistantName`, `historyMessages`, `maxLines`, `scanRadius`, `maxNotes`.
- `world`: `storageRadius`, `fetchRadius`, `actions` (the hands on or off).
- `mcp`: `enabled`, `host`, `port`, `tokenFile`.
- `learn`: `learnModel`, `learnExtraBody`, `maxChars`.
- `recipes`: `sources` (below).

`config/aiguide/system.md` is the assistant's instructions; edit it freely (delete it to get the default back).
Placeholders: `{name} {language} {player} {mods} {guides} {about} {notes}`.

`config/aiguide/players/<name>.md`: the player's own text, then the assistant's notes under `## Notes`.

## Recipe sources

Machine recipes are read by reflection, without code for each mod. A source is

    Class#member[#member...]=machine name or its language key[|label][|fluid:in or fluid:out]

The first member is a static field or method of the class, the next ones are members of what it returned.
The value may be a map (input -> output), an array or a collection of recipe objects, or an object with
`getRecipes()`. A recipe object's getters and fields are sorted into inputs and outputs by their names
(`input`, `ingredient`, `additive` / `output`, `result`, `product`; numbers named energy, time or chance become
notes). Examples from the defaults:

    ic2.api.recipe.Recipes#macerator=blockMacerator
    cofh.thermalexpansion.util.crafting.TransposerManager#getFillRecipeList=tile.thermalexpansion.machine.transposer.name|fill|fluid:in
    crazypants.enderio.machine.crusher.CrusherRecipeManager#getInstance#getRecipes=tile.blockSagMill.name

The server log tells how many recipes each source gave.

## Example guides

`examples/knowledge-ru` holds the Russian guides of the server this mod was made for (IndustrialCraft 2
experimental, Thermal Expansion, AE2, EnderIO, Railcraft, Witchery and more, checked against that server's
configs). Use them as a start or as a model for your own; some facts are about that server's settings.

## Build

Python 3 and a JDK (8 or newer):

    python tools/setup_lib.py   # once: downloads Minecraft 1.7.10, Forge 1614, the MCP mappings into lib/
    python tools/setup_mcp.py   # once, for the client mod: the same library with readable MCP names
    python tools/build_client.py   # build/AIGuideClient-<version>.jar
    python tools/build.py       # build/AIGuide-<version>.jar

There is no ForgeGradle: the code uses SRG names (`func_...`, `field_...`) and compiles against the vanilla
client and Forge merged and renamed to SRG names with SpecialSource. Nothing Minecraft's is in this repository.

## License

MIT
