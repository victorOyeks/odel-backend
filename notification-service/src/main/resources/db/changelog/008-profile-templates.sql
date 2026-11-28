--liquibase formatted sql

--changeset odel:008-profile-templates
-- B3 My profile (L-14 … L-14j): the email-change code, the notices for every change to the student's contact details and account,
-- corrections and photos sent to, approved or declined by the ODeL office, and the bell for the office. Email only, no SMS.
-- Numbered 008; the content half of B3 takes 007.
insert into message_templates (id, tenant_id, key, channel, subject, body, updated_at) values
('10000000-0000-0000-0000-000000b3f001', '00000000-0000-0000-0000-000000000001', 'student.email_change_code', 'EMAIL',
 'Your code to confirm this email: {{code}}',
 'Dear {{firstName}},

You asked to use this address for your {{universityName}} ODeL account. Enter this code on the profile page:

{{code}}

It works for {{minutes}} minutes. Nothing changes until you enter it.

If you did not ask for this, ignore this email.

{{universityName}} ODeL', now()),

('10000000-0000-0000-0000-000000b3f002', '00000000-0000-0000-0000-000000000001', 'student.email_changed_old', 'EMAIL',
 'Your email address was changed',
 'Dear {{firstName}},

The email on your {{universityName}} ODeL account was changed to {{newEmail}} on {{when}}. Receipts, codes and announcements now go there, and you sign in with it.

If this was not you, contact the ODeL office at once: they can lock your account while they check.

{{universityName}} ODeL', now()),

('10000000-0000-0000-0000-000000b3f003', '00000000-0000-0000-0000-000000000001', 'student.email_changed', 'EMAIL',
 'This is now your email for {{universityName}} ODeL',
 'Dear {{firstName}},

Your email was changed to this address on {{when}}. From now on receipts, codes and announcements come here, and you sign in with it.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000b3f004', '00000000-0000-0000-0000-000000000001', 'student.email_changed', 'IN_APP',
 'Email changed',
 'Your email is now {{newEmail}}.', now()),

('10000000-0000-0000-0000-000000b3f005', '00000000-0000-0000-0000-000000000001', 'student.contact_changed', 'EMAIL',
 'Your contact details were changed',
 'Dear {{firstName}},

These details on your {{universityName}} ODeL record were changed by {{by}} on {{when}}:

{{changes}}

If this was not you, change your password on the profile page and contact the ODeL office.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000b3f006', '00000000-0000-0000-0000-000000000001', 'student.contact_changed', 'IN_APP',
 'Contact details changed',
 '{{changes}}', now()),

('10000000-0000-0000-0000-000000b3f007', '00000000-0000-0000-0000-000000000001', 'student.password_changed', 'EMAIL',
 'Your password was changed',
 'Dear {{firstName}},

Your {{universityName}} ODeL password was changed on {{when}}. {{signedOut}}

If this was not you, reset your password now with Forgot password on the sign-in page, and contact the ODeL office.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000b3f008', '00000000-0000-0000-0000-000000000001', 'student.password_changed', 'IN_APP',
 'Password changed',
 'Your password was changed on {{when}}.', now()),

('10000000-0000-0000-0000-000000b3f009', '00000000-0000-0000-0000-000000000001', 'student.recovery_codes_made', 'EMAIL',
 'You made new recovery codes',
 'Dear {{firstName}},

You made 8 new recovery codes on {{when}}. Your old codes no longer work. Keep the new ones somewhere safe, away from your phone: each one lets you reset your password if you cannot open your email.

If this was not you, change your password on the profile page and contact the ODeL office.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000b3f010', '00000000-0000-0000-0000-000000000001', 'student.recovery_codes_made', 'IN_APP',
 'New recovery codes',
 'Your old recovery codes no longer work.', now()),

('10000000-0000-0000-0000-000000b3f011', '00000000-0000-0000-0000-000000000001', 'student.recovery_code_used', 'EMAIL',
 'A recovery code was used on your account',
 'Dear {{firstName}},

A recovery code was used to reset your {{universityName}} ODeL password on {{when}}. You have {{codesLeft}} codes left.

If this was not you, contact the ODeL office at once.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000b3f012', '00000000-0000-0000-0000-000000000001', 'student.recovery_code_used', 'IN_APP',
 'Recovery code used',
 'You have {{codesLeft}} recovery codes left.', now()),

('10000000-0000-0000-0000-000000b3f013', '00000000-0000-0000-0000-000000000001', 'student.request_received', 'EMAIL',
 'We got your request',
 'Dear {{firstName}},

The ODeL office has your request about {{what}}. Your record stays as it is until they approve it. We will email you when they decide.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000b3f014', '00000000-0000-0000-0000-000000000001', 'student.request_received', 'IN_APP',
 'Request sent to the ODeL office',
 'About {{what}}. We will tell you when they decide.', now()),

('10000000-0000-0000-0000-000000b3f015', '00000000-0000-0000-0000-000000000001', 'student.request_approved', 'EMAIL',
 'Your request was approved',
 'Dear {{firstName}},

{{decidedBy}} approved {{what}}. {{result}}

{{note}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000b3f016', '00000000-0000-0000-0000-000000000001', 'student.request_approved', 'IN_APP',
 'Request approved',
 '{{result}}', now()),
('10000000-0000-0000-0000-000000b3f017', '00000000-0000-0000-0000-000000000001', 'student.request_approved', 'PUSH',
 'Request approved',
 '{{result}}', now()),

('10000000-0000-0000-0000-000000b3f018', '00000000-0000-0000-0000-000000000001', 'student.request_declined', 'EMAIL',
 'Your request was not approved',
 'Dear {{firstName}},

{{decidedBy}} did not approve {{what}}.

Reason: {{note}}

Your record has not changed. You can send a new request from your profile page with the right document.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-000000b3f019', '00000000-0000-0000-0000-000000000001', 'student.request_declined', 'IN_APP',
 'Request not approved',
 '{{decidedBy}}: {{note}}', now()),
('10000000-0000-0000-0000-000000b3f020', '00000000-0000-0000-0000-000000000001', 'student.request_declined', 'PUSH',
 'Request not approved',
 '{{note}}', now()),

('10000000-0000-0000-0000-000000b3f021', '00000000-0000-0000-0000-000000000001', 'staff.profile_request_new', 'IN_APP',
 'Profile change to review',
 '{{studentName}} ({{matricNumber}}) asked for {{what}}.', now());
