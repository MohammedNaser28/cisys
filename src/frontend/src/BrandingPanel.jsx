/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useRef, useState } from "react";
import { Save, Upload } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Card, CardContent } from "./components/ui/card";

// Matches the backend limit on stored images.
const MAX_IMAGE_BYTES = 250000;
// The logo keeps transparency as PNG; the background is a photo, so JPEG compresses it far better.
const IMAGE_TARGETS = {
  logo: { type: "image/png", maxSide: 512 },
  background: { type: "image/jpeg", maxSide: 2560 },
};

function readDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

// Scales and re-encodes an image of any size until it fits the upload limit; small PNG/JPEG files pass through unchanged.
async function fitImage(file, { type, maxSide }) {
  const bitmap = await createImageBitmap(file);
  try {
    const { width, height } = bitmap;
    if (
      ["image/png", "image/jpeg"].includes(file.type) &&
      file.size <= MAX_IMAGE_BYTES &&
      Math.max(width, height) <= maxSide
    )
      return await readDataUrl(file);
    const canvas = document.createElement("canvas");
    let scale = Math.min(1, maxSide / Math.max(width, height));
    for (let attempt = 0; attempt < 12; attempt++, scale *= 0.75) {
      canvas.width = Math.max(1, Math.round(width * scale));
      canvas.height = Math.max(1, Math.round(height * scale));
      const context = canvas.getContext("2d");
      if (type === "image/jpeg") {
        context.fillStyle = "#fff";
        context.fillRect(0, 0, canvas.width, canvas.height);
      }
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      for (const quality of type === "image/jpeg"
        ? [0.9, 0.8, 0.7]
        : [undefined]) {
        const blob = await new Promise((resolve) =>
          canvas.toBlob(resolve, type, quality),
        );
        if (blob && blob.size <= MAX_IMAGE_BYTES)
          return await readDataUrl(blob);
      }
    }
    throw new Error("Image too large");
  } finally {
    bitmap.close();
  }
}

export default function BrandingPanel({ branding, save }) {
  const [draft, setDraft] = useState(branding);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [fileNames, setFileNames] = useState({});
  const inputs = { logo: useRef(null), background: useRef(null) };
  function change(key, value) {
    setDraft((d) => ({ ...d, [key]: value }));
    setNotice("");
  }
  async function image(key, file) {
    setError("");
    setNotice("");
    if (!file) return;
    setBusy(true);
    try {
      change(key, await fitImage(file, IMAGE_TARGETS[key]));
      setFileNames((n) => ({ ...n, [key]: file.name }));
    } catch {
      setError("Unable to read the image.");
    } finally {
      setBusy(false);
    }
  }
  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const result = await save(draft);
      setDraft(result);
      setNotice("Branding saved.");
    } catch (err) {
      setError(err.message);
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
        <p className="file-help mb-4">
          Changes apply to everyone after they refresh or sign in.
        </p>
        <form onSubmit={submit}>
          <div className="branding-form">
            <div className="form-field">
              <Label htmlFor="brand-name">Display name</Label>
              <Input
                id="brand-name"
                required
                maxLength="80"
                value={draft.name}
                onChange={(e) => change("name", e.target.value)}
              />
            </div>
            <div className="color-fields">
              {[
                ["headerColor", "Header and footer"],
                ["headingColor", "Heading"],
                ["buttonColor", "Buttons"],
              ].map(([key, label]) => (
                <div className="form-field" key={key}>
                  <Label htmlFor={key}>{label} color</Label>
                  <div className="color-control">
                    <input
                      id={key}
                      type="color"
                      value={draft[key]}
                      onChange={(e) => change(key, e.target.value)}
                    />
                    <span>{draft[key]}</span>
                  </div>
                </div>
              ))}
            </div>
            {[
              ["logo", "Logo"],
              ["background", "Background image"],
            ].map(([key, label]) => (
              <div className="form-field" key={key}>
                <Label htmlFor={key}>{label}</Label>
                <div className="file-control">
                  <input
                    id={key}
                    ref={inputs[key]}
                    type="file"
                    accept="image/*"
                    className="sr-only"
                    tabIndex={-1}
                    onChange={(e) => {
                      void image(key, e.target.files?.[0]);
                      e.target.value = "";
                    }}
                  />
                  <span className="file-name">
                    {draft[key] ? fileNames[key] || "Current image" : "None"}
                  </span>
                  <Button
                    type="button"
                    variant="outline"
                    size="icon"
                    className="h-10 w-10"
                    disabled={busy}
                    title={`Choose ${label.toLowerCase()}`}
                    aria-label={`Choose ${label.toLowerCase()}`}
                    onClick={() => inputs[key].current?.click()}
                  >
                    <Upload size={16} aria-hidden="true" />
                  </Button>
                </div>
                {draft[key] && (
                  <div className="image-preview">
                    <img src={draft[key]} alt={`${label} preview`} />
                    <button
                      type="button"
                      onClick={() => {
                        change(key, null);
                        setFileNames((n) => ({ ...n, [key]: undefined }));
                      }}
                    >
                      Remove {label.toLowerCase()}
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
          <div className="flex justify-end mt-4">
            <Button
              type="submit"
              size="icon"
              className="h-10 w-10"
              disabled={busy}
              title="Save branding"
              aria-label="Save branding"
            >
              <Save size={16} aria-hidden="true" />
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}
