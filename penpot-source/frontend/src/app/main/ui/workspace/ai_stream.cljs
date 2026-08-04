;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-stream
  "P1.30 — Streaming AI generation live preview.

  A transient text preview area that appears above the AI dock while a
  streaming generation is in flight (`refs/ai-streaming` true). It reveals
  the live stage text + progressively-revealed generated frame names from
  `refs/ai-stream`, so the user sees progress live instead of a frozen
  spinner. Collapses to nothing when streaming ends (byte-identical-when-
  inactive: when `:ai-streaming` is nil/false the component renders nil and
  no DOM is mounted).

  Self-contained: derefs `refs/ai-streaming` + `refs/ai-stream` directly so
  the AI bar can mount it with a bare `[:> stream-preview*]` and no prop
  wiring. Styled to match the AI bar's reference visual world (white
  surface, coral inset ring, Helvetica Now Display, Lucide loader glyph).
  Reduced-motion is honored by the shared `ai-base-css` guard."
  (:require
   [app.main.refs :as refs]
   [app.main.ui.workspace.ai-design :as ad]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; ── Lucide loader-2 (spinning ring) — stroke-width 2, currentColor ──────────

(def ^:private lucide-loader-2
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"
         :style #js {"animation" "ai-spin 0.9s linear infinite"}}
   [:path {:d "M21 12a9 9 0 1 1-6.219-8.56"}]])

;; ── Component CSS (scoped under .ais-root) ───────────────────────────────────

(def ^:private stream-css
  "
.ais-root {
  position: absolute; left: 50%; transform: translateX(-50%);
  bottom: 92px; z-index: 59;
  width: min(560px, calc(100% - 48px));
  background: var(--ai-white);
  border-radius: var(--ai-radius-md);
  padding: 14px 18px;
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral-soft);
  font-family: var(--ai-font);
  animation: ai-rise var(--ai-dur-base) var(--ai-ease-out) both;
  pointer-events: none;             /* the preview is informational only */
}
.ais-head {
  display: flex; align-items: center; gap: 10px; margin-bottom: 8px;
  color: var(--ai-coral); font-size: 13.5px; font-weight: 600;
}
.ais-head .ais-i { width: 16px; height: 16px; display: inline-flex; }
.ais-body {
  font-size: 13px; line-height: 1.55; color: var(--ai-grey-2);
  white-space: pre-wrap; word-break: break-word;
  max-height: 180px; overflow: hidden;
}
.ais-dot {
  display: inline-block; width: 4px; height: 4px; border-radius: 50%;
  background: var(--ai-coral); margin-left: 2px; vertical-align: middle;
  animation: ai-dot 1.1s var(--ai-ease-in-out) infinite;
}
")

(mf/defc stream-preview*
  "Render the transient streaming preview. Returns nil when not streaming
  (byte-identical-when-inactive). Self-contained — no props."
  []
  (let [streaming (mf/deref refs/ai-streaming)
        text      (mf/deref refs/ai-stream)]
    (when (and streaming (or (nil? text) (seq text)))
      [:div {:style #js {"display" "contents"}}
       (ad/base-style-block)
       [:style {:dangerouslySetInnerHTML #js {:__html stream-css}}]
       [:div.ais-root
        [:div.ais-head
         [:span.ais-i lucide-loader-2]
         [:span (tr "workspace.ai.stream.title")]
         [:span.ais-dot]]
        [:div.ais-body (or text (tr "workspace.ai.stream.stage-starting"))]]])))