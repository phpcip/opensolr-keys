# Opensolr Keys

A keyboard for Android that looks and feels like the iOS keyboard, and does nothing else.

- No network permission. Nothing you type ever leaves the phone.
- No dependencies, no services, no analytics. One small APK.
- Big, bordered keys with the iOS layout and a number row, drawn in Inter Display Bold, a blue return key of the same shape, key preview and long-press symbols and accents (Romanian diacritics first).
- The 123 and #+= pages keep the same five rows as the letters, so the keyboard never changes size.
- Number pads chosen by the field: PIN or plain number, number with sign or decimals, phone, date and time.
- Context-aware autocorrect from offline word and word-pair tables (English, Romanian): Romanian diacritics restored by context (`ca si` → `ca și`, and `este ca` is revised once the next word is known), English confusables (`their/there`, `its/it's`) and contractions (`dont` → `don't`), typos weighed by how near the keys are (`hwllo` → `hello`, `tommorow` → `tomorrow`), two words typed as one (`alot` → `a lot`, `nustiu` → `nu știu`). Backspace undoes a correction, and a word sent with its correction undone is learned; the × on a suggestion keeps the word as typed and remembers it.
- Word suggestions on the bar while typing: the correction the space key would make, then the words the typed letters may be the start of, picked by the word before. The suggestion comes back for the word under the cursor and for a selection.
- Emoji picker with every Unicode emoji the phone can draw, a Recent tab and search, plus emoji suggestions while typing, in English and Romanian, for words (`hmm`, `oops`, `lol`, `multumesc`), emoticons (`:)`, `:))`, `<3`, `:P`) and `:names` (`:facepalm`).
- Round emoji and hide buttons in the bottom corners. The suggestion bar is always there; with nothing to suggest it shows a settings cog and the Opensolr logo (opens https://opensolr.com in the browser).
- The system tap sound with its own volume, a short key tap with its own strength (one for every character a held delete removes), key size and bottom offset.

## Build

```
./gradlew assembleRelease
```

Put a `signing.properties` next to `build.gradle.kts` to sign the release build (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

## Third party

Word and word-pair counts computed from the OPUS OpenSubtitles2018 corpora and the Leipzig Corpora Collection (news and Wikipedia), word forms from the Hunspell dictionaries in the LibreOffice dictionaries repository (ro_RO, en_US, en_GB); the build scripts are in `tools/dict/`. Key font [Inter](https://github.com/rsms/inter) Display Bold (SIL Open Font License), emoji data from [gemoji](https://github.com/github/gemoji) (MIT), emoji keywords also from the [Unicode CLDR](https://github.com/unicode-org/cldr) annotations (Unicode License) and [emojilib](https://github.com/muan/emojilib) (MIT); the build script and the hand-picked words and emoticons are in `tools/emoji/`. Licenses and notices in `third_party/`.
