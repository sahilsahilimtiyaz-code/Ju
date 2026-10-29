# AUDIT — Brahma AI → JARVIS upgrade (Phase 0)

## Repo structure (observed, 2026-10-29)
- Root (`~65% Python`): PyQt6 Windows desktop assistant `main.py` + `core/*.py` (~90 modules:
  `local_brain.py`, `memory_cortex.py`, `live_voice_engine.py`, `mcp_manager.py`,
  `skills_engine.py`, `skill_forge.py`, `learned_rules.py`, `anticipatory_watcher.py`,
  `protocols.py`, …) plus `memory/`, `actions/`, `features/`, `smart_home/`, `skills/`,
  `dashboard/` (web), `installer/`. Wired with `pyaudio`, `pyautogui`, `pywinauto`,
  `win10toast`, `psutil`, `opencv`, `mediapipe`. Entry point: `main.py` (PyQt6 UI thread).
- `brahma-connect-android/` (`~28% HTML/JS` incl. three.js shader background in assets +
  Kotlin/TS): a **companion agent**, not the assistant. WebSocket client to the Brahma
  Gateway; device commands (flashlight, battery, volume, launch app); accessibility
  service. UI = Compose shell around a **WebView** (`assets/web_background/index.html`).
- CI: `.github/workflows/build-all-platforms.yml` → PyInstaller Windows .exe / macOS .dmg.
  **No APK pipeline exists yet.**

## How Python runs on the phone TODAY
It does not. There is no Chaquopy/Termux/Python runtime in any Android target. The Python
core is a Windows-only desktop executable (PyInstaller). Nothing in the repo executes
Python on a phone.

## What Kotlin does today
Only `brahma-connect-android`: pairing, gateway WS, command execution, accessibility.
It is a remote-control arm of the desktop brain, not a brain.

## What is web
- `brahma-connect-android/app/src/main/assets/web_background/*` (three.js HUD inside a WebView)
- `dashboard/`, `smart_home_page_new.py` (server-side HTML), misc `.html/.js/.ts/.css`.

## Decision for the upgrade (full detail in DECISIONS.md)
- New native app project `jarvis-android/` (Compose + Ktor-free, OkHttp, Vosk wake word,
  raw SQLite). Brain implemented **on-device** mirroring the Python core's architecture
  (router / memory / persona / proactive / evolution / TTS+STT routers / tool registry /
  MCP client). The Python core stays untouched = desktop brain + optional remote-brain
  gateway target.
- Legacy web files listed in LEGACY_WEB.md; nothing deleted.
