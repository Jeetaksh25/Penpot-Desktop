;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-bar
  "Feature 3 + 4 — the Ovion AI bar, rebuilt to match the Penpot-generated
  UI reference (UI_Reference/*.html) exactly.

  The reference draws a deliberate, authored visual world: white pill
  surfaces with a soft outer shadow and a coral inset ring, a coral send
  disc with a white arrow-up, grey #7d7d7d inactive icons at stroke-width
  2, and a mode pill that turns coral + swaps to a sparkles glyph when
  'Max' is active. The bar is calm — no orb, no sparkle beam, no glass —
  so the motion is calm too: a quiet rise on mount, an icon swap on mode
  toggle, a pill-radius grow when the prompt expands, and the signature
  coral-inset intensify on hover/press (GSAP + anime.js — NO size change).

  Layout (matches the reference's bottom-of-frame composition, kept clean):
    - A bottom-centered dock, sitting between the two sidebars with a
      little space from the exact bottom.
    - A single primary row: a cluster pill `[mode | paperclip | settings]`,
      the input pill `[prompt textarea | coral send]`, and a Screen pill
      on the right (the frame-preset selector, promoted out of the old
      secondary strip and given a custom themed picker popover).
  The reference-URL input was removed (URLs go straight in the prompt; the
  backend parses them out). The 'update only the selection' toggle and the
  Figma-#71 AI tools (rename / generate text) were relocated to the AI
  Settings modal so this bar stays uncluttered — see `ai_settings.cljs` and
  the shared `refs/ai-update-sel` ref.

  All colors/timing/type come from the reference-pinned tokens in
  `app.main.ui.workspace.ai-design` (Helvetica Now Display, #f28b82 coral,
  #7d7d7d grey). Styled via an injected <style> block (no scss pipeline
  dependency, so it compiles without the build-generated .css.json that
  `stl/css` needs)."
  (:require
   [cuerdas.core :as str]
   [app.common.types.design-spec :as cds]
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.data.workspace.ai-checklist :as aicl]
   [app.main.data.workspace.ai-text-ops :as atop]
   [app.main.data.workspace.design-gen :as dg]
   [app.main.data.workspace.prompt-library :as plib]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.ai-chat-history :as aich]
   [app.main.ui.workspace.ai-design :as ad]
   [app.main.ui.workspace.ai-image]              ; bare require — loads the :ai-image modal registration
   [app.main.ui.workspace.ai-settings]           ; bare require — loads the :ai-settings modal registration (opened from this bar + the titlebar gear)
   [app.main.ui.workspace.ai-motion :as aim]
   [app.main.ui.workspace.ai-stream :as ais]      ; P1.30 — streaming preview (stream-preview*)
   [app.main.ui.workspace.ai-branches :as aibr]   ; P2.08 — agent branch-tree viewer (branch-tree*)
   [app.util.code-gen :as cg]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; ── Injected CSS ─────────────────────────────────────────────────────────────
;;
;; Scoped under `.ai-root`. Built on the reference-pinned `ai-base-css`
;; tokens from `app.main.ui.workspace.ai-design` (coral #f28b82, grey
;; #7d7d7d, white surfaces, Helvetica Now Display). The look is the
;; reference's exact visual language, scaled from the 1920×1080 frame to
;; tasteful web-app sizes (circles 40px, icons 18–20px, text 15px) while
;; preserving the pill / coral-ring / inset-glow proportions.
;;
;; Hover/press box-shadow is OWNED by GSAP (ai-motion) — the coral inset
;; ring quietly intensifies and the small-control shadow deepens, with NO
;; transform/scale (the user explicitly banned size-change animations). CSS
;; keeps color/background only as the no-GSAP fallback, so the two never
;; fight over the same property on the same frame.

(def ^:private ai-css
  "
/* ── The dock — bottom-centered between the sidebars ─────────────────────── */
.ai-dock {
  position: absolute; left: 50%; transform: translateX(-50%);
  bottom: 28px; z-index: 60;
  width: min(720px, calc(100% - 48px));
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  font-family: var(--ai-font);
  animation: ai-rise var(--ai-dur-slow) var(--ai-ease-out) both;
  pointer-events: none;              /* let clicks pass through the gaps */
}
.ai-dock > * { pointer-events: auto; }

/* ── Primary row: cluster pill + input pill + screen pill ──────────────── */
.ai-primary { display: flex; align-items: center; gap: 20px; width: 100%;
  justify-content: center; }

/* ── Cluster pill (mode | paperclip | settings) ────────────────────────── */
.ai-cluster {
  display: inline-flex; align-items: center; gap: 12px; flex: none;
  background: var(--ai-white); border-radius: var(--ai-radius-pill);
  padding: 6px;
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral);
}
.ai-mode {
  display: inline-flex; align-items: center; gap: 7px; flex: none;
  height: 40px; padding: 0 16px; border: none; cursor: pointer;
  background: var(--ai-white); color: var(--ai-grey);
  border-radius: var(--ai-radius-pill);
  font-family: var(--ai-font); font-size: 14.5px; font-weight: 500;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out);
}
.ai-mode:hover { color: var(--ai-ink); }
.ai-mode:active { color: var(--ai-ink); }
.ai-mode:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.ai-mode.is-max { color: var(--ai-coral); }
.ai-mode .ai-i { width: 18px; height: 18px; }
.ai-mode .ai-i-anim { display: inline-flex; animation: ai-icon-in var(--ai-dur-base) var(--ai-ease-out) both; }

.ai-circle {
  width: 40px; height: 40px; flex: none; border: none; cursor: pointer;
  background: var(--ai-white); color: var(--ai-grey);
  border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out);
}
.ai-circle:hover { color: var(--ai-ink); }
.ai-circle:active { color: var(--ai-ink); }
.ai-circle:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.ai-circle .ai-i { width: 20px; height: 20px; }

/* ── Attachment thumbs (inside the cluster, after settings) ─────────────── */
.ai-thumbs { display: inline-flex; align-items: center; gap: 8px; padding: 0 4px 0 2px; }
.ai-thumb { position: relative; width: 34px; height: 34px; border-radius: 10px;
  animation: ai-thumb-in var(--ai-dur-base) var(--ai-ease-out) both; }
.ai-thumb img { width: 100%; height: 100%; object-fit: cover; border-radius: 10px;
  display: block; box-shadow: var(--ai-shadow-btn); }
.ai-thumb-x { position: absolute; top: -6px; right: -6px; width: 16px; height: 16px;
  background: var(--ai-coral); color: #fff; border-radius: 50%;
  display: flex; align-items: center; justify-content: center; cursor: pointer;
  border: 2px solid var(--ai-white); transition: background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-thumb-x .ai-i { width: 10px; height: 10px; }
.ai-thumb-x:hover { background: var(--ai-coral-press); }

/* ── Input pill (prompt + send) ─────────────────────────────────────────── */
.ai-input-pill {
  flex: 1 1 auto; min-width: 0; position: relative;
  display: flex; align-items: center; gap: 8px;
  background: var(--ai-white); border-radius: var(--ai-radius-pill);
  padding: 6px 6px 6px 20px;
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral);
  transition: border-radius var(--ai-dur-base) var(--ai-ease-out);
}
.ai-input-pill.is-expanded { border-radius: var(--ai-radius-expanded); align-items: flex-end; }
.ai-prompt {
  flex: 1 1 auto; min-width: 0; border: none; outline: none; background: transparent;
  font-family: var(--ai-font); font-size: 15px; font-weight: 400; line-height: 1.45;
  color: var(--ai-ink); resize: none; overflow-y: auto;
  min-height: 40px; max-height: 160px; padding: 10px 0;
  white-space: pre-wrap; word-break: break-word;
}
.ai-prompt::placeholder { color: var(--ai-grey); opacity: 1; }

.ai-send {
  flex: none; width: 40px; height: 40px; border: none; cursor: pointer;
  border-radius: 50%;
  background: var(--ai-coral); color: var(--ai-white);
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-white);
  transition: background var(--ai-dur-fast) var(--ai-ease-out);
}
.ai-send:hover { background: var(--ai-coral-press); }
.ai-send:active { background: var(--ai-coral-press); }
.ai-send:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-white), 0 0 0 3px var(--ai-coral-faint); }
.ai-send:disabled { background: #f3c4be; color: #fff; cursor: not-allowed; box-shadow: var(--ai-shadow-btn); }
.ai-send .ai-i { width: 20px; height: 20px; }

/* ── Screen selection pill (right of the input, mirrors the cluster) ───────
   The frame-preset selector promoted to the primary bar. Same construction
   as the mode pill: white surface + coral inset ring + small-control shadow,
   label in #7d7d7d Helvetica Now Display 500. A device glyph leads, a
   chevron trails and flips 180° when the picker is open. ──────────────── */
.ai-screen {
  display: inline-flex; align-items: center; gap: 7px; flex: none;
  height: 40px; padding: 0 14px 0 16px; border: none; cursor: pointer;
  background: var(--ai-white); color: var(--ai-grey);
  border-radius: var(--ai-radius-pill);
  font-family: var(--ai-font); font-size: 14.5px; font-weight: 500;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out);
}
.ai-screen:hover { color: var(--ai-ink); }
.ai-screen:active { color: var(--ai-ink); }
.ai-screen:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.ai-screen.is-open { color: var(--ai-ink); box-shadow: var(--ai-shadow-btn-hover), var(--ai-inset-coral-hover); }
.ai-screen .ai-i { width: 18px; height: 18px; }
.ai-screen .ai-i-chev { width: 16px; height: 16px; margin-left: 2px; color: var(--ai-grey);
  transition: transform var(--ai-dur-base) var(--ai-ease-out), color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-screen.is-open .ai-i-chev { transform: rotate(180deg); color: var(--ai-coral); }

/* ── Screen picker popover (custom, on-theme — NOT a native <select>) ──────
   Renders above the pill. Entrance is driven by anime.js (ai-motion/pop-in):
   a calm rise + fade, no scale. The element starts at opacity:0 in CSS so
   the first frame is invisible; pop-in tweens it in, and under reduced
   motion / missing anime it is forced to the visible end state. ───────── */
.ai-screen-wrap { position: relative; flex: none; }
.ai-screen-back { position: fixed; inset: 0; z-index: 70; background: transparent; }
.ai-screen-pop {
  position: absolute; bottom: calc(100% + 10px); right: 0; z-index: 71;
  min-width: 188px; background: var(--ai-white); border-radius: var(--ai-radius-md);
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral);
  padding: 6px; opacity: 0;
  display: flex; flex-direction: column; gap: 2px;
}
.ai-screen-opt {
  display: flex; align-items: center; gap: 9px; padding: 8px 10px; border: none; cursor: pointer;
  background: transparent; color: var(--ai-grey-2); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 13px; font-weight: 500; text-align: left;
  transition: background var(--ai-dur-fast) var(--ai-ease-out),
              color var(--ai-dur-fast) var(--ai-ease-out);
}
.ai-screen-opt:hover { background: var(--ai-coral-faint); color: var(--ai-ink); }
.ai-screen-opt.is-cur { color: var(--ai-coral); }
.ai-screen-opt .ai-i { width: 17px; height: 17px; flex: none; }
.ai-screen-opt .ai-i-check { width: 15px; height: 15px; flex: none; margin-left: auto; color: var(--ai-coral); }
.ai-screen-opt.is-cur .ai-i-check { animation: ai-check-in var(--ai-dur-base) var(--ai-ease-out) both; }

/* ── Stage / status / error ─────────────────────────────────────────────── */
.ai-stage { font-size: 12px; color: var(--ai-grey-2); min-height: 16px;
  display: flex; align-items: center; gap: 8px; font-family: var(--ai-font); }
.ai-dots { display: inline-flex; gap: 4px; }
.ai-dots span { width: 5px; height: 5px; border-radius: 50%; background: var(--ai-coral);
  animation: ai-dot 1.2s var(--ai-ease-in-out) infinite; }
.ai-dots span:nth-child(2) { animation-delay: 0.16s; }
.ai-dots span:nth-child(3) { animation-delay: 0.32s; }
.ai-err { font-size: 12px; color: #b3261e; background: #fbeceb;
  border: 1px solid #f3c4be; border-radius: var(--ai-radius-sm); padding: 7px 12px;
  font-family: var(--ai-font); max-width: 100%; }
.ai-spin { width: 16px; height: 16px; border: 2px solid rgba(255,255,255,0.45);
  border-top-color: #fff; border-radius: 50%; animation: ai-spin 0.7s linear infinite;
  display: inline-block; }
