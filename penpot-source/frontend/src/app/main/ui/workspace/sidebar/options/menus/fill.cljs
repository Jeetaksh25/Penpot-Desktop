;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
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

;; Figma-parity grain on fills (gap #65). Optional grain overlay per fill
;; (:intensity 0..1 and :size). Absent = no grain = today's rendering. The
;; renderer grain overlay on the paint is deferred (no build to verify);
;; the field round-trips on the fill via dwsh/update-shapes. Rendered for
;; every fill row (always additive — default intensity 0 = no-op).
(mf/defc fill-grain-controls*
  {::mf/wrap [#(mf/memo' %)]}
  [{:keys [index value on-change-grain]}]
  (let [grain          (or (:grain value) {})
        on-intensity   (mf/use-fn (mf/deps index on-change-grain)
                                  (fn [e]
                                    (let [v (.. e -target -value)]
                                      (on-change-grain index (assoc grain :intensity (d/parse-double v))))))
        on-size        (mf/use-fn (mf/deps index on-change-grain)
                                  (fn [e]
                                    (let [v (.. e -target -value)]
                                      (on-change-grain index (assoc grain :size (d/parse-double v))))))]
    [:div {:style #js {:display "flex"
                       :align-items "center"
                       :gap "8px"
                       :padding "4px 8px 0"}
           :data-testid "fill.grain-options"}
     [:span {:style #js {:font-size "11px"
                         :color "var(--color-foreground-secondary)"}}
      (tr "workspace.options.fill-options.grain")]
     [:input {:type "number"
              :min 0 :max 1 :step 0.05
              :value (or (:intensity grain) 0)
              :on-change on-intensity}]
     [:span {:style #js {:font-size "11px"
                         :color "var(--color-foreground-secondary)"}}
      (tr "workspace.options.fill-options.grain-size")]
     [:input {:type "number"
              :min 0
              :value (or (:size grain) "")
              :on-change on-size}]]))
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
        on-flip-v (mf/use-fn (mf/deps toggle-flip) #(toggle-flip :vertical))

        ;; Figma-parity video fill (gap #22). A guarded additive "Replace
        ;; with video" affordance. It reuses the existing
        ;; dwm/upload-fill-image media pipeline; until the upload pipeline
        ;; (data/workspace/media.cljs validate-file + the backend media
        ;; object endpoint) is extended to accept video mtypes, the upload
        ;; is rejected with the existing "media type not supported" toast
        ;; (graceful, no crash). The video mtype round-trips on the schema
        ;; (color.cljc) and the binary fills path (fills/impl.cljc) once a
        ;; video is accepted. The HTML <video> renderer application is
        ;; deferred.
        video-ref (mf/use-ref)
        on-pick-video (mf/use-fn (mf/deps video-ref) #(dom/click (mf/ref-val video-ref)))
        on-video-file (mf/use-fn
                        (mf/deps index on-change-image-attrs)
                        (fn [file]
                          (let [on-success
                                (fn [img]
                                  (let [new-img (-> (select-keys img [:id :width :height :mtype :name])
                                                    (assoc :keep-aspect-ratio true))]
                                    (on-change-image-attrs
                                     index
                                     (fn [fill]
                                       (assoc fill :fill-image new-img)))))]
                            (st/emit! (dwm/upload-fill-image file on-success)))))]
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
                          :ref file-ref}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.fill.image.replace-video")
                         :on-click on-pick-video
                         :icon i/play}]
       [:> file-uploader {:accept "video/mp4,video/webm"
                          :input-id (str "fill-video-replace-" index)
                          :on-selected on-video-file
                          :ref video-ref}]])))

