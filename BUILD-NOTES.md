# Build Notes

Local build setup for this fork, recorded 2026-08-13. This is a personal/dev
setup — not upstream documentation.

## Custom features added on top of upstream

- **`simNumber` on the REST read API** (`GET /message/{id}`, `GET /messages`) —
  which SIM a message was sent through is now visible when polling, not just
  via webhooks.
- **SIM-locked API tokens** — `POST /auth/token` accepts an optional
  `sim_number` (1-3). A token minted with `sim_number` set can only be used
  to send messages through that exact SIM: `POST /message` requests using
  that token are forced to that SIM if `simNumber` is omitted, and rejected
  with 403 if they explicitly request a different one. The lock survives
  `POST /auth/token/refresh` (carried via a hidden JWT claim, mirroring how
  scopes already survive refresh).
  **Caveat:** HTTP Basic auth (the `username`/`password` from Local Server
  settings) bypasses all scopes and claims entirely, including this lock — a
  SIM-locked token only means something if the basic-auth credentials aren't
  also handed to whatever integration you're restricting.
- **"Processed" flag on incoming messages** — `GET /inbox` accepts a
  `processed` filter, and `PATCH /inbox/{id}` (new endpoint, scope
  `inbox:write`) marks a message processed/unprocessed. This is purely an
  app-level bookkeeping flag your own system sets — it does not fire a
  webhook, and it has no effect on the existing time-based inbox cleanup
  (unprocessed messages are still pruned once they age out).
- **Per-SIM sent-message stats screen** — Settings → a new entry showing how
  many messages have been sent through each SIM, so you can notice if one
  SIM is getting overused relative to the other.
- **Separate package/app name** — `applicationId` is now `me.capcom.smsgateway.dev`
  (suffix added in `defaultConfig`, applies to every build type) and the app
  label is "SMSGate Dev", so this build installs alongside the real SMSGate
  app instead of overwriting it. Because Firebase matches on exact package
  name, this required registering a *second* Android app (same package,
  `.dev` suffix) under the same Firebase project — `app/google-services.json`
  now contains client entries for both `me.capcom.smsgateway` and
  `me.capcom.smsgateway.dev`. If you ever change the applicationId again,
  you'll need to add another matching app registration in Firebase console
  the same way, or the build fails with "No matching client found for
  package name ...".

## Environment on this machine

- JDK 17 (Homebrew `openjdk@17`) — required by Android Gradle Plugin 8.1.2
- Android SDK at `~/Library/Android/sdk` (compileSdk/targetSdk 33, minSdk 21,
  build-tools 33.0.1/33.0.2/34.0.0 installed)
- `ANDROID_HOME` and `JAVA_HOME` are set in `~/.zshrc`, so any new terminal
  window already has them. If you ever need them in a one-off shell:

  ```sh
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
  export ANDROID_HOME="$HOME/Library/Android/sdk"
  export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  ```

- The project's own `./gradlew` wrapper pins Gradle 8.0 — you don't need the
  Homebrew `gradle` formula for this project, `./gradlew` downloads/uses its
  own version automatically.

## Rebuilding after a code change

From the project root:

```sh
./gradlew assembleDebugInsecure
```

Output APK lands at:

```
app/build/outputs/apk/debugInsecure/app-debugInsecure.apk
```

Other useful tasks:

- `./gradlew tasks` — list all available Gradle tasks
- `./gradlew test` — run unit tests for all four build variants
- `./gradlew clean` — wipe build outputs (use if you get weird stale-build errors)
- `./gradlew assembleDebug` / `assembleInsecure` / `assembleRelease` — build
  the other variants (see below; `insecure` and `release` need a release
  keystore that doesn't exist yet on this machine)

## Installing the APK to a connected phone over ADB

1. On the phone: Settings > About phone > tap "Build number" 7 times to
   enable Developer Options, then Settings > Developer options > enable
   "USB debugging".
2. Connect the phone via USB, and accept the "Allow USB debugging?" prompt
   that appears on the phone screen.
3. Confirm the device is visible:

   ```sh
   adb devices
   ```

4. Install (or reinstall, `-r` keeps app data) the freshly built APK:

   ```sh
   adb install -r app/build/outputs/apk/debugInsecure/app-debugInsecure.apk
   ```

You can also do this over Wi-Fi with `adb pair`/`adb connect` instead of USB,
but USB is simpler to start with.

## The Firebase situation

`app/build.gradle` applies the Google Services Gradle plugin
(`com.google.gms.google-services`) unconditionally — this is **not**
optional or Cloud-mode-only. Without a valid `app/google-services.json` file
present, the build fails for every variant (debug, debugInsecure, insecure,
release) at the `processXGoogleServices` task, before any app code even
compiles.

`google-services.json` is gitignored upstream (it's tied to a specific
Firebase project and isn't meant to be committed). Upstream CI
(`.github/workflows/build-apk.yml`, `release-build.yml`) injects it from a
GitHub Actions secret at build time.

For this local build, a `google-services.json` was generated by creating a
free Firebase project and registering an Android app with package name
`me.capcom.smsgateway` (the file is tied specifically to that package name —
if the package name is ever changed, a new file must be generated to match).
That file now lives at `app/google-services.json` and is gitignored, so it
won't accidentally get committed.

What this file actually gates at runtime: Firebase Cloud Messaging, used by
the app's **Cloud Server** mode (push-based, works across networks/NAT
without port forwarding). It has nothing to do with **Local Server** mode
(LAN-only, direct HTTP to the phone), which works regardless of whether the
Firebase project is "real." Since the plugin is applied unconditionally,
though, the file must exist and be well-formed for the project to compile at
all, even if you only ever plan to use Local Server mode.

## Secure vs insecure build variants

The project defines four Gradle build types in `app/build.gradle`:

| Build type      | Gradle task              | Debuggable | Signing config       | Network security config                    | Needs release keystore? |
|------------------|---------------------------|:----------:|-----------------------|---------------------------------------------|:---:|
| `debug`          | `assembleDebug`           | yes        | auto (debug key)      | `network_security_config` (strict/secure)   | no |
| `debugInsecure`  | `assembleDebugInsecure`   | yes        | auto (debug key)      | `network_security_config_insecure` (allows cleartext HTTP) | no |
| `insecure`       | `assembleInsecure`        | yes        | `signingConfigs.release` | `network_security_config_insecure`       | **yes** |
| `release`        | `assembleRelease`         | no         | `signingConfigs.release` | `network_security_config` (strict/secure) | **yes** |

- "Secure" vs "insecure" refers to the Android **network security config**,
  not app permissions or code. The secure config only permits HTTPS traffic;
  the insecure config additionally allows plain HTTP (cleartext), which is
  what lets Local Server mode work conveniently on a local network without
  setting up TLS on the phone.
- `debugInsecure` is the variant to use for local development/testing on
  this machine right now: it's debuggable, auto-signed with the ephemeral
  Android debug key (no keystore/passwords needed), and allows cleartext
  HTTP for LAN use.
- `insecure` is confusingly named: despite the name, it's meant to be a
  distributable "insecure-network-allowed" release build, so it uses the
  same **release** signing config as `release` — meaning it needs a real
  keystore (`app/keystore.jks`, referenced via the env vars
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` in
  `app/build.gradle`). That keystore does not exist yet on this machine and
  was intentionally not created in this session — building `insecure` or
  `release` will fail until one is generated and those env vars are set.
