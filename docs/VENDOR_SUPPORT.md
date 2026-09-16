# Vendor support

What one Android SKIN does differently, and where the next one's work goes.

Read this before adding support for a vendor. It is the current-state reference
for the in-app half; the platform's phone-host agent keeps its own counterpart
for the adb half of the same problem (`phone-host-agent/VENDOR_SUPPORT.md` in
the platform repo), and the two describe different planes of the same phone.

**One vendor has been brought up on real hardware: MIUI / HyperOS**, on a Xiaomi
Redmi Note 9S. Every accommodation below was made for an observed failure on
that handset. Everything else runs on the generic profile, which is a real
answer — "this skin needs nothing beyond the platform APIs" — rather than a gap.

---

## 1. The seam

`app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/vendor/VendorProfile.kt`
holds every vendor-specific component name in the app. Nothing else does.

| piece | what it is |
| --- | --- |
| `VendorProfile` | one skin, as an interface: `id`, `displayName`, `manufacturers`, `autostartComponents` |
| `MiuiVendorProfile` | MIUI / HyperOS — Xiaomi, Redmi, POCO. The reference phone's |
| `GenericVendorProfile` | stock Android and every skin needing nothing special: no manufacturers, no components |
| `VendorProfiles` | the registry (`known`) and the selection rule (`forThisDevice`, `autostartIntent`, `hasAutostartScreen`, `openAutostart`) |

`GenericVendorProfile` is a real object rather than a null on purpose: call
sites then carry no "no vendor" branch, and the absence of an autostart screen
is stated once instead of inferred from a null in three places.

### Selection is by RESOLVABILITY, not by brand string

`Build.MANUFACTURER` only **orders** the candidates. What decides is
`PackageManager`: a profile applies when a component it names actually resolves
on this phone (`VendorProfiles.forDevice` sorts by the manufacturer hint, then
takes the first profile with a resolvable component, falling back to
`GenericVendorProfile`).

Switching on the brand string is the obvious design and the wrong one, for four
reasons that all produce the same failure:

- **Rebrands.** One ROM ships under several manufacturer strings — Xiaomi,
  Redmi, POCO are all MIUI.
- **Regional variants**, which report strings nobody listed.
- **Custom ROMs** that carry another vendor's security app, and so genuinely
  have the screen while reporting a brand that does not suggest it.
- **OEMs not in the list at all.**

And the failure is **silent**: a button that resolves to nothing throws
`ActivityNotFoundException` at the moment it is pressed, or simply goes nowhere.
Resolvability inverts that — a Xiaomi build that dropped the security app falls
through to generic instead of offering a dead control, and a phone whose brand
nobody listed is still served if it ships a known screen.

### Adding a vendor

Three edits, and nothing else changes:

1. **Write the profile object** in `VendorProfile.kt` — its id, display name,
   the manufacturer strings it is the first guess for (lower-cased), and its
   candidate components, best first.
2. **Add it to `VendorProfiles.known`.** `GenericVendorProfile` is deliberately
   not a member; it is the fallback.
3. **Add every package it names to the manifest's `<queries>`**
   (`app/src/main/AndroidManifest.xml`).

Step 3 is load-bearing and its omission is invisible. From API 30, package
visibility is filtered: a component in a package this app has not declared is
hidden from `PackageManager.resolveActivity`, so `resolves()` answers false and
the profile never wins — on a phone that has the screen. The symptom is "this
vendor's build does not have it", which is indistinguishable from the truth.

Tests:
`app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/vendor/VendorProfilesTest.kt`
pins the selection rule (hint-only ordering, resolvability deciding, the
fall-through to generic) and the null that keeps a dead button off the screen.
`forDevice` exists as an internal overload precisely so a JVM test can vary the
manufacturer, which `Build.MANUFACTURER` cannot be off a device.

---

## 2. What a profile may NOT claim

A profile says **where to send the holder**. It never claims to know whether the
vendor setting is ON, and it must not be extended to:

- **Android exposes no API for a vendor autostart setting.** There is no
  platform intent and no platform query — the control exists only as the
  component name that vendor happens to use.
- **MIUI addresses its autostart op by the bare number 10008**, with no public
  name on the reference build.
- **Hidden-API reflection is forbidden by this project's contribution rules
  (Android safety: never bypass Android security restrictions) and blocked by
  modern Android's hidden-API policy anyway.** An undocumented per-OEM,
  per-version reflection also fails silently when it drifts, which would make
  the app confidently wrong on exactly the phones the question matters for.

