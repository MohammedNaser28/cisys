# Building cisys

This document describes how to build the project, run it locally, run the tests, and generate the manual.

## Prerequisites

- Java 25 or newer
- Maven
- Node.js 24 and npm
- Docker Compose, or Podman with Compose (PostgreSQL and Keycloak for local development)
- Pandoc and the Eisvogel Pandoc template (only for the PDF manual)

Dependencies and container images must be downloadable. The setup below is for a trusted local machine only.

## Build the project

Install the frontend dependencies:

```bash
cd src/frontend
npm install
```

Installing the frontend dependencies also installs the Git hooks (the `prepare` script in `src/frontend/package.json`). Hooks only install inside a Git checkout; if you unpacked an archive, run `git init` in the project root before `npm install`.

Build the backend:

```bash
mvn package
```

This produces the application artifacts in `target/`. Use `mvn clean package` only when you want to remove previous build output first.

Build the frontend for production:

```bash
cd src/frontend
npm run build
```

The output is written to `src/frontend/dist`. The backend does not serve the frontend; in development the Vite dev server serves it.

## Git hooks

Hooks live in `.husky/`.

* `pre-commit` (fast, on staged files only): `lint-staged` for changes under `src/frontend/`, which runs ESLint on the staged frontend files.

There is no `pre-push` hook; run the full checks (frontend lint and backend tests) with `make test`. Bypass the hook once with `git commit --no-verify`.

## Running cisys

### Support services

From the project root start PostgreSQL and Keycloak:

```bash
docker compose up -d
```

(or `podman compose up -d`). The `compose.yaml` file starts:

- `postgres` (PostgreSQL 18) on `127.0.0.1:5432`, database, user and password `cisys`
- `keycloak` (26.8.2, `start-dev`) on `http://localhost:8180`, importing `contrib/keycloak/realm.json`

Wait for Keycloak to become available before using the application. The realm is imported only on the first start; existing Keycloak data is not overwritten. To recreate the local development data, run `make reset` (this deletes the local databases).

Introducing direct project membership requires a database reset for databases created before this change: run `make reset`, which deletes the local PostgreSQL and Keycloak data volumes, including projects, builds and users. New projects automatically assign their creator the LEAD role at creation time; existing membership data is not migrated or repaired. Databases created after the change are not affected, and not every future upgrade requires a reset. The reusable Teams feature needs no reset: `R__schema.sql` adds its tables and the project's `teamid` column to existing databases.

Signing out returns to the public front page, which needs `http://localhost:5173/*` as a valid post-logout redirect URI on the `cisys-ui` client. Realms imported before this setting was added must either be re-imported with `make reset` or updated in the Keycloak admin console (Clients → `cisys-ui` → Valid post logout redirect URIs).

When cisys shuts down it signs all users out of the `cisys` realm through the `cisys-backend` service account, so everyone signs in again after a restart. A live reload keeps the sessions, and so does a crash, as nothing can run at that point. A Keycloak realm created before this client was added logs a warning instead; run `make reset` to import it. Access tokens that were already issued stay valid until they expire, by default within 5 minutes.

### Backend

```bash
mvn quarkus:dev
```

The backend listens on <http://localhost:8080>. The Quarkus configuration is in `src/backend/main/resources/application.properties`.

It also serves the monitoring endpoints of the Quarkus health and metrics extensions on the management interface, a separate HTTP server on <http://localhost:9000>:

| URL | Purpose |
| :--- | :------ |
| <http://localhost:9000/health> | All health checks |
| <http://localhost:9000/health/live> | Liveness |
| <http://localhost:9000/health/ready> | Readiness, including the database check |
| <http://localhost:9000/metrics> | Metrics in the Prometheus exposition format |

The management interface has no authentication by default and ignores an `Authorization` header. In a production build it binds to `0.0.0.0` like the main HTTP server, because `quarkus.management.host` is not overridden; do not expose port `9000` to untrusted networks, keep it on an internal network or behind a firewall. Set `quarkus.management.host` or `QUARKUS_MANAGEMENT_HOST` to bind it to another address. See [SECURITY.md](SECURITY.md).

### Frontend

In a second terminal:

```bash
cd src/frontend
npm run dev
```

The application is available at <http://localhost:5173>.

### Local accounts

| Role   | Username / password |
| :----- | :------------------ |
| Member | `member` / `member` |
| Lead   | `lead` / `lead`     |
| Admin  | `admin` / `admin`   |

