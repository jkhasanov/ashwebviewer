create table app_user (
    id            bigint generated always as identity primary key,
    username      text        not null unique,
    password_hash text        not null,
    role          text        not null check (role in ('ADMIN', 'VIEWER')),
    enabled       boolean     not null default true,
    created_at    timestamptz not null default now()
);

create table target (
    id           bigint generated always as identity primary key,
    name         text        not null unique,
    db_type      text        not null check (db_type in ('ORACLE', 'POSTGRES')),
    jdbc_url     text        not null,
    username     text        not null,
    password_enc text        not null,
    enabled      boolean     not null default true,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

-- One row per active session per sample. Daily partitions are created by the application.
create table ash_sample (
    target_id      bigint      not null,
    sample_time    timestamptz not null,
    session_id     bigint      not null,
    session_serial bigint      not null,
    user_name      text,
    program        text,
    sql_id         text        not null default '',
    sql_opname     text,
    wait_class     text        not null,
    event          text        not null,
    extra          jsonb
) partition by range (sample_time);

create index ash_sample_target_time on ash_sample (target_id, sample_time);

-- Sample counts per minute. Daily partitions are created by the application.
create table ash_rollup_1m (
    target_id      bigint      not null,
    bucket         timestamptz not null,
    wait_class     text        not null,
    event          text        not null,
    sql_id         text        not null default '',
    session_id     bigint      not null,
    session_serial bigint      not null,
    sql_opname     text,
    user_name      text,
    program        text,
    samples        integer     not null,
    primary key (target_id, bucket, wait_class, event, sql_id, session_id, session_serial)
) partition by range (bucket);

-- For the all-databases overview, which reads the last hour without a target filter
create index ash_rollup_1m_bucket on ash_rollup_1m (bucket);

create table sql_text (
    target_id  bigint      not null,
    sql_id     text        not null,
    sql_text   text        not null,
    first_seen timestamptz not null default now(),
    primary key (target_id, sql_id)
);
