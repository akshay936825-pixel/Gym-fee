# Security model

| Threat | Defence |
|---|---|
| Gym A reads or edits Gym B's data | PostgreSQL Row Level Security on **every** table (`is_gym_member(gym_id)`), composite foreign keys `(id, gym_id)` so a row can never reference another gym's parent, `gym_id` is immutable (trigger). Verified by `supabase/tests/rls_and_ledger_test.sql`. |
| Staff does things only owners may do | Roles `owner / manager / staff` enforced in RLS policies (refunds, expenses, plans, team). The app only *hides* buttons; the server *refuses* the request. |
| Fake "I paid" on the phone | Subscription state lives in `subscriptions`, writable only by the service role. `gym_is_writable()` is checked inside every write policy. The Razorpay webhook verifies an HMAC signature before activating a plan. |
| Edited or deleted payments | `payments` is an append-only ledger: no UPDATE/DELETE grant, plus a trigger that rejects both. Corrections are recorded as `refund` rows. |
| Forged `created_by` | Set by a database trigger from the JWT, never from the client. |
| Leaked keys | Only the public **anon** key is in the app. The service-role key, Razorpay secrets, Google service-account JSON and the AI key exist only as Supabase function secrets. `.gitignore` blocks `.env`, keystores and credential JSON. |
| AI inventing numbers | AI never queries tables. It receives one JSON document computed by the `ai_facts()` SQL function (called with the *user's own JWT*, so RLS applies) and is instructed to use only those figures. It is optional: no key = feature disabled, everything else works. |
| Stolen phone | Tokens are stored in Android Keystore-backed `EncryptedSharedPreferences`; backups are disabled; cleartext HTTP is disabled. |
| Spreadsheet formula injection | CSV export prefixes cells that start with `= + - @` (see `Csv` and its unit test). |

## Known limits (be honest with your customers)
* Offline data lives in an unencrypted Room database protected by Android's app sandbox. A rooted phone can read it. Use a phone screen lock.
* Sign-in is email + password. Phone-OTP sign-in needs an SMS provider (see README "Next steps").
* Receipt numbers are generated on the phone (`HB-yyMMdd-XXXXX`) so they work offline; they are unique per gym (enforced) but not consecutive.
