# Diode legacy fix agent

A tiny Java agent that lets old Forge versions (for Minecraft 1.6 and 1.7.2) start on Java 8.

Those Forge builds call `Collections.sort` on launchwrapper's tweak list while launchwrapper is
looping over it. From Java 8u20, that throws a `ConcurrentModificationException`, and Mojang only
offers Java 8u51. The agent redirects that call in Forge's classes to `LegacySort`, which sorts
the way Java 7 did.

The launcher ships the compiled agent as `src/launcher/src/main/resources/diode-legacy-fix.jar`
and adds `-javaagent:` for versions whose main class is `net.minecraft.launchwrapper.Launch`.

It has to be compiled for Java 8, so rebuild the jar after changing the sources here:

```bash
java src/legacyfix/BuildLegacyFix.java
```
