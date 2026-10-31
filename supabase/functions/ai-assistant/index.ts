// AI is OPTIONAL. It only explains numbers that the DATABASE already computed (ai_facts RPC),
// fetched with the caller's own JWT so Row Level Security decides what it may see.
import { handle, HttpError, json, requireRole, requireUser, requireUuid, userClient } from "../_shared/common.ts";

/** Add *_rupees next to every *_paise so the model never has to do currency maths. */
function withRupees(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(withRupees);
  if (v && typeof v === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
      out[k] = withRupees(val);
      if (k.endsWith("_paise") && typeof val === "number") {
        out[k.replace(/_paise$/, "_rupees")] = Math.round(val) / 100;
      }
    }
    return out;
  }
  return v;
}

const SYSTEM = `You are the business assistant inside GYM HISAB, an app for Indian gym owners.
Rules you must follow:
- Use ONLY the FACTS JSON provided. Never invent, estimate, extrapolate or guess a number.
- Quote rupee amounts from the *_rupees fields. Do not do new arithmetic on money; if a total is not in the facts, say it is not available.
- If the facts do not contain the answer, say so plainly and suggest which screen in the app shows it.
- Write short, simple English (or Hindi if the owner asks in Hindi). Use the Indian rupee sign and lakh/crore style grouping, e.g. ₹1,25,000.
- Give practical, careful suggestions (e.g. who to remind about fees). Do not give legal or tax advice.`;

Deno.serve(handle(async (req) => {
  const key = Deno.env.get("ANTHROPIC_API_KEY");
  if (!key) throw new HttpError("AI is not enabled for this app yet.", 501);

  const db = userClient(req);
  await requireUser(req, db);
  const body = await req.json();
  const gymId = requireUuid(body.gym_id, "gym_id");
  const question = String(body.question ?? "").trim().slice(0, 500);
  if (!question) throw new HttpError("Ask a question.", 400);
  await requireRole(db, gymId, ["owner", "manager"]);

  const { data: writable } = await db.rpc("gym_is_writable", { gid: gymId });
  if (writable !== true) throw new HttpError("AI needs an active subscription.", 402);

  const today = new Date();
  const from = String(body.from ?? new Date(today.getFullYear(), today.getMonth() - 5, 1).toISOString().slice(0, 10));
  const to = String(body.to ?? today.toISOString().slice(0, 10));

  const { data: facts, error } = await db.rpc("ai_facts", { p_gym: gymId, p_from: from, p_to: to });
  if (error) throw new HttpError("Could not load your numbers.", 400);

  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": key, "anthropic-version": "2023-06-01" },
    body: JSON.stringify({
      model: Deno.env.get("AI_MODEL") ?? "claude-sonnet-5-5",
      max_tokens: 700,
      system: SYSTEM,
      messages: [{
        role: "user",
        content: `FACTS (computed by the database):\n${JSON.stringify(withRupees(facts))}\n\nQUESTION: ${question}`,
      }],
    }),
  });
  if (!res.ok) {
    console.error("AI provider error", res.status);
    throw new HttpError("The AI service is busy. Please try again.", 502);
  }
  const out = await res.json();
  const answer = (out.content ?? []).filter((b: { type: string }) => b.type === "text")
    .map((b: { text: string }) => b.text).join("\n").trim();
  return json({ answer, range: { from, to } });
}));