.ai-cancel { background: none; border: none; cursor: pointer; color: var(--ai-grey);
  font-family: var(--ai-font); font-size: 12.5px; font-weight: 500; text-decoration: underline;
  padding: 0; margin-left: 4px; transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-cancel:hover { color: var(--ai-ink); }

/* ── Preview modal ──────────────────────────────────────────────────────── */
.ai-overlay { position: fixed; inset: 0; background: var(--ai-overlay); z-index: 200;
  display: flex; align-items: center; justify-content: center; padding: 24px;
  animation: ai-overlay-in var(--ai-dur-base) var(--ai-ease-out) both; }
.ai-modal { background: var(--ai-white); border-radius: var(--ai-radius-md);
  max-width: 920px; width: 100%; max-height: 88vh; display: flex; flex-direction: column;
  overflow: hidden; box-shadow: 0 24px 70px rgba(0,0,0,0.4);
  animation: ai-modal-in var(--ai-dur-slow) var(--ai-ease-out) both; }
.ai-modal-head { padding: 16px 24px; border-bottom: 1px solid #ececec;
  display: flex; align-items: center; justify-content: space-between; }
.ai-modal-title { font-size: 15px; font-weight: 700; color: var(--ai-ink); font-family: var(--ai-font); }
.ai-modal-body { padding: 24px; overflow: auto; flex: 1; background: #fafafa; }
.ai-modal-foot { padding: 12px 24px; border-top: 1px solid #ececec;
  display: flex; gap: 8px; justify-content: flex-end; }
.ai-badge { font-size: 11px; font-weight: 600; color: var(--ai-coral);
  background: var(--ai-coral-faint); padding: 3px 9px; border-radius: var(--ai-radius-sm); }
.ai-btn { border: none; border-radius: var(--ai-radius-md); padding: 9px 16px; font-size: 13px;
  font-weight: 600; cursor: pointer; font-family: var(--ai-font);
  display: inline-flex; align-items: center; gap: 8px; white-space: nowrap;
  transition: background var(--ai-dur-fast) var(--ai-ease-out),
              color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-btn:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ai-btn-primary { background: var(--ai-coral); color: var(--ai-white);
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-white); }
.ai-btn-primary:hover { background: var(--ai-coral-press); }
.ai-btn-ghost { background: var(--ai-white); color: var(--ai-grey-2);
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral); }
.ai-btn-ghost:hover { color: var(--ai-ink); }
.ai-close { width: 34px; height: 34px; flex: none; border: none; cursor: pointer; background: var(--ai-white);
  color: var(--ai-grey); border-radius: 50%; display: inline-flex; align-items: center; justify-content: center;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-close:hover { color: var(--ai-ink); }
.ai-close:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.ai-close .ai-i { width: 18px; height: 18px; }

/* ── Multi-variant carousel (Phase 2) ──────────────────────────────────────
   When the preview carries :specs (vector >1), variants render side-by-side
   with prev/next arrows and a dot indicator. Arrows reuse the .ai-circle
   surface; the dot row sits under the body. No scale motion — calm only. */
.ai-var-wrap { display: flex; flex-direction: column; gap: 10px; }
.ai-var-row { display: flex; align-items: stretch; gap: 10px; }
.ai-var-col { flex: 1 1 0; min-width: 0; }
.ai-var-arrow { flex: none; align-self: center; }
.ai-dots-ind { display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
.ai-dot { width: 7px; height: 7px; border-radius: 50%; background: #e2e2e2;
  border: none; padding: 0; cursor: pointer;
  transition: background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-dot.is-cur { background: var(--ai-coral); }
.ai-dot:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--ai-coral-faint); }

/* ── Review / Spec-doc result modal (read-only text) ───────────────────────
   Reuses .ai-modal / .ai-overlay. The body renders a scrollable text column
   with a coral score badge for reviews and a monospace-ish pre for spec-doc. */
.ai-result-body { white-space: pre-wrap; word-break: break-word; font-family: var(--ai-font);
  font-size: 13px; line-height: 1.55; color: var(--ai-ink); }
.ai-result-sec { font-size: 12px; font-weight: 700; color: var(--ai-grey);
  margin-top: 12px; margin-bottom: 4px; font-family: var(--ai-font); }
.ai-result-list { margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.5;
  color: var(--ai-ink); font-family: var(--ai-font); }
.ai-result-score { font-size: 13px; font-weight: 700; color: var(--ai-coral);
  background: var(--ai-coral-faint); padding: 4px 10px; border-radius: var(--ai-radius-sm); }

/* ── P1.13 / P2.28 — segmented control + mini popover ──────────────────────────
   Reused by the image-mode picker (P1.13) and any small single-choice menu.
   The segmented control mirrors the variant-count pill: white surface, coral
   inset ring. The mini popover mirrors the screen popover (.ai-screen-pop). */
.ai-seg { display: inline-flex; align-items: center; gap: 2px; flex: none;
  padding: 4px; background: var(--ai-white); border-radius: var(--ai-radius-pill);
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral); }
.ai-seg-opt { display: inline-flex; align-items: center; justify-content: center;
  height: 30px; padding: 0 11px; border: none; cursor: pointer;
  background: transparent; color: var(--ai-grey-2); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 12.5px; font-weight: 500;
  transition: background var(--ai-dur-fast) var(--ai-ease-out),
              color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-seg-opt:hover { color: var(--ai-ink); }
.ai-seg-opt.is-cur { color: var(--ai-coral); background: var(--ai-coral-faint); }
.ai-seg-opt:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--ai-coral-faint); }

.ai-mini-wrap { position: relative; flex: none; }
.ai-mini-back { position: fixed; inset: 0; z-index: 70; background: transparent; }
.ai-mini-pop { position: absolute; bottom: calc(100% + 10px); left: 0; z-index: 71;
  min-width: 188px; background: var(--ai-white); border-radius: var(--ai-radius-md);
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral);
  padding: 6px; opacity: 0;
  display: flex; flex-direction: column; gap: 2px; }

/* ── P2.04 — text ops popover ─────────────────────────────────────────────────── */
.ai-textop-row { display: flex; align-items: center; gap: 8px; padding: 4px 6px; }
.ai-textop-input { flex: 1 1 auto; border: 1px solid #ececec; border-radius: 8px;
  padding: 6px 8px; font-size: 12px; font-family: var(--ai-font); color: var(--ai-ink);
  background: var(--ai-white); outline: none; min-width: 90px; }
