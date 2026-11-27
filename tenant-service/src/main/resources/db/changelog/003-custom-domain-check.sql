--liquibase formatted sql

--changeset odel:003-custom-domain-check
-- Own domain: WAITING_DNS -> DNS_FOUND (it points at us) -> LIVE (certificate issued, nginx serving it).
alter table tenants drop constraint if exists tenants_web_address_status_check;
alter table tenants add constraint tenants_web_address_status_check
    check (web_address_status in ('NONE', 'WAITING_DNS', 'DNS_FOUND', 'LIVE'));
alter table tenants add column web_address_checked_at timestamptz;
alter table tenants add column web_address_error text;
alter table tenants add column web_address_cert_attempt_at timestamptz;
alter table tenants add column web_address_live_at timestamptz;
