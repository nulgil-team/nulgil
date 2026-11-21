-- Kakao REST key for the resolve-place Edge Function (지도 공유 링크 → 좌표).
-- The key itself is stored in Supabase Vault (encrypted) by the project owner, never in this repository:
--   select vault.create_secret('<Kakao REST API key>', 'kakao_rest_key', 'Kakao Local API for resolve-place');
-- Only the service role (used inside the Edge Function) can read it.

create or replace function public.kakao_rest_key()
returns text
language sql stable security definer set search_path = ''
as $$
  select s.decrypted_secret from vault.decrypted_secrets s where s.name = 'kakao_rest_key' limit 1
$$;

revoke all on function public.kakao_rest_key() from public, anon, authenticated;
grant execute on function public.kakao_rest_key() to service_role;
