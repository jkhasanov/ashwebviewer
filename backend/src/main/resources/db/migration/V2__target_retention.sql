-- How long to keep what is collected from one database. Null means the server's setting applies.
alter table target
    add column raw_days    integer check (raw_days between 1 and 3650),
    add column rollup_days integer check (rollup_days between 1 and 3650);
