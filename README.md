# WaxCfg

A lightweight config library for Minecraft Fabric mods (26.2+). Provides shared options storage, config serializers, keybind helpers and config screen scaffolding used by several Wax libraries.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.17.0+ |
| Fabric API | 0.154.2+26.2 |
| Java | 21+ |

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2.
2. Drop [Fabric API](https://modrinth.com/mod/waxcfg) into `.minecraft/mods`.
3. Drop the latest `waxcfg-x.x.x.jar` (from [Releases](../../releases)) into `.minecraft/mods`.

## For mod developers

```gradle
repositories {
    maven { url = "https://api.modrinth.com/maven" }
}

dependencies {
    modImplementation "maven.modrinth:waxcfg:1.4.0"
}
```

## License

MIT.
