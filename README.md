# LP3-Bible

A Bible reader for the **Light Phone 3**, built under the DailyHobbyist name.

- **Package:** `com.dailyhobbyist.bible`
- **Version:** 2.0 (versionCode 11)
- **Permissions:** `android.permission.INTERNET`

## What this repo is

This is a full checkout of the Light Phone SDK. **The app itself lives in `tool/`** —
that's the only folder that holds DailyHobbyist code. Everything else is upstream SDK
scaffolding that has to be present for the build to work.

- `tool/lighttool.toml` — the app manifest, and the **single source of truth for the
  version number**. Both `versionCode` and `versionName` get bumped on every build.
- `tool/` — the app source.

## Two things that will trip you up

**The default branch is `bible`, not `main`.** Anything that assumes `main` will fail
against this repo.

**`serverPackage` must be `com.lightos`.** The value `com.thelightphone.sdk.emulator` is
for the SDK emulator only. This repo was committed in emulator mode once, which produces a
build that will not run on the phone; it was corrected on 2026-09-01. Check this line
before every build.

## Related

Other DailyHobbyist Light Phone 3 apps live in sibling repos under `cmg-ops`
(LP3-Lists, LP3-StopWatch, LP3-Budget, LP3-DrawPad, LP3-Rolodex, LP3-Calculator,
LP3-Routines, and others).
