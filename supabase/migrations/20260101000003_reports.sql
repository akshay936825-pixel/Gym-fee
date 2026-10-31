-- Reporting: all totals are computed by the database, never by the AI and never guessed.
-- Views/functions run as the CALLER (security invoker) so Row Level Security still applies.

create view public.membership_balances with (security_invoker = true) as
select
  ms.id         as membership_id,
  ms.gym_id,
  ms.member_id,
  ms.status,
  (ms.price_paise - ms.discount_paise) as due_paise,
  coalesce(sum(case p.kind when 'payment' then p.amount_paise else -p.amount_paise end), 0)::bigint as paid_paise,
  case when ms.status = 'cancelled' then 0
       else (ms.price_paise - ms.discount_paise)
            - coalesce(sum(case p.kind when 'payment' then p.amount_paise else -p.amount_paise end), 0)
  end::bigint as balance_paise
from public.memberships ms
left join public.payments p on p.membership_id = ms.id
group by ms.id;

create function public.ist_today() returns date
language sql stable as $$ select (now() at time zone 'Asia/Kolkata')::date $$;

create function public.dashboard_summary(p_gym uuid, p_today date default public.ist_today())
returns jsonb language plpgsql stable set search_path = public as $$
declare
  v_month_start date := date_trunc('month', p_today)::date;
  v_month_end   date := (date_trunc('month', p_today) + interval '1 month')::date;
  v_money       boolean;
  v_today       bigint;
  v_month       bigint;
  v_exp         bigint;
  v_pending     bigint;
  v_pending_n   int;
  v_active      int;
  v_renewals    int;
  v_expired     int;
begin
  if not public.is_gym_member(p_gym) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  v_money := public.has_gym_role(p_gym, array['owner','manager']);

  select coalesce(sum(case kind when 'payment' then amount_paise else -amount_paise end), 0)
    into v_today from public.payments where gym_id = p_gym and paid_on = p_today;

  select coalesce(sum(case kind when 'payment' then amount_paise else -amount_paise end), 0)
    into v_month from public.payments
   where gym_id = p_gym and paid_on >= v_month_start and paid_on < v_month_end;

  if v_money then
    select coalesce(sum(amount_paise), 0) into v_exp from public.expenses
     where gym_id = p_gym and not deleted and spent_on >= v_month_start and spent_on < v_month_end;
  end if;

  select coalesce(sum(b.balance_paise), 0), count(distinct b.member_id)
    into v_pending, v_pending_n
    from public.membership_balances b
    join public.members m on m.id = b.member_id
   where b.gym_id = p_gym and b.balance_paise > 0 and not m.archived;

  select count(distinct ms.member_id) into v_active
    from public.memberships ms join public.members m on m.id = ms.member_id
   where ms.gym_id = p_gym and not m.archived and ms.status = 'active'
     and p_today between ms.start_on and ms.end_on;

  select count(*) into v_renewals
    from public.memberships ms join public.members m on m.id = ms.member_id
   where ms.gym_id = p_gym and not m.archived and ms.status = 'active'
     and ms.end_on between p_today and p_today + 7
     and not exists (select 1 from public.memberships n
                      where n.member_id = ms.member_id and n.status = 'active' and n.end_on > ms.end_on);

  select count(*) into v_expired
    from public.members m
   where m.gym_id = p_gym and not m.archived
     and not exists (select 1 from public.memberships n
                      where n.member_id = m.id and n.status = 'active' and n.end_on >= p_today);

  return jsonb_build_object(
    'as_of', p_today,
    'today_collection_paise', v_today,
    'month_collection_paise', case when v_money then v_month end,
    'month_expenses_paise',   case when v_money then v_exp end,
    'pending_paise',          v_pending,
    'pending_members',        v_pending_n,
    'active_members',         v_active,
    'upcoming_renewals',      v_renewals,
    'expired_members',        v_expired
  );
end $$;

