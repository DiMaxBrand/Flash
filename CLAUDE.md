# Flash NFC App — Claude Development Guide

## Project Overview

**Flash** is a premium NFC file-sharing Android application with advanced physics-based animations and liquid glass rendering. The app leverages NFC for zero-click device pairing and Wi-Fi for fast local file transfers, with a focus on visual excellence through 120Hz-optimized animations and fluid morphing effects.

### Key Tech Stack
- **Language**: Kotlin 2.3.0 (built into AGP 9 — there is no `kotlin-android` plugin; `kotlin-compose` and `kotlin-serialization` remain)
- **UI Framework**: Jetpack Compose (BOM 2026.09.00 = Compose 1.12.1)
- **Material Design**: Material3 1.5.0-beta01 (Expressive; pinned outside the BOM, which only maps stable 1.4.0 — same as the user's Impulse app)
- **Glass Effects**: Backdrop library 1.0.6 (SDF rendering)
- **Networking**: Ktor CIO (3.1.2)
- **Build System**: AGP 9.2.1, Gradle 9.4.1
- **Min SDK**: 26, Target SDK: 35, Compile SDK: 37
- **App identity**: package, namespace and `applicationId` are all `com.dimax.flash` (since 0.12.3). It is a different app from the old placeholder-named builds, which had to be uninstalled by hand; nothing of the old name is left in the repository. Use `context.packageName`, never a hard-coded id (the NFC Android Application Record does).

## Build Environment

### Android SDK Setup
The project requires Android SDK platform 37 (`sdkmanager "platforms;android-37.0"`) and build-tools 34+. If you see "SDK location not found" errors:

1. Install Android Command Line Tools from android.com/studio
2. Create `local.properties` in project root:
   ```
   sdk.dir=/path/to/Android/SDK
   ```
3. Verify: `./gradlew assembleDebug` should complete in ~90 seconds

### AGP Compatibility
- Current: AGP 9.2.1 (pinned in `gradle/libs.versions.toml`), Gradle 9.4.1. Material3 1.5.0-beta01 requires AGP 9.1+ and compileSdk 37 — do NOT downgrade
- AGP 9 has built-in Kotlin: do not re-add `org.jetbrains.kotlin.android`
- The unused accompanist-permissions dependency was removed (permissions use the Activity Result API)

### Gradle Properties
- `org.gradle.jvmargs=-Xmx4096m` — required for compilation
- `android.useAndroidX=true` — mandatory
- `kotlin.code.style=official` — follow official Kotlin style

Verify build succeeds: `./gradlew clean assembleDebug`

## Git Workflow & PR Management

### Branch Model
- **`develop`** — the cutting edge. All work lands here; every push builds the APK in CI and publishes a GitHub release (`.github/workflows/build.yml`).
- **`main`** — "known to work". **Current phase (since Oct 18): transfers work (NFC pairing + Wi-Fi transfer verified on a Redmi Note and a Galaxy S24), so `main` is NO LONGER synced with `develop`.** `main` only moves to a `develop` state the user has explicitly confirmed works; `develop` keeps running ahead. First confirmed build: **0.11.0 (versionCode 50), commit a971521** (main points at the docs-only commit right after it). Never push `develop:main` on your own again — ask the user which build to promote. When promoting, prefer a commit whose message contains `[skip ci]` or accept the duplicate release run.
- The workflow file must exist on a branch for pushes to it to build — keep `.github/workflows/build.yml` identical on `develop` and `main`.
- Other branches are legacy/session leftovers (`claude-code`, `claude/*`): never base new work on them. The cloud session harness may hand you a `claude/...` branch; push your work to it if told, but also land it on `develop`.

### Delivery (standing authorization from the user)
The user develops without a computer and installs APKs from GitHub releases. They have **explicitly and permanently authorized pushing directly to `develop` and `main`** whenever work is built and ready. Releases are no longer automatic on every push: see "When to release" below. Always compile first (`./gradlew assembleDebug`); in the sandbox install the Android SDK (platform 36, build-tools 34) if missing, and use a Maven Central mirror via a `~/.gradle/init.d` script if repo.maven.apache.org rate-limits (429/404). If an auto-mode permission check still blocks a push, stop and tell the user rather than working around it. Put `[skip ci]` in the commit message of docs-only commits so they do not publish a duplicate release.
**Signing (since 0.12.3):** releases are signed with the user's own private key, which lives ONLY in GitHub Actions secrets: `KEYSTORE_BASE64` (the .jks, base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The workflow's first step checks them: **if any is missing it builds and releases NOTHING** (a green run with a notice), so no unsigned or debug-signed build can ever be published. When present, the keystore is decoded to a temp file and handed to Gradle through `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` (`signingConfigs.flash` in `app/build.gradle.kts`). Local and sandbox builds have no key and use Android's auto-generated debug key; the old committed `debug.keystore` is gone. Never commit a keystore or print its passwords.

### Workflow
```
1. Change code on develop, bump the version (see Version Management)
2. ./gradlew assembleDebug  — must pass
3. git push origin develop
4. If the push changed what the APK does (see "When to release"): run the release workflow
   (below) with a title and description, then check the run until it finishes
```

### When to release (standing rule from the user, Oct 18)
- The user tests ONLY through GitHub releases, so a **user-facing** change that deserves at least a beta gets a release automatically — Claude triggers it, the user is not asked to press anything.
- **User-facing** = anything that changes the APK: code, resources, strings, manifests, dependencies, build settings that change the output. Pushes that only touch docs (CLAUDE.md, notes), comments, or CI files that don't affect the APK do NOT release. When in doubt whether a change reaches the APK, release.
- Release = bump the beta number (`1.0.0-beta.1` → `1.0.0-beta.2`) and the versionCode in `app/build.gradle.kts` in the same commit as the change, push, then trigger the workflow (below). Do not jump the X.Y.Z while in beta.
- Several small user-facing changes in one session may be batched into one beta, but never ship a half-finished feature: a release must be self-contained and must not break the app for anyone.
- **Stable (plain `X.Y.Z`) needs the user's explicit approval first, every time.** Before triggering it, give a short opinion on whether it is ready, then the exact title and description verbatim, and wait for the user to agree. Authorization for one stable release does not carry over to the next.

### How to trigger a release
- Trigger with the GitHub Actions tool `run_workflow` on repo `dimabpy/flash` (the repo the GitHub tools are configured for; `DiMaxBrand/Flash` is the same project), `workflow_id` = `build.yml`, `ref` = `develop`, and the `title` and `description` inputs. Write both BEFORE triggering: the workflow can't edit a release's title or body afterwards, and a release made without them can only be deleted and redone.
- **Title format (Russian brand, as in Impulse):** `Флэш <version>: <короткое описание>`, e.g. `Флэш 1.0.0-beta.2: Исправлен приём по NFC`. The prefix is always present, because the update sheet shows this title as its heading. The `<короткое описание>` is in Russian.
- **Description:** the user-facing highlights in Russian first, then English, short, for ordinary users. Put technical detail in a separate English-only **Developer notes** section. Name unfinished or flagged work plainly as early and off by default; no excited wording for unfinished things. Beta/alpha descriptions are optional, but add one when there is something to call out.
- Commit message = the release notes body (the workflow appends the commit message without trailers under the description), so write it as a real changelog entry, not a bare subject.
- After triggering, check the run (`list_workflow_runs`) until it completes, then confirm the release exists and is marked pre-release. If it failed, read the failing step and report it; don't retrigger blindly.

### Critical Rules
- **Never create draft PRs** — they bypass CodeRabbit entirely
- **CodeRabbit** only reviews PRs targeting `main`; the normal flow no longer uses PRs (direct pushes), so it is mostly idle
- **All PR descriptions must be clean** — no auto-generated footers; they interfere with CodeRabbit command parsing
- **Commit messages should explain WHY**, not just WHAT
- **Version management** (CRITICAL — applies to EVERY app file change):
  - **Always check the current date** before updating versions (system context provides this)
  - **Increment `versionCode` by 1** for every file change that goes into the APK
  - **Update `versionName` format**: `"X.Y.Z, Month Day"` (e.g., "0.5.1, May 4")
  - **Date in versionName is REQUIRED** — it documents when the change was made
  - **Never commit version changes without the date** — discuss with user if uncertain about version number
  - Example: If you change `MotherCore.kt`, you MUST increment versionCode AND update versionName with today's date

### CodeRabbit Configuration
- Auto-reviews trigger only on PRs targeting `main`
- Rate limit: 1 review per hour per organization
- Review profile: CHILL (less strict, suitable for rapid iteration)

## Animation Architecture

### Core Concept: RepeatMode
Jetpack Compose has two repeat modes for infinite animations:

#### RepeatMode.Restart
```kotlin
infiniteRepeatable(
    animation = tween(duration, easing),
    repeatMode = RepeatMode.Restart  // Jump back to start, play forward again
)
```
- Animation goes: 0 → targetValue → 0 → targetValue → ...
- **Use for**: Cyclic patterns where start state = end state (Perlin noise, orbits, rotating elements)
- **Visual result**: Smooth, continuous looping with no artificial bounce

#### RepeatMode.Reverse
```kotlin
infiniteRepeatable(
    animation = tween(duration, easing),
    repeatMode = RepeatMode.Reverse  // Play forward, then backward
)
```
- Animation goes: 0 → targetValue → 0 → targetValue → ...
- **Use for**: Genuine oscillating/pulsing motion (breathing, gentle expansion/contraction)
- **Visual result**: Back-and-forth motion; visible slowdown/reversal at midpoint
- **Gotcha**: Looks bouncy if the motion is directional; causes artificial "breathing" artifacts

**Rule for Flash**: `Restart` only for truly periodic motion (an angle running 0..2π, a sine). Perlin noise is NOT periodic, so never drive it with a 0..N animation that restarts: it jumps at the loop point (a visible snap, found in 1.0.1-beta.7). Drive noise with `rememberContinuousTime()` (`ui/core/ContinuousTime.kt`, seconds that only count up). `Reverse` only for intentional oscillations.

### Mother Core (Liquid Glass Blob)

**File**: `app/src/main/java/com/dimax/flash/ui/core/MotherCore.kt`

#### Constants
```kotlin
private const val CONTROL_POINTS  = 16      // Bézier control points around the blob perimeter
private const val BASE_RADIUS_DP  = 60f     // Base radius from center to edge
private const val NOISE_OFFSET_DP = 20f     // ±20dp Perlin noise variation (max bulge)
```

#### Sizing & Space
- Base blob diameter: `(60 * 2) + (20 * 2) + 32 = 172dp` (container size)
- Max reach from center: 60 + 20 = 80dp
- Available space before hitting boundary: ~90 - 80 = 10dp
- Current usage: ~80% of available space (conservative for safety)

#### Animation Loop
```kotlin
val time by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(
        animation = tween(2_700, easing = LinearEasing),
        repeatMode = RepeatMode.Restart  // Smooth loop; 2.7s per cycle
    )
)
```
- `time` drives Perlin noise via `updateBlobPath()`
- Perlin noise is deterministic: `noise(cos(angle)*1.5 + time*0.0004, sin(angle)*1.5 + time*0.0004)` produces smooth morphing
- This is the OLD loop. Its claim that the noise returns to the same state at `time=1000` was wrong (the noise offset is `time*0.0004`, so it jumped back 0.4 units every 2.7 s, and the highlight phases jumped too). Since 1.0.1-beta.7 `time` comes from `rememberContinuousTime()` at the same pace (1000 units per 2.7 s) and never restarts

#### Specular Highlights
```kotlin
val specularShiftX = (sin(time * 0.00063 * PI) * baseRPx * 0.12f).toFloat()
val specularShiftY = (cos(time * 0.00047 * PI) * baseRPx * 0.09f).toFloat()
```
- Two highlights (large soft + small pinpoint) drift slowly across the blob surface
- **Everything is clipped to the morphing blob path** (`blobShape`): camera preview, glass layer, tint, highlights. There is no static circle or outer glow.
- **Clear glass, not frosted**: the lens layer uses only `lens()` (no blur/vibrancy). The backdrop lens only supports rounded-rect SDF shapes (it throws for generic paths), so it renders in a circle and is clipped to the blob.
- **Sender tint is a flat colour** (`accentColor`, alpha 0.80, no gradient, no aqua rim/shadow over it) so the receiver's camera can read a clean hue; white specular highlights have low saturation and are ignored by the matcher.
- Creates illusion of liquid depth and movement
- Frequencies slightly different to avoid static patterns

### Photo Orbiting

**File**: `app/src/main/java/com/dimax/flash/ui/workbench/PhotoOrbit.kt`

#### Golden Angle Spacing
```kotlin
private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()  // ≈ 137.5°
```
- Each new photo lands at `nextPhaseIndex * GOLDEN_ANGLE` radians
- Guarantees photos never bunch together regardless of count
- Phase is assigned once on entry; adding photos doesn't shift existing ones

#### Orbit Animations

**1. Orbit Ring** (photos circling the core)
```kotlin
val time by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = (2 * PI).toFloat(),
    animationSpec = infiniteRepeatable(
        animation = tween(8000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart
    )
)
```
- 8-second full rotation, then jump back and rotate again
- Photos position: `x = coreCenter.x + orbitR * cos(time + phaseOffset)`

**2. Photo Blob Morphing** (edge distortion on each photo)
```kotlin
val blobTime by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing))
)
```
- Visible edge morphing on each thumbnail, 1000 noise units per 12 s, from `rememberContinuousTime()` (it used to be a 0..1000 loop that snapped every 12 s)
- Uses same Perlin noise logic as Mother Core
- Each photo gets unique morphing based on: `photoBlobTime = (blobTime + phaseOffset * 137f)`

**3. Radial Drift** (photos drift slightly in/out)
```kotlin
val radialDrift by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = with(density) { 8.dp.toPx() },
    animationSpec = infiniteRepeatable(
        animation = tween(4000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart
    )
)
```
- 4-second smooth swing, 0..8dp out from the base orbit radius (a cosine of the continuous clock). It used to be a sawtooth that jumped back every 4 s

#### Photo Lifecycle
- **Entry**: Spring animation (600ms, dampingRatio 0.6, stiffness 150)
- **Orbit**: Continuous, driven by `time`
- **Success Pulse**: 400ms bloom + collapse to center when transfer completes
- **Exit**: 400ms fade to center with FastOutSlowInEasing

### Onboarding Animation

**File**: `app/src/main/java/com/dimax/flash/ui/onboarding/OnboardingScreen.kt`

Two phones animating in TwoPhonesAnimation:
- Phone offset: 1200ms `Restart` cycle (60dp → 8dp → 60dp)
- Tap pulse: 2400ms `Restart` cycle (full circle wave)

## Common Mistakes & How to Avoid Them

### Mistake 1: Creating Draft PRs
**Problem**: CodeRabbit skips reviewing draft PRs.
**Solution**: Always mark `draft = false` when creating PRs. Use `update_pull_request` if you created one as draft.

### Mistake 2: Forgetting the Develop→Main Step
**Problem**: You finish work, create a PR to develop, and wait for CodeRabbit review. CodeRabbit never reviews because it's not targeting `main`.
**Solution**: 
1. Create feature→develop PR, merge immediately
2. Then create develop→main PR for CodeRabbit review

### Mistake 3: Adding "Generated by Claude Code" Footers
**Problem**: CodeRabbit treats footers as conversation text, not command markers. `@coderabbitai review` with a footer becomes a chat message instead of a trigger.
**Solution**: Never include auto-generated footers in PR descriptions or bot command comments.

### Mistake 4: RepeatMode.Reverse for Perlin Morphing
**Problem**: Animation bounces at the midpoint, looks unnatural.
**Solution**: Use `Restart` for any animation where start state = end state.

## Key Files & Their Purposes

- `MotherCore.kt` — Liquid glass blob rendering and morphing
- `PhotoOrbit.kt` — Photo orbiting, Perlin noise edge distortion, golden angle spacing
- `LiquidButton.kt` — Interactive button with backdrop effects
- `WorkbenchScreen.kt` — Main UI layout, orchestrates core, photos, and buttons
- `RippleShader.kt` — AGSL shader for ripple effect on file transfer
- `gradle/libs.versions.toml` — Dependency versions (AGP, Kotlin, Compose, Ktor, etc.)

## Version Management Workflow

**Location**: `app/build.gradle.kts`, the `appVersionCode` / `appVersionName` lines at the top. `appVersionName` IS the GitHub release tag and the in-app updater version: `X.Y.Z` is a stable release, `X.Y.Z-beta.N` a beta (pre-release). The release title and description are NOT in the build file: they are optional inputs of the manual workflow run (Actions > Build & Release APK > Run workflow). The workflow runs ONLY by hand; a push publishes nothing.

### When to Update
Update version numbers whenever you make **any file change that affects the APK** (code, resources, strings, manifests, etc.). Do this **before committing**.

### Step-by-Step Process

1. **Check the current date** (system context provides this at session start)
2. **Increment versionCode by 1**
   - Current: Find the number on line 16
   - New: Add 1 (e.g., 16 → 17)
3. **Update versionName** with the format `"X.Y.Z, Month Day"`
   - Always include today's date
   - Example: `"0.8.1, May 12"`
   - **Never guess**: Ask the user what the next version should be, then they tell you
4. **Commit both together** — never commit versionCode without versionName's date, or vice versa
5. **Include in commit message** — mention that versions were bumped (e.g., "Bump to 0.8.1 for camera handshake feature")

### Why This Matters
- **versionCode** is used by Android to determine if an update is newer; must increment for every release
- **versionName** documents when the change was made; the date is required for tracking
- Both must match the actual code changes; mismatches cause confusion in release notes

### Example
```
Current: versionCode = 16, versionName = "0.8.0-camera-handshake, May 11"
Files changed: MotherCore.kt, PhotoOrbit.kt, WorkbenchScreen.kt

Action:
- versionCode = 17
- versionName = "0.8.1, May 12"

Commit message: "feat: update MotherCore animations (versionCode 17, May 12)"
```

## Performance Notes

- **120Hz target**: Animations use `LinearEasing` for consistent frame rates
- **Perlin noise**: 3 octaves with 0.55 persistence for smooth variation
- **Canvas operations**: All morphing done in `Canvas` with cached paths where possible
- **Memory**: Base APK ~24MB (debug); optimize with proguard/R8 for release

## Future Enhancements (from README)

- [ ] Simultaneous receiving and sending (currently sender rejects incoming handshakes)
- [ ] Gallery flight destination from real `LazyVerticalGrid` positions (currently uses fixed dp constants)

## Camera Handshake System (HyperOS Fallback)

**Files**: `CameraHandshakeManager.kt`, `ColorDetectionScreen.kt`, `ScanCompletePopup.kt`

### Why It Exists
On HyperOS 3+ devices (certain Xiaomi phones), NFC reader mode doesn't work reliably. The camera handshake provides a visual fallback: the receiver points its back camera at the sender's MotherCore blob, which is tinted with a specific color. The receiving device analyzes YUV frames to detect and match that color, confirming the peer and establishing a connection.

### Architecture

#### 1. Color Generation & mDNS Advertisement
```kotlin
// CameraHandshakeManager.kt
fun generateDisplayColor(): Int {
    val hue = HUES[Random.nextInt(HUES.size)]
    return Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.95f))
}
// HUES = [200, 140, 280, 30, 170, 60, 320, 0] (8 colors covering hue wheel)
```
- Sender generates a random HSV color (high saturation & value for camera visibility)
- Advertises via mDNS with: peerId, displayColor, ip, port, token, fileCount. **Known security gap:** the token is in the mDNS TXT record, readable by anyone on the same Wi-Fi; tokens never expire and are not single-use; transfer is plain HTTP.
- `TransferRepository.getLocalIp()` finds the IPv4 via the active Wi-Fi network's LinkProperties, then Wi-Fi/hotspot interfaces, then the deprecated `WifiManager.connectionInfo` as a last resort. The receiver ignores an advertised `0.0.0.0`/`127.x` and falls back to the NSD-resolved host.

#### 2. Color Detection (YUV Analysis)
```kotlin
// ColorDetectionScreen.kt - ColorMatcher class
fun updateFrame(yuv: ByteArray) {
    // Convert YUV to RGB for dominant color detection
    // Check 3 conditions:
    // 1. Saturation ≥ 0.45f, value ≥ 0.12 (no grays/whites/darkness); hue within 28° of the target
    // 2. Fill ratio > 0.35f of ALL sampled centre pixels (not just the saturated ones)
    // 3. Sustained match ≥ 1000ms (no flickering false positives)
}
```
- In camera mode a receiver (no photos selected, not mid-transfer) shows the live back-camera preview immediately; color matching starts once a sender is discovered over mDNS (state `Detecting`). `MotherCore(viewfinderContent = { MotherCoreViewfinder(...) })` clips the live view to the morphing blob path itself (no static circle). The view is a `PreviewView` in COMPATIBLE (TextureView) mode inside the blob clip. It works on the user's Samsung — a black blob there just means the camera is pointing at something dark (it looked like a bug once; it wasn't). The core skips its opaque lens layer and glow
- Continuously analyzes YUV frames for dominant color
- Only locks when all 3 conditions sustained for 1 second

