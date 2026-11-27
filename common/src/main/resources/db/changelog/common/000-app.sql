--liquibase formatted sql

--changeset odel:000-app-audit-log runOnChange:false
-- The one audit log for every service (sign-ins, role changes, provider actions, later score changes).
-- Each service's changelog includes this file; IF NOT EXISTS makes the second and later runs a no-op.
-- Services start together: the lock makes the second and third wait for the first instead of racing it.
select pg_advisory_xact_lock(7313000);
create schema if not exists app;
create table if not exists app.audit_log (
    id              uuid primary key,
    tenant_id       uuid         not null, -- the university the entry is about
    actor_user_id   uuid,
    actor_tenant_id uuid,                  -- the platform tenant for provider actions
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
create index if not exists audit_log_tenant_idx on app.audit_log (tenant_id, created_at desc);
create index if not exists audit_log_actor_tenant_idx on app.audit_log (actor_tenant_id, created_at desc);
