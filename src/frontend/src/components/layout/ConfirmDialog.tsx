/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import * as React from "react";
import { Trash2, X } from "lucide-react";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import { Button } from "../ui/button";

type ConfirmDialogProps = {
  open: boolean;
  title: string;
  message: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
};

/**
 * A confirmation pop-up in the site's branding: the title sits on a band in the branding header
 * colour and the buttons use the branding button colour. Used instead of window.confirm, which
 * the browser draws unbranded.
 */
export default function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = "Delete",
  cancelLabel = "Cancel",
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && onCancel()}>
      <AlertDialogContent className="overflow-hidden pt-0">
        <AlertDialogHeader className="place-items-start text-left">
          <AlertDialogTitle className="-mx-4 w-[calc(100%+2rem)] bg-[var(--header-bg)] px-4 py-3 text-[var(--header-text)]">
            {title}
          </AlertDialogTitle>
          <AlertDialogDescription className="pt-2">
            {message}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter className="flex-row justify-end">
          <Button
            type="button"
            variant="outline"
            size="icon"
            title={cancelLabel}
            aria-label={cancelLabel}
            onClick={onCancel}
          >
            <X size={16} aria-hidden="true" />
          </Button>
          <Button
            type="button"
            size="icon"
            title={confirmLabel}
            aria-label={confirmLabel}
            onClick={onConfirm}
          >
            <Trash2 size={16} aria-hidden="true" />
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