#### 3. Visual Feedback
- MotherCore tints to the detected color (via `accentColor` parameter)
- ScanCompletePopup shows a spring-animated confirmation with color name
- ColorDetectionScreen shows "Point at their MotherCore" hint text

### Integration Points
- `WorkbenchScreen` conditionally shows `MotherCoreViewfinder` when `colorDetectionState == Detecting`
- `MotherCore` receives `accentColor: Color?` and draws an overlay when locked
- `WorkbenchViewModel` manages `ColorDetectionState` enum: Idle → Detecting → Locked
- Connection confirmed via `onColorConfirmed()` → calls `startDownload()`

### Design Notes
- **Permissions** are requested by `WorkbenchScreen` as soon as pairing is active: `CAMERA` (only if camera pairing is on), `NEARBY_WIFI_DEVICES` (Android 13+) and `ACCESS_LOCAL_NETWORK` (newer Android/HyperOS may block LAN connections without it — a hypothesis for `NoRouteToHostException`, unverified). The status box shows `permissions: camera/nearby/local network yes|NO|n/a` (n/a = this Android version does not define that permission, so it is never requested). Tapping the box re-requests whatever is missing, and opens the app's settings page if Android shows no dialog (denied for good). The viewfinder only shows once the camera is granted
- **Fallback only**: Only used on HyperOS devices (checked via `DeviceDetector.isHyperOSDevice()`)
- **UI non-blocking**: Analysis runs on background thread, doesn't freeze Compose
- **Deterministic colors**: HSV palette ensures distinct, visually separable hues

