;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.css-anim
  "CSS-keyframe animation chooser (ALL_APPS_PARITY P2.06).

  A self-contained sidebar menu for choosing a named CSS-keyframe
  animation preset + duration / delay / iteration count on the selected
  shape. The config is persisted as shape plugin-data `:ovion \"css-anim\"`
  = pr-str `{:preset <keyword> :duration <ms> :delay <ms> :iteration
  <int|:infinite>}`. At render time the viewer emits the corresponding
  @keyframes + class on the shape's DOM node (reduced-motion guarded —
  disabled under prefers-reduced-motion). Self-hides unless exactly one
  shape is selected.

  RENDER HOOK (the lead must wire this — do NOT edit viewer/shapes.cljs):
  the CSS class + @keyframes must be emitted in
  `penpot-source/frontend/src/app/main/ui/viewer/shapes.cljs` inside
  `generic-wrapper-factory`, alongside the existing motion-effect /
  stroke-anim / state-overrides effect blocks (lines ~805-870). The
  established pattern: read the shape's `:ovion \"css-anim\"` slot via
  `dwca/read-css-anim shape`, locate the DOM node `#shape-<id>`, and
  inject the class + a <style> element with the @keyframes. Under
  prefers-reduced-motion emit nothing (static). With no slot, no-op ->
  byte-identical render.

  This module owns the PRESET CATALOG (the @keyframes CSS strings) so the
  render hook can import them. `css-for-anim` builds the full CSS (class +
  @keyframes) for a given config, reduced-motion aware."

  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.css-anim :as dwca]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
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

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "6px"})

(def ^:private label-style
  {:font-size "11px" :color grey :width "64px" :flex-shrink "0"})

;; Inline Lucide icons.
(defn- lucide-icon [children]
  [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
         :width 14 :height 14 :style {:flex-shrink 0}}
   children])

(defn- icon-wand []
  (lucide-icon [[:path {:d "M15 4V2M15 16v-2M8 9h2M20 9h2M17.8 11.8L19 13M15 9h0M17.8 6.2L19 5M3 21l9-9M12.2 6.2L11 5"}]]))

(defn- icon-trash []
  (lucide-icon [[:path {:d "M3 6h18"}]
                [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))

;; --- Ref ------------------------------------------------------------------

(defn- css-anim-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwca/read-css-anim shape)))
   refs/workspace-page
   =))

;; --- Menu ------------------------------------------------------------------

