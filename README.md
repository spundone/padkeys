# PadKeys

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

PadKeys is an Android system keyboard (input method) spun off from [PadBridge](https://github.com/spundone/padbridge)'s
on-screen keyboard. It keeps that keyboard's feel and types into this phone's apps instead of a Bluetooth computer.

- **Layouts:** Phone (ten big columns, number row, `?123` symbols page, slim Esc / Tab / Ctrl / Alt / arrows row),
  Laptop, 65%, Full and Split. One layout upright and one sideways; the keyboard button left of the suggestions
  cycles layouts, holding it opens Android's keyboard picker.
- **Flick:** flick a key up (or hold it) for its shifted character, shown small in the corner.
- **Space-bar cursor:** drag along the space bar to move the cursor; with Shift held it selects.
- **Modifiers:** tap = one-shot, tap twice = lock, hold = chord. Ctrl + A / C / X / V / Z / Y, Ctrl + Backspace
  (delete word), Shift + arrows (select), Fn + number row = F-keys on the laptop layouts.
- **Enter** runs the field's action (Go, Search, Send, Next, Done) or types a newline.
- **Suggestions:** completions from a bundled list of 40,000 common English words and corrections from the phone's
  spell checker, for the word before the cursor. Off in password, number and no-suggestion fields.
- **Learning** (opt-in, off by default): remembers words you type, only on the phone, never from passwords or fields
  that ask for no personalized learning (incognito). "Forget learned words" clears them.
- Material 3 Expressive look: rounded tonal keys that morph toward a pill when pressed, a primary-color Enter with
  drawn action icons, a flat toolbar row, Google Sans where the phone has it; the settings app uses a collapsing
  large top bar, grouped list items and connected toggle groups.
- Haptics (Auto / Rich / Basic), key preview bubbles, colors from the wallpaper and light / dark mode, optional
  "Realistic" hardware look.

PadKeys has no internet permission.

## Install

Download the APK from [Releases](https://github.com/spundone/padkeys/releases/latest), install it, open PadKeys
and follow the two setup steps (turn it on in Settings › On-screen keyboards, then pick it). PadKeys has no internet
permission, so it doesn't update itself: watch the releases, or add the repo to
[Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates.

Also by the same author: [PadBridge](https://github.com/spundone/padbridge) turns your phone into a Bluetooth
trackpad, keyboard, remote and gamepad for your computer or TV.

## Build

Android Studio or the command line (JDK 17+, Android SDK with platform 37):

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk (package dev.padkeys.debug)
./gradlew lintDebug
```

Then turn it on in Settings › System › Keyboard › On-screen keyboards, or run the app and follow its setup steps.
From adb: `adb shell ime enable dev.padkeys.debug/dev.padkeys.PadKeysService` and
`adb shell ime set dev.padkeys.debug/dev.padkeys.PadKeysService` (`adb shell ime reset` goes back to the default).

## Code

| File | What |
| --- | --- |
| `PadKeysService.kt` | The `InputMethodService`: keys → `InputConnection` (text, Backspace, Enter / editor action, key events, shortcuts), suggestion picking, auto-capitals |
| `KeyboardView.kt` | The keyboard view and layouts, adapted from PadBridge's (no HID; reports to a listener) |
| `SuggestionStrip.kt` | Suggestions, layout and settings buttons |
| `Predict.kt` | Word list completions, spell checker corrections, opt-in learned words |
| `Field.kt` | What the focused field allows (suggestions, learning, Enter label, symbols first) and word finding |
| `Prefs.kt`, `Ui.kt`, `Real.kt` | Settings, colors and haptics, the Realistic look |
| `MainActivity.kt`, `ui/` | Setup, test field, settings and licenses (Compose, Material 3 Expressive) |

## License

Apache License 2.0, see [LICENSE](LICENSE).

The English word list (`app/src/main/assets/words_en.txt`) comes from
[wordfreq](https://github.com/rspeer/wordfreq) by Robyn Speer, whose data is licensed
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); the list is shared under the same license.
