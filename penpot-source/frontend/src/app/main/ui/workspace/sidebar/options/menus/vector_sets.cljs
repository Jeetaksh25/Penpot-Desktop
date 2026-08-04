;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.vector-sets
  "P2.38 Vector Sets chooser + stroke-flow animation toggle.

  Mounted alongside the stroke menu (see options.cljs mount point reported
  to the lead). Self-hides unless exactly one shape is selected AND that
  shape has at least one visible stroke — so multi-select / no-stroke /
  nothing-selected render no new DOM (byte-identical sidebar).

  The chooser is a row of coral buttons (one per catalog set); the active
  set is highlighted (filled coral). Below it, a stroke-flow toggle turns
  the marching-ants animation on/off and exposes speed + direction selects.
  Reduced-motion: when `prefers-reduced-motion` is on, the flow renders as a
  static dash — a coral note is shown so the author knows why the preview is
  still (the animation still runs in the viewer only under reduced-motion).

  All persistence goes through data/workspace/vector_sets.cljs events
  (apply-vector-set / clear-vector-set / set-stroke-anim / clear-stroke-anim),
  which store plugin-data on `:ovion` and commit one undo each."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.vector-sets :as dwvs]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.workspace.ai-motion :as am]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme — matches effects.cljs).
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private coral-btn-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :height "26px"
   :padding "0 10px"
   :border-radius "6px"
   :border "1px solid rgba(242,139,130,0.4)"
   :background "rgba(242,139,130,0.08)"
   :color coral
   :cursor "pointer"
   :font-size "11px"
   :font-weight "500"})

(def ^:private label-style
  {:font-size "11px" :color grey :width "64px" :flex-shrink "0"})

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "6px"})

;; Inline Lucide icons (stroke-width 2, currentColor — matches effects.cljs).
(defn- lucide-icon [children]
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :width 16 :height 16 :style {:flex-shrink 0}}
   children])

(defn- icon-wand []
  ;; "sparkles / wand" — a curated-preset cue.
  (lucide-icon [[:path {:d "M15 4V2"}]
                [:path {:d "M15 16v-2"}]
                [:path {:d "M8 9h2"}]
                [:path {:d "M20 9h2"}]
                [:path {:d "M17.8 11.8L19 13"}]
                [:path {:d "M15 9h.01"}]
                [:path {:d "M17.8 6.2L19 5"}]
                [:path {:d "m3 21 9-9"}]
                [:path {:d "M12.2 6.2L11 5"}]]))

(defn- icon-flow []
  ;; "spline / flow" — a dashed path cue for stroke-flow.
  (lucide-icon [[:path {:d "M2 12s3-7 10-7 10 7 10 7"}]
                [:path {:d "M2 18h20" :stroke-dasharray "4 3"}]]))

(defn- icon-trash []
  (lucide-icon [[:path {:d "M3 6h18"}]
                [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))

;; --- Derived refs (re-render on plugin-data change) ------------------------

(defn- vector-set-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwvs/read-vector-set shape)))
   refs/workspace-page
   =))

(defn- stroke-anim-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwvs/read-stroke-anim shape)))
   refs/workspace-page
   =))

;; --- Small presentational rows (mirror effects.cljs idioms) ----------------

(mf/defc select-row*
  [{:keys [label value options on-change]}]
  [:div {:style row-style}
   [:span {:style label-style} label]
   [:select {:value value :class (stl/css :type-input) :style {:flex "1"}
             :on-change on-change}
    (for [opt options]
      [:option {:key (d/name (:value opt)) :value (d/name (:value opt))}
       (:label opt)])]])

(mf/defc number-row*
  [{:keys [label value on-change suffix]}]
  [:div {:style row-style}
   [:span {:style label-style} label]
   [:input {:type "number" :value value
            :class (stl/css :type-input) :style {:flex "1"}
            :on-change on-change}]
   (when suffix [:span {:style {:font-size "11px" :color grey}} suffix])])

;; --- Main menu -------------------------------------------------------------

