/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Save } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import NetworkPanel from "./NetworkPanel";
import { Card, CardContent } from "./components/ui/card";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "./components/ui/tabs";
import { Separator } from "./components/ui/separator";
import PageHeader from "./components/layout/PageHeader";
import BrandingPanel from "./BrandingPanel";
import GeneralPanel from "./GeneralPanel";
import PollingPanel from "./PollingPanel";
import PullRequestPanel from "./PullRequestPanel";
import ReportsPanel from "./ReportsPanel";

const SECTIONS = {
  retention: {
    fields: [
      {
        key: "maxAgeDays",
        id: "retention-days",
        min: 0,
        max: 3650,
      },
      {
        key: "maxBuilds",
        id: "retention-builds",
        min: 0,
        max: 10000,
      },
    ],
  },
  builds: {
    fields: [
      {
        key: "maxConcurrent",
        id: "builds-concurrent",
        min: 1,
        max: 16,
      },
      {
        key: "maxQueued",
        id: "builds-queued",
        min: 1,
        max: 1000,
      },
      {
        key: "timeoutMinutes",
        id: "builds-timeout",
        min: 1,
        max: 1440,
      },
    ],
  },
  checkout: {
    fields: [
      {
        key: "maxSizeMib",
        id: "checkout-size",
        min: 1,
        max: 102400,
      },
      {
        key: "maxFiles",
        id: "checkout-files",
        min: 1,
        max: 1000000,
      },
    ],
  },
  artifacts: {
    title: "Artifacts",
    fields: [
      {
        key: "maxSizeMib",
        id: "artifacts-size",
        label: "Max size (MiB)",
        min: 1,
        max: 10240,
      },
      {
        key: "maxFiles",
        id: "artifacts-files",
        label: "Max files",
        min: 1,
        max: 100000,
      },
    ],
  },
  performance: {
    fields: [
      {
        key: "maxMemoryMib",
        id: "performance-memory",
        min: 64,
        max: 65536,
      },
      {
        key: "maxCpus",
        id: "performance-cpus",
        min: 1,
        max: 16,
      },
      {
        key: "maxPids",
        id: "performance-pids",
        min: 16,
        max: 32768,
      },
    ],
  },
};

function SettingsTab({ api, section }) {
  const { t } = useTranslation();
  const { fields } = SECTIONS[section];
  const title = t(`settings.sections.${section}.title`);
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    api(`/settings/${section}`)
      .then(setDraft)
      .catch((e) => setError(e.message));
  }, [api, section]);

  function change(key, value) {
    setDraft((d) => ({ ...d, [key]: value }));
    setNotice("");
  }

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const body = Object.fromEntries(
        fields.map(({ key }) => [key, Number(draft[key])]),
      );
      setDraft(
        await api(`/settings/${section}`, {
          method: "PUT",
          body: JSON.stringify(body),
        }),
      );
      setNotice(t("settings.saved", { section: title }));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="project-card">
      <CardContent className="pt-6">
        {error && (
          <div className="error-message" role="alert">
            {error}
          </div>
        )}
        {notice && (
          <div className="success-message" role="status">
            {notice}
          </div>
        )}
        {!draft ? (
          !error && (
            <p role="status">
              {t("settings.loading", { section: title.toLowerCase() })}
            </p>
          )
        ) : (
          <form onSubmit={submit}>
            <div className="settings-form">
              {fields.map(({ key, id, min, max }) => (
                <div className="form-field" key={key}>
                  <Label htmlFor={id}>
                    {t(`settings.sections.${section}.${key}`)}
                  </Label>
                  <Input
                    id={id}
                    type="number"
                    min={min}
                    max={max}
                    step="1"
                    required
                    value={draft[key]}
                    onChange={(e) => change(key, e.target.value)}
                  />
                </div>
              ))}
            </div>
            <div className="flex justify-end mt-4">
              <Button
                type="submit"
                size="icon"
                disabled={busy}
                title={t("settings.save", { section: title.toLowerCase() })}
                aria-label={t("settings.save", {
                  section: title.toLowerCase(),
                })}
              >
                <Save size={16} aria-hidden="true" />
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

export default function SettingsPanel({ api, branding, saveBranding }) {
  const { t } = useTranslation();
  return (
    <main className="page-container">
      <PageHeader title={t("settings.title")} />
      <Tabs defaultValue="general" className="settings-tabs">
        <TabsList>
          <TabsTrigger value="general">
            {t("settings.tabs.general")}
          </TabsTrigger>
          {Object.keys(SECTIONS).map((value) => (
            <TabsTrigger key={value} value={value}>
              {t(`settings.sections.${value}.title`)}
            </TabsTrigger>
          ))}
          <TabsTrigger value="reports">
            {t("settings.tabs.reports")}
          </TabsTrigger>
          <TabsTrigger value="network">
            {t("settings.tabs.network")}
          </TabsTrigger>
          <TabsTrigger value="branding">
            {t("settings.tabs.branding")}
          </TabsTrigger>
        </TabsList>
        <TabsContent
          value="general"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <GeneralPanel api={api} />
        </TabsContent>
        {Object.keys(SECTIONS).map((value) => (
          <TabsContent
            key={value}
            value={value}
            forceMount
            className="data-[state=inactive]:hidden"
          >
            <SettingsTab api={api} section={value} />
            {value === "builds" && (
              <>
                <Separator className="my-6" />
                <PollingPanel api={api} />
                <Separator className="my-6" />
                <PullRequestPanel api={api} />
                <Separator className="my-6" />
              </>
            )}
          </TabsContent>
        ))}
        <TabsContent
          value="reports"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <ReportsPanel api={api} />
        </TabsContent>
        <TabsContent
          value="network"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <NetworkPanel api={api} />
        </TabsContent>
        <TabsContent
          value="branding"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <BrandingPanel branding={branding} save={saveBranding} />
        </TabsContent>
      </Tabs>
    </main>
  );
}
