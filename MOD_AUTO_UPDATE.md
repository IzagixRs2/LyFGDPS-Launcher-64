# LyFGDPS automatic mod updates

The launcher now checks the latest GitHub release assets for:

- `geode.node-ids.geode`
- `izagix.lyfgdps.geode`

The check happens before the launcher starts the game. If GitHub reports a changed ETag, the launcher downloads the new `.geode`, validates its `mod.json` ID, and replaces the installed file.

If the network is unavailable or an update fails, the launcher keeps the currently installed mod. On a fresh installation, the bundled mods remain the fallback.

The remote URLs are configured in `GdpsConfig.kt`.