(mf/defc vector-sets-menu*
  [{:keys [shapes]}]
  (let [shape    (first shapes)
        shape-id (:id shape)
        strokes  (get shape :strokes)
        has-strokes? (and (some? strokes)
                          (pos? (count (remove :hidden strokes))))
        single?  (= 1 (count shapes))

        active-set (mf/deref (vector-set-ref shape-id))
        anim       (mf/deref (stroke-anim-ref shape-id))
        reduced?   (am/reduced-motion?)

        on-pick-set
        (mf/use-fn
         (mf/deps shape-id)
         (fn [set-id]
           (if (= active-set set-id)
             (st/emit! (dwvs/clear-vector-set {:shape-id shape-id}))
             (st/emit! (dwvs/apply-vector-set {:shape-id shape-id
                                               :set-id set-id})))))

        on-toggle-anim
        (mf/use-fn
         (mf/deps shape-id anim)
         (fn [_]
           (if (some? anim)
             (st/emit! (dwvs/clear-stroke-anim {:shape-id shape-id}))
             (st/emit! (dwvs/set-stroke-anim
                        {:shape-id shape-id
                         :config {:speed 2000 :direction :forward}})))))

        on-speed-change
        (mf/use-fn
         (mf/deps shape-id anim)
         (fn [e]
           (let [v (js/parseInt (.. e -target -value) 10)]
             (st/emit! (dwvs/set-stroke-anim
                        {:shape-id shape-id
                         :config (assoc anim :speed (or v 2000))})))) )

        on-direction-change
        (mf/use-fn
         (mf/deps shape-id anim)
         (fn [e]
           (let [v (keyword (.. e -target -value))]
             (st/emit! (dwvs/set-stroke-anim
                        {:shape-id shape-id
                         :config (assoc anim :direction v)}))))) ]

    ;; Self-hide: only render for a single selected shape with >=1 visible
    ;; stroke. Multi-select / no-stroke / nothing-selected → no DOM.
    (when (and single? has-strokes?)
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable true :collapsed false
                        :title (tr "workspace.options.vector-sets.title")}]]
       [:div {:class (stl/css :element-set-content)}
        [:div {:class (stl/css :element-group)
               :style {:display "flex" :flex-direction "column" :gap "8px"}}

         ;; Vector Sets chooser — coral buttons, active = filled coral.
         [:div {:style (merge row-style {:flex-wrap "wrap" :align-items "center"})}
          [:span {:style (merge label-style {:width "auto" :margin-right "2px"})}
           (icon-wand)]
          (for [set-spec dwvs/vector-set-catalog]
            (let [sid (:id set-spec)]
              [:button {:key (d/name sid)
                        :type "button"
                        :style (merge coral-btn-style
                                     (when (= active-set sid)
                                       {:background coral :color "#fff"
                                        :border-color coral}))
                        :on-click #(on-pick-set sid)}
               (tr (:label-key set-spec))]))]

         ;; Stroke-flow toggle + config.
         [:div {:style (merge row-style {:flex-wrap "wrap" :align-items "center"})}
          [:span {:style (merge label-style
                                {:width "auto" :margin-right "2px"
                                 :color (when (some? anim) coral)})}
           (icon-flow)]
          [:button {:type "button"
                    :style (merge coral-btn-style
                                 (when (some? anim)
                                   {:background coral :color "#fff"
                                    :border-color coral}))
                    :on-click on-toggle-anim}
           (tr "workspace.options.vector-sets.flow.toggle")]
          (when (some? anim)
            [:button {:type "button" :style coral-btn-style
                      :title (tr "workspace.options.vector-sets.flow.clear")
                      :on-click on-toggle-anim}
             (icon-trash)])]

         ;; Speed + direction (only when animation is on).
         (when (some? anim)
           [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                          :padding "4px 0 0 0"}}
            [:> number-row*
             {:label (tr "workspace.options.vector-sets.flow.speed")
              :value (or (:speed anim) 2000)
              :suffix "ms"
              :on-change on-speed-change}]
            [:> select-row*
             {:label (tr "workspace.options.vector-sets.flow.direction")
              :value (d/name (or (:direction anim) :forward))
              :options [{:value :forward :label (tr "workspace.options.vector-sets.flow.dir-forward")}
                        {:value :reverse :label (tr "workspace.options.vector-sets.flow.dir-reverse")}]
              :on-change on-direction-change}]])

         ;; Reduced-motion note (non-negotiable a11y disclosure).
         (when (and (some? anim) reduced?)
           [:div {:style {:display "flex" :gap "6px" :align-items "flex-start"
                          :color grey :font-size "11px" :line-height "1.4"}}
            [:span {:style {:color coral :margin-top "1px"}} (icon-flow)]
            [:span (tr "workspace.options.vector-sets.flow.reduced")]])

         ;; Empty hint (no set + no anim).
         (when (and (nil? active-set) (nil? anim))
           [:div {:style {:display "flex" :gap "6px" :align-items "flex-start"
                          :color grey :font-size "11px" :line-height "1.4"}}
            [:span {:style {:color coral :margin-top "1px"}} (icon-wand)]
            [:span (tr "workspace.options.vector-sets.empty")]])]]])))