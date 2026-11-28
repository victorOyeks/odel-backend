# API contract · B3 Teach and learn (content-service)

The lecturer builds a course week by week (videos, audio, PDFs, slides converted to PDF, links, text pages), posts
announcements and sees each student's progress; the student opens a registered course, a week, watches at 360p or listens
(audio only), reads the slides, and their progress is kept. Design: staff T-01 … T-08 (K, K2, L), student L-01 … L-13 and E-10,
E-11, E-16 (J, J2, K, L, L2). Offline downloads are **not** in B3 (owner, V1): streaming only.

Same conventions as `docs/api-b0.md`: paths start with `/api/v1`, `X-Odel-Host` on every call, CSRF header on every non-GET,
errors `{"error","message","fields"}`, lists `{"items",…}`, dates `YYYY-MM-DD` (calendar days in Africa/Lagos), times ISO-8601,
sizes in bytes with a ready-made label ("1.2 MB"). Ids from another university → `404`.

## Service and routes

| Service | Port | Schema | Gateway prefixes |
|---|---|---|---|
| `content-service` (new) | 9308 | `content` | `/api/v1/content/**`, `/api/v1/dev/content/**` (dev profile only), `/api-docs/content` |
| `academic-service` | 9304 | `academic` | no new public route; new internal `GET /internal/academic/teaching` (below) |
| `registration-service` | 9307 | `registration` | no new public route; new internal `GET /internal/registration/courses/{courseId}/students` (below) |
| `notification-service` | 9303 | `notification` | changelog `007-content-templates.sql` |

`POST /api/v1/content/webhooks/bunny` is public (no session, no CSRF): see "Bunny's webhook".

## Words

- **Offering**: one course in one semester (CSC 201 in 2026/2027 · 1st semester). Its weeks, items, announcements and progress
  belong to it. Created the first time anyone opens the course for that semester (the course's `semester` number picks the semester
  of the session).
- **Week**: week 1…n with a title, the day it opens to students (`opensOn`, may be empty after a copy: "No open date"), a note to
  students and a hidden switch. A week is open when `opensOn` ≤ today and it is not hidden.
- **Item**: an ordered entry in a week: `VIDEO`, `AUDIO`, `PDF`, `SLIDES` (PowerPoint or ODP, converted to PDF),
  `NOTES` (Word/ODT notes converted to PDF), `LINK`, `PAGE` (text). Each can be hidden, moved, reordered, renamed, replaced, deleted.

## Who may call

| Who | What |
|---|---|
| `STUDENT` | `/content/me/**`: only offerings of courses they are **registered** for in that semester (registration-service). Anyone else asking → `403 not_registered` "You are not registered for CSC 201 this semester. Register the course first, or ask your study centre." |
| `LECTURER` | edits the offerings of courses they lecture in that session (academic Staffing); reads the ones they facilitate |
| `ODEL_ADMIN` | edits every offering |
| `ODEL_DIRECTOR`, `MANAGEMENT` | read only, every offering |
| `CENTRE_COORDINATOR` | read only; the progress table shows only the students of the centres they coordinate this session |
| anyone else | `403 forbidden` |

A staff member who may read but not edit gets `403 read_only` "You can view this course but not change it. Ask the course lecturer or the ODeL office." on any change.

---

## 1. My courses and the course overview (staff T-01, T-02, T-02b, T-08)

### `GET /content/courses?semesterId=` (T-01)
Default semester: the active session's semester that holds today, else its first. Lecturers see the courses they lecture or
facilitate; read-only roles and the admin see every course of that semester.
```json
{"semester":{"id","sessionId","sessionName":"2026/2027","number":1,"label":"2026/2027 · 1st semester"},
 "semesters":[{"id","label"}],
 "items":[{"offeringId","course":{"id","code":"CSC 201","title":"Data Structures","units":3,"level":200},
   "lecturer":{"id","fullName":"Dr Funmi Bello"},"myRole":"LECTURER","canEdit":true,
   "students":168,"weeks":13,"weeksReady":5,"readyLabel":"5 of 13 weeks ready","empty":false}],
 "subtitle":"2 courses · 261 students"}
```
`weeksReady` = weeks that have at least one item ready for students. `empty` = no weeks yet (T-02b).

