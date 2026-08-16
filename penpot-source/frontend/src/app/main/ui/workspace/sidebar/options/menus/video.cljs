;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.video
  "Video/GIF playback inspector menu (ALL_APPS_PARITY P2.39).

  Authors the `:ovion \"video\"` plugin-data slot on a selected rect (the
  carrier shape). Self-hides unless the selected shape is a rect OR already
  carries the video slot (so any shape that has it can be edited/cleared).
  When the slot is absent the menu shows an 'Add video' button; once added
  it shows URL / poster / loop / muted / controls / autoplay controls plus a
  remove button. The config is persisted via data.workspace.video and
  rendered via ui.shapes.video (foreignObject + <video>/<img>). Reduced-
  motion is handled at render time, not here."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.video :as dwv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme) — matches the effects menu.
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "8px" :width "100%"})

(def ^:private label-style
  {:font-size "11px" :color grey :width "64px" :flex-shrink "0"})

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

(defn- video-ref
  "Derived ref that reads the video slot for `shape-id` from the workspace
  page objects, so the menu re-renders when the slot changes."
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwv/read-video-slot shape)))
   refs/workspace-page
   =))

;; Small presentational row helpers — keep the main component's hiccup shallow.

(mf/defc text-row*
  [{:keys [label value placeholder on-change]}]
  [:div {:style row-style}
   [:span {:style label-style} label]
   [:input {:type "text"
            :value (or value "")
            :placeholder placeholder
            :class (stl/css :type-input)
            :style {:flex "1"}
            :on-change on-change}]])

(mf/defc check-row*
  [{:keys [label checked on-change]}]
  [:div {:style row-style}
   [:span {:style label-style} label]
   [:input {:type "checkbox"
            :checked checked
            :style {:accent-color coral :width "16px" :height "16px"
                    :cursor "pointer"}
            :on-change on-change}]])

;; Lucide "film" icon (lucide.dev, MIT) — 24x24, stroke currentColor, sw 2.
(defn- icon-film
  []
  (mf/html [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
                  :stroke coral :stroke-width 2 :stroke-linecap "round"
                  :stroke-linejoin "round"
                  :style {:flex-shrink "0"}}
            [:rect {:x 2 :y 2 :width 20 :height 20 :rx 2.18 :ry 2.18}]
            [:line {:x1 7 :y1 2 :x2 7 :y2 22}]
            [:line {:x1 17 :y1 2 :x2 17 :y2 22}]
            [:line {:x1 2 :y1 12 :x2 22 :y2 12}]
            [:line {:x1 2 :y1 7 :x2 7 :y2 7}]
            [:line {:x1 2 :y1 17 :x2 7 :y2 17}]
            [:line {:x1 17 :y1 17 :x2 22 :y2 17}]
            [:line {:x1 17 :y1 7 :x2 22 :y2 7}]]))

;; Lucide "trash-2" icon (lucide.dev, MIT) — 24x24, stroke currentColor, sw 2.
(defn- icon-trash
  []
  (mf/html [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
                  :stroke coral :stroke-width 2 :stroke-linecap "round"
                  :stroke-linejoin "round"
                  :style {:flex-shrink "0"}}
            [:path {:d "M3 6h18"}]
            [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
            [:line {:x1 10 :y1 11 :x2 10 :y2 17}]
            [:line {:x1 14 :y1 11 :x2 14 :y2 17}]]))

