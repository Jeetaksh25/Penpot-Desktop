;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.workshop
  "Workshop overlay (ALL_APPS_PARITY P1.35) — interactive learning center.

  A slide-in panel (right side) with two modes:
    1. Library  — searchable/filterable list of tutorials grouped by
       category and difficulty, with a search input and filter chips.
    2. Runner   — the currently active tutorial: step title/body/hint,
       a progress indicator, and Back / Next / Restart / Close controls.

  Visual world: matches Ovion's AI surfaces exactly — white surfaces, the
  #f28b82 coral accent, Helvetica Now Display, Lucide icons (stroke-width
  2, currentColor). Tokens + @font-face + reduced-motion guard are reused
  from `app.main.ui.workspace.ai-design` via `(ad/base-style-block)`; the
  root element carries `ai-root` so the `--ai-*` custom properties apply,
  plus `ws-root` for the workshop's own scoped styles. Class names are
  plain strings (the AI-surface idiom — raw CSS, no SCSS pipeline).

  Mounted by `ui/workspace.cljs` ONLY when `:workshop-open?` is true, so
  the closed path renders nothing (byte-identical-when-inactive)."
  (:require
   [app.main.data.workspace.workshop :as wsp]
   [app.main.store :as st]
   [app.main.ui.workspace.ai-design :as ad]
   [app.util.i18n :as i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Workshop-scoped CSS -----------------------------------------------------
;; Reuses the --ai-* tokens (scoped to .ai-root by ai-base-css) and adds
;; panel/layout-specific rules under .ws-root. Raw CSS string, no SCSS.

(def ^:private workshop-css
  "
.ws-overlay { position: fixed; inset: 0; background: var(--ai-overlay);
  z-index: 200; display: flex; justify-content: flex-end;
  animation: ws-overlay-in 220ms cubic-bezier(0.16, 1, 0.3, 1) both; }
.ws-panel { width: min(440px, 100%); height: 100%; background: var(--ai-white);
  display: flex; flex-direction: column; box-shadow: -20px 0 60px rgba(0,0,0,0.3);
  font-family: var(--ai-font); color: var(--ai-ink);
  animation: ws-panel-in 320ms cubic-bezier(0.16, 1, 0.3, 1) both; }
.ws-head { padding: 18px 20px 14px; border-bottom: 1px solid #ececec;
  display: flex; align-items: center; gap: 12px; flex: 0 0 auto; }
.ws-head-icon { width: 34px; height: 34px; border-radius: 10px; display: flex;
  align-items: center; justify-content: center; color: var(--ai-coral);
  background: var(--ai-coral-faint); flex: 0 0 auto; }
.ws-head-text { display: flex; flex-direction: column; gap: 1px; flex: 1 1 auto; min-width: 0; }
.ws-title { font-size: 15px; font-weight: 700; color: var(--ai-ink); font-family: var(--ai-font); }
.ws-subtitle { font-size: 11px; color: var(--ai-grey); font-family: var(--ai-font); }
.ws-close { width: 30px; height: 30px; border-radius: 9px; border: none; cursor: pointer;
  background: transparent; color: var(--ai-grey); display: flex; align-items: center;
  justify-content: center; flex: 0 0 auto;
  transition: background 150ms cubic-bezier(0.16, 1, 0.3, 1), color 150ms cubic-bezier(0.16, 1, 0.3, 1); }
.ws-close:hover { background: var(--ai-coral-faint); color: var(--ai-coral); }

.ws-body { flex: 1 1 auto; overflow: auto; padding: 16px 20px 20px; }

.ws-search { position: relative; margin-bottom: 14px; }
.ws-search-input { width: 100%; border: 1px solid #ececec; border-radius: 10px;
  padding: 9px 12px 9px 34px; font-size: 13px; font-family: var(--ai-font);
  color: var(--ai-ink); background: var(--ai-white); outline: none;
  transition: border-color 150ms cubic-bezier(0.16, 1, 0.3, 1), box-shadow 150ms cubic-bezier(0.16, 1, 0.3, 1); }
.ws-search-input:focus { border-color: var(--ai-coral); box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ws-search-icon { position: absolute; left: 10px; top: 50%; transform: translateY(-50%);
  color: var(--ai-grey); pointer-events: none; }

.ws-filters { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 16px; align-items: center; }
.ws-spacer { flex: 1 1 auto; }
.ws-chip { border: 1px solid #ececec; border-radius: 999px; padding: 5px 11px;
  font-size: 11px; font-weight: 600; font-family: var(--ai-font); cursor: pointer;
  background: var(--ai-white); color: var(--ai-grey);
  transition: background 150ms cubic-bezier(0.16, 1, 0.3, 1), color 150ms cubic-bezier(0.16, 1, 0.3, 1), border-color 150ms cubic-bezier(0.16, 1, 0.3, 1); }
.ws-chip:hover { border-color: var(--ai-coral); color: var(--ai-coral); }
.ws-chip.is-active { background: var(--ai-coral); border-color: var(--ai-coral); color: var(--ai-white); }

.ws-section-label { font-size: 11px; font-weight: 700; letter-spacing: 0.04em;
  text-transform: uppercase; color: var(--ai-grey); margin: 6px 0 10px; font-family: var(--ai-font); }

.ws-card { border: 1px solid #ececec; border-radius: 12px; padding: 14px 15px;
  margin-bottom: 10px; cursor: pointer; background: var(--ai-white);
  display: flex; flex-direction: column; gap: 6px;
  transition: border-color 150ms cubic-bezier(0.16, 1, 0.3, 1), box-shadow 150ms cubic-bezier(0.16, 1, 0.3, 1); }
.ws-card:hover { border-color: var(--ai-coral); box-shadow: 0 2px 10px rgba(242,139,130,0.15); }
.ws-card-head { display: flex; align-items: flex-start; gap: 10px; }
.ws-card-title { font-size: 14px; font-weight: 700; color: var(--ai-ink); font-family: var(--ai-font); flex: 1 1 auto; }
.ws-card-desc { font-size: 12px; color: var(--ai-grey-2); font-family: var(--ai-font); line-height: 1.45; }
.ws-card-meta { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; margin-top: 2px; }
.ws-tag { font-size: 10px; font-weight: 600; font-family: var(--ai-font); padding: 3px 8px;
  border-radius: 999px; background: #f4f4f4; color: var(--ai-grey-2);
  display: inline-flex; align-items: center; gap: 4px; }
.ws-tag.is-done { background: var(--ai-coral-faint); color: var(--ai-coral); }
.ws-difficulty { font-size: 10px; font-weight: 600; font-family: var(--ai-font);
  color: var(--ai-grey); display: inline-flex; align-items: center; gap: 4px; }

.ws-empty { text-align: center; color: var(--ai-grey); font-size: 13px;
  font-family: var(--ai-font); padding: 40px 10px; }

.ws-runner { display: flex; flex-direction: column; gap: 16px; }
.ws-runner-top { display: flex; align-items: center; gap: 10px; }
.ws-back { border: none; background: transparent; cursor: pointer; color: var(--ai-grey);
  font-size: 12px; font-weight: 600; font-family: var(--ai-font); display: inline-flex;
  align-items: center; gap: 4px; padding: 4px 2px;
  transition: color 150ms cubic-bezier(0.16, 1, 0.3, 1); }
.ws-back:hover { color: var(--ai-coral); }

.ws-progress { display: flex; gap: 4px; }
.ws-progress-dot { height: 4px; flex: 1 1 0; border-radius: 999px; background: #ececec;
  transition: background 200ms cubic-bezier(0.16, 1, 0.3, 1); }
.ws-progress-dot.is-done { background: var(--ai-coral); }
.ws-progress-dot.is-current { background: var(--ai-coral); }

.ws-step-num { font-size: 11px; font-weight: 700; letter-spacing: 0.04em;
  text-transform: uppercase; color: var(--ai-coral); font-family: var(--ai-font); }
.ws-step-title { font-size: 18px; font-weight: 700; color: var(--ai-ink); font-family: var(--ai-font);
  line-height: 1.3; }
.ws-step-body { font-size: 13px; color: var(--ai-ink); font-family: var(--ai-font); line-height: 1.55; }
.ws-hint { display: flex; gap: 8px; align-items: flex-start; padding: 11px 13px;
  border-radius: 10px; background: var(--ai-coral-faint); color: var(--ai-coral);
  font-size: 12px; font-family: var(--ai-font); line-height: 1.45; }
.ws-hint-icon { flex: 0 0 auto; margin-top: 1px; }

.ws-runner-foot { display: flex; gap: 8px; align-items: center; padding-top: 4px; }
.ws-btn { border: none; border-radius: 10px; padding: 9px 16px; font-size: 13px;
  font-weight: 600; cursor: pointer; font-family: var(--ai-font);
  display: inline-flex; align-items: center; justify-content: center; gap: 7px; white-space: nowrap;
  transition: background 150ms cubic-bezier(0.16, 1, 0.3, 1), color 150ms cubic-bezier(0.16, 1, 0.3, 1); }
.ws-btn-primary { background: var(--ai-coral); color: var(--ai-white); }
.ws-btn-primary:hover { background: var(--ai-coral-press); }
.ws-btn-ghost { background: transparent; color: var(--ai-grey); }
.ws-btn-ghost:hover { color: var(--ai-coral); }
.ws-btn-ghost:disabled { color: #d4d4d4; cursor: default; }
.ws-btn-icon { background: transparent; color: var(--ai-grey); width: 34px; height: 34px;
  padding: 0; border-radius: 9px; }
.ws-btn-icon:hover { color: var(--ai-coral); background: var(--ai-coral-faint); }

@keyframes ws-overlay-in { from { opacity: 0; } to { opacity: 1; } }
@keyframes ws-panel-in { from { transform: translateX(40px); opacity: 0; }
  to { transform: translateX(0); opacity: 1; } }

@media (prefers-reduced-motion: reduce) {
  .ws-overlay, .ws-panel, .ws-overlay *, .ws-panel * {
    animation-duration: 0.001ms !important; transition-duration: 0.001ms !important; }
}
")

;; --- Lucide icons (inline SVG, stroke-width 2, currentColor) -----------------

(defn- icon-graduation-cap
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size
         :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M22 10v6M2 10l10-5 10 5-10 5z"}]
   [:path {:d "M6 12v5c3 3 9 3 12 0v-5"}]])

(defn- icon-search
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size
         :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:circle {:cx "11" :cy "11" :r "8"}]
   [:path {:d "m21 21-4.3-4.3"}]])

(defn- icon-check
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size
         :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M20 6 9 17l-5-5"}]])

(defn- icon-chevron-left
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size
         :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "m15 18-6-6 6-6"}]])

