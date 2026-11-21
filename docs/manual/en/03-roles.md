\newpage

# Roles

[**cisys**](https://github.com/mnemosyne-systems/cisys) uses two sets of roles:

* **Keycloak realm roles** — `member`, `lead` and `admin`. They are assigned to the account and decide what you can do at the instance level, and are checked on every request.
* **Project access rows** — `MEMBER` and `LEAD` in the project's own `projectmember` table, and in the reusable `team` the project may belong to. They decide what a user can do inside a project. The project creator is automatically assigned a direct `LEAD` when the project is created, and a project always keeps at least one direct `LEAD`.

The effective role in a project is the higher of the caller's direct project role and their role in that project's team. A team `LEAD` therefore supplies project `LEAD` access, while a team `MEMBER` supplies project `MEMBER` access. Team membership never counts toward the rule that a project keeps at least one direct `LEAD`.

The two kinds of role are independent: a member with `LEAD` realm role is still a mere `MEMBER` of projects where they were assigned with the `MEMBER` role, and a `LEAD` row in a project or team does not grant a caller any global `lead` realm powers. A global admin bypasses project roles; a global `lead` does not gain access to every project.

## Member

The **Member** role provides standard project access without project-management privileges. Members can view public projects, the private projects they have direct or team access to, and the build history and logs of the projects they can access. Members of a project (or users with a `LEAD` row in it, directly or through its team) can start builds there; they cannot edit the project, manage its team, pin builds, or delete it.

## Lead

The **Lead** role is for people who run projects. Leads can create projects; the creator becomes the project's `LEAD` automatically. Every `LEAD` of a project can edit it, pin and unpin builds, start builds, and manage its direct members from the project's settings page (Team tab, `/<name>/settings?tab=team`): adding existing users to the project, assigning them `MEMBER` or `LEAD`, changing a member's role, and removing members. Leads can also create reusable teams.

A project always keeps at least one direct `LEAD`; removing or demoting the last one is rejected with `HTTP 409 Conflict`, even for an administrator. A project's creator is additionally protected: a project `LEAD`, including the creator themselves and any other project `LEAD`, cannot remove the creator or demote them out of `LEAD` while the creator remains `LEAD`. Only an administrator can remove or demote the creator; once that is done, the creator is managed like any other member and there is no automatic re-promotion.

## Admin

The **Admin** role manages the installation. Admins can see every project, the entire user directory and every team. They can manage any project, its direct members, any team member and any team token; start, cancel and pin builds; use credential references for private Git repositories; manage the build images, change the branding, and change the settings such as build retention, the build queue, the hosts build steps may reach, the checkout limits and the build resource limits. Only administrators can delete projects. A project keeps at least one direct `LEAD` even for an administrator, and the last one is protected: it cannot be removed or demoted (also `HTTP 409`). A team always keeps at least one `LEAD` under the same rule.

## Teams

Teams have an owner subject, a unique case-insensitive name, members with `MEMBER` or `LEAD` roles, and one encrypted repository token. A public project never uses its team's token. The Teams section is under **Users**; the Users API does not assign team memberships.

Team members and administrators can view a team. A team `LEAD` or administrator can rename it, add or remove members, change member roles, set or clear its token, and delete it. An attempted removal or demotion that would leave a team without a `LEAD` returns `HTTP 409`, including for administrators. Only an administrator can remove or demote the team creator.

Deleting a team detaches it from its projects; direct project members and their roles remain unchanged.

The project creator or a direct project `LEAD` can change a project's team only when they are also a `LEAD` of the target team; administrators can always do so. This requirement prevents a project lead from attaching a project to a team whose token they do not control. Clearing a team requires only the project creator or a direct project `LEAD`.

At project creation, `teamId` is optional. The creator must lead the selected team, unless they are an administrator. No personal team is created automatically.

The project Team tab manages direct project members only. Team-membership writes from the Teams API and user deletion use the same locked service, so the last-`LEAD` rule is safe under concurrent requests.

## Access summary

The Member, Lead and Admin columns below refer to the caller's global Keycloak realm role; what a caller can do inside a project is decided by their direct `projectmember` row there (`MEMBER` or `LEAD`) or by their role in the project's team. A global `lead` has no automatic power inside a project whose access rows were assigned differently.

| Action                              | Member | Lead | Admin |
| :---------------------------------- | :----- | :--- | :---- |
| View public projects                | yes    | yes  | yes   |
| View assigned private projects      | yes    | yes  | yes   |
| View other users' private projects  | no     | no   | yes   |
| Create a project                    | no     | yes  | yes   |
| View build history and logs         | yes    | yes  | yes   |
| Start a build                       | assigned (MEMBER) | assigned | yes   |
| Pin / unpin a build                 | no     | assigned | yes   |
| Comment on a build                  | assigned | assigned | yes   |
| Edit a comment                      | own, assigned | own, assigned | own |
| Delete a comment                    | own, assigned | own; any assigned (LEAD) | yes |
| Create, edit and delete articles    | if allowed | if allowed | yes |
| Cancel a build                      | no     | own, or any in their project | yes |
| Manage project team                | no     | assigned | yes   |
| View build reports                  | no     | assigned (LEAD) | yes |
| Delete a project                    | no     | no   | yes   |
| Use a Git credential reference      | no     | no   | yes   |
| Create a team                       | no     | yes  | yes   |
| Manage a team                       | no     | team `LEAD` | yes   |
| Set or clear a team token           | no     | team `LEAD` | yes   |
| Manage build images                 | no     | no   | yes   |
| Change branding                     | no     | no   | yes   |
| Change settings                     | no     | no   | yes   |
| View the audit log                  | no     | no   | yes   |

* "Assigned" means the caller has a direct `projectmember` row or belongs to the project's team; for Start a build any role (`MEMBER` or `LEAD`) suffices, while Pin / unpin, Cancel of others' builds and project management require `LEAD`.
* In the Cancel row, the global realm `lead` role additionally allows cancelling your own requested builds regardless of project rows.
* "own" means builds requested by the caller themselves (while holding the global `lead` realm role); "any in their project" means any build in a project where the caller is project `LEAD`, regardless of who requested it.
* Everyone can read articles, also without signing in. Who may write them is set under Settings → General: admins only (the default), leads and admins, or all users.
* The address of a project or build that does not exist or is not visible to the user opens the front page instead, so a private project is not revealed; the same applies to any unknown address.

The following chapters describe the application areas in more detail.