Where that state does come from: the **platform's phone-host agent** reads and
sets it over adb — `phone-host-agent/internal/vendorprofile` owns which appops a
phone's vendor needs, and `phone-host-agent/internal/phonepolicy` asserts them
on the phone (both in the platform repo, a different repository from this app).
On a tethered phone the question is therefore moot: the host applies the vendor
autostart over USB at provisioning. On a phone with a human holder, the app can
only link to the screen and say it cannot confirm the setting — which is what
`ConnectorKeepAliveHintCard` does.

---

## 3. The MIUI / HyperOS accommodations

Four, each made for an observed failure.

### 3.1 The autostart screen component

**File:** `services/vendor/VendorProfile.kt` (`MiuiVendorProfile`).

`com.miui.securitycenter` /
`com.miui.permcenter.autostart.AutoStartManagementActivity` — MIUI's "may this
app start itself" screen, inside its security centre. Declared in `<queries>` as
the one vendor package this app asks about.

It is offered as a **link, never as a state**. The app can open the screen; it
cannot read what the holder did there (§2). That asymmetry is why
`VendorProfiles` exposes `hasAutostartScreen` (does this control exist here?)
separately from `openAutostart` (take me there), and why the card's wording
admits the app is not watching the setting rather than implying it is.

Why the control matters at all: `START_STICKY` is a request, not a guarantee.
HyperOS kills the connector's foreground service and suppresses the sticky
restart, and `BOOT_COMPLETED` never arrives without the vendor's autostart
permission — the failure `services/connector/ConnectorEnsure.kt` and
`ConnectorWatchdogWorker` exist to repair from inside the app.

### 3.2 The background-start guard

**File:** `utils/ActivityContext.kt` — `Context.startSettingsActivity`,
`Context.findActivity`.

**Observed:** a checklist **Fix** button that did nothing at all. No exception,
no log, no screen. MIUI's "display pop-up windows while running in the
background" is off by default for a sideloaded app, and refusing such a launch
costs no exception — so the button looked like dead UI.

**The cause:** `FLAG_ACTIVITY_NEW_TASK` was added unconditionally. A new TASK is
precisely the launch a vendor background-start guard drops.

**The rule now:** the flag is added **only when the context has no Activity**.
It is mandatory from an application or service context and actively harmful from
an activity — a target started in another task also cannot deliver a RESULT back,
which the permissions audit depends on to re-read itself. `findActivity()`
unwraps the `ContextWrapper` chain to answer, because Compose's
`LocalContext.current` is the hosting activity wrapped (by a
`ContextThemeWrapper` and however many more a theme or preview adds), so a bare
`is Activity` test answers false on a screen that plainly has one.

Both failure modes are returned rather than thrown: `ActivityNotFoundException`
(the screen is not on this build) and `SecurityException` (it exists but we may
not launch it) both become `false`, and the caller takes its fallback.
`ActivityResultLauncher.startSettingsActivity` is the sibling for launches that
need a result — it adds no flag and none may be added.

Tested in `app/src/test/kotlin/.../utils/ActivityContextTest.kt`.

### 3.3 Missing-screen fallbacks

**File:** `services/permissions/RemedyRouter.kt`.

`PermissionRemedy` says what a grant NEEDS; `RemedyDestination` says where the
holder can actually be taken **on this build**. The two differ whenever a vendor
ships an Android without one of the system screens the framework documents —
which is the whole reason a checklist row can end up doing nothing.

Two remedies have a fallback, and both are weaker than the prompt they replace
(a list the holder must search, rather than a dialog that grants):

| remedy | prompt | fallback when it does not resolve |
| --- | --- | --- |
| `DEVICE_ADMIN_ACTIVATION` | `DEVICE_ADMIN_PROMPT` | `DEVICE_ADMIN_LIST` (security settings) |
| `BATTERY_EXEMPTION_REQUEST` | `BATTERY_EXEMPTION_PROMPT` | `BATTERY_OPTIMIZATION_LIST` |

`RemedyCapabilities` carries which of the two prompts this build has, and both
default to **present** — the flags exist to carry a negative answer that was
MEASURED (`ServerScreen.remedyCapabilities` resolves each intent through
`canStartSettingsActivity`), never assumed. Routing is a pure exhaustive `when`
with a test per arm, deliberately not decided inside the click handler: that is
how a fallback ends up existing for one remedy and not the other with nothing
able to tell you which. A row routed to a fallback carries an extra sentence
saying so (`ServerScreen.manualNote`, exhaustive with no `else`).

Note that the router is **not** vendor-keyed. It reacts to what the build
resolves, which is the same posture as §1's selection rule.

### 3.4 The keep-alive card's two halves

