-- 폰에서 경로 보내기: the driver's phone sends a destination; the car shows it and starts after one tap.
--
-- A car creates a link once (QR on the car screen). The car keeps a car token, the phone keeps a phone token;
-- only SHA-256 hashes are stored. The server holds the place the phone sent until the car takes it (2 hours at
-- most), never the car's position or route. Tables are reachable only through the functions below.
--
--   TOO_BUSY       more than 60 links were created across all cars in the last 10 minutes
--   TOO_FAST       a phone sent 10 places in the last hour
--   NOT_ALLOWED    unknown link, wrong token or a link the car removed

create table public.phone_links (
  id               uuid primary key default gen_random_uuid(),
  car_token_hash   text not null,
  phone_token_hash text not null,
  created_at       timestamptz not null default now(),
  revoked_at       timestamptz
);
create index phone_links_created_idx on public.phone_links (created_at);

create table public.phone_routes (
  id            uuid primary key default gen_random_uuid(),
  link_id       uuid not null references public.phone_links(id) on delete cascade,
  place_name    text not null check (char_length(place_name) between 1 and 80),
  place_address text not null default '' check (char_length(place_address) <= 160),
  latitude      double precision not null check (latitude between 33 and 39),
  longitude     double precision not null check (longitude between 124 and 132),
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null default now() + interval '2 hours',
  taken_at      timestamptz
);
create index phone_routes_link_idx on public.phone_routes (link_id, created_at);

alter table public.phone_links enable row level security;
alter table public.phone_routes enable row level security;
revoke all on public.phone_links, public.phone_routes from anon, authenticated;

create or replace function public._phone_link(p_link uuid, p_token text, p_side text)
returns public.phone_links
language sql stable security definer set search_path = ''
as $$
  select l.* from public.phone_links l
  where l.id = p_link and l.revoked_at is null
    and (case when p_side = 'car' then l.car_token_hash else l.phone_token_hash end)
        = encode(extensions.digest(coalesce(p_token, ''), 'sha256'), 'hex')
$$;
revoke all on function public._phone_link(uuid, text, text) from public, anon, authenticated;

-- Car: create a link. Returns the link id, the car's secret and the phone's secret (shown once, in the QR).
create or replace function public.create_phone_link()
returns table (link_id uuid, car_token text, phone_token text)
language plpgsql volatile security definer set search_path = ''
as $$
declare car text := encode(extensions.gen_random_bytes(24), 'hex'); phone text := encode(extensions.gen_random_bytes(24), 'hex');
        new_id uuid;
begin
  perform pg_advisory_xact_lock(hashtext('create_phone_link'));
  delete from public.phone_routes r where r.expires_at < now() - interval '1 day';
  if (select count(*) from public.phone_links l where l.created_at > now() - interval '10 minutes') >= 60 then
    raise exception 'TOO_BUSY' using errcode = 'P0001';
  end if;
  insert into public.phone_links (car_token_hash, phone_token_hash)
  values (encode(extensions.digest(car, 'sha256'), 'hex'), encode(extensions.digest(phone, 'sha256'), 'hex'))
  returning id into new_id;
  return query select new_id, car, phone;
end
$$;

-- Phone: send a destination. A newer place replaces one the car has not taken yet.
create or replace function public.send_phone_route(
  p_link uuid, p_phone_token text, p_place_name text, p_place_address text,
  p_latitude double precision, p_longitude double precision)
returns uuid
language plpgsql volatile security definer set search_path = ''
as $$
declare link public.phone_links; new_id uuid;
begin
  link := public._phone_link(p_link, p_phone_token, 'phone');
  if link.id is null then raise exception 'NOT_ALLOWED' using errcode = 'P0001'; end if;
  perform pg_advisory_xact_lock(hashtext('send_phone_route:' || link.id::text));
  if (select count(*) from public.phone_routes r where r.link_id = link.id and r.created_at > now() - interval '1 hour') >= 10 then
    raise exception 'TOO_FAST' using errcode = 'P0001';
  end if;
  delete from public.phone_routes r where r.link_id = link.id and r.taken_at is null;
  insert into public.phone_routes (link_id, place_name, place_address, latitude, longitude)
  values (link.id, trim(p_place_name), trim(coalesce(p_place_address, '')), p_latitude, p_longitude)
  returning id into new_id;
  return new_id;
end
$$;

-- Phone: did the car take it? WAITING, TAKEN, EXPIRED (or NONE for an unknown id).
create or replace function public.get_phone_route_status(p_link uuid, p_phone_token text, p_id uuid)
returns text
language plpgsql stable security definer set search_path = ''
as $$
declare link public.phone_links; route public.phone_routes;
begin
  link := public._phone_link(p_link, p_phone_token, 'phone');
  if link.id is null then raise exception 'NOT_ALLOWED' using errcode = 'P0001'; end if;
  select r.* into route from public.phone_routes r where r.id = p_id and r.link_id = link.id;
  if route.id is null then return 'NONE'; end if;
  if route.taken_at is not null then return 'TAKEN'; end if;
  if route.expires_at <= now() then return 'EXPIRED'; end if;
  return 'WAITING';
end
$$;

-- Car: take the waiting place, once. Empty when nothing waits.
create or replace function public.take_phone_route(p_link uuid, p_car_token text)
returns table (id uuid, place_name text, place_address text, latitude double precision, longitude double precision,
               created_at timestamptz)
language plpgsql volatile security definer set search_path = ''
as $$
declare link public.phone_links;
begin
  link := public._phone_link(p_link, p_car_token, 'car');
  if link.id is null then raise exception 'NOT_ALLOWED' using errcode = 'P0001'; end if;
  return query
    update public.phone_routes r set taken_at = now()
    where r.id = (select w.id from public.phone_routes w
                  where w.link_id = link.id and w.taken_at is null and w.expires_at > now()
                  order by w.created_at desc limit 1)
    returning r.id, r.place_name, r.place_address, r.latitude, r.longitude, r.created_at;
end
$$;

-- Car: remove the link; the phone can no longer send.
create or replace function public.revoke_phone_link(p_link uuid, p_car_token text)
returns boolean
language plpgsql volatile security definer set search_path = ''
as $$
declare link public.phone_links;
begin
  link := public._phone_link(p_link, p_car_token, 'car');
  if link.id is null then return false; end if;
  update public.phone_links l set revoked_at = now() where l.id = link.id;
  delete from public.phone_routes r where r.link_id = link.id;
  return true;
end
$$;

revoke all on function public.create_phone_link(), public.send_phone_route(uuid, text, text, text, double precision, double precision),
  public.get_phone_route_status(uuid, text, uuid), public.take_phone_route(uuid, text), public.revoke_phone_link(uuid, text) from public;
grant execute on function public.create_phone_link(), public.send_phone_route(uuid, text, text, text, double precision, double precision),
  public.get_phone_route_status(uuid, text, uuid), public.take_phone_route(uuid, text), public.revoke_phone_link(uuid, text)
  to anon, authenticated;