### VPN handling
A VPN makes the *tunnel* the default network: `getLocalIp()` used to return its address (e.g. 100.x, unreachable from other phones), mDNS was announced on the tunnel, and connects to 192.168.x.x timed out. `util/LocalNetwork.kt` finds the real Wi-Fi `Network` (TRANSPORT_WIFI and not TRANSPORT_VPN), takes the LAN IPv4 from it (rejecting loopback/link-local/CGNAT 100.64/10), announces/discovers mDNS on that network explicitly (API 31+/33+), and `TransferRepository.startDownload` binds the process to Wi-Fi (`bindProcessToNetwork`) for the download so it bypasses the VPN. An always-on VPN with "block connections without VPN" can still block it. Evidence: user screenshots with the VPN badge on showed advertised IP 100.98.x and `ConnectTimeoutException` to 192.168.1.84.

### Download failures
`TransferRepository.startDownload` tries the real Wi-Fi network first, then the default network. If both fail, the red "Transfer failed" pill shows the error plus `LocalNetwork.probe()`: `default=… | wifi=… | ping=…` (TCP connect over each network, and an ICMP ping). Observed on the user's phones with VPN off: discovery over mDNS worked and the right LAN IPs were advertised, but TCP failed with `NoRouteToHostException` (ARP unanswered). That pattern usually means the router isolates Wi-Fi clients from each other (multicast passes, unicast doesn't) — the fix is a different network or a phone hotspot, not a code change. mDNS discovery succeeding does NOT prove two phones can open TCP connections to each other.

