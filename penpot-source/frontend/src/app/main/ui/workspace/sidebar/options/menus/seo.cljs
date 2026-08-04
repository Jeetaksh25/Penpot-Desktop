;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.seo
  "ALL_APPS_PARITY P1.23 — SEO metadata panel (page-level).

  Renders inputs for per-page SEO metadata: Title, Description, OG image
  URL, Keywords (comma-separated). Reads the current page's `:seo` map
  from `refs/workspace-page` (see app.main.data.workspace.seo) and emits
  `set-page-seo` on change.

  Mounted by the lead on frames in frame.cljs. Lucide icons are inlined
  (viewBox 0 0 24 24, stroke-width 2, currentColor, no emoji). Coral
  accent #f28b82 on the section header icon. i18n keys are placeholders —
  the lead adds them to en.po."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.seo :as seo]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :as i18n :refer [tr]]
   [clojure.string :as cstr]
   [rumext.v2 :as mf]))

;; ── Inline Lucide icons ────────────────────────────────────────────────────
;; viewBox "0 0 24 24", stroke-width 2, currentColor, no emoji.

(defn- lucide-globe
  "Lucide `globe` icon — coral accent on the SEO section header."
  []
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :width 16 :height 16 :class (stl/css :seo-section-icon)}
   [:circle {:cx 12 :cy 12 :r 10}]
   [:line {:x1 2 :y1 12 :x2 22 :y2 12}]
   [:path {:d "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"}]])

;; ── SEO field row ──────────────────────────────────────────────────────────

(mf/defc seo-field*
  {::mf/wrap [mf/memo]}
  [{:keys [label value placeholder on-change]}]
  (let [on-change* (mf/use-fn
                    (mf/deps on-change)
                    (fn [e]
                      (let [v (.. e -target -value)]
                        (on-change v))))]
    [:div {:class (stl/css :seo-row)}
     [:label {:class (stl/css :seo-label)} label]
     [:input {:type "text"
              :class (stl/css :seo-input)
              :value (or value "")
              :placeholder placeholder
              :on-change on-change*}]]))

;; ── Menu ───────────────────────────────────────────────────────────────────

(mf/defc seo-menu*
  {::mf/wrap [mf/memo]}
  [{:keys [shapes]}]
  ;; `shapes` is supplied by frame.cljs (the lead mounts us on frames) but
  ;; SEO is page-level, so we read from `refs/workspace-page` directly.
  (when (seq shapes)
    (let [page      (mf/deref refs/workspace-page)
          seo       (or (:seo page) {})
          open?*    (mf/use-state true)
          open?     (deref open?*)
          toggle    (mf/use-fn #(swap! open?* not))

          ;; Debounced emitter: collapse rapid keystrokes into one undo step.
          timer*   (mf/use-ref nil)

          emit-seo
          (mf/use-fn
           (fn [partial]
             (when-let [t @timer*] (js/clearTimeout t))
             (mf/set-ref-val! timer*
                              (js/setTimeout
                               (fn []
                                 (st/emit! (seo/set-page-seo partial))
                                 (mf/set-ref-val! timer* nil))
                               350))))

          on-title       (mf/use-fn (mf/deps emit-seo) #(emit-seo {:title %}))
          on-description (mf/use-fn (mf/deps emit-seo) #(emit-seo {:description %}))
          on-og-image    (mf/use-fn (mf/deps emit-seo) #(emit-seo {:og-image %}))
          on-keywords    (mf/use-fn (mf/deps emit-seo)
                                    (fn [v]
                                      ;; Comma-separated string -> vector of trimmed strings.
                                      (let [kws (->> (cstr/split v #",")
                                                     (map cstr/trim)
                                                     (filter seq)
                                                     (into []))]
                                        (emit-seo {:keywords kws}))))]

      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable true
                        :collapsed (not open?)
                        :on-collapsed toggle
                        :title (tr "workspace.options.seo.section")}
         [:& lucide-globe]]]

       (when open?
         [:div {:class (stl/css :element-set-content)}
          [:& seo-field* {:label (tr "workspace.options.seo.title")
                          :value (:title seo)
                          :placeholder (tr "workspace.options.seo.title")
                          :on-change on-title}]
          [:& seo-field* {:label (tr "workspace.options.seo.description")
                          :value (:description seo)
                          :placeholder (tr "workspace.options.seo.description")
                          :on-change on-description}]
          [:& seo-field* {:label (tr "workspace.options.seo.og-image")
                          :value (:og-image seo)
                          :placeholder "https://..."
                          :on-change on-og-image}]
          [:& seo-field* {:label (tr "workspace.options.seo.keywords")
                          :value (some-> (:keywords seo) (cstr/join ", "))
                          :placeholder (tr "workspace.options.seo.keywords")
                          :on-change on-keywords}]
          [:div {:class (stl/css :seo-hint)}
           (tr "workspace.options.seo.hint")]])])))