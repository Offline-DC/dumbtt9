# KT9 — Bottom "gap / black strip" investigation & plan (v2, deep-researched)

> **UPDATE (r38) — device test result.** r37 (theme via `android:theme` on the `<service>`) did
> **not** work: the `TREE` still showed `LinearLayout … mB=30` on every frame. Reason: **a
> `<service android:theme>` does not reach the IME window.** KikaIME applies its theme by calling
> `setTheme(R.style.IMEServiceTheme)` in `onCreate()` **before** `super.onCreate()` (which creates the
> SoftInputWindow). r38 does the same: `setTheme(R.style.TTheme_Ime)` in `KeyPadHandler.onCreate`.
> r38 also (a) turns the proven runtime margin-removal fallback back **ON** so the keyboard is usable
> immediately, and (b) adds a `KT9geo` **`FLAGS`** log that prints `drawsSysBars=` and `decorBg=` so we
> can finally see whether the theme actually applied. **Open question the r38 logs will answer:** is
> the 30px margin caused by system-bar backgrounds at all? If `FLAGS` shows `drawsSysBars=false` but
> `TREE` still shows `mB=30`, the theme is not the cause and the margin removal fallback is the real
> fix (and we stop chasing the theme). See §6 for how to read it.

Working notes for the tray-keyboard bottom strip. This version supersedes v1 after a second,
adversarial research pass across **three** codebases (KikaIME, this fork, and upstream sspanak/tt9)
plus the Android framework and other keyboards. The root cause changed from v1; read §3–§5.

Device: TCL 4058R, **Android 11**, QVGA 240×320, no touchscreen, no real navigation bar
(`sysBottom = 0`), tray/small layout.

---

## 1. TL;DR — what this is and what I changed (r37)

**The strip is caused by the IME WINDOW THEME, not by insets and not by anything per-frame.**
The fork's IME window inherits `TTheme`, a **Material** theme, which sets
`windowDrawsSystemBarBackgrounds = true`. On our full-screen (KikaIME-style) transparent candidates
host, that flag makes the framework **reserve the device's navigation-bar height (~30px) as a bottom
margin on the decor's content**, and the **opaque Material window background paints that reserved
strip black**. KikaIME never gets it because its IME window theme is plain **AppCompat** (no
system-bar backgrounds) with a **transparent** window background.

**r37 fixes it the way KikaIME does — declaratively, with a dedicated IME window theme:**

- `app/src/main/res/values/styles.xml` — new `TTheme.Ime` (parent `TTheme`) with
  `windowDrawsSystemBarBackgrounds=false` and `windowBackground=@android:color/transparent`.
- `app/src/main/AndroidManifest.xml` — the `<service>` now sets `android:theme="@style/TTheme.Ime"`.
- `TraditionalT9.onWindowShown()` — the old runtime work-arounds (clearFlags + pre-draw margin
  listener) are gated **off** behind `USE_RUNTIME_MARGIN_FALLBACK = false`, so this build tests the
  theme fix in isolation. Diagnostics stay on.

This is the "simple, elegant, performant like the previous keyboard" fix you asked for: **no
per-frame work, no listener, no margin-poking — one style + one manifest attribute, mirroring
KikaIME.** The keyboard KEYS are unaffected because they're inflated with their own `R.style.TTheme`
context (`ThemedContextBuilder`), so the service window theme only changes the window chrome.

**Next step:** build r37 and confirm on device (test plan in §6). If the `TREE` dump shows the decor
child at `mB=0` and the bar flush at the bottom, delete the now-dead runtime work-arounds (§6 step 4).

---

## 2. Side-by-side: three keyboards (all verified this pass)

| Aspect | KikaIME (reference, no strip) | **upstream sspanak/tt9** (no strip) | this fork before r37 (strip) |
|---|---|---|---|
| `targetSdk` | **30** | **37** | 36 |
| IME window theme | `IMEServiceTheme → AppTheme → Theme.AppCompat.Light.DarkActionBar` | `TTheme` = `Theme.MaterialComponents.DayNight` (Material) | `TTheme` = Material (inherited; service had **no** `android:theme`) |
| Draws system-bar backgrounds? | **No** (AppCompat) | Yes (Material) — but window is short, see below | **Yes** (Material) + full-screen window → strip |
| Window background | **transparent** (`IMEServiceTheme`) | Material default (opaque) | Material default (opaque) → the black |
| IME window shape | **full-screen** transparent candidates host | **short** wrap_content keyboard (keyboard = input view) | **full-screen** transparent candidates host |
| `onComputeInsets` | `content=visible=screenHeight` idle; `screenHeight − barHeight` when candidates up | `if (clearInsets() && shouldBeVisible()) contentTopInsets = 0;` | `contentTopInsets` = bar's real top; `screenHeight` idle |
| Runtime nav-bar / flag code | **none** | **none** | clearFlags + pre-draw listener (r36) — now gated off |
| `onEvaluateFullscreenMode` | `false` | (default) | `false` |

