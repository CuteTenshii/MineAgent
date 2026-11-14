# MineAgent

A client-side Fabric mod for Minecraft 26.3. You give it an API key for a language model and a goal such as `get 3 iron ingots`, and the model plays your character until the goal is done or it reports why it stopped.

The model never sees the screen. It calls tools that read the world (`observe`, `find_blocks`, ...) and tools that act in it (`walk_to`, `gather`, `craft`, `smelt`, ...). Each action runs tick by tick as normal player input, so it works in singleplayer and on servers without anything installed server-side.

## Requirements

- Minecraft 26.3 with Fabric Loader 0.20.1 or newer
- Fabric API
- Java 25
- An API key for one of the supported providers, or a local model through Ollama

## Setup

1. Put `mineagent-<version>.jar` and Fabric API in your `mods` folder.
2. In game, pick a provider with `/agent provider <id>` (the default is `anthropic`).
3. Run `/agent key` and paste the key into the masked field. It is saved to `config/mineagent.json` (readable only by your user on Linux and macOS). It never goes through chat or command history. Instead of saving it, you can set the provider's environment variable before launching the game.
4. Give a goal: `/agent goal build a crafting table and a wooden pickaxe`.

While the agent works, your movement keys are ignored, and a status line at the top left shows the goal and the current action. The model's short progress notes appear in chat with an `[Agent]` prefix.

While the agent is in control it keeps the player from drowning: between actions it treads water at the surface, and during an action it pauses to swim up for air when air drops below half (mining a block overhead if needed), then carries on. The pathfinder avoids long underwater stretches.

If the player dies during a goal, the agent presses Respawn and tells the model where and how it died and whether it kept its items (read from the `keep_inventory` game rule in singleplayer, and from the respawned inventory on servers), so it can go back for them. In hardcore it stops instead and leaves the death screen to you.

## Commands

| Command | What it does |
| --- | --- |
| `/agent goal <text>` | Start working on a goal |
| `/agent say <text>` | Send extra instructions or a question; the agent reads it after its current action and answers privately in its `[Agent]` lines |
| `/agent stop` | Stop immediately and give control back |
| `/agent status` | Show the goal, current action, tokens used, provider, model and key source |
| `/agent provider <id>` | Switch provider (applies to the next goal) |
| `/agent key` / `/agent key clear` | Enter or remove the saved key for the current provider |
| `/agent model <id>` | Set the model for the current provider |
| `/agent baseurl <url>` / `/agent baseurl reset` | Override the API address for the current provider |
| `/agent effort <low\|medium\|high\|xhigh\|max>` | Thinking effort, Anthropic models only |

## Providers

Anthropic is called through the Messages API (`anthropic-version: 2026-10-21`). Every other provider goes through the OpenAI Chat Completions API, so any service that offers that API works via `custom` with `/agent baseurl`. Both use the JDK's HTTP client and the Gson library Minecraft ships, so the mod has no bundled dependencies.

| id | Default model | Key variable | Notes |
| --- | --- | --- | --- |
| `anthropic` | `claude-opus-5-5` | `ANTHROPIC_API_KEY` | Prompt caching and refusal fallback are turned on |
| `openai` | `gpt-5` | `OPENAI_API_KEY` | |
| `deepseek` | `deepseek-chat` | `DEEPSEEK_API_KEY` | |
| `gemini` | `gemini-2.5-pro` | `GEMINI_API_KEY` | Google's OpenAI-compatible endpoint |
| `openrouter` | none, set one | `OPENROUTER_API_KEY` | Model ids look like `openai/gpt-5` |
| `groq` | none, set one | `GROQ_API_KEY` | |
| `mistral` | `mistral-large-latest` | `MISTRAL_API_KEY` | |
| `xai` | none, set one | `XAI_API_KEY` | |
| `ollama` | none, set one | none | Expects Ollama at `http://localhost:11434/v1` |
| `custom` | none, set one | none | Set `/agent baseurl` to a URL ending before `/chat/completions` |

Default models are starting points; set any model your provider serves with `/agent model`. The model has to support tool calling. Small local models often call tools poorly, so expect weaker results from them.

## What the agent can do

When the player is hurt during an action, its result says how much damage was taken, from what, and the attacker's entity id (for example `Took 8 damage during this action (zombie #19 x2, fall x1); health now 18/20.`). Every tool result also ends with a `Warning:` line when something needs attention: health or food at half or below, low air, burning, or a harmful status effect such as poison or wither.

| Tool | Behaviour |
| --- | --- |
| `observe` | Position, health, food, status effects, time, held item, armor, inventory, nearby entities, block types within 8 blocks |
| `find_blocks`, `find_entities`, `inspect_block` | Search loaded chunks by id or tag (`#minecraft:logs`) |
| `recipes` | Unlocked crafting recipes for an item and whether the inventory can make them |
| `walk_to` | A* pathfinding that walks, jumps, drops up to 3 blocks, swims, breaks blocks in the way, and pillars up with dirt, cobblestone or planks |
| `mine_block`, `gather` | Break one block, or several of a kind, with the best tool carried, then pick up drops |
| `place_block`, `use_block` | Place a block against a solid neighbour; right-click a block in clear view with the held item: doors, buttons, beds, filling or emptying buckets, bone meal |
| `craft` | Uses the recipe book; 3x3 recipes walk to a crafting table within 24 blocks |
| `smelt` | Loads a furnace, smoker or blast furnace with the exact count plus enough fuel and collects the output |
| `container` | List a chest or other container, or move stacks in or out |
| `equip`, `use_item`, `eat`, `drop_item` | Hold or wear items, right-click, eat, throw items |
| `attack`, `collect_items` | Melee mobs by id, type or "any hostile"; pick up dropped items |
| `wait` | Up to 60 seconds |

## Limits

- Only loaded chunks can be searched, so the agent has to walk to find distant things.
- The pathfinder will not break blocks next to water or lava, blocks with a block entity (chests, furnaces), or anything harder than 30 (obsidian, for example). It avoids lava, fire, magma, cactus and powder snow.
- `container` moves whole stacks, so `count` is a minimum, not an exact amount.
- In singleplayer the game pauses when its window loses focus, and the agent pauses with it. Turn off "Pause on lost focus" (F3 + P) if you want it to keep going in the background.
- Each goal is capped at 200 model turns (`maxTurns` in `config/mineagent.json`). Long goals can use a lot of tokens; `/agent status` shows the running total.

## Configuration file

`config/mineagent.json`:

```json
{
  "provider": "anthropic",
  "apiKeys": { "anthropic": "..." },
  "models": { "openai": "gpt-5" },
  "baseUrls": { "ollama": "http://192.168.1.20:11434/v1" },
  "effort": "medium",
  "maxTurns": 200,
  "maxTokens": 16000
}
```

`maxTokens` and `effort` only apply to Anthropic; other providers use their own output limit.

## Building

```sh
./gradlew build                 # jar in build/libs/
./gradlew runClient             # dev client
./gradlew runClientGameTest     # in-game tests
```

The client game test creates a singleplayer world and runs the real tasks: pathing around a wall, mining, crafting in the inventory grid and at a placed table, gathering stone, smelting iron, a chest round trip, killing a pig and eating. It then runs the full agent loop twice against local mock servers, once with the OpenAI-compatible backend and once with the Anthropic backend, and checks that the player does not drown while idle, holding still or crossing a pool, that damage taken during an action is reported and that a death mid-goal ends in a respawn with the model informed. It needs no API key or network access. On a machine without a display, run it under `xvfb-run`.

## License

MIT, see [LICENSE](LICENSE).
