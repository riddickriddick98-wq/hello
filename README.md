# KoyaRobbery – Forge 1.20.1 port (source project)

Built from the real KoyaRobbery 1.2.2 plugin JAR (configs, messages, loot tables, block mapping) and
KoyaRobbery-Rp v1.1 (models, textures, `custom.robbery` sound – bundled under assets/minecraft).

**NOT COMPILED OR TESTED.** Written without a Java compiler or network. Expect a few compile errors; send them to me.

## Build
Java 17. Open in IntelliJ (Gradle import) or copy `gradlew`/`gradle/wrapper` from the Forge 1.20.1 MDK into this folder, then `./gradlew build`.
Jar: `build/libs/koyarobbery-forge-1.20.1-1.0.0.jar`. Install on server AND clients (the resource pack assets ship inside the mod).

## First-time setup (replaces WorldGuard)
1. `/robbery region pos1`, `/robbery region pos2`, `/robbery region create bank_region_name`
   (use the `Region-Id` from `config/koyarobbery/config.json`).
2. `/robbery structure` – take Table / Safe / Jewelry / Vault Door blocks and Vault Drill; right-click to place inside a region.
3. `/robbery getstick` – right-click a structure or vault door to remove it.
4. Players: `/robbery start CentralBank` (inside the region) -> START button.

## Config files: `config/koyarobbery/`
config.json, loots.json, language.json = the plugin's YAML values converted 1:1 (police permission, Minimum-Online-Players
and Required-Crew-Size removed as requested). data.json = regions, structures, vault doors.

## Faithful vs. inferred
Faithful (read from the JAR): all messages, places/timers/cooldowns, loot tables + tool items, structure block mapping,
drill item (name, lore, CustomModelData 1001), GUI titles ("Crew Menu", "Start Robbery", "Lock Picking ...", "Structures",
"Robbery (Safe)"), action-bar countdown/fail/done texts, drill sounds/particles list, 20-block start-sound radius.
The plugin has **no scoreboard or boss bar**; its HUD is action-bar text, which this port reproduces.

Inferred (bytecode could not be fully decompiled here – please verify against the plugin):
- Vault door box shape (3x7x7 from the GUI lore) and IRON_BLOCK fill
- Lock-pick timing/layout, table/jewelry hold time (`Loot-Time-Seconds`, my addition)
- Crew GUI slot layout, `Max-Crew-Size` (my addition), leader-leaves behaviour
- Structure Stick behaviour (remove structures)
