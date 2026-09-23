<p align="center">
  <img src="docs/icon.png" width="140" alt="Tanlish Translate icon: the Tamil letter த on a violet tile">
</p>

<h1 align="center">Tanlish Translate</h1>

<p align="center">Type Tanglish with your normal keyboard, then tap one small floating button: the text in
that field is replaced with English, right where you typed it.</p>

## What it is

Tanglish is Tamil written in Latin letters — *vanakkam nanba epdi irukka*. You type it with whatever
keyboard you already use, in whatever app you are already in. Then one tap on the floating **த**
button and the sentence in that field becomes *Hello friend, how are you?*

- **Any app** — WhatsApp, Messages, Gmail, Chrome. Anything with a text field.
- **One tap for the whole sentence**, not a keyboard to learn and not a word-by-word popup.
- **Each sentence is only ever translated once** — the result is saved on the device, so a phrase you
  use again costs nothing and works with no connection at all.
- **Adjustable opacity** — a slider in the app sets how see-through the circle is over your apps.
- **Undo** — a six-second chip puts your Tanglish back.

The button only appears while the keyboard is up. Drag it anywhere; long press hides it.

The translation itself is done by AI at `easytamiltyping.com`, so a phrase that has never been
translated before needs an internet connection. There is no offline dictionary: when a request cannot
be made, the button says so and leaves your text exactly as you typed it.

## Install

Download `app-release.apk` from the [latest release](https://github.com/Kirisos-Guna/tanlish-translate/releases/latest)
and open it on your phone, allowing installation from this source when Android asks.

Then two one-time steps, both on the app's own setup screen:

1. **Display over other apps** — lets the button float above other apps.
2. **Switch on the accessibility service** — lets the button read the field you are in *at the moment
   you tap*, and write the English back.

> **Sideloaders on Android 13+:** Android greys out the accessibility toggle for apps that were not
> installed from a store. Open **App info → ⋮ (top right) → Allow restricted settings**, then come
> back and the toggle works.

If you already have an older build of this app installed from a different signing key (an Android
Studio run, for instance), Android will refuse to install over it — uninstall that first. Doing so
clears its cached translations, quota counter and settings.

## What it reads and what it sends

| | |
|---|---|
| Reads | Only the focused text field, only when you tap the button. Nothing is read while you type, and password fields are skipped entirely. |
| Sends | That field's text goes to `easytamiltyping.com` for translation, once per tap, only if you have allowed AI translation. Results are cached on the device, so repeating a phrase costs nothing. |
| Free tier | That public endpoint allows roughly **15 requests an hour**. When the budget is spent the button tells you how long until it resets and leaves your text untouched. |
| Fails safely | Apps that refuse programmatic edits get the translation on the clipboard instead of having their field rewritten. |

## Building

Needs JDK 21 and Android SDK 37 (`compileSdk 37`, `minSdk 24`).

```bash
./gradlew :app:testDebugUnitTest   # unit tests (no network, no device)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # signed only if keystore.properties is present
```

### Signing

Release signing reads `keystore.properties` (gitignored) pointing at `keystore/tanlish-release.jks`
(also gitignored). Without those files the release build still assembles, unsigned, so a clone can be
built by anyone. The real key is deliberately not in this repository: a clone can build its own APK,
but it cannot sign as this app. Back that keystore up — losing it means future versions can never be
installed as an update, only as a fresh install.

### Icons

One mark, **த**, drawn as vectors: `drawable/ic_launcher_foreground.xml` for the adaptive icon,
`ic_launcher_monochrome.xml` for Android 13+ themed icons, `ic_app_mark.xml` for the badge on the app's
own screen and `ic_bubble_mark.xml` for the floating button. The WebP files under `mipmap-*` are the
legacy pre-API-26 raster copies of the same tile, and are the one set that has to be redrawn by hand if
the mark ever changes.

The shape is traced from Microsoft's Nirmala UI. That is fine for a personal build; a store release
should redraw it as original artwork.

## Layout

| Path | What lives there |
|---|---|
| `overlay/` | The floating button: its window, drag and snap, placement maths, boot restore |
| `accessibility/` | Reading the focused field, rewriting it, and noticing when the keyboard is up |
| `translate/` | The button's decision core: what to translate and when to fall back |
| `engine/`, `net/`, `data/` | Where a sentence gets translated, the endpoint client, the translation cache and quota bookkeeping |

## Notes

Translation comes from easytamiltyping.com's public endpoint, which may change at any time;
`net/EasyTamilTypingApi.kt` is the single place that would need updating if it does. This is an
unaffiliated personal project. No licence is granted yet — all rights reserved.