The Keycloak admin console is at <http://localhost:8180/admin> with `admin` / `admin`.

### Running without Compose

If you prefer not to use Compose, run PostgreSQL and Keycloak yourself and point cisys at them with the environment variables below. The Keycloak realm must be named `cisys` with the realm roles `member`, `lead` and `admin` and a public client `cisys-ui` using PKCE; `contrib/keycloak/realm.json` can be imported for this.

### Environment variables

| Variable            | Default                                  | Description                                          |
| :------------------ | :--------------------------------------- | :--------------------------------------------------- |
| `KEYCLOAK_URL`      | `http://localhost:8180`                  | Keycloak base URL (the `cisys` realm is appended)    |
| `CISYS_CORS_ORIGINS` | `http://localhost:5173,http://localhost:8180` | Comma-separated exact origins allowed by CORS; never use `*` |
| `SESSION_INVALIDATE_ON_SHUTDOWN` | `true` | Signs all users out of the realm when cisys shuts down |
| `KEYCLOAK_ADMIN_CLIENT_ID` | `cisys-backend` | Keycloak service account used to sign users out |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | `cisys` | Secret of that service account |
| `STATS_CACHE_SECONDS` | `30` | How long the public front-page statistics are cached on the server |
| `PUBLIC_LOG_MAX_BYTES` | `2097152` | Maximum log bytes returned to an anonymous request |
| `PUBLIC_RATE_LIMIT_REQUESTS` | `120` | Anonymous requests per client per window; `0` disables the limiter |
| `PUBLIC_RATE_LIMIT_WINDOW_SECONDS` | `60` | Length of the anonymous rate-limit window |
| `PUBLIC_RATE_LIMIT_MAX_CLIENTS` | `10000` | Upper bound on tracked client addresses |
| `POLL_EVERY` | `60s` | How often automatic builds poll each project's repository |
| `POLL_TICK` | `10s` | How often the timer checks whether the interval has elapsed |
| `POLL_MAX_COMMITS` | `1000` | Safety net for commits queued per branch in one tick |
| `POLL_MIRROR_DIR` | `~/.cisys/mirrors` | One bare mirror per project; must not be under `java.io.tmpdir` |
| `POLL_MIRROR_MAX_MIB` | `4096` | Mirror size limit per project in MiB; `0` turns it off |
| `DB_URL`            | `jdbc:postgresql://localhost:5432/cisys` | JDBC URL                                             |
| `DB_USER`           | `cisys`                                  | Database user                                        |
| `DB_PASSWORD`       | `cisys`                                  | Database password                                    |
| `CISYS_GIT_*`       | not set                                  | Read-only Git provider tokens referenced by an admin-only project credential or per-build reference |
| `CISYS_SECRET_KEY`  | not set                                  | Base64-encoded 32-byte key for encrypted team Git tokens |
| `CISYS_GIT_ALLOWED_HOSTS` | `github.com,gitlab.com`            | HTTPS hosts that may be cloned                       |
| `CISYS_GIT_ALLOWED_PORTS` | `443`                              | Ports that may be used; keep `443` in the list       |
| `CISYS_GIT_ALLOWED_NETWORKS` | not set                        | Private CIDR ranges that may be fetched, for a local server |
| `CISYS_GIT_INSECURE_TLS_HOSTS` | not set                     | Hosts whose self-signed certificate is accepted      |

Team tokens are bound to their team id with AES-256-GCM authenticated encryption. Changing `CISYS_SECRET_KEY` makes existing stored tokens unusable, so they must be re-entered. A public project never uses its team's token.

The runner executes repository-controlled code. Read the **Builds** chapter of the manual and [SECURITY.md](SECURITY.md) before using it.

`CISYS_SECRET_KEY` must decode from base64 to exactly 32 bytes. Generate one with:

```bash
openssl rand -base64 32
```

Keep the key outside the repository and stable across restarts. The application still starts when it is missing, but team-token writes return `503`. If it changes, existing encrypted tokens cannot be decrypted and must be re-entered. There is no key-rotation support.

### Running the built frontend on another origin

The frontend is a static bundle (`npm --prefix src/frontend run build`, output in `src/frontend/dist`). cisys does not serve it: a static host or a reverse proxy serves the bundle and forwards `/api` to the backend. Deploying that host is out of scope here; this section only covers the endpoints the bundle uses.

