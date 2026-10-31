// OPTIONAL Google Sheets export. Supabase stays the source of truth; the sheet is a mirror.
// The owner shares their sheet (Editor) with the service-account email returned by action "info".
import { handle, HttpError, json, requireRole, requireUser, requireUuid, userClient } from "../_shared/common.ts";

type ServiceAccount = { client_email: string; private_key: string };

function loadAccount(): ServiceAccount {
  const raw = Deno.env.get("GOOGLE_SERVICE_ACCOUNT_JSON");
  if (!raw) throw new HttpError("Google Sheets is not enabled on this server yet.", 501);
  return JSON.parse(raw);
}

const b64url = (data: ArrayBuffer | string) => {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let s = ""; bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/spreadsheets",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3000,
  }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claim}`));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${head}.${claim}.${b64url(sig)}` }),
  });
  if (!res.ok) throw new HttpError("Google sign-in failed on the server.", 502);
  return (await res.json()).access_token;
}

/** Accepts a full Google Sheets link or a bare spreadsheet id. */
export function parseSpreadsheetId(input: string): string | null {
  const m = input.match(/\/spreadsheets\/d\/([A-Za-z0-9_-]{20,})/);
  if (m) return m[1];
  return /^[A-Za-z0-9_-]{20,}$/.test(input.trim()) ? input.trim() : null;
}

const rupees = (p: number) => Math.round(p) / 100;

Deno.serve(handle(async (req) => {
  const sa = loadAccount();
  const db = userClient(req);
  await requireUser(req, db);
  const body = await req.json();
  const gymId = requireUuid(body.gym_id, "gym_id");
  await requireRole(db, gymId, ["owner"]);

  if (body.action === "info") return json({ service_account_email: sa.client_email });

  if (body.action === "connect") {
    const id = parseSpreadsheetId(String(body.link ?? ""));
    if (!id) throw new HttpError("That does not look like a Google Sheets link.", 400);
    const { error } = await db.from("sheets_config").upsert({ gym_id: gymId, spreadsheet_id: id, enabled: true, last_error: null });
    if (error) throw new HttpError("Could not save the link.", 400);
  }

  const { data: cfg } = await db.from("sheets_config").select("spreadsheet_id").eq("gym_id", gymId).maybeSingle();
  if (!cfg) throw new HttpError("Connect a Google Sheet first.", 400);

  const [members, payments, expenses] = await Promise.all([
    db.from("members").select("name,phone,joined_on,archived").eq("gym_id", gymId).order("name"),
    db.from("payments").select("receipt_no,paid_on,kind,amount_paise,mode,note,members(name)").eq("gym_id", gymId).order("paid_on"),
    db.from("expenses").select("spent_on,category,amount_paise,note").eq("gym_id", gymId).eq("deleted", false).order("spent_on"),
  ]);
  if (members.error || payments.error || expenses.error) throw new HttpError("Could not read your records.", 400);

  const tabs: Record<string, (string | number)[][]> = {
    Members: [["Name", "Phone", "Joined", "Archived"],
      ...members.data!.map((m) => [m.name, m.phone ?? "", m.joined_on, m.archived ? "yes" : "no"])],
    Payments: [["Receipt", "Date", "Type", "Member", "Amount (INR)", "Mode", "Note"],
      ...payments.data!.map((p: any) => [p.receipt_no, p.paid_on, p.kind, p.members?.name ?? "", rupees(p.amount_paise), p.mode, p.note ?? ""])],
    Expenses: [["Date", "Category", "Amount (INR)", "Note"],
      ...expenses.data!.map((e) => [e.spent_on, e.category, rupees(e.amount_paise), e.note ?? ""])],
  };

  const token = await accessToken(sa);
  const base = `https://sheets.googleapis.com/v4/spreadsheets/${cfg.spreadsheet_id}`;
  const auth = { authorization: `Bearer ${token}`, "content-type": "application/json" };

  const meta = await fetch(`${base}?fields=sheets.properties.title`, { headers: auth });
  if (!meta.ok) {
    await db.from("sheets_config").update({ last_error: "Sheet not shared with the service account" }).eq("gym_id", gymId);
    throw new HttpError("Cannot open the sheet. Share it with the email shown in the app (Editor access).", 403);
  }
  const existing = new Set((await meta.json()).sheets.map((s: any) => s.properties.title));
  const add = Object.keys(tabs).filter((t) => !existing.has(t)).map((title) => ({ addSheet: { properties: { title } } }));
  if (add.length) await fetch(`${base}:batchUpdate`, { method: "POST", headers: auth, body: JSON.stringify({ requests: add }) });

  for (const [title, values] of Object.entries(tabs)) {
    await fetch(`${base}/values/${encodeURIComponent(title)}:clear`, { method: "POST", headers: auth, body: "{}" });
    const put = await fetch(`${base}/values/${encodeURIComponent(title)}!A1?valueInputOption=RAW`, {
      method: "PUT", headers: auth, body: JSON.stringify({ values }),
    });
    if (!put.ok) throw new HttpError("Google rejected the update.", 502);
  }
  await db.from("sheets_config").update({ last_synced_at: new Date().toISOString(), last_error: null }).eq("gym_id", gymId);
  return json({ ok: true, rows: { members: members.data!.length, payments: payments.data!.length, expenses: expenses.data!.length } });
}));
