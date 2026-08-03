;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.rows.stroke-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.color :as ctc]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.components.reorder-handler :refer [reorder-handler*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.sidebar.options.common :as soc]
   [app.main.ui.workspace.sidebar.options.menus.input-wrapper-tokens :refer [numeric-input-wrapper*]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity per-stroke blend modes (gap #9). Reuses the existing
;; layer-level blend-mode labels so no new i18n keys are needed.
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

(mf/defc stroke-row*
  [{:keys [index
           stroke
           title
           show-caps
           on-color-change
           on-reorder
           on-color-detach
           on-remove
           on-stroke-width-change
           on-stroke-dash-change
           on-stroke-gap-change
           on-stroke-style-change
           on-stroke-alignment-change
           on-stroke-cap-start-change
           on-stroke-cap-end-change
           on-stroke-cap-switch
           on-stroke-join-change
           on-stroke-miter-limit-change
           on-stroke-width-mode-change
           on-stroke-side-change
           ;; Figma-parity per-stroke blend mode (gap #9).
           on-blend-mode-change
           ;; Figma-parity dynamic strokes (gap #54).
           on-stroke-variation-change
           on-toggle-visibility
           disable-drag
           on-focus
           on-blur
           applied-tokens
           on-detach-token
           disable-stroke-style
           select-on-focus
           ids]}]

  (let [hidden?            (:hidden stroke)
        hidden?            (if (nil? hidden?) false hidden?)

        token-numeric-inputs
        (features/use-feature "tokens/numeric-input")

        on-drop
        (mf/use-fn
         (mf/deps on-reorder index)
         (fn [relative-pos data]
           (let [from-pos             (:index data)
                 to-space-between-pos (if (= relative-pos :bot) (inc index) index)]
             (on-reorder from-pos to-space-between-pos))))

        [dprops dref]
        (if (some? on-reorder)
          (h/use-sortable
           :data-type "penpot/stroke-row"
           :on-drop on-drop
           :disabled @disable-drag
           :detect-center? false
           :data {:index index})
          [nil nil])

        stroke-color-token
        (:stroke-color applied-tokens)

        on-color-change-refactor
        (mf/use-fn
         (mf/deps index on-color-change)
         (fn [color]
           (on-color-change index color)))

        on-color-detach
        (mf/use-fn
         (mf/deps index on-color-detach)
         (fn [_ color]
           (on-color-detach index color)))

        on-remove
        (mf/use-fn
         (mf/deps index on-remove)
         #(on-remove index))

        stroke-width (:stroke-width stroke)

        on-width-change
        (mf/use-fn
         (mf/deps index on-stroke-width-change ids)
         (fn [value]
           (soc/emit-value-or-token
            value
            #(on-stroke-width-change index %)
            ids
            #{:stroke-width})))

        ;; The SVG renderer defaults dash and gap to `stroke-width + 10` when
        ;; unset. Showing that value as placeholder makes the override obvious.
        default-dash-gap (when (number? stroke-width) (+ 10 stroke-width))

        stroke-gap  (or (:stroke-gap stroke) default-dash-gap)
        stroke-dash (or (:stroke-dash stroke) default-dash-gap)

        on-dash-change
        (mf/use-fn
         (mf/deps index on-stroke-dash-change)
         #(on-stroke-dash-change index %))

        on-gap-change
        (mf/use-fn
         (mf/deps index on-stroke-gap-change)
         #(on-stroke-gap-change index %))

        stroke-alignment (or (:stroke-alignment stroke) :center)

        stroke-alignment-options
        (mf/with-memo [stroke-alignment]
          (d/concat-vec
           (when (= :multiple stroke-alignment)
             [{:value :multiple :label "--"}])
           [{:value :center :label (tr "workspace.options.stroke.center") :id "center" :icon "stroke-center"}
            {:value :inner :label (tr "workspace.options.stroke.inner") :id "inner" :icon "stroke-inside"}
            {:value :outer :label (tr "workspace.options.stroke.outer") :id "outer" :icon "stroke-outside"}]))

        on-alignment-change
        (mf/use-fn
         (mf/deps index on-stroke-alignment-change)
         #(on-stroke-alignment-change index (keyword %)))

        on-token-change
        (mf/use-fn
         (mf/deps ids)
         (fn [_ token]
           (st/emit!
            (dwta/apply-token-from-input {:token token
                                          :attrs #{:stroke-color}
                                          :shape-ids ids
                                          :expand-with-children true}))))

        stroke-style (or (:stroke-style stroke) :solid)

        stroke-style-options
        (mf/with-memo [stroke-style]
          (d/concat-vec
           (when (= :multiple stroke-style)
             [{:value :multiple :label "--"}])
           [{:value :solid :label (tr "workspace.options.stroke.solid") :id "solid" :icon "stroke-solid"}
            {:value :dotted :label (tr "workspace.options.stroke.dotted") :id "dotted" :icon "stroke-dotted"}
            {:value :dashed :label (tr "workspace.options.stroke.dashed") :id "dashed" :icon "stroke-dashed"}
            {:value :mixed :label (tr "workspace.options.stroke.mixed") :id "mixed" :icon "stroke-mixed"}]))

        on-style-change
        (mf/use-fn
         (mf/deps index on-stroke-style-change)
         #(on-stroke-style-change index (keyword %)))

        on-caps-start-change
        (mf/use-fn
         (mf/deps index on-stroke-cap-start-change)
         (fn [cap]
           (let [cap (if (= cap "none")
                       nil
                       (keyword cap))]
             (on-stroke-cap-start-change index cap))))

        on-caps-end-change
        (mf/use-fn
         (mf/deps index on-stroke-cap-end-change)
         (fn [cap]
           (let [cap (if (= cap "none")
                       nil
                       (keyword cap))]
             (on-stroke-cap-end-change index cap))))

        on-detach-token-color
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (on-detach-token token #{:stroke-color})))

        on-detach-token-width
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (on-detach-token token #{:stroke-width})))

        stroke-caps-options
        [{:id "none" :value "none" :label (tr "workspace.options.stroke-cap.none")}
         {:label "" :type :separator :id "separator"}
         {:id "line-arrow" :value :line-arrow :label (tr "workspace.options.stroke-cap.line-arrow-short") :icon i/stroke-arrow}
         {:id "triangle-arrow" :value :triangle-arrow :label (tr "workspace.options.stroke-cap.triangle-arrow-short") :icon i/stroke-triangle}
         {:id "square-marker" :value :square-marker :label (tr "workspace.options.stroke-cap.square-marker-short") :icon i/stroke-rectangle}
         {:id "circle-marker" :value :circle-marker :label (tr "workspace.options.stroke-cap.circle-marker-short") :icon i/stroke-circle}
         {:id "diamond-marker" :value :diamond-marker :label (tr "workspace.options.stroke-cap.diamond-marker-short") :icon i/stroke-diamond}
         {:label "" :type :separator :id "separator"}
         {:id "round" :value :round :label (tr "workspace.options.stroke-cap.round") :icon i/stroke-rounded}
         {:id "square" :value :square :label (tr "workspace.options.stroke-cap.square") :icon i/stroke-squared}]

        on-cap-switch
        (mf/use-fn
         (mf/deps index on-stroke-cap-switch)
         #(on-stroke-cap-switch index))

        ;; Figma-parity: stroke join (miter/round/bevel) + miter limit.
        ;; Unset join = :miter and unset miter limit = 4, the SVG defaults,
        ;; so legacy strokes render pixel-identically.
        stroke-join         (or (:stroke-join stroke) :miter)
        stroke-miter-limit  (or (:stroke-miter-limit stroke) 4)

        stroke-joins-options
        (mf/with-memo [stroke-join]
          (d/concat-vec
           (when (= :multiple stroke-join)
             [{:value :multiple :label "--"}])
           [{:value :miter :label (tr "workspace.options.stroke.join.miter") :id "miter"}
            {:value :round :label (tr "workspace.options.stroke.join.round") :id "round"}
            {:value :bevel :label (tr "workspace.options.stroke.join.bevel") :id "bevel"}]))

        on-join-change
        (mf/use-fn
         (mf/deps index on-stroke-join-change)
         #(on-stroke-join-change index (keyword %)))

        on-miter-limit-change
        (mf/use-fn
         (mf/deps index on-stroke-miter-limit-change)
         #(on-stroke-miter-limit-change index %))

        ;; Figma-parity per-stroke blend mode (gap #9). Default :normal =
        ;; today's compositing. Renderer application deferred; the value
        ;; round-trips on the stroke.
        stroke-blend-mode (or (:blend-mode stroke) :normal)

        on-blend-mode-change*
        (mf/use-fn
         (mf/deps index on-blend-mode-change)
         (fn [value]
           (when (some? on-blend-mode-change)
             (on-blend-mode-change index (keyword value)))))

        ;; Figma-parity dynamic strokes (gap #54). Optional :variation map
        ;; on the stroke (wiggle/noise amplitude + frequency + seed).
        ;; Absent = a plain uniform stroke = today's rendering. The whole
        ;; block renders only when the parent wires on-stroke-variation-change
        ;; (stroke.cljs does); the renderer per-segment jitter is deferred.
        variation           (:variation stroke)
        variation-type      (or (:type variation) :wiggle)
        variation-amplitude (:amplitude variation 0)
        variation-frequency (:frequency variation 1)
        variation-seed      (:seed variation 0)
        variation-enabled?  (some? variation)

        on-toggle-variation
        (mf/use-fn
         (mf/deps index on-stroke-variation-change variation)
         (fn []
           (when (some? on-stroke-variation-change)
             (on-stroke-variation-change
              index
              (if variation-enabled?
                nil
                {:type :wiggle :amplitude 1 :frequency 1 :seed 0})))))

        on-variation-type-change
        (mf/use-fn
         (mf/deps index on-stroke-variation-change)
         (fn [value]
           (when (some? on-stroke-variation-change)
             (on-stroke-variation-change
              index (assoc (or variation {:amplitude 0 :frequency 1 :seed 0})
                           :type (keyword value))))))

        on-variation-amplitude-change
        (mf/use-fn
         (mf/deps index on-stroke-variation-change variation)
         (fn [v]
           (when (some? on-stroke-variation-change)
             (on-stroke-variation-change
              index (assoc (or variation {}) :amplitude v)))))

        on-variation-frequency-change
        (mf/use-fn
         (mf/deps index on-stroke-variation-change variation)
         (fn [v]
           (when (some? on-stroke-variation-change)
             (on-stroke-variation-change
              index (assoc (or variation {}) :frequency v)))))

        on-variation-seed-change
        (mf/use-fn
         (mf/deps index on-stroke-variation-change variation)
         (fn [v]
           (when (some? on-stroke-variation-change)
             (on-stroke-variation-change
              index (assoc (or variation {}) :seed v)))))

        ;; Figma-parity per-side stroke widths (rect/frame).
        stroke-width-mode  (or (:stroke-width-mode stroke) :uniform)
        per-side?           (= stroke-width-mode :per-side)
        stroke-side-top     (or (:stroke-top stroke)    (:stroke-width stroke) 0)
        stroke-side-right   (or (:stroke-right stroke)  (:stroke-width stroke) 0)
        stroke-side-bottom  (or (:stroke-bottom stroke) (:stroke-width stroke) 0)
        stroke-side-left    (or (:stroke-left stroke)   (:stroke-width stroke) 0)

        on-toggle-per-side
        (mf/use-fn
         (mf/deps index on-stroke-width-mode-change stroke-width-mode)
         (fn []
           (on-stroke-width-mode-change index
                                         (if (= stroke-width-mode :per-side) :uniform :per-side)
                                         (or (:stroke-width stroke) 0))))

        on-side-change
        (mf/use-fn
         (mf/deps index on-stroke-side-change)
         (fn [side value]
           (on-stroke-side-change index side value)))

        on-toggle-visibility
        (mf/use-fn
         (mf/deps index on-toggle-visibility)
         (fn []
           (when on-toggle-visibility
             (on-toggle-visibility index))))]

    [:div {:class (stl/css-case
                   :stroke-data true
                   :hidden hidden?
                   :dnd-over-top (= (:over dprops) :top)
                   :dnd-over-bot (= (:over dprops) :bot))
           :aria-label (str "stroke-row-" index)}

     (when (some? on-reorder)
       [:> reorder-handler* {:ref dref}])

     ;; Stroke Color
     ;; FIXME: memorize stroke color
     [:div {:class (stl/css :stroke-color-actions)}
      [:> color-row* {:color (ctc/stroke->color stroke)
                      :index index
                      :title title
                      :on-change on-color-change-refactor
                      :on-detach on-color-detach
                      :disable-drag disable-drag
                      :applied-token (if (= index 0)
                                       stroke-color-token
                                       nil)
                      :on-detach-token on-detach-token-color
                      :on-token-change on-token-change
                      :on-focus on-focus
                      :origin :stroke-color
                      :select-on-focus select-on-focus
                      :on-blur on-blur}]

      (when (some? on-toggle-visibility)
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.stroke.toggle-stroke")
                          :on-click on-toggle-visibility
                          :icon (if hidden? "hide" "shown")}])

      [:> icon-button* {:variant "ghost"
                        :aria-label (tr "workspace.options.stroke.remove-stroke")
                        :on-click on-remove
                        :icon i/remove}]]

     ;; Stroke Width, Alignment & Style
     (if token-numeric-inputs
       [:div {:class (stl/css :stroke-options-tokens)}
        [:> numeric-input-wrapper* {:on-change on-width-change
                                    :on-detach on-detach-token-width
                                    :icon i/stroke-size
                                    :min 0
                                    :on-focus on-focus
                                    :on-blur on-blur
                                    :attr :stroke-width
                                    :class (stl/css :numeric-input-wrapper)
                                    :property (tr "workspace.options.stroke-width")
                                    :applied-token (get applied-tokens :stroke-width)
                                    :value stroke-width}]
        [:> select* {:default-selected (d/name stroke-alignment)
                     :options stroke-alignment-options
                     :variant "icon-only"
                     :data-testid "stroke.alignment"
                     :disabled (if (= :multiple hidden?) true hidden?)
                     :wrapper-class (stl/css :stroke-align-icon-select)
                     :on-change on-alignment-change}]

        (when-not disable-stroke-style
          [:> select* {:default-selected (d/name stroke-style)
                       :options stroke-style-options
                       :wrapper-class (stl/css :stroke-style-icon-select)
                       :data-testid "stroke.style"
                       :variant "icon-only"
                       :disabled (if (= :multiple hidden?) true hidden?)
                       :dropdown-alignment :right
                       :on-change on-style-change}])]

       [:div {:class (stl/css :stroke-options)}
        [:div {:class (stl/css :stroke-width-input)
               :title (tr "workspace.options.stroke-width")}
         [:> icon* {:icon-id i/stroke-size
                    :size "s"}]
         [:> deprecated-input/numeric-input* {:value stroke-width
                                              :min 0
                                              :placeholder (tr "settings.multiple")
                                              :on-change on-width-change
                                              :on-focus on-focus
                                              :select-on-focus select-on-focus
                                              :on-blur on-blur}]]
        [:div {:class (stl/css :stroke-alignment-select)
               :data-testid "stroke.alignment"}
         [:& select {:default-value stroke-alignment
                     :options stroke-alignment-options
                     :disabled hidden?
                     :on-change on-alignment-change}]]

        (when-not disable-stroke-style
          [:div {:class (stl/css :stroke-style-select)
                 :data-testid "stroke.style"}
           [:& select {:default-value stroke-style
                       :options stroke-style-options
                       :disabled hidden?
                       :on-change on-style-change}]])])

     ;; Figma-parity: per-side stroke widths toggle + inputs (rect/frame).
     [:div {:class (stl/css :stroke-caps-options)
            :data-testid "stroke.per-side-options"}
      [:> icon-button* {:variant (if per-side? "secondary" "ghost")
                        :aria-label (tr "workspace.options.stroke.per-side")
                        :on-click on-toggle-per-side
                        :icon i/stroke-size}]
      (when per-side?
        [:> numeric-input-wrapper* {:on-change #(on-side-change :stroke-top %)
                                     :text-icon "T" :min 0
                                     :on-focus on-focus :on-blur on-blur
                                     :attr :stroke-top
                                     :class (stl/css :numeric-input-wrapper)
                                     :property (tr "workspace.options.stroke.side-top")
                                     :value stroke-side-top}])
      (when per-side?
        [:> numeric-input-wrapper* {:on-change #(on-side-change :stroke-right %)
                                     :text-icon "R" :min 0
                                     :on-focus on-focus :on-blur on-blur
                                     :attr :stroke-right
                                     :tooltip-placement "top-left"
                                     :class (stl/css :numeric-input-wrapper)
                                     :property (tr "workspace.options.stroke.side-right")
                                     :value stroke-side-right}])
      (when per-side?
        [:> numeric-input-wrapper* {:on-change #(on-side-change :stroke-bottom %)
                                     :text-icon "B" :min 0
                                     :on-focus on-focus :on-blur on-blur
                                     :attr :stroke-bottom
                                     :class (stl/css :numeric-input-wrapper)
                                     :property (tr "workspace.options.stroke.side-bottom")
                                     :value stroke-side-bottom}])
      (when per-side?
        [:> numeric-input-wrapper* {:on-change #(on-side-change :stroke-left %)
                                     :text-icon "L" :min 0
                                     :on-focus on-focus :on-blur on-blur
                                     :attr :stroke-left
                                     :tooltip-placement "top-left"
                                     :class (stl/css :numeric-input-wrapper)
                                     :property (tr "workspace.options.stroke.side-left")
                                     :value stroke-side-left}])]

     ;; Stroke Dash / Gap (only visible for dashed style)
     (when (= stroke-style :dashed)
       [:div {:class (stl/css :stroke-dash-options)
              :data-testid "stroke.dash-options"}
        [:> numeric-input-wrapper* {:on-change on-dash-change
                                    :text-icon "DASH"
                                    :min 0
                                    :on-focus on-focus
                                    :on-blur on-blur
                                    :attr :stroke-dash
                                    :class (stl/css :numeric-input-wrapper)
                                    :property (tr "workspace.options.stroke-dash")
                                    :value stroke-dash}]
        [:> numeric-input-wrapper* {:on-change on-gap-change
                                    :text-icon "GAP"
                                    :min 0
                                    :on-focus on-focus
                                    :on-blur on-blur
                                    :attr :stroke-gap
                                    :tooltip-placement "top-left"
                                    :class (stl/css :numeric-input-wrapper)
                                    :property (tr "workspace.options.stroke-gap")
                                    :value stroke-gap}]])

     ;; Stroke Caps
     (when show-caps
       [:div {:class (stl/css :stroke-caps-options)}
        [:> select* {:default-selected (or (d/name (:stroke-cap-start stroke)) "none")
                     :options stroke-caps-options
                     :data-testid "stroke.cap-start"
                     :disabled hidden?
                     :on-change on-caps-start-change}]
        [:> icon-button* {:variant "secondary"
                          :aria-label (tr "labels.switch")
                          :disabled hidden?
                          :on-click on-cap-switch
                          :icon i/switch}]
        [:> select* {:default-selected (or (d/name (:stroke-cap-end stroke)) "none")
                     :options stroke-caps-options
                     :data-testid "stroke.cap-end"
                     :disabled hidden?
                     :on-change on-caps-end-change}])]

     ;; Stroke Joins (Figma-parity: miter/round/bevel + miter limit).
     ;; Shown for every stroke: joins apply to closed shapes' corners too,
     ;; not only open paths. Miter limit only matters when join is :miter.
     [:div {:class (stl/css :stroke-caps-options)
            :data-testid "stroke.join-options"}
      [:> select* {:default-selected (d/name stroke-join)
                   :options stroke-joins-options
                   :data-testid "stroke.join"
                   :disabled hidden?
                   :on-change on-join-change}]
      (when (= :miter stroke-join)
        [:> numeric-input-wrapper* {:on-change on-miter-limit-change
                                    :text-icon "MITER"
                                    :min 1
                                    :on-focus on-focus
                                    :on-blur on-blur
                                    :attr :stroke-miter-limit
                                    :class (stl/css :numeric-input-wrapper)
                                    :property (tr "workspace.options.stroke.miter-limit")
                                    :value stroke-miter-limit}])]

     ;; Figma-parity dynamic strokes (gap #54). A toggle + amplitude /
     ;; frequency / seed controls, rendered only when the parent wires
     ;; on-stroke-variation-change (stroke.cljs does) so legacy callers
     ;; see nothing. Absent :variation = a plain uniform stroke = today's
     ;; rendering; the renderer per-segment jitter is deferred.
     (when (some? on-stroke-variation-change)
       [:div {:class (stl/css :stroke-caps-options)
              :data-testid "stroke.variation-options"}
        [:> icon-button* {:variant (if variation-enabled? "secondary" "ghost")
                          :aria-label (tr "workspace.options.stroke.variation.toggle")
                          :on-click on-toggle-variation
                          :disabled hidden?
                          :icon i/stroke-size}]
        (when variation-enabled?
          [:> select* {:default-selected (d/name variation-type)
                       :options [{:value "wiggle" :label (tr "workspace.options.stroke.variation.wiggle")}
                                 {:value "noise"  :label (tr "workspace.options.stroke.variation.noise")}]
                       :disabled hidden?
                       :on-change on-variation-type-change}])
        (when variation-enabled?
          [:> numeric-input-wrapper* {:on-change on-variation-amplitude-change
                                       :text-icon "AMP"
                                       :min 0
                                       :on-focus on-focus
                                       :on-blur on-blur
                                       :attr :variation-amplitude
                                       :class (stl/css :numeric-input-wrapper)
                                       :property (tr "workspace.options.stroke.variation.amplitude")
                                       :value variation-amplitude}])
        (when variation-enabled?
          [:> numeric-input-wrapper* {:on-change on-variation-frequency-change
                                       :text-icon "FREQ"
                                       :min 0
                                       :on-focus on-focus
                                       :on-blur on-blur
                                       :attr :variation-frequency
                                       :class (stl/css :numeric-input-wrapper)
                                       :property (tr "workspace.options.stroke.variation.frequency")
                                       :value variation-frequency}])
        (when variation-enabled?
          [:> numeric-input-wrapper* {:on-change on-variation-seed-change
                                       :text-icon "SEED"
                                       :min 0
                                       :on-focus on-focus
                                       :on-blur on-blur
                                       :attr :variation-seed
                                       :class (stl/css :numeric-input-wrapper)
                                       :property (tr "workspace.options.stroke.variation.seed")
                                       :value variation-seed}])])
     ;; Figma-parity per-stroke blend mode (gap #9). Rendered only when the
     ;; menu wires on-blend-mode-change (stroke.cljs); absent = no control.
     (when (some? on-blend-mode-change)
       [:div {:class (stl/css :stroke-caps-options)
              :data-testid "stroke.blend-mode-options"}
        [:& select {:default-value stroke-blend-mode
                    :options blend-mode-options
                    :disabled hidden?
                    :on-change on-blend-mode-change*}]]])))
