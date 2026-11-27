--liquibase formatted sql

--changeset odel:003-student-accounts
-- B1 students: PASSWORD accounts with role STUDENT, created by student-service when a portal file is imported.
-- They sign in with their matric number (or email) and a password; some have no email on record yet.
alter table users alter column email drop not null;
alter table users add column matric_number       varchar(40);
alter table users add column student_id          uuid;
alter table users add column password_hash       varchar(100);
alter table users add column password_changed_at timestamptz;
alter table users add column consent_version     varchar(20);
alter table users add column consent_at          timestamptz;
alter table users add column failed_sign_ins     int not null default 0;
alter table users add column locked_until        timestamptz;
create unique index users_tenant_matric_uq on users (tenant_id, matric_number) where matric_number is not null;
