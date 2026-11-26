# PadKeys

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

PadKeys is an Android system keyboard (input method) spun off from [PadBridge](https://github.com/spundone/padbridge)'s
on-screen keyboard. It keeps that keyboard's feel and types into this phone's apps instead of a Bluetooth computer.

- **Layouts:** Phone (ten big columns, number row, `?123` symbols page, slim Esc / Tab / Ctrl / Alt / arrows row),
  Laptop, 65%, Full and Split. One layout upright and one sideways; the keyboard button left of the suggestions
  cycles layouts, holding it opens Android's keyboard picker.
- **Flick:** flick a key up (or hold it) for its shifted character, shown small in the corner.
- **Glide typing:** slide through a word's letters without lifting, Gboard-style, on every layout's letters. The
  word goes in with a space before it (none after, so punctuation sticks to it); Shift / Caps Lock give "Hello" /
  "HELLO"; the strip offers the next guesses (tap one to swap it in) and Backspace right after deletes the whole word.
  A fading trail in the theme color and a preview of the best guess follow the finger. Decoded on the phone from
  the bundled word list (and learned words). Off in password fields; trail and glide can be turned off.
- **Flick:** flick a key up for its shifted character, shown small in the corner (holding works too on keys
  without accents).
- **Hold for accents:** hold a letter for its accented forms (e → è é ê ë ē ę ė, a → à á â ä æ ã å ā, …) and slide
  to one; hold the period for punctuation; hold the comma (Phone layout) for emoji.
- **Autocorrect:** a misspelled word is fixed when a space or punctuation ends it (common slips like "teh" and
  "dont", the phone's spell checker, the nearest common word by a keyboard-aware edit distance). Backspace right
  after puts back what you typed. Off in password, email, web address, name and no-suggestion fields.
- **Double-space period** and capitals at the start of sentences (where the field asks for them).
- **Swipe to delete:** drag left from Backspace to select whole words, lift to delete them.
- **Space-bar cursor:** drag along the space bar to move the cursor; with Shift held it selects.
- **Modifiers:** tap = one-shot, tap twice = lock, hold = chord. Ctrl + A / C / X / V / Z / Y, Ctrl + Backspace
  (delete word), Shift + arrows (select), Fn + number row = F-keys on the laptop layouts.
- **Enter** runs the field's action (Go, Search, Send, Next, Done) or types a newline.
- **Number pad and phone pad** in number, phone and date fields (ABC for the full keyboard).
- **Toolbar** (in the suggestion strip when there are no suggestions): emoji, clipboard, text editing, one-handed
  mode and voice typing.
  - **Emoji:** categories, recently used (kept on the phone), Backspace and ABC.
  - **Clipboard:** text copied while the keyboard is open (in memory only, until PadKeys stops) and pinned items;
    tap to paste. A fresh copy is also offered as a chip above the keys for a minute. Clips marked sensitive are
    skipped.
  - **Text editing:** arrows, Select (arrows extend the selection), Home / End, Select all, Copy, Cut, Paste.
  - **One-handed mode:** the keys shrink to one side, with buttons to switch sides or go back to full width.
  - **Voice typing:** speak and PadKeys types, with the phone's speech recognizer (Google's on most phones; the
    audio goes to that service, not to PadKeys, and PadKeys asks for the microphone the first time). Words show as
    you speak. Phones without a recognizer switch to their voice typing keyboard instead.
- **Suggestions:** completions from a bundled list of 40,000 common English words and corrections from the phone's
  spell checker, for the word before the cursor. Off in password, number and no-suggestion fields.
- **Learning** (opt-in, off by default): remembers words you type, only on the phone, never from passwords or fields
  that ask for no personalized learning (incognito). "Forget learned words" clears them.
- Material 3 Expressive look: rounded tonal keys that morph toward a pill when pressed, a primary-color Enter with
  drawn action icons, a flat toolbar row, Google Sans where the phone has it; the settings app uses a collapsing
  large top bar, grouped list items and connected toggle groups.
- **TrackPoint:** a red nub between G, H and B (in the gap on Split). Push it to move the cursor, faster the
  further you push, up and down by line; with Shift on it selects.
- **Modifiers like a hardware keyboard:** Ctrl, Alt and Win are real held keys in remote desktop and terminal apps
  (or everywhere, if you choose): hold or lock Alt and tap Tab for Alt+Tab, Ctrl+Alt+Del, Win+E. A Win key sits in
  the bottom row of the PC layouts. Android's own system shortcuts only answer physical keyboards.
- **PC keys:** an optional function row (Esc, F1–F12) above every layout, and with a hardware keyboard attached
  PadKeys stays on screen as a slim bar of Esc, F1–F12, Del and (on wide screens) Home, End, PgUp, PgDn.
- Themes like PadBridge's: system / light / dark; PadKeys blue, wallpaper, a custom palette grown from one color, or
  two-tone (two colors, with presets and a contrast check); pure black for OLED; optional "Realistic" hardware look.
- Haptics (Auto / Rich / Basic) and key preview bubbles.

Nothing you type leaves the phone. Since 1.2.0 PadKeys has the internet permission for one thing only: in-app
updates. The settings screen (never the keyboard) checks GitHub's latest release at most once a day (or when you
tap "Check for updates"; the daily check can be turned off), downloads the APK, verifies its checksum and signing
key, and hands it to Android's installer.

