--liquibase formatted sql

--changeset odel:001-payment
-- B2 payments: the platform fee per student per semester, paid through Paystack (or the fake gateway locally).
-- Money is in kobo. Every table carries tenant_id (Hibernate @TenantId), except webhook_events, which arrive before we know the university.

-- Per university, set by the provider: the fee, the ODeL centre's flat share, and the settlement account behind the Paystack subaccount.
-- Only the last four digits of the account number are kept.
create table fee_settings (
    tenant_id              uuid primary key,
    fee_kobo               bigint       not null,
    centre_share_kobo      bigint       not null,
    bank_code              varchar(20),
    bank_name              varchar(120),
    account_last4          varchar(4),
    account_name           varchar(200),
    subaccount_code        varchar(60),
    account_since          timestamptz,
    account_updated_by     uuid,
    account_updated_by_name varchar(200),
    updated_at             timestamptz  not null
);

-- One fee per student per semester. status: NOT_PAID | PAID | REFUNDED | REVERSED (CONFIRMING is worked out from an open payment).
create table fees (
    id              uuid primary key,
    tenant_id       uuid         not null,
    student_id      uuid         not null,
    user_id         uuid,
    semester_id     uuid         not null,
    session_id      uuid         not null,
    session_name    varchar(20)  not null,
    semester_number int          not null,
    amount_kobo     bigint       not null,
    status          varchar(20)  not null check (status in ('NOT_PAID', 'PAID', 'REFUNDED', 'REVERSED')),
    paid_payment_id uuid,
    paid_at         timestamptz,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    unique (tenant_id, student_id, semester_id)
);
create index fees_semester_idx on fees (tenant_id, semester_id, status);

create table payments (
    id                      uuid primary key,
    tenant_id               uuid         not null,
    fee_id                  uuid         not null references fees (id),
    student_id              uuid         not null,
    user_id                 uuid,
    student_name            varchar(200) not null,
    matric_number           varchar(40)  not null,
    fee_label               varchar(60)  not null,
    reference               varchar(40)  not null unique,
    method                  varchar(20)  not null check (method in ('CARD', 'USSD', 'BANK_TRANSFER')),
    status                  varchar(20)  not null check (status in ('PENDING', 'CONFIRMING', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED',
                                                                    'REFUNDING', 'REFUNDED', 'REVERSED')),
    amount_kobo             bigint       not null,
    provider_share_kobo     bigint       not null,
    centre_share_kobo       bigint       not null,
    gateway_fee_kobo        bigint,
    subaccount_code         varchar(60),
    email                   varchar(320) not null,
    authorization_url       text,
    access_code             varchar(100),
    ussd_bank               varchar(20),
    ussd_code               varchar(100),
    transfer_bank           varchar(120),
    transfer_account_number varchar(20),
    transfer_account_name   varchar(200),
    expires_at              timestamptz,
    channel                 varchar(30),
    card_type               varchar(40),
    card_bank               varchar(120),
    card_last4              varchar(4),
    gateway_transaction_id  varchar(40),
    gateway_message         varchar(300),
    failure_reason          varchar(300),
    duplicate               boolean      not null default false,
    claimed_at              timestamptz,
    paid_at                 timestamptz,
    confirmed_by            varchar(20),
    disputed_at             timestamptz,
    reversed_at             timestamptz,
    last_checked_at         timestamptz,
    created_at              timestamptz  not null,
    updated_at              timestamptz  not null
);
create index payments_fee_idx on payments (tenant_id, fee_id, created_at desc);
create index payments_student_idx on payments (tenant_id, student_id, created_at desc);
create index payments_paid_idx on payments (paid_at) where paid_at is not null;
create index payments_open_idx on payments (status, created_at) where status in ('PENDING', 'CONFIRMING');
-- One open payment per student per semester, guaranteed by the database.
create unique index payments_one_open_idx on payments (fee_id) where status in ('PENDING', 'CONFIRMING');

create table refunds (
    id                uuid primary key,
    tenant_id         uuid         not null,
    payment_id        uuid         not null references payments (id),
    reference         varchar(20)  not null unique,
    gateway_refund_id varchar(40),
    amount_kobo       bigint       not null,
    reason            varchar(200) not null,
    status            varchar(20)  not null check (status in ('NOT_SENT', 'PENDING', 'PROCESSED', 'FAILED')),
    last_error        varchar(300),
    started_at        timestamptz  not null,
    processed_at      timestamptz,
    expected_by       date         not null
);
create unique index refunds_payment_idx on refunds (payment_id);

-- Each webhook event once: the same event again changes nothing.
create table webhook_events (
    id          uuid primary key,
    event_key   varchar(200) not null unique,
    event       varchar(60)  not null,
    reference   varchar(60),
    tenant_id   uuid,
    outcome     varchar(60)  not null,
    received_at timestamptz  not null
);
