;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.connectors
  "Connector authoring menu (P2.14): color / stroke width / dash style
  / arrow-end / orthogonal-elbow toggles for a selected connector path,
  plus a delete button. The menu self-hides unless the selected shape
  carries the `:ovion`/`\"connector\"` plugin-data slot (see
  data/workspace/connectors.cljs). Reduced-motion n/a (no motion)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.connectors :as dwconn]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme) — matches effects.cljs.
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

;; Inline Lucide icons (stroke-width 2, currentColor).
(defn- lucide-icon [children]
  (hic/el (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                       :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                       :width 16 :height 16 :style {:flex-shrink 0}}]
                children)))

(defn- icon-spline []
  (lucide-icon [[:path {:d "M9 17c3-3 3-7 0-10S6 1 3 4"}]
                [:circle {:cx "5" :cy "5" :r "1.5"}]
                [:circle {:cx "19" :cy "19" :r "1.5"}]
                [:path {:d "M9 7h6c3 0 4 1 4 4v6"}]]))

(defn- icon-trash []
  (lucide-icon [[:path {:d "M3 6h18"}]
                [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))

(def ^:private dash-options
  [{:value :solid :label-key "workspace.options.connectors.dash.solid"}
   {:value :dashed :label-key "workspace.options.connectors.dash.dashed"}
   {:value :dotted :label-key "workspace.options.connectors.dash.dotted"}])

(defn- connector-ref
  "Derived ref that reads the selected shape's connector slot, so the
  menu re-renders when the connector data changes."
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dwconn/read-connector shape)))
   refs/workspace-page
   =))

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

(mf/defc connectors-menu*
  "Authoring menu for a selected connector. Self-hides (renders nil)
  unless the selected shape is a connector (carries the plugin-data
  slot). When exactly one connector is selected, shows color / width /
  dash / arrow-end / orthogonal toggles + a delete button."
  [{:keys [shapes]}]
  (let [shape    (first shapes)
        shape-id (:id shape)
        conn     (mf/deref (connector-ref shape-id))]
    ;; Self-hide when not a connector (byte-identical-when-inactive:
        ;; non-connector selections render exactly as today — this menu
        ;; simply mounts to nil).
    (when (and (some? conn) (some? shape-id))
      (let [style    (:style conn)
            color    (or (:color style) "#7d7d7d")
            width    (or (:width style) 1)
            dash     (or (:dash style) :solid)
            arrow?   (boolean (:arrow-end? style))
            ortho?   (boolean (:orthogonal? style))

            on-style
            (mf/use-fn
             (mf/deps shape-id)
             (fn [style-update]
               (st/emit! (dwconn/set-connector-style
                          {:shape-id shape-id
                           :style-update style-update}))))

            on-color
            (mf/use-fn
             (mf/deps on-style)
             #(on-style {:color (.. % -target -value)}))

            on-width
            (mf/use-fn
             (mf/deps on-style)
             (fn [e]
               (let [v (js/parseFloat (.. e -target -value))]
                 (on-style {:width (if (js/isNaN v) 1 (max 0.5 v))}))))

            on-dash
            (mf/use-fn
             (mf/deps on-style)
             #(on-style {:dash (keyword (.. % -target -value))}))

            on-toggle-arrow
            (mf/use-fn
             (mf/deps on-style arrow?)
             #(on-style {:arrow-end? (not arrow?)}))

            on-toggle-ortho
            (mf/use-fn
             (mf/deps on-style ortho?)
             #(on-style {:orthogonal? (not ortho?)}))

            on-delete
            (mf/use-fn
             (mf/deps shape-id)
             #(st/emit! (dwconn/delete-connector {:shape-id shape-id})))]

        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-title)}
          [:> title-bar* {:collapsable true :collapsed false
                          :title (tr "workspace.options.connectors.title")}]]
         [:div {:class (stl/css :element-set-content)}
          [:div {:class (stl/css :element-group)
                 :style {:display "flex" :flex-direction "column" :gap "8px"}}

           [:div {:style row-style}
            [:span {:style label-style} (tr "workspace.options.connectors.color")]
            [:input {:type "color" :value color
                     :style {:flex "1" :height "26px" :border "1px solid #e0e0e0"
                             :border-radius "6px" :background "transparent"
                             :cursor "pointer"}
                     :on-change on-color}]]

           [:> number-row*
            {:label (tr "workspace.options.connectors.width")
             :value width
             :on-change on-width}]

           [:> select-row*
            {:label (tr "workspace.options.connectors.dash.label")
             :value (d/name dash)
             :options (mapv (fn [o] {:value (:value o)
                                      :label (tr (:label-key o))})
                            dash-options)
             :on-change on-dash}]

           [:div {:style row-style}
            [:span {:style label-style} (tr "workspace.options.connectors.arrow")]
            [:button {:type "button"
                      :style (merge coral-btn-style
                                   (when arrow? {:background coral :color "#fff" :border-color coral}))
                      :on-click on-toggle-arrow}
             (if arrow? (tr "workspace.options.connectors.on")
                        (tr "workspace.options.connectors.off"))]]

           [:div {:style row-style}
            [:span {:style label-style} (tr "workspace.options.connectors.orthogonal")]
            [:button {:type "button"
                      :style (merge coral-btn-style
                                   (when ortho? {:background coral :color "#fff" :border-color coral}))
                      :on-click on-toggle-ortho}
             (if ortho? (tr "workspace.options.connectors.on")
                        (tr "workspace.options.connectors.off"))]]

           [:div {:style (merge row-style {:justify-content "flex-end"})}
            [:button {:type "button" :style coral-btn-style
                      :title (tr "workspace.options.connectors.delete")
                      :on-click on-delete}
             (icon-trash)]]]]]))))