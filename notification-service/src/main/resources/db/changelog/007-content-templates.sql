--liquibase formatted sql

--changeset odel:007-content-templates
-- B3 teach and learn (content-service): course announcements to students (email, bell, push), and the uploader's notices about
-- their videos (ready: bell; could not be prepared: email and bell). Numbered 007; B3's profile half takes 008.
insert into message_templates (id, tenant_id, key, channel, subject, body, updated_at) values
('10000000-0000-0000-0000-0000000b3c01', '00000000-0000-0000-0000-000000000001', 'content.announcement', 'EMAIL',
 '{{courseCode}}: {{title}}',
 'Dear {{firstName}},

{{author}} posted an announcement in {{courseCode}} {{courseTitle}}:

{{title}}

{{body}}

Read it on the course page:
{{announcementUrl}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b3c02', '00000000-0000-0000-0000-000000000001', 'content.announcement', 'IN_APP',
 '{{courseCode}} · {{title}}',
 '{{body}}', now()),
('10000000-0000-0000-0000-0000000b3c03', '00000000-0000-0000-0000-000000000001', 'content.announcement', 'PUSH',
 '{{courseCode}}: {{title}}',
 '{{author}} posted an announcement. Tap to read it.', now()),

('10000000-0000-0000-0000-0000000b3c04', '00000000-0000-0000-0000-000000000001', 'content.video_ready', 'IN_APP',
 '{{title}} is ready',
 '{{courseCode}} week {{weekNumber}}: students can now watch it at 240p, 360p or 480p, or listen to the audio.', now()),

('10000000-0000-0000-0000-0000000b3c05', '00000000-0000-0000-0000-000000000001', 'content.video_failed', 'EMAIL',
 '{{title}} could not be prepared',
 'Hello,

The video "{{title}}" in {{courseCode}} week {{weekNumber}} could not be prepared for students.

{{reason}}

Open the week to upload it again:
{{contentUrl}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b3c06', '00000000-0000-0000-0000-000000000001', 'content.video_failed', 'IN_APP',
 '{{title}} could not be prepared',
 '{{courseCode}} week {{weekNumber}}: upload it again. {{reason}}', now());
