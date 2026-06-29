# Orchestra — first release preparation

Target: the first public release of **Orchestra** (`io.github.thelok1s.orchestra`) — an LSPosed
module + app that adds native Soundcore controls (About-device page + 4-mode ANC) on Pixel /
Android 16. Scope it honestly: ship the **verified, working** surface; mark the rest as roadmap.

Legend: **P0** = blocker · **P1** = should-have · **P2** = nice-to-have · 🧑 = needs your decision.

---

## P0 — blockers (must do before any public build)

- [ ] **Lock down `DebugSendReceiver` (security).** It is `exported="true"` and sends *arbitrary
  RFCOMM frames* to bonded headphones on broadcast — any installed app could drive/abuse it. Fix:
  gate to debug builds only (register/guard with `BuildConfig.DEBUG`) **or** drop `exported` /
  protect with a signature permission. Re-audit the other exported receivers too: `VolumeApplyReceiver`
  + `ConnectReceiver` (`APPLY`/`APPLY_INDEX`) must validate sender or be signature-protected — the
  SystemUI hook (system uid) is the only legitimate caller. (Provider services are already
  `BLUETOOTH_PRIVILEGED`-gated — good.)
- [~] **Release signing.** DONE: env-driven `release` signingConfig in `Orchestra/app/build.gradle.kts`
  (uses `ORCHESTRA_KEYSTORE*` env, falls back to debug locally) + `.github/workflows/release.yml`
  (build `assembleRelease` + GitHub Release on `v*` tag, keystore from secrets). `assembleRelease`
  verified building. REMAINING 🧑: generate a release keystore, add the 4 repo secrets
  (`ORCHESTRA_KEYSTORE_BASE64`/`_PASSWORD`/`ORCHESTRA_KEY_ALIAS`/`_PASSWORD`), and verify the LSPosed
  module loads when release-signed. (Minify stays OFF for now, so no R8 keep-rules needed yet; revisit
  if `isMinifyEnabled` is turned on — keep the devicesettings parcelables + `xposed_init`.)
- [x] **Version + name.** DECIDED: **1.0.0** initial release. `versionName = "1.0.0"`,
  `versionCode = 10000` (MMmmpp scheme; must increase every release). Tag releases `v1.0.0`, `v1.0.1`…
  (the `release.yml` workflow fires on `v*`).
- [x] **LICENSE.** DONE — **GPL-3.0** at repo root (both the app repo and `orchestra-manifests`). Root
  `README.md` credits the third-party RE references (OpenSCQ30 et al., GPL-3.0); they live in
  `re_refs/` (gitignored, not shipped in the APK).

## P1 — release hygiene

- [ ] **Root `README.md`** (user-facing): what it is + a screenshot or two; requirements (rooted Pixel,
  LSPosed/Xposed, Android 16); **install + exact LSPosed scope** (System UI + Settings + Orchestra,
  then restart System UI); how to hook a device; supported devices (Space One Pro, Liberty 4 Pro);
  **disclaimer** (unofficial; sends control frames to your own headphones at your own risk; some bytes
  are reverse-engineered/unverified). Point contributors at `docs/devices/` + `framework/SCHEMA.md`.
- [ ] **Known-limitations / roadmap section** (in README or `docs/`): no in-app screen yet for
  slider/list/composite controls (they show as "in-app only"); Liberty 4 Pro discrete ANC mapping
  unconfirmed; only 2 devices verified; several per-device opcodes still `_verified:false`.
- **Android 17 — volume-panel ANC tile BROKEN (known limitation, deferred post-v1).** On Android 17
  + the "Vector" LSPosed framework (API 101), the **About-device pages work fully** for both devices
  (4-mode + 3-mode ANC, switches, TWS battery — verified on-device 2026-06-29), but the **SystemUI
  volume-panel ANC tile does not render**. The SystemUI hook loads + installs correctly (boot `[MX]`
  logs confirm); the failure is Google reworking the A17 volume-panel ANC component chain
  (StateFlow-based Pixel gating + a module **double-load** on two classloaders + lazily-loaded panel
  classes). Forcing `isPixelDevice`/`isAvailable`/`getUuids` true was insufficient — the gate is
  deeper (ANC component/view-model, not yet RE'd). To be fixed during the **modern-libxposed engine
  rework** (which solves the double-load + correct-classloader/lazy hooking foundation). Full
  diagnosis in agent memory `android17-vector-ondevice.md`; engine plan in
  `docs/superpowers/plans/2026-06-29-dual-lsposed-api-engine.md`.
- [x] **Catalog/device-def distribution** — DONE (2026-06-24). Built the dedicated **`orchestra-manifests`**
  repo (schema v3: versioning + named transport channels + embedded per-ROM `platforms`), divided by
  manufacturer, with a CI-generated `index.json` (freshness-gated). The app now bundles a **seed** of the
  manifests + index in assets (synced at build time via `Orchestra/sync-manifests.sh`), highlights bonded
  devices eligible for hooking from the index, **downloads/updates** manifests OTA from
  `raw.githubusercontent.com/thelok1s/orchestra-manifests/main/` (12h TTL, sha256-verified, revision-gated),
  and supports **sideloading** a local manifest for testing (highest precedence, badged). See
  `docs/superpowers/specs/2026-06-24-manifest-repo-schema-v3-design.md`.
  🧑 **Remaining decision:** push `orchestra-manifests` to GitHub as a **public** repo to make live OTA work
  (the app works fully offline on the bundled seed regardless).
- [ ] **Clean-install QA pass** (document the steps + expected result): uninstall → install signed APK →
  enable in LSPosed + set scope → restart SystemUI → open app, grant BT permission → hook a device →
  open Bluetooth settings once → open the device's About page → confirm ANC 4-mode + switches render
  and toggle. Repeat for both devices.
- [ ] **Crash-safety review**: the hook (Settings/SystemUI) and provider must never crash the host —
  audit the `try/catch` coverage in `OrchestraHooks` + the provider `onTransact` paths.

## P2 — polish

- [ ] **Strings to `strings.xml` + localization.** App UI labels are hardcoded English; device control
  titles already support `*_i18n` (ru done). Extract UI strings; add ru to match.
- [ ] **Screenshots** for README/release (Status tiles, device card with 4-mode ANC + switches, About
  page). Several already captured this session.
- [ ] **In-app control screen** (the deferred architecture): a row on the About page (ActionSwitch +
  IntentAction) that launches an Orchestra Compose screen with the sliders/EQ/composite controls that
  the native page can't render. Big; likely post-v1.
- [ ] **Volume-panel independence (#2)**: make the panel ANC tile Orchestra-fed (4-mode there too) and
  customizable independently of the About page. Big; likely post-v1.
- [ ] **Verify TWS battery card** on a connected Liberty (metadata read may be blocked for our
  non-privileged process → falls back to single `getBatteryLevel()`); confirm L/Case/R chips populate.

---

## Suggested v1 cut line
Ship: native About-page controls for Soundcore Space One Pro + Liberty 4 Pro — **4-mode ANC** + the
verified switches (Dolby, Surrounding sounds, Side tone, Low-battery, Multipoint), per-control
enable/disable, the redesigned app (status/devices/settings/debug). **Defer to roadmap**: volume-panel
control, in-app slider/EQ screen, more devices, unverified opcodes.
