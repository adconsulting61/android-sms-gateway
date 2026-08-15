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
  label is "Valor Reach", so this build installs alongside the real SMSGate
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

## Reaching the phone from anywhere (Cloudflare Tunnel)

Local Server mode only answers callers on the same LAN as the phone. Since
the phone needs to be usable from anywhere (not just at home), its Local
Server is additionally exposed over the internet via a **Cloudflare Tunnel**.

Cloud Server mode was deliberately *not* used for this: none of the custom
features above (SIM-locked tokens, the `processed` flag, `simNumber` on the
read API) exist in Cloud mode's code path — Cloud mode talks to capcom6's own
hosted `api.sms-gate.app`, which this repo has no access to. The tunnel
instead makes the *Local Server* itself reachable publicly, so all the custom
features keep working from anywhere.

### Cloudflare dashboard setup (one-time, already done)

- Cloudflare account: `adconsulting61@gmail.com`, domain `valorsystems.app`
  (already on Cloudflare, nameservers already pointed)
- Zero Trust → Networks → Tunnels → tunnel named `valor-reach-sms-gateway`
- Public hostname: `sms.valorsystems.app` → Service type `HTTP` → URL
  `localhost:8080` (this is `localhost` and not a LAN IP because
  `cloudflared` runs *on the same phone* as the app itself)
- The connector token lives only in the Cloudflare dashboard (Tunnels →
  `valor-reach-sms-gateway` → Configure → "run manually" command) —
  intentionally **not** duplicated here, since this is a live credential and
  this file may end up in a public fork.

### Current approach: cloudflared embedded in the app

`cloudflared` is bundled into the APK and run by the app itself, so there's
no separate Termux install to keep alive.

- The binary lives at `app/src/main/jniLibs/arm64-v8a/libcloudflared.so`. It
  is a real executable, not a linkable library — the `lib*.so` name is
  mandatory, because that naming convention is the only way Android will
  extract a file to `nativeLibraryDir` on disk with the exec bit set.
- `app/build.gradle` sets `packagingOptions { jniLibs { useLegacyPackaging true } }`.
  This is **required**: without it AGP may leave the "library" mmap'd inside
  the APK zip instead of extracting it, and there'd be no real file path to
  `exec()`.
- `TunnelService` (`modules/localserver/TunnelService.kt`) runs it as a
  subprocess — `cloudflared tunnel run --token <token>` — under a foreground
  service with a status notification, restarting it with exponential backoff
  (2s, doubling, capped at 60s) whenever it exits. `LocalServerService`
  starts and stops it alongside `WebService`.
- Gated on `LocalServerSettings.tunnelToken`, set in the app at **Settings →
  Local Server → "Cloudflare Tunnel Token"**. No token set means no tunnel is
  started at all — same LAN-only behavior as before this existed.

**How the binary was produced:** cross-compiled from source
(`github.com/cloudflare/cloudflared`, cloned to `~/Projects/cloudflared`) for
`android/arm64` with `CGO_ENABLED=0`. This needs **no Android NDK** — it's a
pure Go build — and critically it produces a proper PIE executable linked
against Android's own bionic loader. Do not substitute the official GitHub
release binary for Linux/arm64: that one is non-PIE and fails on Android with
`unexpected e_type: 2`.

> **Status: not yet confirmed on-device.** The APK builds and the binary is
> verified present in the package at the right path, but as of 2026-08-15 no
> end-to-end test result has been reported — it has *not* been confirmed that
> `sms.valorsystems.app` still answers with the Termux tunnel stopped and
> only the embedded one running. Until that's confirmed, treat the Termux
> setup below as the known-good fallback.

### Previous approach: cloudflared in Termux (superseded, known-good fallback)

This was the original setup, and it's the one actually verified end-to-end.
It stays documented because the embedded replacement above is still
unconfirmed. The two are mutually exclusive in practice — running both at
once just means two connectors serving the same hostname.

- Termux installed from **F-Droid**, not the Play Store
- Battery optimization disabled for Termux (Settings → Apps → Termux →
  Battery → Unrestricted), otherwise Android kills the background tunnel
- `cloudflared` must be installed via `pkg install cloudflared`, **not** the
  raw GitHub release binary — same non-PIE problem described above; Termux's
  own packaged build doesn't have it
