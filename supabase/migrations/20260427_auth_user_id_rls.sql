-- Supabase Auth: প্রতি ব্যবহারকারীর নোট/ফোল্ডার আলাদা (RLS)
-- পুরনো সারিতে user_id null থাকলে কেউ দেখতে পাবে না, যতক্ষণ না ড্যাশবোর্ড SQL এ একবার অ্যাসাইন করেন:
--   update public.vn_notes set user_id = 'YOUR-UUID'::uuid where user_id is null;
--   update public.vn_folders set user_id = 'YOUR-UUID'::uuid where user_id is null;

alter table public.vn_notes
  add column if not exists user_id uuid references auth.users (id) on delete cascade;

alter table public.vn_folders
  add column if not exists user_id uuid references auth.users (id) on delete cascade;

create index if not exists vn_notes_user_id_idx on public.vn_notes (user_id);
create index if not exists vn_folders_user_id_idx on public.vn_folders (user_id);

alter table public.vn_notes enable row level security;
alter table public.vn_folders enable row level security;

drop policy if exists "vn_notes_select_own" on public.vn_notes;
drop policy if exists "vn_notes_insert_own" on public.vn_notes;
drop policy if exists "vn_notes_update_own" on public.vn_notes;
drop policy if exists "vn_notes_delete_own" on public.vn_notes;

create policy "vn_notes_select_own"
  on public.vn_notes for select to authenticated
  using (user_id = (select auth.uid()));

create policy "vn_notes_insert_own"
  on public.vn_notes for insert to authenticated
  with check (user_id = (select auth.uid()));

create policy "vn_notes_update_own"
  on public.vn_notes for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

create policy "vn_notes_delete_own"
  on public.vn_notes for delete to authenticated
  using (user_id = (select auth.uid()));

drop policy if exists "vn_folders_select_own" on public.vn_folders;
drop policy if exists "vn_folders_insert_own" on public.vn_folders;
drop policy if exists "vn_folders_update_own" on public.vn_folders;
drop policy if exists "vn_folders_delete_own" on public.vn_folders;

create policy "vn_folders_select_own"
  on public.vn_folders for select to authenticated
  using (user_id = (select auth.uid()));

create policy "vn_folders_insert_own"
  on public.vn_folders for insert to authenticated
  with check (user_id = (select auth.uid()));

create policy "vn_folders_update_own"
  on public.vn_folders for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

create policy "vn_folders_delete_own"
  on public.vn_folders for delete to authenticated
  using (user_id = (select auth.uid()));
