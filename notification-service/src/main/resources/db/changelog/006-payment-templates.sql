--liquibase formatted sql

--changeset odel:006-payment-templates
-- B2 payments (platform fee). Email is the only outside channel; the bell carries the same news. Numbered 006: 005 is left for the
-- registration half of B2. Emails may carry attachments (the receipt PDF), stored with the message until it is sent.
alter table outbound_messages add column if not exists attachments jsonb;

insert into message_templates (id, tenant_id, key, channel, subject, body, updated_at) values
('10000000-0000-0000-0000-0000000b2a01', '00000000-0000-0000-0000-000000000001', 'payment.receipt', 'EMAIL',
 'Receipt: platform fee {{feeLabel}} ({{reference}})',
 'Hello {{firstName}},

We have received your platform fee for {{feeLabel}}.

Amount: {{amount}}
Paid on: {{paidAt}}
Method: {{method}}
Receipt number: {{reference}}
Paystack reference: {{gatewayReference}}

Your receipt is attached to this email, and you can download it again any time under Payments in the portal:
{{paymentsLink}}

Registration is now open to you.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2a02', '00000000-0000-0000-0000-000000000001', 'payment.receipt', 'IN_APP',
 'Payment received · {{feeLabel}}',
 'Your {{amount}} platform fee is paid (receipt {{reference}}). You can register your courses.', now()),
('10000000-0000-0000-0000-0000000b2a03', '00000000-0000-0000-0000-000000000001', 'payment.duplicate_refund', 'EMAIL',
 'You paid twice: {{amount}} is coming back to you',
 'Hello {{firstName}},

You paid the platform fee for {{feeLabel}} twice. Your fee was already paid ({{keptReference}}), so we have sent the second payment back.

Refunded payment: {{reference}} on {{paidAt}}
Amount: {{amount}}
Going back to: {{refundTo}}
Refund reference: {{refundReference}}
Expected in your account by: {{expectedBy}} (banks take 5 to 10 working days)

You do not need to do anything. Your fee stays paid and registration stays open.
See the details under Payments: {{paymentsLink}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2a04', '00000000-0000-0000-0000-000000000001', 'payment.duplicate_refund', 'IN_APP',
 'You paid twice: {{amount}} is coming back',
 'Refund {{refundReference}} to {{refundTo}}, by {{expectedBy}}. Your fee stays paid.', now()),
('10000000-0000-0000-0000-0000000b2a05', '00000000-0000-0000-0000-000000000001', 'payment.refunded', 'EMAIL',
 'Your refund of {{amount}} has been sent ({{refundReference}})',
 'Hello {{firstName}},

Paystack has sent your refund of {{amount}} for payment {{reference}} back to {{refundTo}}. Banks usually show it within 5 to 10
working days.

Refund reference: {{refundReference}}
{{feeNote}}

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2a06', '00000000-0000-0000-0000-000000000001', 'payment.reversed', 'EMAIL',
 'Your bank took back your platform fee for {{feeLabel}}',
 'Hello {{firstName}},

Your bank took back the {{amount}} platform fee you paid on {{paidAt}} (reference {{reference}}). Your fee for {{feeLabel}} is no longer
paid, so course registration and exams are locked again. Your registered courses are kept.

To unlock them, pay again in the portal:
{{paymentsLink}}

If you did not ask your bank for this, call the ODeL office{{officePhone}}.

{{universityName}} ODeL', now()),
('10000000-0000-0000-0000-0000000b2a07', '00000000-0000-0000-0000-000000000001', 'payment.reversed', 'IN_APP',
 'Payment reversed · {{feeLabel}}',
 'Your bank took back the {{amount}} paid on {{paidAt}}. Registration and exams are locked until you pay again.', now());
