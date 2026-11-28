-- One database, one schema per service (Liquibase writes its changelog table into the service's schema).
create schema if not exists app;
create schema if not exists identity;
create schema if not exists tenant;
create schema if not exists notification;
create schema if not exists academic;
create schema if not exists student;
create schema if not exists payment;
create schema if not exists registration;
create schema if not exists content;
