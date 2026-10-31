// Owner taps "Subscribe": we create a Razorpay Payment Link for the chosen plan.
// Access is granted ONLY by razorpay-webhook after Razorpay confirms payment.
import { handle, HttpError, json, requireRole, requireUser, requireUuid, userClient } from "../_shared/common.ts";

const PRICES: Record<string, number> = {
  monthly: Number(Deno.env.get("PRICE_MONTHLY_PAISE") ?? 49900),
  yearly: Number(Deno.env.get("PRICE_YEARLY_PAISE") ?? 499900),
};

Deno.serve(handle(async (req) => {
  const id = Deno.env.get("RAZORPAY_KEY_ID");
  const secret = Deno.env.get("RAZORPAY_KEY_SECRET");
  if (!id || !secret) throw new HttpError("Payments are not configured yet.", 501);

  const db = userClient(req);
  const user = await requireUser(req, db);
  const body = await req.json();
  const gymId = requireUuid(body.gym_id, "gym_id");
  const plan = body.plan === "yearly" ? "yearly" : "monthly";
  await requireRole(db, gymId, ["owner"]);

  const res = await fetch("https://api.razorpay.com/v1/payment_links", {
    method: "POST",
    headers: { "content-type": "application/json", authorization: "Basic " + btoa(`${id}:${secret}`) },
    body: JSON.stringify({
      amount: PRICES[plan],
      currency: "INR",
      description: `GYM HISAB ${plan} subscription`,
      customer: { email: user.email },
      notes: { gym_id: gymId, plan },
      reminder_enable: false,
    }),
  });
  if (!res.ok) throw new HttpError("Could not start the payment. Please try again.", 502);
  const link = await res.json();
  return json({ url: link.short_url });
}));
