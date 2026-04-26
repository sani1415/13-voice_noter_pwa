-- Voice Notes: custom app users (username + PIN), no Supabase Auth.
-- Default admin: username = admin , PIN = admin  (CHANGE after first login via SQL or future flow)
-- Run in Supabase SQL Editor or via supabase db push after linking.

create extension if not exists pgcrypto;

-- ── App users (PIN hashed with bcrypt via crypt) ─────────────────────────
create table if not exists public.vn_app_users (
  id uuid primary key default gen_random_uuid(),
  username text not null,
  pin_hash text not null,
  is_admin boolean not null default false,
  created_at timestamptz not null default now(),
  constraint vn_app_users_username_nonempty check (length(trim(username)) >= 1),
  constraint vn_app_users_pin_nonempty check (length(pin_hash) >= 1)
);

create unique index if not exists vn_app_users_username_lower_idx
  on public.vn_app_users (lower(trim(username)));

-- Fixed admin id for predictable seed / backfill
insert into public.vn_app_users (id, username, pin_hash, is_admin)
values (
  'a0000000-0000-4000-8000-000000000001'::uuid,
  'admin',
  crypt('admin', gen_salt('bf')),
  true
)
on conflict (id) do nothing;

-- ── Sessions (anon client sends token; RPCs validate) ───────────────────
create table if not exists public.vn_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.vn_app_users (id) on delete cascade,
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);

create index if not exists vn_sessions_user_id_idx on public.vn_sessions (user_id);
create index if not exists vn_sessions_expires_at_idx on public.vn_sessions (expires_at);

-- Sessions / credentials: never expose to PostgREST roles (RPCs only)
revoke all on public.vn_app_users from anon, authenticated;
revoke all on public.vn_sessions from anon, authenticated;

-- ── Notes / folders: owner_id (app user), not auth.users ───────────────
alter table public.vn_notes
  add column if not exists owner_id uuid references public.vn_app_users (id) on delete cascade;
alter table public.vn_folders
  add column if not exists owner_id uuid references public.vn_app_users (id) on delete cascade;

alter table public.vn_notes
  add column if not exists pinned boolean not null default false;
alter table public.vn_notes
  add column if not exists tags jsonb not null default '[]'::jsonb;
alter table public.vn_notes
  add column if not exists label_color text not null default '';
alter table public.vn_folders
  add column if not exists label_color text not null default '';

-- Backfill existing rows to admin
update public.vn_notes set owner_id = 'a0000000-0000-4000-8000-000000000001'::uuid where owner_id is null;
update public.vn_folders set owner_id = 'a0000000-0000-4000-8000-000000000001'::uuid where owner_id is null;

-- Drop legacy Supabase Auth column if present
alter table public.vn_notes drop column if exists user_id;
alter table public.vn_folders drop column if exists user_id;

create index if not exists vn_notes_owner_id_idx on public.vn_notes (owner_id);
create index if not exists vn_folders_owner_id_idx on public.vn_folders (owner_id);

-- ── Drop permissive / auth-based policies ───────────────────────────────
drop policy if exists "vn_notes_anon_all" on public.vn_notes;
drop policy if exists "vn_folders_anon_all" on public.vn_folders;
drop policy if exists "vn_notes_select_own" on public.vn_notes;
drop policy if exists "vn_notes_insert_own" on public.vn_notes;
drop policy if exists "vn_notes_update_own" on public.vn_notes;
drop policy if exists "vn_notes_delete_own" on public.vn_notes;
drop policy if exists "vn_folders_select_own" on public.vn_folders;
drop policy if exists "vn_folders_insert_own" on public.vn_folders;
drop policy if exists "vn_folders_update_own" on public.vn_folders;
drop policy if exists "vn_folders_delete_own" on public.vn_folders;

revoke all on public.vn_notes from anon;
revoke all on public.vn_notes from authenticated;
revoke all on public.vn_folders from anon;
revoke all on public.vn_folders from authenticated;

alter table public.vn_notes enable row level security;
alter table public.vn_folders enable row level security;

-- No direct table access for anon/authenticated; data only via SECURITY DEFINER RPCs
drop policy if exists "vn_notes_deny_anon" on public.vn_notes;
drop policy if exists "vn_notes_deny_authenticated" on public.vn_notes;
drop policy if exists "vn_folders_deny_anon" on public.vn_folders;
drop policy if exists "vn_folders_deny_authenticated" on public.vn_folders;

create policy "vn_notes_deny_anon"
  on public.vn_notes for all to anon using (false) with check (false);
create policy "vn_notes_deny_authenticated"
  on public.vn_notes for all to authenticated using (false) with check (false);
create policy "vn_folders_deny_anon"
  on public.vn_folders for all to anon using (false) with check (false);
create policy "vn_folders_deny_authenticated"
  on public.vn_folders for all to authenticated using (false) with check (false);

-- ── Helper: session → user_id ───────────────────────────────────────────
create or replace function public.vn_session_user_id(p_token uuid)
returns uuid
language sql
volatile
security definer
set search_path = public
as $$
  select s.user_id
  from public.vn_sessions s
  where s.id = p_token and s.expires_at > now();
$$;

-- ── Login ──────────────────────────────────────────────────────────────
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
  where s.id = p_session_token and s.expires_at > now();
  if not found then
    return jsonb_build_object('ok', false, 'error', 'expired_or_invalid');
  end if;
  return jsonb_build_object('ok', true, 'user_id', uid::text, 'username', unm, 'is_admin', adm);
