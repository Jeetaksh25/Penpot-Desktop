;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.blur
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def blur-attrs [:blur :background-blur])

;; Figma-parity per-blur blend modes (gap #9). Reuses the existing
;; layer-level blend-mode labels so no new i18n keys are needed.
(def ^:private blend-mode-options
  [{:value "normal" :label (tr "workspace.options.layer-options.blend-mode.normal")}
   {:value "darken" :label (tr "workspace.options.layer-options.blend-mode.darken")}
   {:value "multiply" :label (tr "workspace.options.layer-options.blend-mode.multiply")}
   {:value "color-burn" :label (tr "workspace.options.layer-options.blend-mode.color-burn")}
   {:value "lighten" :label (tr "workspace.options.layer-options.blend-mode.lighten")}
   {:value "screen" :label (tr "workspace.options.layer-options.blend-mode.screen")}
   {:value "color-dodge" :label (tr "workspace.options.layer-options.blend-mode.color-dodge")}
   {:value "overlay" :label (tr "workspace.options.layer-options.blend-mode.overlay")}
   {:value "soft-light" :label (tr "workspace.options.layer-options.blend-mode.soft-light")}
   {:value "hard-light" :label (tr "workspace.options.layer-options.blend-mode.hard-light")}
   {:value "difference" :label (tr "workspace.options.layer-options.blend-mode.difference")}
   {:value "exclusion" :label (tr "workspace.options.layer-options.blend-mode.exclusion")}
   {:value "hue" :label (tr "workspace.options.layer-options.blend-mode.hue")}
   {:value "saturation" :label (tr "workspace.options.layer-options.blend-mode.saturation")}
   {:value "color" :label (tr "workspace.options.layer-options.blend-mode.color")}
   {:value "luminosity" :label (tr "workspace.options.layer-options.blend-mode.luminosity")}])

(defn create-blur [type]
  (let [id (uuid/next)]
    {:id id
     :type type
     :value 4
     :hidden false}))

;; Figma-parity stacked layer blurs (gap #74). A stack entry mirrors the
;; single-blur map shape (so the existing blur-menu-content* can be reused
;; to edit each entry). The :blurs vector on the shape is opaque
;; ([:vector ::sm/any] in shape.cljc); absent :blurs = single-blur
;; behavior unchanged. Renderer multi-blur compositing is deferred.
(defn create-stack-blur
  "Create a new stack blur entry (a layer-blur by default)."
  []
  (let [id (uuid/next)]
    {:id id
     :type :layer-blur
     :value 4
     :hidden false}))

(mf/defc blur-menu-content*
  [{:keys [blur-key value change-fn blur-values]}]
  (let [render-wasm?        (features/use-feature "render-wasm/v1")
        bg-blur?            (and render-wasm?
                                 (contains? cf/flags :background-blur))
        is-hidden           (get value :hidden)
        show-more-options*  (mf/use-state false)
        show-more-options   (deref show-more-options*)
        toggle-more-options (mf/use-fn #(swap! show-more-options* not))

        handle-delete
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn []
           (change-fn #(dissoc % blur-key))))

        handle-toggle-visibility
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn []
           (change-fn #(update-in % [blur-key :hidden] not))))

        handle-change
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn [value]
           (change-fn #(assoc-in % [blur-key :value] value))))

        ;; Figma-parity per-blur blend mode (gap #9). Default :normal =
        ;; today's compositing. change! routes through dwsh/update-shapes
        ;; (save-undo defaults true). Renderer application deferred.
        blur-blend-mode (or (:blend-mode value) :normal)
        handle-blend-mode-change
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn [value]
           (change-fn #(assoc-in % [blur-key :blend-mode] (keyword value)))))

        ;; Figma-parity progressive blur (gap #60). When :progressive? is
        ;; true the blur falloff varies across the shape (gradient-like
        ;; blur). The :start-radius / :start-offset / :end-offset params
        ;; describe the falloff region. All optional; absent = :value is a
        ;; uniform blur = today's behavior. The renderer gradient-blur
        ;; kernel is deferred (significant GPU work, no build to verify);
        ;; the fields round-trip on the blur map via change!.
        progressive? (boolean (:progressive? value))
        handle-toggle-progressive
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn []
           (change-fn #(update-in % [blur-key :progressive?] (fn [v] (not (boolean v)))))))

        handle-change-start-radius
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn [v]
           (change-fn #(assoc-in % [blur-key :start-radius] v))))

        handle-change-start-offset
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn [v]
           (change-fn #(assoc-in % [blur-key :start-offset] v))))

        handle-change-end-offset
        (mf/use-fn
         (mf/deps change-fn blur-key)
         (fn [v]
           (change-fn #(assoc-in % [blur-key :end-offset] v))))

        handle-type-change
        (mf/use-fn
         (mf/deps change-fn value blur-key)
         (fn [type]
           (let [type-kw    (keyword type)
                 target-key (if (= type-kw :layer-blur) :blur :background-blur)]
             (change-fn
              (fn [shape]
                (cond
                  ;; mismo tipo
                  (= blur-key target-key)
                  shape

                  ;; ya existe un blur del tipo destino
                  (contains? shape target-key)
                  shape

                  ;; blur origen no existe
                  (not (contains? shape blur-key))
                  shape

                  :else
                  (let [blur (get shape blur-key)]
                    (-> shape
                        (dissoc blur-key)
                        (assoc target-key
                               (assoc blur :type type-kw))))))))))

        bb-disabled? (and (= 2 (count blur-values))
                          (not= blur-key :background-blur))
        lb-disabled? (and (= 2 (count blur-values))
                          (not= blur-key :blur))
        label-ref (mf/use-ref nil)

        type-options
        [{:value "layer-blur"  :disabled lb-disabled? :id "layer-blur" :label (tr "workspace.options.blur-options.layer-blur")}
         {:value "background-blur" :disabled bb-disabled?  :id "background-blur" :label (tr "workspace.options.blur-options.background-blur")}]


        background-blur-disabled?
        (and (= blur-key :background-blur)
             (not bg-blur?))

        label-text
        (cond
          (= blur-key :background-blur)
          (tr "workspace.options.blur-options.background-blur")

          bg-blur?
          (tr "workspace.options.blur-options.layer-blur")

          :else
          (tr "labels.blur"))

        label
        (mf/html [:span {:aria-labelledby "background-blur-disabled-label"
                         :ref label-ref
                         :class (stl/css-case :label true
                                              :disabled-label background-blur-disabled?)}
                  label-text])]

    [:*
     [:div {:class (stl/css-case :first-row true
                                 :hidden is-hidden)}
      [:div {:class (stl/css :blur-info)
             :data-testid "blur-info"}
       [:> icon-button* {:class (stl/css-case :show-more true
                                              :selected show-more-options)
                         :on-click toggle-more-options
                         :selected show-more-options
                         :variant "ghost"
                         :disabled (or
                                    is-hidden
                                    (and (= blur-key :background-blur)
                                         (= false bg-blur?)))
                         :aria-label (tr "workspace.options.blur-options.toggle-more-options")
                         :icon i/menu}]
       (cond bg-blur?
             [:> select*
              {:class (stl/css :blur-type-select)
               :default-selected (d/name (:type value))
               :aria-label (tr "workspace.options.blur-options.blur-type-select")
               :options type-options
               :disabled is-hidden
               :on-change handle-type-change}]
             background-blur-disabled?
             [:> tooltip*
              {:trigger-ref label-ref
               :id "background-blur-disabled-label"
               :class (stl/css :disabled-label-tooltip)
               :content (tr "workspace.options.blur-options.disabled-blur-label")}
              label]
             :else
             label)]

      [:div {:class (stl/css :actions)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.blur-options.toggle-blur")
                         :on-click handle-toggle-visibility
                         :disabled (and (= blur-key :background-blur)
                                        (= false bg-blur?))
                         :tooltip-placement "top-left"
                         :icon (if (or is-hidden
                                       (and (= blur-key :background-blur)
                                            (= false bg-blur?))) i/hide i/shown)}]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.blur-options.remove-blur")
                         :on-click handle-delete
                         :tooltip-placement "top-left"
                         :icon i/remove}]]]

     (when show-more-options
       [:div {:class (stl/css :second-row)}
        [:> numeric-input*
         {:class (stl/css :numeric-input)
          :placeholder "--"
          :min 0
          :text-icon "value"
          :on-change handle-change
          :name "blur-value"
          :value (:value value)}]
        ;; Figma-parity per-blur blend mode (gap #9).
        [:> select*
         {:class (stl/css :blur-blend-mode-select)
          :default-selected (d/name blur-blend-mode)
          :options blend-mode-options
          :disabled is-hidden
          :on-change handle-blend-mode-change}]

        ;; Figma-parity progressive blur (gap #60). Toggle + falloff params.
        ;; Rendered only when the more-options panel is open; default off =
        ;; today's uniform blur. Renderer falloff deferred.
        [:div {:class (stl/css :blur-progressive-row)
               :data-testid "blur.progressive-options"}
         [:label {:class (stl/css :blur-progressive-toggle)}
          [:input {:type "checkbox"
                   :checked progressive?
                   :on-change handle-toggle-progressive}]
          [:span {:class (stl/css :blur-progressive-label)}
           (tr "workspace.options.blur-options.progressive")]]
         (when progressive?
           [:div {:class (stl/css :blur-progressive-params)}
            [:> numeric-input*
             {:class (stl/css :numeric-input)
              :placeholder "--"
              :min 0
              :text-icon "value"
              :on-change handle-change-start-radius
              :name "blur-start-radius"
              :value (:start-radius value)}]
            [:> numeric-input*
             {:class (stl/css :numeric-input)
              :placeholder "--"
              :min 0
              :text-icon "value"
              :on-change handle-change-start-offset
              :name "blur-start-offset"
              :value (:start-offset value)}]
            [:> numeric-input*
             {:class (stl/css :numeric-input)
              :placeholder "--"
              :min 0
              :text-icon "value"
              :on-change handle-change-end-offset
              :name "blur-end-offset"
              :value (:end-offset value)}]])]])]))

(defn get-blurs [values]
  (cond-> []
    (:blur values)
    (conj {:key :blur
           :value (:blur values)})

    (:background-blur values)
    (conj {:key :background-blur
           :value (:background-blur values)})))

(defn- check-blur-menu-props
  [old-props new-props]
  (let [old-values (unchecked-get old-props "values")
        new-values (unchecked-get new-props "values")]
    (and (identical? (unchecked-get old-props "ids")
                     (unchecked-get new-props "ids"))
         (identical? (unchecked-get old-props "type")
                     (unchecked-get new-props "type"))
         (identical? (get old-values :blur)
                     (get new-values :blur))
         (identical? (get old-values :background-blur)
                     (get new-values :background-blur)))))

(mf/defc blur-menu*
  {::mf/wrap [#(mf/memo' % check-blur-menu-props)]}
  [{:keys [ids type values]}]
  (let [render-wasm?        (features/use-feature "render-wasm/v1")
        bg-blur?            (and render-wasm?
                                 (contains? cf/flags :background-blur))

        blur-values          (get-blurs values)

        mixed-state (and (or (= :group type)
                             (= :multiple type))
                         (boolean
                          (some #(= :multiple (:value %)) blur-values)))

        state*         (mf/use-state {:show-content true})
        state          (deref state*)
        open?          (:show-content state)

        toggle-content (mf/use-fn #(swap! state* update :show-content not))

        change!
        (mf/use-fn
         (mf/deps ids)
         (fn [update-fn]
           (st/emit! (dwsh/update-shapes ids update-fn)
                     (udw/trigger-bounding-box-cloaking ids))))

        handle-delete-all
        (mf/use-fn
         (mf/deps change!)
         (fn []
           (change! #(dissoc % :blur :background-blur))))

        handle-add
        (mf/use-fn
         (mf/deps change! blur-values)
         (fn []
           (cond
             (= 1 (count blur-values))
             (let [existing-key (:key (first blur-values))
                   new-key      (if (= existing-key :blur)
                                  :background-blur
                                  :blur)]
               (change! #(assoc % new-key (create-blur (if (= :blur new-key)
                                                         :layer-blur
                                                         :background-blur)))))
             (= 0 (count blur-values))
             (change! #(assoc % :blur (create-blur :layer-blur))))
           :else
           blur-values))

        ;; Figma-parity stacked layer blurs (gap #74). The :blurs vector on
        ;; the shape is opaque (see shape.cljc). These handlers add / remove
        ;; / reorder / edit entries in that vector via dwsh/update-shapes.
        ;; Rendered ONLY when (seq :blurs) is true; absent :blurs = the
        ;; single-blur UI above is the whole UI (byte-identical). Renderer
        ;; multi-blur compositing is deferred (significant GPU work).
        stack-blurs (seq (:blurs values))

        handle-add-stack-blur
        (mf/use-fn
         (mf/deps change!)
         (fn []
           (change! #(update % :blurs (fn [b] (conj (vec b) (create-stack-blur)))))))

        handle-remove-stack-blur
        (mf/use-fn
         (mf/deps change!)
         (fn [index]
           (change! #(update % :blurs (fn [b] (vec (keep-indexed (fn [i x] (when (not= i index) x)) b)))))))

        handle-reorder-stack-blur
        (mf/use-fn
         (mf/deps change!)
         (fn [from to]
           (change! #(update % :blurs (fn [b]
                                        (let [b (vec b)
                                              item (nth b from)
                                              b (vec (keep-indexed (fn [i x] (when (not= i from) x)) b))
                                              b (into (subvec b 0 to) (conj (subvec b to) item))]
                                          b))))))

        handle-toggle-stack-blur-visibility
        (mf/use-fn
         (mf/deps change!)
         (fn [index]
           (change! #(update-in % [:blurs index :hidden] (fn [v] (not (boolean v)))))))

        handle-change-stack-blur-value
        (mf/use-fn
         (mf/deps change!)
         (fn [index value]
           (change! #(assoc-in % [:blurs index :value] value))))]

    [:section {:class (stl/css :element-set)
               :hidden (not open?)
               :aria-label (if bg-blur?
                             (tr "labels.blur-effects")
                             (tr "labels.blur"))}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable  (seq blur-values)
                      :collapsed    (not open?)
                      :on-collapsed toggle-content
                      :aria-expanded open?
                      :aria-controls "blur-content"
                      :title        (if bg-blur?
                                      (cond
                                        (= type :multiple) (tr "workspace.options.blur-effects-options.title.multiple")
                                        (= type :group) (tr "workspace.options.blur-effects-options.title.group")
                                        :else (tr "labels.blur-effects"))
                                      (cond
                                        (= type :multiple) (tr "workspace.options.blur-options.title.multiple")
                                        (= type :group) (tr "workspace.options.blur-options.title.group")
                                        :else (tr "labels.blur")))
                      :class        (stl/css-case :title-spacing-blur (not (seq blur-values))
                                                  :long-title true)}
       (when (and (not mixed-state)
                  (if bg-blur?
                    (< (count blur-values) 2)
                    (nil? (:blur values))))
         [:> icon-button*
          {:variant "ghost"
           :aria-label (tr "workspace.options.blur-options.add-blur")
           :on-click handle-add
           :icon i/add
           :tooltip-placement "top-left"
           :data-testid "add-blur"}])]]
     (when (and open? (seq blur-values))
       [:div {:class (stl/css :element-set-content)
              :hidden (not open?)
              :id "blur-content"}
        (if mixed-state
          [:div  {:class (stl/css :first-row)}
           [:span {:class (stl/css :mixed-label)}
            (tr "labels.mixed-values")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.blur-options.remove-blur")
                             :on-click handle-delete-all
                             :tooltip-placement "top-left"
                             :icon i/remove}]]

          (for [{:keys [key value]} blur-values]
            [:> blur-menu-content*
             {:key key
              :blur-key key
              :value value
              :blur-values blur-values
              :change-fn change!}]))])
     ;; Figma-parity stacked layer blurs (gap #74). Stack manager UI
     ;; (add / remove / reorder / edit) for the opaque :blurs vector on
     ;; the shape. Renders ONLY when (seq :blurs) is true; absent :blurs =
     ;; the single-blur UI above is the whole UI (byte-identical). Renderer
     ;; multi-blur compositing is deferred (significant GPU work).
     (when (and open? stack-blurs)
       [:section {:class (stl/css :element-set)
                  :aria-label (tr "workspace.options.blur-options.stack-title")
                  :data-testid "blur.stack"}
        [:div {:class (stl/css :element-title)}
         [:> title-bar* {:collapsable true
                         :collapsed false
                         :title (tr "workspace.options.blur-options.stack-title")
                         :class (stl/css :long-title)}
          [:> icon-button* {:variant "ghost"
                            :aria-label (tr "workspace.options.blur-options.add-stack-blur")
                            :on-click handle-add-stack-blur
                            :icon i/add
                            :tooltip-placement "top-left"
                            :data-testid "add-stack-blur"}]]]
        [:div {:class (stl/css :element-set-content)}
         (for [index (range (count (:blurs values)))]
           (let [blur (nth (:blurs values) index)
                 is-hidden (:hidden blur)]
             [:div {:key (str "stack-blur-" index)
                    :class (stl/css-case :first-row true :hidden is-hidden)}
              [:> icon-button* {:variant "ghost"
                                :aria-label (tr "workspace.options.blur-options.move-stack-blur-up")
                                :on-click #(handle-reorder-stack-blur index (max 0 (dec index)))
                                :disabled (= index 0)
                                :icon i/arrow-up}]
              [:> icon-button* {:variant "ghost"
                                :aria-label (tr "workspace.options.blur-options.move-stack-blur-down")
                                :on-click #(handle-reorder-stack-blur index (min (dec (count (:blurs values))) (inc index)))
                                :disabled (= index (dec (count (:blurs values))))
                                :icon i/arrow-down}]
              [:> numeric-input*
               {:class (stl/css :numeric-input)
                :placeholder "--"
                :min 0
                :text-icon "value"
                :on-change #(handle-change-stack-blur-value index %)
                :name (str "stack-blur-value-" index)
                :value (:value blur)}]
              [:div {:class (stl/css :actions)}
               [:> icon-button* {:variant "ghost"
                                 :aria-label (tr "workspace.options.blur-options.toggle-blur")
                                 :on-click #(handle-toggle-stack-blur-visibility index)
                                 :icon (if is-hidden i/hide i/shown)}]
               [:> icon-button* {:variant "ghost"
                                 :aria-label (tr "workspace.options.blur-options.remove-blur")
                                 :on-click #(handle-remove-stack-blur index)
                                 :tooltip-placement "top-left"
                                 :icon i/remove}]]]))]])]))