### `GET /content/offerings/{id}` (T-02 overview, T-02b empty)
```json
{"id","course":{…},"semester":{…},"lecturer":{…},"canEdit":true,"myRole":"LECTURER",
 "outline":"…","objectives":["…","…"],"assessment":"Coursework 30% · Exam 70% · set by the ODeL office",
 "weeks":13,"weeksReady":5,"items":31,"students":168,"empty":false,
 "contentComplete":38,"contentLabel":"5 of 13 weeks ready · 38%",
 "copiedFrom":{"offeringId","label":"2025/2026 · 1st semester"}|null,
 "canCopy":true}
```
`canCopy`: the offering has no weeks and an earlier session's offering of the same course exists.

### `PUT /content/offerings/{id}` (T-08 settings) `{"outline":"…","objectives":["…"]}`
Outline up to 600 characters, up to 8 objectives of up to 200 characters each. → the overview. Audit `CONTENT_COURSE_UPDATED`.

---

## 2. Weeks (T-03, T-03d, T-03g, T-03h)

### `GET /content/offerings/{id}/weeks` (T-03 left list)
```json
{"items":[{"id","number":5,"title":"Linked lists II","opensOn":"2027-02-08","open":true,"hidden":false,
   "itemCount":6,"readyCount":5,"statusLabel":"6 items · released","note":"Watch lecture 5 before Thursday's live class."}]}
```
`statusLabel`: "6 items · released", "Empty · opens Mon 15 Feb", "3 items · opens Mon 22 Feb", "No open date", "Hidden from students".

### `POST /content/offerings/{id}/weeks` (T-03d) `{"title":"Hashing","opensOn":"2027-02-22","note":null}`
→ `201` the week, numbered after the last one. Title required (≤ 120). Toast text in `message`: "Week 8 added · opens Mon 1 Mar".

### `GET /content/weeks/{id}` (T-03 editor) → the week with `items` (staff view, hidden items included):
```json
{"id","number":5,"title","opensOn","open","hidden","note","releaseLabel":"Released Mon 8 Feb",
 "items":[<Item, staff view>]}
```

### `PUT /content/weeks/{id}` (T-03g) `{"title","opensOn":"2027-02-22"|null,"note","hidden":false}` → the week;
`message` "Week 6 now opens Mon 22 Feb".
### `DELETE /content/weeks/{id}` → `204` (only an empty week; else `409 not_empty` "Move or delete this week's 3 items first."). Weeks after it keep their numbers.
### `POST /content/offerings/{id}/weeks/dates` (T-06b) `{"firstOpensOn":"2027-01-11","everyDays":7,"onlyEmpty":true}`
Sets each week's open date in order (week 1 on the first date, then every 7 days); `onlyEmpty` leaves weeks that already have a date.
→ `{"updated":13,"message":"13 weeks dated, from Mon 11 Jan"}`.

---

## 3. Items (T-03, T-03b, T-03c, T-03e/f/k/l/m, T-04, T-04b, T-03i)

### Item (staff view)
```json
{"id","weekId","position":1,"kind":"VIDEO","title":"Lecture 5: Linked lists","hidden":false,
 "status":"READY","statusLabel":"Ready",
 "meta":"Video · 18 min · 240p/360p/480p + audio ready",
 "durationSeconds":1084,"sizeBytes":…,"pages":14,
 "file":{"name":"lecture-05.mp4","contentType":"video/mp4","sizeBytes":…},
 "converted":{"fromBytes":3565158,"toBytes":1153433,"label":"PowerPoint → PDF · 3.4 MB → 1.1 MB"}|null,
 "url":"https://visualgo.net/en/list","note":"…","warnData":true,"body":"…","readMinutes":1,
 "openedBy":23,"failure":{"code","message"}|null,"uploadedAt","updatedAt"}
```
`status` and its label:

| `status` | Label | Meaning |
|---|---|---|
| `UPLOADING` | "Uploading" | waiting for the browser's upload; `POST …/uploaded` when done |
| `PROCESSING` | "Processing" (meta: "uploaded 20 minutes ago · making 240p/360p/480p + audio") | video encoding at Bunny ("Preparing for low data") |
| `CONVERTING` | "Converting" | slides or notes being turned into a compressed PDF |
| `READY` | "Ready" / "Converted" (slides, notes) / "Published" (page) | students see it once its week is open |
| `KEPT_ORIGINAL` | "Kept as the original file" | conversion was not possible (no converter, or it failed); students download the original |
| `FAILED` | "Failed" | upload never arrived or encoding failed; `failure.message` says what to do (T-04b) |

### `POST /content/weeks/{id}/items` (links and pages)
- Link (T-03b): `{"kind":"LINK","title":"Visualgo: linked lists","url":"https://visualgo.net/en/list","note":"Why open it","warnData":true}`;
  `url` must be `http(s)://`.
- Page (T-03c): `{"kind":"PAGE","title":"How to prepare for Thursday's Q&A","body":"…"}` (≤ 20,000 characters; `readMinutes` at 200 words a minute).
→ `201` the item, last in the week.

### `POST /content/weeks/{id}/uploads` (T-04 upload panel) `{"fileName":"lecture-06.mp4","contentType":"video/mp4","sizeBytes":214000000,"title":null}`
The kind comes from the file: video (`.mp4 .mov .m4v .webm .mkv .avi`, up to **2 GB**), audio (`.mp3 .m4a .aac .ogg .wav`, 200 MB), PDF
(50 MB), slides (`.pptx .ppt .odp`, 100 MB; `.key` → `400 unsupported_file` "Export Keynote files to PowerPoint or PDF first."), notes (`.docx .doc .odt .rtf`, 50 MB).
Anything else → `400 unsupported_file` "We cannot use .zip files. Upload a video, audio, PDF, PowerPoint or Word file." Too big →
`400 file_too_large` "Videos can be up to 2 GB. Shorten the video or export it at a lower quality, then try again." The title defaults to the
file name without its extension ("lecture-06" → "Lecture 06").
→ `201`:
```json
{"item":<Item, status UPLOADING>,
 "upload":{"protocol":"PUT","url":"<signed storage link>","method":"PUT","headers":{"Content-Type":"application/pdf"},"expiresAt"}}
```
For a video the browser uploads **straight to Bunny Stream with TUS** (resumable; nothing passes through our servers):
```json
"upload":{"protocol":"TUS","endpoint":"https://video.bunnycdn.com/tusupload","expiresAt",
          "headers":{"AuthorizationSignature":"<sha256 hex>","AuthorizationExpire":"1738900000","VideoId":"<guid>","LibraryId":"<id>"},
          "metadata":{"filetype":"video/mp4","title":"Lecture 06"}}
```
(`AuthorizationSignature` = `sha256(libraryId + apiKey + expires + videoId)`, valid 24 hours; the browser's TUS client can resume an
interrupted upload with the same headers.) In fake mode (`ODEL_VIDEO_MODE=fake`) the endpoint is `/api/v1/dev/content/videos/{videoId}/upload`
with `"protocol":"PUT"`.

### `POST /content/items/{id}/uploaded` (the browser's upload finished)
- Storage files: checks the file is there (`409 upload_missing` "We could not find the uploaded file. Upload it again."). PDF and audio → `READY`
  (pages counted); slides and notes → `CONVERTING` (background job, below).
- Video → `PROCESSING`, then Bunny's webhook (or the 2-minute re-check) moves it to `READY` or `FAILED`.
→ the item.