(defn- icon-chevron-right
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size
         :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "m9 18 6-6-6-6"}]])

(defn- icon-x
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size
         :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M18 6 6 18"}]
   [:path {:d "m6 6 12 12"}]])

(defn- icon-rotate-ccw
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size
         :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"}]
   [:path {:d "M3 3v5h5"}]])

(defn- icon-lightbulb
  [size]
  [:svg {:xmlns "http://www.w3.org/2000/svg" :width size :height size
         :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :aria-hidden "true"}
   [:path {:d "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5"}]
   [:path {:d "M9 18h6"}]
   [:path {:d "M10 22h4"}]])

;; --- Static option lists -----------------------------------------------------

(def ^:private categories
  "The six workshop categories, in display order."
  ["Getting started" "Layout" "Components" "Prototyping" "AI" "Export"])

(def ^:private difficulties
  ["beginner" "intermediate" "advanced"])

(defn- difficulty-label
  [difficulty]
  (condp = difficulty
    "beginner"     (tr "workspace.workshop.difficulty.beginner")
    "intermediate" (tr "workspace.workshop.difficulty.intermediate")
    "advanced"     (tr "workspace.workshop.difficulty.advanced")
    difficulty))

(defn- chip-class
  [active?]
  (cstr/join " " (concat ["ws-chip"] (when active? ["is-active"]))))

