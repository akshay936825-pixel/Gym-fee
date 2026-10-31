# Legacy cloud-sync code (Supabase / outbox / WorkManager)

These are untouched copies of the files that used to power Supabase sign-in, the
outbox sync, hourly/background workers, staff accounts, subscriptions and the
Sheets/AI edge-function calls. They are NOT compiled (no .kt files under src/).

The app is now local-only (Room is the only live database). The files are kept so
nothing is lost; they are also in git history. The `supabase/` backend folder at the
repository root is unchanged.
