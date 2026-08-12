# AdBlocker Suite

One Android app combining three accessibility-service-based filters:

- **TikTok** - skips ads and blocked creators, with an optional real Block/Download
  automation (audio extraction included). Ported from `tiktok-feed-filter`.
- **Spotify** - detects ads and taps the existing Skip/Next control; a floating button
  also taps Spotify's own real "Download for offline" toggle. Ported from `spot_block`.
- **Podcast Addict** - a floating button that skips forward a **duration you configure**
  in Setup. There's no automatic ad detection here (see "Why no automatic detection?"
  below) - just a fast manual skip sized to how long your podcast's sponsor reads
  actually run.

Nothing in this app touches the network. Everything either reads on-screen
accessibility text, taps an existing on-screen control, or (Podcast Addict only) calls
Android's own public MediaSession API. See `PRD.md` for the full design writeup.

## Setup

Each module needs its own permission grant(s), from the app's Setup tab:

| Module | Grant needed | Where |
|---|---|---|
| TikTok | Accessibility Service | Settings > Accessibility |
| Spotify | Accessibility Service | Settings > Accessibility |
| Podcast Addict | Accessibility Service **and** Notification access | Settings > Accessibility, Settings > Notification access |

Podcast Addict needs two separate grants because the floating button and the actual
skip mechanism are two different services: the accessibility grant lets the button
know when to show itself (Podcast Addict is in the foreground); the notification-access
grant is what actually lets the app read Podcast Addict's current playback position and
call Android's `seekTo()` control. Skipping the second grant means the button will show
up but every tap will log a "no active media session" failure rather than silently
doing nothing.

The Diagnostics tab has one shared toggle and log file covering all three modules -
off by default; turn it on when troubleshooting, then Share or Clear it.

## Why no automatic ad detection for Podcast Addict?

Podcast sponsor reads are recorded directly into the episode's audio by the podcaster.
Unlike TikTok/Spotify's ads, there's no on-screen text, no distinct UI state, and no API
signal an accessibility service (or any on-device, non-network, non-audio-analysis tool)
can key off - detecting it would mean actually listening to and classifying the audio,
which is a materially different, much larger project. Instead, since ad-read lengths
tend to be consistent per show, the Podcast tab lets you set a skip duration once (e.g.
30s, 45s - whatever matches your podcast) and tap through with one button.

## Honest limits

- **TikTok/Spotify's keyword matching is heuristic**, not an official "is this an ad"
  API - if either app changes its wording, add the new phrase to the relevant keyword
  list in Setup; no rebuild needed.
- **TikTok's Block/Download buttons now toast their real outcome on every tap** - "Blocked
  X" always fires immediately (that part is guaranteed, local, and permanent - future
  videos from that creator get auto-skipped regardless of what follows), but the *also*-block-
  it-inside-TikTok and Save/Download automations depend on tap-searching for guessed menu
  button labels (`More options`, `Block`, `Save video`, etc. - see Setup's keyword lists),
  which are unverified against a real TikTok build and can time out silently if your TikTok's
  actual wording differs. The toast tells you which happened; if the automation keeps
  failing, add your TikTok's real button wording to the matching Setup list.
- **Every auto-skip (ad/blocked-creator) now toasts the instant it fires**, e.g. "Skipped
  ad (\"Ad starts in\")". This is for attribution, not notification: if TikTok ever seems
  to scroll on its own, this toast is the fast way to tell "this app did that" from
  "something else did" (TikTok's own autoplay, or another accessibility-capable app -
  Tasker/AutoInput/MacroDroid-style automation setups can dispatch gestures too, and
  Android allows more than one accessibility service to run at once). No toast at the
  moment it happens means it wasn't this app. The TikTok tab's Activity list has the
  same information after the fact, always recording regardless of the Diagnostics
  logging toggle.
- **Podcast Addict's skip depends on its MediaSession supporting `ACTION_SEEK_TO`** -
  unverified from this build environment (no way to install/run the app here). If a
  tap reports "seek not supported," Podcast Addict's session doesn't expose that
  control and this approach won't work for it; check the Diagnostics log for the exact
  failure.
- **Subject Boost's auto-like** (TikTok tab) can only nudge TikTok's own recommendation
  signals, not verify or guarantee any actual change in what gets shown.

## Building

Standard Android Studio project - open the repo root, let Gradle sync, run
`:app:assembleDebug`. `minSdk 24`, `compileSdk`/`targetSdk 34`.

This project was built in a sandboxed environment without a Kotlin compiler
(`kotlinc`) available, so it has not been compile-checked or run here - see
`PRD.md`'s "Verification status" for what specifically still needs a real
device/Android Studio to confirm, in particular whether Podcast Addict's MediaSession
supports seeking.