create function public.report_monthly(p_gym uuid, p_from date, p_to date)
returns table (month date, collected_paise bigint, refunded_paise bigint, expenses_paise bigint, net_paise bigint)
language plpgsql stable set search_path = public as $$
begin
  if not public.has_gym_role(p_gym, array['owner','manager']) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  return query
  select m.month,
    c.collected, c.refunded, e.spent,
    (c.collected - c.refunded - e.spent)::bigint
  from (select d::date as month
          from generate_series(date_trunc('month', p_from), date_trunc('month', p_to), interval '1 month') d) m
  cross join lateral (
    select coalesce(sum(p.amount_paise) filter (where p.kind = 'payment'), 0)::bigint as collected,
           coalesce(sum(p.amount_paise) filter (where p.kind = 'refund'), 0)::bigint  as refunded
      from public.payments p
     where p.gym_id = p_gym and p.paid_on >= m.month
       and p.paid_on < (m.month + interval '1 month')::date
  ) c
  cross join lateral (
    select coalesce(sum(x.amount_paise), 0)::bigint as spent
      from public.expenses x
     where x.gym_id = p_gym and not x.deleted and x.spent_on >= m.month
       and x.spent_on < (m.month + interval '1 month')::date
  ) e
  order by m.month;
end $$;

create function public.expense_breakdown(p_gym uuid, p_from date, p_to date)
returns table (category text, total_paise bigint)
language plpgsql stable set search_path = public as $$
begin
  if not public.has_gym_role(p_gym, array['owner','manager']) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  return query
  select x.category, sum(x.amount_paise)::bigint
    from public.expenses x
   where x.gym_id = p_gym and not x.deleted and x.spent_on between p_from and p_to
   group by x.category order by 2 desc;
end $$;

create function public.pending_list(p_gym uuid)
returns table (member_id uuid, member_name text, phone text, balance_paise bigint, last_end_on date)
language plpgsql stable set search_path = public as $$
begin
  if not public.is_gym_member(p_gym) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  return query
  select m.id, m.name, m.phone, sum(b.balance_paise)::bigint, max(ms.end_on)
    from public.membership_balances b
    join public.members m      on m.id = b.member_id
    join public.memberships ms on ms.id = b.membership_id
   where b.gym_id = p_gym and b.balance_paise > 0 and not m.archived
   group by m.id, m.name, m.phone
   order by 4 desc;
end $$;

-- Everything the AI assistant is allowed to talk about. Computed here, in SQL.
create function public.ai_facts(p_gym uuid, p_from date, p_to date, p_today date default public.ist_today())
returns jsonb language plpgsql stable set search_path = public as $$
declare v jsonb;
begin
  if not public.has_gym_role(p_gym, array['owner','manager']) then
    raise exception 'not allowed' using errcode = '42501';
  end if;
  select jsonb_build_object(
    'currency', 'INR',
    'amounts_are_in', 'paise (divide by 100 for rupees)',
    'range', jsonb_build_object('from', p_from, 'to', p_to),
    'summary', public.dashboard_summary(p_gym, p_today),
    'monthly', coalesce((select jsonb_agg(to_jsonb(r)) from public.report_monthly(p_gym, p_from, p_to) r), '[]'::jsonb),
    'expenses_by_category', coalesce((select jsonb_agg(to_jsonb(r)) from public.expense_breakdown(p_gym, p_from, p_to) r), '[]'::jsonb),
    'top_pending', coalesce((select jsonb_agg(to_jsonb(r)) from (select * from public.pending_list(p_gym) limit 10) r), '[]'::jsonb),
    'plan_mix', coalesce((
        select jsonb_agg(jsonb_build_object('plan', plan_name, 'sold', n, 'billed_paise', billed))
          from (select ms.plan_name, count(*) n, sum(ms.price_paise - ms.discount_paise)::bigint billed
                  from public.memberships ms
                 where ms.gym_id = p_gym and ms.start_on between p_from and p_to
                 group by ms.plan_name order by 2 desc) q), '[]'::jsonb)
  ) into v;
  return v;
end $$;

revoke execute on function public.dashboard_summary(uuid, date), public.report_monthly(uuid, date, date),
  public.expense_breakdown(uuid, date, date), public.pending_list(uuid),
  public.ai_facts(uuid, date, date, date), public.ist_today() from public, anon;
grant  execute on function public.dashboard_summary(uuid, date), public.report_monthly(uuid, date, date),
  public.expense_breakdown(uuid, date, date), public.pending_list(uuid),
  public.ai_facts(uuid, date, date, date), public.ist_today() to authenticated;
grant select on public.membership_balances to authenticated;
