# LytsoCoder Repository Guidelines

- **Package IDs (10-byte rule)**: `TermuxRelocation` rewrites `com.termux` (10 bytes) byte-for-byte in Termux binaries and `.deb` packages. Release builds MUST use `lytso.code` (10 chars) and debug builds MUST use `lytso.cdev` (10 chars).
- **Local ports**: Release uses `BRIDGE_PORT=1240`, `HUB_PORT=1241`, `BROWSER_MCP_PORT=8770`; debug uses `1242`, `1243`, `8771` so LytsoCoder runs alongside Termux (`1234/1235/8765`) and AntiGem (`1236/1237/8767`).
- **Build & test**:
  - Go bridge tests: `cd agy_ide_bridge && go test ./...`
  - Android unit tests & debug APK: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
  - Note: `./gradlew :app:preBuild` builds `agy_ide_bridge` (Go `GOOS=android GOARCH=arm64`), compresses it with `xz`, syncs `scripts/*.sh` into `app/src/main/assets/bin/`, and builds `termux-am` (`am.apk`). Always initialize the `termux-am` submodule before building.
- **Rootfs mirrors**: `LocalEnvironmentManager.kt` fetches `rootfs-v2` (`bootrapz_2026-11-19.zip`) from the upstream GitHub/Hugging Face mirrors unless overridden in `Lytso/LytsoCoder`.
- **Attribution**: LytsoCoder is maintained by `@ItsLytso` and licensed under GPLv3 (forked from `Santoshkurmi/antigem`).
