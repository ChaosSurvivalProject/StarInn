# StarDome Inn · Newcomer Onboarding Plugin (StarInn)

> 中文文档见 [README.md](README.md)。This is the English version of the documentation.

A single-plugin fusion build of the newcomer onboarding experience: Java players
(see dialogue line-by-line on a BossBar) and Bedrock players (connected via Geyser,
using ActionBar + form/chat options) behave identically. Progress is persisted in
SQLite. The resource pack's **colored character art is baked into the jar as real
PNGs**; at runtime the plugin only performs four steps — "extract → overwrite →
package → backfill SHA-256" — and serves the result through a **built-in HTTP
server** (port fully configurable, with zero hardcoded ports in the code).

- **Server:** Purpur 1.21.11 (Paper/Spigot of the same version also work)
- **Java:** 21 (required)
- **Dependencies:** **None.** No LuckPerms / PlaceholderAPI / ProtocolLib / Adventure /
  Vault. Geyser is only an optionally detected component; the plugin runs fully
  without it (rendered entirely on the Java side). **No component is installed on
  the Velocity side.**
- **Network:** **Zero outbound requests.** The built-in HTTP server only *passively
  serves local files* and never downloads from any external address.
- **Architecture:** a single main class `com.starinn.StarInn` with nested classes
  (Config / Database / Dialogue / Node / Script / Session / Renderer /
  BossBarRenderer / ActionBarRenderer / PackBuilder / PackServer / SelfCheck) —
  one jar does everything.
- **Robustness:** a per-item startup self-check with automatic degradation. Any
  single failure only disables that one feature — **it never affects other parts of
  the server, never causes plugin startup to fail, and never blocks players.**

---

## 1. Installation

1. Put `StarInn-1.0.0.jar` into `plugins/` and restart the server;
2. On first startup `plugins/StarInn/` is auto-generated: `config.yml`,
   `dialogues/` (three dialogue files), `textures/` (empty folder for your official
   art), `StarInnResourcePack.zip`, `guides.db`;
3. The log prints
   `[星穹旅驿] 资源包下发地址：http://xxx:port/StarInnResourcePack.zip`
   and `[星穹旅驿] 自检完成：正常 X 项 / 降级 Y 项`;
4. Allow the configured port in your firewall/security group; run `/starinn reload`
   in-game, then join and you'll see the guidance.

The distribution package (this repo's `dist/`) additionally provides:

| File | Purpose |
|---|---|
| `StarInn-1.0.0.jar` | The plugin itself, into `plugins/` |
| `resource-pack/StarInnResourcePack.zip` | Prebuilt Java resource pack (same structure as the one generated at runtime, pack_format 75, supports 1.21.9~1.21.11) |
| `bedrock/StarInnResourcePack.mcpack` | Bedrock resource pack (with manifest, double-click to import; Geyser players may also receive it auto-converted from the server) |
| `textures/*.png` | Source art/background PNGs baked into the jar (all power-of-two, standard PNG) |

## 2. Resource Pack & Port Notes (Important)

### 2.1 Colored art is baked into the jar; no live drawing at runtime

The art PNGs live under `src/main/resources/resourcepack/` and are packaged into the
jar at build time:

```
resourcepack/
  pack.mcmeta                                  pack_format 75 (1.21.11), supports 70~75
  pack.png                                     64x64 pack icon
  assets/starinn/textures/gui/dialogue_bg.png   512x256 dialogue background
  assets/starinn/textures/gui/wu|chen|an.png    64x64 three colored character portraits
  assets/starinn/textures/gui/bg_*.png          64x64 three scene backgrounds
```

`PackBuilder` performs only four steps: ① release `resourcepack/` from the jar to a
temp dir; ② if a server owner placed a same-named PNG under `plugins/StarInn/textures/`,
overwrite it; ③ package as `plugins/StarInn/StarInnResourcePack.zip`; ④ compute the
SHA-256 and write it back into `config.yml`'s `resource-pack.sha256` (Chinese comments
preserved). On later startups: if the zip exists and its hash matches the config,
generation is skipped; otherwise it is rebuilt automatically.

**To swap in official art later:** drop a same-named PNG into
`plugins/StarInn/textures/` and run `/starinn pack regen` — no recompile needed.
Textures must be standard PNGs with power-of-two dimensions (64/128/256/512…);
non-compliant ones are logged and ignored (to keep Geyser transcoding safe on
Bedrock). Keep the total resource-pack size under 5 MB; do not include font /
shader / optifine directories. A resource-pack generation failure only logs and
continues startup — guidance works normally (just without art).

### 2.2 Port is fully configurable, zero hardcoded in code

There are no port number literals anywhere in the Java source; the delivery URL is
always assembled from config:

```
http://<public-host>:<builtin-port><serve-path>
```

The four `config.yml` items take strict effect:

| Config item | Description |
|---|---|
| `resource-pack.builtin-port` | Port the built-in HTTP server listens on. **Change this once you obtain a port**, and allow it in the firewall/security group |
| `resource-pack.builtin-host` | Listen address, default `0.0.0.0` (all interfaces) |
| `resource-pack.serve-path` | Download path, default `/StarInnResourcePack.zip` |
| `resource-pack.public-host` | Public IP or domain; **leave empty to auto-use the server IP**; **Bedrock players must be able to reach this address** |

- After changing the port, `/starinn reload` applies it immediately (stops the old
  listener, reopens with the new config);
- Port occupied: a `BindException` is caught and logged
  ("port X is occupied, please change resource-pack.builtin-port in config.yml");
  the plugin keeps starting and guidance works, only resource-pack delivery is off;
