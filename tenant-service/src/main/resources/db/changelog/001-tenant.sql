--liquibase formatted sql

--changeset odel:001-tenant
-- Universities on the platform. The platform itself is a tenant (kind PLATFORM) so provider staff
-- live in the same tables as university staff.

create table tenants (
    id              uuid primary key,
    kind            varchar(20)  not null check (kind in ('PLATFORM', 'UNIVERSITY')),
    slug            varchar(40)  not null unique,
    name            varchar(200) not null,
    short_name      varchar(100) not null,
    web_address     varchar(255) unique,
    google_domain   varchar(255),
    status          varchar(20)  not null check (status in ('SETTING_UP', 'LIVE', 'SUSPENDED')),
    primary_color   varchar(7)   not null,
    accent_color    varchar(7)   not null,
    logo_key        varchar(500),
    created_at      timestamptz  not null,
    created_by      uuid,
    created_by_name varchar(200),
    updated_at      timestamptz  not null
);

insert into tenants (id, kind, slug, name, short_name, status, primary_color, accent_color, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000001', 'PLATFORM', 'platform', 'ODeL Platform', 'ODeL', 'LIVE',
        '#1A2368', '#C27808', now(), now());

