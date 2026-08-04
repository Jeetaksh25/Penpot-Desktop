;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.effects
  "Effects authoring menu (P1.16): Appear / Loop / Drag as first-class
  design-time effect primitives, beyond hover/press. The user picks an
  effect type + config on a selected shape; the config is persisted as
  shape plugin-data (see data/workspace/motion_effects.cljs) and
  rendered via GSAP/AnimeJS in the viewer (see ai_motion.cljs + the
  viewer runtime in viewer/shapes.cljs). Reduced-motion guarded."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.motion-effects :as dwme]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme).
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

;; Inline Lucide icons.
(defn- lucide-icon [children]
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :width 16 :height 16 :style {:flex-shrink 0}}
   children])

(defn- icon-sparkles []
  (lucide-icon [[:path {:d "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3z"}]
                [:path {:d "M5 19l.7 1.9L7.6 21.6 5.7 22.5 5 24.4 4.3 22.5 2.4 21.6 4.3 20.7 5 19z"}]]))
(defn- icon-trash []
  (lucide-icon [[:path {:d "M3 6h18"}]
                [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))

(def ^:private effect-type-options
  [{:value :appear :label-key "workspace.options.effects.type.appear"}
   {:value :loop   :label-key "workspace.options.effects.type.loop"}
   {:value :drag   :label-key "workspace.options.effects.type.drag"}])

(def ^:private appear-directions
  [{:value :left :label "←"}
   {:value :right :label "→"}
   {:value :up :label "↑"}
   {:value :down :label "↓"}
   {:value :fade :label "Fade"}
   {:value :scale :label "Scale"}])

(def ^:private loop-kinds
  [{:value :rotate :label-key "workspace.options.effects.loop.rotate"}
   {:value :pulse  :label-key "workspace.options.effects.loop.pulse"}
   {:value :slide  :label-key "workspace.options.effects.loop.slide"}])

(def ^:private drag-axes
  [{:value :x :label "X"}
   {:value :y :label "Y"}
   {:value :both :label "XY"}])

(defn- effect-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwme/read-motion-effect shape)))
   refs/workspace-page
   =))

;; Small presentational row helpers — keep the main component's hiccup shallow.

(mf/defc number-row*
  [{:keys [label value on-change suffix]}]
  [:div {:style row-style}
   [:span {:style label-style} label]
   [:input {:type "number" :value value
            :class (stl/css :type-input) :style {:flex "1"}
            :on-change on-change}]
   (when suffix [:span {:style {:font-size "11px" :color grey}} suffix])])

(mf/defc select-row*
  [{:keys [label value options on-change]}]
  [:div {:style row-style}
   [:span {:style label-style} label]
   [:select {:value value :class (stl/css :type-input) :style {:flex "1"}
             :on-change on-change}
    (for [opt options]
      [:option {:key (d/name (:value opt)) :value (d/name (:value opt))}
       (:label opt)])]])