(mf/defc video-menu*
  "Inspector menu for the video/GIF playback slot. `shapes` is the vector of
  currently selected shapes. Self-hides (returns nil) unless exactly one shape
  is selected AND it is a rect OR already carries the slot (mirrors the
  code-component-menu* single-selection guard — all emits author only the one
  selected shape)."
  [{:keys [shapes]}]
  (let [shape    (first shapes)
        shape-id (:id shape)
        stype    (:type shape)
        cfg      (mf/deref (video-ref shape-id))
        has-slot (some? cfg)
        ;; Show for a single selected rect (the carrier) or a single shape
        ;; that already has the slot (so it can be edited/cleared on whatever
        ;; carries it). The single-selection guard matches the sibling
        ;; code-component-menu*: all emits author only the first shape, so a
        ;; multi-select would silently change just one of the selected rects.
        show?    (and (= 1 (count shapes))
                      (or (= :rect stype) has-slot))]
    (when show?
      (let [;; Helper: merge `f` into cfg and re-emit. When the slot is absent
            ;; we seed an empty config map so the first edit adds it.
            update-cfg
            (mf/use-fn
             (mf/deps shape-id cfg)
             (fn [f]
               (fn [e]
                 (let [base   (or cfg {})
                       new-cfg (f base e)]
                   (st/emit! (dwv/set-video-config
                              {:shape-id shape-id :config new-cfg}))))))

            on-add
            (mf/use-fn
             (mf/deps shape-id)
             (fn []
               (st/emit! (dwv/add-video-config
                          {:shape-id shape-id
                           :config {:src "" :poster nil
                                    :loop? false :muted? true
                                    :controls? true :autoplay? false}}))))

            on-clear
            (mf/use-fn (mf/deps shape-id)
                       #(st/emit! (dwv/clear-video-config {:shape-id shape-id})))]

        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-title)}
          [:> title-bar* {:collapsable true :collapsed false
                          :title (tr "workspace.options.video.title")}
           [:span {:style {:display "inline-flex" :align-items "center"
                           :margin-left "6px"}}
            (icon-film)]]]

         [:div {:class (stl/css :element-set-content)}
          [:div {:class (stl/css :element-group)
                 :style {:display "flex" :flex-direction "column" :gap "8px"}}

           (if-not has-slot
             ;; No slot yet: a single 'Add video' coral button.
             [:div {:style row-style}
              [:button {:type "button" :style coral-btn-style :on-click on-add}
               (tr "workspace.options.video.add")]]

             ;; Slot present: full controls.
             [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
              [:> text-row*
               {:label (tr "workspace.options.video.src")
                :value (:src cfg)
                :placeholder "https://…/clip.mp4"
                :on-change (update-cfg
                            (fn [c e]
                              (assoc c :src (.. e -target -value))))}]
              [:> text-row*
               {:label (tr "workspace.options.video.poster")
                :value (:poster cfg)
                :placeholder "https://…/poster.jpg"
                :on-change (update-cfg
                            (fn [c e]
                              (assoc c :poster (let [v (.. e -target -value)]
                                                 (if (empty? v) nil v)))))}]
              [:> check-row*
               {:label (tr "workspace.options.video.loop")
                :checked (boolean (:loop? cfg))
                :on-change (update-cfg
                            (fn [c e]
                              (assoc c :loop? (.. e -target -checked))))}]
              [:> check-row*
               {:label (tr "workspace.options.video.muted")
                :checked (boolean (:muted? cfg))
                :on-change (update-cfg
                            (fn [c e]
                              (assoc c :muted? (.. e -target -checked))))}]
              [:> check-row*
               {:label (tr "workspace.options.video.controls")
                :checked (boolean (:controls? cfg))
                :on-change (update-cfg
                            (fn [c e]
                              (assoc c :controls? (.. e -target -checked))))}]
              [:> check-row*
               {:label (tr "workspace.options.video.autoplay")
                :checked (boolean (:autoplay? cfg))
                :on-change (update-cfg
                            (fn [c e]
                              (assoc c :autoplay? (.. e -target -checked))))}]
              [:div {:style (merge row-style {:justify-content "flex-end"
                                              :padding-top "4px"})}
               [:button {:type "button" :style coral-btn-style
                         :title (tr "workspace.options.video.clear")
                         :on-click on-clear}
                (icon-trash)
                [:span {:style {:margin-left "4px"}}
                 (tr "workspace.options.video.clear")]]]])]]]))))
