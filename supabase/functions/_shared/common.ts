// Shared helpers for edge functions. Secrets come from Deno.env (set with `supabase secrets set`).
import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

export function fail(message: string, status = 400): Response {
  return json({ error: message }, status);
}

/** Client that acts AS THE CALLER (their JWT) so Row Level Security applies. */
export function userClient(req: Request): SupabaseClient {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } },
    auth: { persistSession: false },
  });
}

/** Privileged client. Server-side only; never expose its key. */
export function adminClient(): SupabaseClient {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });
}

export async function requireUser(req: Request, db: SupabaseClient) {
  const { data, error } = await db.auth.getUser();
  if (error || !data.user) throw new HttpError("Please sign in again.", 401);
  return data.user;
}

export async function requireRole(db: SupabaseClient, gymId: string, roles: string[]) {
  const { data, error } = await db.rpc("has_gym_role", { gid: gymId, roles });
  if (error || data !== true) throw new HttpError("You do not have permission for this.", 403);
}

export class HttpError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

export function handle(fn: (req: Request) => Promise<Response>) {
  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
    try {
      return await fn(req);
    } catch (e) {
      if (e instanceof HttpError) return fail(e.message, e.status);
      console.error(e);
      return fail("Something went wrong. Please try again.", 500);
    }
  };
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function requireUuid(v: unknown, name: string): string {
  if (typeof v !== "string" || !UUID.test(v)) throw new HttpError(`Invalid ${name}`, 400);
  return v;
}