(defn- dot-class
  [idx cur]
  (cstr/join " "
             (concat ["ws-progress-dot"]
                     (when (<= idx cur) ["is-done"])
                     (when (= idx cur) ["is-current"]))))

;; --- Runner view -------------------------------------------------------------

(mf/defc runner-view*
  {::mf/private true}
  [{:keys [tutorial step]}]
  (let [steps      (:steps tutorial)
        n          (count steps)
        max-step   (dec n)
        cur-step   (max 0 (min step max-step))
        step-map   (nth steps cur-step)

        on-back    (mf/use-fn #(st/emit! (wsp/set-step (dec cur-step))))
        on-next    (mf/use-fn #(st/emit! (wsp/advance-step)))
        on-restart (mf/use-fn #(st/emit! (wsp/start-tutorial (:id tutorial))))
        on-library (mf/use-fn #(st/emit! (wsp/back-to-library)))]

    [:div.ws-runner
     [:div.ws-runner-top
      [:button {:type "button" :class "ws-back" :on-click on-library
                :aria-label (tr "workspace.workshop.library")}
       (icon-chevron-left 16)
       (tr "workspace.workshop.library")]]

     [:div.ws-progress
      (map-indexed
       (fn [idx _]
         [:div {:key idx :class (dot-class idx cur-step)}])
       steps)]

     [:div
      [:div.ws-step-num
       (tr "workspace.workshop.step.of" (inc cur-step) n)]
      [:div.ws-step-title (:title step-map)]
      [:div.ws-step-body (:body step-map)]]

     [:div.ws-hint
      [:span.ws-hint-icon (icon-lightbulb 15)]
      [:span (:hint step-map)]]

     [:div.ws-runner-foot
      [:button {:type "button" :class "ws-btn ws-btn-icon"
                :on-click on-restart :title (tr "workspace.workshop.restart")
                :aria-label (tr "workspace.workshop.restart")}
       (icon-rotate-ccw 17)]
      [:button {:type "button" :class "ws-btn ws-btn-ghost"
                :on-click on-back :disabled (zero? cur-step)}
       (icon-chevron-left 16)
       (tr "workspace.workshop.back")]
      [:div.ws-spacer]
      (if (>= cur-step max-step)
        [:button {:type "button" :class "ws-btn ws-btn-primary"
                  :on-click #(st/emit! (wsp/complete-tutorial))}
         (icon-check 16)
         (tr "workspace.workshop.finish")]
        [:button {:type "button" :class "ws-btn ws-btn-primary"
                  :on-click on-next}
         (tr "workspace.workshop.next")
         (icon-chevron-right 16)])]]))

;; --- Panel (shared shell: header + body dispatch) ----------------------------

(mf/defc workshop-panel*
  "The workshop overlay. Rendered by workspace.cljs ONLY when
  `:workshop-open?` is true, so the closed path emits nothing
  (byte-identical-when-inactive)."
  []
  (let [active-ref  (mf/with-memo [] (l/derived :workshop-active-tutorial st/state))
        step-ref    (mf/with-memo [] (l/derived :workshop-step st/state))
        active-id   (mf/deref active-ref)
        step        (mf/deref step-ref)

        progress    (mf/use-memo (mf/deps active-id) (wsp/read-progress))

        search*      (mf/use-state "")
        search       (deref search*)
        cat-filter*  (mf/use-state nil)
        cat-filter   (deref cat-filter*)
        diff-filter* (mf/use-state nil)
        diff-filter  (deref diff-filter*)

        on-close     (mf/use-fn #(st/emit! (wsp/close-workshop)))
        on-search    (mf/use-fn #(reset! search* (.. % -target -value)))
        on-pick-cat  (mf/use-fn
                      (mf/deps cat-filter)
                      (fn [cat]
                        (reset! cat-filter* (if (= cat cat-filter) nil cat))))
        on-pick-diff (mf/use-fn
                      (mf/deps diff-filter)
                      (fn [d]
                        (reset! diff-filter* (if (= d diff-filter) nil d))))
        on-start     (mf/use-fn #(st/emit! (wsp/start-tutorial %)))

        matches
        (mf/use-memo
         (mf/deps search cat-filter diff-filter)
         (fn []
           (let [q (cstr/lower-case search)]
             (filter
              (fn [t]
                (and
                 (or (nil? cat-filter)  (= (:category t) cat-filter))
                 (or (nil? diff-filter) (= (:difficulty t) diff-filter))
                 (or (= q "")
                     (cstr/includes? (cstr/lower-case (:title t)) q)
                     (cstr/includes? (cstr/lower-case (:description t)) q))))
              wsp/tutorials))))

        tutorial (when active-id (wsp/tutorial-by-id active-id))]

    [:div.ws-overlay {:on-click on-close}
     [:div.ai-root.ws-root
      (ad/base-style-block)
      [:style {:dangerouslySetInnerHTML #js {:__html workshop-css}}]
      [:div.ws-panel {:on-click #(.stopPropagation %)}
       [:div.ws-head
        [:div.ws-head-icon (icon-graduation-cap 20)]
        [:div.ws-head-text
         [:div.ws-title
          (if tutorial (:title tutorial) (tr "workspace.workshop.title"))]
         [:div.ws-subtitle
          (if tutorial
            (tr "workspace.workshop.runner.subtitle")
            (tr "workspace.workshop.subtitle"))]]
        [:button {:type "button" :class "ws-close" :on-click on-close
                  :title (tr "workspace.workshop.close")
                  :aria-label (tr "workspace.workshop.close")}
         (icon-x 18)]]

       [:div.ws-body
        (if tutorial
          [:> runner-view* {:tutorial tutorial :step step}]

          [:*
           [:div.ws-search
            [:span.ws-search-icon (icon-search 16)]
            [:input {:type "text"
                     :class "ws-search-input"
                     :placeholder (tr "workspace.workshop.search.placeholder")
                     :value search
                     :on-change on-search}]]

           [:div.ws-filters
            (for [cat categories]
              [:button {:key cat :type "button"
                        :class (chip-class (= cat cat-filter))
                        :on-click #(on-pick-cat cat)}
               cat])
            [:div.ws-spacer]
            (for [d difficulties]
              [:button {:key d :type "button"
                        :class (chip-class (= d diff-filter))
                        :on-click #(on-pick-diff d)}
               (difficulty-label d)])]

           [:div.ws-section-label (tr "workspace.workshop.section.tutorials")]

           (if (empty? matches)
             [:div.ws-empty (tr "workspace.workshop.empty")]
             (for [t matches]
               (let [entry (get progress (:id t))
                     done? (:completed? entry)]
                 [:div.ws-card {:key (:id t) :on-click #(on-start (:id t))}
                  [:div.ws-card-head
                   [:div.ws-card-title (:title t)]
                   (when done?
                     [:span.ws-tag.is-done
                      (icon-check 12)
                      (tr "workspace.workshop.completed")])]
                  [:div.ws-card-desc (:description t)]
                  [:div.ws-card-meta
                   [:span.ws-tag (:category t)]
                   [:span.ws-difficulty (difficulty-label (:difficulty t))]
                   (when (and entry (pos? (:step entry)) (not done?))
                     [:span.ws-difficulty
                      (tr "workspace.workshop.in.progress")])]])))])]]]]))