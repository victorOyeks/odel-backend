--liquibase formatted sql

--changeset odel:004-staff-sign-in
-- Staff sign-in per university (owner's decision 28 Nov 2026): Google Workspace, Microsoft 365, or a code emailed to the
-- work address. Every university before this change used Google, so that is the default. "Staff Google domain" is now
-- called the staff email domain; the column keeps its old name (google_domain) so services and branches built before the
-- change still run against the same database.
alter table tenants add column staff_sign_in varchar(20) not null default 'GOOGLE';
alter table tenants add constraint tenants_staff_sign_in_check check (staff_sign_in in ('GOOGLE', 'MICROSOFT', 'EMAIL_CODE'));
comment on column tenants.google_domain is 'Staff email domain: staff work emails end with @ this domain (every sign-in method checks it)';
