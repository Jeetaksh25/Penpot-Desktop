;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.inspect.site-audit
  "In-design accessibility audit panel (parity gap P2.35).

  A self-contained Inspect panel that runs `sas/run-audit` over the
  current page's `objects` map and reports contrast / alt-text /
  hierarchy / touch-target issues in a scrollable list. Clicking an
  issue selects the offending shape via `dws/select-shapes`. The result
  lives in local component state — nothing is persisted, and 'Re-run'
  recomputes on demand.

  Analytics (A/B testing, funnels, click tracking) is intentionally
  deferred: those features require server-side hosting + event
  ingestion that the offline Tauri desktop shell does not provide, so
  surfacing them here would be dishonest. See the ns docstring of
  `app.main.data.workspace.site-audit` for the full check catalogue."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace :as dws]
   [app.main.data.workspace.site-audit :as sas]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Small presentational helpers
;; ---------------------------------------------------------------------------

(defn- severity-icon
  "Inline Lucide-style icon (stroke-width 2, currentColor) for an issue
  severity. error = alert-circle, warning = alert-triangle."
  [severity]
  (if (= :error severity)
    [:svg {:class (stl/css :site-audit-icon :site-audit-icon-error)
           :viewBox "0 0 24 24" :width 14 :height 14
           :fill "none" :stroke "currentColor"
           :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
           :aria-hidden true}
     [:circle {:cx 12 :cy 12 :r 10}]
     [:line {:x1 12 :y1 8 :x2 12 :y2 12}]
     [:line {:x1 12 :y1 16 :x2 12.01 :y2 16}]]
    [:svg {:class (stl/css :site-audit-icon :site-audit-icon-warn)
           :viewBox "0 0 24 24" :width 14 :height 14
           :fill "none" :stroke "currentColor"
           :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
           :aria-hidden true}
     [:path {:d "M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"}]
     [:line {:x1 12 :y1 9 :x2 12 :y2 13}]
     [:line {:x1 12 :y1 17 :x2 12.01 :y2 17}]]))

(defn- category-label
  "Human label for an issue category, i18n-keyed."
  [category]
  (case category
    :contrast      (tr "inspect.site-audit.category.contrast")
    :alt-text      (tr "inspect.site-audit.category.alt-text")
    :hierarchy     (tr "inspect.site-audit.category.hierarchy")
    :touch-target  (tr "inspect.site-audit.category.touch-target")
    (name category)))

;; ---------------------------------------------------------------------------
;; Panel
;; ---------------------------------------------------------------------------

(mf/defc site-audit*
  "In-design accessibility audit panel. Receives the page `objects` map
  (the same `objects` the Inspect right sidebar already passes to its
  other panels) and scans every shape on the page on demand.

  State machine of `result*`:
    nil  — never run; shows the prompt + 'Run audit' button.
    []   — run, no issues; shows the pass state.
    [...] — run, with issues; shows summary + scrollable list.

  Clicking an issue emits `dws/select-shapes` with the issue's
  `:shape-id` so the offending shape is selected in the canvas."
  {::mf/private true}
  [{:keys [objects] :as props}]
  (let [result*   (mf/use-state nil)
        on-run    (mf/use-fn
                   (mf/deps objects)
                   (fn []
                     (reset! result* (sas/run-audit objects))))
        on-select (mf/use-fn
                   (fn [shape-id]
                     (when (some? shape-id)
                       (st/emit! (dws/select-shapes (d/ordered-set shape-id))))))]
    (let [issues  (deref result*)
          n       (count issues)
          n-error (count (filter #(= :error (:severity %)) issues))
          n-warn  (count (filter #(= :warning (:severity %)) issues))]
      [:div {:class (stl/css :site-audit-section)}
       [:div {:class (stl/css :site-audit-header)}
        [:span {:class (stl/css :site-audit-title)}
         (tr "inspect.site-audit.title")]
        [:button {:class (stl/css :site-audit-run-btn)
                  :type "button"
                  :on-click on-run}
         [:svg {:class (stl/css :site-audit-run-icon)
                :viewBox "0 0 24 24" :width 14 :height 14
                :fill "none" :stroke "currentColor"
                :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                :aria-hidden true}
          [:polyline {:points "23 4 23 10 17 10"}]
          [:path {:d "M20.49 15a9 9 0 1 1-2.12-9.36L23 10"}]]
         [:span (if (nil? issues)
                  (tr "inspect.site-audit.run")
                  (tr "inspect.site-audit.rerun"))]]]

       (cond
         (nil? issues)
         [:div {:class (stl/css :site-audit-empty)}
          (tr "inspect.site-audit.empty")]

         (zero? n)
         [:div {:class (stl/css :site-audit-pass)}
          [:svg {:class (stl/css :site-audit-pass-icon)
                 :viewBox "0 0 24 24" :width 16 :height 16
                 :fill "none" :stroke "currentColor"
                 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                 :aria-hidden true}
           [:path {:d "M22 11.08V12a10 10 0 1 1-5.93-9.14"}]
           [:polyline {:points "22 4 12 14.01 9 11.01"}]]
          [:span (tr "inspect.site-audit.no-issues")]]

         :else
         [:*
          [:div {:class (stl/css :site-audit-summary)}
           [:span {:class (stl/css-case :site-audit-count true
                                        :site-audit-count-error (> n-error 0))}
            (tr "inspect.site-audit.errors" n-error)]
           [:span {:class (stl/css-case :site-audit-count true
                                        :site-audit-count-warn (> n-warn 0))}
            (tr "inspect.site-audit.warnings" n-warn)]]
          [:div {:class (stl/css :site-audit-list)}
           (for [[idx issue] (map-indexed vector issues)]
             [:button {:key idx
                       :class (stl/css :site-audit-item)
                       :type "button"
                       :on-click (fn [] (on-select (:shape-id issue)))}
              [:div {:class (stl/css :site-audit-item-head)}
               [severity-icon (:severity issue)]
               [:span {:class (stl/css :site-audit-item-cat)}
                (category-label (:category issue))]]
              [:div {:class (stl/css :site-audit-item-msg)}
               (:message issue)]
              [:div {:class (stl/css :site-audit-item-sug)}
               (:suggestion issue)]])]])])))