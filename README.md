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

While the agent works, your movement keys are ignored, and a status box at the top left shows the goal, the current action and the tokens used so far. The model's short progress notes appear in chat with an `[Agent]` prefix. With `/agent toolchat on`, every tool call is also posted in chat as it starts (`▸ gather block=#minecraft:logs count=5`), followed by a one-line result marked ✓ or ✗; hover over the result to read it in full.

While the agent walks, its path is drawn in the world: a cyan line to the destination (outlined in green), red outlines on blocks it will break, and yellow where it will pillar up. The line shows through terrain; turn it off with `/agent showpath off`.

While the agent is in control it keeps the player from drowning: between actions it treads water at the surface, and during an action it pauses to swim up for air when air drops below half (mining a block overhead if needed), then carries on. The pathfinder avoids long underwater stretches.

If the player dies during a goal, the agent presses Respawn and tells the model where and how it died and whether it kept its items (read from the `keep_inventory` game rule in singleplayer, and from the respawned inventory on servers), so it can go back for them. In hardcore it stops instead and leaves the death screen to you.

## Commands

| Command | What it does |
| --- | --- |
| `/agent goal <text>` | Start working on a goal |
| `/agent say <text>` | Send extra instructions or a question; the agent reads it after its current action and answers privately in its `[Agent]` lines |
| `/agent stop` | Stop immediately and give control back |
| `/agent continue [turns]` | Resume a goal that paused at its turn limit, for the default number of turns or the number given |
| `/agent status` | Show the goal, current action, tokens used, provider, model and key source |
| `/agent provider <id>` | Switch provider (applies to the next goal) |
| `/agent key` / `/agent key clear` | Enter or remove the saved key for the current provider |
| `/agent model <id>` | Set the model for the current provider |
| `/agent baseurl <url>` / `/agent baseurl reset` | Override the API address for the current provider |
| `/agent showpath <on\|off>` | Draw the path the agent is walking (on by default) |
| `/agent toolchat <on\|off>` | Post every tool call and its result in chat (off by default) |
| `/agent commands <on\|off>` | Let the agent run server commands (off by default; see below) |
| `/agent vision <on\|off>` | Offer the `look` screenshot tool for the current provider (see below) |
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

When a mob hits the player while the agent is in control, the player fights back on its own: it pauses the running action, takes out its best sword or axe, hits the attackers (and any other hostile mob within 4 blocks) and chases them up to 10 blocks, then resumes the action. This also happens between actions, while the model is thinking. The action result names who was fought and how each fight ended (`Fought back during this action: zombie #19 (killed).`). If health drops to 6 or below, the fight lasts more than 20 seconds, or an archer can't be reached, the action is interrupted and the model decides what to do; automatic fighting then stays out of its next action so it can flee or eat. It never fights back against players, and it leaves the `attack` tool to do its own fighting.

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
| `stonecut` | Picks a carried block that makes the requested stairs, slab, wall or other shape and cuts just enough of it |
| `smith` | Netherite upgrades and armor trims at a smithing table |
| `anvil` | Combines an item with another of its kind or an enchanted book, repairs it with up to 4 units of its material, renames it; refuses when the player can't pay the levels |
| `enchant` | Lists an enchanting table's three offers with level costs and one hinted enchantment each, or takes offer 1-3 |
| `grindstone` | Removes enchantments, or merges two damaged items of the same kind |
| `brew` | Loads up to 3 bottles (optionally only those holding a given potion, such as `water` or `awkward`) and one ingredient, adds blaze powder if the stand has no fuel, waits for the brew and collects the bottles |
| `container` | List a chest or other container, or move stacks in or out |
| `inventory` | List every slot; move an item to a given hotbar, inventory or offhand slot; or tidy up (merge partial stacks and put tools, food, torches and blocks in fixed hotbar slots) |
| `equip`, `use_item`, `eat`, `drop_item` | Hold or wear items, right-click, eat, throw items |
| `attack`, `collect_items` | Melee mobs by id, type or "any hostile"; pick up dropped items |
| `fly_to`, `fly` | Fly through open air along an A* route, then land or hover; take off or land in place |
| `give_item` | Creative mode only: put any item from the creative menu into a free slot or a named one |
| `run_command` | Run a server command as the player and return the server's replies; offered only after `/agent commands on` |
| `look` | Screenshot of the player's view with the HUD hidden, optionally after turning to face a block; offered when vision is on |
| `wait` | Up to 60 seconds |

