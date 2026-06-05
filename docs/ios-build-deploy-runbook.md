# iOS build & deploy runbook

Cuts the rediscovery time on every iPad push. All facts below are **verified on the Mac build host**
(2026-06-05). The slow part is the NativeAOT publish of the framework — everything here is oriented at
not paying it twice.

## TL;DR fast path (physical iPad)

**Build as `steve`, NOT `clawagent`** (see Build host facts for why). From Windows or anywhere with the
SSH key:

```bash
ssh steve@steve-mac-mini "zsh -lc '
  cd /Users/steve/dotnet-ios-android-poc-native-frontend
  bash build/build-ios-framework-device.sh        # device-only XCFramework (~half the full build)
  cd ios && xcodegen generate
  xcodebuild -project OnDeviceLlmApp.xcodeproj -scheme OnDeviceLlmUnified -configuration Debug \
    -destination \"generic/platform=iOS\" -derivedDataPath build/dd \
    -allowProvisioningUpdates DEVELOPMENT_TEAM=QJW4S8BDFX build
  xcrun devicectl device install app --device 00008101-0019092E1EFA601E \
    build/dd/Build/Products/Debug-iphoneos/*.app
'"
```

`build-ios-framework-device.sh` skips the simulator slice — use the original
`build/build-ios-framework.sh` only when you also need the Simulator.

## Build host facts (verified)

| Thing | Value |
|------|-------|
| SSH host | `steve-mac-mini` → **user `steve`** (the alias forces it). **Build as `steve`, not `clawagent`.** |
| Why not clawagent | `clawagent`'s `~/.local/share/NuGet/http-cache` is **root-owned** → restore fails, and `TreatWarningsAsErrors` promotes the resulting `NU1900` to a hard error. Signing certs also live in **steve's** keychain. `clawagent` also has no passwordless sudo. |
| Project on Mac | `/Users/steve/dotnet-ios-android-poc-native-frontend` (owned by `steve`) |
| Xcode | 26.5.1 (Swift 6, iOS deploy target 17.0) |
| `dotnet` | `/usr/local/share/dotnet/dotnet` — **only on the login PATH**, so wrap remote cmds in `zsh -lc '…'` |
| Workloads | `maui` / `maui-ios` / `maui-android` (SDK 10.0.300) |
| `xcodegen` | `/opt/homebrew/bin/xcodegen` (no committed `.xcodeproj`; must generate) |
| Signing identity | **"Apple Development: Steven Ackley"**, team **`QJW4S8BDFX`** (the "Apple Distribution" cert is **revoked** — device installs must use the Development cert) |
| Target device | **"Michael's iPad"**, UDID **`00008101-0019092E1EFA601E`** |

## Getting Windows changes onto the Mac

The repo lives on both Windows (edit) and the Mac (build). `clawagent` can read but **not write**
`steve`'s tree, and has no passwordless sudo. Two options:

1. **Owner opens it up once** (run as `steve`, no sudo needed):
   `chmod -R g+w /Users/steve/dotnet-ios-android-poc-native-frontend` — then `clawagent` (in `staff`)
   can write/build in place.
2. Overlay just the changed files from Windows (cwd = repo) via tar-over-ssh:
   ```bash
   tar -cf - <relative paths…> | ssh steve-mac-mini \
     "tar -xf - -C /Users/steve/dotnet-ios-android-poc-native-frontend"
   ```

## Code-signing over SSH (`errSecInternalComponent`) — THE key gotcha

`xcodebuild` over SSH fails at `CodeSign` with `errSecInternalComponent` because **the login keychain is
locked in every fresh SSH session.** The GUI login unlocks it, but that state lives in the GUI's
`securityd`/bootstrap context; each independent `ssh steve-mac-mini "…"` is a separate bootstrap namespace
that starts **locked**, so `codesign` can read the public cert but not the private key. (Verified: the
search list and the key's partition-list ACL are both fine — it is purely lock state.)

**There is no password-free persistent fix.** `security set-keychain-settings` (disable auto-lock) only
prevents *re-locking within one session*; it does NOT make a new SSH command see the keychain unlocked.
**Every build must `unlock-keychain` in the same shell as `xcodebuild`.**

Recommended pattern (keeps the password out of command lines / logs): store it once in a `0600` file on
the Mac, reference it inline.

```bash
# one-time, as steve, password NOT echoed or saved to shell history:
read -rs 'P?login password: '; printf '%s' "$P" > ~/.sign_kc_pw; chmod 600 ~/.sign_kc_pw; unset P

# then every signed build unlocks inline in the SAME ssh command:
ssh steve-mac-mini "zsh -lc '
  security unlock-keychain -p \"\$(cat ~/.sign_kc_pw)\" ~/Library/Keychains/login.keychain-db &&
  cd /Users/steve/dotnet-ios-android-poc-native-frontend/ios &&
  xcodebuild … build &&
  xcrun devicectl device install app --device 00008101-0019092E1EFA601E build/dd/.../*.app
'"
```

A plaintext login-password file is a tradeoff (it's your machine; `chmod 600`). More secure alternative:
import the signing identity into a **dedicated signing keychain** (fastlane-`match` style) so the file
holds only that keychain's password, not your login password.

## Time-savers / gotchas

- **Device-only RID** (`build-ios-framework-device.sh`) ≈ halves the framework build vs device+simulator.
- Remote commands need a **login shell** (`zsh -lc`) or `dotnet`/`xcodegen`/`brew` aren't on PATH.
- A **single-slice** (device-only) `.xcframework` is valid for device builds; don't recreate the
  simulator slice unless you need the Simulator.
- `xcodegen`'s project is `ios/OnDeviceLlmApp.xcodeproj`, scheme **`OnDeviceLlmUnified`**, bundle id
  `com.ondevicellm.unified` (from `ios/project.yml`).
- `ios-deploy` is **not installed**; use `xcrun devicectl device install app` (Xcode 15+).
- Adding a feature to the engine catalog needs **no ABI/Swift change** — but the **dylib changes**, so the
  XCFramework MUST be rebuilt for new demos to appear on device.

## Verifying without a device (Windows)

Engine demos are deterministic and self-checking. Build + a throwaway probe referencing
`OnDeviceLlm.Engine` (assert `LanguageFeatureCatalog.Run(id).Ok`), then delete the probe. The native
build/deploy above is the only way to verify the **visual** demos and AOT-only behaviour (e.g. AES-GCM via
CryptoKit). See `nativeaot-mobile-caveats.md` for what can and can't be proven off-device.
