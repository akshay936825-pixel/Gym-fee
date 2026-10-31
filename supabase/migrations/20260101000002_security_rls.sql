-- Row Level Security: every gym can only ever touch its own rows.
-- Subscription status is checked HERE, on the server, for every write.

create function public.is_gym_member(gid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.gym_users gu
    where gu.gym_id = gid and gu.user_id = auth.uid() and gu.active
  );
$$;

create function public.gym_role(gid uuid) returns text
language sql stable security definer set search_path = public as $$
  select gu.role from public.gym_users gu
  where gu.gym_id = gid and gu.user_id = auth.uid() and gu.active;
$$;

create function public.has_gym_role(gid uuid, roles text[]) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce(public.gym_role(gid) = any(roles), false);
$$;

-- Writes are allowed only while the gym has a valid trial or paid period.
create function public.gym_is_writable(gid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.subscriptions s
    where s.gym_id = gid
      and s.status in ('trialing','active')
      and s.current_period_end > now()
  );
$$;

revoke execute on function public.is_gym_member(uuid), public.gym_role(uuid),
  public.has_gym_role(uuid, text[]), public.gym_is_writable(uuid) from public, anon;
grant  execute on function public.is_gym_member(uuid), public.gym_role(uuid),
  public.has_gym_role(uuid, text[]), public.gym_is_writable(uuid) to authenticated;

-- ---------- table privileges (defence in depth; RLS still applies on top) ----------
revoke all on all tables in schema public from anon, authenticated;

grant select on public.gyms, public.gym_users, public.subscriptions, public.plans, public.members,
               public.memberships, public.payments, public.expenses, public.attendance,
               public.sheets_config to authenticated;

grant insert on public.plans, public.members, public.memberships, public.payments,
                public.expenses, public.attendance, public.sheets_config to authenticated;

grant update on public.plans, public.members, public.memberships, public.expenses,
                public.attendance, public.sheets_config to authenticated;

grant update (name, phone, city) on public.gyms to authenticated;
grant update (role, active, display_name) on public.gym_users to authenticated;
-- No DELETE is granted anywhere: data is archived/soft-deleted, payments are append-only.

alter table public.gyms          enable row level security;
alter table public.gym_users     enable row level security;
alter table public.subscriptions enable row level security;
alter table public.plans         enable row level security;
alter table public.members       enable row level security;
alter table public.memberships   enable row level security;
alter table public.payments      enable row level security;
alter table public.expenses      enable row level security;
alter table public.attendance    enable row level security;
alter table public.sheets_config enable row level security;

-- gyms
create policy gyms_select on public.gyms for select to authenticated
  using (public.is_gym_member(id));
create policy gyms_update on public.gyms for update to authenticated
  using (public.has_gym_role(id, array['owner'])) with check (public.has_gym_role(id, array['owner']));

-- gym_users (rows are created by the backend only: create_gym() and the invite-staff function)
create policy gym_users_select on public.gym_users for select to authenticated
  using (public.is_gym_member(gym_id));
create policy gym_users_update on public.gym_users for update to authenticated
  using (public.has_gym_role(gym_id, array['owner']) and user_id <> auth.uid())
  with check (public.has_gym_role(gym_id, array['owner']) and user_id <> auth.uid()
              and role in ('manager','staff'));

-- subscriptions: read-only for clients
create policy subscriptions_select on public.subscriptions for select to authenticated
  using (public.is_gym_member(gym_id));

-- plans: everyone reads; owner/manager manage
create policy plans_select on public.plans for select to authenticated
  using (public.is_gym_member(gym_id));
create policy plans_insert on public.plans for insert to authenticated
  with check (public.has_gym_role(gym_id, array['owner','manager']) and public.gym_is_writable(gym_id));
create policy plans_update on public.plans for update to authenticated
  using (public.has_gym_role(gym_id, array['owner','manager']) and public.gym_is_writable(gym_id))
  with check (public.has_gym_role(gym_id, array['owner','manager']) and public.gym_is_writable(gym_id));

-- members: any active team member
create policy members_select on public.members for select to authenticated
  using (public.is_gym_member(gym_id));
create policy members_insert on public.members for insert to authenticated
  with check (public.is_gym_member(gym_id) and public.gym_is_writable(gym_id));
create policy members_update on public.members for update to authenticated
  using (public.is_gym_member(gym_id) and public.gym_is_writable(gym_id))
  with check (public.is_gym_member(gym_id) and public.gym_is_writable(gym_id));

-- memberships: staff can sell one; only owner/manager can change/cancel it
create policy memberships_select on public.memberships for select to authenticated
  using (public.is_gym_member(gym_id));
create policy memberships_insert on public.memberships for insert to authenticated
  with check (public.is_gym_member(gym_id) and public.gym_is_writable(gym_id));
create policy memberships_update on public.memberships for update to authenticated
  using (public.has_gym_role(gym_id, array['owner','manager']) and public.gym_is_writable(gym_id))
  with check (public.has_gym_role(gym_id, array['owner','manager']) and public.gym_is_writable(gym_id));

-- payments: staff may collect; only owner/manager may refund. No update/delete policy exists.
create policy payments_select on public.payments for select to authenticated
  using (public.is_gym_member(gym_id));
create policy payments_insert on public.payments for insert to authenticated
  with check (
    public.is_gym_member(gym_id) and public.gym_is_writable(gym_id)
    and (kind = 'payment' or public.has_gym_role(gym_id, array['owner','manager']))
  );

-- expenses: owner/manager only (staff cannot even read them)
create policy expenses_select on public.expenses for select to authenticated
  using (public.has_gym_role(gym_id, array['owner','manager']));
create policy expenses_insert on public.expenses for insert to authenticated
  with check (public.has_gym_role(gym_id, array['owner','manager']) and public.gym_is_writable(gym_id));
create policy expenses_update on public.expenses for update to authenticated
  using (public.has_gym_role(gym_id, array['owner','manager']) and public.gym_is_writable(gym_id))
  with check (public.has_gym_role(gym_id, array['owner','manager']) and public.gym_is_writable(gym_id));

-- attendance
create policy attendance_select on public.attendance for select to authenticated
  using (public.is_gym_member(gym_id));
create policy attendance_insert on public.attendance for insert to authenticated
  with check (public.is_gym_member(gym_id) and public.gym_is_writable(gym_id));
create policy attendance_update on public.attendance for update to authenticated
  using (public.is_gym_member(gym_id) and public.gym_is_writable(gym_id))
  with check (public.is_gym_member(gym_id) and public.gym_is_writable(gym_id));

-- sheets_config: owner only (and it never contains credentials)
create policy sheets_select on public.sheets_config for select to authenticated
  using (public.has_gym_role(gym_id, array['owner']));
create policy sheets_insert on public.sheets_config for insert to authenticated
  with check (public.has_gym_role(gym_id, array['owner']));
create policy sheets_update on public.sheets_config for update to authenticated
  using (public.has_gym_role(gym_id, array['owner']))
  with check (public.has_gym_role(gym_id, array['owner']));
