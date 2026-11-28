--liquibase formatted sql

--changeset odel:009-staff-sign-in-templates
-- Staff sign-in per university (28 Nov 2026): Google Workspace, Microsoft 365 or a code emailed to the work address.
-- Staff emails stop saying "Google": the sender passes {{signInHow}}, e.g. "Sign in with your Microsoft work account
-- (your @wesleyuni.edu.ng address)". Numbered 009: 007 and 008 are taken by the content and profile work.
-- {{signInHow|...}} falls back to a neutral sentence if a sender built before this change leaves it out.
update message_templates set body = 'Dear {{fullName}},

You have been added to the {{universityName}} ODeL staff portal as {{roleLabel}}.

{{signInHow|Sign in with your work account}} here:
{{signInUrl}}

There is no separate password. The first time you sign in, you will set up two-step sign-in with an authenticator app.

{{universityName}} ODeL', updated_at = now()
where id = '10000000-0000-0000-0000-000000000001';

update message_templates set body = 'Dear {{fullName}},

The ODeL office has assigned you as {{what}} for the {{sessionName}} session.

You will find it in the staff portal. {{signInHow|Sign in with your work account}} here:
{{signInUrl}}

{{universityName}} ODeL', updated_at = now()
where id = '10000000-0000-0000-0000-0000000b1a01';

update message_templates set body = 'Dear {{fullName}},

{{resetBy}} reset your two-step sign-in for the {{universityName}} ODeL staff portal. You have been signed out everywhere, and your old
authenticator codes, backup codes and trusted browsers no longer work.

The next time you sign in, you will set up two-step sign-in again with your authenticator app. {{signInHow|Sign in with your work account}} here:
{{signInUrl}}

If you did not ask for this, tell the ODeL office straight away.

{{universityName}} ODeL', updated_at = now()
where id = '10000000-0000-0000-0000-0000000b1a03';

insert into message_templates (id, tenant_id, key, channel, subject, body, updated_at) values
('10000000-0000-0000-0000-0000000c5101', '00000000-0000-0000-0000-000000000001', 'staff.sign_in_code', 'EMAIL',
 'Your {{universityName}} staff sign-in code: {{code}}',
 'Dear {{fullName}},

Your code to sign in to the {{universityName}} ODeL staff portal is:

{{code}}

It works for {{minutes}} minutes. After it, you confirm it is you with your authenticator app as usual.

If you did not try to sign in, you can ignore this email: nobody can get in without the code and your authenticator app.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000c5102', '00000000-0000-0000-0000-000000000001', 'staff.sign_in_blocked', 'EMAIL',
 'You cannot sign in to {{universityName}}''s staff portal yet',
 'Dear {{fullName}},

Someone, probably you, asked for a code to sign in to the {{universityName}} ODeL staff portal with this address.

We did not send a code because {{reason}} {{nextStep}}

If it was not you, you can ignore this email.

{{universityName}} ODeL', now());
