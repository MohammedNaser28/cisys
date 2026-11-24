/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { cva } from "class-variance-authority";

export const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center rounded-md border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--color-buttons-bg)] focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      variant: {
        default:
          "bg-[var(--color-buttons-bg)] text-[var(--color-buttons-text)] hover:bg-[var(--color-buttons-hover)] [a]:text-[var(--color-buttons-text)] [a]:hover:text-[var(--color-buttons-text)]",
        outline:
          "border-[var(--color-buttons-bg)] bg-[var(--color-buttons-bg)] text-[var(--color-buttons-text)] hover:bg-[var(--color-buttons-hover)] hover:text-[var(--color-buttons-text)] aria-expanded:bg-[var(--color-buttons-hover)] aria-expanded:text-[var(--color-buttons-text)] [a]:text-[var(--color-buttons-text)] [a]:hover:text-[var(--color-buttons-text)]",
        secondary:
          "bg-[var(--color-buttons-bg)] text-[var(--color-buttons-text)] hover:bg-[var(--color-buttons-hover)] aria-expanded:bg-[var(--color-buttons-hover)] aria-expanded:text-[var(--color-buttons-text)] [a]:text-[var(--color-buttons-text)] [a]:hover:text-[var(--color-buttons-text)]",
        ghost:
          "bg-[var(--color-buttons-bg)] text-[var(--color-buttons-text)] hover:bg-[var(--color-buttons-hover)] hover:text-[var(--color-buttons-text)] aria-expanded:bg-[var(--color-buttons-hover)] aria-expanded:text-[var(--color-buttons-text)] [a]:text-[var(--color-buttons-text)] [a]:hover:text-[var(--color-buttons-text)]",
        destructive:
          "bg-[var(--color-buttons-bg)] text-[var(--color-buttons-text)] hover:bg-[var(--color-buttons-hover)] focus-visible:border-[var(--color-buttons-bg)] focus-visible:ring-[var(--color-buttons-bg)] [a]:text-[var(--color-buttons-text)] [a]:hover:text-[var(--color-buttons-text)]",
        link: "bg-[var(--color-buttons-bg)] text-[var(--color-buttons-text)] hover:bg-[var(--color-buttons-hover)] no-underline [a]:text-[var(--color-buttons-text)] [a]:hover:text-[var(--color-buttons-text)]",
      },
      size: {
        default: "h-10 px-4 py-2",
        sm: "h-9 rounded-md px-3",
        lg: "h-11 rounded-md px-8",
        icon: "h-8 w-8",
        "icon-sm": "h-8 w-8",
        "icon-xs": "h-7 w-7",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);
