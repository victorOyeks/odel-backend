--liquibase formatted sql

--changeset odel:005-registration-templates
-- B2 course registration (registration-service) and the centre move (student-service, A-06). Email and bell for each.
-- Numbered 005; the payments half of B2 takes 006.
insert into message_templates (id, tenant_id, key, channel, subject, body, updated_at) values
('10000000-0000-0000-0000-0000000b2e01', '00000000-0000-0000-0000-000000000001', 'registration.confirmed', 'EMAIL',
 'You are registered for {{semesterLabel}}',
 'Dear {{firstName}},

You are registered for {{semesterLabel}}: {{courseCount}} courses, {{totalUnits}} units.

{{courseList}}

Your registration slip is {{slipNumber}}. Download it here:
{{slipUrl}}

To change a course, ask for a course change in the portal by {{changesCloseOn}}. Your centre coordinator decides.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2e02', '00000000-0000-0000-0000-000000000001', 'registration.confirmed', 'IN_APP',
 'Registered for {{semesterLabel}}',
 '{{courseCount}} courses, {{totalUnits}} units. Slip {{slipNumber}}.', now()),

('10000000-0000-0000-0000-0000000b2e03', '00000000-0000-0000-0000-000000000001', 'registration.late_approved', 'EMAIL',
 'Your late registration was approved',
 'Dear {{firstName}},

{{decidedBy}} approved your request for late registration for {{semesterLabel}} on {{decidedOn}}.

Register your courses by {{registerBy}}:
{{registerUrl}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2e04', '00000000-0000-0000-0000-000000000001', 'registration.late_approved', 'IN_APP',
 'Late registration approved',
 'Register your courses by {{registerBy}}.', now()),

('10000000-0000-0000-0000-0000000b2e05', '00000000-0000-0000-0000-000000000001', 'registration.late_declined', 'EMAIL',
 'Your late registration was not approved',
 'Dear {{firstName}},

Your request for late registration for {{semesterLabel}} was not approved.

{{decidedBy}}: {{note}}

You can send a new request with the right document while late registration is open:
{{requestUrl}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2e06', '00000000-0000-0000-0000-000000000001', 'registration.late_declined', 'IN_APP',
 'Late registration not approved',
 '{{decidedBy}}: {{note}}', now()),

('10000000-0000-0000-0000-0000000b2e07', '00000000-0000-0000-0000-000000000001', 'registration.change_approved', 'EMAIL',
 'Your course change was approved',
 'Dear {{firstName}},

{{decidedBy}} approved your course change for {{semesterLabel}} ({{changeSummary}}). You now have {{totalUnits}} units.

Your new registration slip:
{{slipUrl}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2e08', '00000000-0000-0000-0000-000000000001', 'registration.change_approved', 'IN_APP',
 'Course change approved',
 '{{changeSummary}}. Your new slip is ready.', now()),

('10000000-0000-0000-0000-0000000b2e09', '00000000-0000-0000-0000-000000000001', 'registration.change_declined', 'EMAIL',
 'Your course change was not approved',
 'Dear {{firstName}},

Your course change for {{semesterLabel}} ({{changeSummary}}) was not approved. Your courses stay as they were.

{{decidedBy}}: {{note}}

You can send a new request in the portal:
{{requestUrl}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2e10', '00000000-0000-0000-0000-000000000001', 'registration.change_declined', 'IN_APP',
 'Course change not approved',
 '{{decidedBy}}: {{note}}', now()),

('10000000-0000-0000-0000-0000000b2e11', '00000000-0000-0000-0000-000000000001', 'registration.request_new', 'EMAIL',
 '{{kindLabel}} request from {{studentName}}',
 'Dear {{fullName}},

{{studentName}} ({{matricNumber}}, {{centreName}}) asked for {{kindLabel}}: {{summary}}

Approve or decline it in the staff portal:
{{queueUrl}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2e12', '00000000-0000-0000-0000-000000000001', 'registration.request_new', 'IN_APP',
 'New {{kindLabel}} request',
 '{{studentName}} · {{summary}}', now()),

('10000000-0000-0000-0000-0000000b2e13', '00000000-0000-0000-0000-000000000001', 'student.centre_moved', 'EMAIL',
 'You are moving to {{toCentre}}',
 'Dear {{firstName}},

The ODeL office has moved you from {{fromCentre}} to {{toCentre}}, from {{effectiveOn}}. Your tutorials and exams take place at
{{toCentre}} from that day, and your requests go to its coordinator.

Reason: {{reason}}

Questions? Call {{centrePhone}}.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2e14', '00000000-0000-0000-0000-000000000001', 'student.centre_moved', 'IN_APP',
 'Your study centre is changing',
 'From {{effectiveOn}} you are at {{toCentre}}.', now()),

('10000000-0000-0000-0000-0000000b2e15', '00000000-0000-0000-0000-000000000001', 'staff.centre_move', 'EMAIL',
 '{{studentName}} {{direction}}',
 'Dear {{fullName}},

{{studentName}} ({{matricNumber}}) {{direction}}: the ODeL office has moved them from {{fromCentre}} to {{toCentre}}, from
{{effectiveOn}}. Their open registration requests now go to the {{toCentre}} coordinator.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2e16', '00000000-0000-0000-0000-000000000001', 'staff.centre_move', 'IN_APP',
 'Student moving centre',
 '{{studentName}} {{direction}} from {{effectiveOn}}.', now());