### `POST /content/items/{id}/upload-again` (T-04b "Resume", "Upload again") → `{"item","upload"}` with fresh upload details for the same
item. For a video still `UPLOADING` the same Bunny video is kept (TUS resumes); after a `FAILED` encoding a new Bunny video is made and the
failed one deleted.

### `POST /content/items/{id}/replace` (T-03l) `{"fileName","contentType","sizeBytes"}` → `200 {"item","upload"}`
Uploads a new file for the item (a video for a video; any document or audio file for a document). `POST /content/items/{id}/uploaded`
with the **item's** id finishes it. Students keep the old file until the new one is ready; then it takes its place (same item, same position,
students' progress kept) and the old file or video is deleted. While waiting the staff item shows `replacement:{"status","statusLabel"}`.

### `PUT /content/items/{id}` (rename T-03e, edit a link or page) `{"title","url","note","warnData","body"}` → the item.
### `POST /content/items/{id}/hide` / `POST /content/items/{id}/show` (T-03k) → the item; `message` "Hidden from students" / "Visible to students".
### `POST /content/items/{id}/move` (T-03m) `{"weekId"}` → the item, last in that week of the same offering; `message` "Moved to Week 6".
### `PUT /content/weeks/{id}/order` (drag to reorder) `{"itemIds":[…every item of the week, in the new order]}` → the week. A list that is not exactly the week's items → `400 order_mismatch`.
### `DELETE /content/items/{id}` (T-03f) → `204`. The stored file or Bunny video is deleted unless a copied course still uses it. The confirm
dialog reads `openedBy` from the item ("Delete Week 5 slides? Students lose access. 23 have opened it.").

### Slides and notes conversion (FR-4.3)
A background job in content-service converts `SLIDES` and `NOTES` to a compressed PDF with LibreOffice headless
(`soffice --headless --convert-to pdf`, images down-sampled to 150 dpi where the installed LibreOffice supports export options) and stores it
beside the original. `converted.label` "PowerPoint → PDF · 3.4 MB → 1.1 MB". When `soffice` is not installed (this Mac) or conversion fails
or takes over 3 minutes, the item becomes `KEPT_ORIGINAL` ("Kept as the original file") and students download the original. Setting:
`ODEL_SOFFICE_PATH` (default `soffice`).

---

## 4. Copy last session's course (T-06, T-06b, FR-4.6)

### `GET /content/offerings/{id}/copy-sources` → `{"items":[{"offeringId","label":"2025/2026 · 1st semester","weeks":13,"items":58,"lecturer":"Dr Funmi Bello"}]}` (newest first: the same course in earlier sessions).
### `POST /content/offerings/{id}/copy` `{"fromOfferingId":null}` (default: the newest source)
Only into an offering with no weeks (`409 not_empty` "This course already has weeks. Copy only into an empty course."). Copies outline and
objectives (when empty), every week (title, note, hidden; **no open date**: "No open date") and every item in order, hidden items
stay hidden. Videos and files are shared, not uploaded again; items still processing or failed are left out (listed in `skipped`).
Announcements and progress are not copied. → `{"weeks":13,"items":58,"skipped":[{"title","reason"}],"message":"Course copied · 13 weeks into CSC 305"}`.
Audit `CONTENT_COURSE_COPIED`.

---

## 5. Student: courses, course page, week, items (L-03, L-04, L-05, L-05b, L-06, L-07, L-08, L-09, L-09b, E-10, E-11, E-16)

### `GET /content/me/courses?semesterId=` (L-03)
```json
{"semester":{…},"registered":true,
 "items":[{"offeringId","course":{"code":"CSC 201","title":"Data Structures","units":3},"lecturer":{"fullName":"Dr Funmi Bello","initials":"FB"},
   "progress":{"percent":31,"weeksDone":4,"weeksTotal":13,"label":"4 of 13 weeks done · 31%"},
   "currentWeek":{"id","number":5,"title":"Linked lists II"}|null,"empty":false}],
 "subtitle":"7 registered courses · 18 units"}
```
Not registered for the semester → `"registered":false,"items":[],"message":"You have not registered your courses for 2026/2027 · 1st semester yet. Register first and your courses appear here.","registerPath":"/registration"`.

