# Opensolr Keys

A keyboard for Android that looks and feels like the iOS keyboard, and does nothing else.

- No network permission. Nothing you type ever leaves the phone.
- No dependencies, no services, no analytics. One small APK.
- Big, bordered keys with the iOS layout, key preview and long-press accents (Romanian diacritics first).
- Small, context-aware autocorrect from offline word and word-pair tables (English, Romanian): Romanian diacritics restored by context (`ca si` → `ca și`, and `este ca` is revised once the next word is known), English confusables (`their/there`, `its/it's`). Words you keep as typed are remembered. Backspace undoes a correction.
- Emoji picker, plus emoji suggestions while typing (`lol`, `sad`, `:facepalm`, …).
- The system tap sound with its own volume, vibration with its own strength, key size and bottom offset.

## Build

```
./gradlew assembleRelease
```

Put a `signing.properties` next to `build.gradle.kts` to sign the release build (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

## Third party

Word and word-pair counts computed from the OPUS OpenSubtitles2018 corpora (see `third_party/NOTICE-OpenSubtitles.txt`), emoji data from [gemoji](https://github.com/github/gemoji) (MIT). Licenses in `third_party/`.
