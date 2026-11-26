# Changelog

All notable changes to PadKeys are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.4.0] – 2026-11-26

### Added
- A Googlebook layout: a top row of Esc, Back, Home, hide keyboard, media, mute, volume, voice typing, emoji and
  clipboard keys (F1–F12 with Fn), the launcher (Action) key where Caps Lock is, an assistant key between Fn and
  Alt, and Action + Space / H / ← for the assistant, home and back.
- Floating keyboard: a smaller keyboard window that can be dragged anywhere and resized, over the app instead of
  pushing it up (taps around it reach the app). From the toolbar or settings; docks again from its bar. Remembers
  its place and width for upright and sideways.

### Changed
- The layout picker in settings shows two rows of three.

## [1.3.0] – 2026-11-26

### Added
- Voice typing inside PadKeys: the mic opens a voice panel that listens with the phone's speech recognizer (Google's
  on most phones). Words appear in the field as you speak and are committed when recognized, with a space before
  them and a capital at the start of a sentence. The panel has ABC, Space, Backspace and Enter, and a button for
  the phone's voice keyboard. Asks for the microphone the first time; the audio goes to the speech service, not
  to PadKeys. Phones without a recognizer still switch to the voice keyboard as before.
- Modifiers as real keys: Ctrl, Alt, Win (and Shift in terminals) go down when pressed or latched and up when
  released or used, like a hardware keyboard, so holding (or locking) Alt across several Tabs is Alt+Tab, and
  Ctrl+Alt+Del or Win+E reach remote desktops, terminals and VMs. Automatic (apps that take raw keys) by default;
  can be set to Always or Off under PC keys.
- A Win key in the bottom row of Laptop, 65%, Full and Split.

### Changed
- Split layout redone: two even blocks pinned to the screen edges with letters in straight columns, Esc and ` on
  the left, arrows and Del in the right half's corner (no more PgUp / PgDn / End column), and a gap that grows with
  the screen so keys stay thumb-sized on tablets. The TrackPoint sits in the gap; with the function row on, F1–F6
  go over the left half and F7–F12 over the right.

## [1.2.0] – 2026-11-26

### Added
- Themes, as in PadBridge: light or dark (or follow the system); colors from PadKeys' palette, the wallpaper, a
  custom palette grown from one picked color, or two-tone (background and foreground, presets, contrast warning);
  pure black in dark mode. The settings screen uses the same theme as the keys.
- TrackPoint: a red nub at the corner of G, H and B on Phone, Laptop, 65% and Full. Push it to move the text cursor
  (speed grows with the push, up / down by line); with Shift on it selects. Speed setting; can be turned off.
- Function row setting: Esc and F1–F12 in a slim row above the keys on every layout (Full already has one).
- In-app updates: Settings › About › "Check for updates", and a daily check when the settings screen opens (can be
  turned off). Downloads the release APK from GitHub, verifies its SHA-256 and signing key, and opens Android's
  installer. PadKeys now has the INTERNET permission for this only; the keyboard itself never goes online.
- With a hardware keyboard attached (and Android's "show on-screen keyboard" off), PadKeys stays on screen as a bar
  of Esc, F1–F12, Del and, on wide screens, Home, End, PgUp, PgDn. On by default; can be turned off.

## [1.1.0] – 2026-11-24

### Added
- Glide typing: slide across the letters to type a word, on all layouts. Words are decoded on the phone by matching
  the path against the bundled word list (and learned words, if learning is on). The word gets a space before it,
  punctuation typed after it attaches to it, Shift / Caps Lock capitalize it, the next guesses appear in the
  suggestion strip to swap in, and Backspace right after deletes the whole word. A fading trail in the theme color
  and a floating preview of the best guess follow the finger. Settings: "Glide typing" and "Show gesture trail"
  (both on); never in password fields.
- Hold a letter for its accented forms and slide to one; hold the period for punctuation; hold the comma (Phone
  layout) for emoji.
- Autocorrect when a space or punctuation ends a misspelled word, undone by Backspace right after; off in
  password, email, web address, name and no-suggestion fields. Setting to turn it off.
- Double-space types ". " (setting).
- Toolbar in the suggestion strip: emoji panel with categories and recently used emoji; clipboard history with
  pinning and a paste chip for fresh copies; text editing panel; one-handed mode; voice typing hand-over.
- Number pad and phone pad in number, phone and date fields (setting).
- One-handed mode (left or right) with switch-side and full-width buttons.
- Swipe left from Backspace to select words and delete them (setting).
- Phone and URL fields in the settings screen's test field.

### Changed
- Holding a letter that has accents opens the accent popup instead of typing the capital (flick up still does).

## [1.0.0] – 2026-11-20

### Added
- PadBridge's on-screen keyboard as an Android system keyboard.
- Layouts: Phone (number row, `?123` symbols page, Esc / Tab / Ctrl / Alt / arrows row), Laptop, 65%, Full and
  Split, one for portrait and one for landscape.
- Flick up (or hold) for shifted characters, drag the space bar to move the cursor (select with Shift), one-shot /
  locked / held modifiers, Ctrl shortcuts, Fn + number row for F-keys on the laptop layouts.
- Enter runs the field's action (Go, Search, Send, Next, Done) with a matching icon.
- Word suggestions: completions from a bundled 40,000-word list (wordfreq, CC BY-SA 4.0) and corrections from the
  phone's spell checker; optional on-device learning, off by default, never in incognito fields.
- Material 3 Expressive look, wallpaper colors, light and dark, optional Realistic look, Auto / Rich / Basic
  haptics, key preview bubbles.
- Setup screen with a test field and settings.