### `GET /content/me/offerings/{id}` (L-04, E-11)
```json
{"id","course":{…},"semester":{…},"lecturer":{…},
 "about":{"outline","objectives":[…],"assessment":"Coursework 30% · Exam 70%"},
 "progress":{"percent":31,"weeksDone":4,"weeksTotal":13,"itemsDone":21,"itemsTotal":29,"label":"4 of 13 weeks done · 31%"},
 "weeks":[{"id","number":5,"title","open":true,"opensOn","openLabel":null|"Opens Mon 15 Feb","itemsDone":1,"itemsTotal":6,"done":false,"current":true}],
 "announcements":[<the newest 3, section 7>],
 "continue":{"itemId","title","kind","positionSeconds","weekNumber"}|null,
 "empty":false,"emptyMessage":null}
```
Hidden weeks are left out. `empty:true` (E-11): "Dr Funmi Bello has not added anything to this course yet. You will get a notification when the first week is ready."

### `GET /content/me/weeks/{id}` (L-05)
```json
{"id","number":5,"title","note","lecturer":{…},"opensOn","itemsDone":1,"itemsTotal":6,"progressLabel":"1 of 6 done",
 "items":[<Item, student view>],"empty":false}
```
A week that is not open yet → `409 week_not_open` (E-16): "Week 6 opens Mon 15 Feb. Come back then." with `opensOn` and `openLabel`
in the body. An open week with no items → `empty:true`, "Nothing here yet. Dr Funmi Bello has not added this week's materials."

### Item (student view)
```json
{"id","kind":"VIDEO","title":"Lecture 5: Linked lists","ready":true,"statusLabel":null|"Not ready",
 "meta":"Video · 18 min","dataCost":"about 41 MB at 360p · 7 MB audio only",
 "durationSeconds":1084,"sizeBytes":1153433,"sizeLabel":"1.1 MB","pages":14,"readMinutes":1,
 "url","note","warnData":true,"linkHost":"visualgo.net",
 "progress":{"done":false,"positionSeconds":422,"percentWatched":38,"openedAt"}}
```
Every item says its data cost (`dataCost`): videos the 360p size and the audio size; PDFs their size; links "opens your browser, uses data";
pages "under 10 KB". Items not ready (`PROCESSING`, `FAILED`, `CONVERTING`) show `ready:false`, "Not ready". Hidden items are left out.

### `GET /content/me/items/{id}` (L-06 video, L-08 audio, L-09 PDF, L-09b page, L-05b link)
`{"item":<Item, student view>,"week":{"id","number","title"},"next":{"id","title","kind"}|null, …}` plus, by kind:
- `VIDEO`: `playback` as below (or `playback:null` with `notReady` when not ready).
- `AUDIO`, `PDF`, `SLIDES`, `NOTES`: `file:{"url":"<signed link, 30 minutes>","contentType","sizeBytes","sizeLabel","name","converted":true}`.
- `PAGE`: `body`. `LINK`: `url`, `note`, `warning`: "This leaves the portal and uses your mobile data."
- `next`: the next item of the week or null.

Video not ready → `playback:null` and `notReady:{"code":"video_preparing","message":"Being prepared. Dr Funmi Bello uploaded it 20 minutes ago; check back in about 20 minutes."}` (E-10)
or `{"code":"video_failed","message":"This lecture could not be prepared. Dr Funmi Bello has been told and will upload it again."}`.