Key reads this pass (all confirmed):
- KikaIME `IMEServiceTheme` (decompiled `/tmp/kika_res/.../values/styles.xml:963`):
  `parent=AppTheme` (`Theme.AppCompat.Light.DarkActionBar`), `windowBackground=@android:color/transparent`,
  `colorPrimaryDark=@android:color/transparent`. **No `windowDrawsSystemBarBackgrounds` anywhere.**
- KikaIME `HDKeyboardService` sets **no** window flags, **no** nav-bar code; its whole layout logic is
  `onComputeInsets` (screen-height based) + empty input view + full-screen candidates. (`/tmp/kika_src/.../HDKeyboardService.java:1312`.)
- **Upstream tt9 (master) `TraditionalT9.onComputeInsets`** is literally just:
  ```java
  if (settings.clearInsets() && shouldBeVisible()) {
      // otherwise the MainView wouldn't show up on Sonim XP3900,
      // or it expands the application window past the edge of the screen
      outInsets.contentTopInsets = 0;
  }
  ```
  Upstream `app/build.gradle`: `compileSdk = 37`, `targetSdk = 37`, `material:1.14.0`. So upstream, at
  a HIGHER targetSdk than us and the same Material theme, has **no strip** with zero theme/flag code.

---

## 3. Root cause (corrected — high confidence)