## Install

Download the APK from [Releases](https://github.com/spundone/padkeys/releases/latest), install it, open PadKeys
and follow the two setup steps (turn it on in Settings › On-screen keyboards, then pick it). From 1.2.0 on,
PadKeys updates itself (Settings › About › Check for updates). Versions before 1.2.0 can't, so install 1.2.0 by
hand once.

Also by the same author: [PadBridge](https://github.com/spundone/padbridge) turns your phone into a Bluetooth
trackpad, keyboard, remote and gamepad for your computer or TV.

## Build

Android Studio or the command line (JDK 17+, Android SDK with platform 37):

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk (package dev.padkeys.debug)
./gradlew lintDebug
./gradlew testDebugUnitTest  # glide decoder tests (JVM)
```

Then turn it on in Settings › System › Keyboard › On-screen keyboards, or run the app and follow its setup steps.
From adb: `adb shell ime enable dev.padkeys.debug/dev.padkeys.PadKeysService` and
`adb shell ime set dev.padkeys.debug/dev.padkeys.PadKeysService` (`adb shell ime reset` goes back to the default).

## Code

| File | What |
| --- | --- |
| `PadKeysService.kt` | The `InputMethodService`: keys → `InputConnection` (text, Backspace, Enter / editor action, key events, shortcuts), suggestion picking, auto-capitals |
| `KeyboardView.kt` | The keyboard view and layouts, adapted from PadBridge's (no HID; reports to a listener) |
| `KeyPopup.kt` | Long-press popups: accents and punctuation |
| `NumPad.kt` | Number / phone / date pads, and `KeyActions` (delete-key swipe, comma for emoji) |
| `AutoCorrect.kt` | Autocorrect and its undo |
| `Toolbar.kt`, `Panels.kt` | Toolbar tools and drawn icons; the emoji, clipboard and text editing panels |
| `Emoji.kt`, `Clips.kt` | The emoji set and recents; clipboard history and pins |
| `OneHand.kt` | One-handed mode's side buttons, hand-over to the voice keyboard |
| `VoiceTyping.kt` | Voice typing with the speech recognizer, and its panel |
| `SuggestionStrip.kt` | Suggestions, toolbar, paste chip, layout and settings buttons |
| `Predict.kt` | Word list completions, spell checker corrections, opt-in learned words |
| `Glide.kt`, `SwipeDecoder.kt`, `GlideInput.kt` | Glide typing: gesture tracking, trail and preview; SHARK2-style template-matching decoder over the word list; committing, spacing, alternatives and Backspace |
| `Field.kt` | What the focused field allows (suggestions, learning, Enter label, symbols first) and word finding |
| `Prefs.kt`, `Ui.kt`, `Real.kt` | Settings, colors and haptics, the Realistic look |
| `MainActivity.kt`, `ui/` | Setup, test field, settings and licenses (Compose, Material 3 Expressive) |

## License

Apache License 2.0, see [LICENSE](LICENSE).

The English word list (`app/src/main/assets/words_en.txt`) comes from
[wordfreq](https://github.com/rspeer/wordfreq) by Robyn Speer, whose data is licensed
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); the list is shared under the same license.
