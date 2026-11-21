\newpage

# Getting started

This chapter describes how to prepare a local development setup for [**cisys**](https://github.com/mnemosyne-systems/cisys) and how to start the application in development mode.

## Requirements

Before you begin, make sure the following tools are available:

* [Java](https://openjdk.org/) 25 or newer
* [Maven](https://maven.apache.org/)
* [Node.js 24 and npm](https://nodejs.org/)
* [Docker Compose](https://docs.docker.com/compose/) or [Podman](https://podman.io/) with Compose
* [Git](https://git-scm.com/)

Container images and dependencies must be downloadable. This setup is for a trusted local machine only.

## Get the source code

Clone the repository and enter the project directory:

```sh
git clone https://github.com/mnemosyne-systems/cisys.git
cd cisys
```

## Start PostgreSQL and Keycloak

Start PostgreSQL and Keycloak:

```sh
make platform-start
```

Wait for Keycloak to become available at <http://localhost:8180>.

The default local configuration expects a PostgreSQL database named `cisys` with user `cisys` and password `cisys`. These defaults can be changed through the `DB_URL`, `DB_USER` and `DB_PASSWORD` environment variables.

The Keycloak realm is imported on the first startup only; existing Keycloak data is not overwritten. To recreate the local development data, run `make reset`. This deletes the local databases.

Introducing direct project membership requires a database reset for databases created before this change: run `make reset`, which deletes the local PostgreSQL and Keycloak data volumes, including projects, builds and users. New projects automatically assign their creator the LEAD role at creation time; existing membership data is not migrated or repaired. Databases created after the change are not affected, and not every future upgrade requires a reset. The reusable Teams feature needs no reset: `R__schema.sql` adds its tables and the project's `teamid` column to existing databases.

## Start the application

Start the application:

```sh
make run
```
Open <http://localhost:5173>.

## First login

The development realm contains the following accounts:

* Member: `member` / `member`
* Lead: `lead` / `lead`
* Admin: `admin` / `admin`

The Keycloak admin console is at <http://localhost:8180/admin> with `admin` / `admin`.

![Projects, Admin view](images/projects-admin.png){ width=100% }

## Windows

Install Docker Desktop with its WSL 2 backend and Ubuntu. Put the project in your Ubuntu home directory, install Java, Maven, Node.js 24 and npm inside Ubuntu, and follow the steps above from an Ubuntu terminal.
