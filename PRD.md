# PRD — AdBlocker Suite (TikTok + Spotify + Podcast Addict)

## Goals

Combine two existing, working accessibility-service apps — `tiktok-feed-filter` and
`spot_block` — into a single Android app, and add a genuinely new third module for
Podcast Addict. One app, one install, one place to manage all three.

## Non-goals

- No automatic in-episode audio-ad *detection* for Podcast Addict. Podcast sponsor
  reads are baked directly into the audio by the podcaster, with no on-screen or
  API-visible signal an accessibility service (or any on-device, non-network,
  non-audio-analysis tool) can key off. This was explicitly scoped down by the user
  after discussion — see "Podcast Addict module" below for what was built instead.
- No dismissing Podcast Addict's own in-app (visual/AdMob) ads — user explicitly said
  they don't care about those; only the audio ad reads matter to them.
- No behavior change to the TikTok or Spotify detection logic itself. Both are ported
  as close to verbatim as the merge allows (only package names and the shared
  DiagnosticLog wiring change) to avoid regressing logic that's already been tuned
  against real device diagnostic logs.

## Architecture decision

Both source apps turned out to be structurally near-identical (same
`SettingsRepository`/`StatsRepository`/`OverlayController`/`MainActivity` shape, and a
byte-for-byte identical `DiagnosticLog.kt`). Rather than force a deeper shared-code
refactor that risks regressing two independently-tuned detection engines, the merge
shares only what was already provably identical and keeps the rest module-local:

- **Shared**: `diagnostics/DiagnosticLog.kt` (ported verbatim, now takes a simple
  `isEnabled: () -> Boolean` supplier instead of a full SettingsRepository, since there
  are three of those now) + `diagnostics/GlobalSettings.kt` (one on/off toggle, one log
  file, for all three modules — tagged `[TikTok]`/`[Spotify]`/`[Podcast]` per entry).
- **Module-local** (own package, own `SettingsRepository`, own `StatsRepository`): the
  TikTok and Spotify detection/automation logic, ported near-verbatim.
- **New**: a Podcast Addict module (see below).
- **Unified at the UI layer**: one `MainActivity` with a tab switcher (TikTok / Spotify
  / Podcast / Diagnostics) instead of three separate apps to install and manage.

## Podcast Addict module — what "skip the audio ads" actually means here

Per the user's own resolution to the detection problem ("if I know how long the ad
duration is on a consistent basis I can add a skip button for that duration"), this is
a **manual button with a user-configurable skip-forward duration**, not automatic ad
detection. Per-podcast ad-read lengths tend to be consistent, so a one-tap
"skip forward N seconds" button is honest and genuinely useful without pretending to
detect anything it can't.

Two real, buildable pieces make this work:

1. **`PodcastMediaListenerService`** (`NotificationListenerService`) — the standard
   public Android API path to read another app's active `MediaSession`. Once the user
   grants Notification access (a separate permission from the accessibility grant),
   this listens for Podcast Addict's `MediaController` via
   `MediaSessionManager.getActiveSessions()` / `addOnActiveSessionsChangedListener`.
