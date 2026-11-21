/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import UsersPanel from "./UsersPanel";
import TeamsPanel from "./TeamsPanel";
import PageHeader from "./components/layout/PageHeader";
import { Tabs, TabsList, TabsTrigger } from "./components/ui/tabs";

export default function UsersPage({ api, user }) {
  const { t } = useTranslation();
  const canManageUsers =
    user.roles.includes("lead") || user.roles.includes("admin");
  const [section, setSection] = useState(canManageUsers ? "users" : "teams");

  const SectionSwitch = (
    <Tabs value={section} onValueChange={setSection} className="settings-tabs">
      <TabsList aria-label={t("users.sections")}>
        {canManageUsers && (
          <TabsTrigger value="users">{t("nav.users")}</TabsTrigger>
        )}
        <TabsTrigger value="teams">{t("nav.teams")}</TabsTrigger>
      </TabsList>
    </Tabs>
  );

  if (!canManageUsers) {
    return (
      <main id="users" className="page-container">
        <PageHeader title={t("nav.teams")} />
        <TeamsPanel api={api} user={user} />
      </main>
    );
  }

  if (section === "users") {
    return <UsersPanel api={api} user={user} tabs={SectionSwitch} />;
  }

  return (
    <main id="users" className="page-container">
      <PageHeader title={t("nav.users")} />
      {SectionSwitch}
      <TeamsPanel api={api} user={user} />
    </main>
  );
}
