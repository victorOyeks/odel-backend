--liquibase formatted sql

--changeset odel:001-content
-- Teach and learn (B3): a course in a semester (offering), its weeks, the ordered items in each week (videos on Bunny Stream, files in
-- storage, links, text pages), each student's progress per item, and the course's announcements. Every row carries the university.

create table offerings (
    id              uuid primary key,
    tenant_id       uuid         not null,
    course_id       uuid         not null,
    course_code     varchar(20)  not null,
    course_title    varchar(200) not null,
    units           int          not null,
    level           int          not null,
    semester_id     uuid         not null,
    semester_number int          not null,
    session_id      uuid         not null,
    session_name    varchar(20)  not null,
    outline         text,
    objectives      text,                      -- one objective per line
    copied_from_id  uuid,
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    unique (tenant_id, course_id, semester_id)
);
create index offerings_semester on offerings (tenant_id, semester_id);

create table weeks (
    id           uuid primary key,
    tenant_id    uuid         not null,
    offering_id  uuid         not null references offerings (id) on delete cascade,
    number       int          not null,
    title        varchar(120) not null,
    opens_on     date,
    note         varchar(1000),
    hidden       boolean      not null default false,
    created_at   timestamptz  not null,
    updated_at   timestamptz  not null,
    unique (offering_id, number)
);

create table items (
    id                  uuid primary key,
    tenant_id           uuid          not null,
    offering_id         uuid          not null references offerings (id) on delete cascade,
    week_id             uuid          not null references weeks (id),
    position            int           not null,
    kind                varchar(10)   not null check (kind in ('VIDEO', 'AUDIO', 'PDF', 'SLIDES', 'NOTES', 'LINK', 'PAGE')),
    title               varchar(200)  not null,
    status              varchar(15)   not null check (status in ('UPLOADING', 'PROCESSING', 'CONVERTING', 'READY', 'KEPT_ORIGINAL', 'FAILED')),
    hidden              boolean       not null default false,
    -- files in storage: what students get (storage_key) and what was uploaded (original_key)
    storage_key         varchar(400),
    original_key        varchar(400),
    file_name           varchar(255),
    content_type        varchar(120),
    size_bytes          bigint,
    original_size_bytes bigint,
    pages               int,
    pages_checked       boolean       not null default false,
    converted           boolean       not null default false,
    -- videos on Bunny Stream
    video_id            varchar(64),
    duration_seconds    int,
    qualities           varchar(200),  -- "240p:29360128,360p:44040192,480p:76546048" (bytes; estimated when the host gives none)
    encode_progress     int,
    audio_status        varchar(10)   check (audio_status in ('PENDING', 'READY', 'SKIPPED', 'FAILED')),
    audio_key           varchar(400),
    audio_size_bytes    bigint,
    -- links and pages
    url                 varchar(2000),
    note                varchar(1000),
    warn_data           boolean       not null default false,
    body                text,
    failure_code        varchar(40),
    failure_message     varchar(500),
    -- a replacement upload waiting to take this item's place (T-03l); hidden from every list
    replaces_item_id    uuid,
    created_by          uuid,
    created_by_name     varchar(200),
    created_at          timestamptz   not null,
    updated_at          timestamptz   not null,
    uploaded_at         timestamptz,
    ready_at            timestamptz,
    last_checked_at     timestamptz
);
create index items_week on items (week_id, position);
create index items_offering on items (offering_id);
create index items_video on items (video_id) where video_id is not null;
create index items_status on items (status) where status in ('UPLOADING', 'PROCESSING', 'CONVERTING');

create table item_progress (
    id               uuid primary key,
    tenant_id        uuid        not null,
    offering_id      uuid        not null references offerings (id) on delete cascade,
    item_id          uuid        not null references items (id) on delete cascade,
    student_id       uuid        not null,
    user_id          uuid        not null,
    position_seconds int         not null default 0,
    watched_seconds  int         not null default 0,
    buckets          text,                 -- one character per 10 seconds of the video: 1 = watched
    percent_watched  int         not null default 0,
    done             boolean     not null default false,
    done_at          timestamptz,
    opened_at        timestamptz,
    last_seen_at     timestamptz not null,
    unique (item_id, student_id)
);
create index item_progress_offering on item_progress (offering_id, student_id);
create index item_progress_student on item_progress (tenant_id, student_id, last_seen_at desc);

create table announcements (
    id             uuid primary key,
    tenant_id      uuid         not null,
    offering_id    uuid         not null references offerings (id) on delete cascade,
    title          varchar(120) not null,
    body           text         not null,
    author_user_id uuid         not null,
    author_name    varchar(200) not null,
    audience       varchar(10)  not null check (audience in ('ALL', 'INACTIVE', 'STUDENTS')),
    recipients     int          not null,
    email          boolean      not null,
    posted_at      timestamptz  not null,
    edited_at      timestamptz
);
create index announcements_offering on announcements (offering_id, posted_at desc);
