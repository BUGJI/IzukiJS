# Izuki JS

[简体中文](README.md) · [English](README_EN.md)

> The name Izuki comes from the Japanese「いづき」, meaning "to reside / to attach."<br>
> In the context of automation scripts it suggests a script that clings to the system like a shadow, executing tasks quietly, continuously and reliably.<br>
> The `-js` suffix keeps scripts written in JavaScript and makes the same script more portable.

An Android automation script runtime built with Kotlin / Jetpack Compose. Write scripts in
JavaScript (QuickJS) and drive the device by combining multiple control backends —
**Accessibility / Shizuku / Root / Bluetooth HID** — with built-in image matching, color finding,
OCR and AI-Agent recovery.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/BUGJI/IzukiJS?sort=semver&label=Release)](https://github.com/BUGJI/IzukiJS/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%2028%2B-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)]()

## Screenshots

<p align="center">
  <img src="docs/images/run.png" width="22%" alt="Run page" />
  <img src="docs/images/editor.png" width="22%" alt="Script editor" />
  <img src="docs/images/logs.png" width="22%" alt="Logs page" />
  <img src="docs/images/settings.png" width="22%" alt="Settings page" />
</p>

<p align="center">
  <sub>Run status &amp; capabilities / Script editor / Leveled logs / Global settings</sub>
</p>

## Features

- **Multiple control backends with capability negotiation and fallback**: Accessibility, Shizuku,
  Root and Bluetooth HID are unified behind a `Capability` abstraction; set a global or per-capability
  priority and the runtime picks the backend that is actually available.
- **JavaScript script runtime**: based on QuickJS with an isolated runtime per script; exposes input
  and the `device` / `app` / `selector` / `shell` / `images` / `ocr` / `notify` / `fs` / `http` /
  `clipboard` / `permissions` / `ai` interfaces, with `// @need` to expose only what a script uses.
- **Hybrid control**: the recommended setup is "read the view tree via Accessibility + inject input
  via HID/Shizuku" — each backend does what it does best.
- **Vision**: OpenCV image / color matching, local MLKit Chinese OCR or online OCR (Baidu /
  Google Vision / custom), with automatic fallback to local when online fails.
- **Continuous screen capture**: authorize once and keep it resident, providing unlimited screen
  frames for capture / image matching / OCR and bypassing the Accessibility screenshot limits.
- **AI Agent recovery**: when a script gets stuck, hand the screen to an OpenAI-compatible model to
  observe and operate the device; native function calling is used when supported, otherwise it falls
  back to a prompt protocol.
- **MCP server**: exposes the device's screen-reading and control abilities to external AI clients
  (Claude Desktop / Cursor / Cline) over Model Context Protocol, with LAN / local-only listening,
  token auth and per-tool toggles; includes script execution, log reading, a screenshot resource
  and a redacted audit log.
- **Bluetooth HID injection**: drives the device with real hardware events through an external HID
  device (e.g. an ESP32 digitizer dongle, firmware at
  [IzukiJS_HID](https://github.com/BUGJI/IzukiJS_HID)) for scenarios that need hardware-level input.
- **System capability extensions**: opt-in notification listening (`notify.*` read / wait for new /
  clear), usage access (`app.foreground()` and usage records), app list (`app.list()` to enumerate
  installed apps), full file access (`fs.*` to read/write shared storage) and script network access
  (`http.*`, off by default with an optional domain allowlist); scripts can hold a wake lock while
  running (optionally waking the screen), and a Quick Settings tile can run a pinned script with one tap.
- **Debugging & tools**: a layout inspector that generates selector code, a draggable debug overlay
  (layout / OCR coordinate overlay), a floating control bar, scheduled tasks (cron / charging /
  Wi-Fi / unlock triggers, condition filters, retries), the Quick Settings tile, per-file log
  storage with automatic cleanup; the editor supports API completion, tags, version history,
  run-from-cursor and variable/state inspection, plus run timeouts and dead-loop interruption.
- **Bilingual UI**: ships with Simplified Chinese and English, switching automatically with the
  system language.
- **Config backup**: one-tap export / import of AI / OCR / control / screenshot / script network /
  log / editor preferences; sensitive keys are encrypted with the Android Keystore. Because the Keystore key
  cannot migrate across devices, system-level auto backup excludes configs that contain ciphertext —
  **use the in-app export / import when switching devices**.

## Usage & Disclaimer

Izuki JS is an experimental runtime for personal-device automation and accessibility scenarios. Its
multiple control backends are intended for writing automation scripts on devices you own or are
explicitly authorized to use.

Please use it only for lawful purposes and comply with local laws and the terms of service of any
third-party apps. This project is provided "as is" under GPL-3.0, with no guarantee of stability or
fitness; users assume all consequences of use.

> HID injection and the AI Agent may conflict with third-party terms of service. Use them only on
> devices and in scenarios you are authorized to operate.

## Project Status

The project is at an early experimental stage. The script API and backend behavior may change at any
time, and it is not recommended for critical tasks or production environments. Feedback via issues
is welcome.

## Control Modes & Capabilities

| Capability | Accessibility | Shizuku | Root | Bluetooth HID |
|---|---|---|---|---|
| Gestures / absolute coordinates | ✅ | ✅ | ✅ | ✅ |
| Keyboard / text | Partial | ✅ | ✅ | ✅ |
| Read view tree | ✅ | △ dump | △ dump | ❌ |
| Screenshot | ✅ (API30+) | ✅ | ✅ | ❌ |
| Shell | ❌ | ✅ | ✅ | ❌ |

> In "Read view tree", `△ dump` for Shizuku / Root means the tree is obtained via
> `uiautomator dump` (about 1–2 s, occasionally fails) as a screen-reading fallback for the AI Agent;
> **script-level `selector.*` still only supports Accessibility**.

Besides the control backends, the "Permissions & Capabilities" page offers switch-based read /
operate helpers: notification listening (`notify.*`), usage access (`app.foreground()` and more),
app list (`app.list()`) and full file access (`fs.*`), plus keep-awake / wake screen and the Quick
Settings tile (see [`docs/TOOLS.md`](docs/TOOLS.md)).

Scripts can query readiness with `permissions.*`; see [`docs/SCRIPT_API.md`](docs/SCRIPT_API.md).

## Getting Started

### Option 1: Download (recommended)

Download the APK from [Releases](https://github.com/BUGJI/IzukiJS/releases/latest) and pick the one
matching your device architecture:

| File | Device |
|---|---|
| `app-arm64-v8a-release.apk` | Most modern phones (first choice) |
| `app-armeabi-v7a-release.apk` | Older 32-bit devices |
| `app-x86_64-release.apk` | Emulators / x86 tablets |

If installs are blocked as "unknown source", allow that source in system settings. This app uses
high-privilege features such as Accessibility / Shizuku / Root, so only install packages from a
trusted source.

> Release builds fall back to the debug signature when no release signing is configured (see
> "Build" below) and are for distribution testing only; configure `keystore.properties` as described
> below to build a properly signed release yourself.

### Option 2: Build from source

#### Requirements

- Android 9 (API 28) or above
- Build: JDK 17 (required by Gradle; source compatibility level is Java 11), Android SDK
  (`compileSdk 37`), Android NDK `27.1.12297006` and CMake `3.22.1` (for the JNI shim that
  exposes QuickJS interrupt callbacks)

#### Build

```bash
# Clone
git clone https://github.com/BUGJI/IzukiJS.git
cd IzukiJS

# Build debug
./gradlew :app:assembleDebug

# Build release (falls back to debug signing when unsigned; for local verification only)
./gradlew :app:assembleRelease
```

For proper signing, copy `keystore.properties.example` to `keystore.properties` and fill in your key
details (the file is ignored by `.gitignore` and never committed).

Artifacts are split per ABI: `arm64-v8a` / `armeabi-v7a` / `x86_64`.

### First Run

1. Open the app and, under "Run → Permissions & Capabilities", enable the backends you need within
   the "Read / Operate / Other" groups:
   - **Accessibility**: jump to the system accessibility settings and enable it (view tree,
     API30+ screenshots, basic gestures).
   - **Shizuku**: install and start Shizuku first, then authorize in the app (input injection,
     screenshots, Shell).
   - **Root**: detect Root and authorize through `su`.
   - **Bluetooth HID**: scan for and connect an external HID dongle.
   - **Other capabilities** (switch-based): notification listening, usage access, app list, full
     file access, keep-awake / wake screen, Quick Settings tile.

2. For unlimited screenshots, authorize "Screen capture" once under "Permissions & Capabilities" and
   start it.
3. Create a script on the "Scripts" page, edit it and run it; the editor supports JS syntax
   highlighting, find & replace, undo/redo, API completion and version history. Scripts support
   tags and can be searched by name or tag. You can pin frequently used scripts to the Quick
   Settings tile or add them to scheduled tasks.

> Tip: while a script runs you can revoke unnecessary permissions and keep only the modes that
> script actually needs, reducing unnecessary permission exposure.

## Script Example

```js
// Launch an app → wait for the login button → click
app.launch("com.example.app");
sleep(1500);

var hit = ocr.find("登录");
if (hit) {
  click(hit.x, hit.y);
  log("Clicked login @ " + hit.x + "," + hit.y);
} else {
  toast("Login button not found");
}

// Click via the view tree
if (selector.existsById("com.example.app:id/confirm")) {
  selector.clickById("com.example.app:id/confirm");
}

// Hand off to the AI when stuck
if (ai.available()) {
  ai.run("Close the ad popup and return home. Screen text: " + ocr.recognize(), {
    maxSteps: 6,
    onTool: function (name, args) { log("→ " + name + " " + args); }
  });
}
```

For the full API see [`docs/SCRIPT_API.md`](docs/SCRIPT_API.md).

## Documentation

> The documents under `docs/` are currently Chinese-only.

| Document | Contents |
|---|---|
| [`docs/SCRIPT_API.md`](docs/SCRIPT_API.md) | Script API reference: global functions, input, selectors, image matching, OCR, AI, and more |
| [`docs/TOOLS.md`](docs/TOOLS.md) | Tools and permissions: control modes, continuous capture, debug overlay, scheduled tasks, logs |
| [`docs/BLE_HID_PROTOCOL.md`](docs/BLE_HID_PROTOCOL.md) | Bluetooth HID control protocol: GATT services, frame format, coordinate mapping, connection flow |
| [`docs/MCP.md`](docs/MCP.md) | MCP server: enabling, client setup, tools and resources, security notes |
| [`CHANGELOG.md`](CHANGELOG.md) | Release history |

## Project Structure

```
app/src/main/java/com/benton/izukijs/
├── ai/            # AI Agent: config, client, tool calls, prompts
├── mcp/           # MCP server: JSON-RPC protocol, HTTP transport, tools & resources
├── controller/    # Control abstraction layer and backend implementations
│   ├── accessibility/  # Accessibility
│   ├── shizuku/        # Shizuku
│   ├── root/           # Root
│   ├── hid/            # Bluetooth HID (protocol / GATT client / controller)
│   └── shell/          # Command backend
├── runtime/       # QuickJS runtime and script API bindings (runtime/api)
├── ocr/           # Local MLKit / online OCR engines
├── service/       # Foreground services: scripts, overlay, debug overlay, capture, notification listener, Quick Settings tile
├── schedule/      # Scheduled tasks and boot re-registration
├── data/          # Script repository, editor settings, config backup
├── security/      # Key encryption
├── ui/            # Compose UI (run / scripts / editor / settings / ...)
└── model/         # ControlMode / Capability, etc.

app/src/main/cpp/  # JNI shim for QuickJS interrupt callbacks
```

## Tech Stack

- Kotlin 2.2.10 · Jetpack Compose (Material 3) · Navigation Compose
- [QuickJS-Android](https://github.com/taoweiji/quickjs-android) (`com.github.hjhrq1991:quickjs-android`,
  using a 16KB-page-size-compatible fork distributed via JitPack)
- [Shizuku](https://shizuku.rikka.app/) · OpenCV · MLKit Chinese text recognition
- Kotlin Coroutines · Timber

## Security

- **Generated on-device, encrypted locally**: the MCP access token is generated locally on the
  device and stored encrypted with the Android Keystore (`SecretCipher`); it is never uploaded to any
  server, and can be rotated via "Regenerate" on the settings page.
- **Network listening**: MCP listens on localhost only by default (used with `adb forward`),
  and can be switched to the LAN (reachable by devices on the same Wi-Fi). Anyone holding the token
  can read the screen and control the device, so use it only on trusted networks.
- **Least privilege by default**: `shell` and script execution are off by default and must be
  explicitly enabled on the settings page; high-risk commands are blocked by default. See
  [`docs/MCP.md`](docs/MCP.md) for details.

## License

This project is released under the [GNU General Public License v3.0](LICENSE).

```
Copyright (C) 2026 BUGJI

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```
