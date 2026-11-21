-- Limits, expiry and cleanup for 들를 곳 초대. No new data is stored: limits only count existing rows,
-- so the server still keeps nothing but invite codes and the places friends sent (no IP, no device id).
--
--   TOO_BUSY  more than 60 invites were created across all cars in the last 10 minutes
--   TOO_FAST  a trip already got 3 places in the last minute
--   EXPIRED   (read only) a friend's place that was still waiting when the invite ended
--
-- Invites are deleted one day after they ended (closed or expired), with their places.

create index if not exists invite_trips_created_idx on public.invite_trips (created_at);

-- Car: open a trip. Same as before, plus a global creation limit and cleanup of ended invites.
create or replace function public.create_invite_trip()
returns table (code text, car_token text, expires_at timestamptz)
language plpgsql volatile security definer set search_path = ''
as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  max_recent constant int := 60;
  new_code text; new_token text; attempt int := 0; i int;
begin
  -- One creator at a time, so the limit below cannot be raced.
  perform pg_advisory_xact_lock(hashtext('create_invite_trip'));
  delete from public.invite_trips t where coalesce(t.closed_at, t.expires_at) < now() - interval '1 day';
  if (select count(*) from public.invite_trips t where t.created_at > now() - interval '10 minutes') >= max_recent then
    raise exception 'TOO_BUSY' using errcode = 'P0001';
  end if;
  loop
    attempt := attempt + 1;
    new_code := '';
    for i in 1..6 loop
      new_code := new_code || substr(alphabet, 1 + (get_byte(extensions.gen_random_bytes(1), 0) % 32), 1);
    end loop;
    exit when not exists (select 1 from public.invite_trips t where t.code = new_code);
    if attempt > 20 then raise exception 'could not allocate a trip code'; end if;
  end loop;
  new_token := encode(extensions.gen_random_bytes(24), 'hex');
  insert into public.invite_trips (code, car_token_hash)
  values (new_code, encode(extensions.digest(new_token, 'sha256'), 'hex'));
  return query select t.code, new_token, t.expires_at from public.invite_trips t where t.code = new_code;
end
$$;

-- Friend: suggest a stop. At most 10 per trip and 3 per minute per trip.
create or replace function public.submit_invite_suggestion(
  p_code text, p_sender text, p_place_name text, p_place_address text,
  p_latitude double precision, p_longitude double precision, p_note text default '')
returns table (id uuid, status text)
language plpgsql volatile security definer set search_path = ''
as $$
declare
  max_total constant int := 10;
  max_per_minute constant int := 3;
  trip public.invite_trips; new_id uuid;
begin
  trip := public._invite_open_trip(p_code);
  if trip.id is null then raise exception 'TRIP_CLOSED' using errcode = 'P0001'; end if;
  -- One sender per trip at a time, so the counts below cannot be raced.
  perform pg_advisory_xact_lock(hashtext('submit_invite_suggestion:' || trip.id::text));
  if (select count(*) from public.invite_suggestions s where s.trip_id = trip.id) >= max_total then
    raise exception 'TOO_MANY_SUGGESTIONS' using errcode = 'P0001';
  end if;
  if (select count(*) from public.invite_suggestions s
      where s.trip_id = trip.id and s.created_at > now() - interval '1 minute') >= max_per_minute then
    raise exception 'TOO_FAST' using errcode = 'P0001';
  end if;
  insert into public.invite_suggestions (trip_id, sender, place_name, place_address, latitude, longitude, note)
  values (trip.id, trim(p_sender), trim(p_place_name), trim(coalesce(p_place_address, '')),
          p_latitude, p_longitude, trim(coalesce(p_note, '')))
  returning invite_suggestions.id into new_id;
  return query select new_id, 'PENDING'::text;
end
$$;

-- Friend: follow one suggestion. A place still waiting when the invite ended reads as EXPIRED,
-- so the friend's page can stop waiting. The stored status is unchanged; the car never sees EXPIRED.
create or replace function public.get_invite_suggestion(p_code text, p_id uuid)
returns table (status text, updated_at timestamptz)
language sql stable security definer set search_path = ''
as $$
  select case when s.status = 'PENDING' and (t.closed_at is not null or t.expires_at <= now())
              then 'EXPIRED' else s.status end,
         s.updated_at
  from public.invite_suggestions s
  join public.invite_trips t on t.id = s.trip_id
  where s.id = p_id and t.code = upper(trim(p_code))
$$;
