// Owner creates a staff/manager login (email + temporary password). No email links needed,
// which keeps it simple for gyms. The staff member can change the password in the app.
import { adminClient, handle, HttpError, json, requireRole, requireUser, requireUuid, userClient } from "../_shared/common.ts";

Deno.serve(handle(async (req) => {
  const db = userClient(req);
  await requireUser(req, db);
  const body = await req.json();
  const gymId = requireUuid(body.gym_id, "gym_id");
  await requireRole(db, gymId, ["owner"]);

  const email = String(body.email ?? "").trim().toLowerCase();
  const password = String(body.password ?? "");
  const name = String(body.name ?? "").trim().slice(0, 60);
  const role = body.role === "manager" ? "manager" : "staff";
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new HttpError("Enter a valid email.", 400);
  if (password.length < 8) throw new HttpError("Password must be at least 8 characters.", 400);

  const { data: writable } = await db.rpc("gym_is_writable", { gid: gymId });
  if (writable !== true) throw new HttpError("Subscription expired. Renew to add staff.", 402);

  const admin = adminClient();
  const created = await admin.auth.admin.createUser({
    email, password, email_confirm: true, user_metadata: { name },
  });
  if (created.error || !created.data.user) {
    throw new HttpError(created.error?.message ?? "Could not create the account.", 400);
  }
  const { error } = await admin.from("gym_users").insert({
    gym_id: gymId, user_id: created.data.user.id, role, display_name: name, email,
  });
  if (error) {
    await admin.auth.admin.deleteUser(created.data.user.id);
    throw new HttpError("Could not add the staff member.", 400);
  }
  return json({ ok: true, user_id: created.data.user.id });
}));
