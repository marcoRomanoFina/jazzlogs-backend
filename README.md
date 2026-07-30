# JazzLogs🎵🎷

JazzLogs is a backend for a jazz-focused music logging and discovery app: users log albums and tracks they've listened to, write reviews and short notes, build "listen later" queues, follow curated playlists and audio series, and get recommendations from a graph built on top of their listening history.

## About this repo

This is a from-scratch rewrite of [jazzlogs](https://github.com/marcoRomanoFina/jazzlogs), an earlier version of the same idea. The core concept and domain (jazz catalog, reviews, recommendations) carry over, but the architecture, data model, and several product decisions were reworked along the way — this repo is the result of redesigning it with cleaner separation of concerns and patterns that scale better as features were added.

## Tech stack

- **Java 21 / Spring Boot 4** — REST API, validation, security
- **PostgreSQL (Supabase)** — primary data store, source of truth for every write
- **Neo4j** — recommendation graph, built from listening history and catalog relationships (artists, styles, moods, similar albums)
- **Supabase Auth** — JWT-based authentication; the backend only validates tokens against Supabase's JWKS, it never issues or stores credentials
- **Spotify Web API** — catalog metadata lookup (album art, track durations, etc.)
- **OpenAI API + pgvector** — embeddings for editorial content, stored directly in Postgres
- **JUnit 5 / AssertJ / Mockito** — testing, including full-context integration tests against an in-memory H2 database

## Architecture notes

A few decisions that shaped most of the codebase:

- **Postgres is the source of truth, Neo4j is a mirror.** Every read that gates access to a feature (e.g. "has this user listened to this album before allowing a review") goes through Postgres only. Neo4j is written to asynchronously, best-effort, purely to feed the recommendation graph — if it's down, the product still works.
- **Failed graph syncs aren't silently dropped.** A failed Neo4j write is recorded in a `sync_failures` table and retried by a scheduled worker until it succeeds or exceeds a retry limit, instead of just logging and moving on.
- **Cross-cutting features are modeled generically, not duplicated per entity.** Likes, saved-for-later items, and listens all follow the same shape: a single table keyed by `(user_id, entity_type, entity_id)`, dispatched through `Map<EntityType, Handler>` wiring instead of a switch statement or a separate table per feature per entity. Adding a new likeable or listenable type is a config change, not a new table.
- **Content that needs ordered, structured entries (playlists, audio series) uses granular per-item operations** (add/remove/reorder) rather than replace-the-whole-list-on-every-save, so partial updates don't require resending everything.

## Features

- **Catalog** — albums, tracks, and artists, enriched from Spotify
- **Reviews** — half-point ratings, standout tracks, aggregate rating per album
- **Notes** — short, timestamped text notes on a track, independent from reviews
- **Playlists** — editorial/curated playlists with per-track curator notes and tag-based discovery (style, mood, context)
- **Series** — podcast-style audio series with sequential chapters that unlock as the previous one is completed
- **Likes & Saved Items** — generic like/unlike and a "listen later" queue that work across every content type
- **Recommendations** — Neo4j-backed graph traversal over artists, styles, moods, and listening history

## Endpoints

All routes except `/public/**` require a `Authorization: Bearer <supabase-jwt>` header. Routes marked **admin** additionally require the `ADMIN` role.

**Catalog**
| Method | Path | Notes |
|---|---|---|
| GET | `/albums/{id}` | album detail: tracks, personnel, tags, rating |
| POST | `/albums` | **admin** |
| PATCH | `/albums/{id}` | **admin** — partial update |
| POST | `/albums/{id}/tracks` | **admin** |
| POST | `/albums/{id}/tags/{style\|mood\|context}` | **admin** |
| POST | `/albums/{id}/listen` | mark listened (gates reviews) |
| GET / POST / DELETE | `/albums/{id}/reviews` | list / upsert / delete |
| GET | `/albums/{id}/reviews/me`, `/albums/{id}/rating` | |
| GET | `/artists/{id}` | |
| POST / PATCH | `/artists`, `/artists/{id}` | **admin** |
| POST | `/artists/{id}/{instrument\|styles\|contexts\|similar}` | **admin** |
| PATCH | `/tracks/{id}` | **admin** |
| POST | `/tracks/{id}/tags/{mood\|context\|rhythm\|instrument}` | **admin** |
| POST | `/tracks/{id}/listen` | |
| POST | `/tracks/{id}/ratings` | half-point track rating |
| GET / POST | `/tracks/{id}/notes`, `/tracks/{id}/notes/me` | |
| DELETE | `/notes/{id}` | |

**Playlists** (curated, admin-authored)
| Method | Path | Notes |
|---|---|---|
| GET | `/playlists`, `/playlists/{id}` | published-only for non-admins |
| POST / PUT | `/playlists`, `/playlists/{id}` | **admin** |
| POST / DELETE | `/playlists/{id}/tracks`, `/playlists/{id}/tracks/{trackId}` | **admin** |
| PATCH | `/playlists/{id}/tracks/{trackId}` | **admin** — curator note / title |
| PUT | `/playlists/{id}/tracks/reorder` | **admin** |
| POST / DELETE | `/playlists/{id}/listened` | |

**Series** (audio series with sequential chapters)
| Method | Path | Notes |
|---|---|---|
| GET | `/series`, `/series/{id}` | published-only for non-admins |
| POST / PUT | `/series`, `/series/{id}` | **admin** |
| POST / DELETE / PATCH | `/series/{id}/chapters`, `/series/{id}/chapters/{chapterId}` | **admin** |
| PUT | `/series/{id}/chapters/reorder` | **admin** |
| POST | `/series/{id}/chapters/{chapterId}/complete` | rejected if the chapter is still locked |

**Cross-cutting**
| Method | Path | Notes |
|---|---|---|
| POST / DELETE | `/likes` | body: `{ entityType, entityId }` — works for reviews, notes, playlists, series, editorials |
| GET | `/likes/count`, `/likes/me` | |
| POST / DELETE / GET | `/saved-items` | "listen later" queue, same generic entity model as likes |
| GET | `/me` | current user, resolved from the JWT |


## Running locally

Requirements: Java 21, Maven, and a local Neo4j (Community Edition works fine via Docker).

Two Spring profiles control the datasource — `application-dev.properties` /
`application-prod.properties`:

- **`dev` (default, no flag needed)** — an in-memory H2 database, schema
  created fresh on every boot (`ddl-auto=update`), no Postgres required. Data
  does not survive a restart. Browse it at
  [http://localhost:8080/h2-console](http://localhost:8080/h2-console)
  (JDBC URL `jdbc:h2:mem:jazzlogs`, user `sa`, blank password).
- **`prod`** — the real Supabase Postgres, via `DATABASE_URL` /
  `DATABASE_USERNAME` / `DATABASE_PASSWORD`. Activate with
  `SPRING_PROFILES_ACTIVE=prod` (set automatically in deployment, e.g. Railway).

```bash
./mvnw spring-boot:run
# against real Postgres instead:
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run
```

Configuration is read from environment variables (see `application.properties`,
`application-dev.properties` and `application-prod.properties` for the full
list and defaults):

| Variable | Required | Notes |
|---|---|---|
| `SUPABASE_JWKS_URI` | yes | JWKS endpoint used to validate incoming JWTs |
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | only for `prod` | ignored on the `dev` profile (H2) |
| `NEO4J_URI` / `NEO4J_USERNAME` / `NEO4J_PASSWORD` | password only | defaults to `bolt://localhost:7687` |
| `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` | no | catalog enrichment is skipped without these |
| `OPENAI_API_KEY` | no | editorial embeddings are skipped without this |

## Testing

```bash
./mvnw test
```

Tests run against an in-memory H2 database with the real Spring context — no external services required.
