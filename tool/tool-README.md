# Bible — a tool for the Light Phone III

A simple, distraction-free Bible for LightOS. Built with the official
[Light SDK](https://github.com/lightphone/light-sdk).

## What it does

- **Read offline.** On first launch the app downloads the King James Version
  (one ~8 MB download) and stores it on the device. After that, reading works
  with no connection at all.
- **More translations.** Eight public-domain / freely licensed versions can be
  downloaded and kept offline: KJV, AKJV, ASV, BSB, YLT, Darby, BBE, and
  Webster. Downloaded versions can be deleted at any time to free space.
- **ESV support.** Enter your own free API key from
  [api.esv.org](https://api.esv.org) and the English Standard Version becomes
  available, fetched chapter-by-chapter from Crossway's official API
  (per their license, ESV is not stored on the device).
- **NIV, NKJV and more via API.Bible.** Enter your own free key from
  [scripture.api.bible](https://scripture.api.bible) and enable any
  translations your account unlocks, including licensed versions chosen on
  your API.Bible plan. Fetched live, with each publisher's copyright notice
  displayed as required.
- **Search.** Approximate full-text search across the selected version
  (exact phrase, any-order words, and word-stem matching). ESV and API.Bible
  versions use their providers' own search engines.
- **Reading.** Tap any verse to highlight it while you read (session-only).
  Previous/next buttons walk chapter to chapter across book boundaries.
- **Where I Left Off.** Tap the star in the reader to save your spot for the
  current version; the home menu jumps you back there. Saving is always
  deliberate — reading and searching never move your spot.
- **Saved verses.** Long-press a verse to save it. The Saved screen lists your
  current version's saves first, then saves made in other versions (each
  showing the reference, version, and date), and tapping any save opens it in
  your current version. Saves are stored per version with an offline text
  snapshot, so the list works with no connection.

## Scripture sources & licensing

- Offline translations are public domain or freely licensed, sourced from
  [scrollmapper/bible_databases](https://github.com/scrollmapper/bible_databases).
- ESV text is fetched from Crossway's API under their free non-commercial
  terms, using the user's own key. The required copyright notice is displayed
  with the text.
- API.Bible content is fetched under the user's own API.Bible plan, with each
  publisher's copyright notice displayed as returned by the API.
- No analytics, no accounts, no tracking. The only network calls are the
  scripture downloads/fetches described above.

## Building

This repo is a fork of the Light SDK. The tool lives in
[`tool/src/main/kotlin/com/dailyhobbyist/bible/`](src/main/kotlin/com/dailyhobbyist/bible/)
and builds exactly like the SDK's sample tool: open the repo in Android
Studio, sync, and run the `tool` configuration on the LightOS emulator
(see the SDK docs for emulator setup).

## License

MIT, same as the Light SDK this fork is built on.
