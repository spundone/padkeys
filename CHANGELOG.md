# Changelog

All notable changes to PadKeys are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