### Diagnostics (no computer available)
Layout: the status box, the red failure pill and the scan popup live in ONE `Column` at the end of the outer `Box` in `WorkbenchScreen` (drawn after `MotherCore` and the "+" button, `bottom = 160.dp` so it clears the "+" and the Exit/Settings buttons). Don't put overlays at the top (the blob covers them) or before the "+" button in the tree (it draws over them).
Camera mode shows the last 5 mDNS events (`CameraHandshakeManager.status`) in a box at the top of the workbench: advertising registered/failed (with NSD error code), search started/failed, services found/lost, resolve failures (with retry on code 3), and the resolved `ip:port`. A failed download shows a red "Transfer failed: <ExceptionClass: message>" pill and restarts discovery after 4 s (NSD reports a sender only once per search). Resolves are queued one at a time (NSD allows one active resolve). Ask the user to read these lines back when pairing misbehaves.

### Pairing Mode Setting
Settings has a **Pairing method** row (`HandshakeMode`: NFC / CAMERA, stored in `ThemeRepository`). AUTO was removed in 1.0.1-beta.9 (a stored "AUTO" now reads as NFC; HyperOS no longer turns the camera on by itself, the user picks Camera).
- NFC (the default): camera discovery/advertising off
- CAMERA: NFC foreground dispatch and outbound NFC handshake off. It must be chosen on BOTH phones: the sender only advertises its code (mDNS) in camera mode
- `WorkbenchViewModel.applyHandshakeMode(nfc, camera)` applies it; the sender tints its own MotherCore with its advertised `displayColor` so the receiver's camera has something to find.

## Received Files & Hotspot Prompt

- Downloads land in `cacheDir/transfers/` (Android may clear it). After each file passes its checksum, `WorkbenchViewModel.saveToGallery()` copies it into MediaStore `Pictures/Flash` (Android 10+: no permission; Android 8–9: written straight to Pictures/Flash with WRITE_EXTERNAL_STORAGE, requested with the gallery permission) and a green "Saved to Pictures/Flash" message shows. The in-app Uri is left as the cache file on purpose so the orbit doesn't swap items.
- The "No Wi-Fi?" hotspot dialog is driven by `updateWifiStatus()`, polled every 1.5 s from `WorkbenchScreen`. "Connected" = `LocalNetwork.localIpv4() != null` (joined to Wi-Fi, even under a VPN, or hosting a hotspot). Dismissing it keeps it dismissed until a connection is seen and lost again.
- Settings shows `Flash <versionName> (build <versionCode>)` so a screenshot/recording can be matched to a release.

## Interface Style (Settings > Interface)

`UiStyle { LIQUID, EXPRESSIVE }` in `ThemeRepository` (default LIQUID). `WorkbenchScreen` runs all the shared logic (permissions, pairing mode, NFC dispatch, discovery, gallery loading) and then, if the style is EXPRESSIVE, hands off to `ui/expressive/ExpressiveWorkbench.kt` and returns — so both styles drive the same `WorkbenchViewModel`.
Expressive layout: a **tray** at the top (status line; the receiver's camera view or the sender's flat colour beacon inside a `MaterialShapes.Cookie9Sided` shape; the photos being sent/received as tiles that dim and light up bottom-to-top with progress; a `LinearWavyProgressIndicator`), the gallery grid in the middle, a `BottomAppBar` with Exit / Settings and an Add FAB. Dynamic colour on Android 12+, `MaterialExpressiveTheme` with the expressive motion scheme. It has its own settings bottom sheet (theme, pairing method, interface style, language, version) and its own copies of the small effects the liquid screen runs inside its layout (Wi-Fi polling, auto-confirm on colour lock, ripple-complete reset). No Mother Core / glass in this style. Material 3 1.5 `toShape()` is `@Composable` — don't wrap it in `remember {}`.

## Reveal cards (Expressive style only)

The Expressive screen shows photos as big stacked cards (`RevealCard`, `SendCard`, `BigCards`): on the **receiver** when 1-2 photos are incoming or when you tap a tray photo (opened one at a time); on the **sender** when 1-2 photos are selected (the original photo, dimmed and lighting up bottom-to-top with send progress). More photos stay as tray tiles.
Protocol additions on `FileServer` (same token): `GET /meta/{token}` → JSON `{files:[{i,w,h,pw,ph,bands,thumb(base64 32px JPEG)}]}`; `GET /band/{token}/{index}/{n}` → JPEG band `n` of the 1080px-wide display preview, counted from the BOTTOM (6 bands, q88; EXIF orientation applied; cached). The untouched original still goes through `/transfer/...` with its SHA-256. An older sender without `/meta` makes `fetchMeta` return null and the UI falls back to plain tiles.
Receiver (`WorkbenchViewModel.launchPreviews`): fetches meta right when the download starts (process bound to Wi-Fi, ref-counted in `LocalNetwork`), builds `uiState.incoming: List<IncomingPhoto>`, and fetches bands ONLY for open photos (`openIncoming` on tap; auto-open when ≤2 photos). The original and its verdict update the same `IncomingPhoto` (`ready` = file complete and checksum valid).
`RevealCard`: shimmer until the tiny thumbnail exists → blurred thumbnail → sharp layer masked by a bottom-up wipe with a soft faded edge. The wipe is a slow non-bouncy spring (stiffness 10) toward the loaded-band level; it never passes the bands. When the original becomes ready mid-wipe the wipe pauses ~180 ms, the original crossfades in under the same mask (invisible), and the wipe continues to the top with the original. Tray tiles use no bands: they wipe up when the original is ready. When all reveals are finished (+1.5 s hold on big cards) the screen calls `viewModel.onRevealFinished()`; `settleReceivedPhotos` waits for that (`setHoldLanding(true)`) instead of the Liquid 3 s orbit, then the photos land in the grid (cards shrink away via `AnimatedContent`).

## Sender completion

`FileServer` marks a file delivered when its response body was written in full (a dropped client throws before that) and emits `allDelivered` once every served file has been delivered. `WorkbenchViewModel.onAllFilesDelivered()` (sender only) sets `transferProgress = 1f` so `PhotoOrbit` plays its success pulse (photos swell and collapse into the core), shows "Photos delivered", then after ~2.6 s clears the selection, the NFC handshake and the mDNS advertisement, and sets `currentPort = 0` so the next selection starts a fresh server/token. The old server is stopped 60 s later (only if nothing new started) so a receiver whose checksum failed can still retry. "Delivered" means bytes sent — the receiver's checksum result is not reported back.

## Sender progress

`FileServer` counts bytes written to receivers against the total size of the served files (`sendProgress`, 0..1, aggregate over all files; it measures bytes handed to the socket, so small photos can jump quickly). `WorkbenchViewModel` mirrors it into `uiState.sendProgress` on the sending phone. `PhotoOrbit` then grows each orbiting photo to 2x (`sendScale`, non-bouncy spring) and dims the unlit part with a vertical gradient whose soft edge rises from the bottom (`sendLit`, eased with a critically damped low-stiffness spring so jumps like 0%→80% glide).

