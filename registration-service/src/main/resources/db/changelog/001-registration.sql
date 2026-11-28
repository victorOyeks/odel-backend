--liquibase formatted sql

--changeset odel:001-registration
-- Course registration (B2): the university's registration rules (fee block, unit limits per programme and level, course-change
-- deadline per semester), carry-overs, each student's registration per semester with its courses and history, and the late
-- registration and course change requests the centre coordinator decides. Every row carries the university.

create table settings (
    tenant_id            uuid primary key,
    block_registration   boolean      not null default true,
    block_exams          boolean      not null default true,
    previews_when_unpaid boolean      not null default true,
    updated_at           timestamptz  not null,
    updated_by_name      varchar(200)
);

-- The course-change deadline of one semester (default: the day after the late close).
create table semester_settings (
    tenant_id         uuid not null,
    semester_id       uuid not null,
    changes_close_on  date,
    updated_at        timestamptz not null,
    primary key (tenant_id, semester_id)
);

-- Unit limits. programme_id null = the university's default. A programme with its own rows overrides the default.
create table unit_rules (
    id             uuid primary key,
    tenant_id      uuid        not null,
    programme_id   uuid,
    level          int         not null check (level between 100 and 900),
    min_units      int         not null,
    max_units      int         not null,
    max_electives  int         not null,
    updated_at     timestamptz not null,
    updated_by_name varchar(200)
);
create unique index unit_rules_uq on unit_rules (tenant_id, coalesce(programme_id, '00000000-0000-0000-0000-000000000000'), level);

create table carry_overs (
    id              uuid primary key,
    tenant_id       uuid         not null,
    student_id      uuid         not null,
    course_id       uuid         not null,
    course_code     varchar(20)  not null,
    course_title    varchar(200) not null,
    units           int          not null,
    semester        int          not null,
    from_session    varchar(20),
    source          varchar(20)  not null check (source in ('ADMIN', 'IMPORT', 'RESULTS')),
    status          varchar(20)  not null check (status in ('OPEN', 'CLEARED')),
    created_at      timestamptz  not null,
    created_by_name varchar(200)
);
create unique index carry_overs_open_uq on carry_overs (tenant_id, student_id, course_id) where status = 'OPEN';
create index carry_overs_student_idx on carry_overs (student_id);

create table registrations (
    id                uuid primary key,
    tenant_id         uuid         not null,
    student_id        uuid         not null,
    user_id           uuid,
    matric_number     varchar(40)  not null,
    student_name      varchar(250) not null,
    semester_id       uuid         not null,
    session_id        uuid         not null,
    session_name      varchar(20)  not null,
    semester_number   int          not null,
    programme_id      uuid,
    programme_code    varchar(40)  not null,
    programme_name    varchar(200) not null,
    level             int          not null,
    centre_id         uuid,
    centre_code       varchar(40),
    centre_name       varchar(200),
    status            varchar(20)  not null check (status in ('REGISTERED')),
    late              boolean      not null default false,
    total_units       int          not null,
    course_count      int          not null,
    slip_number       varchar(40)  not null,
    slip_version      int          not null,
    verification_code varchar(20)  not null,
    submitted_at      timestamptz  not null,
    updated_at        timestamptz  not null,
    changed_at        timestamptz
);
create unique index registrations_student_semester_uq on registrations (tenant_id, student_id, semester_id);
create unique index registrations_slip_uq on registrations (tenant_id, slip_number);
create unique index registrations_code_uq on registrations (tenant_id, verification_code);
create index registrations_semester_idx on registrations (tenant_id, semester_id);

create table registration_courses (
    id              uuid primary key,
    tenant_id       uuid         not null,
    registration_id uuid         not null references registrations (id) on delete cascade,
    course_id       uuid         not null,
    code            varchar(20)  not null,
    title           varchar(200) not null,
    units           int          not null,
    kind            varchar(20)  not null check (kind in ('COMPULSORY', 'ELECTIVE', 'CARRY_OVER')),
    carry_over_id   uuid
);
create unique index registration_courses_uq on registration_courses (registration_id, course_id);

create table registration_events (
    id              uuid primary key,
    tenant_id       uuid         not null,
    registration_id uuid         not null references registrations (id) on delete cascade,
    action          varchar(30)  not null,
    summary         varchar(500) not null,
    slip_version    int          not null,
    actor_name      varchar(200),
    courses         text         not null,  -- JSON: the course codes after this event
    created_at      timestamptz  not null
);
create index registration_events_reg_idx on registration_events (registration_id, created_at);

-- Late registration and course change requests, decided by the centre coordinator (or an ODeL admin).
create table requests (
    id                    uuid primary key,
    tenant_id             uuid          not null,
    kind                  varchar(10)   not null check (kind in ('LATE', 'CHANGE')),
    status                varchar(20)   not null check (status in ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED')),
    student_id            uuid          not null,
    user_id               uuid,
    matric_number         varchar(40)   not null,
    student_name          varchar(250)  not null,
    student_first_name    varchar(100),
    student_email         varchar(320),
    programme_code        varchar(40),
    programme_name        varchar(200),
    level                 int,
    centre_id             uuid,
    centre_name           varchar(200),
    semester_id           uuid          not null,
    session_name          varchar(20)   not null,
    semester_number       int           not null,
    registration_id       uuid,
    reason_category       varchar(20),
    reason                varchar(1000) not null,
    attachment_key        varchar(400),
    attachment_name       varchar(300),
    attachment_size       bigint,
    attachment_type       varchar(100),
    drop_courses          text,          -- JSON [{courseId, code, title, units}]
    add_courses           text,
    units_before          int,
    units_after           int,
    register_by           date,
    decided_at            timestamptz,
    decided_by            uuid,
    decided_by_name       varchar(200),
    decided_by_role       varchar(30),
    decision_note         varchar(500),
    created_at            timestamptz   not null
);
create index requests_queue_idx on requests (tenant_id, status, kind, created_at desc);
create index requests_student_idx on requests (student_id, semester_id);
