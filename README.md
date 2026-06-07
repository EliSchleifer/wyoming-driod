# Wyoming Satellite for Android (wyoming-driod)

Turn an Android device — such as a **Lenovo ThinkSmart View running
LineageOS 15.1** — into a [Wyoming](https://github.com/OHF-Voice/wyoming) voice
satellite for [Home Assistant](https://www.home-assistant.io/). The app runs a
foreground service that captures the microphone and streams 16 kHz PCM audio in
real time to Home Assistant's Assist pipeline, and (optionally) plays the spoken
response back through the device speaker.

It speaks the Wyoming protocol directly, so it shows up in Home Assistant exactly
like a Raspberry Pi running [`wyoming-satellite`](https://github.com/rhasspy/wyoming-satellite)
— no add-ons or extra bridges required.

---

## How it works

```
┌──────────────────────────┐         TCP :10700          ┌─────────────────────┐
│  Android device          │  ◀──── connects ──────────  │  Home Assistant     │
│                          │                             │  (Wyoming integr.)  │
│  SatelliteService        │  ── info / run-pipeline ──▶ │                     │
│   • Wyoming TCP server    │  ── audio-chunk (mic) ───▶ │  wake → STT →       │
│   • AudioRecord (16 kHz)  │  ◀── audio (TTS reply) ──── │  intent → TTS       │
│   • AudioTrack (playback) │                             │                     │
└──────────────────────────┘         mDNS _wyoming._tcp  └─────────────────────┘
```

1. The app opens a TCP server (default port **10700**) and advertises itself on
   the network via mDNS as `_wyoming._tcp`.
2. Home Assistant's **Wyoming** integration connects to it (auto-discovered, or
   added manually by IP + port).
3. On `run-satellite`, the satellite asks HA to `run-pipeline`
   (`restart_on_end = true`) and begins streaming microphone audio continuously
   as `audio-chunk` events.
4. Home Assistant performs **wake-word detection, speech-to-text, intent
   handling and text-to-speech on the server**. The satellite itself stays
   "dumb" — it just streams audio and plays back any TTS response.

> **Pipeline mode.** The default mode is **Wake word**: audio streams
> continuously and Home Assistant listens for the wake word server-side, so you
> need a wake-word engine configured in HA (e.g. the **openWakeWord** add-on)
> and selected in your Assist pipeline. If you'd rather have no wake word and
> send everything straight to speech-to-text, switch the *Pipeline mode* setting
> to **Always stream to speech-to-text**.

---

## Requirements

- An Android device, API 26+ (Android 8.0+). LineageOS 15.1 = Android 8.1
  (API 27) is the primary target.
- Home Assistant with the **Wyoming** integration (built in) and a configured
  **Assist pipeline**. For the default wake-word mode you also need a wake-word
  engine such as **openWakeWord**.
- To build: **JDK 17+** and the **Android SDK** (Android Studio recommended).

---

## Building the APK

This is a standard Gradle/Android Studio project (Gradle 8.7, AGP 8.5.2,
Kotlin 1.9.24).

### Option 0 — GitHub Actions (no local toolchain needed)

Every push builds the APK in CI ([`.github/workflows/build.yml`](.github/workflows/build.yml)).
Open the run under the repository's **Actions** tab and download the
**`wyoming-satellite-debug`** artifact — it contains `app-debug.apk`, ready to
sideload. You can also trigger a build manually via **Actions → Build APK → Run
workflow**.

The Gradle wrapper jar is intentionally **not** committed; CI and Android Studio
provide Gradle, and CLI users can regenerate it with `gradle wrapper` (see below).

### Option A — Android Studio (easiest)

1. Open the project folder in Android Studio (Hedgehog or newer).
2. Let it sync Gradle and download the SDK components it asks for. Android
   Studio sets up the Gradle wrapper automatically on first sync.
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — Command line

You need the Android SDK installed and pointed to via either the
`ANDROID_HOME` / `ANDROID_SDK_ROOT` environment variable or a
`local.properties` file in the project root:

```properties
# local.properties
sdk.dir=/absolute/path/to/Android/Sdk
```

If `gradle/wrapper/gradle-wrapper.jar` is missing (so `./gradlew` won't run),
generate it once with a local Gradle 8.7+ install — Android Studio does this for
you automatically:

```bash
gradle wrapper --gradle-version 8.7
```

Then:

```bash
# Debug APK (good enough for sideloading)
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Release APK (unsigned unless you configure signing)
./gradlew assembleRelease
```

> The first build downloads the Android Gradle Plugin and dependencies, so it
> needs internet access.

---

## Deploying to a LineageOS 15.1 device (ThinkSmart View)

The ThinkSmart View has no Google Play, so install over **adb**.

### 1. Enable developer options & ADB on the device

1. **Settings → About tablet/device → Build number**, tap 7 times to unlock
   Developer options.
2. **Settings → Developer options → Android debugging (USB)** → on.
   - The ThinkSmart View has a single USB-C port. If you can't connect over USB,
     enable **ADB over network** (Developer options → *Wireless ADB* / *ADB over
     network*) and connect with `adb connect <device-ip>:5555`.

### 2. Install the app

```bash
# USB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# or over the network
adb connect 192.168.1.50:5555
adb -s 192.168.1.50:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Grant the microphone permission

Open the **Wyoming Satellite** app and tap **Start satellite** — Android will
prompt for microphone access. You can also pre-grant it:

```bash
adb shell pm grant dev.wyomingdroid android.permission.RECORD_AUDIO
```

### 4. (Recommended) Keep it running

LineageOS is aggressive about killing background apps. For 24/7 operation:

- In the app, enable **Start automatically on boot**.
- **Settings → Battery → (⋮) Battery optimization →** find *Wyoming Satellite*
  → **Don't optimize**.
- Disable **Settings → Battery → Adaptive Battery / aggressive doze** if present.
- The service already holds a partial wake lock + Wi-Fi lock so audio keeps
  streaming with the screen off.

### 5. Configure the satellite

In the app:

| Setting | Notes |
|---|---|
| **Satellite name** | Friendly name shown in Home Assistant. |
| **Port** | Default `10700`. Match this in Home Assistant. |
| **Pipeline mode** | *Wake word* (server-side wake word) or *Always stream to STT*. |
| **Microphone source** | Default *Voice recognition*. Try *Voice communication* for hardware echo cancellation if the device hears its own TTS. |
| **Play spoken responses** | Plays HA's TTS reply through the device speaker. |
| **Start on boot** | Auto-start after reboot. |

Tap **Start satellite**. The status line shows the device IP and port, and
whether Home Assistant is connected / streaming.

---

## Adding the satellite in Home Assistant

**Auto-discovery:** Home Assistant usually finds the device via mDNS and offers
a *Discovered* card for the Wyoming integration — just confirm it.

**Manual:** *Settings → Devices & Services → Add Integration → Wyoming
Protocol*, then enter:

- **Host:** the device IP shown in the app (e.g. `192.168.1.50`)
- **Port:** `10700` (or whatever you set)

A new **Assist satellite** entity/device appears. Open it and:

1. Assign your **Assist pipeline** (the one with STT/TTS configured).
2. For *Wake word* mode, make sure that pipeline has a **wake word engine**
   enabled (e.g. openWakeWord) and a wake word selected.

Say the wake word (or just speak, in *Always stream* mode) and watch the app's
status switch to **Streaming**.

---

## Troubleshooting

- **HA can't connect / not discovered** → add it manually by IP + port. Confirm
  the device IP in the app and that both are on the same subnet. Some networks
  block mDNS; manual add always works.
- **Connected but nothing happens** → in *Wake word* mode you must have a
  wake-word engine in your Assist pipeline. Otherwise switch to *Always stream
  to speech-to-text*.
- **Device hears its own TTS / echoes** → set *Microphone source* to *Voice
  communication* (enables hardware AEC), lower the speaker volume, or disable
  *Play spoken responses* and let another media player handle audio.
- **Stops streaming when screen turns off / after a while** → disable battery
  optimization for the app (see step 4) and enable *Start on boot*.
- **`AudioRecord failed to initialise`** → another app holds the mic, or the
  chosen *Microphone source* isn't supported on the device; try *Microphone
  (raw)*.
- **Logs:** `adb logcat -s SatelliteService WyomingServer AudioCapture AudioPlayback`

---

## Protocol notes / implementation

The Wyoming wire format is implemented in
[`WyomingIo`](app/src/main/java/dev/wyomingdroid/wyoming/WyomingIo.kt): a JSON
header line terminated by `\n`, followed by `data_length` bytes of UTF-8 JSON
and `payload_length` bytes of raw audio. Event handling and the satellite
state machine live in
[`WyomingServer`](app/src/main/java/dev/wyomingdroid/wyoming/WyomingServer.kt).
This matches the reference [`wyoming`](https://github.com/OHF-Voice/wyoming)
Python library so Home Assistant talks to it unmodified.

Audio format: **16 kHz, mono, 16-bit PCM** (what HA's Assist pipeline expects).

## License

See [LICENSE](LICENSE).
