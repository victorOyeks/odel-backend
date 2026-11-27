--liquibase formatted sql

--changeset odel:004-staff-templates
-- B1 academic setup: a staff member is told when they are assigned to a course or centre (W-16b "Email them that they have been
-- assigned"), and when an ODeL admin resets their two-step sign-in. Numbered 004 so the students half can take 003.
insert into message_templates (id, tenant_id, key, channel, subject, body, updated_at) values
('10000000-0000-0000-0000-0000000b1a01', '00000000-0000-0000-0000-000000000001', 'staff.assigned', 'EMAIL',
 'You have been assigned: {{what}}',
 'Dear {{fullName}},

The ODeL office has assigned you as {{what}} for the {{sessionName}} session.

You will find it in the staff portal when you sign in with your university Google account:
{{signInUrl}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b1a02', '00000000-0000-0000-0000-000000000001', 'staff.assigned', 'IN_APP',
 'New assignment for {{sessionName}}',
 'You are {{what}}.', now()),
('10000000-0000-0000-0000-0000000b1a03', '00000000-0000-0000-0000-000000000001', 'staff.two_step_reset', 'EMAIL',
 'Your two-step sign-in was reset',
 'Dear {{fullName}},

{{resetBy}} reset your two-step sign-in for the {{universityName}} ODeL staff portal. You have been signed out everywhere, and your old
authenticator codes, backup codes and trusted browsers no longer work.

The next time you sign in with your university Google account, you will set up two-step sign-in again with your authenticator app:
{{signInUrl}}

If you did not ask for this, tell the ODeL office straight away.

{{universityName}} ODeL', now());
