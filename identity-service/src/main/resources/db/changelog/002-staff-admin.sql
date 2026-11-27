--liquibase formatted sql

--changeset odel:002-staff-admin
-- B1 People & roles (W-15, W-21): why and when a staff member was suspended, and who invited them.
alter table users add column suspended_at timestamptz;
alter table users add column suspended_reason varchar(500);
alter table users add column invited_by uuid;
create index users_tenant_status_idx on users (tenant_id, status);
create index role_assignments_tenant_role_idx on role_assignments (tenant_id, role);