### `GET /content/me/items/{id}/playback` (video player, L-06, L-07, L-08; FR-5.2–5.4)
```json
{"itemId","videoId","durationSeconds":1084,"defaultQuality":"360p","resumeAt":422,"expiresAt",
 "auto":{"hlsUrl":"https://<cdn>/bcdn_token=…/<videoId>/playlist.m3u8"},
 "qualities":[{"quality":"240p","sizeBytes":…,"sizeLabel":"28 MB","perMinuteLabel":"about 1.5 MB a minute","hlsUrl","mp4Url"},
              {"quality":"360p","default":true,…},{"quality":"480p",…}],
 "audio":{"kind":"AUDIO_FILE","url","sizeBytes","sizeLabel":"6.2 MB","perMinuteLabel":"about 0.3 MB a minute","saving":"saves about 85% of the data"},
 "speeds":[1,1.25,1.5,2],"embedUrl":"https://iframe.mediadelivery.net/embed/<lib>/<videoId>?token=…&expires=…"}
```
Signed, expiring links (4 hours). `audio.kind`: `AUDIO_FILE` (a 48 kbps mono AAC file made from the 240p rendition, served from storage) or
`VIDEO_LOW` (no audio file: play the 240p stream with the picture hidden; `saving` then reads "plays the 240p stream without the picture").
The sizes are the host's MP4 sizes when it has them (Bunny's MP4 fallback), else estimates (240p 1.5, 360p 2.3, 480p 4.0 MB a minute). `403 not_registered` for anyone not registered.

### `POST /content/me/items/{id}/progress` (player heartbeat every 15–30 s and on pause/leave)
`{"positionSeconds":438,"fromSeconds":422,"toSeconds":438,"mode":"VIDEO|AUDIO","speed":1.5}` → `{"done":false,"percentWatched":40,"watchedSeconds":…}`.
Marks the 10-second stretches from `fromSeconds` to `toSeconds` as watched (a jump > 60 s in one beat counts only 60 s; seeking does not
count). Watch time adds `toSeconds − fromSeconds` (capped at 60 per beat). A video is **done** when 90 % of it has been watched (audio
counts). Position kept for resume (FR-5.3; "We saved your place at 7:02").

### `POST /content/me/items/{id}/open` → `{"done":true}`: a PDF, slides, notes, audio file or link opened counts as done; a page is done on
`POST /content/me/items/{id}/done` ("Mark as done and continue"). Opening a video only records `openedAt`.

### Progress rules (FR-4.4)
- Item done: video ≥ 90 % watched; audio file, document or link opened; page marked done.
- Week done: every item of the week that is ready and visible is done (a week with nothing ready is not done).
- Course %: weeks done ÷ weeks (visible weeks, open or not) → "4 of 13 weeks done · 31%".

### `GET /content/me/continue` (Home "Continue where you stopped", L-01b) → the newest unfinished video or audio she started this semester:
`{"itemId","offeringId","course":"CSC 201","title","positionSeconds","durationSeconds","label":"7 of 18 min"}` or `204`.

---

## 6. Student progress for staff (T-07, T-07b; FR-4.4, FR-5.6)

### `GET /content/offerings/{id}/progress?filter=ALL|INACTIVE|BEHIND|NOT_STARTED&q=&centreId=`
```json
{"counts":{"all":168,"inactive":12,"behind":31,"notStarted":4},"videos":5,"openWeeks":5,
 "items":[{"studentId","fullName":"Chidi Nwosu","matricNumber","centre":{"id","code","name":"Ondo"},
   "percent":12,"weeksDone":0,"videosWatched":1,"videosLabel":"1 of 5","minutesWatched":14,
   "lastActiveAt","lastActiveLabel":"24 Jan · 16 days","status":"INACTIVE","statusLabel":"Inactive"}]}
```
Rows: the students registered for the course this semester (registration-service). `INACTIVE`: no activity for 14 days (and has started);
`NOT_STARTED`: never opened anything; `BEHIND`: fewer weeks done than open weeks with content minus one; else `ON_TRACK`. Coordinators see
only their centres' students.

### `GET /content/offerings/{id}/progress/{studentId}` (T-07b) → the row plus `weeks:[{"number","title","itemsDone","itemsTotal","done"}]`,
`videos:[{"itemId","title","minutesWatched","percentWatched","done"}]`.
### `GET /content/offerings/{id}/progress.csv` → `student,matric_number,centre,percent,weeks_done,videos_watched,minutes_watched,last_active,status`.

