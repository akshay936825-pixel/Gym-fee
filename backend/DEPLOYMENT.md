# Gym Hisab Google Sheets sync deployment

The Android app now queues a full Room snapshot through WorkManager whenever local records change and retries when connectivity returns. Room remains the source of truth. The Apps Script bridge uses stable row IDs and never clears or deletes rows.

## Deploy the bridge
1. Open the existing Apps Script project associated with the supplied `/exec` URL.
2. Replace the contents of `Code.gs` with `backend/Code.gs` from this project.
3. Save the project.
4. Open **Deploy → Manage deployments**. Edit the existing Web App deployment and select **New version**. Keep the deployment as **Execute as: Me**. The Android client does not use Google Sign-In.
5. The Web App must be reachable by the Android client without an interactive Google login. The app sends a shared app token; this is a deterrent against casual requests, not strong authentication because a token embedded in an APK can be extracted. Do not treat this endpoint as a high-security database.
6. Keep the existing spreadsheet ID in `SHEET_ID`. Do not rename or delete the existing `Members` and `Payments` tabs.

## Existing sheet safety
- Existing Members columns A:E are updated by member ID; columns F:I are left untouched. Column J is used for Archived.
- Existing Payments columns A:H are updated by payment ID. Refunds are represented as negative amounts.
- Memberships, Expenses, Plans and Attendance tabs are created if missing. Existing rows are never deleted.
- A row absent from the local snapshot is deliberately not deleted from Sheets.

## Important first-sync precaution
Before enabling the new deployment, make a copy of the spreadsheet. If the local app database is empty but the spreadsheet contains important historical data, this bridge will preserve those sheet rows, but it does not import them into Room. Do not uninstall the old app or clear its data. The new sync is an outbound mirror only.
