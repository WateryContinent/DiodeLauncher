# Diode - Minecraft Legacy Launcher

A reconstructed Minecraft Launcher 1.6.44 era project, updated to build on **JDK 21** and launch modern Minecraft versions.

## Run in IntelliJ

1. Open this folder in IntelliJ IDEA.
2. Set the Project SDK to **JDK 21** if IntelliJ asks.
3. Select **`Bootstrap - Local Online Login`**.
4. Press **Run**.

The run configuration builds `launcher.jar` automatically and places it in `runtime/minecraft/` before starting the bootstrap.

## Build a standalone launcher

In IntelliJ use:

`Build -> Build Artifacts -> launcher-release -> Build`

The result is:

`dist/launcher.jar`

## Modern Minecraft support

The launcher uses Mojang's current version metadata/download URLs and reads each version's requested Java runtime. If a game needs a different Java version, the launcher downloads Mojang's matching runtime into its own `runtime/` directory and launches the game with it.

A custom Java path set in a profile still takes priority.
