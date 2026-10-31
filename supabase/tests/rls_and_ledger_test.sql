-- Run with:  supabase test db        (requires the pgTAP extension, included in Supabase local)
begin;
select plan(12);

-- ---------- fixtures (as superuser) ----------
insert into auth.users (id, email) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'owner-a@test.local'),
  ('aaaaaaaa-0000-0000-0000-000000000002', 'staff-a@test.local'),
  ('bbbbbbbb-0000-0000-0000-000000000001', 'owner-b@test.local'),
  ('cccccccc-0000-0000-0000-000000000001', 'owner-expired@test.local');

insert into public.gyms (id, name, owner_id) values
  ('a0000000-0000-0000-0000-00000000000a', 'Gym A',       'aaaaaaaa-0000-0000-0000-000000000001'),
  ('b0000000-0000-0000-0000-00000000000b', 'Gym B',       'bbbbbbbb-0000-0000-0000-000000000001'),
  ('c0000000-0000-0000-0000-00000000000c', 'Gym Expired', 'cccccccc-0000-0000-0000-000000000001');

insert into public.gym_users (gym_id, user_id, role) values
  ('a0000000-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0000-000000000001', 'owner'),
  ('a0000000-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0000-000000000002', 'staff'),
  ('b0000000-0000-0000-0000-00000000000b', 'bbbbbbbb-0000-0000-0000-000000000001', 'owner'),
  ('c0000000-0000-0000-0000-00000000000c', 'cccccccc-0000-0000-0000-000000000001', 'owner');

insert into public.subscriptions (gym_id, plan, status, current_period_end) values
  ('a0000000-0000-0000-0000-00000000000a', 'monthly', 'active', now() + interval '10 days'),
  ('b0000000-0000-0000-0000-00000000000b', 'monthly', 'active', now() + interval '10 days'),
  ('c0000000-0000-0000-0000-00000000000c', 'monthly', 'active', now() - interval '1 day');

insert into public.members (id, gym_id, name) values
  ('a1000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-00000000000a', 'Ravi A'),
  ('b1000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-00000000000b', 'Sunil B');

insert into public.memberships (id, gym_id, member_id, plan_name, start_on, end_on, price_paise) values
  ('a2000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-00000000000a',
   'a1000000-0000-0000-0000-000000000001', 'Monthly', public.ist_today(), public.ist_today() + 30, 150000);

-- ---------- act as OWNER of gym A ----------
set local role authenticated;
select set_config('request.jwt.claims',
  '{"sub":"aaaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}', true);

select is((select count(*)::int from public.members), 1,
  'owner A sees only gym A members, never gym B');

select throws_ok(
  $$insert into public.members (id, gym_id, name) values (gen_random_uuid(), 'b0000000-0000-0000-0000-00000000000b', 'Hacker')$$,
  '42501', null, 'owner A cannot write into gym B');

select throws_ok(
  $$insert into public.memberships (id, gym_id, member_id, plan_name, start_on, end_on, price_paise)
    values (gen_random_uuid(), 'a0000000-0000-0000-0000-00000000000a',
            'b1000000-0000-0000-0000-000000000001', 'X', current_date, current_date, 1)$$,
  '23503', null, 'composite FK blocks pointing at another gym''s member');

select throws_ok($$select public.dashboard_summary('b0000000-0000-0000-0000-00000000000b')$$,
  '42501', null, 'owner A cannot read gym B dashboard');

select throws_ok($$update public.subscriptions set current_period_end = now() + interval '5 years'$$,
  '42501', null, 'clients cannot edit subscriptions');

-- ledger: 1000 payment + 200 refund today -> collection 800 rupees
insert into public.payments (id, gym_id, member_id, membership_id, kind, amount_paise, paid_on, receipt_no) values
  (gen_random_uuid(), 'a0000000-0000-0000-0000-00000000000a', 'a1000000-0000-0000-0000-000000000001',
   'a2000000-0000-0000-0000-000000000001', 'payment', 100000, public.ist_today(), 'T-1'),
  (gen_random_uuid(), 'a0000000-0000-0000-0000-00000000000a', 'a1000000-0000-0000-0000-000000000001',
   'a2000000-0000-0000-0000-000000000001', 'refund', 20000, public.ist_today(), 'T-2');

select is((public.dashboard_summary('a0000000-0000-0000-0000-00000000000a')->>'today_collection_paise')::bigint,
  80000::bigint, 'today collection = payments minus refunds, in paise');

select is((public.dashboard_summary('a0000000-0000-0000-0000-00000000000a')->>'pending_paise')::bigint,
  70000::bigint, 'pending = 1500 due - (1000 paid - 200 refunded) = 700 rupees');

select throws_ok($$update public.payments set amount_paise = 1$$,
  '42501', null, 'payments cannot be edited by any client (no UPDATE privilege; trigger is a 2nd barrier)');

-- ---------- act as STAFF of gym A ----------
select set_config('request.jwt.claims',
  '{"sub":"aaaaaaaa-0000-0000-0000-000000000002","role":"authenticated"}', true);

select throws_ok(
  $$insert into public.payments (id, gym_id, member_id, kind, amount_paise, paid_on, receipt_no)
    values (gen_random_uuid(), 'a0000000-0000-0000-0000-00000000000a',
            'a1000000-0000-0000-0000-000000000001', 'refund', 100, current_date, 'T-3')$$,
  '42501', null, 'staff cannot issue refunds');

select is((select count(*)::int from public.expenses), 0, 'staff cannot read expenses');

select is((public.dashboard_summary('a0000000-0000-0000-0000-00000000000a')->>'month_collection_paise'), null,
  'staff does not receive monthly totals');

-- ---------- act as owner of the EXPIRED gym ----------
select set_config('request.jwt.claims',
  '{"sub":"cccccccc-0000-0000-0000-000000000001","role":"authenticated"}', true);

select throws_ok(
  $$insert into public.members (id, gym_id, name) values (gen_random_uuid(), 'c0000000-0000-0000-0000-00000000000c', 'Late')$$,
  '42501', null, 'expired subscription blocks writes on the server');

select * from finish();
rollback;