The bundle reads its endpoints at runtime from `/config.js` (copied from `src/frontend/public/config.js` into `dist`). Edit `config.js` next to `index.html`; no rebuild is needed:

```js
window.cisysConfig = {
  apiUrl: "https://ci.example.com/api",
  keycloakUrl: "https://sso.example.com",
  realm: "cisys",
  clientId: "cisys-ui",
};
```

Leave a key out to keep the local development default for it. For a non-localhost deployment:

- **Backend CORS.** Set `CISYS_CORS_ORIGINS` to the exact frontend origins, for example `https://ci.example.com`. Use scheme, host and port, list them comma-separated, and never use `*`. The defaults are `http://localhost:5173,http://localhost:8180`.
- **Keycloak realm.** On the `cisys-ui` client, add the deployed frontend origin to **Valid redirect URIs**, **Valid post logout redirect URIs** and **Web origins**. The development values are `http://localhost:5173/*` (and `http://localhost:5173` for web origins). A realm imported before this can be updated in the Keycloak admin console.

The default `config.js` is empty, so the dev setup runs unchanged on `http://localhost:5173` with the defaults above.

## Run tests

Backend tests run against a real PostgreSQL that Quarkus Dev Services starts in a container (`postgres:18`), so Docker or Podman must be running. Flyway applies the migrations and Hibernate validates the schema, as in production. OIDC is disabled and `@TestSecurity` supplies users, so Keycloak is not needed, and the `make platform-start` database is not used:

```bash
mvn test
```

An opt-in smoke test fetches a public repository and runs a container. It requires a trusted Linux Docker host with `alpine:3.23` already pulled:

```bash
CISYS_RUNNER_SMOKE=true mvn -Dtest=RunnerSmokeTest test
```

Check the frontend:

```bash
cd src/frontend
npm run lint
npm run build
```

## Build documentation

Generate the English manual as HTML and PDF (Eisvogel) into `target/`:

```bash
mvn -Pmanual-docs generate-resources
```

or run the script directly:

```bash
cd docs/manual/en
./generate-manual.sh
```

Generated files:

- `target/cisys-en.html`
- `target/cisys-en.pdf`

Pass a directory as the first argument to write the output elsewhere. If the Eisvogel template is stored in a non-standard location, set `EISVOGEL_TEMPLATE`. See [DEVELOPERS.md](DEVELOPERS.md) for the required packages.

If PDF generation fails with `Mismatched LaTeX support files detected`, refresh the XeLaTeX format and rerun:

```bash
fmtutil-user --byfmt xelatex
```

If your TeX installation is managed system-wide and the user refresh does not help, rebuild the system format instead with `sudo fmtutil-sys --byfmt xelatex`.

## Makefile

The `Makefile` wraps the common commands. The `frontend` target runs `npm ci` when dependencies are missing or when `src/frontend/package.json` or `package-lock.json` changes.

The targets are

* `all` - Clean, lint and run (Default)
* `setup` - Install frontend dependencies and the Git hooks
* `clean` - Clean
* `lint` - Run the frontend ESLint check
* `check` - Run the frontend checks (lint and build), as CI does
* `format` - Format the backend and the whole `src/frontend` folder (Prettier)
* `format-check` - Check formatting of all files without changing them, as CI does
* `checkstyle` - Run the Checkstyle rules, including JavaDoc on public and protected types and methods (see `checkstyle.xml`)
* `build` - Build the frontend
* `run` - Run the backend and the frontend in development mode
* `test` - Run the frontend lint, the formatting and Checkstyle checks and the backend test cases
* `docs` - Generate the manual
* `platform-start` - Start compose services (PostgreSQL + Keycloak)
* `platform-stop` - Stop the compose services
* `reset` - Stop the compose services and delete their data volumes, after confirmation
* `full` - Run everything (platform-start, clean, frontend, lint, test, build, docs, run)

The `platform-start`, `platform-stop` and `reset` targets use Podman Compose when available, otherwise Docker Compose.

## Windows

Use Docker Desktop with its WSL 2 backend and Ubuntu. Extract or clone the project into your Ubuntu home directory, install Java, Maven, Node.js 24 and npm inside Ubuntu, and follow the steps above from an Ubuntu terminal. Open
<http://localhost:5173> in your Windows browser.
