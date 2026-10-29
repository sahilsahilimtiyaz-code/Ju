# jarvis-android — Brahma → J.A.R.V.I.S. (native)

Native Android replacement for the Brahma assistant shell: Jetpack Compose UI (no HTML/
CSS/JS/WebView anywhere in the new code), on-device brain, always-on voice.

## Features

- **Wake word** `hey jarvis` / `jarvis` / `wake up jarvis` — Vosk offline keyword spotting
  inside a foreground mic service (notification stays persistent). Microphone stays live
  until you switch it off. On reboot: "Tap to wake Jarvis" notification (Android 14+
  forbids background mic starts).
- **Greeting** — time- and memory-aware, e.g. "Good evening, sir. It's 7:42. All systems
  are online and at your service. What are we cooking, sir?"
- **Brain (Python-core architecture, native)** — persona, LLM router (OpenAI-compatible:
  OpenRouter/Groq/DeepSeek + Anthropic) with fast/strong routing and a fallback chain;
  tool loop; optional remote-brain mode (base URL + Bearer token).
- **Memory never forgets** — SQLite WAL raw log + atomic facts (LLM extractor, dedupe and
  supersede), core profile block, session summaries, retrieval via FTS4 keyword +
  local hashed-vector cosine, recall inside a fixed budget. Voice commands and tools:
  memory_save / memory_search; Settings > Memory Hard-wipe.
- **Voice pipeline** — streaming STT (Android recognizer default; Deepgram WS; Fish
  Audio; generic custom HTTP POST), TTS (Fish Audio live WS + REST; ElevenLabs stream-input
  WS Flash v2.5; Android TTS fallback; generic custom provider), sentence-chunked so
  speech starts while the model is still generating.
- **Always-on** — foreground service type microphone, audio focus, barge-in (speaking
  pauses on loud speech within ~200 ms and re-arms wake), self-hearing prevention.
- **Actions** — alarm, timer, reminders (exact), calendar read, open app, flashlight,
  volume, call dialer, SMS compose (irreversible ones require confirmed:true), web
  search, notification read-out (NotificationListenerService), system report.
- **MCP** — user-configured MCP servers over Streamable HTTP JSON-RPC
  (initialize / notifications/initialized / tools/list / tools/call) exposed to the
  LLM as extra tools next to device tools.
- **Proactive** — gate-checked morning/evening briefs and device events
  (charger, low battery), quiet hours, daily budget, per-kind cooldown, learned
  aversion from signals, heads-up notification delivery.
- **Self-evolution** — nightly reflection proposes wording/timing/memory edits from real
  signals; regression-scored against a golden set of your real prompts; low-risk edits
  auto-apply and are versioned with a 1-tap rollback; kill switch in Settings.
- **Keys** — API keys live only in EncryptedSharedPreferences. Nothing in code, DataStore
  or git.

## Build (from a phone via GitHub Actions)

Push to `main` triggers `.github/workflows/build-apk.yml` which runs unit tests, builds
`app-debug.apk` and `app-release-unsigned.apk` and attaches them as downloadable
artifacts on the workflow run page.

## Build locally

```
cd jarvis-android
./gradlew assembleDebug      # JDK 17, Android SDK 35
```

Install `app/build/outputs/apk/debug/app-debug.apk`, grant mic + notifications,
open Tools > Diagnostics > START/STOP MIC. First run of the wake word needs the model
download in Settings > Voice (once, ~40 MB).

## Honest status

See `../PROGRESS.md` and `../DECISIONS.md` for per-feature verdicts and every decision made.
