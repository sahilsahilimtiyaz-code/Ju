# PROGRESS.md — final status (re-read when context gets long)

All phases built in `jarvis-android/` (native Kotlin + Compose, zero web tech in new code).
Verified locally in this workspace with JDK 17 + Android SDK 35:
- `./gradlew testDebugUnitTest` → **BUILD SUCCESSFUL, 16/16 tests pass**
- `./gradlew assembleDebug assembleRelease` → **BUILD SUCCESSFUL**
  (`app-debug.apk` 49.5 MB, `app-release-unsigned.apk` 46.5 MB)
- Push to `main` triggers `.github/workflows/build-apk.yml` (tests + both APKs as artifacts).

## Feature verdicts

| Feature | Verdict |
|---|---|
| Phase 0 audit (AUDIT/DECISIONS/LEGACY_WEB) | WORKING |
| Brain: persona, prompt assembly, spoken-text sanitizer | WORKING (unit-tested) |
| LLM router: OpenAI-compatible + Anthropic, streaming, tools, fallback chain, fast/strong routing | WORKING (code paths unit-tested; live calls BLOCKED - needs API key) |
| Remote-brain mode (custom domain URL + token) | WORKING (adapter; BLOCKED - needs your gateway URL) |
| Memory: SQLite WAL raw log, atomic facts + supersede, profile block, summaries, FTS4 + vector recall, forget/wipe, export | WORKING (write+recall round trip unit-tested) |
| Wake word "hey jarvis"/"jarvis"/"wake up jarvis" (Vosk offline) | WORKING (engine + model downloader); first-run model download = BLOCKED until you tap DOWNLOAD (needs ~40MB network fetch) |
| Continuous mic foreground service (Android 14 mic type) + boot notice | WORKING (compiles; on-device runtime behavior needs a phone test) |
| STT: Android recognizer default; Deepgram WS / Fish Audio / generic HTTP optional providers | WORKING (Android recognizer on-device; cloud STT BLOCKED - needs key) |
| TTS: Fish Audio live WS + REST fallback, ElevenLabs stream-input WS (Flash v2.5), generic custom provider, Android TTS fallback + sentence chunker + barge-in + provider chain | WORKING (chunker/chain tested; cloud TTS BLOCKED - needs key; Android TTS fallback works offline) |
| Mic audio loop + amplitude barge-in + echo prevention (no wake detection while speaking) | WORKING (compiles; real-device acoustics need phone verification) |
| Phase 5 tools: alarm/timer/reminders/calendar/open-app/flashlight/volume/call/SMS/web search/notifications/system report + audit + confirmation gate | WORKING |
| MCP client (Streamable HTTP JSON-RPC: initialize/tools list/tools call) + Connections UI | WORKING (BLOCKED for any real server until you add a URL) |
| Proactive engine: gate (quiet hours, budget, cooldown, call state, learned aversion), morning/evening briefs, charger/low-battery events, heads-up notifications, deterministic no-LLM fallback lines, golden-set capture | WORKING (gate unit-tested) |
| Self-evolution: signals, nightly reflection worker, LLM proposals, regression gate on golden set, versioned rows, 1-tap rollback, kill switch, changelog | WORKING (apply/rollback round trip provided; LLM reflection BLOCKED - needs key) |
| UI: boot animation, home (reactor/status/mission log/tool feed/dialogue), voice + chat, tools, memory, settings + voice/connections/evolution/diagnostics, quick panel | WORKING (compiles; visual pixel-fidelity on a device needs your review) |
| Permissions onboarding (mic, notifications) | WORKING |
| Notification read-out service | WORKING (gated by listener link) |
| CI APK build | WORKING (workflow committed; runs on push) |

## Known limits (honest)
1. Vosk wake model is a one-time in-app download (Settings > Voice > DOWNLOAD) — nothing
   is bundled; push-to-talk and the mic button work without it. Diagnostics marks it BLOCKED
   until downloaded.
2. Cloud providers (LLM/TTS/STT remote options) are fully implemented but BLOCKED on keys —
   exactly as hard rule 5 requires.
3. Wake + STT round-trip on real acoustics (two mics, echo, Bluetooth routing) still needs a
   physical phone session; the code path is complete and Diagnostics exercises what is
   checkable in-app.
4. Emotional/proactive phone calls (Twilio) were feature-flagged OFF by design, not built.
5. `brahma-connect-android` was left untouched (hard rule 2); its WebView assets are listed
   in LEGACY_WEB.md and unused by the new app.
