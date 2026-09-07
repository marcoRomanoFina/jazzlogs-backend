-- pg_trgm is created by Flyway itself (V1); vector isn't, since the base
-- postgres image doesn't ship it at all — this compose file uses the
-- pgvector/pgvector image instead, which bundles the extension's binaries,
-- this script just has to actually enable it.
create extension if not exists vector;
create extension if not exists pgcrypto;