---

## 7. Announcements (T-05, T-05b, L-02, L-04, L-13; FR-4.5)

### Announcement
`{"id","title","body","author":{"fullName":"Dr Funmi Bello","initials":"FB"},"postedAt","postedLabel":"Tue 9 Feb · 9:12 am","edited":false,
"editedAt","audience":"ALL","audienceLabel":"All 168 students","recipients":168,"email":true}`

### `GET /content/offerings/{id}/announcements` (staff) · `GET /content/me/offerings/{id}/announcements` (student) → `{"items":[…]}` newest first.
### `POST /content/offerings/{id}/announcements` `{"title","body","audience":"ALL|INACTIVE|STUDENTS","studentIds":[…],"email":true}`
Title ≤ 120, body ≤ 5,000. Each recipient gets the bell item and a push (where the browser allowed it), and the email when `email` is true
(template `content.announcement`). `INACTIVE` = the T-07 inactive students (T-05b), `STUDENTS` = the given ids (they must be registered).
→ `201` the announcement with `message` "Sent to 168 students". No registered students → `409 no_students` "No students are registered for
CSC 201 yet. Post again once they register." Audit `CONTENT_ANNOUNCEMENT_POSTED`.
### `PUT /content/announcements/{id}` `{"title","body"}` → the announcement, `edited:true`. Edits are not sent again (the bell item opens the
current text). Only its author or an ODeL admin.

---

## 8. Bunny's webhook and the video life cycle

`POST /content/webhooks/bunny` (public, no session, no CSRF) with Bunny's body `{"VideoLibraryId":123,"VideoGuid":"…","Status":3}`.
- The library id must be ours (`401 bad_library` otherwise). The video must be one of ours (unknown → `200`, ignored).
- **Never trusted as it comes**: the video is looked up through Bunny's API (`GET /library/{id}/videos/{guid}`) and only that answer moves
  the item: status 3/4 (finished / resolution finished) with 360p available → `READY` (length and per-quality sizes stored); 5 or 8 (failed)
  → `FAILED`; anything else → stays `PROCESSING` (encode progress kept).
- Idempotent: the same webhook twice changes nothing.
- A re-check every 2 minutes asks Bunny about videos `PROCESSING` for more than 3 minutes (a lost webhook cannot leave a video stuck) and
  marks `UPLOADING` items older than 24 hours `FAILED` ("The upload did not finish. Upload the file again.").
- On `READY`: the uploader gets the bell item `content.video_ready`; an audio file is made in the background (ffmpeg: the 240p MP4 →
  48 kbps mono AAC in storage). On `FAILED`: bell and email `content.video_failed` to the uploader.

