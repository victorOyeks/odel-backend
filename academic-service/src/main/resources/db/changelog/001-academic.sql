--liquibase formatted sql

--changeset odel:001-academic
-- Academic setup (B1): sessions and semesters, faculties, departments and programmes, the course catalogue, study centres and
-- rooms, staffing per session, grading rules (versioned) and the structure/catalogue CSV imports. Every row carries the university.

create table sessions (
    id         uuid primary key,
    tenant_id  uuid        not null,
    name       varchar(20) not null,
    status     varchar(20) not null check (status in ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    starts_on  date,
    ends_on    date,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    created_by uuid
);
create unique index sessions_tenant_name_uq on sessions (tenant_id, name);
-- Only one active session per university.
create unique index sessions_one_active_uq on sessions (tenant_id) where status = 'ACTIVE';

create table semesters (
    id                    uuid primary key,
    tenant_id             uuid not null,
    session_id            uuid not null references sessions (id) on delete cascade,
    number                int  not null check (number in (1, 2)),
    starts_on             date,
    ends_on               date,
    reg_opens_on          date,
    reg_closes_on         date,
    late_reg_closes_on    date
);
create unique index semesters_session_number_uq on semesters (session_id, number);

create table faculties (
    id         uuid primary key,
    tenant_id  uuid         not null,
    name       varchar(200) not null,
    code       varchar(20)  not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);
create unique index faculties_code_uq on faculties (tenant_id, upper(code));
create unique index faculties_name_uq on faculties (tenant_id, lower(name));

create table departments (
    id         uuid primary key,
    tenant_id  uuid         not null,
    faculty_id uuid         not null references faculties (id),
    name       varchar(200) not null,
    code       varchar(20)  not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);
create unique index departments_code_uq on departments (tenant_id, upper(code));
create unique index departments_name_uq on departments (faculty_id, lower(name));

create table programmes (
    id                    uuid primary key,
    tenant_id             uuid         not null,
    department_id         uuid         not null references departments (id),
    name                  varchar(200) not null,
    code                  varchar(30)  not null,
    degree                varchar(40)  not null,
    duration_years        int          not null check (duration_years between 1 and 7),
    entry_levels          varchar(60)  not null,
    min_units_to_graduate int,
    status                varchar(20)  not null check (status in ('ACTIVE', 'CLOSED')),
    created_at            timestamptz  not null,
    updated_at            timestamptz  not null
);
create unique index programmes_code_uq on programmes (tenant_id, upper(code));
create unique index programmes_name_uq on programmes (department_id, lower(name));

create table courses (
    id         uuid primary key,
    tenant_id  uuid         not null,
    code       varchar(20)  not null,  -- stored as "CSC 201"
    title      varchar(200) not null,
    units      int          not null check (units between 1 and 12),
    level      int          not null check (level between 100 and 900),
    semester   int          not null check (semester in (1, 2)),
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);
create unique index courses_code_uq on courses (tenant_id, upper(replace(code, ' ', '')));

create table course_programmes (
    id           uuid primary key,
    tenant_id    uuid        not null,
    course_id    uuid        not null references courses (id) on delete cascade,
    programme_id uuid        not null references programmes (id),
    kind         varchar(20) not null check (kind in ('COMPULSORY', 'ELECTIVE'))
);
create unique index course_programmes_uq on course_programmes (course_id, programme_id);
create index course_programmes_programme_idx on course_programmes (programme_id);

create table centres (
    id         uuid primary key,
    tenant_id  uuid         not null,
    name       varchar(200) not null,
    code       varchar(10)  not null,
    address    varchar(300),
    state      varchar(40),
    phone      varchar(30),
    created_at timestamptz  not null,
    updated_at timestamptz  not null
);
create unique index centres_code_uq on centres (tenant_id, upper(code));
create unique index centres_name_uq on centres (tenant_id, lower(name));

create table rooms (
    id         uuid primary key,
    tenant_id  uuid         not null,
    centre_id  uuid         not null references centres (id) on delete cascade,
    name       varchar(100) not null,
    seats      int          not null check (seats between 1 and 2000),
    computers  int          not null check (computers >= 0),
    created_at timestamptz  not null
);
create unique index rooms_name_uq on rooms (centre_id, lower(name));

-- Who teaches a course (lecturer, a facilitator per centre, exam officer) and who coordinates a centre, per session.
create table staff_assignments (
    id         uuid primary key,
    tenant_id  uuid        not null,
    session_id uuid        not null references sessions (id) on delete cascade,
    kind       varchar(20) not null check (kind in ('LECTURER', 'FACILITATOR', 'EXAM_OFFICER', 'COORDINATOR')),
    course_id  uuid references courses (id) on delete cascade,
    centre_id  uuid references centres (id) on delete cascade,
    user_id    uuid        not null,
    created_at timestamptz not null,
    created_by uuid
);
create index staff_assignments_session_idx on staff_assignments (session_id, kind);
create index staff_assignments_user_idx on staff_assignments (user_id);
create unique index staff_assignments_course_uq on staff_assignments (session_id, kind, course_id) where kind in ('LECTURER', 'EXAM_OFFICER');
create unique index staff_assignments_facilitator_uq on staff_assignments (session_id, course_id, centre_id) where kind = 'FACILITATOR';
create unique index staff_assignments_coordinator_uq on staff_assignments (session_id, centre_id) where kind = 'COORDINATOR';

-- Grading rules, one row per saved version. programme_id null = the university's default. inherit = the programme follows the default again.
create table grading_rules (
    id                   uuid primary key,
    tenant_id            uuid         not null,
    programme_id         uuid references programmes (id),
    version              int          not null,
    effective_session_id uuid references sessions (id),
    inherit              boolean      not null default false,
    ca_weight            int          not null,
    exam_weight          int          not null,
    pass_mark            int          not null,
    scale                int          not null,
    round_totals         boolean      not null,
    bands                text         not null,  -- JSON list of bands
    standing             text         not null,  -- JSON: probation, withdrawal, degree classes
    created_at           timestamptz  not null,
    created_by           uuid,
    created_by_name      varchar(200)
);
create unique index grading_rules_version_uq on grading_rules (tenant_id, coalesce(programme_id, '00000000-0000-0000-0000-000000000000'), version);

-- Structure and catalogue CSV imports: checked first (nothing saved), then confirmed.
create table imports (
    id            uuid primary key,
    tenant_id     uuid         not null,
    kind          varchar(20)  not null check (kind in ('STRUCTURE', 'CATALOGUE')),
    file_name     varchar(300) not null,
    file_key      varchar(300) not null,
    status        varchar(20)  not null check (status in ('CHECKED', 'IMPORTED', 'CANCELLED')),
    total_rows    int          not null,
    ready_rows    int          not null,
    problem_rows  int          not null,
    problems      text         not null,  -- JSON [{row, message}]
    problem_csv   text,                   -- the problem rows with a "problem" column
    payload       text         not null,  -- JSON: the ready rows, saved on confirm
    imported      text,                   -- JSON counts after confirm
    created_at    timestamptz  not null,
    created_by    uuid,
    confirmed_at  timestamptz
);
create index imports_tenant_idx on imports (tenant_id, created_at desc);