## Receiving flow (verification runs alongside the animation)

1. `FileClient.downloadAll` returns as soon as the files are saved and starts one checksum verification per file **concurrently** in the repository scope (SHA-256 of a few MB takes tens of ms; the old fixed 500 ms delay and the old "wait for verification before reporting Complete" are gone). Verdicts arrive on `fileVerifiedFlow` (a `SharedFlow`, never conflated). Corrupted files are no longer deleted by the client.
2. `TransferState.Complete(files: List<DownloadedFile>)` -> the VM puts the photos in `receivingPhotos` (they materialize and orbit the core) and runs `settleReceivedPhotos`: orbit for **at least 3 s** (`ORBIT_MIN_MS`), wait for any verdict still missing, then split:
   - **good**: prepended to the grid (`landingPhotos`; the grid cells animate apart via `Modifier.animateItem()` and stay empty/hidden), each orbiting photo flies from where it is to its square grid cell while growing (`OrbitPhotoItem` landing animation, 900 ms), then `onPhotoLanded` removes it from the orbit and the cell fades in. Each good photo is also copied to Pictures/Flash.
   - **bad**: `rejectedPhotos` turn red, move back into the Mother Core and stay parked there; the core turns red, a red "N photo(s) came through damaged" pill shows and the red `CorruptionAlert` dialog appears. "Retry" deletes the damaged files and downloads only those indices again (`startDownload(..., indices)`; the sender's server stays up for 60 s); "Skip" deletes them.
3. The receive session ends (`finishReceiveSession`: `isReceiving=false`, progress 0, `sessionColor=null`) when nothing is left to land or park. `onRippleComplete` is only decoration now.
4. The core keeps the sender's colour for the whole session (`sessionColor`), not just the 3.5 s the scan popup shows.
5. Grid cells are square (`aspectRatio(1f)`, column width = (screen − 8dp padding×2 − 4dp gaps×2) / 3) so `landingTargets` match the real cells.
6. Gotcha: `ConcurrentHashMap.contains(x)` means `containsValue` — use `containsKey`.
7. The Expressive style has no orbit: `landingPhotos` land immediately and rejected photos show as red tray tiles plus the same dialog.

## Network Corruption Detection & Recovery

**Files**: `FileServer.kt`, `FileClient.kt`, `TransferRepository.kt`

### SHA-256 Verification
```kotlin
// FileServer.kt
// Real code streams the file and sends lowercase HEX SHA-256 in the X-File-Checksum header
val checksum = digest.digest().joinToString("") { "%02x".format(it) }
```

```kotlin
// FileClient.kt
val receivedChecksum = response.header("X-File-Checksum") ?: ""
val computed = MessageDigest.getInstance("SHA-256").let {
    it.update(downloadedFile.readBytes())
    Base64.getEncoder().encodeToString(it.digest())
}
val isValid = receivedChecksum == computed
onFileVerified(index, isValid)
```

### Corruption Alert UI
- Shows corrupted photo thumbnails in a circular arrangement
- Offers retry option (returns to idle state)
- Photos marked with red tint in PhotoOrbit during transfer

### Data Flow
1. `TransferRepository.fileVerifiedFlow` emits `Pair<Int, Boolean>` (file index, isValid)
2. `WorkbenchViewModel` records them in `verifyResults`; `settleReceivedPhotos` acts on them (see Receiving flow)
3. `PhotoOrbit` parks `rejectedPhotos` red inside the core
4. The red `CorruptionAlert` modal offers Retry (only the damaged files) or Skip

## How to Update This File in Future Sessions

### Adding Architectural Insights
1. Did you add a new major component? Add it to the "Key Files & Their Purposes" section with 1-line description
2. Did you add new animation logic? Add it to "Animation Architecture" with duration/easing details
3. Did you add a new feature (like camera handshake)? Add a new top-level section explaining WHY, WHAT, and HOW

### Keeping Branch State Current
After major merges or pushes:
1. Run `git log --graph --oneline origin/main origin/develop origin/claude-code --decorate -20` to understand relationships
2. Update the "Branch State & Git Workflow" section with current commit SHAs and divergence status
3. Add any new recommendations to "Short-Term Tasks" (with delete instructions)

### Managing Short-Term Tasks
1. Create a new "Short-Term Tasks" section at the end when session-specific work needs tracking
2. **Always** precede it with: **"IMPORTANT: Delete this entire section after completing all items."**
3. At session end, check every item and either complete it or re-prioritize for next session
4. **NEVER** commit CLAUDE.md with incomplete short-term tasks left in it — clean them up before next session

### When to Delete This File vs. Update It
- **Keep and update**: Always. It's the reference for all future work.
- **Never delete**: CLAUDE.md is the institutional memory of the project.

## Questions for Future Me

**Q: Why RepeatMode.Restart for blob morphing?**
A: It should NOT. Perlin noise is not cyclic: restarting a 0..1000 clock jumps the shape (a snap). Use the forward-only clock `rememberContinuousTime()`. `Restart` is only right for periodic motion such as an angle.

**Q: Why NOISE_OFFSET_DP = 20f?**
A: Gives ~16dp unused space before hitting container boundary. Conservative for safety while providing visible morphing variety.

**Q: Why separate develop→main PR step?**
A: CodeRabbit is configured to only auto-review `main` branch PRs. Develop PRs skip review entirely unless manually triggered.

**Q: How do photos not bunch together?**
A: Golden angle (137.5°) ensures each new photo lands in the largest empty gap, maintaining even spacing regardless of count.

**Q: Why push directly to main via API instead of git push?**
A: HTTP 403 blocks all git push attempts (even force push). GitHub API `push_files` tool bypasses this by committing directly to a branch. Used when normal git workflow is blocked by infrastructure issues.

## Liquid receive session (0.9.4)

When the sender's `/meta` is available the Liquid receiver runs a *session* (`WorkbenchUiState.liquidSession`; `legacyReceive` = old orbit flow for older senders). `WorkbenchViewModel.runLiquidSequence` drives it: photos 1-5 strictly one after another (`IncomingPhoto.tier` 0 for the first 3, 1 for 4-5), 6-10 concurrently by readiness (tier 2), 11+ instantly (tier 3). Stages: WAITING → FEATURED (revealed in the core via `MotherCore(featuredContent = LiquidFeatured)`, bands bottom-to-top over the sender colour) → FLYING (`FlightPhoto`, circle morphs to the rounded cell) → LANDED. UI is in `ui/workbench/LiquidSession.kt` (`LiquidSessionLayer`, drawn in the OUTER Box) plus the core scale (2×, finale overshoot 2.35× → 1) and grid slide in `WorkbenchScreen`. The session colour fades out over the camera view at the end (`accentAlpha`).
Bad checksum: the photo parks in its grid cell (blurred, red; tap → explanation), retries itself up to 5 times (`parkDamaged`), is then shown crisp; if it still mismatches it is shown anyway and a final "couldn't be proved" dialog appears. The Expressive style runs the SAME rules (`cardSession`, `runCardSequence`): the damaged photo stays in its tray tile / big card as a blurred red thumbnail (tap → explanation), retries itself, and the final notice shows; only the staging differs (photos reveal where they are, each finished reveal calls `onFeaturedRevealed`, the session ends when every photo is decided and shown, then `finishCardSession` moves them into the grid). The old red Retry/Skip dialog and `settleReceivedPhotos` now serve only senders without `/meta` (`legacyReceive`). Visual tuning of all of this was done blind (compile-checked only).

## Simulation (Settings > Simulation…)

`SimulationDialog` (both styles) → `WorkbenchViewModel.startSimulation(role, speed, count, damage)`. Generates numbered pictures (`Simulation.kt`, cacheDir/sim) and drives the real UI state paths without network: receiver = `incoming` photos with bands fed bottom-first, per-file arrival and verdicts (`simDownload`, `simFeedBands`, `simRetry`), then `runLiquidSequence` (Liquid) or `enterSettle` (Expressive); sender = generated photos orbit, `sendProgress` ramps, then `onAllFilesDelivered`. FAST = ~60 ms per file, SLOW = 1 s per file. `simActive` stops gallery saves and the network retry; `startDownload` clears it.

## Videos and files (0.9.7)

The "+" button opens a menu: Photos (image picker), Videos (video picker), Files (`OpenMultipleDocuments`). `MediaKind { IMAGE, VIDEO, FILE }` (`ui/workbench/MediaKind.kt`) comes from the MIME type. `FileServer` `/meta` now also sends `kind`, `name`, `size`, `mime`: a video gets a first-frame poster (MediaMetadataRetriever) that goes through the SAME thumb + bands mechanism as pictures; a file has 0 bands. The real file always goes through `/transfer/...` untouched and unchunked (no overall request timeout, 60 s stall limit, 64 KB buffers; the SHA-256 is pre-computed when a file is added). Header file name is the OpenableColumns display name (ASCII-sanitised), so the extension survives.
Receiver: `IncomingPhoto.kind/name/size/progress/poster`; `fileProgressFlow` gives per-file progress. In the Liquid session only pictures run the animated sequence (tiers); videos/files (`runMedia`) show their cell at once (poster/tile, dimmed with a percent while downloading), and the session ends when all are decided. `MediaRegistry` (snapshot map Uri → `MediaInfo`) tells `MediaImage` how to draw non-picture Uris in the grid/orbit. Saving: pictures → Pictures/Flash, videos → Movies/Flash, files → Download/Flash (MediaStore; Android 8-9 direct writes). Known gaps: the Expressive settle flow waits for every verdict with timeouts, so very long videos suit the Liquid style better; videos are not played in-app (poster only).

## Adaptive luminance glass (0.10.2)

`AdaptiveGlass.kt`, modelled on the backdrop catalog's "Adaptive luminance glass" demo (source: github.com/Kyant0/AndroidLiquidGlass, `app/.../destinations/AdaptiveLuminanceGlassContent.kt`; the catalog's "Progressive blur" is NOT a variable-radius blur: it is a constant 4 dp blur plus an alpha-mask/tint gradient, so in light theme it reads as a white strip). `LiquidButton` and `SettingsPanel` pass `onDrawBackdrop = { drawBackdrop -> drawBackdrop(); adaptive.record(this, drawBackdrop) }` so the picture behind them is also recorded into a `GraphicsLayer`; `rememberAdaptiveLuminance` samples it 4×/s (5×5 downscale, luminance 0.2126/0.7152/0.0722), animates `luminance` and a black/white `contentColor` (dead zone 0.45–0.55). `adaptiveColorControls(luminance)` brightens + flattens the glass over bright pictures and darkens it slightly over dark ones. Text/icons on glass must use `LocalGlassContentColor.current` (LiquidButton provides it) or `adaptive.contentColor` (panel), never `Color.White`. Status pills with their own dark background keep white text.

