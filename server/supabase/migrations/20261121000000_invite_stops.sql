-- 들를 곳 초대: a friend suggests a stop for one trip; the driver accepts it in the car.
--
-- Privacy: the server stores only the friend's chosen place and a short trip code.
-- No vehicle position, destination or route is ever sent here.
-- Tables are not reachable directly (RLS on, no policies, no grants). Clients use the RPCs below.
-- The car proves ownership of a trip with a random token; only its SHA-256 hash is stored.

create extension if not exists pgcrypto with schema extensions;

create table public.invite_trips (
  id             uuid primary key default gen_random_uuid(),
  code           text not null unique check (code ~ '^[A-Z2-9]{6}$'),
  car_token_hash text not null,
  created_at     timestamptz not null default now(),
  expires_at     timestamptz not null default now() + interval '12 hours',
  closed_at      timestamptz
);

create table public.invite_suggestions (
  id            uuid primary key default gen_random_uuid(),
  trip_id       uuid not null references public.invite_trips(id) on delete cascade,
  sender        text not null check (char_length(sender) between 1 and 20),
  place_name    text not null check (char_length(place_name) between 1 and 80),
  place_address text not null default '' check (char_length(place_address) <= 160),
  latitude      double precision not null check (latitude between 33 and 39),
  longitude     double precision not null check (longitude between 124 and 132),
  note          text not null default '' check (char_length(note) <= 80),
  status        text not null default 'PENDING'
                check (status in ('PENDING','REQUESTED','ADDED','UNCONFIRMED','FAILED','DISMISSED')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index invite_suggestions_trip_idx on public.invite_suggestions (trip_id, created_at);

alter table public.invite_trips enable row level security;
alter table public.invite_suggestions enable row level security;
revoke all on public.invite_trips, public.invite_suggestions from anon, authenticated;

-- Resolves an open trip by code (null when unknown, closed or expired).
create or replace function public._invite_open_trip(p_code text)
returns public.invite_trips
language sql stable security definer set search_path = ''
as $$
  select t.* from public.invite_trips t
  where t.code = upper(trim(p_code)) and t.closed_at is null and t.expires_at > now()
$$;

-- Resolves a trip by code and car token (null when the token does not match).
create or replace function public._invite_car_trip(p_code text, p_car_token text)
returns public.invite_trips
language sql stable security definer set search_path = ''
as $$
  select t.* from public.invite_trips t
  where t.code = upper(trim(p_code))
    and t.car_token_hash = encode(extensions.digest(coalesce(p_car_token, ''), 'sha256'), 'hex')
$$;
revoke all on function public._invite_open_trip(text), public._invite_car_trip(text, text) from public, anon, authenticated;

-- Car: open a trip. Returns the code to share and the secret token to keep in the car.
create or replace function public.create_invite_trip()
returns table (code text, car_token text, expires_at timestamptz)
language plpgsql volatile security definer set search_path = ''
as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  new_code text; new_token text; attempt int := 0; i int;
begin
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

-- Friend: is this code open, and how many suggestions does it already have?
create or replace function public.get_invite_trip(p_code text)
returns table (open boolean, expires_at timestamptz, suggestion_count int)
language plpgsql stable security definer set search_path = ''
as $$
declare trip public.invite_trips;
begin
  trip := public._invite_open_trip(p_code);
  if trip.id is null then return query select false, null::timestamptz, 0; return; end if;
  return query select true, trip.expires_at,
    (select count(*)::int from public.invite_suggestions s where s.trip_id = trip.id);
end
$$;

-- Friend: suggest a stop. At most 10 suggestions per trip.
create or replace function public.submit_invite_suggestion(
  p_code text, p_sender text, p_place_name text, p_place_address text,
  p_latitude double precision, p_longitude double precision, p_note text default '')
returns table (id uuid, status text)
language plpgsql volatile security definer set search_path = ''
as $$
declare trip public.invite_trips; new_id uuid;
begin
  trip := public._invite_open_trip(p_code);
  if trip.id is null then raise exception 'TRIP_CLOSED' using errcode = 'P0001'; end if;
  if (select count(*) from public.invite_suggestions s where s.trip_id = trip.id) >= 10 then
    raise exception 'TOO_MANY_SUGGESTIONS' using errcode = 'P0001';
  end if;
  insert into public.invite_suggestions (trip_id, sender, place_name, place_address, latitude, longitude, note)
  values (trip.id, trim(p_sender), trim(p_place_name), trim(coalesce(p_place_address, '')),
          p_latitude, p_longitude, trim(coalesce(p_note, '')))
  returning invite_suggestions.id into new_id;
  return query select new_id, 'PENDING'::text;
end
$$;

-- Friend: follow one suggestion. The id is an unguessable UUID returned only to its sender.
create or replace function public.get_invite_suggestion(p_code text, p_id uuid)
returns table (status text, updated_at timestamptz)
language sql stable security definer set search_path = ''
as $$
  select s.status, s.updated_at from public.invite_suggestions s
  join public.invite_trips t on t.id = s.trip_id
  where s.id = p_id and t.code = upper(trim(p_code))
$$;

-- Car: list suggestions for its trip, oldest first.
create or replace function public.list_invite_suggestions(p_code text, p_car_token text)
returns table (id uuid, sender text, place_name text, place_address text,
               latitude double precision, longitude double precision, note text,
               status text, created_at timestamptz)
language plpgsql stable security definer set search_path = ''
as $$
declare trip public.invite_trips;
begin
  trip := public._invite_car_trip(p_code, p_car_token);
  if trip.id is null then raise exception 'NOT_ALLOWED' using errcode = 'P0001'; end if;
  return query select s.id, s.sender, s.place_name, s.place_address, s.latitude, s.longitude, s.note,
                      s.status, s.created_at
               from public.invite_suggestions s where s.trip_id = trip.id order by s.created_at;
end
$$;

-- Car: record what happened to a suggestion. Only forward transitions are allowed.
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
       or (current_status = 'REQUESTED' and p_status in ('ADDED','UNCONFIRMED','FAILED'))) then
    raise exception 'BAD_TRANSITION %->%', current_status, p_status using errcode = 'P0001';
  end if;
  update public.invite_suggestions s set status = p_status, updated_at = now() where s.id = p_id;
  return p_status;
end
$$;

-- Car: stop accepting suggestions for this trip.
create or replace function public.close_invite_trip(p_code text, p_car_token text)
returns boolean
language plpgsql volatile security definer set search_path = ''
as $$
declare trip public.invite_trips;
begin
  trip := public._invite_car_trip(p_code, p_car_token);
  if trip.id is null then raise exception 'NOT_ALLOWED' using errcode = 'P0001'; end if;
  update public.invite_trips t set closed_at = coalesce(t.closed_at, now()) where t.id = trip.id;
  return true;
end
$$;

revoke all on function
  public.create_invite_trip(), public.get_invite_trip(text),
  public.submit_invite_suggestion(text, text, text, text, double precision, double precision, text),
  public.get_invite_suggestion(text, uuid), public.list_invite_suggestions(text, text),
  public.set_invite_suggestion_status(text, text, uuid, text), public.close_invite_trip(text, text)
from public;
grant execute on function
  public.create_invite_trip(), public.get_invite_trip(text),
  public.submit_invite_suggestion(text, text, text, text, double precision, double precision, text),
  public.get_invite_suggestion(text, uuid), public.list_invite_suggestions(text, text),
  public.set_invite_suggestion_status(text, text, uuid, text), public.close_invite_trip(text, text)
to anon, authenticated;
