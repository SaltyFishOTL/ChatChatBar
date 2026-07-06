create extension if not exists pgcrypto;

create table if not exists public.community_runtime_config (
    id text primary key default 'community' check (id = 'community'),
    enabled boolean not null default true,
    message text not null default '',
    updated_at timestamptz not null default now()
);

insert into public.community_runtime_config (id, enabled, message)
values ('community', true, '')
on conflict (id) do nothing;

create or replace function public.set_community_runtime_config_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists community_runtime_config_set_updated_at on public.community_runtime_config;
create trigger community_runtime_config_set_updated_at
before update on public.community_runtime_config
for each row execute function public.set_community_runtime_config_updated_at();

alter table public.community_runtime_config enable row level security;

drop policy if exists "community_runtime_config_public_read" on public.community_runtime_config;
create policy "community_runtime_config_public_read"
on public.community_runtime_config
for select
using (id = 'community');

grant select on public.community_runtime_config to anon, authenticated;

create or replace function public.community_is_enabled()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select coalesce(
        (select enabled from public.community_runtime_config where id = 'community'),
        true
    );
$$;

grant execute on function public.community_is_enabled() to anon, authenticated;

create or replace function public.set_community_enabled(next_enabled boolean, next_message text default '')
returns public.community_runtime_config
language plpgsql
security definer
set search_path = public
as $$
declare
    updated public.community_runtime_config;
begin
    insert into public.community_runtime_config (id, enabled, message)
    values ('community', next_enabled, coalesce(next_message, ''))
    on conflict (id) do update
    set
        enabled = excluded.enabled,
        message = excluded.message
    returning * into updated;

    update storage.buckets
    set public = next_enabled
    where id in ('community-packages', 'community-previews');

    return updated;
end;
$$;

revoke all on function public.set_community_enabled(boolean, text) from public, anon, authenticated;
grant execute on function public.set_community_enabled(boolean, text) to service_role;

create table if not exists public.community_items (
    id uuid primary key default gen_random_uuid(),
    type text not null check (type in ('character', 'format', 'world_book')),
    title text not null check (char_length(title) between 1 and 120),
    description text not null default '',
    tags text[] not null default array[]::text[],
    author_user_id uuid not null,
    author_name text not null default 'Discord user',
    source_local_name text not null default '',
    file_path text not null unique,
    preview_path text,
    sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$'),
    size_bytes bigint not null check (size_bytes > 0 and size_bytes <= 20971520),
    schema_version integer not null,
    download_count integer not null default 0 check (download_count >= 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists community_items_created_at_idx on public.community_items (created_at desc);
create index if not exists community_items_type_idx on public.community_items (type);
create index if not exists community_items_author_idx on public.community_items (author_user_id);

create or replace function public.set_community_item_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists community_items_set_updated_at on public.community_items;
create trigger community_items_set_updated_at
before update on public.community_items
for each row execute function public.set_community_item_updated_at();

alter table public.community_items enable row level security;

drop policy if exists "community_items_public_read" on public.community_items;
create policy "community_items_public_read"
on public.community_items
for select
using (public.community_is_enabled());

drop policy if exists "community_items_authenticated_update_own" on public.community_items;
create policy "community_items_authenticated_update_own"
on public.community_items
for update
to authenticated
using (author_user_id = auth.uid() and public.community_is_enabled())
with check (author_user_id = auth.uid() and public.community_is_enabled());

drop policy if exists "community_items_authenticated_delete_own" on public.community_items;
create policy "community_items_authenticated_delete_own"
on public.community_items
for delete
to authenticated
using (author_user_id = auth.uid() and public.community_is_enabled());

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
    ('community-packages', 'community-packages', public.community_is_enabled(), 20971520, array['application/json']),
    ('community-previews', 'community-previews', public.community_is_enabled(), 3145728, array['image/png', 'image/jpeg', 'image/webp', 'image/gif'])
on conflict (id) do update
set
    public = public.community_is_enabled(),
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "community_packages_public_read" on storage.objects;
create policy "community_packages_public_read"
on storage.objects
for select
using (
    bucket_id in ('community-packages', 'community-previews')
    and public.community_is_enabled()
);

drop policy if exists "community_packages_authenticated_insert" on storage.objects;
create policy "community_packages_authenticated_insert"
on storage.objects
for insert
to authenticated
with check (
    bucket_id in ('community-packages', 'community-previews')
    and (storage.foldername(name))[1] = auth.uid()::text
    and public.community_is_enabled()
);

drop policy if exists "community_packages_authenticated_update_own" on storage.objects;
create policy "community_packages_authenticated_update_own"
on storage.objects
for update
to authenticated
using (
    bucket_id in ('community-packages', 'community-previews')
    and (storage.foldername(name))[1] = auth.uid()::text
    and public.community_is_enabled()
)
with check (
    bucket_id in ('community-packages', 'community-previews')
    and (storage.foldername(name))[1] = auth.uid()::text
    and public.community_is_enabled()
);

drop policy if exists "community_packages_authenticated_delete_own" on storage.objects;
create policy "community_packages_authenticated_delete_own"
on storage.objects
for delete
to authenticated
using (
    bucket_id in ('community-packages', 'community-previews')
    and (storage.foldername(name))[1] = auth.uid()::text
    and public.community_is_enabled()
);

create or replace function public.increment_community_download_count(item_id uuid)
returns void
language sql
security definer
set search_path = public
as $$
    update public.community_items
    set download_count = download_count + 1
    where id = item_id and public.community_is_enabled();
$$;

grant execute on function public.increment_community_download_count(uuid) to anon, authenticated;