### Flight, creative items and commands

`observe` reports what the player is allowed to do: whether it can fly, whether creative items are available, its command permission level, and whether `run_command` is on.

`fly_to` works whenever the player may fly: in creative or spectator mode, or on a server that grants flight. It flies with the same jump and sneak input a player uses, avoids lava and fire, and never breaks blocks. It lands on the ground below the destination unless `land=false`, which leaves the player hovering, for example to build in the air. `walk_to`, and any action that has to walk somewhere, descends to the ground first. If there is no safe ground within 64 blocks below, it refuses instead of dropping the player.

`give_item` sends the same packet as the creative inventory screen, so it only works in creative mode and for items enabled in the world.

`run_command` is off until you type `/agent commands on`, and it is added to the model's tools from the next goal on. Turning it off takes effect immediately. With commands available, the system prompt tells the model to build and clear areas with `fill` and `setblock` instead of placing and mining blocks one at a time. Before sending, the command is checked against the command list the server sent for this player, so a command the player has no permission for is refused without being sent. Client-side mod commands such as `/agent` are always refused. The system prompt tells the model not to use commands against other players, to change operators, bans or server settings, or to chat as the player unless you ask for that, but an operator account still lets it run anything the server allows, so only enable it where that is acceptable.

### Screenshots

`look` captures one frame with the HUD hidden, scales it to 1024 pixels wide and sends it to the model as a JPEG, along with where the player stands, which way it faces and which block is at the center of the image. Anthropic gets the image inside the tool result. The OpenAI-compatible API does not allow images in tool results, so the result says an image follows and the image goes in a user message right after. Only the 3 newest screenshots stay in the conversation; older ones are replaced with a short note, since every request resends the whole history.

The model has to accept images. `look` is offered by default for `anthropic`, `openai`, `gemini` and `openrouter`, and not for the rest, whose models are often text-only. `/agent vision on` or `off` changes this for the current provider from the next goal on. Each screenshot costs roughly 1,000 to 1,500 input tokens.

## Limits

- Only loaded chunks can be searched, so the agent has to walk to find distant things.
- The pathfinder will not break blocks next to water or lava, blocks with a block entity (chests, furnaces), or anything harder than 30 (obsidian, for example). It avoids lava, fire, magma, cactus and powder snow.
- `container` moves whole stacks, so `count` is a minimum, not an exact amount.
- In singleplayer the game pauses when its window loses focus, and the agent pauses with it. Turn off "Pause on lost focus" (F3 + P) if you want it to keep going in the background.
- After 200 model turns (`maxTurns` in `config/mineagent.json`) a goal pauses and gives you control back; click [Continue] in chat (or type `/agent continue`) to resume with the conversation intact, or [Stop] to end it. Long goals can use a lot of tokens; `/agent status` and the pause message show the running total.

## Configuration file

`config/mineagent.json`:

```json
{
  "provider": "anthropic",
  "apiKeys": { "anthropic": "..." },
  "models": { "openai": "gpt-5" },
  "baseUrls": { "ollama": "http://192.168.1.20:11434/v1" },
  "effort": "medium",
  "allowCommands": false,
  "vision": { "ollama": true },
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

The client game test creates a singleplayer world and runs the real tasks: pathing around a wall, mining, crafting in the inventory grid and at a placed table, gathering stone, smelting iron, a chest round trip, killing a pig and eating. In creative mode it takes items with `give_item`, flies over a wall and lands, and runs commands with `run_command`. It uses a stonecutter, smithing table, anvil, enchanting table, grindstone and brewing stand with real items, and takes a screenshot with `look`. It then runs the full agent loop twice against local mock servers, once with the OpenAI-compatible backend and once with the Anthropic backend (each also checking how a screenshot is sent), and checks that the player does not drown while idle, holding still or crossing a pool, that damage taken during an action is reported and that a death mid-goal ends in a respawn with the model informed. It needs no API key or network access. On a machine without a display, run it under `xvfb-run`.

## License

MIT, see [LICENSE](LICENSE).