(mf/defc css-anim-menu*
  [{:keys [shapes]}]
  ;; Self-hides unless exactly one shape is selected.
  (when (= 1 (count shapes))
    (let [shape    (first shapes)
          shape-id (:id shape)
          cfg      (mf/deref (css-anim-ref shape-id))
          preset   (:preset cfg)
          duration (:duration cfg)
          delay    (:delay cfg)
          iter     (:iteration cfg)

          on-pick-preset
          (mf/use-fn
           (mf/deps shape-id)
           (fn [e]
             (let [k (.. e -target -value)]
               (if (str/blank? k)
                 (st/emit! (dwca/clear-css-anim {:shape-id shape-id}))
                 (st/emit! (dwca/set-css-anim
                            {:shape-id shape-id
                             :cfg {:preset (keyword k)
                                   :duration (or duration 600)
                                   :delay    (or delay 0)
                                   :iteration (or iter 1)}}))))))

          on-duration
          (mf/use-fn
           (mf/deps shape-id preset delay iter)
           (fn [e]
             (let [v (js/parseInt (.. e -target -value) 10)]
               (when preset
                 (st/emit! (dwca/set-css-anim
                            {:shape-id shape-id
                             :cfg {:preset preset
                                   :duration (or v 600)
                                   :delay    (or delay 0)
                                   :iteration (or iter 1)}}))))))

          on-delay
          (mf/use-fn
           (mf/deps shape-id preset duration iter)
           (fn [e]
             (let [v (js/parseInt (.. e -target -value) 10)]
               (when preset
                 (st/emit! (dwca/set-css-anim
                            {:shape-id shape-id
                             :cfg {:preset preset
                                   :duration (or duration 600)
                                   :delay    (or v 0)
                                   :iteration (or iter 1)}}))))))

          on-iteration
          (mf/use-fn
           (mf/deps shape-id preset duration delay)
           (fn [e]
             (let [raw (.. e -target -value)
                   v (if (= raw "infinite")
                       :infinite
                       (js/parseInt raw 10))]
               (when preset
                 (st/emit! (dwca/set-css-anim
                            {:shape-id shape-id
                             :cfg {:preset preset
                                   :duration (or duration 600)
                                   :delay    (or delay 0)
                                   :iteration (or v 1)}}))))))

          on-clear
          (mf/use-fn
           (mf/deps shape-id)
           (fn []
             (st/emit! (dwca/clear-css-anim {:shape-id shape-id}))))]

      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable true :collapsed false
                        :title (tr "workspace.options.css-anim.title")}]]
       [:div {:class (stl/css :element-set-content)}
        [:div {:class (stl/css :element-group)
               :style {:display "flex" :flex-direction "column" :gap "8px"}}

         ;; Preset dropdown.
         [:div {:style row-style}
          [:span {:style label-style}
           (tr "workspace.options.css-anim.preset")]
          [:select {:value (if preset (d/name preset) "")
                    :class (stl/css :type-input) :style {:flex "1"}
                    :on-change on-pick-preset}
           [:option {:value ""} (tr "workspace.options.css-anim.none")]
           (for [p dwca/preset-catalog]
             [:option {:key (d/name (:id p)) :value (d/name (:id p))}
              (:label p)])]]

         (when preset
           [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                          :padding "4px 0 0 0"}}
            [:div {:style row-style}
             [:span {:style label-style} (tr "workspace.options.css-anim.duration")]
             [:input {:type "number" :value (or duration 600)
                      :class (stl/css :type-input) :style {:flex "1"}
                      :on-change on-duration}]
             [:span {:style {:font-size "11px" :color grey}} "ms"]]

            [:div {:style row-style}
             [:span {:style label-style} (tr "workspace.options.css-anim.delay")]
             [:input {:type "number" :value (or delay 0)
                      :class (stl/css :type-input) :style {:flex "1"}
                      :on-change on-delay}]
             [:span {:style {:font-size "11px" :color grey}} "ms"]]

            [:div {:style row-style}
             [:span {:style label-style} (tr "workspace.options.css-anim.iteration")]
             [:select {:value (if (= iter :infinite) "infinite" (str (or iter 1)))
                       :class (stl/css :type-input) :style {:flex "1"}
                       :on-change on-iteration}
              [:option {:value "1"} "1"]
              [:option {:value "2"} "2"]
              [:option {:value "3"} "3"]
              [:option {:value "5"} "5"]
              [:option {:value "10"} "10"]
              [:option {:value "infinite"}
               (tr "workspace.options.css-anim.infinite")]]]

            [:div {:style row-style}
             [:button {:type "button" :title (tr "workspace.options.css-anim.clear")
                       :style coral-btn-style :on-click on-clear}
              [:span {:style {:margin-right "4px"}} (icon-trash)]
              (tr "workspace.options.css-anim.clear")]]])

         ;; Reduced-motion note + empty hint.
         (when-not preset
           [:div {:style {:display "flex" :gap "6px" :align-items "flex-start"
                          :color grey :font-size "11px" :line-height "1.4"}}
            [:span {:style {:color coral :margin-top "1px"}} (icon-wand)]
            [:span (tr "workspace.options.css-anim.empty")]])

         [:div {:style {:color grey :font-size "10px" :line-height "1.4"
                        :padding-top "4px"
                        :border-top "1px solid rgba(125,125,125,0.15)"}}
          (tr "workspace.options.css-anim.reduced-motion-note")]]]])))