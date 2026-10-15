# KoyaRobbery Forge — Minecraft 1.20.1

**Target:** Minecraft Java 1.20.1, Forge 47.x, Java 17.

This is a Forge-native conversion project based on the supplied KoyaRobbery 1.2.2 plugin JAR and KoyaRobbery-Rp-v1.1 resource pack. It does not load Bukkit/Paper or WorldGuard.

## Implemented in this source version
- `/robbery help`, `/robbery crew`, `/robbery invite <player>`, `/robbery accept`, `/robbery disband`, `/robbery start <CentralBank|GunStore>`, `/robbery status`.
- Solo robbery start: **no police-online requirement and no minimum-crew requirement**.
- CentralBank (300 seconds / 600-second cooldown) and GunStore (180 seconds / 400-second cooldown).
- Start/end announcements, a robbery-start sound copied from the supplied resource pack, a countdown boss bar, and a scoreboard sidebar.
- Vault Drill item; using it on an iron door consumes it and opens the door after 15 seconds.
- Original supplied resource-pack assets copied into the mod resources, including its vanilla model/texture overrides and the `custom.robbery` sound asset. The original resource-pack ZIP is also retained under `original-resource-pack/`.

## Not yet a full feature-parity release
The original plugin's complete chest GUIs, custom structure editor, WorldGuard region validation, structure restore/teleport options, every loot placement/animation, command-list execution, persistent data migration, and all config-driven behavior have not been fully ported. The plugin JAR itself contains no audio files; the robbery sound comes from the supplied resource pack. This project should be considered an initial functional conversion source, not a verified drop-in replacement.

## Build
1. Install JDK 17 and Gradle (or open/import this Gradle project in IntelliJ IDEA).
2. Run `gradle build` from this directory.
3. If the build succeeds, the mod JAR will be in `build/libs/`.
4. Put that JAR in the `mods` folder of the Forge 1.20.1 client/server.

The project was prepared as source in this environment; a successful Gradle build has not been verified here.