;; Figma-parity image adjustments (gap #23). Rendered only for image fills.
;; Seven adjustment sliders, each -100..100 in the UI mapped to -1..1 on
;; the image map (0/absent = no adjustment). Reuses the per-fill
;; on-change-image-attrs callback (dwsh/update-shapes, undo on). The
;; renderer applies them as a CSS `filter:` chain on the image element
;; (attrs.cljs, not owned here) — that wiring is deferred; the values
;; round-trip on the fill via the vector fills path.
(mf/defc image-adjustments-controls*
  {::mf/wrap [#(mf/memo' %)]}
  [{:keys [index value on-change-image-attrs]}]
  (let [image       (:fill-image value)
        adjustments (or (:adjustments image) {})
        on-change-adj
        (mf/use-fn
         (mf/deps index on-change-image-attrs)
         (fn [field value]
           (let [v   (/ (max -100 (min (or value 0) 100)) 100)
                 img (if (zero? v)
                       (d/dissoc-in image [:adjustments field])
                       (assoc-in image [:adjustments field] v))]
             (on-change-image-attrs
              index
              (fn [fill]
                (assoc fill :fill-image img))))))]
    (when (some? image)
      [:div {:style #js {:display "flex"
                         :flex-direction "column"
                         :gap "2px"
                         :padding "4px 8px 0"}}
       [:span {:style #js {:font-size "11px"
                           :color "var(--color-foreground-secondary)"
                           :padding-top "2px"}}
        (tr "workspace.options.fill.image.adjustments")]
       (for [field [:exposure :contrast :saturation
                    :temperature :tint :highlights :shadows]]
         (let [raw (get adjustments field 0)
               val (* (or raw 0) 100)]
           [:div {:key (str "adj-" (name field))
                  :style #js {:display "flex"
                              :align-items "center"
                              :gap "6px"}}
            [:span {:style #js {:font-size "10px"
                                :color "var(--color-foreground-secondary)"
                                :width "84px"}}
             (tr (str "workspace.options.fill.image.adjustment."
                      (name field)))]
            [:input {:type "range"
                     :min -100 :max 100 :step 1
                     :value val
                     :on-change #(on-change-adj field (.. % -target -value))}]]))])))

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
         (mf/deps ids fills)
         (fn [color index]
           (let [color (select-keys color clr/color-attrs)
                 ;; Figma-parity pattern fill (gap #25). Editing a pattern
                 ;; fill via the colorpicker converts it to a
                 ;; solid/gradient/image fill: we drop :fill-pattern and
                 ;; merge the picked color so the result carries exactly
                 ;; one color attr (has-valid-fill-attrs?), avoiding an
                 ;; invalid fill. Reuses update-shapes (undo on).
                 cur   (when (vector? fills) (nth fills index nil))]
             (if (:fill-pattern cur)
               (st/emit! (dwsh/update-shapes
                          ids
                          #(update-in % [:fills index]
                                      (fn [f] (-> f (dissoc :fill-pattern) (merge color))))))
               (st/emit! (dc/change-fill ids color index))))))

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

        ;; Figma-parity grain on fills (gap #65). Stores the grain map on
        ;; the individual fill. Reuses dwsh/update-shapes (undo on). The
        ;; renderer grain overlay on the paint is deferred (no build to
        ;; verify); the value round-trips on the fill via the vector fills
        ;; path (the binary fills optimization path drops it, same as the
        ;; other Figma-parity image fields).
        on-change-grain
        (mf/use-fn
         (mf/deps ids)
         (fn [index grain]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(assoc-in % [:fills index :grain] grain)))))

        ;; Figma-parity image fill rotate / flip / replace (gap #24). The
        ;; update-fn receives the fill map and returns a new fill (used for
        ;; rotate / flip / replace). Reuses dwsh/update-shapes (undo on).
        on-change-image-attrs
        (mf/use-fn
         (mf/deps ids)
         (fn [index update-fn]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dwsh/update-shapes ids #(update-in % [:fills index] update-fn)))))

        ;; Figma-parity pattern fill (gap #25). Adds a default pattern fill
        ;; (rectangular tiling, no source shape yet) to the selected shapes
        ;; via the standard update-shapes event (undo on). The SVG <pattern>
        ;; tiling renderer + the source-shape picker are deferred; the
        ;; :fill-pattern value round-trips on the fill via the vector fills
        ;; path. Rendered only when fills can be added (single selection).
        on-add-pattern
        (mf/use-fn
         (mf/deps ids multiple?)
         (fn [_]
           (when can-add-fills?
             (st/emit! (udw/trigger-bounding-box-cloaking ids))
             (st/emit!
              (dwsh/update-shapes
               ids
               (fn [shape]
                 (let [fills (or (:fills shape) [])
                       pattern-fill {:fill-pattern {:pattern-shape-id (:id shape)
                                                    :pattern-tiling :rectangular
                                                    :pattern-scale 1.0
                                                    :pattern-offset-x 0
                                                    :pattern-offset-y 0}}]
                   (assoc shape :fills (conj fills pattern-fill))))))
             (open-content))))

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
         [:*
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.options.fill.add-fill")
                            :on-click on-add
                            :data-testid "add-fill"
                            :disabled (not can-add-fills?)
                            :icon i/add}]
          ;; Figma-parity pattern fill (gap #25). Adds a pattern fill
          ;; (stub: rectangular tiling, no source picker yet).
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.options.fill.add-pattern")
                            :on-click on-add-pattern
                            :data-testid "add-pattern-fill"
                            :disabled (not can-add-fills?)
                            :icon i/grid}]])]]

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
                ;; Figma-parity grain on fills (gap #65).
                [:> fill-grain-controls* {:index index
                                           :value value
                                           :on-change-grain on-change-grain}]
                ;; Figma-parity image fill rotate / flip / replace (gap #24).
                ;; Renders nothing for non-image fills.
                [:> image-fill-controls* {:index index
                                          :value value
                                          :on-change-image-attrs on-change-image-attrs}]
                ;; Figma-parity image adjustments (gap #23).
                ;; Renders nothing for non-image fills.
                [:> image-adjustments-controls* {:index index
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
