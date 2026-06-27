-- Voice Notes: remove migration backups + legacy PIN auth artifacts

DROP FUNCTION IF EXISTS public.vn_pin_login(text, text);
DROP FUNCTION IF EXISTS public.vn_pin_logout(uuid);
DROP FUNCTION IF EXISTS public.vn_pin_resolve_session(uuid);
DROP FUNCTION IF EXISTS public.vn_pull_notes_folders(uuid);
DROP FUNCTION IF EXISTS public.vn_upsert_note_session(uuid, jsonb);
DROP FUNCTION IF EXISTS public.vn_upsert_folder_session(uuid, jsonb);
DROP FUNCTION IF EXISTS public.vn_delete_note_session(uuid, uuid);
DROP FUNCTION IF EXISTS public.vn_delete_folder_session(uuid, uuid);
DROP FUNCTION IF EXISTS public.vn_change_own_pin(uuid, text, text);
DROP FUNCTION IF EXISTS public.vn_admin_create_user(uuid, text, text, boolean);
DROP FUNCTION IF EXISTS public.vn_admin_create_user(uuid, text, text);
DROP FUNCTION IF EXISTS public.vn_admin_list_users(uuid);
DROP FUNCTION IF EXISTS public.vn_admin_reset_user_pin(uuid, uuid, text);
DROP FUNCTION IF EXISTS public.vn_admin_set_user_active(uuid, uuid, boolean);
DROP FUNCTION IF EXISTS public.vn_session_user_id(uuid);

DROP TABLE IF EXISTS public.vn_sessions;
DROP TABLE IF EXISTS public.vn_app_users;

DROP TABLE IF EXISTS public.vn_notes_backup_20260612;
DROP TABLE IF EXISTS public.vn_folders_backup_20260612;
DROP TABLE IF EXISTS public.vn_app_users_backup_20260612;
