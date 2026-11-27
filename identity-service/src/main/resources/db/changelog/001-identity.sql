--liquibase formatted sql

--changeset odel:001-identity
-- Staff and provider accounts, roles and two-step sign-in. tenant_id is the university (no FK: tenants live in tenant-service).

create table users (
    id                      uuid primary key,
    tenant_id               uuid         not null,
    email                   varchar(320) not null,
    full_name               varchar(200) not null,
    phone                   varchar(30),
    staff_number            varchar(40),
    auth_type               varchar(20)  not null check (auth_type in ('GOOGLE', 'PASSWORD')),
    status                  varchar(20)  not null check (status in ('INVITED', 'ACTIVE', 'SUSPENDED')),
    mfa_secret_enc          varchar(500),
    mfa_enabled_at          timestamptz,
    last_role_assignment_id uuid,
    invited_at              timestamptz,
    accepted_at             timestamptz,
    last_sign_in_at         timestamptz,
    created_at              timestamptz  not null,
    updated_at              timestamptz  not null
);
create unique index users_tenant_email_uq on users (tenant_id, lower(email));

create table role_assignments (
    id          uuid primary key,
    tenant_id   uuid        not null,
    user_id     uuid        not null,
    role        varchar(40) not null,
    scope_type  varchar(20) not null,
    scope_id    uuid,
    scope_label varchar(200),
    created_at  timestamptz not null,
    created_by  uuid
);
create index role_assignments_user_idx on role_assignments (user_id);
create unique index role_assignments_uq on role_assignments (user_id, role, scope_type, coalesce(scope_id, '00000000-0000-0000-0000-000000000000'));

create table mfa_backup_codes (
    id         uuid primary key,
    tenant_id  uuid         not null,
    user_id    uuid         not null,
    code_hash  varchar(100) not null,
    used_at    timestamptz,
    created_at timestamptz  not null
);
create index mfa_backup_codes_user_idx on mfa_backup_codes (user_id);

create table trusted_devices (
    id           uuid primary key,
    tenant_id    uuid         not null,
    user_id      uuid         not null,
    token_hash   varchar(100) not null unique,
    user_agent   varchar(500),
    created_at   timestamptz  not null,
    expires_at   timestamptz  not null,
    last_used_at timestamptz
);

