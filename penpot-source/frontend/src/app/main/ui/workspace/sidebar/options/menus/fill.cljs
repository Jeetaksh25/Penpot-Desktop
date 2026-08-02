;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.color :as clr]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.media :as dwm]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.features :as feat]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity per-item blend modes (gap #9). Reuses the existing
;; layer-level blend-mode labels so no new i18n keys are needed for the
;; option text. Only a small label key is added (workspace.options.blend-mode).
(def ^:private blend-mode-options
  [{:value :normal :label (tr "workspace.options.layer-options.blend-mode.normal")}
   {:value :darken :label (tr "workspace.options.layer-options.blend-mode.darken")}
   {:value :multiply :label (tr "workspace.options.layer-options.blend-mode.multiply")}
   {:value :color-burn :label (tr "workspace.options.layer-options.blend-mode.color-burn")}
   {:value :lighten :label (tr "workspace.options.layer-options.blend-mode.lighten")}
   {:value :screen :label (tr "workspace.options.layer-options.blend-mode.screen")}
   {:value :color-dodge :label (tr "workspace.options.layer-options.blend-mode.color-dodge")}
   {:value :overlay :label (tr "workspace.options.layer-options.blend-mode.overlay")}
   {:value :soft-light :label (tr "workspace.options.layer-options.blend-mode.soft-light")}
   {:value :hard-light :label (tr "workspace.options.layer-options.blend-mode.hard-light")}
   {:value :difference :label (tr "workspace.options.layer-options.blend-mode.difference")}
   {:value :exclusion :label (tr "workspace.options.layer-options.blend-mode.exclusion")}
   {:value :hue :label (tr "workspace.options.layer-options.blend-mode.hue")}
   {:value :saturation :label (tr "workspace.options.layer-options.blend-mode.saturation")}
   {:value :color :label (tr "workspace.options.layer-options.blend-mode.color")}
   {:value :luminosity :label (tr "workspace.options.layer-options.blend-mode.luminosity")}])

;; Figma-parity per-fill blend mode (gap #9). A component (not an inline
;; form) so hooks are not used inside the `for` loop. Default :normal
;; matches today's compositing; selecting another stores it on the fill
;; (renderer application deferred — see fills.cljc note).
(mf/defc fill-blend-mode-select*
  {::mf/wrap [#(mf/memo' %)]}
  [{:keys [index value on-change]}]
  (let [current (or (:blend-mode value) :normal)
        handle  (mf/use-fn (mf/deps index on-change) (fn [v] (on-change index v)))]
    [:div {:style #js {:display "flex"
                       :align-items "center"
                       :gap "8px"
                       :padding "4px 8px 0"}}
     [:span {:style #js {:font-size "11px"
                         :color "var(--color-foreground-secondary)"}}
      (tr "workspace.options.blend-mode")]
     [:& select {:default-value current
                 :options blend-mode-options
                 :on-change handle}]]))

;; Figma-parity image fill rotate / flip / replace (gap #24). Rendered
;; only for image fills (a :fill-image key is present). Rotate bumps the
;; in-fill rotation by 90 degrees (Figma also allows free rotate via the
;; crop handles, which is deferred — needs the colorpicker crop matrix).
;; Flip toggles :horizontal / :vertical in the :fill-image-flip set.
;; Replace uploads a new image via the existing upload-fill-image event
;; and swaps the :fill-image, preserving crop / rotation / flip.
(mf/defc image-fill-controls*
  {::mf/wrap [#(mf/memo' %)]}
  [{:keys [index value on-change-image-attrs]}]
  (let [image    (:fill-image value)
        rotation (or (:fill-image-rotation image) 0)
        flip     (or (:fill-image-flip image) #{})
        file-ref (mf/use-ref)
        on-pick  (mf/use-fn (mf/deps file-ref) #(dom/click (mf/ref-val file-ref)))
        on-file  (mf/use-fn
                   (mf/deps index on-change-image-attrs)
                   (fn [file]
                     (let [on-success
                           (fn [img]
                             (let [new-img (-> (select-keys img [:id :width :height :mtype :name])
                                               (assoc :keep-aspect-ratio true))]
                               (on-change-image-attrs
                                index
                                (fn [fill]
                                  (let [old-img (:fill-image fill)
                                        kept    (select-keys old-img
                                                              [:fill-image-rotation
                                                               :fill-image-flip
                                                               :crop-x :crop-y
                                                               :crop-w :crop-h])]
                                    (assoc fill :fill-image (merge new-img kept)))))))]
                       (st/emit! (dwm/upload-fill-image file on-success)))))
        on-rotate (mf/use-fn
                    (mf/deps index on-change-image-attrs rotation)
                    (fn []
                      (on-change-image-attrs
                       index
                       (fn [fill]
                         (assoc fill :fill-image
                                (assoc (:fill-image fill)
                                       :fill-image-rotation (+ rotation 90)))))))
        toggle-flip (mf/use-fn
                      (mf/deps index on-change-image-attrs flip)
                      (fn [axis]
                        (let [new-flip (if (contains? flip axis)
                                        (disj flip axis)
                                        (conj flip axis))]
                          (on-change-image-attrs
                           index
                           (fn [fill]
                             (assoc fill :fill-image
                                    (assoc (:fill-image fill)
                                           :fill-image-flip new-flip)))))))
        on-flip-h (mf/use-fn (mf/deps toggle-flip) #(toggle-flip :horizontal))
        on-flip-v (mf/use-fn (mf/deps toggle-flip) #(toggle-flip :vertical))]
    (when (some? image)
      [:div {:style #js {:display "flex"
                         :align-items "center"
                         :gap "4px"
                         :padding "4px 8px 0"}}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.fill.image.rotate")
                         :on-click on-rotate
                         :icon i/rotation}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.fill.image.flip-horizontal")
                         :on-click on-flip-h
                         :icon i/flip-horizontal}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.fill.image.flip-vertical")
                         :on-click on-flip-v
                         :icon i/flip-vertical}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.fill.image.replace")
                         :on-click on-pick
                         :icon i/switch}]
       [:> file-uploader {:accept "image/*"
                          :input-id (str "fill-image-replace-" index)
                          :on-selected on-file
                          :ref file-ref}]])))

(def fill-attrs
  #{:fills :hide-fill-on-export})

(def ^:private
  xf:process-fills
  (map-indexed
   (fn [index item]
     (let [color (types.fills/fill->color item)]
       (with-meta item {:index index :color color})))))

(defn- prepare-fills
  "Internal helper hook that prepares fills"
  [fills]
  (if (= :multiple fills)
    fills
    (->> fills
         (into [] xf:process-fills)
         (not-empty))))

(defn- check-props
  "A fills-menu specific memoize check function that only checks if
  specific values are changed on provided props. This allows pass the
  whole shape as values without adding additional rerenders when other
  shape properties changes."
  [n-props o-props]
  (and (identical? (unchecked-get n-props "ids")
                   (unchecked-get o-props "ids"))
       (identical? (unchecked-get n-props "appliedTokens")
                   (unchecked-get o-props "appliedTokens"))
       (let [o-vals  (unchecked-get o-props "values")
             n-vals  (unchecked-get n-props "values")
             o-fills (get o-vals :fills)
             n-fills (get n-vals :fills)
             o-hide  (get o-vals :hide-fill-on-export)
             n-hide  (get n-vals :hide-fill-on-export)]
         (and (identical? o-hide n-hide)
              (identical? o-fills n-fills)))))

(mf/defc fill-menu*
  {::mf/wrap [#(mf/memo' % check-props)]}
  [{:keys [ids type values applied-tokens]}]

  (let [fills          (get values :fills)
        hide-on-export (get values :hide-fill-on-export false)
        fill-token-applied (:fill applied-tokens)

        render-wasm?   (feat/use-feature "render-wasm/v1")

        ^boolean
        multiple?      (= :multiple fills)

        fills          (mf/with-memo [fills]
                         (prepare-fills fills))

        has-fills?     (or multiple? (some? fills))

        empty-fills?   (and (not multiple?)
                            (= 0 (count fills)))

        open*          (mf/use-state has-fills?)
        open?          (deref open*)

        toggle-content (mf/use-fn #(swap! open* not))
        open-content   (mf/use-fn #(reset! open* true))
        close-content  (mf/use-fn #(reset! open* false))

        checkbox-ref   (mf/use-ref)

        can-add-fills?
        (if render-wasm?
          (and (not multiple?)
               (< (count fills) types.fills/MAX-FILLS))
          (not ^boolean multiple?))

        label
        (case type
          :multiple (tr "workspace.options.selection-fill")
          :group (tr "workspace.options.group-fill")
          (tr "workspace.options.fill"))

        on-add
        (mf/use-fn
         (mf/deps ids multiple? empty-fills?)
         (fn [_]
           (when can-add-fills?
             (st/emit! (udw/trigger-bounding-box-cloaking ids))
             (st/emit! (dc/add-fill ids {:color default-color
                                         :opacity 1}))
             (when (or multiple? empty-fills?)
               (open-content)))))

        on-change
        (mf/use-fn
         (mf/deps ids)
         (fn [color index]
           (let [color (select-keys color clr/color-attrs)]
             (st/emit! (dc/change-fill ids color index)))))

        ;; Figma-parity per-item blend modes (gap #9). Stores the selected
        ;; blend mode on the individual fill. Reuses dwsh/update-shapes
        ;; (save-undo defaults true). Renderer application is deferred
        ;; (see schema note in fills.cljc); the value round-trips on the fill.
        on-blend-mode-change
        (mf/use-fn
         (mf/deps ids)
         (fn [index value]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(assoc-in % [:fills index :blend-mode] value)))))

        ;; Figma-parity image fill rotate / flip / replace (gap #24). The
        ;; update-fn receives the fill map and returns a new fill (used for
        ;; rotate / flip / replace). Reuses dwsh/update-shapes (undo on).
        on-change-image-attrs
        (mf/use-fn
         (mf/deps ids)
         (fn [index update-fn]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(update-in % [:fills index] update-fn)))))

        on-reorder
        (mf/use-fn
         (mf/deps ids)
         (fn [from-pos to-space-between-pos]
           (st/emit! (dc/reorder-fills ids from-pos to-space-between-pos))))

        on-remove
        (mf/use-fn
         (mf/deps ids multiple? empty-fills?)
         (fn [index _event]
           (st/emit! (dc/remove-fill ids index))
           (when (or multiple? empty-fills?)
             (close-content))))

        on-remove-all
        (mf/use-fn
         (mf/deps ids)
         #(st/emit! (dc/remove-all-fills ids)))

        on-detach
        (mf/use-fn
         (mf/deps ids)
         (fn [index _event]
           (st/emit! (dc/detach-fill ids index))))

        on-change-show-on-export
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (st/emit! (dc/change-hide-fill-on-export ids (not value))))))

        disable-drag*
        (mf/use-state false)

        disable-drag?
        (deref disable-drag*)

        on-focus
        (mf/use-fn
         #(reset! disable-drag* true))

        on-blur
        (mf/use-fn #(reset! disable-drag* false))

        on-token-change
        (mf/use-fn
         (mf/deps ids)
         (fn [_ token]
           (st/emit!
            (dwta/apply-token-from-input {:token token
                                          :attrs #{:fill}
                                          :shape-ids ids
                                          :expand-with-children true}))))

        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token-name]
           (st/emit! (dwta/unapply-token {:token-name token-name
                                          :attributes #{:fill}
                                          :shape-ids ids}))))]

    (mf/with-layout-effect [hide-on-export]
      (when-let [checkbox (mf/ref-val checkbox-ref)]
        ;; Note that the "indeterminate" attribute only may be set by code, not as a static attribute.
        ;; See https://developer.mozilla.org/en-US/docs/Web/HTML/Element/input/checkbox#attr-indeterminate
        (if (= hide-on-export :multiple)
          (dom/set-attribute! checkbox "indeterminate" true)
          (dom/remove-attribute! checkbox "indeterminate"))))

    [:section {:class (stl/css :fill-section)
               :aria-label (tr "workspace.options.fill.section")}
     [:div {:class (stl/css :fill-title)}
      [:> title-bar* {:collapsable  has-fills?
                      :collapsed    (not open?)
                      :on-collapsed toggle-content
                      :title        label
                      :class        (stl/css-case :fill-title-bar (not has-fills?))}

       (when (not (= :multiple fills))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.fill.add-fill")
                           :on-click on-add
                           :data-testid "add-fill"
                           :disabled (not can-add-fills?)
                           :icon i/add}])]]

     (when open?
       [:div {:class (stl/css :fill-content)}
        (cond
          (or (= :multiple fills)
              (= :multiple fill-token-applied))
          [:div {:class (stl/css :fill-multiple)}
           [:div {:class (stl/css :fill-multiple-label)}
            (tr "settings.multiple")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.fill.remove-fill")
                             :on-click on-remove-all
                             :icon i/remove}]]

          (some? fills)
          [:> h/sortable-container* {}
           (for [value fills]
             (let [mdata (meta value)
                   index (get mdata :index)
                   color (get mdata :color)]
               [:div {:key index
                      :style #js {:margin-top "4px"}}
                [:> color-row* {:color color
                                :index index
                                :title (tr "workspace.options.fill")
                                :on-change on-change
                                :on-reorder on-reorder
                                :on-detach on-detach
                                :on-detach-token on-detach-token
                                :on-remove on-remove
                                :disable-drag disable-drag?
                                :on-focus on-focus
                                :applied-token (if (= index 0)
                                                 fill-token-applied
                                                 nil)
                                :on-token-change on-token-change
                                :origin :fill
                                :select-on-focus (not disable-drag?)
                                :on-blur on-blur}]
                ;; Figma-parity per-fill blend mode (gap #9).
                [:> fill-blend-mode-select* {:index index
                                             :value value
                                             :on-change on-blend-mode-change}]
                ;; Figma-parity image fill rotate / flip / replace (gap #24).
                ;; Renders nothing for non-image fills.
                [:> image-fill-controls* {:index index
                                          :value value
                                          :on-change-image-attrs on-change-image-attrs}]]))])

        (when (or (= type :frame)
                  (and (= type :multiple)
                       (some? hide-on-export)))
          [:div {:class (stl/css :fill-checkbox)}
           [:label {:for "show-fill-on-export"
                    :class (stl/css-case :global/checked (not hide-on-export))}
            [:span {:class (stl/css-case :check-mark true
                                         :checked (not hide-on-export))}
             (when (not hide-on-export)
               deprecated-icon/status-tick)]
            (tr "workspace.options.show-fill-on-export")
            [:input {:type "checkbox"
                     :id "show-fill-on-export"
                     :ref checkbox-ref
                     :checked (not hide-on-export)
                     :on-change on-change-show-on-export}]]])])]))
