# LEGACY_WEB.md — deprecated web-tech surfaces (NOT deleted, per hard rule 2)

These files stay in the repo for reference/history. The new native app does not load any of
them. Once the native replacements shipped (see jarvis-android/), they are dead weight:

- `brahma-connect-android/app/src/main/assets/web_background/index.html` — WebView HUD (three.js)
- `brahma-connect-android/app/src/main/assets/web_background/index_golden_orb_backup.html`
- `brahma-connect-android/app/src/main/assets/web_background/*.js` (three.min.js, EffectComposer,
  RenderPass, ShaderPass, CopyShader, UnrealBloomPass, OrbitControls, LuminosityHighPassShader)
- Native replacement: `jarvis-android/ui/components/ReactorOrb.kt` (pure Canvas/Compose —
  no WebView, no JS).
- `dashboard/`, `smart_home_page_new.py*`, any `*.html` shipped by the Python desktop app —
  desktop-only, untouched, unused by the Android app.
