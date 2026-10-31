-- Creating a gym is a server-side operation so nobody can grant themselves a subscription
-- or a role. The first gym of a user starts a 14-day trial; extra gyms must be paid for.

create function public.create_gym(p_name text, p_phone text default null, p_city text default null)
returns uuid language plpgsql security definer set search_path = public as $$
declare
  v_uid  uuid := auth.uid();
  v_gym  uuid;
  v_first boolean;
begin
  if v_uid is null then raise exception 'sign in required' using errcode = '28000'; end if;
  if char_length(trim(coalesce(p_name, ''))) < 2 then raise exception 'gym name is too short'; end if;

  select not exists (select 1 from public.gyms where owner_id = v_uid) into v_first;

  insert into public.gyms (name, phone, city, owner_id)
  values (trim(p_name), nullif(trim(p_phone), ''), nullif(trim(p_city), ''), v_uid)
  returning id into v_gym;

  insert into public.gym_users (gym_id, user_id, role, display_name, email)
  select v_gym, v_uid, 'owner', coalesce(u.raw_user_meta_data->>'name', ''), u.email
    from auth.users u where u.id = v_uid;

  insert into public.subscriptions (gym_id, plan, status, current_period_end)
  values (v_gym, 'trial',
          case when v_first then 'trialing' else 'expired' end,
          case when v_first then now() + interval '14 days' else now() end);

  return v_gym;
end $$;

revoke execute on function public.create_gym(text, text, text) from public, anon;
grant  execute on function public.create_gym(text, text, text) to authenticated;

-- Used by edge functions (service role) after a verified payment.
create function public.activate_subscription(p_gym uuid, p_plan text, p_until timestamptz,
                                             p_provider text, p_ref text)
returns void language sql security definer set search_path = public as $$
  update public.subscriptions
     set plan = p_plan, status = 'active', current_period_end = p_until,
         provider = p_provider, provider_ref = p_ref
   where gym_id = p_gym;
$$;
revoke execute on function public.activate_subscription(uuid, text, timestamptz, text, text)
  from public, anon, authenticated;