- Run with: `cloudflared tunnel run --token <token-from-dashboard>`, ideally
  after `termux-wake-lock`, in its own Termux session (swipe in from the left
  edge for the session drawer, or the app's own overflow menu if the swipe
  gesture doesn't register on this device)
- For persistence across phone reboots: Termux:Boot (companion app, also
  F-Droid) plus a `~/.termux/boot/start-tunnel.sh` script that calls
  `termux-wake-lock` then the same `cloudflared tunnel run --token ...` command

Verified working end-to-end 2026-08-15 **with the Termux tunnel**: minted a
SIM-1-locked token, confirmed a request forcing SIM 2 with that token gets
rejected (403, `"This token is locked to SIM 1"`), and sent a real SMS
through `sms.valorsystems.app` with the phone on cellular data (not home
Wi-Fi) — delivery confirmed, `simNumber: 1` correctly persisted and visible
via `GET /message/{id}`.

### Known failure modes and what they actually mean

- Visiting `https://sms.valorsystems.app/...` and getting Cloudflare
  **error 1033** = no `cloudflared` connector is currently connected at all.
  With the embedded tunnel: check the Local Server is running, that a tunnel
  token is actually saved in settings, and look for the tunnel's own status
  notification. With Termux: the session closed or the phone killed it in the
  background — restart the tunnel command.
- Getting a Cloudflare **502 "Bad Gateway" / Host Error** (as opposed to
  1033) means the tunnel itself *is* connected, but the app's Local Server
  isn't answering on `localhost:8080` — check the Local Server toggle is
  actually on in the app, and that its port still matches 8080.
- Reinstalling the app wipes its stored Local Server username/password
  (they're randomly generated and stored in app data), and now also the saved
  tunnel token. Get the new credentials from the app's Local Server settings
  screen after any reinstall, and re-paste the tunnel token.
- Sideloaded APKs hit Android 13+'s "Restricted settings" protection —
  granting SEND_SMS (or other runtime permissions) may silently fail with
  "app was denied access" until you go to Settings → Apps → Valor Reach →
  ⋮ menu → "Allow restricted settings" first.

## SMS only — no RCS, and inbound RCS is invisible

Valor Reach sends and receives **plain SMS/MMS only**. It never sends RCS,
and it can't be made to.

- Outbound goes through `android.telephony.SmsManager`
  (`modules/messages/MessagesService.kt`), which hands the message to the
  cellular radio as an SMS. There's no RCS anywhere in that path and no flag
  that changes it.
- RCS isn't a mode of SMS — it's a separate stack running over IP through
  carrier IMS provisioning (Google's Jibe backend, in practice). On Android
  the only client that speaks it is Google Messages; third-party apps can't
  register with it, not even as the default SMS app. `SmsManager` is the only
  thing this app *can* use.
- Don't confuse this with the gateway phone's own behavior: texting someone
  by hand in Google Messages on that phone *does* go over RCS. Same phone,
  same SIM, two entirely separate rails.

**The operational consequence — inbound RCS silently disappears.** All three
inbound ingest paths are the SMS subsystem:

- the `SMS_RECEIVED` / `DATA_SMS_RECEIVED` broadcasts
  (`modules/receiver/MessagesReceiver.kt`)
- the `content://sms/inbox` fallback observer
  (`modules/receiver/SmsContentObserver.kt`)
- the MMS receiver (`modules/receiver/MmsReceiver.kt`)

RCS messages land in none of them — Google Messages keeps RCS conversations
in its own private database, not the standard Telephony provider. So if a
sender's phone decides to deliver over RCS, this app never sees the message:
no error, no log entry, it simply never arrives.

**Mitigation:** turn RCS off on the gateway phone — Google Messages →
Settings → "RCS chats" (a.k.a. "Chat features"). It's a **per-SIM** setting
on a dual-SIM device, so check both SIMs. That deregisters the number from
RCS and senders' phones fall back to SMS, which this app does see. Expect a
lag of roughly a day before other people's devices stop attempting RCS, since
they cache capability. *(As of 2026-08-15 this has not been checked on the
OnePlus — worth confirming before trusting inbound delivery.)*

There's also no third-party way to detect whether a given number is
RCS-capable, so "use RCS when possible, SMS otherwise" isn't buildable on top
of this app. The only sanctioned RCS path is RCS Business Messaging through a
Google partner (Twilio/Sinch/Vonage), which is a separate, paid channel with
nothing to do with the phone.

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
