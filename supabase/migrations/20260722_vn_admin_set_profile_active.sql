-- Admin can toggle vn_profiles.is_active (access on/off) without full Auth admin API.
CREATE OR REPLACE FUNCTION public.vn_admin_set_profile_active(
  p_user_id uuid,
  p_is_active boolean
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
BEGIN
  IF auth.uid() IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'not_authenticated');
  END IF;

  IF NOT public.vn_is_admin() THEN
    RETURN jsonb_build_object('ok', false, 'error', 'not_admin');
  END IF;

  IF p_user_id IS NULL THEN
    RETURN jsonb_build_object('ok', false, 'error', 'invalid_user');
  END IF;

  IF p_user_id = auth.uid() AND p_is_active IS NOT TRUE THEN
    RETURN jsonb_build_object('ok', false, 'error', 'cannot_disable_self');
  END IF;

  UPDATE public.vn_profiles
  SET is_active = p_is_active,
      updated_at = now()
  WHERE user_id = p_user_id;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'error', 'not_found');
  END IF;

  RETURN jsonb_build_object('ok', true, 'user_id', p_user_id, 'is_active', p_is_active);
END;
$function$;

REVOKE ALL ON FUNCTION public.vn_admin_set_profile_active(uuid, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.vn_admin_set_profile_active(uuid, boolean) TO authenticated;
