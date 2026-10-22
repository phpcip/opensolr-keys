# Opensolr Keys

A keyboard for Android that looks and feels like the iOS keyboard, and does nothing else.

- No network permission. Nothing you type ever leaves the phone.
- No dependencies, no services, no analytics. One small APK.
- Big, bordered keys with the iOS layout, key preview and long-press accents (Romanian diacritics first).
- Small autocorrect from offline word lists (English, Romanian), only for words that are not in the dictionary. Backspace undoes a correction.
- Emoji picker, plus emoji suggestions while typing (`lol`, `sad`, `:facepalm`, …).
- The system tap sound with its own volume, vibration with its own strength, key size and bottom offset.

## Build

```
./gradlew assembleRelease
```

Put a `signing.properties` next to `build.gradle.kts` to sign the release build (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

## Third party

Word lists from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (MIT), emoji data from [gemoji](https://github.com/github/gemoji) (MIT). Licenses in `third_party/`.
