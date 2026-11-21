\newpage

# Projects

A **project** registers a Git repository with cisys so that it can be built.

![Projects, Admin view](images/projects-admin.png){ width=100% }

## Visibility

* **Public** projects are visible to every signed-in user.
* **Private** projects are visible only to their direct members, members of linked groups, and admins. Direct and linked project roles combine by highest role.
* Only a global admin can switch a project from private to public. A project LEAD can switch a public project to private.

The private flag controls visibility of the project and its build logs inside cisys. It does not grant or restrict access to the Git repository itself.

![Projects, Member view](images/projects-member.png){ width=100% }

## Public project page

Every public project has a page at `/<name>/`, for example `http://localhost:5173/cisys/`. It shows the repository branches in a panel on the left and the build history in a table on the right, newest first. Anonymous visitors can read it.

Selecting a branch in the left panel filters the build history to that branch. The page starts on `main`, or `master` for repositories that still use it. Each build row shows a green thumbs up for a passing build, a red thumbs down for a failing build, and a spinner while it runs, followed by the build number, branch, commit, start time, duration and exit code.

Selecting a build opens its own page at `/<name>/<branch>/build/<id>`, for example `http://localhost:5173/cisys/master/build/51`, with the build details and the full log. The `<id>` is the internal build ID, not the build number.

The page has **Builds** and **Commits** tabs. **Builds** is the default. **Commits** lists the selected branch's Git history, newest first, with the short commit ID, message, author, date and links to every build of that commit. Selecting a branch filters both tabs. Add `?tab=commits` to the project URL to open the Commits tab directly, for example `http://localhost:5173/cisys/?tab=commits`; after opening a build from that tab, the browser's Back button returns to it.

The history comes from cisys's repository mirror rather than from a fresh repository read on every page view. When the mirror directory or the selected branch is absent, the tab shows the commits that cisys already has builds for and explains that full history is unavailable. A private project without a configured credential is never polled, so it uses that fallback too. cisys shows at most the newest 5000 commits; if the branch has older history, it says that the history was truncated only when a request reaches that limit, so a branch with exactly 5000 commits is complete. The list loads 50 commits at a time; **Load older commits** fetches the next page. If the mirror exists but cannot be read, the tab shows an error and a retry button.

Private projects use the same page once you sign in as a member of the project or an administrator; everyone else is told the project does not exist. The branches panel reads the repository when the project has a configured credential (see below); without one it lists only the branches that already have builds.

## Create a project

Leads and admins can create projects. Enter

* **Name**: up to 100 letters, digits, `.`, `_` and `-`, starting with a letter or digit, for example `my-project`. The name is the project's URL, `/<name>/`. `manual` is reserved for the manual at `/manual`
* **Repository URL**: an HTTPS URL without embedded credentials, up to 2048 characters
* **Private**: whether the project is private
* **Credential reference**: admins only, and only for private projects. The name of an environment variable that holds a read-only token for the repository, for example `CISYS_GIT_PROJECT`. The token itself is not entered in this reference field; stored tokens are managed separately and encrypted in the database.

The project credential is used for branch discovery, the poller's mirror fetches, automatic builds, and manual or rerun builds that do not supply a per-build credential. A project may hold both an environment-variable reference and an encrypted stored token; the stored token wins. The Commits tab reads the local mirror and does not resolve the credential itself. An admin can name another reference for one build through the API (`POST /api/projects/{id}/builds` with `credentialRef`). See **Private Git repositories** in the **Builds** chapter for a worked example. An admin can change or clear the environment-variable reference later on the project's settings page (`PUT /api/projects/{id}`) or directly through `PUT /api/projects/{id}/credential`; a project LEAD or admin sets or clears the stored token through `PUT` or `DELETE /api/projects/{id}/credential/token`. A project LEAD's URL change clears the stored token and the environment reference; an admin's URL change clears the stored token and replaces the environment reference with the value in the request. Making the project public clears both. Only a global admin can make a private project public.

Admins and project LEADs can open settings with the pencil button. **Save** applies the change and returns to the project page; **Cancel** leaves without saving. Queued builds keep their repository URL and branch. A queued build never uses a project-level stored token or environment-variable fallback for a different URL than the one captured when it was queued; it fails closed. For the same URL, a rotated stored token is resolved when execution begins. The change affects new page views and new builds.

Projects created before this naming rule keep working. An administrator can rename one in the database; its builds follow, because they reference the project's id:

```sql
UPDATE project SET name = 'new-name' WHERE name = 'old name';
```

The repository must be on an allowed host and must not resolve to a loopback, private, link-local or metadata address. By default only `github.com` and `gitlab.com` on port 443 are allowed; the backend settings `cisys.git.allowed-hosts`, `cisys.git.allowed-ports` and `cisys.git.allowed-networks` extend that list. A local server with a self-signed certificate can be named in `cisys.git.insecure-tls-hosts`; adding its certificate to the JVM truststore is preferred.

## Ownership

Two different things are recorded per project:

* `owner_subject` — the Keycloak subject of the user who created the project. It is written once at creation and **never changes afterwards**, not even when the team changes. It identifies the original creator and is useful as a marker of who started the project (for example, for audit). It is not used to drive current authorization, and it does not follow an administrator who promotes other users to `LEAD`.
* `projectmember` rows — one row per (project, user), holding `MEMBER` or `LEAD`. These rows decide what a user can actually do in the project: `MEMBER` can view and start builds; `LEAD` can also edit the project, pin and unpin builds, and manage its team.

## Groups

The **Groups** page lists application groups. A group role controls only membership management: it grants nothing on projects. A project LEAD or admin links a group to a project with a `MEMBER` or `LEAD` link role; every group member receives that link role. Direct and linked roles combine by highest role, and removing one assignment does not remove the other. The Team tab remains a direct-membership view.

The creator is automatically assigned a `LEAD` membership at project creation. A project can have more than one `LEAD`. A project always keeps at least one `LEAD`; the system refuses to remove or demote the last `LEAD` and reports an `HTTP 409 Conflict` — even for an administrator. A project `LEAD` (including the creator themselves, and another `LEAD`) cannot remove the creator or demote them out of `LEAD` while they remain `LEAD`; that is reserved for administrators. Once an administrator has demoted the creator to `MEMBER`, the creator is no longer protected and the normal team rules apply; `owner_subject` is left untouched either way.

Databases created before the project team was introduced do not carry any team data over: `R__schema.sql` repairs nothing, so the team is absent for existing projects. Upgrading such a database requires `make reset`, which deletes the local PostgreSQL and Keycloak data volumes, including projects, builds and users. Databases created after this change are not affected, and not every future upgrade requires a reset.
