--liquibase formatted sql

--changeset odel:001-platform-base
-- B0 Platform base: tenants, people and roles, two-step sign-in, audit, messages, files, jobs.
-- Every row carries tenant_id. The platform itself is a tenant (kind PLATFORM) so provider staff
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
    updated_at      timestamptz  not null
);

insert into tenants (id, kind, slug, name, short_name, status, primary_color, accent_color, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000001', 'PLATFORM', 'platform', 'ODeL Platform', 'ODeL', 'LIVE',
        '#1A2368', '#C27808', now(), now());

create table users (
    id                      uuid primary key,
    tenant_id               uuid         not null references tenants (id),
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
    tenant_id   uuid        not null references tenants (id),
    user_id     uuid        not null references users (id),
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
    tenant_id  uuid         not null references tenants (id),
    user_id    uuid         not null references users (id),
    code_hash  varchar(100) not null,
    used_at    timestamptz,
    created_at timestamptz  not null
);
create index mfa_backup_codes_user_idx on mfa_backup_codes (user_id);

create table trusted_devices (
    id           uuid primary key,
    tenant_id    uuid         not null references tenants (id),
    user_id      uuid         not null references users (id),
    token_hash   varchar(100) not null unique,
    user_agent   varchar(500),
    created_at   timestamptz  not null,
    expires_at   timestamptz  not null,
    last_used_at timestamptz
);

-- Kept at least 7 years (NFR Audit). Never updated or deleted by the application.
create table audit_log (
    id              uuid primary key,
    tenant_id       uuid         not null references tenants (id), -- the university the entry is about
    actor_user_id   uuid,
    actor_tenant_id uuid,                                           -- platform tenant for provider actions
    actor_name      varchar(200),
    actor_role      varchar(40),
    action          varchar(60)  not null,
    entity_type     varchar(60),
    entity_id       uuid,
    summary         text         not null,
    old_value       jsonb,
    new_value       jsonb,
    ip              varchar(64),
    user_agent      varchar(500),
    created_at      timestamptz  not null
);
create index audit_log_tenant_idx on audit_log (tenant_id, created_at desc);
create index audit_log_actor_tenant_idx on audit_log (actor_tenant_id, created_at desc);

create table notifications (
    id         uuid primary key,
    tenant_id  uuid         not null references tenants (id),
    user_id    uuid         not null references users (id),
    type       varchar(60)  not null,
    title      varchar(200) not null,
    body       text,
    link       varchar(500),
    created_at timestamptz  not null,
    read_at    timestamptz
);
create index notifications_user_idx on notifications (user_id, created_at desc);

-- A template row for the platform tenant is the default; a university may override it with its own row.
create table message_templates (
    id         uuid primary key,
    tenant_id  uuid         not null references tenants (id),
    key        varchar(80)  not null,
    channel    varchar(20)  not null check (channel in ('EMAIL', 'IN_APP', 'PUSH')),
    subject    varchar(300),
    body       text         not null,
    updated_at timestamptz  not null,
    unique (tenant_id, key, channel)
);

create table outbound_messages (
    id           uuid primary key,
    tenant_id    uuid         not null references tenants (id),
    user_id      uuid references users (id),
    channel      varchar(20)  not null check (channel in ('EMAIL', 'PUSH')),
    template_key varchar(80)  not null,
    to_address   varchar(500) not null,
    subject      varchar(300),
    body         text         not null,
    html_body    text,
    status       varchar(20)  not null check (status in ('QUEUED', 'SENT', 'FAILED')),
    attempts     int          not null default 0,
    last_error   text,
    created_at   timestamptz  not null,
    sent_at      timestamptz
);
create index outbound_messages_tenant_idx on outbound_messages (tenant_id, created_at desc);

create table push_tokens (
    id           uuid primary key,
    tenant_id    uuid         not null references tenants (id),
    user_id      uuid         not null references users (id),
    token        varchar(500) not null,
    platform     varchar(20)  not null,
    created_at   timestamptz  not null,
    last_seen_at timestamptz  not null,
    unique (tenant_id, token)
);

-- Background jobs (encoding sync, messages, results computation...). Workers claim rows with SKIP LOCKED.
create table jobs (
    id           uuid primary key,
    tenant_id    uuid         not null references tenants (id),
    type         varchar(80)  not null,
    payload      jsonb        not null,
    status       varchar(20)  not null check (status in ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    run_at       timestamptz  not null,
    attempts     int          not null default 0,
    max_attempts int          not null default 5,
    last_error   text,
    locked_at    timestamptz,
    locked_by    varchar(100),
    created_at   timestamptz  not null,
    finished_at  timestamptz
);
create index jobs_due_idx on jobs (run_at) where status = 'QUEUED';

-- Default message templates (platform tenant). Placeholders use {{name}}.
insert into message_templates (id, tenant_id, key, channel, subject, body, updated_at) values
('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'staff.invitation', 'EMAIL',
 'You are invited to {{universityName}}''s ODeL staff portal',
 'Dear {{fullName}},

You have been added to the {{universityName}} ODeL staff portal as {{roleLabel}}.

Sign in with your {{googleDomain}} Google account here:
{{signInUrl}}

There is no separate password. The first time you sign in, you will set up two-step sign-in with an authenticator app.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'staff.invitation', 'IN_APP',
 'Welcome to the staff portal',
 'Start with the setup checklist.', now());
