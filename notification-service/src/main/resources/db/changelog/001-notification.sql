--liquibase formatted sql

--changeset odel:001-notification
-- The bell, templates, outbound email/push and the background job queue.

create table notifications (
    id         uuid primary key,
    tenant_id  uuid         not null,
    user_id    uuid         not null,
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
    tenant_id  uuid         not null,
    key        varchar(80)  not null,
    channel    varchar(20)  not null check (channel in ('EMAIL', 'IN_APP', 'PUSH')),
    subject    varchar(300),
    body       text         not null,
    updated_at timestamptz  not null,
    unique (tenant_id, key, channel)
);

create table outbound_messages (
    id           uuid primary key,
    tenant_id    uuid         not null,
    user_id      uuid,
    channel      varchar(20)  not null check (channel in ('EMAIL', 'PUSH')),
    template_key varchar(80)  not null,
    to_address   varchar(500) not null,
    from_name    varchar(200),
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
    tenant_id    uuid         not null,
    user_id      uuid         not null,
    token        varchar(500) not null,
    platform     varchar(20)  not null,
    created_at   timestamptz  not null,
    last_seen_at timestamptz  not null,
    unique (tenant_id, token)
);

-- Background jobs (encoding sync, messages, results computation...). Workers claim rows with SKIP LOCKED.
create table jobs (
    id           uuid primary key,
    tenant_id    uuid         not null,
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
