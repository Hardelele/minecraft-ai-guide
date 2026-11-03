# AI Guide — files

- `../aiguide.cfg` — the API (baseUrl, model), the chat prefix, the language, the recipe sources.
- `api.key` — the API key, one line (or `apiKey` in the config, or the `AIGUIDE_API_KEY` environment variable).
- `system.md` — the assistant's instructions. Placeholders: {name} {language} {player} {mods} {guides} {about} {notes}.
  Delete the file to get the default back.
- `knowledge/*.md` — your guides. The first line (`# Title — what it is about`) is what the assistant sees in its list;
  it reads a whole file when it needs it. Players read them with `/guide read <name>`. `/guide reload` rereads them.
- `players/<name>.md` — what the assistant knows about a player: their own text on top (`/guide me ...`), and the
  assistant's notes under `## Notes`. Edit freely.
- `lang/` — extra language files (`<lang>.lang`). A dedicated server has no translations of vanilla items:
  copy `minecraft/lang/<lang>.lang` from a client's assets here to get vanilla names in your language.