## NFC pairing (0.11.0 rewrite)

Roles are exclusive: **receiver** (no photos selected) runs `NfcAdapter.enableReaderMode` (NFC-A/B, skip NDEF check) and reads the other phone as `IsoDep`; **sender** (photos selected) turns reader mode OFF (a reader cannot be a card) and serves the handshake as a Type 4 tag through `HandshakeHceService` (HCE). `WorkbenchScreen` applies this with `LifecycleResumeEffect(nfcOn, isSender, nfcOff)` because `MainActivity.onPause` switches NFC off. Receiver flow in `NfcManager.readIsoDep`: SELECT AID `F0394D4B03` (fallback: standard NDEF app `D2760000850101`) → SELECT file `E104` → READ BINARY length → READ BINARY the message in 48-byte chunks → NDEF → JSON `{ip,port,token,lang,fileCount}` → `peerHandshakeFlow` → `startDownload`. Same token is ignored for 8 s. The received handshake is held in `NfcManager.pendingHandshake` (a StateFlow) until the ViewModel takes it (`consumeHandshake()`): a touch that starts a closed app arrives before any screen exists, and an event that is only emitted was lost. The manifest only claims our own MIME type (no TECH_DISCOVERED filter), so other apps are not offered Flash's tags. The HCE service answers both AIDs (`hce_service.xml`), so Android's own tag reading, and a closed receiver app (NDEF_DISCOVERED + AAR), also work; `handleIntent` falls back to `readIsoDep` for tag intents. The old bug: the receiver only ever asked `Ndef.get(tag)`, which needs the standard AID the card did not serve, so nothing was ever read.
Diagnostics: `NfcManager.status` (last 6 lines) is shown in the "NFC" box on both screens (receiver: reader on / tag seen / sw codes / bytes read / got ip:port; sender: card ready, whether Android routes our AID to Flash, "reader selected the Flash app", "handshake read by the other phone"), plus a red "NFC is off — tap to turn it on" pill (`uiState.nfcOff`, polled with the Wi-Fi status). Removed: writing the handshake onto physical NFC tags. Device notes to verify: HyperOS may need NFC > HCE/secure-element setting on "HCE wallet"; both screens must be on and unlocked.
**Verified on real phones (user's screen recording, Oct 18, build 50):** Redmi Note (HyperOS) and Samsung Galaxy S24 pair over NFC in BOTH directions — the NFC box showed `reader on` → `read 177 bytes` → `got 192.168.0.x:port, 1 file(s)` on the receiver, and `card ready` → `card: reader selected the Flash app` → `card: handshake read by the other phone (177 bytes)` on the sender, then the Wi-Fi transfer and "Photos delivered" ran normally. So Flash needs NO default-payment-app setting and no custom Xiaomi API; the plain reader-mode + HCE design works (Expressive style was in use).

## Settings screen and in-app updates (0.12.0)

**Settings is its own screen** (`ui/settings/SettingsScreen.kt`, route `Screen.Settings`), the same for Liquid and Expressive (the old liquid-glass panel and the Expressive bottom sheet are gone). It is wrapped in `FlashExpressiveTheme` (dynamic colour). Top: `ChoiceGroup`s = Material 3 Expressive connected `ToggleButton` groups (theme, pairing, interface, language), the "Save received files" switch, Simulation; below them (the `header` slot of `UpdatesScreen`) the update settings. The Simulation dialog needs `WorkbenchViewModel`, taken from the Main nav entry in `NavGraph`.

**Updates are copied from Impulse** (`eu.siacs.conversations.update` / `ui/UpdatesScreen.kt`, branch `unified-compose-search`), stripped of nightly install, bug-report matching, notifications and the alpha/rc channels. Files: `update/` (`UpdateChecker`, `UpdateDownloader` = DownloadManager + FileProvider install, `UpdatePreferences`, `DownloadEtaTracker`, `UpdateChannel`, `UpdateInfo`) and `ui/update/` (`UpdateController` = one state holder used by Settings AND the sheet, `UpdatesScreen.kt` = the settings rows with channel/wait pickers + the sheet content, `UpdateSheetHost` = `ModalBottomSheet`, `MarkdownText`). `UpdateController` lives in `FlashApplication.updateController`; `MainActivity.onResume` calls `showSheetIfPending()` and `checkOnLaunchIfEligible()` (auto-check on app open, on BOTH channels, at most every 5 min, toggle + "wait between updates" in Settings); on Wi-Fi a found update downloads by itself and the sheet pops up; the sheet always says "Flash". No nightly installs: the user taps Install.
**Release tags (CI, `.github/workflows/build.yml`, manual run only):** the tag is `appVersionName`; `X.Y.Z` is stable (`make_latest`), `X.Y.Z-beta.N` is a pre-release. No branch rules. Title = the optional `title` input, default `Flash <version>`; the optional `description` input is put above the commit notes. Title `Flash <tag>: <name from versionName>`, body = the commit message without trailers (the sheet shows it as "What's new"). Old tags starting with `v` do not parse and are ignored. `UpdateChecker` reads up to 3 pages × 100 releases of `DiMaxBrand/Flash` (every develop push is a release), picks the first `.apk` asset, Beta channel also sees stable releases, default channel = Beta. To TEST an update you need two consecutive builds (the installed one and a newer `X.Y.Z-beta.N`); unit tests for version ordering: `UpdateVersionTest`.

## Playground (Settings > Playground, 1.0.1-beta.2, experiments added in 1.0.1-beta.3)

Experimental, English-only screen in `playground/`. **Contact card** (beta.3): "Share (card)" serves a vCard 3.0 record (`text/vcard`) built from typed-in name, phone and email only (no contacts access); "Receive" reads it and, after a tap, opens the contacts app with `ACTION_INSERT`. **Link tag** (beta.3): "Share" serves an NFC Forum URI record; "Receive" shows the address and opens it after a tap, and only http/https links are accepted (a tag must not start arbitrary apps). `NfcManager.routeRead` sends every type `PlaygroundNdef.isPlayground` recognises to the playground. **Wi-Fi tag**: "Share (card)" serves an NFC Forum Wi-Fi Simple Configuration record (`application/vnd.wfa.wsc`, credential TLV 0x100E with SSID 0x1045 and key 0x1027) through `HandshakeHceService`; "Receive" reads it in reader mode and offers the network with `WifiNetworkSuggestion` (Android 10+; Android decides when to join). **Blink test**: the leader serves a `application/vnd.flash.playground` record (ip, port, token) and runs a small Ktor server (`BlinkSync.kt`); the follower reads it, aligns clocks over 7 `/clock` round trips (smallest RTT wins), long-polls `/next`, flashes on each blink and reports its time on the leader's clock to `/flashed`. The leader fires at unannounced random times. NFC only pairs; timing runs over Wi-Fi. The gaps include display latency on both phones, which is part of what the test measures. `NfcManager.readNdef` takes playground messages; handshakes still go to `pendingHandshake`. Only one side can be a card at a time (the reader is turned off while serving). **Lab** (beta.4, `playground/NfcLab.kt`): six experiments that time the NFC link itself. The card answers our own command class 0x80 (HELLO 0xD0 returns the Wi-Fi address, token and data size; PULL 0xD1 reads chunks of the served data, which is 1 MB in the stream variant: 240-byte short APDUs, or 4 KB extended-length APDUs when the reader's IsoDep reports support (API 28+), falling back to 240 bytes for good on the first error reply (`NfcLab.Puller`); PUSH 0xD2 sends tap times; PING 0xD3 returns "pong"). `HandshakeHceService` hands lab commands to `NfcLab.onCardApdu` while a lab card is on, and `onDeactivated` feeds `NfcLab.cardDropped` (the reliable drop signal). The reader side is `NfcManager.setLabReader`, which replaces the handshake read. Experiments: handshake stopwatch, touch race (reader touch time sent over Wi-Fi, matched with the card's first command), tap together (`TapSensor` accelerometer jolts matched over NFC), relay chain (Forward/Receive, hop times appended), contact time (ping loop), hold test (1 MB stream, text ping-pong, quiet). Android exposes no NFC field strength: the report shows round-trip time, speed, the longest pause, the drop reason and time, tag details, and Wi-Fi RSSI as the closest proxy. Each phone shows only its own side, and the reader cannot see a drop until it talks, so quiet mode relies on the card's report.

## Feature flags (Settings > Feature flags, 1.0.1-beta.6)

Same idea as Impulse (`FeatureFlags.kt` + `FeatureFlagPreferences` + a flags screen that iterates the registry). `FeatureFlags.kt` holds `enum FeatureFlag(key, titleRes, descriptionRes, defaultValue)` and `FeatureFlagStore` (plain SharedPreferences overrides, a key absent = Default, an `overrides` StateFlow; `Map<FeatureFlag, Boolean?>.isOn(flag)` resolves it). `FlashApplication.featureFlags` is the store. Settings iterates `FeatureFlag.entries` with the existing `ChoiceGroup` (Default / On / Off): a new flag only needs an enum entry and its two strings (EN + RU). The flags are all off by default and independent (CAMERA_BOX is described in "The camera hole and the birth of the core"):
- **CORE_MOTION** ("Core reacts to motion"): `CoreMotionState.onGravity/onLinear`. The Mother Core drifts up to 10 dp downhill when the phone tilts, a spring-damper (`onLinear`, about 1 dp per m/s2 of acceleration, clamped to 20 dp) pushes it against shaking, and `shake` (0..1) adds up to 8 dp of blob noise. The highlight also slides against the tilt (when 3D is off).
- **CORE_3D** ("3D core"): `CoreBody3D.kt`. A game rotation vector (fallback rotation vector) gives the phone's orientation `R` (phone to world, z up). The core is a rounded puck fixed in the world: its axes `ex/ey/ez` come from `coreFrame(R, standFraction, yaw)`. It LIES (face up) or STANDS (face towards the viewer); `standFraction` is animated 700 ms when the phone crosses the middle (`|R[8]|` below 0.62 = stand, above 0.80 = lie, hysteresis), and `yaw` (the horizontal direction from the core towards the viewer) follows the phone's heading continuously (`viewerYaw`: where the screen points when the phone is upright, where its bottom edge points when it lies flat), so turning the phone horizontally never turns the core away; only tilting shows it from another side (`CoreFrameTest.aHorizontalTurnNeverChangesWhatYouSee`). Orientation follows the phone immediately. It is GLASS, not a coloured body: `projectCore` projects 7 slices of the rounded rim of the same morphing outline and takes the convex outline of all of them as ONE smooth silhouette; the backdrop is refracted over that whole silhouette in SCREEN space (`drawBackdrop` lens in a box sized to the silhouette, clipped to it, drawn under the face so the picture behind is never tilted along with the face); `drawCoreGlass` only adds a faint body, a Fresnel edge, one light/shade sweep and thin edge lines, no colour of its own (the first version drew faceted aqua quads and tilted the lens with the face: it read as a mosaic and as a sticker). The existing flat 2D core is the puck's front: `drawWithContent` + `Canvas.concat` of a perspective matrix from `android.graphics.Matrix.setPolyToPoly` (`coreFaceMatrix`), nothing is drawn while the front is edge-on or turned away. Both flags on = tilt/jiggle offsets and orientation together.
`rememberCoreMotion(tilt, threeD)` registers the sensors only while the screen is resumed and returns null when both flags are off, so with the flags off nothing changes. `CoreFrameTest` checks the geometry (face-on in both poses, orthonormal axes, fixed in the room). Verified only by compiling and that test: the look, the perspective transform on the camera viewfinder and the featured-photo reveal, and the sensor feel are unverified on phones.

## Exit animation and the translucent window (1.0.1-beta.8)

The exit animation (the core shrinks into the camera cut-out, the screen fades) is meant to show what was behind the app. `MainActivity` always set a transparent window background, but the theme never made the window translucent, so the system showed its transparent pixels as black (the recent-apps card shows the real alpha, which is why that looked right). `Theme.Flash` now has `windowIsTranslucent`. A translucent window needs something opaque behind the screens (navigation fades between screens would show the home screen through): `MainActivity` wraps `NavGraph` in a `Box` painted with the theme background, and its alpha goes to 0 (400 ms) while `FlashApplication.revealBehindApp` is true. `WorkbenchScreen` sets that flag from `uiState.shouldExit` and clears it when it leaves. Keep an opaque background behind any new root-level content, and do not add a fixed screen orientation to this activity (a translucent activity with one crashes on some Android 8 builds). Unverified on phones.

## Welcome, hints and NFC checks (1.0.1-beta.9)

- **Welcome** (`ui/onboarding/OnboardingScreen.kt`): four short steps in a `HorizontalPager` (what Flash is, touch the phones, the camera instead, hints) with Skip / Next / Start. The detail lives in the hints, so the welcome stays short. It has its OWN preference, `welcome_v2_shown` (`ThemeRepository.welcomeShown` / `setWelcomeShown`), so EVERYONE sees the rewritten welcome once, including people who had dismissed the old one (`onboarding_shown` is no longer read). If you rewrite the welcome from scratch again, bump the key (`welcome_v3_shown`). `MainActivity` reads the flag ONCE (`produceState` + `first()`) and draws only the opaque background until it is known: the old code started with `initial = false` and flipped a moment later, which flashed the welcome for a second at every start, and a changing `startDestination` also rebuilt the nav graph.
- **Hints** (Settings > Hints, `ThemeRepository.showHints`, off by default; the welcome's last step can turn them on): `ui/hints/Hints.kt` (`HintKind`, `HintsOverlay`: a card in the middle of the screen, tap to hide, reset when the situation ends) and `HintGraphics.kt` (`TapPhonesIllustration` = two THIN phones with rounded corners (a rounded rectangle extruded 4 units against 64 tall, `drawPlate`; NOT boxes) drawn face by face with a tiny 3D projector (`Projector`, yaw -24°, pitch 14°, perspective, a fixed light), their BACKS facing each other, the left one showing its camera island with two lenses, closing until the islands meet in the middle, NFC waves from there. History: the first version tilted two flat phones like an open book (not back to back); the second used thick boxes and the user rightly called them bricks, after I looked at my own render and accepted it. When you check a drawing off-device (render the same math to a picture), judge it against the real thing (a phone is about 1:20 thick to tall, with rounded corners and a camera island), not against "it shows two slabs"; `NfcChipIllustration` = a phone back with the usual chip area marked; `CameraScanIllustration` = a blob with scan corners that close in and rest snug). All motion runs on `rememberContinuousTime()` and is periodic. `WorkbenchScreen` picks the kind: NFC mode with nothing chosen = `NFC_IDLE` ("receiving? just touch; sending? tap + first"), files chosen = `NFC_SEND`; camera mode shows `CAMERA_SCAN` only to the RECEIVER and only once the other phone has files ready (`ColorDetectionState.Detecting`); the camera SENDER sees no hints. No hint while a transfer is running or while the app is leaving. Both interface styles show the overlay. On exit the status boxes and notices (the `Column` drawn outside the sliding content) fade out with `chromeAlpha`: with the translucent window they used to hang over the home screen until the activity closed. `CameraScanIllustration`'s corners use the theme text colour (white ones were invisible on the light background).
- **NFC checks** (always, hints on or off, `WorkbenchScreen`): a phone WITHOUT NFC (`NfcManager.isNfcAvailable` false) while in NFC mode gets a dialog and is switched to camera mode automatically; NFC present but switched off (`uiState.nfcOff`) gets a dialog that opens the NFC settings (asked once until it is on again; the red pill stays).
- **What the welcome promises must stay true:** after the touch the phones can be pulled apart (the handshake is one short NFC read, the files go over Wi-Fi); camera mode needs Camera on both phones, same Wi-Fi, no need to stay close. Unverified on phones; the 3D look of the drawings was only compile-checked.

## The camera hole and the birth of the core (1.0.1-beta.11)

- **How the camera is found** (`util/CameraCutout.kt`, `readCameraCutout`): `view.rootWindowInsets.displayCutout.boundingRects` (Android 9+) = every cutout area the system reports; the one nearest the top edge is the front camera and its centre is where the core is born, and its SHORTER side is the hole's diameter: the hole is a circle, but the user's phone reports a TALL rectangle whose width is right and whose height is not (beta.13; before that the larger side was used, so the core was born too big). Android 12+ also gives the real outline (`cutoutPath`), only shown by the debug overlay. If the phone reports nothing, the top centre of the status bar is assumed (`fromSystem = false`). The core's start offset is that centre, minus the status bar height (the content sits below it), minus the blob's own centre.
- **The old bug:** it was read ONCE at the first frame and captured in a `remember { Animatable(...) }`. At the first frame the window is not attached (`rootWindowInsets` is null, so "no cutout") and the view has no width (so the assumed centre had x = 0 = the top LEFT), and the animation kept that. So a phone could show "from the top" or "from the top left" whatever its camera. Now `WorkbenchScreen` waits until `isReadable(view)` (width > 0 and insets present), then +32 ms, and the core stays hidden until then (`spawnReady`).
- **Feature flag CAMERA_BOX** ("Show the camera box"): `ui/debug/CameraBoxOverlay.kt`, drawn over everything by `MainActivity`: magenta box = each reported area, cyan dashed halo around it, yellow = the real outline if given, green cross = the birth point, a red dashed circle + a label saying so when nothing is reported. Use it to check the app sees the camera.
- **The birth** (`MotherCore`, one progress `spawn` 0..1 over 3.2 s): the core is a BLACK blob (the colour of the hole) at the hole's size, starts at the hole and grows only as it travels to its place (p 0.1..1, one smooth curve; beta.14 — before, it swelled to full size at the camera and went past the top of the screen) while the black layer fades into the glass (p 0.55..0.95; the black is one path drawn over everything, so the glass underneath fades in). Leaving reverses the colour: black again as it is swallowed into the camera. "Liquid": the noise flows faster and wobbles wider at birth and eases back, as a smooth extra time offset `1500 * (1 - (1-p)^3)` (ends as a constant, no jump) and up to +8 dp of amplitude. Unverified on phones, the look is tuned blind.

## Hotspot button (1.0.1-beta.15)

The "No Wi-Fi?" dialog's button calls `util/HotspotSettings.open(context)`. `ACTION_WIRELESS_SETTINGS` only reached "More connectivity options" on Xiaomi (MIUI/HyperOS: airplane mode, private DNS, printing, OTG, no hotspot), so Xiaomi/Redmi/POCO phones first try `com.android.settings/.TetherSettings` and two other tether pages, then `android.settings.TETHER_SETTINGS`, then the old action as the last resort; each is tried and skipped if the phone refuses. Unverified on phones (the component names are not public API).

## Release builds (1.0.1-beta.16)

CI now runs `./gradlew assembleRelease` and publishes `app-release.apk` (before, every release was the `debug` build type: signed with the user's key but not minified and debuggable). The `release` build type uses `signingConfigs.flash` (same key, so it updates over the old debug-type installs), R8 minify on, rules in `app/proguard-rules.pro` (Ktor, serialization, logback/slf4j, NFC classes). R8 passed in the sandbox but runtime behaviour is unverified: if something works in beta.15 and breaks in beta.16 (NFC, transfer, updater, logging), suspect missing keep rules first. Local `assembleDebug` is still the compile check; `assembleRelease` locally is unsigned.

## Handshake misread as a playground tag (fixed in 1.0.1-beta.17)

From beta.3 to beta.16 the receiver logged "playground tag read" for EVERY normal handshake and never started the download, so NFC sending was broken for 14 builds (found by the user on beta.16: the receiver's NFC box said "playground tag" in red while sending files). Cause: `PlaygroundNdef.isPlayground` used `record.toUri() != null` to spot link tags, but `NdefRecord.toUri()` also returns a `vnd.android.nfc://ext/...` URI for an EXTERNAL_TYPE record, and the handshake carries one (the Android Application Record). Now a message holding `HANDSHAKE_MIME` is never playground, and a link tag must be a real URI record (well-known "U" or absolute URI). Lesson: never test a record type with `toUri()`; check `tnf` and `type`.
