;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-branches
  "P2.08 — AI Agent branch-tree viewer.

  Renders the branch tree of concurrent / iterative AI explorations
  persisted on file-level plugin-data `:ovion \"ai-branches\"` (read via
  `aib/read-branches` from `refs/workspace-data`). Shows each branch with a
  status indicator (coral = :active, grey = :done, red = :error) and
  Re-run / Discard controls. Re-run fires `ai/run-agent-branch` with the
  branch's prompt + id as the new branch's parent, so a re-run becomes a
  child in the tree. Discard fires `aib/discard-branch`.

  Self-contained: derefs `refs/workspace-data` directly so the AI bar can
  mount it with a bare `[:> branch-tree*]`. Renders nil when there are no
  branches (byte-identical-when-inactive: an empty tree mounts no DOM).
  Styled to match the AI bar's reference visual world (white surface, coral
  inset ring, Helvetica Now Display, Lucide glyphs). Reduced-motion is
  honored by the shared `ai-base-css` guard."
  (:require
   [app.main.data.workspace.ai-branches :as aib]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.ai-design :as ad]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; ── Lucide glyphs — stroke-width 2, currentColor ─────────────────────────────

;; `li` returns a real React element (via `ad/icon-el`), not a hiccup
;; vector — a bare vector as a React child throws Minified error #31.
(defn- li [body]
  (ad/icon-el
   (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                :aria-hidden "true"}]
         body)))

(def ^:private lucide-git-branch
  (li [[:line {:x1 6 :y1 3 :x2 6 :y2 15}]
       [:circle {:cx 18 :cy 6 :r 3}]
       [:circle {:cx 6 :cy 18 :r 3}]
       [:path {:d "M18 9a9 9 0 0 1-9 9"}]]))

(def ^:private lucide-refresh-cw
  (li [[:path {:d "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"}]
       [:path {:d "M21 3v5h-5"}]
       [:path {:d "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"}]
       [:path {:d "M3 21v-5h5"}]]))

