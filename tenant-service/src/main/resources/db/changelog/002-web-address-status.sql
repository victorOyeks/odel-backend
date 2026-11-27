--liquibase formatted sql

--changeset odel:002-web-address-status
-- A university's own domain only goes into links once it is live. Until then everyone uses its platform address
-- (<slug>.<base domain>), which works the moment the university is created.
alter table tenants add column web_address_status varchar(20) not null default 'NONE'
    check (web_address_status in ('NONE', 'WAITING_DNS', 'LIVE'));
update tenants set web_address_status = 'WAITING_DNS' where web_address is not null;
