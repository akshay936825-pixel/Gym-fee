# Gym Hisab — offline-first Android app

Native Android app for a gym owner to manage members, memberships, fee payments, refunds, expenses, reports and local backups.

## Current behaviour

- Kotlin + Jetpack Compose UI; Room/SQLite (`hisab.db`) is the local source of truth.
- Records are saved on the device first and the app remains usable offline.
- No Google sign-in or OAuth flow is used by the Android app.
- WorkManager queues a full snapshot upload after local writes and retries when a network connection returns; a periodic retry is also scheduled.
- Google Sheets is a secondary mirror, not the source of truth. The upload uses stable row IDs and never clears or deletes sheet rows.
- Owner-initiated backup/restore remains available. Restore adds missing records only and schedules a background Sheets upload.

## Google Sheets setup

The Apps Script source and deployment steps are in `backend/`. Replace the current Apps Script code with `backend/Code.gs` and update the existing Web App deployment to a new version before installing this app build. Keep the existing spreadsheet ID and `Members` / `Payments` tabs. The bridge preserves existing rows, updates matching IDs, and creates `Memberships`, `Expenses`, `Plans`, and `Attendance` tabs if needed.

The Android app does not ask the owner to sign in. Apps Script executes as the spreadsheet owner. The app token is embedded in the APK, so it deters casual requests but is not a strong secret. Do not use this design for sensitive high-security data.

**Important:** this is an outbound mirror. It does not import old spreadsheet-only records into an empty phone database. Keep the existing app installed and do not clear app data before updating. Make a copy of the spreadsheet before first deployment.

## Build

Open this folder in Android Studio with Android SDK installed, or push it to GitHub and run the workflow at `.github/workflows/android.yml`.

The project uses Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 2.0.20, compile SDK 34 and Java 17. The repository does not include `gradle-wrapper.jar`; CI installs Gradle 8.9 directly.

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Data safety

- Application ID remains `com.gym.hisab`.
- Database filename remains `hisab.db`; Room schema remains version 1.
- No destructive Room migration is configured.
- Version code is raised to 2 for a future in-place update.
- Never uninstall the previous app or clear its data when updating, or local records may be lost.


## Google Sheets background mirror
The app uses Room as its local source of truth. WorkManager schedules a network-constrained upload after local writes and periodically, with retries when offline. The Apps Script source and deployment instructions are in `backend/`. Replace and redeploy the existing Apps Script before expecting the new client sync to work. The bridge performs ID-based upserts and does not clear or delete spreadsheet history.