- `onDisable` always closes the HttpServer and releases the port;
- The startup log prints the full delivery URL (see Installation step 3).

## 3. Startup Self-Check & Automatic Degradation

At startup each item is self-checked and the log prints a summary
`[星穹旅驿] 自检完成：正常 X 项 / 降级 Y 项`, with degraded items listed one by one.
The six self-check items and their degradation behavior:

| Self-check item | On failure |
|---|---|
| Config file (config.yml) | Fall back to built-in defaults; log the exact SnakeYAML line/column |
| Dialogue lines (all 22 present) | Skip missing nodes; remaining guidance works (options pointing to missing nodes end gracefully, no soft-lock) |
| Database persistence | Degrade to in-memory progress (not persisted), log a warning |
| Resource-pack generation | Turn off resource-pack delivery; guidance works (just without art) |
| HTTP port delivery | Invalid/occupied port → turn off delivery; guidance works |
| Geyser Bedrock bridge | Not detected / interface mismatch → render entirely on Java side, works normally |

Any single failure **only disables that feature** — it never causes plugin startup
failure and never affects other parts of the server.

## 4. Commands

| Command | Permission | Function |
|---|---|---|
| `/starinn reload` | `starinn.reload` | Hot-reload config and dialogues without kicking players; port changes auto-restart the built-in server and print the new address; in-progress sessions rebind to the new script |
| `/starinn reset <player>` | `starinn.reset` | Clear that player's progress; guidance restarts on next join |
| `/starinn status` | `starinn.reload` | Plugin status: listen port, public-host, actual delivery URL, sha256, database, session stats, line count |
| `/starinn pack regen` | `starinn.reload` | Force-delete old zip → reassemble → recompute and backfill sha256 → restart HTTP server per current config; prints the new address afterward (fully async, no TPS drop) |

## 5. Dual-Platform Behavior (never blocks players)

- **Java side:** `Bukkit.createBossBar()` shows dialogue character-by-character
  (`char-interval` ticks/char); color per NPC: 芜=white, 晨=gold, 黯=purple. ActionBar
  shows `[左键地面继续] [Shift 跳过]` (left-click ground to continue / Shift to skip),
  switching to `[空格继续]` (space to continue) when finished (both click and jump
  advance). The option-node mechanism is retained (left-click = option 1, right-click
  = option 2, F = option 3); the current main story has no option nodes — faction
  selection is handed off to the standalone faction plugin, and after S5_4 finishes
  you just click continue to end guidance. **Shift to skip at any time.**
- **Bedrock side (Geyser):** **never use BossBar for text** (Bedrock BossBar is only a
  health bar); full lines go through ActionBar. Options prefer Geyser `SimpleForm`
  button groups (fully reflective calls, auto-degrade on any version difference),
  falling back to ActionBar hint + chat input `1/2/3`; **skip = the form's "跳过引导"
  (Skip Guidance) button or chat input `skip`**.
- Reconnect after disconnect resumes from `current_node` in the database; if parked on
  an option node, that option is re-shown.
- Idle timeout: 芜 first prompts once; a further timeout auto-closes guidance and marks
  it "skipped" — never stuck permanently.
- After guidance ends/skips, no UI is triggered again; players are free to act.
- Resource-pack download/transcode failure (including `FAILED_DOWNLOAD`) is only
  logged and allowed through — **players are never kicked because of the resource pack**.
- All heavy operations (database, packaging, HTTP, forms) run on async threads — no TPS
  drop.
- Full UTF-8 throughout the chain (files read with explicit
  `StandardCharsets.UTF_8`) to prevent Bedrock mojibake.

## 6. FAQ

- **Geyser not installed?** No need. The plugin only reflectively probes Geyser; on
  failure it renders entirely on the Java side.
- **Bedrock players can't download the resource pack?** Use `/starinn status` to check
  whether the delivery URL is publicly reachable (behind NAT, fill in `public-host`);
  allow `builtin-port` in the firewall/security group; failure won't kick players,
  only the art is missing.
- **Port occupied?** After the log hint, change `builtin-port` → `/starinn reload`;
  the plugin won't crash, only delivery is off.
- **Database locked?** Async retry 3 times; if still failing, auto-degrade to in-memory
  progress with a warning; guidance is not interrupted.
- **Want to swap art?** Drop a same-named PNG into `plugins/StarInn/textures/`
  (power-of-two size), then `/starinn pack regen`.
- **Dialogue file corrupted?** The log prints the file and line/column; missing
  dialogue nodes are auto-skipped, the rest works normally.

## 7. Content & Acceptance Red Lines

- 22 dialogue lines (wu 14 + chen 4 + an 4) match the original setting text verbatim;
  faction selection has been handed off to the standalone faction plugin (players see
  a selection prompt 10 minutes after joining, from that plugin); S5_4 has no options
  and ends with COMPLETED once finished; the four landing lines S6_1/S6_2/S7_1/S7_2
  have been removed, guidance no longer jumps to S6/S7;
- Zero hardcoded ports in code; zero third-party plugin dependencies; zero outbound
  network requests;
- Compiles on Purpur 1.21.11 + Java 21 (see `build.gradle`: only `compileOnly` Purpur
  API and SQLite driver, provided by the server at runtime).

## 8. Development & Build

```bash
gradle build                  # artifact build/libs/StarInn-1.0.0.jar (art PNGs packaged into the jar via resourcepack/)
python3 packs/build_packs.py  # validate texture compliance + repackage the dual-platform resource packs and distribution
```

See `TESTING.md` for the self-test checklist and this round's self-check report.
