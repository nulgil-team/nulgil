-- The car may return a REQUESTED suggestion to PENDING when its final check proved nothing reached the navi
-- (for example the driver started moving). The friend then keeps seeing "waiting" instead of a failure.
create or replace function public.set_invite_suggestion_status(p_code text, p_car_token text, p_id uuid, p_status text)
returns text
language plpgsql volatile security definer set search_path = ''
as $$
declare trip public.invite_trips; current_status text;
begin
  trip := public._invite_car_trip(p_code, p_car_token);
  if trip.id is null then raise exception 'NOT_ALLOWED' using errcode = 'P0001'; end if;
  select s.status into current_status from public.invite_suggestions s where s.id = p_id and s.trip_id = trip.id;
  if current_status is null then raise exception 'NOT_FOUND' using errcode = 'P0001'; end if;
  if not ((current_status = 'PENDING' and p_status in ('REQUESTED','DISMISSED'))
       or (current_status = 'REQUESTED' and p_status in ('ADDED','UNCONFIRMED','FAILED','PENDING'))) then
    raise exception 'BAD_TRANSITION %->%', current_status, p_status using errcode = 'P0001';
  end if;
  update public.invite_suggestions s set status = p_status, updated_at = now() where s.id = p_id;
  return p_status;
end
$$;
