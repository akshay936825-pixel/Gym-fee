# Architecture

```
Android (Kotlin, Compose)                     Supabase
┌───────────────────────────┐   HTTPS    ┌──────────────────────────────┐
│ UI (Compose screens)      │            │ Auth (email + password)      │
│   ↓ reads Flow            │            │ PostgREST + RLS (all tables) │
│ Room  ← single source for │  push      │ SQL: reports, ai_facts       │
│  the UI, works offline    │ ───────►   │ Edge functions:              │
│   ↑                       │ outbox     │   invite-staff               │
│ Repository + SyncEngine   │ ◄───────   │   ai-assistant (optional)    │
│   outbox → upsert         │  pull by   │   razorpay-create-link       │
│   pull by updated_at      │ updated_at │   razorpay-webhook           │
└───────────────────────────┘            │   sheets-sync (optional)     │
                                         └──────────────────────────────┘
```

* **Money** is `Long` paise everywhere. `Ledger`, `Dashboard`, `Reports` are pure Kotlin with the same formulas as the SQL in `20260101000003_reports.sql`; both are tested against the same scenario.
* **Offline**: every action writes Room + an `outbox` row in one transaction. `SyncWorker` (WorkManager, network constraint) pushes the outbox in order, then pulls changes with `updated_at >= cursor - 10s`. Client-generated UUIDs make every push idempotent. A pulled row never overwrites a local row that still has an unsent change.
* **Conflicts**: last write wins for editable rows (members, plans, expenses). Payments are append-only so they cannot conflict. Attendance ids are deterministic (member + day).
* **Failures that will not fix themselves** (expired subscription, role not allowed) are flagged in the outbox (`failed`) and shown in a banner with a Retry button instead of retrying forever.
* **Roles**: owner (everything), manager (everything except team, Sheets, subscription), staff (members, collect fees, check-in).
