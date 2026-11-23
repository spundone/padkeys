# Changelog

All notable changes to PadKeys are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Glide typing: slide across the letters to type a word, on all layouts. Words are decoded on the phone by matching
  the path against the bundled word list (and learned words, if learning is on). The word gets a space before it,
  punctuation typed after it attaches to it, Shift / Caps Lock capitalize it, the next guesses appear in the
  suggestion strip to swap in, and Backspace right after deletes the whole word. A fading trail in the theme color
  and a floating preview of the best guess follow the finger. Settings: "Glide typing" and "Show gesture trail"
  (both on); never in password fields.

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
