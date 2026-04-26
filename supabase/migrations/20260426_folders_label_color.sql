alter table public.vn_folders
  add column if not exists label_color text not null default '';
