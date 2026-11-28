--liquibase formatted sql

--changeset odel:003-profile
-- B3 My profile (L-14 … L-14j): home address and next of kin (the student changes them), the photo (approved by the ODeL office),
-- requests the office approves (name, date of birth and gender corrections with proof; new photos), and the history of every change
-- to the record (by the student, by an approved request, or by the office on W-22b).
alter table students add column address_line             varchar(200);
alter table students add column address_town             varchar(100);
alter table students add column address_state            varchar(40);
alter table students add column next_of_kin_name         varchar(150);
alter table students add column next_of_kin_relationship varchar(50);
alter table students add column next_of_kin_phone        varchar(30);
alter table students add column photo_key                varchar(300);
alter table students add column photo_updated_at         timestamptz;

create table profile_requests (
    id                 uuid primary key,
    tenant_id          uuid         not null,
    student_id         uuid         not null,
    kind               varchar(20)  not null check (kind in ('CORRECTION', 'PHOTO')),
    field              varchar(20)  check (field in ('SURNAME', 'FIRST_NAME', 'OTHER_NAMES', 'DATE_OF_BIRTH', 'GENDER')),
    current_value      varchar(150),
    requested_value    varchar(150),
    note               varchar(500),
    file_key           varchar(300),
    file_name          varchar(300),
    file_size          bigint,
    file_type          varchar(100),
    status             varchar(20)  not null check (status in ('PENDING', 'APPROVED', 'DECLINED', 'WITHDRAWN')),
    submitted_at       timestamptz  not null,
    decided_at         timestamptz,
    decided_by         uuid,
    decided_by_name    varchar(200),
    decision_note      varchar(500)
);
create index profile_requests_queue_idx on profile_requests (tenant_id, status, submitted_at);
create index profile_requests_student_idx on profile_requests (student_id, submitted_at desc);
-- One open request per field (and one open photo) per student.
create unique index profile_requests_one_open_uq on profile_requests (student_id, kind, coalesce(field, '')) where status = 'PENDING';

create table profile_changes (
    id            uuid primary key,
    tenant_id     uuid         not null,
    student_id    uuid         not null,
    field         varchar(20)  not null,
    old_value     varchar(400),
    new_value     varchar(400),
    source        varchar(20)  not null check (source in ('STUDENT', 'REQUEST', 'OFFICE')),
    request_id    uuid,
    actor_user_id uuid,
    actor_name    varchar(200),
    created_at    timestamptz  not null
);
create index profile_changes_student_idx on profile_changes (student_id, created_at desc);
