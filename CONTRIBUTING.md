# Contributing guide

**Want to contribute? Great!**

All contributions are more than welcome ! This includes bug reports, bug fixes, enhancements, features, questions, ideas, and documentation.

This document will hopefully help you contribute to cisys.

* [Legal](#legal)
* [Reporting an issue](#reporting-an-issue)
* [Setup your build environment](#setup-your-build-environment)
* [Building the main branch](#building-the-main-branch)
* [Before you contribute](#before-you-contribute)
* [Code reviews](#code-reviews)
* [Coding Guidelines](#coding-guidelines)
* [Discuss a Feature](#discuss-a-feature)
* [Development](#development)
* [Code Style](#code-style)

## Legal

All contributions to cisys are licensed under the [Eclipse Public License - v2.0](https://www.eclipse.org/legal/epl-2.0/).

## Reporting an issue

This project uses GitHub issues to manage the issues. Open an issue directly in GitHub.

If you believe you found a bug, and it's likely possible, please indicate a way to reproduce it, what you are seeing and what you would expect to see. Don't forget to indicate your cisys version (or commit).

Do not report security problems in a public issue; see [docs/SECURITY.md](docs/SECURITY.md).

## Setup your build environment

You need

* Java 25 or newer
* [Apache Maven](https://maven.apache.org/)
* [Node.js 24 and npm](https://nodejs.org/)
* Docker Compose or Podman with Compose (for PostgreSQL and Keycloak)
* Git

See [docs/BUILDING.md](docs/BUILDING.md) for details.

## Building the main branch

To build the `main` branch:

```
git clone https://github.com/mnemosyne-systems/cisys.git
cd cisys
docker compose up -d
mvn quarkus:dev
```

and in a second terminal

```
cd src/frontend
npm install
npm run dev
```

and you will have a running instance at <http://localhost:5173>.

## Before you contribute

To contribute, use GitHub Pull Requests, from your **own** fork.

Also, make sure you have set up your Git authorship correctly:

```
git config --global user.name "Your Full Name"
git config --global user.email your.email@example.com
```

We use this information to acknowledge your contributions in release announcements.

## Code reviews

GitHub pull requests can be reviewed by all such that input can be given to the author(s).

See [GitHub Pull Request Review Process](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/reviewing-changes-in-pull-requests/about-pull-request-reviews) for more information.

## Coding Guidelines

* Discuss the feature
* Do development
  + Follow the code style
  + Run `make test` before you submit
* Commits should be atomic and semantic. Therefore, squash your pull request before submission and keep it rebased until merged
  + If your feature has independent parts submit those as separate pull requests

## Discuss a Feature

You can discuss bug reports, enhancements and features in our [forum](https://github.com/mnemosyne-systems/cisys/discussions).

Once there is an agreement on the development plan you can open an issue that will used for reference in the pull request.

## Development

You can follow this workflow for your development.

Add your repository

```
git clone git@github.com:yourname/cisys.git
cd cisys
git remote add upstream https://github.com/mnemosyne-systems/cisys.git
```

Create a work branch

```
git checkout -b mywork main
```

During development

```
git commit -a -m "[#issue] My feature"
git push -f origin mywork
```

If you have more commits then squash them

```
git rebase -i HEAD~2
git push -f origin mywork
```

If the `main` branch changes then

```
git fetch upstream
git rebase -i upstream/main
git push -f origin mywork
```

as all pull requests should be squashed and rebased.

In your first pull request you need to add yourself to the `AUTHORS` file.

## Code Style

Please, follow the coding style of the project. Format with `make format` before committing; CI checks formatting of all files. Java is formatted with [google-java-format](https://github.com/google/google-java-format) (2-space indent, Spotless) and is additionally checked with Checkstyle (`make checkstyle`, rules in `checkstyle.xml`). The frontend is formatted with Prettier (the whole `src/frontend` folder) and checked with ESLint (`npm run lint` in `src/frontend`), which also runs as a Husky `pre-commit` hook on staged files. Run the frontend lint, the formatting and Checkstyle checks and the backend test cases with `make test`.
