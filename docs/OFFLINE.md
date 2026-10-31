# Offline-first edition

## What is stored (all on the phone, Room DB `hisab.db`, schema version 1, unchanged)
plans, members, memberships (a member's plan period + fee), payments (payments AND refunds, money as integer
paise), expenses, attendance rows from older installs (kept, not shown), and the old `outbox` table (kept, no
longer written). Settings (gym name, theme, last backup time) are in SharedPreferences.

## Money rules
* Payments are never edited or deleted. A mistake is fixed with a refund entry plus a new payment.
* Each save has a request id; saving the same request twice returns the first result and writes nothing.
* The balance check (payment <= due, refund <= paid) runs inside the same database transaction as the insert.
* WAL + `synchronous=FULL`: a committed payment survives a power loss right after the "saved" message.

## Backup / restore (More -> Backup & restore, owner-initiated only)
* Backup writes one JSON file (with SHA-256 checksum) to a location you pick, then reads it back to verify.
* Restore validates the checksum, writes a safety copy (`files/backups/before-restore-*.json`, newest 5 kept),
  then ADDS missing rows in one transaction. It never overwrites or deletes existing rows.
* "Export for Google Sheets" produces CSV files via the share sheet (Drive / Sheets). It reads Room only.
* Local-only data is lost if the phone is lost or the app data is cleared/uninstalled: back up regularly.

## Measuring startup
`scripts/measure_startup.sh com.gym.hisab 10` on the old build, then on the new build, same phone and data.