**File:** `ui/components/ConnectorKeepAliveHintCard.kt`, wired in
`ui/screens/ServerScreen.kt`.

The card names the two OEM settings that keep a background connector alive, and
its two halves are presented on completely different terms:

- **The battery half is always shown**, because it is a platform API and
  therefore a STATE. `PowerManager.isIgnoringBatteryOptimizations` is read by
  `PermissionAuditor`, and the card's whole visibility is that state
  (`ConnectorViewModel.keepAliveHintVisible` = enrolled AND the exemption is
  missing). So the card clears itself when the grant lands and returns if it is
  revoked. It has no dismiss control, and that is the point: an earlier
  dismissal-gated version was unsatisfiable — a holder who went and granted the
  setting came back to the same card with Dismiss as the only way out.
- **The autostart half appears only when a screen resolves.**
  `ServerScreen` reads `VendorProfiles.hasAutostartScreen(context)` once
  (`remember`, not per recomposition — asking `PackageManager` on every
  recomposition would be a resolution storm) and passes a **null**
  `onOpenAutostart` when it is false. A null drops both the button and the
  sentence about autostart, because a link that goes nowhere and a paragraph
  about a setting this phone does not have are the same defect: the app claiming
  knowledge it has not got.

`VendorProfiles.openAutostart` falls back to this app's details page only for a
launch the build RESOLVES and then refuses (§3.2's guard) — never for a phone
that has no such screen, where landing the holder on a page they never asked for
explains nothing.

---

## 4. What is deliberately NOT vendor-branched

The rule: **reach for the platform API first.** A vendor branch is the last
resort, and when it is unavoidable it lives in a profile. Three places where the
branch was considered and rejected, kept here as the calibration for the next
one.

- **Screen-lock reporting** — `services/connector/ScreenLockMonitor.kt`.
  `KeyguardManager.isKeyguardLocked`, re-read on `ACTION_SCREEN_OFF` /
  `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`, with no vendor branch anywhere.
  The defence against a hostile ROM is DEFENSIVENESS, not a per-OEM table: every
  read is wrapped, a refused service or a throwing registration costs the
  connector nothing, and `current()` answers `null` — "could not ask" — rather
  than guessing. Catching narrowly would mean predicting which OEM throws what,
  which is the guess the class exists to avoid.
- **Resolving the Settings package** —
  `services/connector/policy/StructuralDenylist.kt`. The permanently-undrivable
  set names the AOSP and Google Settings packages, and then asks the OS which
  package handles `Settings.ACTION_SETTINGS` rather than guessing an OEM's
  spelling. A hard-coded list of vendor spellings would fail silently on the
  first phone nobody thought of — the same argument as §1, one layer down.
- **Battery-optimisation exemption** — `PowerManager` for the state,
  `android.provider.Settings` actions for both destinations
  (`utils/OemKeepAliveSettings.kt`). Every screen in that file is a platform
  screen, which is what makes it writable once. Granting vendor autostart does
  **not** exempt an app from doze; they are separate controls on separate
  screens, and the audit row for the battery exemption says so — a holder who
  turned on MIUI's autostart is entitled to wonder why the row is still red
  (`services/permissions/RequiredPermission.kt`).

If you cannot say what the vendor branch would do that the platform API does
not, there is no vendor difference there.

---

## 5. Per-vendor status

| skin | profile | status |
| --- | --- | --- |
| MIUI / HyperOS (Xiaomi, Redmi, POCO) | `MiuiVendorProfile` | **Reference.** Brought up on real hardware (Xiaomi Redmi Note 9S); every accommodation in §3 comes from an observed failure on it |
| Stock Android / AOSP | `GenericVendorProfile` | Supported by having nothing to do. The platform APIs are the whole answer |
| Everything else | `GenericVendorProfile` | **Untested.** The app runs, the autostart control is absent rather than dead, and nothing vendor-specific is claimed |

**Not built.** These skins ship an autostart manager of their own and would each
be one profile object plus a `<queries>` entry (§1). None is implemented, none
has been tested, and no component name for them appears anywhere in this
codebase:

| skin | vendor | the control |
| --- | --- | --- |
| One UI | Samsung | "Sleeping apps" |
| ColorOS | OPPO | its autostart manager |
| Funtouch OS | vivo | its autostart manager |
| EMUI | Huawei | its autostart manager |

Adding one is the §1 recipe. Do not add a component name that has not been
resolved on a real handset of that vendor: an unverified spelling never
resolves, so the profile silently never wins and the phone is served the generic
answer — which looks exactly like not having added it at all.
