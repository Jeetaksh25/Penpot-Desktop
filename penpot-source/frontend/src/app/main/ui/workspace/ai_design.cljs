;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-design
  "The shared design system for Ovion's AI surfaces, rebuilt to match the
  Penpot-generated UI reference exactly (UI_Reference/*.html).

  The reference is a deliberate, authored visual world — not a generic SaaS
  bar — so the tokens here are PINNED to its exact values rather than derived
  from Penpot's theme tokens. The reference's accent is the same coral (#f28b82) the app chrome
  now uses (ds/colors.scss $peach-500 = #f28b82), so --ai-coral matches
  --color-accent-primary. This is intentional: the AI surfaces are
  a distinct layer on top of the base app and carry their own identity, the way
  the reference draws them.

  Visual language (from the reference):
    - White pill surfaces (#ffffff) with a soft outer shadow
      (0 0 20px 1px rgba(0,0,0,.3)) and a 5px coral inner ring drawn as a
      transparent-fill rect with a #f28b82 border (the 'inset coral outline').
    - Small controls (mode pill, icon circles) carry a tiny drop shadow
      (1px 1px 2px 0px rgba(0,0,0,.3)) plus an inset coral glow
      (inset 0 0 2px 1px rgba(242,139,130,.4)).
    - The send button is a solid coral disc with an inset WHITE glow
      (inset 0 0 2px 1px rgba(255,255,255,.4)) and a white arrow-up glyph.
    - Inactive icons + placeholder text are grey #7d7d7d at stroke-width 2.
    - Active mode state turns the label + icon coral (#f28b82).
    - Everything is fully rounded (pill) at rest; the input pill drops to a
      rounded rectangle (~22px radius) when the prompt grows past one line,
      mirroring the reference's 169px -> 48px radius shift.
    - Type is Helvetica Now Display (self-hosted; see @font-face below).

  This file exports:
    - `ai-base-css`  — tokens + keyframes + @font-face + reduced-motion guard.
                      Injected once per AI surface alongside that surface's
                      own CSS. Idempotent (duplicate injection is harmless).
    - `cubic-*`, `dur-*` — timing constants reused by the CLJS motion helpers.
    - `(base-style-block)` — the `[:style ...]` to render once per surface.

  Motion thesis (Operate mode): the bar is a tool, not a stage. Motion serves
  feedback (press, toggle), state (mode-active cross-fade), and continuity
  (pill grow, calm entrance). No choreographed entrances, no loops, no
  spectacle — the reference is calm and confident, so the motion is too.")

;; ── Motion constants (mirrored in CSS below) ─────────────────────────────────
;;
;; Calm Operate timings. Keep in sync with the `--ai-dur-*` / `--ai-ease-*`
;; tokens in `ai-base-css`. No spring/bounce — the reference never does.

(def cubic-out "cubic-bezier(0.16, 1, 0.3, 1)")      ; confident arrival
(def cubic-in-out "cubic-bezier(0.65, 0, 0.35, 1)")

(def dur-fast 150)
(def dur-base 220)
(def dur-slow 320)
(def dur-slower 460)

(def ^:private ai-base-css
  "
/* ── Ovion AI design system — reference-pinned base ────────────────────────────
   Scoped under .ai-root / .ais-root so it never collides with Penpot's own
   styles. Colors are the reference's exact values (not theme tokens) so the
   AI surfaces match the reference in both light and dark app themes. ─────── */

@font-face {
  font-family: 'Helvetica Now Display';
  src: url('/fonts/HelveticaNowDisplay-Light.woff2') format('woff2'),
       url('/fonts/HelveticaNowDisplay-Light.ttf') format('truetype');
  font-weight: 300; font-style: normal; font-display: swap;
}
@font-face {
  font-family: 'Helvetica Now Display';
  src: url('/fonts/HelveticaNowDisplay-Regular.woff2') format('woff2'),
       url('/fonts/HelveticaNowDisplay-Regular.ttf') format('truetype');
  font-weight: 400; font-style: normal; font-display: swap;
}
@font-face {
  font-family: 'Helvetica Now Display';
  src: url('/fonts/HelveticaNowDisplay-Medium.woff2') format('woff2'),
       url('/fonts/HelveticaNowDisplay-Medium.ttf') format('truetype');
  font-weight: 500; font-style: normal; font-display: swap;
}
@font-face {
  font-family: 'Helvetica Now Display';
  src: url('/fonts/HelveticaNowDisplay-Bold.woff2') format('woff2'),
       url('/fonts/HelveticaNowDisplay-Bold.ttf') format('truetype');
  font-weight: 700; font-style: normal; font-display: swap;
}

.ai-root, .ai-root *, .ais-root, .ais-root * { box-sizing: border-box; }
.ai-root, .ais-root {
  /* ── Pinned palette (from UI_Reference) ─────────────────────────────────── */
  --ai-coral: #f28b82;                 /* the reference accent */
  --ai-coral-press: #e0756b;           /* a touch deeper for :active/:hover */
  --ai-coral-soft: rgba(242, 139, 130, 0.4);   /* inset glow + ring fill tint */
  --ai-coral-faint: rgba(242, 139, 130, 0.12); /* quiet hover wash */
  --ai-grey: #7d7d7d;                  /* inactive icon + placeholder text (reference) */
  --ai-grey-2: #6b6b6b;                /* secondary text on white — AA for 12.5px */
  --ai-white: #ffffff;
  --ai-ink: #4a4a4a;                  /* typed prompt text (>=4.5:1 on white) */
  --ai-overlay: rgba(0, 0, 0, 0.32);  /* modal scrim, matches #0000004D-ish */

  /* ── Surfaces ───────────────────────────────────────────────────────────── */
  --ai-bg: var(--ai-white);
  --ai-surface: var(--ai-white);
  --ai-surface-2: #fafafa;

  /* ── Shadows (reference exact) ──────────────────────────────────────────── */
  --ai-shadow-soft: 0 0 20px 1px rgba(0, 0, 0, 0.3);        /* big pills */
  --ai-shadow-btn: 1px 1px 2px 0px rgba(0, 0, 0, 0.3);      /* small controls */
  --ai-inset-coral: inset 0 0 2px 1px var(--ai-coral-soft); /* coral inset glow */
  --ai-inset-coral-hover: inset 0 0 3px 2px var(--ai-coral-soft); /* hover: ring intensifies (wider spread, same coral) */
  --ai-shadow-btn-hover: 1px 2px 5px 0px rgba(0, 0, 0, 0.32);    /* hover: small control shadow deepens + drops a hair */
  --ai-inset-white: inset 0 0 2px 1px rgba(255, 255, 255, 0.4); /* send button */

  /* ── Radii ──────────────────────────────────────────────────────────────── */
  --ai-radius-pill: 999px;             /* fully rounded (resting pill) */
  --ai-radius-expanded: 22px;         /* rounded-rect when prompt grows */
  --ai-radius-md: 14px;
  --ai-radius-sm: 10px;

  /* ── Spacing — 4/8 rhythm ───────────────────────────────────────────────── */
  --ai-sp-0: 0px;
  --ai-sp-1: 4px;
  --ai-sp-2: 8px;
  --ai-sp-3: 12px;
  --ai-sp-4: 16px;
  --ai-sp-5: 20px;
  --ai-sp-6: 24px;
  --ai-sp-7: 32px;

  /* ── Motion ────────────────────────────────────────────────────────────── */
  --ai-dur-fast: 150ms;
  --ai-dur-base: 220ms;
  --ai-dur-slow: 320ms;
  --ai-dur-slower: 460ms;
  --ai-ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  --ai-ease-in-out: cubic-bezier(0.65, 0, 0.35, 1);

  /* ── Type ───────────────────────────────────────────────────────────────── */
  --ai-font: 'Helvetica Now Display', 'Helvetica Neue', Helvetica, Arial, sans-serif;

  color: var(--ai-ink);
  font-family: var(--ai-font);
  -webkit-font-smoothing: antialiased;
}

/* ── Keyframes (calm, purposeful — no loops, no spectacle) ─────────────────── */

@keyframes ai-spin { to { transform: rotate(360deg); } }

/* Quiet bar entrance: a small rise + fade, expo settle. Runs once on mount. */
@keyframes ai-rise {
  from { opacity: 0; transform: translateY(10px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* Mode-pill icon swap: the outgoing icon rotates out, the incoming rotates in.
   Driven by a CSS class toggle; keeps the toggle legible without a full
   cross-fade machinery. No scale — only rotation + opacity (no size change). */
@keyframes ai-icon-in {
  from { opacity: 0; transform: rotate(-40deg); }
  to   { opacity: 1; transform: rotate(0); }
}

/* Attachment thumb pop-in (used when a file is attached). No scale — a quiet
   rise + fade so the grid doesn't visibly resize. */
@keyframes ai-thumb-in {
  from { opacity: 0; transform: translateY(4px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* Spinner dots (busy state). */
@keyframes ai-dot {
  0%, 80%, 100% { opacity: 0.25; transform: scale(0.8); }
  40%           { opacity: 1; transform: scale(1); }
}

/* Modal entrance (settings + preview). No scale — a calm rise + fade. */
@keyframes ai-modal-in {
  from { opacity: 0; transform: translateY(8px); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes ai-overlay-in {
  from { opacity: 0; }
  to   { opacity: 1; }
}

/* Selected-row check mark fade (screen picker) — opacity only, no size change. */
@keyframes ai-check-in {
  from { opacity: 0; }
  to   { opacity: 1; }
}

/* ── Reduced motion — non-negotiable ──────────────────────────────────────────
   When the user prefers reduced motion, kill every animation in the AI
   surfaces and force end states. The JS helpers (ai-motion) honor the same
   preference independently. ──────────────────────────────────────────────── */

@media (prefers-reduced-motion: reduce) {
  .ai-root, .ai-root *, .ais-root, .ais-root * {
    animation-duration: 0.001ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.001ms !important;
    scroll-behavior: auto !important;
  }
}
")

(defn base-style-block
  "Render the shared base <style> (tokens + @font-face + keyframes + reduced
  motion). Call once near the top of each AI surface, before that surface's
  own CSS."
  []
  [:style {:dangerouslySetInnerHTML #js {:__html ai-base-css}}])