.ai-textop-input:focus { border-color: var(--ai-coral);
  box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ai-textop-sep { height: 1px; background: #ececec; margin: 3px 4px; }

/* ── P2.34 — Show Code reveal in the preview modal ──────────────────────────────
   A toggle in the preview header flips the body between the visual preview
   and a two-section code view (markup + style), each with a copy button. */
.ai-code-panel { display: flex; flex-direction: column; gap: 14px; }
.ai-code-sec { display: flex; flex-direction: column; gap: 6px; }
.ai-code-head { display: flex; align-items: center; justify-content: space-between; }
.ai-code-label { font-size: 11px; font-weight: 700; color: var(--ai-grey);
  text-transform: uppercase; letter-spacing: 0.04em; font-family: var(--ai-font); }
.ai-code-copy { display: inline-flex; align-items: center; gap: 6px; height: 28px;
  padding: 0 10px; border: none; cursor: pointer; background: var(--ai-white);
  color: var(--ai-grey-2); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 11.5px; font-weight: 600;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-code-copy:hover { color: var(--ai-ink); }
.ai-code-copy:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.ai-code-copy .ai-i { width: 13px; height: 13px; }
.ai-code-block { margin: 0; padding: 12px 14px; background: #fafafa;
  border: 1px solid #ececec; border-radius: var(--ai-radius-sm);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11.5px;
  line-height: 1.5; color: var(--ai-ink); white-space: pre; overflow: auto;
  max-height: 240px; }
.ai-code-empty { font-size: 12px; color: var(--ai-grey-2); font-family: var(--ai-font);
  padding: 18px; text-align: center; }
.ai-code-toggle { display: inline-flex; align-items: center; gap: 7px; height: 34px;
  padding: 0 14px; border: none; cursor: pointer; background: var(--ai-white);
  color: var(--ai-grey-2); border-radius: var(--ai-radius-pill);
  font-family: var(--ai-font); font-size: 12.5px; font-weight: 600;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-code-toggle:hover { color: var(--ai-ink); }
.ai-code-toggle.is-on { color: var(--ai-coral); }
.ai-code-toggle:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.ai-code-toggle .ai-i { width: 15px; height: 15px; }

/* ── P2.03 — Prompt library popover ──────────────────────────────────────
   Mirrors .ai-mini-pop but wider: a search input, grouped preset rows,
   and a "save current as preset" inline form at the bottom. Reduced-motion
   forces opacity:1 (the pop-in anime handles the calm entrance otherwise). */
.ai-lib-pop { position: absolute; bottom: calc(100% + 10px); left: 0; z-index: 71;
  min-width: 320px; max-width: 380px; max-height: 360px; display: flex; flex-direction: column;
  background: var(--ai-white); border-radius: var(--ai-radius-md);
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral);
  padding: 6px; opacity: 0; }
.ai-lib-search-row { display: flex; align-items: center; gap: 8px; padding: 6px 8px 8px; }
.ai-lib-search { flex: 1 1 auto; border: 1px solid #ececec; border-radius: var(--ai-radius-sm);
  padding: 6px 8px; font-size: 12.5px; font-family: var(--ai-font); color: var(--ai-ink);
  background: var(--ai-white); outline: none; min-width: 0; }
.ai-lib-search:focus { border-color: var(--ai-coral); box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ai-lib-search .ai-i { width: 15px; height: 15px; color: var(--ai-grey); flex: none; }
.ai-lib-list { overflow-y: auto; flex: 1 1 auto; display: flex; flex-direction: column; gap: 2px; }
.ai-lib-group { font-size: 11px; font-weight: 700; color: var(--ai-grey);
  text-transform: uppercase; letter-spacing: 0.04em; font-family: var(--ai-font);
  padding: 8px 10px 3px; }
.ai-lib-item { display: flex; align-items: flex-start; gap: 9px; padding: 7px 10px; border: none;
  cursor: pointer; background: transparent; color: var(--ai-grey-2); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 12.5px; font-weight: 500; text-align: left;
  transition: background var(--ai-dur-fast) var(--ai-ease-out), color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-lib-item:hover { background: var(--ai-coral-faint); color: var(--ai-ink); }
.ai-lib-item .ai-lib-label { font-weight: 600; flex: 1 1 auto; min-width: 0; }
.ai-lib-item .ai-lib-prompt { font-size: 11px; font-weight: 400; color: var(--ai-grey-2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1 1 auto; min-width: 0; }
.ai-lib-del { flex: none; width: 22px; height: 22px; border: none; cursor: pointer;
  background: transparent; color: var(--ai-grey); border-radius: 50%;
  display: none; align-items: center; justify-content: center; transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-lib-item:hover .ai-lib-del { display: inline-flex; }
.ai-lib-del:hover { color: #b3261e; }
.ai-lib-del .ai-i { width: 13px; height: 13px; }
.ai-lib-sep { height: 1px; background: #ececec; margin: 4px 4px; }
.ai-lib-save-row { display: flex; align-items: center; gap: 6px; padding: 6px 8px; }
.ai-lib-save-input { flex: 1 1 auto; border: 1px solid #ececec; border-radius: var(--ai-radius-sm);
  padding: 6px 8px; font-size: 12px; font-family: var(--ai-font); color: var(--ai-ink);
  background: var(--ai-white); outline: none; min-width: 0; }
.ai-lib-save-input:focus { border-color: var(--ai-coral); box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ai-lib-save-btn { flex: none; height: 30px; padding: 0 10px; border: none; cursor: pointer;
  background: var(--ai-coral); color: var(--ai-white); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 12px; font-weight: 600;
  display: inline-flex; align-items: center; gap: 5px;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-white);
  transition: background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-lib-save-btn:hover { background: var(--ai-coral-press); }
.ai-lib-save-btn:disabled { background: #f3c4be; cursor: not-allowed; }
.ai-lib-save-btn .ai-i { width: 13px; height: 13px; }
.ai-lib-empty { font-size: 12px; color: var(--ai-grey-2); font-family: var(--ai-font);
  padding: 14px 10px; text-align: center; }

/* ── P2.41 — Voice input (Web Speech API) ────────────────────────────────
   The mic + library buttons sit inside the input pill as compact ghost
   circles (.ai-inpill): grey at rest, coral on active. The mic adds a
   coral + pulse state while listening. The pulse is a calm opacity
   breathe (NO size change), matching the bar's calm-motion language.
   Under reduced motion the mic is solid coral with no pulse. */
.ai-inpill { width: 32px; height: 32px; flex: none; border: none; cursor: pointer;
  background: transparent; color: var(--ai-grey); border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  transition: color var(--ai-dur-fast) var(--ai-ease-out), background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-inpill:hover { color: var(--ai-ink); background: var(--ai-coral-faint); }
.ai-inpill:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ai-inpill .ai-i { width: 18px; height: 18px; }
.ai-inpill.is-active { color: var(--ai-coral); }
.ai-mic.is-listening { color: var(--ai-coral); box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 4px var(--ai-coral-faint); }
.ai-mic.is-listening .ai-i { animation: ai-mic-pulse 1.6s var(--ai-ease-in-out) infinite; }
@keyframes ai-mic-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

/* ── P2.01 — AI design checklist popover ─────────────────────────────────
   Mirrors .ai-lib-pop but tailored to a tickable checklist: a header
   row (title + generate/clear), a scrollable item list with coral
   checkboxes, and a "Generate checklist" call-to-action when empty.
   Reduced-motion forces opacity:1 (the pop-in anime handles the calm
   entrance otherwise). */
.ai-check-pop { position: absolute; bottom: calc(100% + 10px); left: 0; z-index: 71;
  min-width: 300px; max-width: 360px; max-height: 380px; display: flex; flex-direction: column;
  background: var(--ai-white); border-radius: var(--ai-radius-md);
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral);
  padding: 6px; opacity: 0; }
.ai-check-head { display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px 6px; }
.ai-check-title { font-size: 12px; font-weight: 700; color: var(--ai-grey);
  text-transform: uppercase; letter-spacing: 0.04em; font-family: var(--ai-font); }
.ai-check-actions { display: inline-flex; align-items: center; gap: 4px; }
.ai-check-actbtn { height: 26px; padding: 0 9px; border: none; cursor: pointer;
  background: transparent; color: var(--ai-grey); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 11.5px; font-weight: 600;
  display: inline-flex; align-items: center; gap: 5px;
  transition: color var(--ai-dur-fast) var(--ai-ease-out),
              background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-check-actbtn:hover { color: var(--ai-ink); background: var(--ai-coral-faint); }
.ai-check-actbtn:disabled { color: var(--ai-grey-2); cursor: not-allowed; background: transparent; }
.ai-check-actbtn .ai-i { width: 14px; height: 14px; }
.ai-check-list { overflow-y: auto; flex: 1 1 auto; display: flex; flex-direction: column;
  gap: 1px; padding: 0 2px; }
.ai-check-item { display: flex; align-items: flex-start; gap: 9px; padding: 7px 8px; border: none;
  cursor: pointer; background: transparent; border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 12.5px; font-weight: 500; text-align: left;
  color: var(--ai-ink);
  transition: background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-check-item:hover { background: var(--ai-coral-faint); }
.ai-check-item.is-done { color: var(--ai-grey-2); }
.ai-check-item.is-done .ai-check-label { text-decoration: line-through; }
.ai-check-box { flex: none; width: 18px; height: 18px; border: 2px solid var(--ai-grey);
  border-radius: 5px; display: inline-flex; align-items: center; justify-content: center;
  margin-top: 1px; transition: border-color var(--ai-dur-fast) var(--ai-ease-out),
                               background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-check-item:hover .ai-check-box { border-color: var(--ai-coral); }
.ai-check-item.is-done .ai-check-box { border-color: var(--ai-coral);
  background: var(--ai-coral); }
.ai-check-box .ai-i { width: 13px; height: 13px; color: var(--ai-white);
  animation: ai-check-in var(--ai-dur-base) var(--ai-ease-out) both; }
.ai-check-label { flex: 1 1 auto; min-width: 0; line-height: 1.4; }
.ai-check-empty { font-size: 12px; color: var(--ai-grey-2); font-family: var(--ai-font);
  padding: 18px 14px; text-align: center; display: flex; flex-direction: column; gap: 10px;
  align-items: center; }
.ai-check-genbtn { height: 30px; padding: 0 12px; border: none; cursor: pointer;
  background: var(--ai-coral); color: var(--ai-white); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 12px; font-weight: 600;
  display: inline-flex; align-items: center; gap: 6px;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-white);
  transition: background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-check-genbtn:hover { background: var(--ai-coral-press); }
.ai-check-genbtn:disabled { background: #f3c4be; cursor: not-allowed; }
.ai-check-genbtn .ai-i { width: 14px; height: 14px; }

/* ── P2.19 — focus-area predictor popover ────────────────────────────────
   A compact popover: a one-line rationale + a coral "Go to" action and
   a dismiss link. Reuses .ai-check-pop surface tokens. */
.ai-focus-pop { position: absolute; bottom: calc(100% + 10px); left: 0; z-index: 71;
  min-width: 280px; max-width: 360px; background: var(--ai-white);
  border-radius: var(--ai-radius-md);
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral);
  padding: 10px 12px; opacity: 0; display: flex; flex-direction: column; gap: 8px; }
.ai-focus-head { display: flex; align-items: center; gap: 7px; }
.ai-focus-head .ai-i { width: 16px; height: 16px; color: var(--ai-coral); }
.ai-focus-title { font-size: 12px; font-weight: 700; color: var(--ai-grey);
  text-transform: uppercase; letter-spacing: 0.04em; font-family: var(--ai-font); }
.ai-focus-rationale { font-size: 12.5px; font-weight: 500; color: var(--ai-ink);
  font-family: var(--ai-font); line-height: 1.45; }
.ai-focus-actions { display: flex; align-items: center; gap: 10px; }
.ai-focus-go { height: 30px; padding: 0 12px; border: none; cursor: pointer;
  background: var(--ai-coral); color: var(--ai-white); border-radius: var(--ai-radius-sm);
  font-family: var(--ai-font); font-size: 12px; font-weight: 600;
  display: inline-flex; align-items: center; gap: 6px;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-white);
  transition: background var(--ai-dur-fast) var(--ai-ease-out); }
.ai-focus-go:hover { background: var(--ai-coral-press); }
.ai-focus-go:disabled { background: #f3c4be; cursor: not-allowed; }
.ai-focus-go .ai-i { width: 14px; height: 14px; }
.ai-focus-dismiss { background: none; border: none; cursor: pointer; color: var(--ai-grey);
  font-family: var(--ai-font); font-size: 12px; font-weight: 500; text-decoration: underline;
  padding: 0; transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.ai-focus-dismiss:hover { color: var(--ai-ink); }
.ai-focus-empty { font-size: 12px; color: var(--ai-grey-2); font-family: var(--ai-font);
  padding: 8px 4px 4px; display: flex; flex-direction: column; gap: 10px; align-items: flex-start; }

@media (prefers-reduced-motion: reduce) {
  .ai-mini-pop, .aich-pop, .ai-lib-pop, .ai-check-pop, .ai-focus-pop { opacity: 1 !important; }
  .ai-mic.is-listening .ai-i { animation: none; }
}
")

(defn- style-block
  "Render the shared base + AI bar <style> once. Wrapped in a
  display:contents div so it adds no layout (some rumext versions don't
  parse the `:<>` fragment keyword reliably)."
  []
  [:div {:style #js {"display" "contents"}}
   (ad/base-style-block)
   [:style {:dangerouslySetInnerHTML #js {:__html ai-css}}]])

;; ── Lucide icons (one family, stroke-width 2, currentColor) ──────────────────
;;
;; The reference uses Lucide glyphs drawn as inline SVG. `currentColor`
;; lets each context tint them via CSS (grey at rest, coral when active,
;; white on the coral send disc).

(defn- li
  "Wrap a seq of SVG children in a Lucide 24×24 icon frame."
  [body]
  [:svg.ai-i {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
              :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
              :aria-hidden "true"} body])

(def ^:private lucide-arrow-up
  (li [[:path {:d "M12 19V5"}]
       [:path {:d "m5 12 7-7 7 7"}]]))

(def ^:private lucide-paperclip
  (li [[:path {:d "m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 17.93 8.83l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"}]]))

(def ^:private lucide-settings
  (li [[:path {:d "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"}]
       [:circle {:cx 12 :cy 12 :r 3}]]))

(def ^:private lucide-refresh-cw
  (li [[:path {:d "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"}]
       [:path {:d "M21 3v5h-5"}]
       [:path {:d "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"}]
       [:path {:d "M3 21v-5h5"}]]))

(def ^:private lucide-sparkles
  (li [[:path {:d "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"}]
       [:path {:d "M20 3v4"}]
       [:path {:d "M22 5h-4"}]
       [:path {:d "M4 17v2"}]
       [:path {:d "M5 18H3"}]]))

(def ^:private lucide-x
  (li [[:path {:d "M18 6 6 18"}]
       [:path {:d "m6 6 12 12"}]]))

;; Screen-selection glyphs (one per frame preset). All Lucide, stroke-width 2.
(def ^:private lucide-layout-grid
  (li [[:rect {:x 3 :y 3 :width 7 :height 7 :rx 1}]
       [:rect {:x 14 :y 3 :width 7 :height 7 :rx 1}]
       [:rect {:x 14 :y 14 :width 7 :height 7 :rx 1}]
       [:rect {:x 3 :y 14 :width 7 :height 7 :rx 1}]]))

(def ^:private lucide-smartphone
  (li [[:rect {:x 5 :y 2 :width 14 :height 20 :rx 2}]
       [:path {:d "M12 18h.01"}]]))

(def ^:private lucide-tablet
  (li [[:rect {:x 4 :y 2 :width 16 :height 20 :rx 2}]
       [:path {:d "M12 18h.01"}]]))

(def ^:private lucide-monitor
  (li [[:rect {:x 2 :y 3 :width 20 :height 14 :rx 2}]
       [:path {:d "M8 21h8"}]
       [:path {:d "M12 17v4"}]]))

(def ^:private lucide-watch
  (li [[:circle {:cx 12 :cy 12 :r 6}]
       [:path {:d "M9 6V3h6v3"}]
       [:path {:d "M9 18v3h6v-3"}]]))

(def ^:private lucide-chevron-down
  (li [[:path {:d "m6 9 6 6 6-6"}]]))

(def ^:private lucide-check
  (li [[:path {:d "M20 6 9 17l-5-5"}]]))

;; Phase 2 — carousel + cluster action glyphs (Lucide, stroke-width 2).
(def ^:private lucide-chevron-left
  (li [[:path {:d "m15 18-6-6 6-6"}]]))

(def ^:private lucide-chevron-right
  (li [[:path {:d "m9 18 6-6-6-6"}]]))

(def ^:private lucide-image
  (li [[:rect {:x 3 :y 3 :width 18 :height 18 :rx 2 :ry 2}]
       [:circle {:cx 9 :cy 9 :r 2}]
       [:path {:d "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"}]]))

;; "scan-eye" — corner scan brackets + an eye in the middle.
(def ^:private lucide-scan-eye
  (li [[:path {:d "M3 7V5a2 2 0 0 1 2-2h2"}]
       [:path {:d "M17 3h2a2 2 0 0 1 2 2v2"}]
       [:path {:d "M21 17v2a2 2 0 0 1-2 2h-2"}]
       [:path {:d "M7 21H5a2 2 0 0 1-2-2v-2"}]
       [:path {:d "M3 12a9 9 0 0 1 18 0"}]
       [:path {:d "M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6"}]]))

(def ^:private lucide-file-text
  (li [[:path {:d "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"}]
       [:path {:d "M14 2v4a2 2 0 0 0 2 2h4"}]
       [:path {:d "M16 13H8"}]
       [:path {:d "M16 17H8"}]
       [:path {:d "M10 9H8"}]]))

;; P2.04 — AI text operations (Translate / Continue / Polish / Summarize).
(def ^:private lucide-languages
  (li [[:path {:d "m5 8 6 6"}]
       [:path {:d "m4 14 6-6 2-3"}]
       [:path {:d "m2 5 12 12"}]
       [:path {:d "m9 4 1.5 3"}]
       [:path {:d "M14 14h6"}]
       [:path {:d "M14 17h6"}]
       [:path {:d "M17 14v6"}]
       [:path {:d "M21 14v5a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-5"}]]))

(def ^:private lucide-type
  (li [[:path {:d "M4 7V4h16v3"}]
       [:path {:d "M9 20h6"}]
       [:path {:d "M12 4v16"}]]))

;; P2.28 — multi-screen adaptation.
(def ^:private lucide-monitor-smartphone
  (li [[:path {:d "M2 13a2 2 0 0 0 2 2h6"}]
       [:path {:d "M2 13V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v6"}]
       [:path {:d "M2 13h8"}]
       [:path {:d "M14 14h8a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-2a2 2 0 0 1 2-2Z"}]
       [:path {:d "M18 18h.01"}]]))

;; P2.30 — chat history (clock glyph).
(def ^:private lucide-history
  (li [[:path {:d "M3 12a9 9 0 1 0 3-6.7L3 8"}]
       [:path {:d "M3 3v5h5"}]
       [:path {:d "M12 7v5l3 3"}]]))

;; P2.34 — show code reveal + copy.
(def ^:private lucide-code
  (li [[:path {:d "m16 18 6-6-6-6"}]
       [:path {:d "m8 6-6 6 6 6"}]]))

(def ^:private lucide-copy
  (li [[:rect {:x 9 :y 9 :width 13 :height 13 :rx 2 :ry 2}]
       [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]]))

;; P2.03 — prompt library + save-preset glyphs.
(def ^:private lucide-book-marked
  (li [[:path {:d "M19 21V5a2 2 0 0 0-2-2H4"}]
       [:path {:d "M5 3a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14"}]
       [:path {:d "M12 3v18"}]
       [:path {:d "M12 8h4"}]
       [:path {:d "M12 12h4"}]]))

(def ^:private lucide-bookmark-plus
  (li [[:path {:d "M19 21V5a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v16l7-3 7 3z"}]
       [:path {:d "M12 7v6"}]
       [:path {:d "M9 10h6"}]]))

(def ^:private lucide-search
  (li [[:circle {:cx 11 :cy 11 :r 8}]
       [:path {:d "m21 21-4.3-4.3"}]]))

(def ^:private lucide-trash-2
  (li [[:path {:d "M3 6h18"}]
       [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
       [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
       [:path {:d "M10 11v6"}]
       [:path {:d "M14 11v6"}]]))

;; P2.41 — voice input (microphone).
(def ^:private lucide-mic
  (li [[:path {:d "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z"}]
       [:path {:d "M19 10v2a7 7 0 0 1-14 0v-2"}]
       [:path {:d "M12 19v3"}]]))

;; P2.01 — AI design checklist (list-checks glyph).
(def ^:private lucide-list-checks
  (li [[:path {:d "M3 17l2 2 4-4"}]
       [:path {:d "M3 7l2 2 4-4"}]
       [:path {:d "M13 6h8"}]
       [:path {:d "M13 11h8"}]
       [:path {:d "M13 16h8"}]]))

;; P2.19 — focus-area predictor (target glyph).
(def ^:private lucide-crosshair
  (li [[:circle {:cx 12 :cy 12 :r 10}]
       [:circle {:cx 12 :cy 12 :r 6}]
       [:circle {:cx 12 :cy 12 :r 2}]]))

;; P1.05 — next-screens generation (branch glyph).
(def ^:private lucide-git-branch
  (li [[:line {:x1 6 :y1 3 :x2 6 :y2 15}]
       [:circle {:cx 18 :cy 6 :r 3}]
       [:circle {:cx 6 :cy 18 :r 3}]
       [:path {:d "M18 9a9 9 0 0 1-9 9"}]]))

;; P1.30 — streaming generation toggle (zap glyph).
(def ^:private lucide-zap
  (li [[:path {:d "M4 14a1 1 0 0 1-.78-1.63l9-9a1 1 0 0 1 1.56 1.36L7.3 9H20a1 1 0 0 1 .9 1.45l-4 8a1 1 0 0 1-.9.55H4"}]]))

;; ── Small presentational bits ───────────────────────────────────────────────

;; Frame presets — the Screen selection pill. Each pairs a backend value
;; (`:frame-preset`) with a label + a Lucide device glyph shown in the pill
;; and the picker. "auto" lets the backend/model pick the canvas size.
(def ^:private presets
  [{:v "auto"      :label "Auto"         :icon lucide-layout-grid}
   {:v "mobile"    :label "Mobile"       :icon lucide-smartphone}
   {:v "mobile-sm" :label "Mobile small" :icon lucide-smartphone}
   {:v "tablet"    :label "Tablet"       :icon lucide-tablet}
   {:v "web"       :label "Web"          :icon lucide-monitor}
   {:v "web-wide"  :label "Web wide"     :icon lucide-monitor}
   {:v "desktop"   :label "Desktop"      :icon lucide-monitor}
   {:v "watch"     :label "Watch"        :icon lucide-watch}])

(defn- preset-by-v [v] (some #(when (= (:v %) v) %) presets))
(defn- preset-label [v] (or (:label (preset-by-v v)) "Auto"))
(defn- preset-icon  [v] (or (:icon  (preset-by-v v)) lucide-layout-grid))

(defn- spinner [] [:span.ai-spin])

(defn- stage-dots [] [:span.ai-dots [:span] [:span] [:span]])

;; ── The bar ──────────────────────────────────────────────────────────────────

(mf/defc ai-bar*
  []
  (let [prompt*        (mf/use-state "")
        quality*       (mf/use-state "auto")        ; "max" | "auto"
        preset*        (mf/use-state "auto")
        screen-open?*  (mf/use-state false)         ; Screen picker popover
        attachments*   (mf/use-state [])            ; [{:file :name :preview}]
        stage*         (mf/use-state nil)
        file-input*    (mf/use-ref nil)
        prompt-ref     (mf/use-ref nil)
        pop-ref        (mf/use-ref nil)             ; Screen popover element (for anime entrance)
        variant-idx*   (mf/use-state 0)             ; Phase 2 carousel current variant
        variants*      (mf/use-state 1)             ; multi-variant count (Auto only; 1 = off)

        ;; P1.13 — image-mode (none / screenshot / sketch) for screenshot/sketch-to-UI.
        image-mode*    (mf/use-state "none")
        ;; P2.04 — text-ops popover.
        text-ops-open?* (mf/use-state false)
        translate-lang* (mf/use-state "English")
        text-ops-ref   (mf/use-ref nil)
        ;; P2.28 — adapt-screen popover.
        adapt-open?*   (mf/use-state false)
        adapt-ref      (mf/use-ref nil)
        ;; P2.30 — chat history popover + persistent sessions.
        chat-open?*    (mf/use-state false)
        chat-ref       (mf/use-ref nil)
        sessions*      (mf/use-state [])
        active-session-id* (mf/use-state nil)
        ;; P1.30 — streaming generation toggle (auto mode only). When true,
        ;; on-generate fires generate-design-stream so progress streams live.
        stream?*       (mf/use-state false)
        ;; P2.08 — agent branch-tree panel toggle.
        branches-open?* (mf/use-state false)
        ;; P2.34 — Show Code toggle in the preview modal.
        show-code?*    (mf/use-state false)
        ;; P2.03 — prompt library popover + save-preset inline form.
        lib-open?*     (mf/use-state false)
        lib-search*    (mf/use-state "")
        lib-ref        (mf/use-ref nil)
        save-group*    (mf/use-state "")
        save-label*    (mf/use-state "")
        ;; P2.01 — AI design checklist popover.
        check-open?*   (mf/use-state false)
        check-ref      (mf/use-ref nil)
        ;; P2.19 — focus-area predictor popover.
        focus-open?*   (mf/use-state false)
        focus-ref      (mf/use-ref nil)
        ;; P2.41 — voice input (Web Speech API). listening?* tracks the
        ;; active state; speech-rec* holds the live SpeechRecognition
        ;; instance; interim* buffers the interim transcript so it can be
        ;; appended to the prompt without clobbering the user's typed text.
        listening?*    (mf/use-state false)
        speech-rec*    (mf/use-ref nil)
        interim*       (mf/use-ref nil)

        busy          (mf/deref refs/ai-busy)
        preview       (mf/deref refs/ai-preview)
        error*        (mf/deref refs/ai-error)
        review*       (mf/deref refs/ai-review)      ; Phase 2 review result slot
        spec-doc*     (mf/deref refs/ai-spec-doc)    ; Phase 2 spec-doc result slot
        focus*        (mf/deref refs/ai-focus)        ; P2.19 focus-prediction result slot
        selected      (mf/deref refs/selected-shapes)
        has-sel?      (boolean (seq selected))
        file-ref      (mf/deref refs/file)
        file-id       (some-> file-ref :id str)

        ;; P2.03 — file-level plugin-data for user prompt presets.
        ;; P2.01 / P2.19 — checklist + last focus also live on file-level
        ;; plugin-data (:ovion namespace) so they survive save/reload.
        file-data     (mf/deref refs/workspace-data)
        user-presets  (plib/read-user-presets file-data)
        checklist     (aicl/read-checklist file-data)

        ;; P2.41 — Web Speech API support. Nil-safe: when neither
        ;; SpeechRecognition nor webkitSpeechRecognition is defined the mic
        ;; button is hidden entirely (see the render guard below).
        speech-supported?
        (mf/use-memo
         (mf/deps)
         (fn []
           (or (some? (.-SpeechRecognition js/window))
               (some? (.-webkitSpeechRecognition js/window)))))

        ;; "Update only the selection" now lives in the AI Settings modal and
        ;; is shared via refs/ai-update-sel. nil => default true (update the
        ;; selection when one exists), matching the bar's previous local default.
        update-sel-raw (mf/deref refs/ai-update-sel)
        update-sel?    (if (nil? update-sel-raw) true update-sel-raw)

        prompt        (deref prompt*)
        quality       (deref quality*)
        preset        (deref preset*)
        screen-open?  (deref screen-open?*)
        attachments   (deref attachments*)
        stage         (deref stage*)
        variant-idx   (deref variant-idx*)
        variants      (deref variants*)
        image-mode    (deref image-mode*)
        text-ops-open? (deref text-ops-open?*)
        translate-lang (deref translate-lang*)
        adapt-open?   (deref adapt-open?*)
        chat-open?    (deref chat-open?*)
        sessions      (deref sessions*)
        active-session-id (deref active-session-id*)
        show-code?    (deref show-code?*)
        lib-open?     (deref lib-open?*)
        lib-search    (deref lib-search*)
        save-group    (deref save-group*)
        save-label    (deref save-label*)
        listening?    (deref listening?*)
        check-open?   (deref check-open?*)
        focus-open?   (deref focus-open?*)

        ;; The input pill drops from a full pill (999px) to a rounded
        ;; rectangle (~22px) when the prompt grows past one line, mirroring
        ;; the reference's 169px -> 48px radius shift.
        expanded?     (or (str/includes? prompt "\n") (> (count prompt) 48))

        ;; P2.03 — merged + filtered + grouped prompt library for the picker.
        ;; Defaults are always present; user presets are tagged with their
        ;; index in the user vector so delete-preset targets the right row.
        lib-defaults   (plib/default-presets)
        lib-user       user-presets
        lib-merged     (into (vec lib-defaults)
                             (map-indexed (fn [i p]
                                            (assoc p :user-idx i :user? true))
                                          lib-user))
        lib-q          (.. (str lib-search) toLowerCase)
        lib-filtered   (if (str/empty? lib-search)
                         lib-merged
                         (filterv
                          (fn [p]
                            (let [lab (.. (str (:label p "")) toLowerCase)
                                  pr  (.. (str (:prompt p "")) toLowerCase)
                                  gr  (.. (str (:group p "")) toLowerCase)]
                              (or (str/includes? lab lib-q)
                                  (str/includes? pr lib-q)
                                  (str/includes? gr lib-q))))
                          lib-merged))
        lib-groups     (let [seen (volatile! #{})
                             acc  (volatile! [])]
                         (doseq [p lib-filtered
                                 :let [g (:group p)]
                                 :when (not (contains? @seen g))]
                           (vswap! seen conj g)
                           (vswap! acc conj g))
                         @acc)

        ;; Effective target: region update only when something is selected
        ;; AND the user hasn't disabled it (in AI Settings). Otherwise
        ;; "new-board" — the backend's documented placement value.
        target (if (and has-sel? update-sel?) "update-selection" "new-board")

        on-prompt  (mf/use-fn (fn [e] (reset! prompt* (.. e -target -value))))
        on-quality (mf/use-fn (mf/deps quality*) (fn [q] (reset! quality* q)))
        ;; The mode pill toggles Auto <-> Max (the two GLM/Kimi
        ;; orchestration modes; the models are never named in the UI).
        on-toggle-quality
        (mf/use-fn (mf/deps quality*)
          (fn [] (reset! quality* (if (= quality "max") "auto" "max"))))

        on-toggle-screen (mf/use-fn (fn [] (swap! screen-open?* not)))
        on-close-screen  (mf/use-fn (fn [] (reset! screen-open?* false)))
        on-pick-screen   (mf/use-fn
                          (fn [v] (reset! preset* v) (reset! screen-open?* false)))

        on-pick-files
        (mf/use-fn
         (fn [e]
           (let [files (array-seq (.. e -target -files))]
             (when (seq files)
               (let [new (mapv (fn [f]
                                 {:file f
                                  :name (.-name f)
                                  :preview (js/URL.createObjectURL f)})
                               files)]
                 (reset! attachments* (into (deref attachments*) new)))
               ;; reset so picking the same file again re-fires change
               (set! (.. e -target -value) "")))))

        open-picker
        (mf/use-fn
         (mf/deps file-input*)
         (fn []
           (some-> (mf/ref-val file-input*) dom/click)))

        open-settings
        (mf/use-fn
         (fn []
           (st/emit! (modal/show {:type :ai-settings}))))

        remove-attachment
        (mf/use-fn
         (fn [idx]
           (let [cur (deref attachments*)
                 item (get cur idx)]
             (when (:preview item) (js/URL.revokeObjectURL (:preview item)))
             (reset! attachments* (into [] (keep-indexed #(when (not= %1 idx) %2) cur))))))

        on-generate
        (mf/use-fn
         (mf/deps prompt attachments quality preset target variants image-mode
                 sessions active-session-id file-id)
         (fn []
           ;; Reset the carousel to the first variant of the new set so a
           ;; previous position never carries into a fresh generation.
           (reset! variant-idx* 0)
           ;; URLs are no longer a separate field — the user drops them into
           ;; the prompt and the backend's extract_urls parses them out.
           (if (and (str/empty? prompt) (empty? attachments))
             (st/emit! (ai/set-ai-error (tr "workspace.ai.bar.need-prompt")))
             ;; P1.13 — prepend the image-mode instruction (screenshot / sketch)
             ;; to the user prompt when an image-mode is active. The backend
             ;; already routes image attachments to the Kimi vision model.
             (let [mode-prefix (case image-mode
                                 "screenshot" (ai/screenshot-mode-prompt)
                                 "sketch"      (ai/sketch-mode-prompt)
                                 "")
                   eff-prompt  (str mode-prefix prompt)
                   ;; P2.30 — record the user turn into the active chat session.
                   record-user (fn []
                                 (when file-id
                                   (let [active-sess (when active-session-id
                                                       (some #(when (= (:id %) active-session-id) %)
                                                             sessions))
                                         sess (or active-sess (aich/new-session))
                                         sid (:id sess)
                                         sess' (aich/append-message
                                                sess {:role "user" :content prompt})
                                         sessions' (if active-session-id
                                                     (into [] (map #(if (= (:id %) sid) sess' %))
                                                           sessions)
                                                     (conj (vec sessions) sess'))]
                                     (reset! sessions* sessions')
                                     (reset! active-session-id* sid)
                                     (aich/save-sessions file-id sessions'))))]
               (record-user)
               ;; First, make sure the active provider actually has a key (or is
               ;; keyless local Ollama). If not, send the user to AI Settings to
               ;; enter one instead of firing a request that will only 401.
               (-> (ai/invoke-get-config)
                   (p/then
                    (fn [cfg-js]
                      (let [cfg (js->clj cfg-js :keywordize-keys true)]
                        (if (ai/ai-usable? cfg)
                          (-> (ai/files->inputs (mapv :file attachments))
                              (p/then
                               (fn [inputs]
                                 ;; "max" routes to the native agent loop (tool-calling +
                                 ;; vision scout); "auto" stays on the single-shot spec
                                 ;; path whose model is picked by the DeepSeek V4 Flash
                                 ;; router in the backend. No visual change to the bar.
                                 (let [ev-opts (cond-> {:target       target
                                                        :quality      quality
                                                        :frame-preset preset
                                                        :use-memory   true}
                                                 (and (= quality "auto") (> variants 1))
                                                 (assoc :variants variants))
                                       event (if (= quality "max")
                                               (ai/run-agent-design
                                                {:prompt eff-prompt :files inputs :options ev-opts})
                                               (if @stream?*
                                                 (ai/generate-design-stream
                                                  {:prompt eff-prompt :files inputs :options ev-opts})
                                                 (ai/generate-design
                                                  {:prompt eff-prompt :files inputs :options ev-opts})))]
                                   (st/emit! (ai/set-ai-error nil) event))))
                              (p/catch
                               (fn [e] (st/emit! (ai/set-ai-error (str e))))))
                          ;; No key for the active provider → open Settings.
                          (st/emit! (modal/show {:type :ai-settings})
                                    (ai/set-ai-error (tr "workspace.ai.bar.need-key")))))))
                   (p/catch
                    (fn [e] (st/emit! (ai/set-ai-error (str e))))))))))

        on-cancel
        (mf/use-fn (fn []
                     ;; Clear the local stage text immediately: a cancelled
                     ;; generation's HTTP request can't be interrupted, so
                     ;; the backend may never emit its "done" progress event.
                     (reset! stage* nil)
                     (st/emit! (ai/cancel-generation))))

        on-apply
        (mf/use-fn
         (mf/deps preview variant-idx)
         (fn []
           (let [{:keys [spec specs target]} preview
                 chosen (if (and (vector? specs) (seq specs))
                          (get specs variant-idx spec)
                          spec)
                 ;; Thread the user's design-system guidelines (from the AI
                 ;; Settings config) into apply-design-spec so the data layer's
                 ;; `apply-design-constraints` can snap colors/spacing to the
                 ;; token grid + emit reuse markers. Blank → not threaded →
                 ;; byte-identical to the unconstrained apply path.
                 apply-opts (fn [guidelines]
                              (cond-> {:spec chosen :target target}
                                (not (str/empty? guidelines))
                                (assoc :design-system-guidelines guidelines)))]
             (-> (ai/invoke-get-config)
                 (p/then
                  (fn [cfg-js]
                    (let [cfg (js->clj cfg-js :keywordize-keys true)
                          guidelines (or (:design_system_guidelines cfg) "")]
                      (st/emit! (dg/apply-design-spec (apply-opts guidelines))
                                (ai/clear-ai-preview)))))
                 (p/catch
                  (fn [_]
                    ;; Config read failed — fall back to the byte-identical
                    ;; unconstrained apply so a settings hiccup never blocks
                    ;; applying a generated spec.
                    (st/emit! (dg/apply-design-spec {:spec chosen :target target})
                              (ai/clear-ai-preview))))))))

        on-cancel-preview
        (mf/use-fn (fn [] (st/emit! (ai/clear-ai-preview))))

        on-regenerate
        (mf/use-fn
         (mf/deps on-generate)
         (fn []
           (st/emit! (ai/clear-ai-preview))
           (on-generate)))

        ;; ── Phase 2: carousel nav + cluster actions ────────────────────────
        on-var-prev
        (mf/use-fn
         (mf/deps variant-idx)
         (fn []
           (let [n (count (:specs preview))]
             (when (and (int? n) (> n 1))
               (reset! variant-idx* (mod (dec variant-idx) n))))))

        on-var-next
        (mf/use-fn
         (mf/deps variant-idx)
         (fn []
           (let [n (count (:specs preview))]
             (when (and (int? n) (> n 1))
               (reset! variant-idx* (mod (inc variant-idx) n))))))

        on-var-pick
        (mf/use-fn
         (fn [i] (reset! variant-idx* i)))

        ;; Pick a variant count (Auto mode only). Resetting the carousel to
        ;; 0 keeps it on the first variant of the new set.
        on-pick-variant
        (mf/use-fn
         (fn [n] (reset! variants* n) (reset! variant-idx* 0)))

        open-image
        (mf/use-fn
         (fn []
           (st/emit! (modal/show {:type :ai-image}))))

        on-review-design
        (mf/use-fn
         (fn []
           (st/emit! (ai/set-ai-error nil)
                     (ai/review-design))))

        on-spec-doc
        (mf/use-fn
         (mf/deps has-sel?)
         (fn []
           (st/emit! (ai/set-ai-error nil)
                     (ai/generate-spec-doc {:scope (if has-sel? "selection" "page")}))))

        on-close-review
        (mf/use-fn
         (fn [] (st/emit! (ai/set-ai-review nil))))

        on-close-spec-doc
        (mf/use-fn
         (fn [] (st/emit! (ai/set-ai-spec-doc nil))))

        ;; ── P1.13 — image-mode picker (none / screenshot / sketch) ──────────
        on-pick-image-mode
        (mf/use-fn
         (fn [m] (reset! image-mode* m)))

        ;; ── P2.04 — text operations popover ─────────────────────────────────
        on-toggle-text-ops
        (mf/use-fn (fn [] (swap! text-ops-open?* not)))
        on-close-text-ops
        (mf/use-fn (fn [] (reset! text-ops-open?* false)))
        on-change-translate-lang
        (mf/use-fn (fn [e] (reset! translate-lang* (.. e -target -value))))
        on-run-text-op
        (mf/use-fn
         (mf/deps translate-lang)
         (fn [op]
           (reset! text-ops-open?* false)
           (st/emit! (ai/set-ai-error nil)
                     (atop/run-text-op {:op op :lang translate-lang}))))

        ;; ── P2.28 — multi-screen adaptation popover ───────────────────────────
        on-toggle-adapt
        (mf/use-fn (fn [] (swap! adapt-open?* not)))
        on-close-adapt
        (mf/use-fn (fn [] (reset! adapt-open?* false)))
        on-run-adapt
        (mf/use-fn
         (fn [t]
           (reset! adapt-open?* false)
           (st/emit! (ai/set-ai-error nil)
                     (ai/adapt-screen {:target t}))))

        ;; ── P2.30 — chat history popover + persistence ────────────────────────
        on-toggle-chat
        (mf/use-fn (fn [] (swap! chat-open?* not)))
        on-close-chat
        (mf/use-fn (fn [] (reset! chat-open?* false)))
        on-new-chat
        (mf/use-fn
         (mf/deps sessions file-id)
         (fn []
           (let [ns (aich/new-session)
                 new-sessions (conj (vec sessions) ns)]
             (reset! sessions* new-sessions)
             (reset! active-session-id* (:id ns))
             (when file-id (aich/save-sessions file-id new-sessions))
             (reset! chat-open?* false))))
        on-resume-chat
        (mf/use-fn
         (fn [s]
           (reset! active-session-id* (:id s))
           (reset! chat-open?* false)))
        on-delete-chat
        (mf/use-fn
         (mf/deps sessions file-id active-session-id)
         (fn [s]
           (let [sid (:id s)
                 new-sessions (into [] (remove #(= (:id %) sid)) sessions)]
             (reset! sessions* new-sessions)
             (when (= sid active-session-id)
               (reset! active-session-id*
                       (some-> new-sessions first :id)))
             (when file-id (aich/save-sessions file-id new-sessions)))))

        ;; ── P2.34 — Show Code toggle + copy ──────────────────────────────────
        on-toggle-show-code
        (mf/use-fn (fn [] (swap! show-code?* not)))
        on-copy-code
        (mf/use-fn
         (fn [text]
           (some-> js/navigator .-clipboard (.writeText (str text)))))

        ;; ── P2.03 — prompt library popover + save-preset ───────────────────
        on-toggle-lib
        (mf/use-fn
         (fn []
           (swap! lib-open?* not)
           (reset! lib-search* "")))
        on-close-lib
        (mf/use-fn (fn [] (reset! lib-open?* false)))
        on-change-lib-search
        (mf/use-fn (fn [e] (reset! lib-search* (.. e -target -value))))
        on-change-save-group
        (mf/use-fn (fn [e] (reset! save-group* (.. e -target -value))))
        on-change-save-label
        (mf/use-fn (fn [e] (reset! save-label* (.. e -target -value))))
        on-pick-preset
        (mf/use-fn
         (fn [preset]
           (reset! prompt* (plib/use-preset preset))
           (reset! lib-open?* false)
           (reset! lib-search* "")))
        on-save-preset
        (mf/use-fn
         (mf/deps prompt save-group save-label)
         (fn []
           (when-not (str/empty? prompt)
             (st/emit! (plib/add-preset
                        {:group save-group :label save-label :prompt prompt}))
             (reset! save-group* "")
             (reset! save-label* ""))))
        on-delete-user-preset
        (mf/use-fn
         (fn [idx]
           (st/emit! (plib/delete-preset {:index idx}))))

        ;; ── P2.01 — AI design checklist popover + generate/toggle/clear ──
        on-toggle-checklist
        (mf/use-fn (fn [] (swap! check-open?* not)))
        on-close-checklist
        (mf/use-fn (fn [] (reset! check-open?* false)))
        on-generate-checklist
        (mf/use-fn
         (fn []
           (st/emit! (ai/set-ai-error nil)
                     (aicl/generate-checklist))))
        on-toggle-checklist-item
        (mf/use-fn
         (fn [id]
           (st/emit! (aicl/toggle-checklist-item id))))
        on-clear-checklist
        (mf/use-fn
         (fn []
           (st/emit! (aicl/clear-checklist))))

        ;; ── P2.19 — focus-area predictor popover + Go to ────────────────
        on-toggle-focus
        (mf/use-fn (fn [] (swap! focus-open?* not)))
        on-close-focus
        (mf/use-fn (fn [] (reset! focus-open?* false)))
        on-predict-focus
        (mf/use-fn
         (fn []
           (st/emit! (ai/set-ai-error nil)
                     (aicl/predict-focus))))
        on-goto-focus
        (mf/use-fn
         (fn [shape-id]
           (st/emit! (aicl/goto-focus shape-id))))
        on-dismiss-focus
        (mf/use-fn
         (fn []
           (st/emit! (aicl/clear-ai-focus))))

        ;; ── P2.41 — voice input (Web Speech API). Interim transcript
        ;; appends to the prompt; the final transcript is committed on
        ;; onresult. Nil-safe: the start fn is only reachable from the
        ;; render guard, which hides the mic button when the API is absent.
        on-start-listening
        (mf/use-fn
         (mf/deps prompt speech-supported?)
         (fn []
           (when speech-supported?
             (let [Ctor (or (.-SpeechRecognition js/window)
                            (.-webkitSpeechRecognition js/window))
                   rec  (new Ctor)]
               (set! (.-lang rec) "en-US")
               (set! (.-continuous rec) false)
               (set! (.-interimResults rec) true)
               (mf/set-ref-val! interim* nil)
               (let [base prompt]
                 (set! (.-onresult rec)
                       (fn [e]
                         (let [final-str (volatile! "")
                               interim-str (volatile! "")
                               results (.-results e)
                               n (.-length results)]
                           (doseq [i (range n)]
                             (let [res (aget results i)
                                   alt (aget res 0)
                                   txt (.-transcript alt)]
                               (if (.-isFinal res)
                                 (vswap! final-str str txt)
                                 (vswap! interim-str str txt))))
                           (reset! prompt*
                                   (str base @final-str @interim-str)))))
                 (set! (.-onerror rec)
                       (fn [_]
                         (reset! listening?* false)
                         (mf/set-ref-val! interim* nil)))
                 (set! (.-onend rec)
                       (fn []
                         (reset! listening?* false)
                         (mf/set-ref-val! interim* nil))))
               (mf/set-ref-val! speech-rec* rec)
               (reset! listening?* true)
               (try (.start rec) (catch :default _ (reset! listening?* false)))))))
        on-toggle-voice
        (mf/use-fn
         (mf/deps listening? on-start-listening)
         (fn []
           (if listening?
             (when-let [rec (mf/ref-val speech-rec*)]
               (try (.stop rec) (catch :default _ nil)))
             (on-start-listening))))]

    ;; ── Subscribe to backend ai-progress events → local stage text.
    (mf/with-effect
      []
      (let [unp (ai/subscribe-progress
                  (fn [payload]
                    (let [s (.-stage payload)
                          d (.-detail payload)]
                      (cond
                        (= s "done")     (reset! stage* nil)
                        (= s "starting") (reset! stage* (tr "workspace.ai.bar.stage-starting"))
                        (= s "fetching-url") (reset! stage* (tr "workspace.ai.bar.stage-fetching"))
                        (= s "scouting")  (reset! stage* (tr "workspace.ai.bar.stage-scouting"))
                        (= s "routing")   (reset! stage* (tr "workspace.ai.bar.stage-routing"))
                        (= s "generating") (reset! stage* (tr "workspace.ai.bar.stage-generating"))
                        (= s "finalizing") (reset! stage* (tr "workspace.ai.bar.stage-finalizing"))
                        (= s "tool-thinking") (reset! stage* (tr "workspace.ai.bar.stage-thinking"))
                        (= s "executing-tool") (reset! stage* (tr "workspace.ai.bar.stage-tool" (or d "")))
                        (= s "agent-done") (reset! stage* nil)
                        (= s "agent-max-iterations") (reset! stage* (tr "workspace.ai.bar.stage-max-iter"))
                        (= s "agent-cancelled") (reset! stage* nil)
                        :else (reset! stage* (str d))))))]
        (fn [] (-> unp
                   (p/then (fn [u] (when (fn? u) (u))))
                   (p/catch (fn [_] nil))))))

    ;; Clear the stage text whenever an error appears (the backend returns Err
    ;; BEFORE emitting "done", so the spinner would otherwise spin forever).
    (mf/with-effect [error*]
      (when error* (reset! stage* nil)))

    ;; Reset the carousel to the first variant whenever a fresh preview lands.
    ;; P2.30 — also record an assistant turn into the active chat session so
    ;; the conversation log captures both sides of each generation.
    (mf/with-effect [preview]
      (reset! variant-idx* 0)
      (when (and preview file-id active-session-id)
        (let [nframes (count (:frames (:spec preview)))
              summary (tr "workspace.ai.bar.chat.assistant-generated"
                          (max 1 (or nframes 1)))
              sess (some #(when (= (:id %) active-session-id) %) sessions)]
          (when sess
            (let [sess' (aich/append-message sess {:role "assistant" :content summary})
                  sessions' (into [] (map #(if (= (:id %) active-session-id) sess' %))
                                  sessions)]
              (reset! sessions* sessions')
              (aich/save-sessions file-id sessions')))))
      nil)

    ;; P2.30 — load persisted chat sessions for the current file on mount /
    ;; when the file changes.
    (mf/with-effect [file-id]
      (when file-id
        (let [loaded (aich/load-sessions file-id)]
          (reset! sessions* loaded)
          (when (seq loaded)
            (reset! active-session-id* (:id (first loaded))))))
      nil)

    ;; P2.19 — hydrate the ephemeral focus-prediction slot from file-level
    ;; plugin-data (:ovion "ai-focus") on mount / when the file changes, so
    ;; the last prediction survives reload. Only hydrates when the ephemeral
    ;; slot is empty (a freshly-predicted result takes precedence).
    (mf/with-effect [file-id]
      (when file-id
        (let [persisted (aicl/read-focus file-data)]
          (when (and persisted (nil? focus*))
            (st/emit! (aicl/set-ai-focus persisted)))))
      nil)

    ;; P2.04 / P2.28 / P2.30 — popover anime entrance + Escape-to-close for the
    ;; text-ops, adapt, and chat-history popovers. Mirrors the screen picker.
    (mf/with-effect [text-ops-open?]
      (if text-ops-open?
        (do
          (aim/pop-in (mf/ref-val text-ops-ref))
          (let [on-key (fn [e] (when (= (.-key e) "Escape")
                                 (reset! text-ops-open?* false)))]
            (.addEventListener js/document "keydown" on-key)
            (fn [] (.removeEventListener js/document "keydown" on-key))))
        (fn [] nil)))

    (mf/with-effect [adapt-open?]
      (if adapt-open?
        (do
          (aim/pop-in (mf/ref-val adapt-ref))
          (let [on-key (fn [e] (when (= (.-key e) "Escape")
                                 (reset! adapt-open?* false)))]
            (.addEventListener js/document "keydown" on-key)
            (fn [] (.removeEventListener js/document "keydown" on-key))))
        (fn [] nil)))

    (mf/with-effect [chat-open?]
      (if chat-open?
        (do
          (aim/pop-in (mf/ref-val chat-ref))
          (let [on-key (fn [e] (when (= (.-key e) "Escape")
                                 (reset! chat-open?* false)))]
            (.addEventListener js/document "keydown" on-key)
            (fn [] (.removeEventListener js/document "keydown" on-key))))
        (fn [] nil)))

    ;; P2.03 — prompt library popover: anime.js entrance + Escape-to-close.
    ;; Mirrors the screen/chat popovers. Reduced-motion forces opacity:1.
    (mf/with-effect [lib-open?]
      (if lib-open?
        (do
          (aim/pop-in (mf/ref-val lib-ref))
          (let [on-key (fn [e] (when (= (.-key e) "Escape")
                                 (reset! lib-open?* false)))]
            (.addEventListener js/document "keydown" on-key)
            (fn [] (.removeEventListener js/document "keydown" on-key))))
        (fn [] nil)))

    ;; P2.01 / P2.19 — checklist + focus popover anime entrance + Escape.
    ;; Mirrors the screen/chat/library popovers. Reduced-motion forces
    ;; opacity:1 via the @media block in ai-css.
    (mf/with-effect [check-open?]
      (if check-open?
        (do
          (aim/pop-in (mf/ref-val check-ref))
          (let [on-key (fn [e] (when (= (.-key e) "Escape")
                                 (reset! check-open?* false)))]
            (.addEventListener js/document "keydown" on-key)
            (fn [] (.removeEventListener js/document "keydown" on-key))))
        (fn [] nil)))

    (mf/with-effect [focus-open?]
      (if focus-open?
        (do
          (aim/pop-in (mf/ref-val focus-ref))
          (let [on-key (fn [e] (when (= (.-key e) "Escape")
                                 (reset! focus-open?* false)))]
            (.addEventListener js/document "keydown" on-key)
            (fn [] (.removeEventListener js/document "keydown" on-key))))
        (fn [] nil)))

    ;; ── Auto-grow the prompt textarea to fit its content (up to 160px),
    ;; so the input pill expands the way the reference's does. Runs on
    ;; every prompt change, including the regenerate path that re-fills it.
    (mf/with-effect [prompt]
      (when-let [el (mf/ref-val prompt-ref)]
        (set! (.. el -style -height) "auto")
        (let [sh (.. el -scrollHeight)]
          (set! (.. el -style -height) (str (min sh 160) "px"))))
      nil)

    ;; ── Screen picker: anime.js entrance + Escape-to-close. The popover
    ;; element starts at opacity:0 (CSS); pop-in tweens it in, and under
    ;; reduced motion / missing anime it forces the visible end state.
    (mf/with-effect [screen-open?]
      (if screen-open?
        (do
          (aim/pop-in (mf/ref-val pop-ref))
          (let [on-key (fn [e] (when (= (.-key e) "Escape") (reset! screen-open?* false)))]
            (.addEventListener js/document "keydown" on-key)
            (fn [] (.removeEventListener js/document "keydown" on-key))))
        (fn [] nil)))

    ;; Single root: a display:contents wrapper so the absolute dock + fixed
    ;; modal position against the workspace :section / viewport, not this div.
    [:div {:style #js {"display" "contents"}}
     (style-block)

     ;; P1.30 — transient streaming preview (renders nil when not streaming).
     [:> ais/stream-preview*]
     ;; P2.08 — agent branch-tree panel (renders nil when no branches).
     [:> aibr/branch-tree* {:open? @branches-open?*
                            :on-close #(reset! branches-open?* false)}]

     [:div.ai-root
      [:div.ai-dock
       ;; ── Primary row: matches the reference exactly ───────────────────
       [:div.ai-primary
        ;; cluster pill: [mode | paperclip | settings]
        [:div.ai-cluster
         [:button.ai-mode
          {:type "button"
           :class (when (= quality "max") "is-max")
           :on-click on-toggle-quality
           :on-mouse-enter aim/hov-white-in
           :on-mouse-leave aim/hov-white-out
           :on-mouse-down aim/press-white-in
           :on-mouse-up aim/press-white-out
           :aria-pressed (str (= quality "max"))}
          ;; Reference: the Auto/Max glyph sits on the RIGHT of the label.
          [:span (if (= quality "max")
                   (tr "workspace.ai.bar.mode-max")
                   (tr "workspace.ai.bar.mode-auto"))]
          [:span.ai-i-anim {:key (str "mode-" quality)}
           (if (= quality "max") lucide-sparkles lucide-refresh-cw)]]
         [:button.ai-circle
          {:type "button" :on-click open-picker
           :title (tr "workspace.ai.bar.attach")
           :on-mouse-enter aim/hov-white-in
           :on-mouse-leave aim/hov-white-out
           :on-mouse-down aim/press-white-in
           :on-mouse-up aim/press-white-out}
          lucide-paperclip]
         ;; P1.13 — Screenshot/Sketch to UI mode picker. A 3-option segmented
         ;; control next to the paperclip; selects how an attached image is
         ;; interpreted by the vision model (none / screenshot / sketch).
         [:div.ai-seg {:role "group"
                      :aria-label (tr "workspace.ai.bar.image-mode-label")}
          (for [m [{:v "none" :l (tr "workspace.ai.bar.image-mode-none")}
                    {:v "screenshot" :l (tr "workspace.ai.bar.image-mode-screenshot")}
                    {:v "sketch" :l (tr "workspace.ai.bar.image-mode-sketch")}]]
            [:button.ai-seg-opt
             {:type "button" :key (:v m)
              :class (when (= image-mode (:v m)) "is-cur")
              :aria-pressed (str (= image-mode (:v m)))
              :on-click #(on-pick-image-mode (:v m))}
             (:l m)])]
         [:button.ai-circle
          {:type "button" :on-click open-settings
           :title (tr "workspace.ai.bar.settings")
           :on-mouse-enter aim/hov-white-in
           :on-mouse-leave aim/hov-white-out
           :on-mouse-down aim/press-white-in
           :on-mouse-up aim/press-white-out}
          lucide-settings]
         ;; Phase 2 cluster actions: image / review / spec-doc (coral on hover).
         [:button.ai-circle
          {:type "button" :on-click open-image
           :title (tr "workspace.ai.bar.generate-image")
           :on-mouse-enter aim/hov-white-in
           :on-mouse-leave aim/hov-white-out
           :on-mouse-down aim/press-white-in
           :on-mouse-up aim/press-white-out}
          lucide-image]
         [:button.ai-circle
          {:type "button" :on-click on-review-design
           :title (tr "workspace.ai.bar.review-design")
           :on-mouse-enter aim/hov-white-in
           :on-mouse-leave aim/hov-white-out
           :on-mouse-down aim/press-white-in
           :on-mouse-up aim/press-white-out}
          lucide-scan-eye]
         [:button.ai-circle
          {:type "button" :on-click on-spec-doc
           :title (tr "workspace.ai.bar.spec-doc")
           :on-mouse-enter aim/hov-white-in
           :on-mouse-leave aim/hov-white-out
           :on-mouse-down aim/press-white-in
           :on-mouse-up aim/press-white-out}
          lucide-file-text]

         ;; P2.01 — AI design checklist. Opens a coral-bordered popover
         ;; listing the tickable checklist items (or a Generate call-to-
         ;; action when empty). Coral checkboxes turn filled-coral when
         ;; checked. Reduced-motion popover (pop-in / opacity:1 fallback).
         [:div.ai-mini-wrap
          [:button.ai-circle
           {:type "button" :on-click on-toggle-checklist
            :title (tr "workspace.ai.bar.checklist-tooltip")
            :on-mouse-enter aim/hov-white-in
            :on-mouse-leave aim/hov-white-out
            :on-mouse-down aim/press-white-in
            :on-mouse-up aim/press-white-out}
           lucide-list-checks]
          (when check-open?
            [:div.ai-mini-back {:on-click on-close-checklist}
             [:div.ai-check-pop {:ref check-ref
                                 :on-click #(.stopPropagation %)}
              [:div.ai-check-head
               [:span.ai-check-title (tr "workspace.ai.bar.checklist-title")]
               [:div.ai-check-actions
                [:button.ai-check-actbtn
                 {:type "button"
                  :disabled (empty? checklist)
                  :on-click on-generate-checklist
                  :title (tr "workspace.ai.bar.checklist-regenerate")}
                 (if (empty? checklist)
                   (tr "workspace.ai.bar.checklist-generate")
                   (tr "workspace.ai.bar.checklist-regenerate"))]
                [:button.ai-check-actbtn
                 {:type "button"
                  :disabled (empty? checklist)
                  :on-click on-clear-checklist
                  :title (tr "workspace.ai.bar.checklist-clear")}
                 (tr "workspace.ai.bar.checklist-clear")]]]
              [:div.ai-check-list
               (if (empty? checklist)
                 [:div.ai-check-empty
                  (tr "workspace.ai.bar.checklist-empty-state")
                  [:button.ai-check-genbtn
                   {:type "button"
                    :disabled busy
                    :on-click on-generate-checklist}
                   lucide-list-checks
                   (tr "workspace.ai.bar.checklist-generate")]]
                 (for [item checklist]
                   [:button.ai-check-item
                    {:key (:id item)
                     :type "button"
                     :class (when (:done? item) "is-done")
                     :on-click #(on-toggle-checklist-item (:id item))}
                    [:span.ai-check-box
                     (when (:done? item) lucide-check)]
                    [:span.ai-check-label (:label item)]]))]]])]

         ;; P2.19 — Focus-area predictor. Calls predict-focus and, on
         ;; result, shows a one-line rationale + a "Go to" action that
         ;; selects + centers the predicted shape via the existing
         ;; select-shape / zoom-to-selected-shape events. Reduced-motion.
         [:div.ai-mini-wrap
          [:button.ai-circle
           {:type "button" :on-click on-toggle-focus
            :title (tr "workspace.ai.bar.focus-tooltip")
            :on-mouse-enter aim/hov-white-in
            :on-mouse-leave aim/hov-white-out
            :on-mouse-down aim/press-white-in
            :on-mouse-up aim/press-white-out}
           lucide-crosshair]
          (when focus-open?
            [:div.ai-mini-back {:on-click on-close-focus}
             [:div.ai-focus-pop {:ref focus-ref
                                 :on-click #(.stopPropagation %)}
              [:div.ai-focus-head
               lucide-crosshair
               [:span.ai-focus-title (tr "workspace.ai.bar.focus-title")]]
              (if focus*
                [:*
                 [:div.ai-focus-rationale (:rationale focus* "")]
                 [:div.ai-focus-actions
                  [:button.ai-focus-go
                   {:type "button"
                    :disabled busy
                    :on-click #(on-goto-focus (:shape-id focus*))}
                   (tr "workspace.ai.bar.focus-go")]
                  [:button.ai-focus-dismiss
                   {:type "button" :on-click on-dismiss-focus}
                   (tr "workspace.ai.bar.focus-dismiss")]]]
                [:div.ai-focus-empty
                 (tr "workspace.ai.bar.focus-empty-state")
                 [:button.ai-check-genbtn
                  {:type "button"
                   :disabled busy
                   :on-click on-predict-focus}
                  lucide-crosshair
                  (tr "workspace.ai.bar.focus-predict")]])]])]

         ;; P2.04 — AI text operations on the selected text shape. Opens a
         ;; popover with Translate / Continue / Polish / Summarize + a
         ;; target-language field for Translate.
         [:div.ai-mini-wrap
          [:button.ai-circle
           {:type "button" :on-click on-toggle-text-ops
            :title (tr "workspace.ai.bar.text-ops-tooltip")
            :on-mouse-enter aim/hov-white-in
            :on-mouse-leave aim/hov-white-out
            :on-mouse-down aim/press-white-in
            :on-mouse-up aim/press-white-out}
           lucide-languages]
          (when text-ops-open?
            [:div.ai-mini-back {:on-click on-close-text-ops}
             [:div.ai-mini-pop {:ref text-ops-ref
                                :on-click #(.stopPropagation %)}
              [:div.ai-textop-row
               [:input.ai-textop-input
                {:type "text"
                 :value translate-lang
                 :placeholder (tr "workspace.ai.bar.text-ops-lang-placeholder")
                 :on-change on-change-translate-lang}]]
              [:div.ai-textop-sep]
              [:button.ai-screen-opt
               {:type "button" :on-click #(on-run-text-op :translate)}
               lucide-languages [:span (tr "workspace.ai.bar.text-op-translate")]]
              [:button.ai-screen-opt
               {:type "button" :on-click #(on-run-text-op :continue)}
               lucide-type [:span (tr "workspace.ai.bar.text-op-continue")]]
              [:button.ai-screen-opt
               {:type "button" :on-click #(on-run-text-op :polish)}
               lucide-type [:span (tr "workspace.ai.bar.text-op-polish")]]
              [:button.ai-screen-opt
               {:type "button" :on-click #(on-run-text-op :summarize)}
               lucide-type [:span (tr "workspace.ai.bar.text-op-summarize")]]]])]

         ;; P2.28 — Multi-screen size adaptation. Opens a popover with
         ;; Mobile / Tablet / Desktop one-shot reflow of the selection.
         [:div.ai-mini-wrap
          [:button.ai-circle
           {:type "button" :on-click on-toggle-adapt
            :title (tr "workspace.ai.bar.adapt-tooltip")
            :on-mouse-enter aim/hov-white-in
            :on-mouse-leave aim/hov-white-out
            :on-mouse-down aim/press-white-in
            :on-mouse-up aim/press-white-out}
           lucide-monitor-smartphone]
          (when adapt-open?
            [:div.ai-mini-back {:on-click on-close-adapt}
             [:div.ai-mini-pop {:ref adapt-ref
                                :on-click #(.stopPropagation %)}
              [:button.ai-screen-opt
               {:type "button" :on-click #(on-run-adapt "mobile")}
               lucide-smartphone [:span (tr "workspace.ai.bar.adapt-mobile")]]
              [:button.ai-screen-opt
               {:type "button" :on-click #(on-run-adapt "tablet")}
               lucide-tablet [:span (tr "workspace.ai.bar.adapt-tablet")]]
              [:button.ai-screen-opt
               {:type "button" :on-click #(on-run-adapt "desktop")}
               lucide-monitor [:span (tr "workspace.ai.bar.adapt-desktop")]]]])]

         ;; P2.30 — Per-project chat history browser. Opens the history
         ;; popover (list / new chat / resume / delete).
         [:div.ai-mini-wrap
          [:button.ai-circle
           {:type "button" :on-click on-toggle-chat
            :title (tr "workspace.ai.bar.chat-tooltip")
            :on-mouse-enter aim/hov-white-in
            :on-mouse-leave aim/hov-white-out
            :on-mouse-down aim/press-white-in
            :on-mouse-up aim/press-white-out}
           lucide-history]
          (when chat-open?
            [aich/chat-history-popover
             {:sessions sessions
              :active-id active-session-id
              :on-new on-new-chat
              :on-resume on-resume-chat
              :on-delete on-delete-chat
              :pop-ref chat-ref
              :on-close on-close-chat}])]

         ;; P1.05 — AI next-screens generation. Fires the next-screens
         ;; event which asks the model for the next logical screens of the
         ;; current flow and creates them right of the existing content.
         [:div.ai-mini-wrap
          [:button.ai-circle
           {:type "button" :on-click #(st/emit! (ai/generate-next-screens {}))
            :title (tr "workspace.ai.bar.next-screens")
            :on-mouse-enter aim/hov-white-in :on-mouse-leave aim/hov-white-out
            :on-mouse-down aim/press-white-in :on-mouse-up aim/press-white-out}
           lucide-git-branch]]

         ;; P1.30 — streaming generation toggle (auto mode only). Coral when
         ;; active; routes on-generate to generate-design-stream.
         [:div.ai-mini-wrap
          [:button.ai-circle
           {:type "button" :on-click #(swap! stream?* not)
            :class (when @stream?* "is-max")
            :title (tr "workspace.ai.bar.stream-toggle")
            :on-mouse-enter aim/hov-white-in :on-mouse-leave aim/hov-white-out
            :on-mouse-down aim/press-white-in :on-mouse-up aim/press-white-out}
           lucide-zap]]

         ;; P2.08 — agent branch-tree panel toggle. Opens the branch viewer.
         [:div.ai-mini-wrap
          [:button.ai-circle
           {:type "button" :on-click #(swap! branches-open?* not)
            :class (when @branches-open?* "is-max")
            :title (tr "workspace.ai.bar.branches-toggle")
            :on-mouse-enter aim/hov-white-in :on-mouse-leave aim/hov-white-out
            :on-mouse-down aim/press-white-in :on-mouse-up aim/press-white-out}
           lucide-git-branch]]

         ;; attachment thumbnails live inside the cluster so the paperclip's
         ;; result is visible without leaving the primary bar.
         (when (seq attachments)
           [:div.ai-thumbs
            (for [idx (range (count attachments))]
              (let [a (get attachments idx)]
                [:div.ai-thumb {:key idx
                                :style #js {"animationDelay" (str (* idx 40) "ms")}}
                 [:img {:src (:preview a) :alt (:name a)}]
                 [:span.ai-thumb-x {:on-click #(remove-attachment idx)} lucide-x]]))])]

        ;; variant-count segmented control (Auto mode only). Reuses the
        ;; screen-option button (ai-screen-opt + is-cur coral selected
        ;; state) inside a pill wrapper that mirrors the cluster's white
        ;; surface + coral inset ring via the shared design tokens. Hidden
        ;; entirely in "max" mode, where run-agent-design is single-spec.
        (when (= quality "auto")
          [:div {:role "group"
                 :aria-label (tr "workspace.ai.bar.variants-label")
                 :style #js {"display" "inline-flex"
                             "alignItems" "center"
                             "gap" "2px"
                             "flex" "none"
                             "padding" "4px"
                             "background" "var(--ai-white)"
                             "borderRadius" "var(--ai-radius-pill)"
                             "boxShadow" "var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral)"}}
           (for [n (range 1 5)]
             [:button.ai-screen-opt
              {:type "button"
               :key n
               :aria-pressed (str (= variants n))
               :class (when (= variants n) "is-cur")
               :on-click #(on-pick-variant n)
               :on-mouse-enter aim/hov-white-in
               :on-mouse-leave aim/hov-white-out
               :on-mouse-down aim/press-white-in
               :on-mouse-up aim/press-white-out
               :style #js {"padding" "6px 11px"
                           "borderRadius" "var(--ai-radius-sm)"
                           "fontSize" "13px"
                           "justifyContent" "center"}}
              (str n)])])

        ;; input pill: [prompt textarea | library + mic | coral send disc]
        [:div.ai-input-pill {:class (when expanded? "is-expanded")}
         [:textarea.ai-prompt
          {:ref prompt-ref
           :placeholder (tr "workspace.ai.bar.placeholder")
           :value prompt
           :on-change on-prompt
           :rows 1}]
         ;; P2.03 — prompt library button + grouped popover. Sits inside
         ;; the input pill as a compact ghost circle (coral when open).
         [:div.ai-mini-wrap
          [:button.ai-inpell
           {:type "button"
            :class (when lib-open? "is-active")
            :on-click on-toggle-lib
            :aria-expanded (str lib-open?)
            :aria-haspopup "listbox"
            :title (tr "workspace.ai.bar.prompt-library-tooltip")}
           lucide-book-marked]
          (when lib-open?
            [:div.ai-mini-back {:on-click on-close-lib}
             [:div.ai-lib-pop {:ref lib-ref :role "listbox"
                               :on-click #(.stopPropagation %)}
              [:div.ai-lib-search-row
               lucide-search
               [:input.ai-lib-search
                {:type "text"
                 :value lib-search
                 :placeholder (tr "workspace.ai.bar.prompt-library-search")
                 :on-change on-change-lib-search}]]
              [:div.ai-lib-list
               (if (empty? lib-filtered)
                 [:div.ai-lib-empty (tr "workspace.ai.bar.prompt-library-empty")]
                 (for [g lib-groups]
                   [:div {:key g}
                    [:div.ai-lib-group g]
                    (for [p (filter #(= (:group %) g) lib-filtered)]
                      [:div.ai-lib-item
                       {:key (str g "|" (:label p) "|" (:user-idx p -1))
                        :type "button"
                        :role "option"
                        :on-click #(on-pick-preset p)}
                       [:span.ai-lib-label (:label p)]
                       [:span.ai-lib-prompt (:prompt p)]
                       (when (:user? p)
                         [:button.ai-lib-del
                          {:type "button"
                           :title (tr "workspace.ai.bar.prompt-library-delete")
                           :on-click (fn [e]
                                       (.stopPropagation e)
                                       (on-delete-user-preset (:user-idx p)))}
                          lucide-trash-2])])]))]
              [:div.ai-lib-sep]
              [:div.ai-lib-save-row
               [:input.ai-lib-save-input
                {:type "text"
                 :value save-group
                 :placeholder (tr "workspace.ai.bar.prompt-library-save-group")
                 :on-change on-change-save-group}]
               [:input.ai-lib-save-input
                {:type "text"
                 :value save-label
                 :placeholder (tr "workspace.ai.bar.prompt-library-save-label")
                 :on-change on-change-save-label}]
               [:button.ai-lib-save-btn
                {:type "button"
                 :disabled (str/empty? prompt)
                 :on-click on-save-preset}
                lucide-bookmark-plus
                (tr "workspace.ai.bar.prompt-library-save")]]]])]
         ;; P2.41 — voice input button. Hidden entirely when the Web Speech
         ;; API is unsupported (nil-safe). Coral + pulse while listening;
         ;; reduced-motion makes it solid coral with no pulse (CSS).
         (when speech-supported?
           [:button.ai-inpell.ai-mic
            {:type "button"
             :class (when listening? "is-listening")
             :on-click on-toggle-voice
             :aria-pressed (str listening?)
             :title (tr (if listening?
                          "workspace.ai.bar.voice-stop"
                          "workspace.ai.bar.voice-start"))}
            lucide-mic])
         [:button.ai-send
          {:type "button"
           :on-click on-generate
           :disabled (or busy (and (str/empty? prompt) (empty? attachments)))
           :on-mouse-enter aim/hov-coral-in
           :on-mouse-leave aim/hov-coral-out
           :on-mouse-down aim/press-coral-in
           :on-mouse-up aim/press-coral-out}
          (if busy (spinner) lucide-arrow-up)]]

        ;; screen pill: frame-preset picker (custom themed popover).
        [:div.ai-screen-wrap
         [:button.ai-screen
          {:type "button"
           :class (when screen-open? "is-open")
           :on-click on-toggle-screen
           :aria-expanded (str screen-open?)
           :aria-haspopup "listbox"
           :title (tr "workspace.ai.bar.screen-tooltip")
           :on-mouse-enter aim/hov-white-in
           :on-mouse-leave aim/hov-white-out
           :on-mouse-down aim/press-white-in
           :on-mouse-up aim/press-white-out}
          (preset-icon preset)
          [:span (preset-label preset)]
          lucide-chevron-down]
         (when screen-open?
           [:div.ai-screen-back {:on-click on-close-screen}
            [:div.ai-screen-pop {:ref pop-ref :role "listbox"
                                 :on-click #(.stopPropagation %)}
             (for [p presets]
               (let [cur? (= (:v p) preset)]
                 [:button.ai-screen-opt
                  {:key (:v p)
                   :type "button"
                   :role "option"
                   :aria-selected (str cur?)
                   :class (when cur? "is-cur")
                   :on-click #(on-pick-screen (:v p))}
                  (:icon p)
                  [:span (:label p)]
                  (when cur? lucide-check)]))]])]]

       ;; ── Stage + error (generation progress) ──────────────────────────
       (when (or stage busy)
         [:div.ai-stage
          (stage-dots)
          (or stage (tr "workspace.ai.bar.working"))
          (when busy
            [:button.ai-cancel {:type "button" :on-click on-cancel}
             (tr "workspace.ai.bar.cancel")])])
       (when error* [:div.ai-err error*])

       ;; hidden file input
       [:input {:type "file" :accept "image/*" :multiple true
                :ref file-input* :style #js {"display" "none"}
                :on-change on-pick-files}]]

      ;; ── Preview modal ───────────────────────────────────────────────────
      (when-let [p preview]
        (let [specs  (:specs p)
              multi? (and (vector? specs) (> (count specs) 1))
              cur    (if multi? (get specs variant-idx) (:spec p))
              ;; P2.34 — code-gen of the just-generated element. Pure: expand
              ;; the current spec into a shape tree, then run the same Inspect
              │  Code generators (HTML markup + CSS style) the Inspect panel
              ;;  uses. Defensive — any failure yields nil so the code panel
              ;;  shows an "unavailable" message instead of crashing the modal.
              tree         (try (cds/spec->shape-tree cur) (catch :default _ nil))
              code-objects (when tree (:objects tree))
              code-order   (when tree (:order tree))
              ;; Top-level frames only (parent-id == uuid/zero) — exactly the
              ;; `root-shapes` the publish/inspect code paths feed to
              ;; `generate-markup-code` / `generate-style-code`.
              top-shapes   (when (and code-objects code-order)
                             (into []
                                   (keep (fn [id]
                                           (let [s (get code-objects id)]
                                             (when (= (:parent-id s) uuid/zero) s))))
                                   code-order))
              all-shapes   (when code-objects
                             (into [] (vals code-objects)))
              markup-code  (when (seq top-shapes)
                             (try (cg/generate-markup-code code-objects "html" top-shapes)
                                  (catch :default _ nil)))
              style-code   (when (seq top-shapes)
                             (try (cg/generate-style-code code-objects "css" top-shapes all-shapes)
                                  (catch :default _ nil)))]
          [:div.ai-overlay {:on-click on-cancel-preview}
           [:div.ai-modal {:on-click #(.stopPropagation %)}
            [:div.ai-modal-head
             [:div {:style #js {"display" "flex" "alignItems" "center" "gap" "10px"}}
              [:span.ai-modal-title (tr "workspace.ai.bar.preview-title")]
              [:span.ai-badge (if (= (:target p) "update-selection")
                                (tr "workspace.ai.bar.preview-region")
                                (tr "workspace.ai.bar.preview-full"))]
              (when multi?
                [:span.ai-badge
                 (tr "workspace.ai.bar.variants" (inc variant-idx) (count specs))])]
             [:div {:style #js {"display" "flex" "alignItems" "center" "gap" "8px"}}
              ;; P2.34 — Show Code toggle (alongside the preview).
              [:button.ai-code-toggle
               {:type "button"
                :class (when show-code? "is-on")
                :aria-pressed (str show-code?)
                :on-click on-toggle-show-code
                :title (tr "workspace.ai.bar.show-code-tooltip")}
               lucide-code
               (tr "workspace.ai.bar.show-code")]
              [:button.ai-close {:type "button" :on-click on-cancel-preview
                                 :on-mouse-enter aim/hov-white-in
                                 :on-mouse-leave aim/hov-white-out
                                 :on-mouse-down aim/press-white-in
                                 :on-mouse-up aim/press-white-out}
               lucide-x]]]
            [:div.ai-modal-body
             (if show-code?
               ;; P2.34 — code panel: HTML markup + CSS style, each with a
               ;; copy button (Lucide copy, stroke-width 2, currentColor).
               [:div.ai-code-panel
                [:div.ai-code-sec
                 [:div.ai-code-head
                  [:span.ai-code-label (tr "workspace.ai.bar.code-markup")]
                  [:button.ai-code-copy
                   {:type "button" :on-click #(on-copy-code markup-code)}
                   lucide-copy
                   (tr "workspace.ai.bar.copy")]]
                 (if (str/empty? markup-code)
                   [:div.ai-code-empty (tr "workspace.ai.bar.code-unavailable")]
                   [:pre.ai-code-block markup-code])]
                [:div.ai-code-sec
                 [:div.ai-code-head
                  [:span.ai-code-label (tr "workspace.ai.bar.code-style")]
                  [:button.ai-code-copy
                   {:type "button" :on-click #(on-copy-code style-code)}
                   lucide-copy
                   (tr "workspace.ai.bar.copy")]]
                 (if (str/empty? style-code)
                   [:div.ai-code-empty (tr "workspace.ai.bar.code-unavailable")]
                   [:pre.ai-code-block style-code])]]
               (if multi?
                 [:div.ai-var-wrap
                  [:div.ai-var-row
                   [:button.ai-circle.ai-var-arrow
                    {:type "button" :on-click on-var-prev
                     :title (tr "workspace.ai.bar.variants-prev")
                     :on-mouse-enter aim/hov-white-in
                     :on-mouse-leave aim/hov-white-out
                     :on-mouse-down aim/press-white-in
                     :on-mouse-up aim/press-white-out}
                    lucide-chevron-left]
                   [:div.ai-var-col (dg/spec->preview cur)]
                   [:button.ai-circle.ai-var-arrow
                    {:type "button" :on-click on-var-next
                     :title (tr "workspace.ai.bar.variants-next")
                     :on-mouse-enter aim/hov-white-in
                     :on-mouse-leave aim/hov-white-out
                     :on-mouse-down aim/press-white-in
                     :on-mouse-up aim/press-white-out}
                    lucide-chevron-right]]
                  [:div.ai-dots-ind
                   (for [i (range (count specs))]
                     [:button.ai-dot {:key i :type "button"
                                      :class (when (= i variant-idx) "is-cur")
                                      :on-click #(on-var-pick i)
                                      :aria-label (tr "workspace.ai.bar.variants"
                                                       (inc i) (count specs))}])]]
                 (dg/spec->preview cur)))]
            [:div.ai-modal-foot
             [:button.ai-btn.ai-btn-ghost {:on-click on-regenerate
                                           :on-mouse-enter aim/hov-white-in
                                           :on-mouse-leave aim/hov-white-out
                                           :on-mouse-down aim/press-white-in
                                           :on-mouse-up aim/press-white-out}
              (tr "workspace.ai.bar.regenerate")]
             [:button.ai-btn.ai-btn-ghost {:on-click on-cancel-preview
                                           :on-mouse-enter aim/hov-white-in
                                           :on-mouse-leave aim/hov-white-out
                                           :on-mouse-down aim/press-white-in
                                           :on-mouse-up aim/press-white-out}
              (tr "workspace.ai.bar.cancel")]
             [:button.ai-btn.ai-btn-primary {:on-click on-apply
                                             :on-mouse-enter aim/hov-coral-in
                                             :on-mouse-leave aim/hov-coral-out
                                             :on-mouse-down aim/press-coral-in
                                             :on-mouse-up aim/press-coral-out}
              (tr "workspace.ai.bar.apply")]]]]))

      ;; ── Review design result modal (read-only) ───────────────────────────
      (when-let [r review*]
        [:div.ai-overlay {:on-click on-close-review}
         [:div.ai-modal {:on-click #(.stopPropagation %)}
          [:div.ai-modal-head
           [:div {:style #js {"display" "flex" "alignItems" "center" "gap" "10px"}}
            [:span.ai-modal-title (tr "workspace.ai.bar.review-design")]
            (when (number? (:score r))
              [:span.ai-result-score
               (tr "workspace.ai.bar.review-score" (:score r))])]
           [:button.ai-close {:type "button" :on-click on-close-review
                              :on-mouse-enter aim/hov-white-in
                              :on-mouse-leave aim/hov-white-out
                              :on-mouse-down aim/press-white-in
                              :on-mouse-up aim/press-white-out}
            lucide-x]]
          [:div.ai-modal-body
           (when (:summary r)
             [:div [:div.ai-result-body (:summary r)]])
           (when (seq (:strengths r))
             [:div [:div.ai-result-sec (tr "workspace.ai.bar.review-strengths")]
              [:ul.ai-result-list (for [s (:strengths r)] [:li {:key s} s])]])
           (when (seq (:issues r))
             [:div [:div.ai-result-sec (tr "workspace.ai.bar.review-issues")]
              [:ul.ai-result-list
               (for [it (:issues r)]
                 [:li {:key (str (get it :title) (get it :severity))}
                  (str (get it :title "") " — " (get it :detail ""))])]])
           (when (seq (:recommendations r))
             [:div [:div.ai-result-sec (tr "workspace.ai.bar.review-recommendations")]
              [:ul.ai-result-list (for [s (:recommendations r)] [:li {:key s} s])]])]
          [:div.ai-modal-foot
           [:button.ai-btn.ai-btn-primary {:on-click on-close-review
                                           :on-mouse-enter aim/hov-coral-in
                                           :on-mouse-leave aim/hov-coral-out
                                           :on-mouse-down aim/press-coral-in
                                           :on-mouse-up aim/press-coral-out}
            (tr "workspace.ai.bar.close")]]]])

      ;; ── Spec doc result modal (read-only markdown) ───────────────────────
      (when-let [sd spec-doc*]
        [:div.ai-overlay {:on-click on-close-spec-doc}
         [:div.ai-modal {:on-click #(.stopPropagation %)}
          [:div.ai-modal-head
           [:div {:style #js {"display" "flex" "alignItems" "center" "gap" "10px"}}
            [:span.ai-modal-title (tr "workspace.ai.bar.spec-doc")]]
           [:button.ai-close {:type "button" :on-click on-close-spec-doc
                              :on-mouse-enter aim/hov-white-in
                              :on-mouse-leave aim/hov-white-out
                              :on-mouse-down aim/press-white-in
                              :on-mouse-up aim/press-white-out}
            lucide-x]]
          [:div.ai-modal-body
           [:div.ai-result-body (or (:markdown sd) (:html sd) "")]]
          [:div.ai-modal-foot
           [:button.ai-btn.ai-btn-primary {:on-click on-close-spec-doc
                                           :on-mouse-enter aim/hov-coral-in
                                           :on-mouse-leave aim/hov-coral-out
                                           :on-mouse-down aim/press-coral-in
                                           :on-mouse-up aim/press-coral-out}
            (tr "workspace.ai.bar.close")]]]])]]))