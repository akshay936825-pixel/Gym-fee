-- GYM HISAB core schema.
-- Money is ALWAYS stored as integer paise (1 rupee = 100 paise). Never floats.
-- Every business table carries gym_id, and child rows use composite foreign keys
-- (id, gym_id) so a row can never point at another gym's parent record.

create table public.gyms (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (char_length(name) between 2 and 80),
  phone       text,
  city        text,
  owner_id    uuid not null references auth.users(id),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table public.gym_users (
  gym_id        uuid not null references public.gyms(id) on delete cascade,
  user_id       uuid not null references auth.users(id) on delete cascade,
  role          text not null check (role in ('owner','manager','staff')),
  display_name  text not null default '',
  email         text,
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  primary key (gym_id, user_id)
);
create index gym_users_user_idx on public.gym_users(user_id);

-- Written ONLY by the backend (service role / webhook). Clients can read, never write.
create table public.subscriptions (
  gym_id              uuid primary key references public.gyms(id) on delete cascade,
  plan                text not null default 'trial' check (plan in ('trial','monthly','yearly')),
  status              text not null default 'trialing'
                      check (status in ('trialing','active','past_due','canceled','expired')),
  current_period_end  timestamptz not null,
  provider            text,
  provider_ref        text,
  updated_at          timestamptz not null default now()
);

create table public.plans (
  id              uuid primary key,
  gym_id          uuid not null references public.gyms(id) on delete cascade,
  name            text not null check (char_length(name) between 1 and 60),
  duration_months int  not null check (duration_months in (1,3,6,12)),
  price_paise     bigint not null check (price_paise >= 0),
  active          boolean not null default true,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (id, gym_id)
);

create table public.members (
  id          uuid primary key,
  gym_id      uuid not null references public.gyms(id) on delete cascade,
  name        text not null check (char_length(name) between 1 and 80),
  phone       text,
  joined_on   date not null default current_date,
  notes       text,
  archived    boolean not null default false,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (id, gym_id)
);
create index members_gym_idx on public.members(gym_id, updated_at);

create table public.memberships (
  id              uuid primary key,
  gym_id          uuid not null,
  member_id       uuid not null,
  plan_id         uuid,
  plan_name       text not null,
  start_on        date not null,
  end_on          date not null,
  price_paise     bigint not null check (price_paise >= 0),
  discount_paise  bigint not null default 0 check (discount_paise >= 0),
  status          text not null default 'active' check (status in ('active','cancelled')),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  check (end_on >= start_on),
  check (discount_paise <= price_paise),
  unique (id, gym_id),
  foreign key (member_id, gym_id) references public.members(id, gym_id) on delete cascade,
  foreign key (plan_id, gym_id)   references public.plans(id, gym_id)
);
create index memberships_gym_idx    on public.memberships(gym_id, updated_at);
create index memberships_member_idx on public.memberships(member_id, end_on);

-- Append-only money ledger. Corrections are made with a 'refund' row, never by editing.
create table public.payments (
  id             uuid primary key,
  gym_id         uuid not null,
  member_id      uuid not null,
  membership_id  uuid,
  kind           text not null default 'payment' check (kind in ('payment','refund')),
  amount_paise   bigint not null check (amount_paise > 0),
  mode           text not null default 'cash' check (mode in ('cash','upi','card','other')),
  paid_on        date not null,
  note           text,
  receipt_no     text not null,
  created_by     uuid,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (gym_id, receipt_no),
  foreign key (member_id, gym_id)     references public.members(id, gym_id)     on delete cascade,
  foreign key (membership_id, gym_id) references public.memberships(id, gym_id) on delete cascade
);
create index payments_gym_date_idx   on public.payments(gym_id, paid_on);
create index payments_gym_sync_idx   on public.payments(gym_id, updated_at);
create index payments_membership_idx on public.payments(membership_id);

create table public.expenses (
  id            uuid primary key,
  gym_id        uuid not null references public.gyms(id) on delete cascade,
  category      text not null check (char_length(category) between 1 and 40),
  amount_paise  bigint not null check (amount_paise > 0),
  spent_on      date not null,
  note          text,
  deleted       boolean not null default false,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index expenses_gym_date_idx on public.expenses(gym_id, spent_on);
create index expenses_gym_sync_idx on public.expenses(gym_id, updated_at);

-- id is deterministic on the client (member + day) so two phones never double-count a visit.
create table public.attendance (
  id           uuid primary key,
  gym_id       uuid not null,
  member_id    uuid not null,
  attended_on  date not null,
  present      boolean not null default true,
  created_by   uuid,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (gym_id, member_id, attended_on),
  foreign key (member_id, gym_id) references public.members(id, gym_id) on delete cascade
);
create index attendance_gym_sync_idx on public.attendance(gym_id, updated_at);

-- Optional Google Sheets integration. Holds NO credentials: the service-account key lives
-- only in the edge-function secrets. The owner shares their sheet with that service account.
create table public.sheets_config (
  gym_id          uuid primary key references public.gyms(id) on delete cascade,
  spreadsheet_id  text not null,
  enabled         boolean not null default true,
  last_synced_at  timestamptz,
  last_error      text,
  updated_at      timestamptz not null default now()
);

-- ---------- triggers ----------
create function public.touch_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;

create function public.lock_gym_id() returns trigger
language plpgsql as $$
begin
  if new.gym_id is distinct from old.gym_id then
    raise exception 'gym_id is immutable';
  end if;
  return new;
end $$;

create function public.stamp_created_by() returns trigger
language plpgsql as $$
begin
  new.created_by := auth.uid();   -- cannot be spoofed by the client
  return new;
end $$;

create function public.payments_append_only() returns trigger
language plpgsql as $$
begin
  raise exception 'payments are an append-only ledger; record a refund instead';
end $$;

do $$
declare t text;
begin
  foreach t in array array['gyms','gym_users','plans','members','memberships','expenses','attendance','sheets_config','subscriptions']
  loop
    execute format('create trigger %I_touch before insert or update on public.%I for each row execute function public.touch_updated_at()', t, t);
  end loop;
  foreach t in array array['plans','members','memberships','expenses','attendance','payments']
  loop
    execute format('create trigger %I_lock_gym before update on public.%I for each row execute function public.lock_gym_id()', t, t);
  end loop;
  foreach t in array array['payments','expenses','attendance']
  loop
    execute format('create trigger %I_stamp_by before insert on public.%I for each row execute function public.stamp_created_by()', t, t);
  end loop;
end $$;

create trigger payments_touch      before insert on public.payments for each row execute function public.touch_updated_at();
create trigger payments_no_update  before update or delete on public.payments for each row execute function public.payments_append_only();
