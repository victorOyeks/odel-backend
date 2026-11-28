--liquibase formatted sql

--changeset odel:payment-002-charged-amount
-- What the student was charged in total (the fee plus Paystack's charge when the student pays it; owner decision 28 Nov 2026).
-- For an open bank transfer: what Paystack expects into the one-time account.
alter table payments add column charged_kobo bigint;
