--liquibase formatted sql

--changeset odel:002-centre-moves
-- B2 (A-06): moving a student to another study centre, with a reason and an effective date. A move dated today or earlier is
-- applied at once; a later one waits (SCHEDULED) for the daily job. The rows are the student's centre history.
create table centre_moves (
    id                uuid primary key,
    tenant_id         uuid         not null,
    student_id        uuid         not null,
    from_centre_id    uuid,
    from_centre_code  varchar(40)  not null,
    from_centre_name  varchar(200) not null,
    to_centre_id      uuid,
    to_centre_code    varchar(40)  not null,
    to_centre_name    varchar(200) not null,
    reason            varchar(500) not null,
    effective_on      date         not null,
    status            varchar(20)  not null check (status in ('SCHEDULED', 'DONE', 'CANCELLED')),
    requested_by      uuid,
    requested_by_name varchar(200),
    notified          text,         -- JSON [{name, role, email}]
    not_notified      text,         -- JSON ["…"]
    requests_moved    int,
    created_at        timestamptz  not null,
    applied_at        timestamptz,
    cancelled_at      timestamptz
);
create index centre_moves_student_idx on centre_moves (student_id, created_at desc);
create index centre_moves_due_idx on centre_moves (effective_on) where status = 'SCHEDULED';