end;
$$;

create or replace function public.vn_pin_logout(p_session_token uuid)
returns void
language sql
security definer
set search_path = public
as $$
  delete from public.vn_sessions where id = p_session_token;
$$;

-- ── Admin creates sub-user (unique username) ─────────────────────────────
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

-- ── Pull all notes + folders for session user ─────────────────────────────
create or replace function public.vn_pull_notes_folders(p_session_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid;
  jnotes jsonb;
  jfolders jsonb;
begin
  uid := public.vn_session_user_id(p_session_token);
  if uid is null then
    return jsonb_build_object('ok', false, 'error', 'invalid_session');
  end if;
  select coalesce(
    (select jsonb_agg(to_jsonb(t) order by t.updated_at desc)
     from (select * from public.vn_notes n where n.owner_id = uid) t),
    '[]'::jsonb
  ) into jnotes;
  select coalesce(
    (select jsonb_agg(to_jsonb(t) order by t.name)
     from (select * from public.vn_folders f where f.owner_id = uid) t),
    '[]'::jsonb
  ) into jfolders;
  return jsonb_build_object('ok', true, 'notes', jnotes, 'folders', jfolders);
end;
$$;

-- ── Upsert / delete note ────────────────────────────────────────────────
create or replace function public.vn_upsert_note_session(p_session_token uuid, p_note jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid;
  nid uuid;
begin
  uid := public.vn_session_user_id(p_session_token);
  if uid is null then raise exception 'invalid_session'; end if;
  nid := (p_note->>'id')::uuid;
  if exists (select 1 from public.vn_notes where id = nid and owner_id is distinct from uid) then
    raise exception 'forbidden';
  end if;
  insert into public.vn_notes (
    id, owner_id, title, content, folder_id, created_at, updated_at,
    title_is_custom, pinned, tags, label_color
  )
  values (
    nid,
    uid,
    coalesce(p_note->>'title', 'নতুন নোট'),
    coalesce(p_note->>'content', ''),
    nullif(p_note->>'folder_id', '')::uuid,
    coalesce((p_note->>'created_at')::timestamptz, now()),
    coalesce((p_note->>'updated_at')::timestamptz, now()),
    coalesce((p_note->>'title_is_custom')::boolean, false),
    coalesce((p_note->>'pinned')::boolean, false),
    coalesce(p_note->'tags', '[]'::jsonb),
    coalesce(p_note->>'label_color', '')
  )
  on conflict (id) do update set
    title = excluded.title,
    content = excluded.content,
    folder_id = excluded.folder_id,
    created_at = excluded.created_at,
    updated_at = excluded.updated_at,
    title_is_custom = excluded.title_is_custom,
    pinned = excluded.pinned,
    tags = excluded.tags,
    label_color = excluded.label_color,
    owner_id = excluded.owner_id
  where public.vn_notes.owner_id = uid;
end;
$$;

create or replace function public.vn_delete_note_session(p_session_token uuid, p_note_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid;
begin
  uid := public.vn_session_user_id(p_session_token);
  if uid is null then raise exception 'invalid_session'; end if;
  delete from public.vn_notes where id = p_note_id and owner_id = uid;
end;
$$;

-- ── Folders ───────────────────────────────────────────────────────────────
create or replace function public.vn_upsert_folder_session(p_session_token uuid, p_folder jsonb)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid;
  fid uuid;
begin
  uid := public.vn_session_user_id(p_session_token);
  if uid is null then raise exception 'invalid_session'; end if;
  fid := (p_folder->>'id')::uuid;
  if exists (select 1 from public.vn_folders where id = fid and owner_id is distinct from uid) then
    raise exception 'forbidden';
  end if;
  insert into public.vn_folders (id, owner_id, name, created_at, label_color)
  values (
    fid,
    uid,
    coalesce(p_folder->>'name', ''),
    coalesce((p_folder->>'created_at')::timestamptz, now()),
    coalesce(p_folder->>'label_color', '')
  )
  on conflict (id) do update set
    name = excluded.name,
    created_at = excluded.created_at,
    label_color = excluded.label_color,
    owner_id = excluded.owner_id
  where public.vn_folders.owner_id = uid;
end;
$$;

create or replace function public.vn_delete_folder_session(p_session_token uuid, p_folder_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid;
begin
  uid := public.vn_session_user_id(p_session_token);
  if uid is null then raise exception 'invalid_session'; end if;
  delete from public.vn_folders where id = p_folder_id and owner_id = uid;
end;
$$;

-- ── Grants: anon may call RPCs only ─────────────────────────────────────
grant usage on schema public to anon;

grant execute on function public.vn_pin_login(text, text) to anon;
grant execute on function public.vn_pin_resolve_session(uuid) to anon;
grant execute on function public.vn_pin_logout(uuid) to anon;
grant execute on function public.vn_admin_create_user(uuid, text, text) to anon;
grant execute on function public.vn_pull_notes_folders(uuid) to anon;
grant execute on function public.vn_upsert_note_session(uuid, jsonb) to anon;
grant execute on function public.vn_delete_note_session(uuid, uuid) to anon;
grant execute on function public.vn_upsert_folder_session(uuid, jsonb) to anon;
grant execute on function public.vn_delete_folder_session(uuid, uuid) to anon;
