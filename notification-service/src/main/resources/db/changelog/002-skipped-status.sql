--liquibase formatted sql

--changeset odel:002-skipped-status
-- Emails to reserved test domains are recorded as SKIPPED ("Not sent: test address") instead of being sent.
alter table outbound_messages drop constraint if exists outbound_messages_status_check;
alter table outbound_messages add constraint outbound_messages_status_check check (status in ('QUEUED', 'SENT', 'FAILED', 'SKIPPED'));