(mf/defc effects-menu*
  [{:keys [shapes]}]
  (let [shape    (first shapes)
        shape-id (:id shape)
        effect   (mf/deref (effect-ref shape-id))
        etype    (:type effect)
        cfg      (:config effect)

        ;; Helper: build an on-change that merges `f` into cfg and re-emits.
        update-cfg
        (mf/use-fn
         (mf/deps shape-id etype cfg)
         (fn [f]
           (fn [e]
             (let [new-cfg (f cfg e)]
               (st/emit! (dwme/set-motion-effect
                          {:shape-id shape-id
                           :effect {:type etype :config new-cfg}})))))

        on-pick-type
        (mf/use-fn
         (mf/deps shape-id)
         (fn [t]
           (let [defaults (case t
                            :appear {:direction :fade :duration 500 :delay 0}
                            :loop   {:kind :pulse :duration 1500}
                            :drag   {:axis :both :constraint :none})]
             (st/emit! (dwme/set-motion-effect
                        {:shape-id shape-id :effect {:type t :config defaults}})))))

        on-clear
        (mf/use-fn (mf/deps shape-id)
                   #(st/emit! (dwme/clear-motion-effect {:shape-id shape-id}))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable true :collapsed false
                      :title (tr "workspace.options.effects.title")}]]
     [:div {:class (stl/css :element-set-content)}
      [:div {:class (stl/css :element-group)
             :style {:display "flex" :flex-direction "column" :gap "8px"}}

       ;; Type picker (3 coral buttons) + clear.
       [:div {:style (merge row-style {:flex-wrap "wrap"})}
        (for [opt effect-type-options]
          [:button {:key (:value opt)
                    :type "button"
                    :style (merge coral-btn-style
                                 (when (= (:value opt) etype)
                                   {:background coral :color "#fff" :border-color coral}))
                    :on-click #(on-pick-type (:value opt))}
           (tr (:label-key opt))])
        (when etype
          [:button {:type "button" :style coral-btn-style
                    :title (tr "workspace.options.effects.clear")
                    :on-click on-clear}
           (icon-trash)])]

       ;; Config per type.
       (when etype
         [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                        :padding "4px 0 0 0"}}

          (when (= etype :appear)
            [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
             [:> select-row*
              {:label (tr "workspace.options.effects.appear.direction")
               :value (d/name (or (:direction cfg) :fade))
               :options appear-directions
               :on-change (update-cfg
                            (fn [c e]
                              (assoc c :direction (keyword (.. e -target -value)))))}]
             [:> number-row*
              {:label (tr "workspace.options.effects.duration")
               :value (or (:duration cfg) 500)
               :suffix "ms"
               :on-change (update-cfg
                           (fn [c e]
                             (let [v (js/parseInt (.. e -target -value) 10)]
                               (assoc c :duration (or v 500)))))}]
             [:> number-row*
              {:label (tr "workspace.options.effects.delay")
               :value (or (:delay cfg) 0)
               :suffix "ms"
               :on-change (update-cfg
                           (fn [c e]
                             (let [v (js/parseInt (.. e -target -value) 10)]
                               (assoc c :delay (or v 0)))))}]])

          (when (= etype :loop)
            [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
             [:> select-row*
              {:label (tr "workspace.options.effects.loop.kind")
               :value (d/name (or (:kind cfg) :pulse))
               :options (mapv (fn [k] {:value (:value k)
                                       :label (tr (:label-key k))})
                              loop-kinds)
               :on-change (update-cfg
                           (fn [c e]
                             (assoc c :kind (keyword (.. e -target -value)))))}]
             [:> number-row*
              {:label (tr "workspace.options.effects.duration")
               :value (or (:duration cfg) 1500)
               :suffix "ms"
               :on-change (update-cfg
                           (fn [c e]
                             (let [v (js/parseInt (.. e -target -value) 10)]
                               (assoc c :duration (or v 1500)))))}]])

          (when (= etype :drag)
            [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
             [:> select-row*
              {:label (tr "workspace.options.effects.drag.axis")
               :value (d/name (or (:axis cfg) :both))
               :options drag-axes
               :on-change (update-cfg
                           (fn [c e]
                             (assoc c :axis (keyword (.. e -target -value)))))}]
             [:> select-row*
              {:label (tr "workspace.options.effects.drag.constraint")
               :value (d/name (or (:constraint cfg) :none))
               :options [{:value :none :label (tr "workspace.options.effects.drag.constraint-none")}
                         {:value :lock :label (tr "workspace.options.effects.drag.constraint-lock")}]
               :on-change (update-cfg
                           (fn [c e]
                             (assoc c :constraint (keyword (.. e -target -value)))))}]])])

       ;; Empty hint.
       (when-not etype
         [:div {:style {:display "flex" :gap "6px" :align-items "flex-start"
                        :color grey :font-size "11px" :line-height "1.4"}}
          [:span {:style {:color coral :margin-top "1px"}} (icon-sparkles)]
          [:span (tr "workspace.options.effects.empty")]])]]]))