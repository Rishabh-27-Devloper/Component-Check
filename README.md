# Component Check 🔍

**Component Check** is an automated Android monitoring application designed to observe and track changes to target Instagram profile pages in real time and in the background.

Whenever a monitored account experiences metric updates (follower count, following count, post count) or profile revisions (display name, bio, status, avatar), Component Check detects the delta, records the audit history in a local SQLite database, and delivers high-priority heads-up push notifications.

---

## ✨ Features

- **Automated Background Monitoring**: Uses Android's native `WorkManager` (periodic sync every 15 minutes) with battery-conscious background scheduling that persists across app restarts.
- **Granular Change Detection**:
  - 📈 **Follower Count** (with `+` / `-` delta badges)
  - 👥 **Following Count**
  - 📸 **Post Count**
  - 📝 **Multiline Formatted Bio** (preserves line breaks and emojis)
  - 👤 **Display Name**
  - 🖼️ **Profile Picture** (intelligently filtered to prevent false alerts from ephemeral CDN token rotation)
  - ⚠️ **Account Status** (Active vs. Deactivated / Unavailable)
- **High-Fidelity Dashboard UI**:
  - Live summary header: Monitored profile count, daily change counter, and last-synced timestamp.
  - Profile cards with real avatar images, verified badges, formatted multiline bios, and delta pills.
  - Pull-to-refresh and individual profile refresh buttons.
  - Full change history viewer for each profile.
- **In-Engine Scraping Architecture**:
  - Built-in headless WebView engine with an asynchronous JavaScript interface bridge (`window.ScraperBridge`).
  - Seamlessly utilizes active session cookies via Android's `CookieManager` to overcome Cloudflare / anti-bot challenges.
  - Direct JSON state parsing (`script[type="application/json"]`) for 100% accurate, unaltered profile metadata.
- **High-Priority Notifications**: Instant heads-up system alerts with deep links to the affected profile.

---

## 🏗️ Architecture & Tech Stack

- **Language**: Kotlin 1.9+
- **Architecture**: MVVM (Model-View-ViewModel) + Repository Pattern
- **UI**: Material 3 Design, ViewBinding, SwipeRefreshLayout, Coil (image loading & caching)
- **Local Database**: Room Database 2.6 (SQLite) with `@Upsert` and migration safety
- **Background Processing**: AndroidX WorkManager & SystemJobService
- **Networking & Scraping**: Native Android WebView (Chromium engine), OkHttp 4, Jsoup

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug / Meerkat (or newer)
- Android SDK 35 (compileSdk 35, minSdk 26)
- Java 17 / JDK 17

### Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/Rishabh-27-Devloper/Component-Check.git
   cd Component-Check
   ```

2. Open the project in Android Studio or build via CLI:
   ```bash
   # Windows
   .\gradlew.bat assembleDebug

   # macOS / Linux
   ./gradlew assembleDebug
   ```

3. Install on a connected device or emulator:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 🔒 Privacy & Permissions

- `INTERNET`: Required to fetch public Instagram profile metrics.
- `ACCESS_NETWORK_STATE`: Monitors connection availability before triggering background sync.
- `POST_NOTIFICATIONS`: Android 13+ permission for heads-up change alert notifications.

---

## 📄 License

This project is licensed under the Apache License 2.0.
