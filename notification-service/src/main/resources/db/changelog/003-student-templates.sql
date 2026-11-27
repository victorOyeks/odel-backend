--liquibase formatted sql

--changeset odel:003-student-templates
-- B1 student emails. The welcome email's wording is owned per university by student-service, which renders it and passes
-- the finished subject and text through student.welcome. Codes: activation and password reset (email is the only channel).
insert into message_templates (id, tenant_id, key, channel, subject, body, updated_at) values
('10000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', 'student.welcome', 'EMAIL',
 '{{subject}}', '{{body}}', now()),
('10000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000001', 'student.activation_code', 'EMAIL',
 'Your {{universityName}} activation code: {{code}}',
 'Hello {{firstName}},

Your code to activate your {{universityName}} ODeL account is:

{{code}}

It works for {{minutes}} minutes. If you did not ask for it, you can ignore this email: nobody can use your account without the code.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-000000000001', 'student.password_reset_code', 'EMAIL',
 'Your {{universityName}} password reset code: {{code}}',
 'Hello {{firstName}},

Your code to choose a new password is:

{{code}}

It works for {{minutes}} minutes. If you did not ask for it, you can ignore this email: your password stays the same.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000001', 'student.password_reset_by_office', 'EMAIL',
 'Reset your {{universityName}} password',
 'Hello {{firstName}},

The ODeL office sent you a code to choose a new password:

{{code}}

Open this link, enter the code and choose your new password:
{{resetLink}}

The code works for {{minutes}} minutes.

{{universityName}} ODeL', now());

-- Delivery reports look up an email's job by the message id in its payload.
create index if not exists jobs_payload_id_idx on jobs ((payload->>'id'));
