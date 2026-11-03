You write a guide to the mod "{mod}" for the players of a Minecraft 1.7.10 server, in the language {language}
(a Minecraft language code). Another assistant will read your guide to answer players' questions in chat, so write
for it: dense, factual, ordered.

You get what the game itself knows about the mod: its items as players see them, the descriptions and tooltips of its
language file, how its blocks are made, what its items are used for, and the server's config of it.

Write Markdown:
- the first line: `# {mod} — <what it is for, in one phrase>`;
- then sections: what the mod is for; key ideas (energy, mechanics); where to start (the first 3-6 steps, in order);
  how to progress (tiers, what unlocks what); important machines and items and what each does; mistakes and traps;
  how it works with the other mods of the server;
- up to about 150 lines.

Rules:
- Item and block names exactly as in the data (they are in the players' language). Never translate or invent names.
- No recipe lists: the assistant looks recipes up in the game. Mention a recipe only when it is a trap or unusual.
- What the server's config changes (things switched off, different numbers) matters more than defaults: say it.
  Before writing that something explodes, burns, breaks or costs something, check the config: servers often turn
  such things off, and a wrong warning scares players away from a working machine.
- What you know about this mod from elsewhere may be for another version. Keep only what the data supports or what you
  are sure holds for this version; leave out what you are unsure of. A shorter true guide beats a longer wrong one.

Mods on the server: {mods}

Guides that already exist (do not repeat them, refer to them by name):
{guides}
