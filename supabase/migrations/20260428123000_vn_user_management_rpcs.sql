-- Voice Notes: account/admin management for custom PIN users.
-- Adds active users, last login, PIN change, admin list/reset/enable-disable RPCs.

alter table public.vn_app_users
  add column if not exists is_active boolean not null default true;

alter table public.vn_app_users
  add column if not exists updated_at timestamptz not null default now();

alter table public.vn_app_users
  add column if not exists last_login_at timestamptz;

update public.vn_app_users
set is_active = true
where is_active is distinct from true;

create or replace function public.vn_pin_login(p_username text, p_pin text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  r record;
  sid uuid;
begin
  if p_username is null or trim(p_username) = '' or p_pin is null or length(p_pin) < 4 then
    return jsonb_build_object('ok', false, 'error', 'invalid_input');
  end if;

  select u.id, u.pin_hash, u.is_admin, u.username, u.is_active into r
  from public.vn_app_users u
  where lower(trim(u.username)) = lower(trim(p_username))
  limit 1;

  if not found then
    return jsonb_build_object('ok', false, 'error', 'invalid_credentials');
  end if;

  if r.is_active is not true then
    return jsonb_build_object('ok', false, 'error', 'user_disabled');
  end if;

  if r.pin_hash is distinct from crypt(p_pin, r.pin_hash) then
    return jsonb_build_object('ok', false, 'error', 'invalid_credentials');
  end if;

  update public.vn_app_users
  set last_login_at = now(), updated_at = now()
  where id = r.id;

  insert into public.vn_sessions (user_id, expires_at)
  values (r.id, now() + interval '30 days')
  returning id into sid;

  return jsonb_build_object(
    'ok', true,
    'session_token', sid::text,
    'user_id', r.id::text,
    'username', r.username,
    'is_admin', r.is_admin
  );
end;
$$;

create or replace function public.vn_pin_resolve_session(p_session_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid;
  unm text;
  adm boolean;
begin
  if p_session_token is null then
    return jsonb_build_object('ok', false, 'error', 'no_token');
  end if;

  select u.id, u.username, u.is_admin into uid, unm, adm
  from public.vn_sessions s
  join public.vn_app_users u on u.id = s.user_id
  where s.id = p_session_token
    and s.expires_at > now()
    and u.is_active = true;

  if not found then
    return jsonb_build_object('ok', false, 'error', 'expired_or_invalid');
  end if;

  return jsonb_build_object('ok', true, 'user_id', uid::text, 'username', unm, 'is_admin', adm);
end;
$$;

create or replace function public.vn_change_own_pin(
  p_session_token uuid,
  p_current_pin text,
  p_new_pin text
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  uid uuid;
  current_hash text;
begin
  uid := public.vn_session_user_id(p_session_token);
  if uid is null then
    return jsonb_build_object('ok', false, 'error', 'invalid_session');
  end if;

  if p_new_pin is null or length(p_new_pin) < 4 then
    return jsonb_build_object('ok', false, 'error', 'pin_too_short');
  end if;

  select pin_hash into current_hash
  from public.vn_app_users
  where id = uid and is_active = true;

  if not found then
    return jsonb_build_object('ok', false, 'error', 'invalid_session');
  end if;

  if current_hash is distinct from crypt(p_current_pin, current_hash) then
    return jsonb_build_object('ok', false, 'error', 'invalid_current_pin');
  end if;

  update public.vn_app_users
  set pin_hash = crypt(p_new_pin, gen_salt('bf')),
      updated_at = now()
  where id = uid;

  delete from public.vn_sessions
  where user_id = uid and id is distinct from p_session_token;

  return jsonb_build_object('ok', true);
end;
$$;

create or replace function public.vn_admin_list_users(p_session_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  admin_uid uuid;
  items jsonb;
begin
  admin_uid := public.vn_session_user_id(p_session_token);
  if admin_uid is null then
    return jsonb_build_object('ok', false, 'error', 'invalid_session');
  end if;

  if not exists (
    select 1 from public.vn_app_users
    where id = admin_uid and is_admin = true and is_active = true
  ) then
    return jsonb_build_object('ok', false, 'error', 'not_admin');
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
    'id', u.id::text,
    'username', u.username,
    'is_admin', u.is_admin,
    'is_active', u.is_active,
    'created_at', u.created_at,
    'last_login_at', u.last_login_at
  ) order by u.is_admin desc, lower(u.username)), '[]'::jsonb)
  into items
  from public.vn_app_users u;

  return jsonb_build_object('ok', true, 'users', items);
end;
$$;

create or replace function public.vn_admin_reset_user_pin(
  p_session_token uuid,
  p_user_id uuid,
  p_new_pin text
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  admin_uid uuid;
begin
  admin_uid := public.vn_session_user_id(p_session_token);
  if admin_uid is null then
    return jsonb_build_object('ok', false, 'error', 'invalid_session');
  end if;

  if not exists (
    select 1 from public.vn_app_users
    where id = admin_uid and is_admin = true and is_active = true
  ) then
    return jsonb_build_object('ok', false, 'error', 'not_admin');
  end if;

  if p_new_pin is null or length(p_new_pin) < 4 then
    return jsonb_build_object('ok', false, 'error', 'pin_too_short');
  end if;

  update public.vn_app_users
  set pin_hash = crypt(p_new_pin, gen_salt('bf')),
      updated_at = now()
  where id = p_user_id;

  if not found then
    return jsonb_build_object('ok', false, 'error', 'user_not_found');
  end if;

  delete from public.vn_sessions where user_id = p_user_id;

  return jsonb_build_object('ok', true);
end;
$$;

create or replace function public.vn_admin_set_user_active(
  p_session_token uuid,
  p_user_id uuid,
  p_is_active boolean
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  admin_uid uuid;
begin
  admin_uid := public.vn_session_user_id(p_session_token);
  if admin_uid is null then
    return jsonb_build_object('ok', false, 'error', 'invalid_session');
  end if;

  if not exists (
    select 1 from public.vn_app_users
    where id = admin_uid and is_admin = true and is_active = true
  ) then
    return jsonb_build_object('ok', false, 'error', 'not_admin');
  end if;

  if p_user_id = admin_uid and p_is_active is not true then
    return jsonb_build_object('ok', false, 'error', 'cannot_disable_self');
  end if;

  update public.vn_app_users
  set is_active = coalesce(p_is_active, true),
      updated_at = now()
  where id = p_user_id;

  if not found then
    return jsonb_build_object('ok', false, 'error', 'user_not_found');
  end if;

  if p_is_active is not true then
    delete from public.vn_sessions where user_id = p_user_id;
  end if;

  return jsonb_build_object('ok', true);
end;
$$;

grant execute on function public.vn_change_own_pin(uuid, text, text) to anon;
grant execute on function public.vn_admin_list_users(uuid) to anon;
grant execute on function public.vn_admin_reset_user_pin(uuid, uuid, text) to anon;
grant execute on function public.vn_admin_set_user_active(uuid, uuid, boolean) to anon;
