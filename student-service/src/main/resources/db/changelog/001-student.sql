--liquibase formatted sql

--changeset odel:001-student
-- Students (B1): records imported from the university portal's CSV, their status history, the imports themselves,
-- and the welcome emails. Programme and centre are kept by code with the names at import time (academic-service owns them).

create table students (
    id                       uuid primary key,
    tenant_id                uuid         not null,
    user_id                  uuid,
    matric_number            varchar(40)  not null,
    surname                  varchar(100) not null,
    first_name               varchar(100) not null,
    other_names              varchar(150),
    email                    varchar(320),
    phone                    varchar(30),
    gender                   varchar(10),
    date_of_birth            date,
    programme_id             uuid,
    programme_code           varchar(40)  not null,
    programme_name           varchar(200) not null,
    level                    int          not null,
    centre_id                uuid,
    centre_code              varchar(40)  not null,
    centre_name              varchar(200) not null,
    centre_short_name        varchar(100),
    entry_session            varchar(20),
    status                   varchar(20)  not null check (status in ('IMPORTED', 'INVITED', 'ACTIVE', 'SUSPENDED')),
    status_before_suspension varchar(20),
    welcome_sent_at          timestamptz,
    activated_at             timestamptz,
    consent_version          varchar(20),
    consent_at               timestamptz,
    import_id                uuid,
    created_at               timestamptz  not null,
    updated_at               timestamptz  not null
);
create unique index students_tenant_matric_uq on students (tenant_id, matric_number);
create index students_tenant_status_idx on students (tenant_id, status);
create index students_user_idx on students (user_id);
create index students_tenant_name_idx on students (tenant_id, surname, first_name);

create table student_status_history (
    id            uuid primary key,
    tenant_id     uuid         not null,
    student_id    uuid         not null,
    from_status   varchar(20),
    to_status     varchar(20)  not null,
    reason        varchar(300),
    actor_user_id uuid,
    actor_name    varchar(200),
    created_at    timestamptz  not null
);
create index student_status_history_student_idx on student_status_history (student_id, created_at);

create table imports (
    id               uuid primary key,
    tenant_id        uuid         not null,
    file_key         varchar(300) not null,
    file_name        varchar(300) not null,
    status           varchar(20)  not null check (status in ('CHECKED', 'IMPORTED')),
    total_rows       int          not null,
    ready_count      int          not null,
    new_count        int          not null,
    updated_count    int          not null,
    unchanged_count  int          not null,
    error_count      int          not null,
    warning_count    int          not null,
    checked_at       timestamptz  not null,
    checked_by       uuid,
    checked_by_name  varchar(200),
    imported_at      timestamptz,
    imported_by      uuid,
    imported_by_name varchar(200)
);
create index imports_tenant_idx on imports (tenant_id, imported_at desc);

create table import_problems (
    id            uuid primary key,
    tenant_id     uuid         not null,
    import_id     uuid         not null,
    row_number    int          not null,
    matric_number varchar(60),
    name          varchar(300),
    severity      varchar(10)  not null check (severity in ('ERROR', 'WARNING')),
    field         varchar(40),
    message       varchar(500) not null
);
create index import_problems_import_idx on import_problems (import_id, severity, row_number);

create table welcome_templates (
    tenant_id       uuid primary key,
    subject         varchar(300) not null,
    body            text         not null,
    updated_at      timestamptz  not null,
    updated_by      uuid,
    updated_by_name varchar(200)
);

create table welcome_sends (
    id                uuid primary key,
    tenant_id         uuid         not null,
    kind              varchar(20)  not null check (kind in ('ALL', 'SINGLE', 'REMINDER')),
    status            varchar(20)  not null check (status in ('RUNNING', 'DONE')),
    subject           varchar(300) not null,
    body              text         not null,
    remind_after_days int,
    reminder_due_at   timestamptz,
    reminder_sent_at  timestamptz,
    parent_send_id    uuid,
    total_students    int          not null,
    skipped_active    int          not null default 0,
    requested_by      uuid,
    requested_by_name varchar(200),
    started_at        timestamptz  not null,
    finished_at       timestamptz
);
create index welcome_sends_tenant_idx on welcome_sends (tenant_id, started_at desc);
create index welcome_sends_running_idx on welcome_sends (status) where status = 'RUNNING';

create table welcome_deliveries (
    id         uuid primary key,
    tenant_id  uuid         not null,
    send_id    uuid         not null,
    student_id uuid         not null,
    email      varchar(320),
    status     varchar(30)  not null check (status in ('PENDING', 'QUEUED', 'SENT', 'FAILED', 'SKIPPED_NO_EMAIL', 'SKIPPED_TEST_ADDRESS')),
    message_id uuid,
    reason     varchar(500),
    updated_at timestamptz  not null
);
create index welcome_deliveries_send_idx on welcome_deliveries (send_id, status);
create index welcome_deliveries_open_idx on welcome_deliveries (status) where status in ('PENDING', 'QUEUED');