(def ^:private lucide-trash-2
  (li [[:path {:d "M3 6h18"}]
       [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
       [:line {:x1 10 :y1 11 :x2 10 :y2 17}]
       [:line {:x1 14 :y1 11 :x2 14 :y2 17}]]))

(def ^:private lucide-x
  (li [[:path {:d "M18 6 6 18"}]
       [:path {:d "m6 6 12 12"}]]))

(def ^:private lucide-loader-2
  (mf/html [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                  :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                  :aria-hidden "true"
                  :style #js {"animation" "ai-spin 0.9s linear infinite"}}
            [:path {:d "M21 12a9 9 0 1 1-6.219-8.56"}]]))

;; ── Component CSS (scoped under .aibr-root) ──────────────────────────────────

(def ^:private branch-css
  "
.aibr-root {
  position: absolute; left: 50%; transform: translateX(-50%);
  bottom: 92px; z-index: 58;
  width: min(480px, calc(100% - 48px));
  background: var(--ai-white);
  border-radius: var(--ai-radius-md);
  padding: 12px 14px 14px;
  box-shadow: var(--ai-shadow-soft), inset 0 0 0 2px var(--ai-coral-soft);
  font-family: var(--ai-font);
  animation: ai-rise var(--ai-dur-base) var(--ai-ease-out) both;
}
.aibr-head {
  display: flex; align-items: center; gap: 8px; margin-bottom: 10px;
  color: var(--ai-grey-2); font-size: 13px; font-weight: 600;
}
.aibr-head .aibr-i { width: 15px; height: 15px; color: var(--ai-coral); display: inline-flex; }
.aibr-head .aibr-close {
  margin-left: auto; border: none; background: transparent; cursor: pointer;
  color: var(--ai-grey); display: inline-flex; padding: 2px;
  transition: color var(--ai-dur-fast) var(--ai-ease-out);
}
.aibr-head .aibr-close:hover { color: var(--ai-ink); }
.aibr-head .aibr-close .aibr-i { width: 14px; height: 14px; }

.aibr-tree { display: flex; flex-direction: column; gap: 4px; max-height: 260px; overflow: auto; }

.aibr-node { display: flex; align-items: flex-start; gap: 8px; padding: 6px 6px;
  border-radius: var(--ai-radius-sm); }
.aibr-node:hover { background: var(--ai-coral-faint); }

.aibr-rail { width: 2px; align-self: stretch; background: var(--ai-coral-soft);
  border-radius: 1px; flex: none; margin-left: 6px; margin-right: 1px; }
.aibr-rail.is-empty { background: transparent; }

.aibr-dot { width: 9px; height: 9px; border-radius: 50%; flex: none;
  margin-top: 5px; box-shadow: 0 0 0 2px var(--ai-white); }
.aibr-dot.is-active { background: var(--ai-coral); }
.aibr-dot.is-done   { background: var(--ai-grey); }
.aibr-dot.is-error  { background: #e0524d; }

.aibr-main { flex: 1; min-width: 0; }
.aibr-prompt { font-size: 13px; color: var(--ai-ink); line-height: 1.4;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.aibr-result { font-size: 12px; color: var(--ai-grey); line-height: 1.35;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 1px; }
.aibr-result.is-error { color: #c0392b; }

.aibr-actions { display: flex; align-items: center; gap: 4px; flex: none; }
.aibr-btn { border: none; background: transparent; cursor: pointer;
  color: var(--ai-grey); display: inline-flex; padding: 3px;
  border-radius: 6px; transition: color var(--ai-dur-fast) var(--ai-ease-out),
                                      background var(--ai-dur-fast) var(--ai-ease-out); }
.aibr-btn:hover { color: var(--ai-coral); background: var(--ai-coral-faint); }
.aibr-btn .aibr-i { width: 14px; height: 14px; }

.aibr-children { margin-left: 18px; display: flex; flex-direction: column; gap: 4px; }
.aibr-empty { font-size: 12.5px; color: var(--ai-grey); padding: 4px 6px 2px; }

/* ── Reduced motion — non-negotiable. The shared ai-base-css guard covers
   .ai-root / .ais-root but NOT .aibr-root, so this local rule kills every
   animation + transition on the branch tree when the user prefers reduced
   motion. Mirrors the shared guard exactly. ──────────────────────────── */
@media (prefers-reduced-motion: reduce) {
  .aibr-root, .aibr-root * {
    animation-duration: 0.001ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.001ms !important;
    scroll-behavior: auto !important;
  }
}
")

;; ── Status indicator + label ─────────────────────────────────────────────────

(defn- status-dot-class [status]
  (condp = status
    :active "aibr-dot is-active"
    :done   "aibr-dot is-done"
    :error  "aibr-dot is-error"
    "aibr-dot is-done"))

(defn- status-label [status]
  (condp = status
    :active (tr "workspace.ai.branches.status-active")
    :done   (tr "workspace.ai.branches.status-done")
    :error  (tr "workspace.ai.branches.status-error")
    ""))

;; ── Node renderer ────────────────────────────────────────────────────────────

(mf/defc branch-node*
  "Render one branch node + its children (recursive). `node` is
  `{:branch <branch> :children [<node> ...]}`."
  [{:keys [node on-rerun on-discard]}]
  (let [{:keys [branch children]} node
        {:keys [id prompt status result]} branch
        on-rerun   (mf/use-fn (mf/deps id) (fn [] (on-rerun branch)))
        on-discard (mf/use-fn (mf/deps id) (fn [] (on-discard id)))]
    [:div.aibr-node
     [:div.aibr-dot {:class (status-dot-class status)
                     :title (status-label status)}]
     [:div.aibr-main
      [:div.aibr-prompt (or prompt "—")]
      [:div.aibr-result {:class (when (= status :error) "is-error")}
       (cond
         (= status :active) (tr "workspace.ai.branches.status-active")
         (seq result) result
         :else (status-label status))]]
     [:div.aibr-actions
      (when (= status :active)
        [:span.aibr-i lucide-loader-2])
      [:button.aibr-btn {:type "button" :on-click on-rerun
                         :title (tr "workspace.ai.branches.rerun")}
       [:span.aibr-i lucide-refresh-cw]]
      [:button.aibr-btn {:type "button" :on-click on-discard
                         :title (tr "workspace.ai.branches.discard")}
       [:span.aibr-i lucide-trash-2]]]
     (when (seq children)
       [:div.aibr-children
             (for [c children]
               [:> branch-node* {:key (:id (:branch c))
                                 :node c
                                 :on-rerun on-rerun
                                 :on-discard on-discard}])])]))

;; ── Root component ───────────────────────────────────────────────────────────

(mf/defc branch-tree*
  "Render the agent branch tree. Self-contained — derefs
  `refs/workspace-data` and reads branches via `aib/read-branches`. Renders
  nil when there are no (non-discarded) branches (byte-identical-when-
  inactive). Optional props:
    :open?    boolean (default true) — collapse the panel when false.
    :on-close 0-ary callback — render a close button in the header when
              provided (the AI bar can use it to toggle the panel)."
  [{:keys [open? on-close]}]
  (let [file-data (mf/deref refs/workspace-data)
        branches  (aib/read-branches file-data)
        tree      (aib/branches->tree branches)
        on-rerun
        (mf/use-fn
         (fn [branch]
           (st/emit! (ai/run-agent-branch
                      {:prompt (:prompt branch)
                       :parent-id (:id branch)
                       :options {:target "new-board" :quality "auto"
                                 :use-memory true}}))))
        on-discard
        (mf/use-fn
         (fn [id] (st/emit! (aib/discard-branch {:id id}))))]
    (when (and (or (nil? open?) open?) (seq tree))
      [:div {:style #js {"display" "contents"}}
       (ad/base-style-block)
       [:style {:dangerouslySetInnerHTML #js {:__html branch-css}}]
       [:div.aibr-root
        [:div.aibr-head
         [:span.aibr-i lucide-git-branch]
         [:span (tr "workspace.ai.branches.title")]
         (when on-close
           [:button.aibr-close {:type "button" :on-click on-close
                                :aria-label (tr "workspace.ai.branches.close")}
            [:span.aibr-i lucide-x]])]
        [:div.aibr-tree
              (for [node tree]
                [:> branch-node* {:key (:id (:branch node))
                                  :node node
                                  :on-rerun on-rerun
                                  :on-discard on-discard}])]]])))
