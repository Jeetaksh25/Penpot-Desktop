;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.scroll-motion
  "Scroll-triggered motion authoring menu (ALL_APPS_PARITY P1.34).

  Two slots, both rendered as shape plugin-data under namespace `:ovion`
  (see data/workspace/scroll_motion.cljs) and run by the viewer
  (viewer/shapes.cljs generic-wrapper):

  - `path-draw` on a PATH shape: `{:duration ms :direction :forward/:reverse}`
    → stroke draws from hidden → fully drawn as the path scrolls into view.
  - `scroll-video` on a shape carrying the video slot: `{:trigger
    :scrub/:in-view :start :end}` → the <video> scrubs by scroll position
    or plays/pauses on enter/leave.

  The menu self-hides unless the selected shape is a path (path-draw) or
  carries the video slot (scroll-video). Reduced-motion is handled at
  render time (viewer), not here."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.scroll-motion :as dwsm]
   [app.main.data.workspace.video :as dwv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme).
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

(def ^:private path-directions
  [{:value :forward :label "Forward"}
   {:value :reverse :label "Reverse"}])

(def ^:private video-triggers
  [{:value :in-view :label "In view"}
   {:value :scrub :label "Scrub"}])

(defn- path-draw-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwsm/read-path-draw shape)))
   refs/workspace-page
   =))

(defn- scroll-video-ref
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwsm/read-scroll-video shape)))
   refs/workspace-page
   =))

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

;; Lucide "spline" icon (lucide.dev, MIT) — 24x24, stroke currentColor, sw 2.
(defn- icon-spline
  []
  [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
         :stroke coral :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round"
         :style {:flex-shrink "0"}}
   [:circle {:cx 19 :cy 5 :r 2}]
   [:circle {:cx 5 :cy 19 :r 2}]
   [:path {:d "M5 19A9 9 0 0 1 19 5"}]])

(mf/defc scroll-motion-menu*
  "Inspector menu for the path-draw + scroll-video slots. `shapes` is the
  vector of currently selected shapes (the first is authored). Self-hides
  (returns nil) unless the first shape is a path (path-draw) or carries the
  video slot (scroll-video)."
  [{:keys [shapes]}]
  (let [shape     (first shapes)
        shape-id  (:id shape)
        stype     (:type shape)
        video-cfg (dwv/read-video-slot shape)
        path-cfg  (mf/deref (path-draw-ref shape-id))
        sv-cfg    (mf/deref (scroll-video-ref shape-id))
        path?     (= :path stype)
        has-video (some? video-cfg)
        show?     (or path? has-video)]

    (when show?
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable true :collapsed false
                        :title (tr "workspace.options.scroll-motion.title")}
         [:span {:style {:display "inline-flex" :align-items "center"
                         :margin-left "6px"}}
          (icon-spline)]]]

       [:div {:class (stl/css :element-set-content)}
        [:div {:class (stl/css :element-group)
               :style {:display "flex" :flex-direction "column" :gap "8px"}}

         ;; ── Path-draw section (path shapes only) ─────────────────────────
         (when path?
           [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
            [:div {:style (merge row-style {:font-size "11px" :color grey})}
             (tr "workspace.options.scroll-motion.path-draw.title")]

            (if (nil? path-cfg)
              [:div {:style row-style}
               [:button {:type "button" :style coral-btn-style
                         :on-click #(st/emit!
                                     (dwsm/set-path-draw
                                      {:shape-id shape-id
                                       :config {:duration 1200 :direction :forward}}))}
                (tr "workspace.options.scroll-motion.path-draw.add")]]
              [:*
               [:> select-row*
                {:label (tr "workspace.options.scroll-motion.path-draw.direction")
                 :value (d/name (or (:direction path-cfg) :forward))
                 :options path-directions
                 :on-change (fn [e]
                              (st/emit!
                               (dwsm/set-path-draw
                                {:shape-id shape-id
                                 :config (assoc path-cfg :direction
                                                (keyword (.. e -target -value)))})))}]
               [:> number-row*
                {:label (tr "workspace.options.scroll-motion.path-draw.duration")
                 :value (or (:duration path-cfg) 1200)
                 :suffix "ms"
                 :on-change (fn [e]
                              (let [v (js/parseInt (.. e -target -value) 10)]
                                (st/emit!
                                 (dwsm/set-path-draw
                                  {:shape-id shape-id
                                   :config (assoc path-cfg :duration (or v 1200))}))))}]
               [:div {:style (merge row-style {:justify-content "flex-end"})}
                [:button {:type "button" :style coral-btn-style
                          :on-click #(st/emit!
                                      (dwsm/clear-path-draw {:shape-id shape-id}))}
                 (tr "workspace.options.scroll-motion.clear")]]])])

         ;; ── Scroll-video section (shapes with the video slot only) ────────
         (when has-video
           [:div {:style {:display "flex" :flex-direction "column" :gap "6px"}}
            [:div {:style (merge row-style {:font-size "11px" :color grey})}
             (tr "workspace.options.scroll-motion.scroll-video.title")]

            (if (nil? sv-cfg)
              [:div {:style row-style}
               [:button {:type "button" :style coral-btn-style
                         :on-click #(st/emit!
                                     (dwsm/set-scroll-video
                                      {:shape-id shape-id
                                       :config {:trigger :in-view}}))}
                (tr "workspace.options.scroll-motion.scroll-video.add")]]
              [:*
               [:> select-row*
                {:label (tr "workspace.options.scroll-motion.scroll-video.trigger")
                 :value (d/name (or (:trigger sv-cfg) :in-view))
                 :options video-triggers
                 :on-change (fn [e]
                              (st/emit!
                               (dwsm/set-scroll-video
                                {:shape-id shape-id
                                 :config (assoc sv-cfg :trigger
                                                (keyword (.. e -target -value)))})))}]
               [:div {:style (merge row-style {:justify-content "flex-end"})}
                [:button {:type "button" :style coral-btn-style
                          :on-click #(st/emit!
                                      (dwsm/clear-scroll-video {:shape-id shape-id}))}
                 (tr "workspace.options.scroll-motion.clear")]]])])]]])))