Bunny settings for the library (set once in Bunny's dashboard; the library key cannot change them): resolutions **240p, 360p, 480p**; **MP4
fallback on** (the per-quality MP4s give exact sizes and the audio source); **token authentication on** for the CDN (key = `ODEL_BUNNY_TOKEN_KEY`);
**embed view token authentication on**; webhook URL `https://odel-api-dev.oyeksglobal.com/api/v1/content/webhooks/bunny`.

## 9. Internal (`/internal/**`, `X-Internal-Key`)

### content-service uses
- academic-service (new) `GET /internal/academic/teaching?sessionId=&userId=` →
  `{"session":{"id","name"},"items":[{"course":{"id","code","title","units","level","semester"},"lecturerId","facilitatorIds":[…]}]}`
  (every course of the session's catalogue that runs in that session; with `userId` only those the person lectures or facilitates).
  Plus the existing `/internal/academic/calendar`, `/semesters`, `/semesters/{id}`, `/courses?ids=`, `/coordinators`.
- registration-service `GET /internal/registration/students/{studentId}/courses?semesterId=` (is she registered?) and (new)
  `GET /internal/registration/courses/{courseId}/students?semesterId=` →
  `{"items":[{"studentId","userId","matricNumber","fullName","centreId","centreCode","centreName"}]}`.
- student-service `/internal/students/by-user/{userId}`, `/internal/students/roster` (emails for announcements).
- identity-service `/internal/users/{id}` (names). tenant-service `/internal/tenants/{id}` (name, colour, portal).
- notification-service `POST /internal/messages` and `/internal/messages/bulk`, templates (changelog **007**): `content.announcement`
  (email, bell, push), `content.video_ready` (bell), `content.video_failed` (email, bell).

### content-service provides
- `GET /internal/content/students/{studentId}/summary?semesterId=` → `{"items":[{"courseId","offeringId","percent","weeksDone","weeksTotal"}]}`
  (Home and later phases).

## 10. Dev only (profile `dev`)

With `ODEL_VIDEO_MODE=fake` (the default) the fake video host keeps videos in memory:
- `PUT /dev/content/videos/{videoId}/upload` (the upload stand-in; body ignored) → `204`; the video is "uploaded".
- `POST /dev/content/videos/{videoId}/simulate` `{"outcome":"ENCODING|READY|FAILED","lengthSeconds":1084,"webhook":true}` → plays Bunny's
  outcome and posts the webhook to content-service, which checks with the fake "API" as it does with Bunny.
- `ODEL_FAKE_VIDEO_READY_SECONDS` (default 20; 0 = never): an uploaded fake video becomes ready on its own after that long, for clicking
  through the web app.
- `POST /dev/content/jobs/run` → runs the conversion and audio jobs now → `{"converted":1,"kept":0,"audio":1}`.

## Audit actions
`CONTENT_COURSE_UPDATED`, `CONTENT_WEEK_ADDED`, `CONTENT_WEEK_CHANGED`, `CONTENT_WEEK_DELETED`, `CONTENT_ITEM_ADDED`, `CONTENT_ITEM_CHANGED`,
`CONTENT_ITEM_HIDDEN`, `CONTENT_ITEM_SHOWN`, `CONTENT_ITEM_MOVED`, `CONTENT_ITEM_REPLACED`, `CONTENT_ITEM_DELETED`, `CONTENT_COURSE_COPIED`,
`CONTENT_ANNOUNCEMENT_POSTED`, `CONTENT_ANNOUNCEMENT_EDITED` (entity type `CONTENT`).

## Environment
`ODEL_VIDEO_MODE` (`fake` | `bunny`; default `fake`), `ODEL_BUNNY_LIBRARY_ID`, `ODEL_BUNNY_CDN_HOST` (e.g. `vz-xxxx.b-cdn.net`),
`ODEL_BUNNY_API_KEY` (library key: create, read, delete, upload signature), `ODEL_BUNNY_READ_ONLY_API_KEY` (lookups),
`ODEL_BUNNY_TOKEN_KEY` (CDN and embed tokens), `ODEL_BUNNY_TOKEN_FORMAT` (`hs256` default | `sha256`, see below),
`ODEL_SOFFICE_PATH` (`soffice`), `ODEL_FFMPEG_PATH` (`ffmpeg`), `ODEL_REGISTRATION_URL` (`http://127.0.0.1:9307`). Keys only from the environment.

### Playback tokens
CDN links use Bunny's directory token in the path (needed for HLS: the segments inherit it): `https://<cdn>/bcdn_token=<token>&expires=<t>&token_path=%2F<videoId>%2F/<videoId>/playlist.m3u8`.
`hs256` (Bunny's current advanced token): `token = "HS256-" + base64url(HMAC-SHA256(tokenKey, "/<videoId>/" + expires + "token_path=/<videoId>/"))`.
`sha256` (Bunny's earlier advanced token): `token = base64url(SHA256(tokenKey + "/<videoId>/" + expires + "token_path=/<videoId>/"))`.
The embed link uses `token = hex(SHA256(tokenKey + videoId + expires))`. Check one test clip on dev after deploy; switch the format if the CDN answers 403.
