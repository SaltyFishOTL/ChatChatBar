create extension if not exists pgcrypto;

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
using (true);

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
    ('community-packages', 'community-packages', true, 20971520, array['application/json']),
    ('community-previews', 'community-previews', true, 3145728, array['image/png', 'image/jpeg', 'image/webp', 'image/gif'])
on conflict (id) do update
set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "community_packages_public_read" on storage.objects;
create policy "community_packages_public_read"
on storage.objects
for select
using (bucket_id in ('community-packages', 'community-previews'));

drop policy if exists "community_packages_authenticated_insert" on storage.objects;
create policy "community_packages_authenticated_insert"
on storage.objects
for insert
to authenticated
with check (
    bucket_id in ('community-packages', 'community-previews')
    and (storage.foldername(name))[1] = auth.uid()::text
);

create or replace function public.increment_community_download_count(item_id uuid)
returns void
language sql
security definer
set search_path = public
as $$
    update public.community_items
    set download_count = download_count + 1
    where id = item_id;
$$;

grant execute on function public.increment_community_download_count(uuid) to anon, authenticated;
