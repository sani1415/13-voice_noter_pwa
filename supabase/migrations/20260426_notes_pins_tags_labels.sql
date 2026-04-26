-- নোট: পিন, ট্যাগ (jsonb), রঙের লেবেল — একবার Supabase SQL এডিটরে চালান
alter table public.vn_notes
  add column if not exists pinned boolean not null default false;
alter table public.vn_notes
  add column if not exists tags jsonb not null default '[]'::jsonb;
alter table public.vn_notes
  add column if not exists label_color text not null default '';
