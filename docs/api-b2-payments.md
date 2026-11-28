# API contract · B2 Payments (platform fee)

The payments half of B2 (Pay and register). The registration half is `docs/api-b2-registration.md`.
Conventions as in `docs/api-b0.md` (JSON, errors `{"error","message","fields"}`, lists `{"items","page","size","total"}`, CSRF header on
every non-GET, ISO-8601 times with offset; the university's zone is Africa/Lagos).

New service **payment-service** (`:9306`, schema `payment`, Swagger `/api-docs/payment`). The gateway routes to it:
`/api/v1/payments/**`, `/api/v1/fees/**`, `/api/v1/platform/payments/**` (before tenant-service's `/api/v1/platform/**`),
`/api/v1/dev/payments/**` (dev profile and fake mode only).

## The fee

- One platform fee **per student per semester**: ₦15,000 by default, set per university by the provider (console). Each semester of a
  session has its own fee, payment and receipt, and every fee is named `"2026/2027 · 1st semester"`.
- Fee status: `NOT_PAID` · `CONFIRMING` (a payment is waiting for the bank: "do not pay again") · `PAID` · `REFUNDED` (the payment that
  paid it was refunded) · `REVERSED` (the bank took the money back: registration and exams lock again, E-08).
  Registration and exams are open only when the status is `PAID`. Results are never held for the fee.
- The **current fee** is the active session's semester that has not ended yet (Africa/Lagos), else its last semester.
- Money: `amount` fields are naira (numbers, e.g. `15000`), each with a `…Label` ("₦15,000"). Stored in kobo.

### Who marks a fee paid
Only a **verified** Paystack `charge.success`: the webhook's HMAC-SHA512 signature must match, and the transaction is then verified with
Paystack's API (status `success`, amount and currency match). The browser returning from checkout never marks anything paid. A job
re-verifies (with Paystack's API) every payment still pending 30 minutes after it started, so a lost webhook cannot leave a student
waiting forever; that server-to-server check counts as verified too.

## Student (role STUDENT, on the university's host)

### `GET /payments/fees/current` (Home P-01, P-01b, P-01c, P-01e, E-07, E-08; R-08)
```json
{
  "id": "uuid",
  "semesterId": "uuid", "sessionId": "uuid", "sessionName": "2026/2027", "semesterNumber": 1, "semesterName": "1st semester",
  "label": "2026/2027 · 1st semester",
  "amount": 15000, "amountLabel": "₦15,000",
  "status": "NOT_PAID", "statusLabel": "Not paid",
  "message": "Pay your platform fee to register your courses and sit exams.",
  "locked": true,                        // registration and exams locked (anything but PAID)
  "openPayment": null,                   // the payment in progress, see Payment below (resume it instead of starting another)
  "paidPayment": null,                   // PAID: {"id","reference","paidAt","method","methodLabel","receiptUrl"}
  "duplicate": null,                     // E-07 paid twice: {"paymentId","reference","paidAt","amount","amountLabel","refund":{…}}
  "reversal": null,                      // E-08: {"paymentId","reference","reversedAt","message"}
  "methods": [{"key":"CARD","label":"Debit card"},{"key":"BANK_TRANSFER","label":"Bank transfer"},{"key":"USSD","label":"USSD"}],
  "ussdBanks": [{"key":"GTBANK","name":"GTBank","code":"*737#"},{"key":"UBA","name":"UBA","code":"*919#"},
                {"key":"STERLING","name":"Sterling","code":"*822#"},{"key":"ZENITH","name":"Zenith","code":"*966#"}],
  "ussdOtherBanks": "USSD works with GTBank, UBA, Sterling and Zenith. Another bank? Pay by card or transfer.",
  "actions": ["PAY"]                     // PAY | CHECK_STATUS | DOWNLOAD_RECEIPT | VIEW_PAYMENTS
}
```
Messages by status: `NOT_PAID` "Pay your platform fee to register your courses and sit exams." · `CONFIRMING` "We are waiting for your bank
to confirm your payment. Do not pay again." · `PAID` "Paid on Fri 4 Dec 2026. Registration is open to you." · `REFUNDED` "Your payment was
refunded. Pay again to register your courses and sit exams." · `REVERSED` "Your bank took back the ₦15,000 you paid on 4 Dec. Registration
and exams are locked. Pay again or call the ODeL office." `404 {"error":"no_session"}` "Your university has not opened a session yet."
(a student record is needed: `404 not_found` otherwise).

### `GET /payments/fees`
`{"items":[<fee as above>…]}`: every semester of the current session (and older fees the student has paid), newest semester first.

### `POST /payments` (P-02 Pay)  `{"semesterId":"uuid (optional: the current fee)","method":"CARD|BANK_TRANSFER|USSD","ussdBank":"GTBANK"}`
Starts a payment, or returns the one already open (**one open payment per student per semester**):
- `201` new payment, `200` the open payment (same method). Choosing another method while the open payment is still `PENDING` cancels it
  and starts a new one ("Pay another way"); while it is `CONFIRMING`, `409 payment_confirming`.
- `CARD` (card, and USSD or transfer inside Paystack's own page): `checkout.authorizationUrl` is Paystack's hosted checkout; the browser
  goes there (full page). Paystack returns to `{portal}/payments/return?reference=WU-PF-882134` (the web app's page, which calls
  `GET /payments/return`).
- `USSD` (P-04b, our page, Paystack Charge API): `ussd` = `{"bank":"GTBank","code":"*737*000*1234#","expiresAt"}`. Only GTBank, UBA,
  Sterling and Zenith: any other `ussdBank` → `400 ussd_bank_not_supported` "USSD works with GTBank, UBA, Sterling and Zenith. Pay by
  card or bank transfer instead."
- `BANK_TRANSFER` (P-04): `transfer` = `{"bankName":"Paystack-Titan","accountNumber":"9912345678","accountName":"…","amount":15000,
  "amountLabel":"₦15,000.00","expiresAt":"…+30 min","note":"Send exactly ₦15,000.00 in one transfer. This account works for 30 minutes."}`.
Errors: `409 already_paid` "Your fee for 2026/2027 · 1st semester is paid. Your receipt is under Payments." · `409 payment_confirming`
"We are waiting for your bank to confirm your last payment. Do not pay again." · `409 payments_not_ready` "Payments are not open for your
university yet. Try again later or call the ODeL office." (no subaccount yet) · `502 gateway_error` "We could not reach the payment
provider. Nothing was charged. Try again in a minute."

### Payment
```json
{
  "id":"uuid","feeId":"uuid","label":"2026/2027 · 1st semester","reference":"WU-PF-882134",
  "method":"CARD","methodLabel":"Debit card · GTBank card ending 4412",   // after payment: what was actually used
  "amount":15000,"amountLabel":"₦15,000",
  "status":"PENDING","statusLabel":"Waiting for you",
  "message":"…",                      // one sentence for the screen (P-05 … P-08)
  "startedAt":"…","paidAt":null,"expiresAt":"…",
  "checkout":{"authorizationUrl":"https://checkout.paystack.com/…"},  // CARD
  "ussd":null, "transfer":null,
  "failureReason":null,               // P-07: "Your bank declined the card (insufficient funds). Try another card or pay by transfer."
  "receiptUrl":null,                  // PAID: "/api/v1/payments/{id}/receipt.pdf"
  "refund":null                       // see below (E-07b)
}
```
Payment status: `PENDING` "Waiting for you" (started, nothing paid yet) · `CONFIRMING` "Confirming" (the student says it is done, or
Paystack says the bank is still processing) · `PAID` · `FAILED` (declined) · `CANCELLED` (closed or replaced) · `EXPIRED` (the 30-minute
transfer account or USSD code ran out, nothing arrived) · `REFUNDING` · `REFUNDED` · `REVERSED`.
`refund` = `{"reference":"RF-3312","amount":15000,"amountLabel":"₦15,000","status":"PENDING|PROCESSED|FAILED","startedAt","processedAt",
"expectedBy":"…+10 working days","to":"GTBank card ending 4412","reason":"You paid the platform fee twice"}`.

### `GET /payments/{id}` · `GET /payments/mine` (P-11, P-11b: every attempt, newest first, `{"items":[Payment…]}`)

### `POST /payments/{id}/sent` (P-04 "I have made the transfer", P-04b "I have dialled the code")
`PENDING` → `CONFIRMING`; returns the payment. Home then shows P-01e. Paid or failed payments are returned unchanged.

### `GET /payments/return?reference=WU-PF-882134` (the browser back from checkout) · `POST /payments/{id}/check` ("Check payment status", P-09)
Asks Paystack for the status **for display only**: declined → `FAILED` with `failureReason` (P-07); not paid and checkout closed →
stays `PENDING`, `message` "You closed the payment page before paying. Nothing was charged." (P-08); paid but the webhook not here yet →
`CONFIRMING` (P-05 "We are confirming your payment"); webhook already here → `PAID` (P-06). Never marks a fee paid.

### `POST /payments/{id}/cancel` ("Pay another way", only while `PENDING`) → the payment, `CANCELLED`.

### `GET /payments/{id}/receipt.pdf` (P-10, P-10b)
`application/pdf`, `Content-Disposition: attachment; filename="receipt-WU-PF-882134.pdf"`. The university's name and crest (PNG crests;
an SVG crest is left out of the PDF), student, matric number, programme and level, session, semester, amount, method, Paystack
reference, date paid, status, receipt number (= our reference). The student (own payments) and staff with the payments roles. `404`
for a payment that is not paid. The same PDF is attached to the receipt email.

## University staff (roles ODEL_ADMIN, ODEL_DIRECTOR, MANAGEMENT; the split is never shown here)

### `GET /fees/payments/summary?semesterId=` (A-01 cards)
```json
{"semester":{"id","label":"2026/2027 · 1st semester"},"amount":15000,"amountLabel":"₦15,000 per student",
 "paid":{"count":3012,"collected":45180000,"collectedLabel":"₦45,180,000 collected"},
 "confirming":{"count":41,"oldestStartedAt":"…"},"notPaid":{"count":359},
 "refunds":{"count":3,"amount":45000,"amountLabel":"₦45,000"},"reversed":{"count":1},"students":3412}
```

### `GET /fees/payments?semesterId=&q=&centre=AKR&status=PAID&method=CARD&from=2026-12-01&to=2026-12-31&page=0&size=20` (A-01)
One row per student (students who never paid included, as "Not paid"):
`{"items":[{"studentId","fullName","matricNumber","centre":{"code","name","shortName"},"programme":{"code","name"},"level",
"status":"PAID","statusLabel":"Paid","paidTwice":false,"method":"CARD","methodLabel":"Card","reference":"WU-PF-882134",
"date":"…","paymentId":"uuid"}],…}`.
`status` filter: `PAID, CONFIRMING, NOT_PAID, FAILED` (last attempt failed, not paid), `REFUNDED`, `REVERSED`, `PAID_TWICE`.
`q` matches name, matric number or reference; `from`/`to` filter on the payment date (Africa/Lagos days). Newest payment first,
students who never paid last (by matric number).

### `GET /fees/payments/export.csv` (same filters) → `student,matric_number,centre,programme,level,status,method,reference,date,amount`.

### `GET /fees/students/{studentId}?semesterId=` (A-02 a student's payment record)
`{"student":{…},"fee":<fee>,"attempts":[Payment…]}` (refund and reversal details included).

### `GET /fees/settings` → read-only fee and settlement account (see the provider section). The provider sets both.

## Provider console (role SUPER_ADMIN, console host)

Owner's decision (28 Nov 2026): **the provider enters each university's settlement account; the university can only view it.**
No university endpoint changes it.

### `GET /platform/payments/universities`
`{"items":[{"tenantId","name","amount":15000,"centreShare":3000,"providerShare":12000,"settlementAccount":<account>|null,"paymentsOpen":true}]}`
where `<account>` = `{"bankCode":"058","bankName":"Guaranty Trust Bank","accountNumberMasked":"••••4521","accountName":"WESLEY UNIVERSITY ODEL CENTRE",
"subaccountCode":"ACCT_…","since":"…","updatedBy":{"fullName":"Chuka Obi"}}`. `paymentsOpen` = a subaccount exists.

### `GET /platform/payments/universities/{tenantId}` → one item as above (SA-01b "Payments" card).

### `PUT /platform/payments/universities/{tenantId}` `{"amount":15000,"centreShare":3000}` (fee and the centre's flat share)
`400` with `fields`: amount 1,000 – 1,000,000; centre share 0 – amount. Audited `PAYMENT_SETTINGS_CHANGED` (old and new). A changed amount
applies to fees nobody has started paying. When a subaccount exists, Paystack's subaccount is not touched (the share is sent per
transaction).

### `GET /platform/payments/banks` → `{"items":[{"code":"058","name":"Guaranty Trust Bank"}…]}`
Nigerian banks from Paystack (`GET /bank?country=nigeria`, cached for a day; fake mode: a fixed list including Paystack's test bank).

### `POST /platform/payments/banks/resolve` `{"bankCode":"058","accountNumber":"0123454521"}` (the wizard's Verify)
→ `{"bankCode","bankName","accountNumberMasked":"••••4521","accountName":"WESLEY UNIVERSITY ODEL CENTRE","resolutionId":"uuid"}`.
`400` `fields.accountNumber` "Enter the 10-digit account number." · `422 {"error":"not_resolved"}` "The bank could not find that account.
Check the number and the bank." A POST so the number never sits in a URL or access log; the result is remembered for 15 minutes.

### `PUT /platform/payments/universities/{tenantId}/settlement-account` `{"resolutionId":"uuid","confirmedAccountName":"WESLEY UNIVERSITY ODEL CENTRE"}`
SA-04 step 3 "Rules and payments" and SA-01b. Saving needs a successful resolve in the last 15 minutes (`409 resolve_first` "Verify the
account number first.") and the provider confirming the name it returned (`400 fields.confirmedAccountName` "Confirm the account name
the bank returned."). Creates the Paystack subaccount (`POST /subaccount`), or updates the existing one (`PUT /subaccount/{code}`) on a
change, with business name = the university's name; stores the code per university → the item above. `502 gateway_error` with
Paystack's message when it refuses. Audited `PAYMENT_ACCOUNT_SET` / `PAYMENT_ACCOUNT_CHANGED` with who and when, old and new as
"bank ••••last 4" only (full numbers are never in the audit log or the logs: `accountNumber` / `account_number` are masked by
BodyLoggingFilter).

### University's read-only view (ODEL_ADMIN, ODEL_DIRECTOR, MANAGEMENT): `GET /fees/settings`
`{"amount":15000,"amountLabel":"₦15,000","paymentsOpen":true,"settlementAccount":{"bankName","accountNumberMasked":"••••4521","accountName","since"}|null}`
(no subaccount code, no split).

Dev: on first start (profile dev), any university with slug `wesley` and no account gets one automatically (devseed): fake mode a fake
`ACCT_FAKE…` code; paystack mode Paystack's test bank (`ODEL_PAYSTACK_TEST_BANK_CODE`, default `001`; `ODEL_PAYSTACK_TEST_ACCOUNT`,
default `0000000000`). The seed retries every 10 minutes until Wesley exists.

### `GET /platform/payments/settlement?from=2026-12-01&to=2026-12-31&tenantId=&page=&size=` (settlement report)
```json
{"from":"2026-12-01","to":"2026-12-31",
 "totals":{"payments":3012,"gross":45180000,"provider":36144000,"centre":9036000,"gatewayFees":978900,"providerNet":35165100,"refunded":45000},
 "universities":[{"tenantId","name","payments":3012,"gross":…,"provider":…,"centre":…,"gatewayFees":…,"providerNet":…,"refunded":…}],
 "items":[{"reference","university","matricNumber","label":"2026/2027 · 1st semester","paidAt","method","amount":15000,"provider":12000,"centre":3000,"gatewayFee":325,"providerNet":11675,"status":"PAID"}],
 "page":0,"size":50,"total":3012}
```
Every successful payment in the period (refunded and reversed ones included and marked). Paystack's charges are borne by the provider
(see Split), so `centre` is always the full ₦3,000.

## Split (Paystack subaccounts)

The provider's Paystack account is the main account; each university's ODeL centre is a subaccount (code stored per university). Every
transaction (hosted checkout and Charge API) is sent with `subaccount=<code>`, `transaction_charge=<amount − centre share>` (₦12,000,
the main account's flat share) and `bearer=account`: Paystack's charge (1.5% + ₦100, capped at ₦2,000: ₦325 on ₦15,000) comes out of
the provider's ₦12,000, so the university's centre always receives exactly ₦3,000 and the student pays exactly ₦15,000.

## Webhook

### `POST /payments/webhooks/paystack` (public: no session, no CSRF; header `x-paystack-signature` required)
- Signature: hex HMAC-SHA512 of the raw body with the secret key. Missing or wrong → `401 {"error":"bad_signature"}`, nothing stored.
- Idempotent: each event is stored once (key = event + reference/id + status); the same event again → `200`, nothing changes.
- `charge.success` → verified with Paystack (`GET /transaction/verify/{reference}`): amount, currency NGN and status `success` must
  match. Then the fee becomes `PAID` (receipt emailed with the PDF attached, bell item), or, when the fee was **already paid** by another
  payment, this payment is refunded automatically through Paystack (`REFUNDING`, E-07 email). An unknown reference is ignored (`200`).
- `refund.processed` → the refund is `PROCESSED`, the payment `REFUNDED` (refund email). If it was the payment that paid the fee, the fee
  becomes `REFUNDED` (locked). `refund.failed` → refund `FAILED` (the office is told in the audit log; Paystack retries).
- `charge.dispute.create` → noted on the payment. `charge.dispute.resolve` with the customer winning (`resolution` `merchant-accepted` or
  `auto-accepted`) → the payment is `REVERSED`; if it paid the fee, the fee becomes `REVERSED` and registration and exams lock again
  (E-08 email). Registered courses are kept (registration's rule).
- A transfer confirmed later is simply a later `charge.success`.

## Internal (`/internal/**`, `X-Internal-Key`, university in `X-Odel-Tenant-Id`)

### payment-service provides
- `GET /internal/payments/fee-status?studentId=&semesterId=` (or `userId=` instead of `studentId`; `semesterId` optional = current) →
  `{"studentId","semesterId","label":"2026/2027 · 1st semester","status":"PAID","paid":true,"paidAt","reference","amount":15000}`.
  A student with no fee row yet is `NOT_PAID`. For registration's fee block and, later, exam eligibility.
- `POST /internal/payments/fee-statuses` `{"semesterId","studentIds":[…]}` → `{"items":[<fee-status>…]}` (lists, A-07).
- `GET /internal/payments/summary?semesterId=` → `{"semesterId","paid":3012,"confirming":41,"refunded":0,"reversed":1,"paidStudentIds":[…]}`
  (dashboards; "paid, not registered" on A-07).

### payment-service uses
- academic-service `GET /internal/academic/semesters` (new) →
  `{"currentSessionId","currentSemesterId","items":[{"id","sessionId","sessionName","sessionStatus","number","name":"1st semester",
  "label":"2026/2027 · 1st semester","startsOn","endsOn","registration":{"opensOn","closesOn","lateClosesOn"}}]}`.
- student-service (new) `GET /internal/students/directory` → `{"items":[{"id","userId","matricNumber","fullName","firstName","email",
  "programme":{"code","name"},"level","levelLabel","centre":{"id","code","name","shortName"},"status"}]}`;
  `GET /internal/students/directory/{studentId}` and `GET /internal/students/directory/by-user/{userId}` → one item (404 if none).
- tenant-service `GET /internal/tenants/{id}` (name, colour, portal URL, `logoUrl` read from tenant-service for the crest) and (new)
  `GET /internal/tenants` → `[TenantView…]` for the provider's list.
- notification-service `POST /internal/messages` with templates (changelog `006-payment-templates.sql`): `payment.receipt` (email with the
  PDF attached + bell), `payment.duplicate_refund` (email + bell, E-07), `payment.refunded` (email), `payment.reversed` (email + bell,
  E-08). Messages may now carry `attachments: [{"fileName","contentType","base64"}]` (email only).

## Dev only (profile `dev` and `ODEL_PAYSTACK_MODE=fake`)

The fake gateway keeps its transactions in memory and posts **signed** webhooks to the payment-service itself (signed with
`ODEL_PAYSTACK_SECRET_KEY`, or `fake-paystack-secret` when unset).
- `GET /dev/payments/checkout/{reference}`: the stand-in for Paystack's checkout page (Pay, Decline, Close). Its links
  `GET /dev/payments/checkout/{reference}/{success|decline|close}` act and redirect to the callback.
- `POST /dev/payments/{reference}/simulate` `{"outcome":"SUCCESS|DECLINE|PENDING|REVERSE|REFUND_PROCESSED","channel":"card|bank_transfer|ussd","webhook":true}`
  → `{"reference","outcome","webhook":{"event","body","signature"}|null}` (the body and signature let a test post the same webhook again).
- `POST /dev/payments/recheck` `{"minutesAhead":31}` → runs the 30-minute re-check as if the clock were that far ahead → `{"checked":2,"paid":1}`.

## Audit actions
`PAYMENT_STARTED`, `PAYMENT_PAID`, `PAYMENT_FAILED`, `PAYMENT_DUPLICATE_REFUND_STARTED`, `PAYMENT_REFUNDED`, `PAYMENT_REVERSED`,
`PAYMENT_SETTINGS_CHANGED`, `PAYMENT_ACCOUNT_SET`, `PAYMENT_ACCOUNT_CHANGED` (university's audit log, type `OTHER`).

## Environment
`ODEL_PAYSTACK_MODE` (`fake` | `paystack`; default `fake`), `ODEL_PAYSTACK_SECRET_KEY` (signature and API), `ODEL_PAYSTACK_PUBLIC_KEY`
(unused: hosted checkout), `ODEL_PAYSTACK_BASE_URL` (default `https://api.paystack.co`), `ODEL_PAYMENT_DEFAULT_FEE` (15000),
`ODEL_PAYMENT_CENTRE_SHARE` (3000), `ODEL_PAYMENTS_URL` (other services → payment-service, default `http://127.0.0.1:9306`),
dev seed: `ODEL_PAYSTACK_TEST_BANK_CODE` / `ODEL_PAYSTACK_TEST_ACCOUNT` / `ODEL_PAYMENT_SEED_SLUG` (default `wesley`) for Wesley's test-mode subaccount.
