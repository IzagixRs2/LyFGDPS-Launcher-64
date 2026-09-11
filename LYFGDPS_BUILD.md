# LyFGDPS Launcher

This project builds the custom Android launcher for LyFGDPS.

## Production packages

- Launcher: `com.izagix.lyf.gdp`
- GDPS: `com.mathieu.lyfgdps00000`
- App name: `LyFGDPS Launcher`

## Production downloads

- GDPS APK: https://github.com/IzagixRs2/LyFGDPS-Android64/releases/download/LyFGDPS/LyFGDPS.apk
- Geode mod: https://github.com/IzagixRs2/LyFGDPS-GeodeMod/releases/download/LyFGDPS/izagix.lyfgdps.geode

The Geode mod is bundled in `app/src/main/assets/mods/` so the launcher copies it into the Geode mods directory when the GDPS starts.

## GitHub Actions secrets

Create these four repository Actions secrets:

- `KEYSTORE_FILE` — Base64 of the release keystore file.
- `KEYSTORE_PASSWORD` — keystore password.
- `KEY_ALIAS` — signing key alias.
- `KEY_PASSWORD` — signing key password.

The workflow reads them through GitHub Actions' `secrets` context. See GitHub's documentation for repository secrets. 