The decisive evidence is that **upstream tt9 (targetSdk 37, Material) has no strip, while this fork
(targetSdk 36, Material) does.** Same theme family, higher SDK upstream — so the strip is **not**
"Material" or "high targetSdk" by themselves (that was v1's error). The one structural thing the fork
changed vs upstream is the **IME window shape**: upstream's window is a short `wrap_content` keyboard;
the fork's is a **full-screen** transparent candidates host (to match KikaIME).

Mechanism on **Android 11** (the actual device):

1. `TTheme` is Material → the IME window has `windowDrawsSystemBarBackgrounds = true`.
2. With that flag set, `DecorView` reserves space for the navigation-bar background — a **bottom
   margin equal to the device's configured `navigation_bar_height` (~30px here)** — on its content
   child. On upstream's short window this reservation has no visible effect; on our **full-screen**
   window it steals 30px from the bottom of the candidates host, so the bar stops ~30px short.
3. The Material window background is **opaque** (dark in night mode), so that reserved 30px paints
   **black** — the strip. (This is the classic pre-Android-15 `updateColorViews` behavior; it does
   not need the actual runtime nav-bar inset to be non-zero, which is why `sysBottom = 0` yet the
   strip appears. `TREE` confirmed the 30px is a bottom **margin** on the decor's content child.)

KikaIME avoids **both** halves declaratively: AppCompat theme → no `windowDrawsSystemBarBackgrounds`
→ no reservation; and `windowBackground = transparent` → even an exposed strip would be see-through.

### Honest caveat about the Android-15 "edge-to-edge" red herring
Other keyboards (e.g. HeliBoard #1439) hit a *similar-looking* bottom-gap, but that one is **Android
15's edge-to-edge enforcement**, which only activates for apps **targeting SDK ≥ 35 running on
Android 15+ devices**, and whose opt-out (`windowOptOutEdgeToEdgeEnforcement`) is **disabled at API
36 on Android 16**. **Jack's device is Android 11, so that enforcement path is not active here** —
our strip is the older `windowDrawsSystemBarBackgrounds` mechanism above. (Relevance: if you ever run
this on an Android 15+ phone at targetSdk 36, you may see a *different* bottom-gap that the theme fix
may not fully cover; you'd handle that by reading the real bottom inset and padding by it, HeliBoard-
style. Not needed for the TCL.)

---

## 4. Conflicting approaches I found (and how they reconcile)

You asked me to surface conflicting advice. There are genuinely **three different "correct" fixes**
in the wild for "IME leaves a strip/gap at the bottom", and they solve **different** manifestations:

1. **Upstream tt9: `outInsets.contentTopInsets = 0`** (gated on `clearInsets()`, default true on
   Sonim Gen2 rugged phones). This is an **inset/app-resize** fix for upstream's short-window
   architecture, where the symptom is "the app window expands past the screen edge." It does **not**
   port cleanly to our full-screen architecture: our tray must reserve only the *bar* height, so
   forcing `contentTopInsets = 0` would over-reserve (every app would fully pan for just the bar).
   Also, our strip is a **decor margin**, which `contentTopInsets` doesn't touch. → Not our fix, but
   proof the tt9 codebase treats this as an inset problem for its own layout.

2. **KikaIME: an AppCompat IME window theme with transparent background.** A **window-theme** fix
   that removes the reservation at the source and makes any exposed area transparent. **This is the
   one that matches our architecture** (KikaIME is full-screen like us) and our symptom (decor margin
   + black paint). → **This is what r37 does.**

3. **HeliBoard: read the bottom inset and add it as keyboard bottom padding.** An **inset-consuming**
   fix for Android-15 edge-to-edge enforcement (a genuinely different mechanism). → Not applicable on
   Android 11; kept only as a note for future high-SDK devices.

The fork's **r36** approach (runtime `clearFlags` + pre-draw margin listener) was a fourth path: the
*runtime* equivalent of KikaIME's theme. It works but is the "fight the framework every frame" hack
you disliked. r37 replaces it with KikaIME's declarative theme and gates r36 off.

Why the theme is preferable to r36's clearFlags on this device: both target the same flag, but the
theme applies at window creation (before the first layout/measure), so the margin is **never
computed** — no transient 30px frame, no listener, no re-assertion race with
`setNavigationBarBackground()` (which runs in `initUi` and was the suspected re-setter that kept the
r36 listener firing).

---

## 5. What r37 changes (exact)

```
app/src/main/res/values/styles.xml
  + <style name="TTheme.Ime" parent="TTheme">
  +     <item name="android:windowDrawsSystemBarBackgrounds">false</item>
  +     <item name="android:windowBackground">@android:color/transparent</item>
  + </style>

app/src/main/AndroidManifest.xml
    <service android:name=".ime.TraditionalT9" ...
  +     android:theme="@style/TTheme.Ime"

app/.../ime/TraditionalT9.java
  + private static final boolean USE_RUNTIME_MARGIN_FALLBACK = false;   // r36 hacks gated off
    onWindowShown(): the clearFlags + installImeMarginFixer + removeImeBottomMargin block now runs
                     only if USE_RUNTIME_MARGIN_FALLBACK. Diagnostics (logGeometry/logFrames/
                     dumpImeTree) still run every show.
    KT9_BUILD = "KT9 build r37 — DECLARATIVE FIX ..."
```

Safety notes: the keys keep Material styling (own `R.style.TTheme` context via `ThemedContextBuilder`,
confirmed at `BaseMainLayout.getView():89`). Large touch layouts also use this window theme; on a
phone WITH a nav bar the only change is that the nav bar no longer gets tt9's blended background
behind the keyboard — cosmetic, matches KikaIME, and irrelevant on the TCL (no nav bar). Verified:
both XML files well-formed; `TraditionalT9.java` braces 111/111, parens 426/426.

---

## 6. Decisive on-device test plan (tomorrow)

**Step 0 — build & confirm APK.** Build `debug` (installs as `com.offlineinc.dumbtt9.debug`).
`adb logcat -s tt9/MAIN:I tt9/KT9geo:D` → confirm `===> KT9 build r37 …`.

**Step 1 — look at the tray.** Focus a text field, type a couple letters (suggestions show). Check
`KT9geo`:
- `TREE` → the decor's `LinearLayout` child should read **`mB=0` from the first frame** (r36 showed
  `mB=30`). **This is the single most important number.**
- `BAR@y296` / `SLOT@y296` (24px bar) or the taller equivalent, `insets content=296`.
- Idle (no bar): no black strip on apps without a nav bar.

**Step 2 — interpret.**
- `mB=0`, bar flush, no strip → **theme fix confirmed.** Go to Step 4.
- `mB=30` still, or strip still present → the theme didn't take. Sanity-check the service actually
  used `TTheme.Ime` (typo? build cached? `adb shell dumpsys package com.offlineinc.dumbtt9.debug |
  grep -i theme`). If needed, flip `USE_RUNTIME_MARGIN_FALLBACK = true` to restore the r36 fallback
  while investigating, and/or try §7 options.

**Step 3 — (only if theme failed) escalate** per §7.

**Step 4 — delete the dead code (once mB=0 confirmed).** Remove from `TraditionalT9.java`:
`USE_RUNTIME_MARGIN_FALLBACK`, `imeMarginFixer`, `imeMarginListenerDecor`, `imeMarginCancelStreak`,
`installImeMarginFixer()`, `removeImeBottomMargin()`. Then remove the remaining `KT9geo` diagnostics
(`logGeometry`, `logFrames`, `frameStr`, `dumpImeTree`, `dumpTree`, `marginBottom`, the inset log in
`onComputeInsets`) and the `KT9pop` log. That leaves a clean, KikaIME-style implementation: empty
input view + full-screen transparent candidates + screen-relative insets + `TTheme.Ime`.

**One-liners.**
```
adb logcat -c
adb logcat -s tt9/KT9geo:D tt9/MAIN:I | grep -E "TREE|BAR@|insets|MARGIN LISTENER|build r"
```

---

## 7. Fallbacks, ranked (only if the theme fix doesn't fully land)

1. **Re-enable the r36 runtime fallback** — `USE_RUNTIME_MARGIN_FALLBACK = true`. Proven to zero the
   margin; the downside is the per-frame listener you wanted gone. Use only to unblock.
2. **Also skip `setNavigationBarBackground()` for the tray** — in `UiHandler.initUi` (line 230), wrap
   it in `if (!settings.isMainLayoutTray() && !settings.isMainLayoutSmall())`. Removes a code path
   that can re-assert system-bar drawing after the theme sets it off. Cheap, complements the theme.
3. **Match KikaIME's theme even more exactly** — set `TTheme.Ime` parent to
   `Theme.AppCompat.DayNight` (or `...Light.DarkActionBar`) instead of `TTheme`, plus
   `colorPrimaryDark=@android:color/transparent`. Only if the Material parent is somehow re-enabling
   the flag; slightly higher risk (Material attrs referenced by the window would need to resolve).
4. **Lower `targetSdk` to 34** — matches KikaIME's pre-edge-to-edge world. On Android 11 this is
   unlikely to be what removes the strip (the theme flag is the driver here, not SDK), so it's a
   low-priority experiment, not the fix. Keep `compileSdk = 36`.
5. **`contentTopInsets = 0`** (upstream's fix) — documented in §4; over-reserves in our architecture,
   not recommended.

---

## 8. Reading the diagnostics (glossary)

- `TREE …` — decor subtree; **watch the `LinearLayout … mB=#`**. `mB=30` = bug, `mB=0` = fixed.
- `refreshTray … DECOR@y# WxH | HOST@y# WxH | SLOT@y# WxH | BAR@y# WxH` — on-screen top-Y + size.
  Success: `HOST 240x302`, `BAR@y296` (flush).
- `insets content=# visible=#` — what the app avoids. Bar shown: `296` (= 320 − 24). Idle: `320`.
- `FRAMES … sysBottom=# stableBottom=#` — real nav-bar inset. **0** here → the 30px was never a real
  nav bar, it was the reserved background strip.
- `MARGIN LISTENER FIRED` — only logs if the runtime fallback is on AND a margin still appeared.
- `barShown` / `trayBarShown` — bar visibility (drives inset height).

---

## 9. Code map (current, post-r37)

- `ime/TraditionalT9.java` — `onCreateInputView` (tray: zero-measuring View; large: `buildBarView`);
  `onCreateCandidatesView` (tray: full-screen transparent host + bottom slot + bar; large: null);
  `onComputeInsets` (tray: screen-relative from bar's real top); `onWindowShown` (diagnostics; r36
  fallback gated by `USE_RUNTIME_MARGIN_FALLBACK`); `KT9_BUILD` (bump every build).
- `ime/UiHandler.java` — `refreshTrayVisibility` (toggles bar + `setCandidatesViewShown(true)`),
  `trayHasContent`, `initUi` (calls `SystemSettings.setNavigationBarBackground` at line 230).
- `ui/ModePopup.java` — the floating "En" pill (`PopupWindow`, offset `+heightPixels/10`).
- `util/sys/SystemSettings.java` — `setNavigationBarBackground` (Android 11: only
  `setNavigationBarContrastEnforced`; color-setting is 12–14 only).
- `util/ThemedContextBuilder.java` + `ui/main/BaseMainLayout.java:89` — keys inflate with their own
  `R.style.TTheme` context (why the service theme change is safe).
- `res/values/styles.xml` — `TTheme` (Material) + new **`TTheme.Ime`**. `res/values-v31/styles.xml` —
  `TTheme` = Material3 (Android 12+); `TTheme.Ime` inherits it automatically by name on those APIs.
- `AndroidManifest.xml` — `<service … android:theme="@style/TTheme.Ime">`.
- `preferences/.../ItemClearInsets.java` + `SettingsHacks.clearInsets()` — the upstream "clear insets"
  toggle. **In this fork it is currently a no-op** (only populates the switch default; the
  `onComputeInsets` consumer was removed in commit d51c0946). Left as-is; not our fix.

---

## 10. Still-open branding TODO (after the strip is confirmed fixed)

Rename app/IME label to "dumb keyboard"; remove Donate; remove Help in settings; remove Privacy
policy in settings; rename "tt9" → "T9" in menus; credit Dimo Karaivanov + his tt9 repo.
