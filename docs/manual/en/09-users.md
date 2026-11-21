\newpage

# Users

The **Users** page manages the people who use [**cisys**](https://github.com/mnemosyne-systems/cisys) and the projects they are assigned to. It is in the main menu for leads and admins. Users, passwords and roles are stored in Keycloak; cisys keeps only the projects each user is assigned to and their team memberships.

Team membership is managed separately on the **Teams** section under **Users**. Use that page to add, remove, or change team members and their roles.

## Admins

Admins see every user and can

* create a user with any role, a temporary password and any projects
* change a user's email, names, role, projects and whether the account is enabled
* set a temporary password
* delete a user

Admins cannot remove their own admin role, disable themselves or delete themselves.

## Leads

A lead's projects are the projects the lead created or is assigned to. Leads see the users assigned to their projects and can

* create a member, assigned to at least one of their projects
* assign a member to, or remove a member from, their projects; searching also finds members of other projects
* set a temporary password for a member assigned to their projects

Leads cannot create leads or admins, change roles or delete users, and see only their own projects in each user's assignments.

## Members

Members have no access to the Users page.

## Large organizations

The list is paged and can be searched by username, email or name, and filtered by role and by project. A user can be assigned to several projects at once.

## Temporary passwords

A new user, or one whose password was set from the Users page, must choose a new password when signing in next. Keycloak's password policy applies.

## Keycloak

The backend uses the `cisys-backend` service account, which needs the realm-management roles `manage-users`, `view-users` and `view-realm`. `contrib/keycloak/realm.json` grants them, but it is only imported into a new realm: grant them in the Keycloak admin console for an existing realm, or run `make reset`.
