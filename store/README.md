# Store listings

Everything for MyTVPlayer's Google Play and Amazon Appstore (Fire TV) listings: graphics, screenshots and the text to
paste in.

## Graphics

| File | Play Console field | Size |
|---|---|---|
| `graphics/icon-512.png` | App icon | 512 × 512, 32-bit PNG |
| `graphics/feature-graphic-1024x500.png` | Feature graphic | 1024 × 500, 24-bit PNG |
| `graphics/tv-banner-1280x720.png` | Android TV banner | 1280 × 720, 24-bit PNG |
| `screenshots/*.png` | Android TV screenshots (and phone screenshots, if asked) | 1920 × 1080, 24-bit PNG |

For the **Amazon Appstore** (Fire TV):

| File | Amazon Developer Console field | Size |
|---|---|---|
| `graphics/tv-banner-1280x720.png` | Fire TV app icon | 1280 × 720 |
| `graphics/fire-tv-background-1920x1080.png` | Background image (shown behind the app's details, so no text) | 1920 × 1080 |
| `screenshots/*.png` | Fire TV screenshots (3 to 10), and phone/tablet ones if asked | 1920 × 1080 |
| `graphics/amazon-icon-114.png`, `graphics/amazon-icon-512.png` | Small and large icons, if the console asks for them | 114 × 114, 512 × 512 |

Every file is a 24-bit PNG (no transparency) except Play's `icon-512.png`, which is 32-bit as Play asks.

They're drawn by the app itself (`app/src/test/.../ui/StoreAssetsTest.kt`), with invented channels and programmes, so
they never show a real broadcaster or programme. The channel logos are original, drawn by `demo-logos.py`. The movies
and series are the Blender Foundation's open movies, with their posters and stills, which are licensed CC BY. If a store asks, or the
listing has room, credit them: "Movie and series art © Blender Foundation and the films' artists (Big Buck Bunny,
Elephants Dream, Sintel, Tears of Steel, Cosmos Laundromat, Charge, Caminandes), CC BY 3.0/4.0, cropped. Credits: https://github.com/biskyt/MyTVPlayer/blob/main/app/src/test/resources/demo-art/CREDITS.md". To make them again after the app's look changes:

```
./gradlew :app:recordRoborazziDebug --tests '*StoreAssetsTest*' -PstoreAssets
```

## Privacy policy

`docs/privacy.html`, published with GitHub Pages at **https://biskyt.github.io/MyTVPlayer/privacy.html**.

## Listing text

Use the same text on Google Play and the Amazon Appstore. The text in each block below is exactly what to paste.

**App name** (Play: 30 characters max · Amazon: title): `MyTVPlayer`

**Short description** (Play: 80 characters max · Amazon: short description):

```text
Live TV, full TV guide, catch-up, movies and series. Lightweight, free, no ads.
```

**Full description** (Play: 4000 characters max · Amazon: long description):

```text
MyTVPlayer is a light, easy TV player for Android TV and Fire TV. Use it with free, legal TV channels, or with the TV provider that you choose.

Every feature is free. There are no ads, no tracking and no accounts.

MAIN FEATURES
• Lightweight: a small download that stays fast on low-cost TV sticks, even with thousands of channels.
• Free: every feature, with no ads, no tracking and no data collection.
• Easy to use: made for the TV remote. The Home screen shows your favourite channels and what you are part-way through.
• Full TV guide: if your provider's guide is not complete, add more guide sources. MyTVPlayer matches them to your channels automatically.
• Free channels or your own provider: start with free channels from their broadcasters and openly licensed movies, or add your provider's Xtream Codes login or M3U playlist.
• Live TV, movies, series and catch-up TV in one app.

OTHER FEATURES
• Start over: watch the programme that is on now from its beginning, on channels that have catch-up.
• Movies and episodes continue from where you stopped, and the next episode starts automatically.
• Choose the countries and languages that you want, so that large playlists stay quick.
• One search for channels, TV programmes, movies and series.
• Type a channel number on the remote, or press Guide to open the TV guide from any screen.
• Dolby, DTS and Atmos sound goes directly to your soundbar or TV.
• Back up your set-up to one file, and restore it on another TV.
• Also works by touch on phones and tablets.
• In English, Spanish, Portuguese (Brazil) and French.

MyTVPlayer does not include any paid channels. To watch your provider's channels, you need a subscription with that provider. Only use services that you have the right to watch.
```

**Amazon product feature bullets** (one bullet on each line, a maximum of 10):

```text
Lightweight and fast, even on low-cost TV sticks
Every feature free, with no ads, no tracking and no accounts
Easy to use with the TV remote
Full TV guide, with extra guide sources if your provider's guide is not complete
Free legal channels, or your own provider's Xtream Codes login or M3U playlist
Live TV, movies, series and catch-up TV in one app
```

**Amazon keywords:** `IPTV player, M3U, Xtream Codes, TV guide, EPG, XMLTV, catch-up TV, live TV, free TV, Android TV`

**Category:** Play: Video Players & Editors. Amazon: the nearest video or TV category that the console offers (for example, Movies & TV).

**Art credit** (add it at the end of the full description if the store has room): see [Graphics](#graphics).

## Play Console answers

- **Data safety:** *No data collected* and *No data shared*.
  - Logins and history stay on the device.
  - The app talks directly to the services the user adds.
  - Nothing reaches the developer.
  - Play doesn't count data handled only on the device, or sent directly to services the user chooses, as
    "collected".
- **Ads:** No ads.
- **Content rating:** answer for a media player with no built-in content.
  - It shows whatever the user's own service provides.
  - It has no user-to-user features.
- **Target audience:** 18+ (or 13+), not directed at children.
- **App access:** reviewers need a way to sign in. Tell them to choose **Free TV, films and series** on the first
  screen, which needs no login.
- **Android TV:** the app declares `LEANBACK_LAUNCHER` and a TV banner, and doesn't require a touchscreen. Opt in to
  Android TV under *Advanced settings › Form factors*.

## Amazon Appstore answers

- **App file:** the APK from the latest GitHub release (https://github.com/biskyt/MyTVPlayer/releases/latest), for example `MyTVPlayer-1.8.16.apk`.
  - Amazon also accepts the `.aab`, but the `.aab` is only a run artifact, and only the owner can download it.
  - Amazon removes the developer's signature and signs the app with its own key.
- **Apply Amazon DRM:** No. The app is free and open source.
- **Supported devices:** Fire TV devices only. Untick the Fire tablets. Fire OS 7 and 8 devices can run the app (Android 9 and later). Vega OS devices cannot run Android apps.
- **Pricing and availability:** free, in all countries that the console offers.
- **In-app items and ads:** none.
- **Content rating:** "None" for each subject.
  - The app is a media player with no content of its own.
  - It shows only what the user's own service provides.
  - It has no user-to-user features, no purchases and no location sharing.
- **Privacy labels:** no data collected and no data shared. Logins and history stay on the device.
- **Privacy policy URL:** https://biskyt.github.io/MyTVPlayer/privacy.html
- **Testing instructions for the reviewers:** "No login is needed. On the first screen, choose "Free TV, films and series". MyTVPlayer then loads free channels from their broadcasters, and openly licensed films and series."
