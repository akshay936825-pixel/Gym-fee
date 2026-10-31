// Razorpay -> server. Verifies the HMAC signature, then extends the subscription.
// This is the ONLY path that can mark a gym as paid. The phone never decides.
import { adminClient, fail, json } from "../_shared/common.ts";

async function hmacHex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

Deno.serve(async (req) => {
  const secret = Deno.env.get("RAZORPAY_WEBHOOK_SECRET");
  if (!secret) return fail("not configured", 501);

  const raw = await req.text();
  const given = req.headers.get("x-razorpay-signature") ?? "";
  if (!safeEqual(await hmacHex(secret, raw), given)) return fail("bad signature", 401);

  const event = JSON.parse(raw);
  if (event.event !== "payment_link.paid") return json({ ok: true, ignored: true });

  const link = event.payload?.payment_link?.entity;
  const gymId: string | undefined = link?.notes?.gym_id;
  const plan: string = link?.notes?.plan === "yearly" ? "yearly" : "monthly";
  const ref: string | undefined = link?.id;
  if (!gymId || !ref) return fail("missing data", 400);

  const admin = adminClient();
  const { data: sub } = await admin.from("subscriptions")
    .select("current_period_end, status, provider_ref").eq("gym_id", gymId).maybeSingle();
  if (!sub) return fail("unknown gym", 404);
  if (sub.provider_ref === ref) return json({ ok: true, duplicate: true }); // idempotent

  const base = sub.status === "active" && new Date(sub.current_period_end) > new Date()
    ? new Date(sub.current_period_end) : new Date();
  const until = new Date(base);
  if (plan === "yearly") until.setFullYear(until.getFullYear() + 1);
  else until.setMonth(until.getMonth() + 1);

  const { error } = await admin.rpc("activate_subscription", {
    p_gym: gymId, p_plan: plan, p_until: until.toISOString(), p_provider: "razorpay", p_ref: ref,
  });
  if (error) { console.error(error); return fail("could not activate", 500); }
  return json({ ok: true });
});
