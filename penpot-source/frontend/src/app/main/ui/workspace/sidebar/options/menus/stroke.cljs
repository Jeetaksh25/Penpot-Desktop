;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.stroke
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.stroke :as cts]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.sidebar.options.rows.stroke-row :refer [stroke-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def stroke-attrs
  [:strokes
   :stroke-style
   :stroke-alignment
   :stroke-width
   :stroke-width-mode
   :stroke-top
   :stroke-right
   :stroke-bottom
   :stroke-left
   :stroke-dash
   :stroke-gap
   :stroke-color
   :stroke-color-ref-id
   :stroke-color-ref-file
   :stroke-opacity
   :stroke-color-gradient
   :stroke-cap-start
   :stroke-cap-end
   :stroke-join
   :stroke-miter-limit
   ;; Figma-parity dynamic strokes (gap #54). Optional :variation map on
   ;; the stroke (wiggle/noise amplitude + frequency + seed). The renderer
   ;; per-segment jitter is deferred; the value round-trips here.
   :variation])

(defn- stroke-menu-check-props
  "A stroke-menu specific memoize check function that only checks if
  specific values are changed on provided props. This allows pass the
  whole shape as values without adding additional rerenders when other
  shape properties changes."
  [n-props o-props]
  (and (identical? (unchecked-get n-props "ids")
                   (unchecked-get o-props "ids"))
       (identical? (unchecked-get n-props "type")
                   (unchecked-get o-props "type"))
       (identical? (unchecked-get n-props "appliedTokens")
                   (unchecked-get o-props "appliedTokens"))
       (identical? (unchecked-get n-props "showCaps")
                   (unchecked-get o-props "showCaps"))
       (identical? (unchecked-get n-props "disableStrokeStyle")
                   (unchecked-get o-props "disableStrokeStyle"))
       (let [o-vals  (unchecked-get o-props "values")
             n-vals  (unchecked-get n-props "values")
             o-strokes (get o-vals :strokes)
             n-strokes (get n-vals :strokes)]
         (identical? o-strokes n-strokes))))

(mf/defc stroke-menu*
  {::mf/wrap [#(mf/memo' % stroke-menu-check-props)]}
  [{:keys [ids type values show-caps disable-stroke-style applied-tokens]}]
  (let [label (case type
                :multiple (tr "workspace.options.selection-stroke")
                :group (tr "workspace.options.group-stroke")
                (tr "labels.stroke"))

        state*          (mf/use-state true)
        open?           (deref state*)

        toggle-content  (mf/use-fn #(swap! state* not))
        open-content    (mf/use-fn #(reset! state* true))

        strokes         (:strokes values)
        has-strokes?    (or (= :multiple strokes) (some? (seq strokes)))


        on-color-change
        (mf/use-fn
         (mf/deps ids)
         (fn [index color]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/change-stroke-color ids color index))))


        on-remove
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/remove-stroke ids index))))

        handle-remove-all
        (mf/use-fn
         (mf/deps ids)
         (fn [_]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/remove-all-strokes ids))))

        on-color-detach
        (mf/use-fn
         (mf/deps ids)
         (fn [index color]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (let [color (-> color
                           (dissoc :ref-id :ref-file))]
             (st/emit! (dc/change-stroke-color ids color index)))))

        handle-reorder
        (mf/use-fn
         (mf/deps ids)
         (fn [from-pos to-space-between-pos]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/reorder-strokes ids from-pos to-space-between-pos))))

        on-stroke-style-change
        (mf/use-fn
         (mf/deps ids)
         (fn [index value]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/change-stroke-attrs ids {:stroke-style value} index))))

        on-stroke-alignment-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-alignment value} index))))

        on-stroke-width-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-width value} index))))

        on-stroke-dash-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-dash value} index))))

        on-stroke-gap-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-gap value} index))))

        on-stroke-cap-start-change
        (fn [index value]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/change-stroke-attrs ids {:stroke-cap-start value} index)))

        on-stroke-cap-end-change
        (fn [index value]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/change-stroke-attrs ids {:stroke-cap-end value} index)))

        on-stroke-join-change
        (fn [index value]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/change-stroke-attrs ids {:stroke-join value} index)))

        on-stroke-miter-limit-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-miter-limit value} index))))

        ;; Figma-parity per-stroke blend mode (gap #9). Stores the selected
        ;; blend mode on the individual stroke via the existing
        ;; change-stroke-attrs event (undo on). Renderer application deferred.
        on-stroke-blend-mode-change
        (fn [index value]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/change-stroke-attrs ids {:blend-mode value} index)))

        ;; Figma-parity per-side stroke widths. Toggling to :per-side seeds
        ;; the four side widths from the current uniform stroke-width so the
        ;; shape does not jump; toggling back to :uniform just flips the mode
        ;; (side widths stay on the stroke but are ignored by the renderer).
        on-stroke-width-mode-change
        (mf/use-fn
         (mf/deps ids)
         (fn [index mode width]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (let [attrs (if (= mode :per-side)
                         {:stroke-width-mode :per-side
                          :stroke-top width :stroke-right width
                          :stroke-bottom width :stroke-left width}
                         {:stroke-width-mode :uniform})]
             (st/emit! (dc/change-stroke-attrs ids attrs index)))))

        on-stroke-side-change
        (fn [index side value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {side value} index))))

        ;; Figma-parity dynamic strokes (gap #54). Updates the optional
        ;; :variation map on the individual stroke via the existing
        ;; change-stroke-attrs event (undo on). Renderer wiggle deferred.
        on-stroke-variation-change
        (fn [index value]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/change-stroke-attrs ids {:variation value} index)))

        on-stroke-cap-switch
        (fn [index]
          (let [stroke-cap-start (get-in values [:strokes index :stroke-cap-start])
                stroke-cap-end   (get-in values [:strokes index :stroke-cap-end])]
            (when (and (not= stroke-cap-start :multiple)
                       (not= stroke-cap-end :multiple))
              (st/emit! (udw/trigger-bounding-box-cloaking ids))
              (st/emit! (dc/change-stroke-attrs ids {:stroke-cap-start stroke-cap-end
                                                     :stroke-cap-end stroke-cap-start} index)))))
        on-toggle-visibility
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids)
                     (dwsh/update-shapes ids #(update-in % [:strokes index :hidden] not)))))

        on-add-stroke
        (fn [_]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/add-stroke ids cts/default-stroke))
          (when (not (some? (seq strokes))) (open-content)))

        disable-drag    (mf/use-state false)

        on-focus (fn [_]
                   (reset! disable-drag true))

        on-blur (fn [_]
                  (reset! disable-drag false))

        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token-name attrs]
           (st/emit! (dwta/unapply-token {:token-name token-name
                                          :attributes attrs
                                          :shape-ids ids}))))]

    [:section {:class (stl/css :stroke-section)
               :aria-label "Stroke section"}
     [:div {:class (stl/css :stroke-title)}
      [:> title-bar* {:collapsable  has-strokes?
                      :collapsed    (not open?)
                      :on-collapsed toggle-content
                      :title        label
                      :class        (stl/css-case :stroke-title-bar (not has-strokes?))}
       (when (not (= :multiple strokes))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.stroke.add-stroke")
                           :on-click on-add-stroke
                           :icon i/add
                           :data-testid "add-stroke"}])]]
     (when open?
       [:div {:class (stl/css-case :stroke-content true
                                   :stroke-content-empty (not has-strokes?))}
        (cond
          (or (= :multiple (:stroke-color applied-tokens))
              (= :multiple (:stroke-width applied-tokens))
              (= :multiple strokes))
          [:div {:class (stl/css :stroke-multiple)}
           [:div {:class (stl/css :stroke-multiple-label)}
            (tr "settings.multiple")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.stroke.remove-stroke")
                             :on-click handle-remove-all
                             :icon i/remove}]]
          (seq strokes)
          [:> h/sortable-container* {}
           (for [[index value] (d/enumerate (:strokes values []))]
             [:> stroke-row* {:key (dm/str "stroke-" index "-" (hash applied-tokens))
                              :index index
                              :stroke value
                              :title (tr "workspace.options.stroke-color")
                              :show-caps show-caps
                              :on-color-change on-color-change
                              :on-reorder handle-reorder
                              :on-color-detach on-color-detach
                              :on-remove on-remove
                              :on-stroke-width-change on-stroke-width-change
                              :on-stroke-dash-change on-stroke-dash-change
                              :on-stroke-gap-change on-stroke-gap-change
                              :on-stroke-style-change on-stroke-style-change
                              :on-stroke-alignment-change on-stroke-alignment-change
                              :on-stroke-cap-start-change on-stroke-cap-start-change
                              :on-stroke-cap-end-change on-stroke-cap-end-change
                              :on-stroke-cap-switch on-stroke-cap-switch
                              :on-stroke-join-change on-stroke-join-change
                              :on-stroke-miter-limit-change on-stroke-miter-limit-change
                              :on-blend-mode-change on-stroke-blend-mode-change
                              :on-stroke-width-mode-change on-stroke-width-mode-change
                              :on-stroke-side-change on-stroke-side-change
                              ;; Figma-parity dynamic strokes (gap #54).
                              :on-stroke-variation-change on-stroke-variation-change
                              :on-toggle-visibility on-toggle-visibility
                              :disable-drag disable-drag
                              :on-focus on-focus
                              :on-blur on-blur
                              :applied-tokens (when (= 0 index) applied-tokens)
                              :on-detach-token on-detach-token
                              :disable-stroke-style disable-stroke-style
                              :select-on-focus (not @disable-drag)
                              :ids ids}])])])]))