2. **`PodcastAddictSkipService`** (`AccessibilityService`) — shows a floating "Skip Ns"
   overlay button whenever Podcast Addict is in the foreground (same
   `TYPE_ACCESSIBILITY_OVERLAY` pattern as the other two modules' overlays). Tapping it
   calls `PodcastPlaybackBridge.skipForward()`, which uses the real
   `MediaController.getTransportControls().seekTo(currentPosition + durationMs)` — the
   same official control surface Android Auto / Bluetooth media buttons use.

**Honest limit**: if Podcast Addict's `MediaSession` doesn't advertise
`PlaybackState.ACTION_SEEK_TO` (i.e. it doesn't support seeking through this API), or no
active session is found, the button reports and logs that failure explicitly rather than
silently doing nothing or pretending to succeed. This can't be confirmed against a real
device from this sandbox — most modern media apps (including Podcast Addict, per its
Android Auto/Wear support) do implement seekable sessions, but this needs real-device
verification, tracked below.

## Functional requirements

- TikTok: ad-keyword skip, blocked-creator skip, Subject Boost auto-like, optional real
  Block/Download automation with audio extraction, live-stream skip — all ported as-is.
- Spotify: ad-keyword detection + Skip/Next tap (honestly distinguishing tapped vs.
  found-but-disabled vs. not-found), Download-toggle tap — ported as-is.
- Podcast Addict: configurable skip-forward duration (seconds), overlay button shown
  only while Podcast Addict is foregrounded and the feature is enabled, stats/log
  entries for every skip attempt and its outcome.
- One shared Diagnostics tab: single log file, single enable/disable toggle, share/clear
  actions — covers all three modules' log entries.
- Each module's own tab keeps its own settings, keyword lists, target package list, and
  stats — unchanged from the source apps except namespacing.

## Implementation checklist

- [x] Gradle scaffold (root + app build files, real gradle wrapper copied from spot_block)
- [x] AndroidManifest: 2 AccessibilityService entries + 1 NotificationListenerService +
      FileProvider + permissions
- [x] Shared `DiagnosticLog` + `GlobalSettings`
- [x] TikTok module ported (`filter/`, `overlay/`, `tiktokactions/`, `media/`,
      `SettingsRepository`, `StatsRepository`, `TikTokFilterService`)
- [x] Spotify module ported (`ad/`, `overlay/`, `SettingsRepository`,
      `StatsRepository`, `SpotifyAdSkipService`)
- [x] Podcast Addict module built (`SettingsRepository`, `StatsRepository`,
      `PodcastPlaybackBridge`, `PodcastMediaListenerService`,
      `PodcastAddictSkipService`, `overlay/PodcastOverlayController`)
- [x] Tabbed `MainActivity` + layouts wiring all three modules + shared Diagnostics tab
- [x] Unit tests ported (FilterEngine, ActionSequence, AdDetector) + new tests
      (Podcast SettingsRepository duration parsing, PodcastPlaybackBridge outcomes)
- [x] README + push to `https://github.com/ddann74/ad-blocker`

## Verification status

Same limitation as every other Android project built this session: this sandbox has
`java`/`javac` but no `kotlinc`, so this code has **not** been compile-checked here.
Real verification (Gradle sync, `assembleDebug`, and actually testing the Podcast
Addict skip button against a real Podcast Addict install) needs to happen in Android
Studio on a real device, same as `tiktok-feed-filter` and `spot_block` were. In
particular, whether Podcast Addict's MediaSession actually supports `ACTION_SEEK_TO`
is unverified until then — that's the one real open question for the new module.

## Post-launch fixes (from real `assembleDebug` runs and real device use)

- **Resource compile failure**: `podcast_accessibility_service_description` used a
  backslash-escaped apostrophe (`Android\'s`) — normally valid Android string-resource
  syntax, but it crashed AGP 8.5.2's resource compiler with "Invalid unicode escape
  sequence". Fixed by rewording around the apostrophe rather than relying on escaping
  behavior that isn't consistent across compiler versions. First real evidence this repo
  had actually been built (`:app:assembleDebug`) since it was written.
- **TikTok scroll-back bug**: the service re-skipped a video the user manually scrolled
  back to, because the skip-dedup key was time-based/single-slot. Fixed to track every
  skipped video's identity for the session instead of just the last one.
- **Block-permanence bug** (found while fixing the above): the scroll-back fix's dedup
  key was `FilterEngine.extractHandle()`, which only identifies the *creator*, not the
  specific video — so once one video from a blocked creator was skipped, every other
  video from that same creator shared the identical key and was wrongly treated as
  "already skipped," silently breaking blocking after the first hit. Fixed by adding
  `FilterEngine.videoFingerprint()` (the video's full own text block: caption, stats,
  etc., not just the creator's name) as the dedup key instead — the same latent bug also
  existed in Subject Boost's auto-like dedup and was fixed there too.
- **Overlay/filter running over other apps**: a real diagnostic log (9-minute capture,
  381 filter evaluations) caught the TikTok overlay showing and the filter actively
  evaluating text for ~11 seconds while the actual on-screen content was the phone's
  home screen/app drawer/recents view (not TikTok) - confirmed by the exact text
  collected (`Clock, WhatsApp, ..., TikTok, ..., Close all, 5.08 GB available`, a
  RAM-cleaner/recents banner, not anything TikTok renders). Root cause: some OEM
  launchers' Recents/task-switcher screens report the underlying app's real package on
  the accessibility *event* even while the actually-visible content is the launcher's
  own live-preview card of that app's last window - `onAccessibilityEvent` only ever
  checked the event's claimed package, never cross-checked it against what
  `rootInActiveWindow` itself actually belongs to. Fixed by also checking
  `root.packageName` and treating a mismatch the same as leaving the app (hide overlay,
  evaluate nothing) - the event's claim alone is no longer trusted on its own.
- **Diagnostic log self-spam masking the real issue**: two consecutive real diagnostic
  logs uploaded to investigate a reported "keeps autoscrolling" symptom both turned out
  to contain ~380 duplicate log lines for a single unchanged video, filling the entire
  512KB log cap in under 2 minutes and trimming away whatever happened before the log
  was pulled - the actual triggering moment, both times. Root cause: TikTok fires
  `typeWindowContentChanged` far more often than the visible video actually changes
  (view counters, etc.), and every evaluation was logged regardless of whether anything
  new was found. Fixed by throttling the "no match"/"skip suppressed"/"live-skip
  disabled" log lines to once per distinct video (keyed on the same
  `FilterEngine.videoFingerprint` used for skip-dedup) instead of once per accessibility
  event - a genuinely new skip decision is still always logged. This alone doesn't fix
  the reported autoscrolling (still unconfirmed/un-root-caused as of this entry - see
  below) but should let the *next* log capture actually retain enough history to show
  it, instead of self-evicting it within two minutes.
- **Silent Block/Download failures**: neither button gave any on-screen feedback at all -
  a failed menu-tap automation (guessed button wording not matching the real TikTok
  build) looked identical to nothing happening. Added a `Toast` at every real outcome
  (added to blocklist / blocked in TikTok / automation timed out / video saved / audio
  extracted / extraction failed / live-stream reject / already-in-flight reject), plus
  broadened the default keyword lists for More Options/Block/Confirm/Download with more
  plausible real-world label variants. Still unverified against a real TikTok build's
  actual wording — the toasts make failures visible and point at exactly which Setup
  keyword list to edit, they don't guarantee success.
- **Toast attribution didn't work — root cause: Android Toast from a background
  AccessibilityService is unreliable, not the attribution idea itself.** A user-provided
  real diagnostic log showed 72 genuine skip decisions dispatched over ~2 hours, but
  zero toasts were ever seen. Most likely a MIUI-style OEM restriction on background
  services showing Toasts (a separate, off-by-default permission from the accessibility
  grant this app already asks for). Fixed by building `TransientBannerOverlay`
  (`app/src/main/java/.../tiktok/overlay/TransientBannerOverlay.kt`) — a small
  auto-dismissing text view shown via the same `TYPE_ACCESSIBILITY_OVERLAY` window
  mechanism already confirmed working on the same device (the Block/Download floating
  buttons render and are interactive there). All skip/Block/Download feedback -
  previously `Toast.makeText()` - now routes through this instead, sidestepping the
  whole class of OEM Toast restriction rather than depending on a system API this app
  had direct evidence it couldn't trust.
- **Root cause of the reported "autoscrolling" finally confirmed, not guessed: `"Ad"`
  matched as a substring inside `"Add"`.** A real diagnostic log showed 56 genuine skip
  decisions in under 8 minutes, spanning completely unrelated creators/videos, every
  single one matching the keyword `"Ad"`. Every TikTok video's on-screen text includes
  the boilerplate UI element "Add or remove this video from Favourites" - a plain
  case-insensitive substring search meant `"Ad"` matched inside `"Ad"d` there, so the
  app was skipping nearly every video, near-instantly, regardless of content.
  Immediate fix for the user: remove `"Ad"` from Setup's Ad Keywords list. Structural
  fix - first attempt was wrong, corrected before shipping: initially added a minimum
  keyword length, but the user pointed out this would also block a genuinely short,
  legitimate keyword (TikTok may well render a bare "Ad" badge on real sponsored
  content) - the actual problem was never length, it was substring-inside-a-word
  matching. Reverted the length gate and instead changed `FilterEngine.evaluate()`'s ad-
  keyword check (and `matchesSubject()`, same underlying issue) from `String.contains()`
  to a word-boundary regex match (`\bkeyword\b`) - "Ad" now matches the standalone word
  "Ad" but not the "Ad" inside "Add". New tests lock in both directions: a short keyword
  must NOT match inside an unrelated word, and MUST still match when it genuinely
  appears as its own word.

## Ralph loop disclosure

No autonomous loop mechanism was actually invoked for this PRD — every checklist item
above was implemented directly, in this same session, not by a background iterate-and-
commit loop. Same disclosure as every other PRD written this session except
`NPL_Intelligence_Engine`'s (which had a genuine pre-existing one).
