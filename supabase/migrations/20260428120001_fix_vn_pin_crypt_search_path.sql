-- pgcrypto lives in schema "extensions" on Supabase; SECURITY DEFINER RPCs
-- used set search_path = public only, so crypt(text,text) was not found at runtime.
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
  select u.id, u.pin_hash, u.is_admin, u.username into r
  from public.vn_app_users u
  where lower(trim(u.username)) = lower(trim(p_username))
  limit 1;
  if not found then
    return jsonb_build_object('ok', false, 'error', 'invalid_credentials');
  end if;
  if r.pin_hash is distinct from crypt(p_pin, r.pin_hash) then
    return jsonb_build_object('ok', false, 'error', 'invalid_credentials');
  end if;
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

create or replace function public.vn_admin_create_user(
  p_session_token uuid,
  p_new_username text,
  p_new_pin text
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  admin_uid uuid;
  new_id uuid;
begin
  select public.vn_session_user_id(p_session_token) into admin_uid;
  if admin_uid is null then
    return jsonb_build_object('ok', false, 'error', 'invalid_session');
  end if;
  if not exists (select 1 from public.vn_app_users where id = admin_uid and is_admin = true) then
    return jsonb_build_object('ok', false, 'error', 'not_admin');
  end if;
  if p_new_username is null or trim(p_new_username) = '' then
    return jsonb_build_object('ok', false, 'error', 'username_required');
  end if;
  if p_new_pin is null or length(p_new_pin) < 4 then
    return jsonb_build_object('ok', false, 'error', 'pin_too_short');
  end if;
  if exists (
    select 1 from public.vn_app_users u where lower(trim(u.username)) = lower(trim(p_new_username))
  ) then
    return jsonb_build_object('ok', false, 'error', 'duplicate_username');
  end if;
  insert into public.vn_app_users (username, pin_hash, is_admin)
  values (trim(p_new_username), crypt(p_new_pin, gen_salt('bf')), false)
  returning id into new_id;
  return jsonb_build_object('ok', true, 'user_id', new_id::text, 'username', trim(p_new_username));
end;
$$;
