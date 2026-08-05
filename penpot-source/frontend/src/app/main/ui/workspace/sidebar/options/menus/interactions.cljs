;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.interactions
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.types.page :as ctp]
   [app.common.types.shape-tree :as ctt]
   [app.common.expressions :as cexpr]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.common :as dcm]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- event-type-names
  []
  {:click       (tr "workspace.options.interaction-on-click")
   ;; Figma #33: re-enabled per parity hint (were commented out pending UX research).
   :mouse-over  (tr "workspace.options.interaction-while-hovering")
   :mouse-press (tr "workspace.options.interaction-while-pressing")
   :mouse-enter (tr "workspace.options.interaction-mouse-enter")
   :mouse-leave (tr "workspace.options.interaction-mouse-leave")
   :after-delay (tr "workspace.options.interaction-after-delay")
   ;; Figma #33: keyboard + input-change triggers.
   :key-down    (tr "workspace.options.interaction-on-key-down")
   :on-change   (tr "workspace.options.interaction-on-change")
   ;; C2: reactive + scroll-driven triggers.
   :variable-changed (tr "workspace.options.interaction-trigger-variable-changed")
   :while-scrolling  (tr "workspace.options.interaction-while-scrolling")})

(defn- event-type-name
  [interaction]
  (get (event-type-names) (:event-type interaction) "--"))

(defn- action-summary
  [interaction destination variable-opts]
  (case (:action-type interaction)
    :navigate       (tr "workspace.options.interaction-navigate-to-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    :open-overlay   (tr "workspace.options.interaction-open-overlay-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    :toggle-overlay (tr "workspace.options.interaction-toggle-overlay-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    :close-overlay  (tr "workspace.options.interaction-close-overlay-dest"
                        (get destination :name (tr "workspace.options.interaction-self")))
    :prev-screen    (tr "workspace.options.interaction-prev-screen")
    :open-url       (tr "workspace.options.interaction-open-url")
    ;; Figma #10/#73: new action summaries.
    :change-to      (tr "workspace.options.interaction-change-to")
    :swap-overlay   (tr "workspace.options.interaction-swap-overlay-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    :scroll-to      (tr "workspace.options.interaction-scroll-to-dest"
                        (get destination :name (tr "workspace.options.interaction-none")))
    ;; C2: variable / conditional / style / error-state / scroll-animate summaries.
    :set-variable       (let [vid (:variable-id interaction)
                              opt (when (some? vid)
                                    (some #(when (= (:value %) (str vid)) %)
                                          variable-opts))]
                        (tr "workspace.options.interaction-set-variable-summary"
                            (or (:label opt)
                                (tr "workspace.options.interaction-none"))))
    :set-variable-mode  (tr "workspace.options.interaction-set-variable-summary"
                            (or (:mode-name interaction) ""))
    :conditional        (tr "workspace.options.interaction-conditional-summary")
    :set-style          (tr "workspace.options.interaction-set-style-summary"
                            (d/name (:property interaction :fill)))
    :set-error-state    (tr "workspace.options.interaction-set-error-state-summary")
    :scroll-animate     (tr "workspace.options.interaction-scroll-animate-summary")
    "--"))

(defn- get-frames-options
  [frames shape]
  (->> frames
       (filter #(and (not= (:id %) (:id shape)) ; A frame cannot navigate to itself
                     (not= (:id %) (:frame-id shape)))) ; nor a shape to its container frame
       (map (fn [frame]
              {:value (str (:id frame))
               :label (:name frame)}))))

(defn- get-shared-frames-options
  [shared-frames]
  (map (fn [frame]
         {:value (str (:id frame))
          :label (:name frame)}) shared-frames))

(mf/defc prototype-pill*
  [{:keys [title description on-change is-editable
           left-button-icon-id left-button-tooltip on-left-button-click is-left-button-active
           right-button-icon-id right-button-tooltip on-right-button-click is-right-button-active]} external-ref]
  (let [local-ref (mf/use-ref)
        ref       (or external-ref local-ref)

        handle-focus
        (mf/use-fn
         (fn []
           (let [input-node (mf/ref-val ref)]
             (dom/select-text! input-node))))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (let [input-node (mf/ref-val ref)
                 target     (dom/get-target event)
                 value      (-> target dom/get-value str/trim)]
             (when (or (kbd/esc? event) (kbd/enter? event))
               (dom/blur! input-node)
               (on-change value)))))

        handle-blur
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-target event)
                 value  (-> target dom/get-value str/trim)]
             (on-change value))))]

    [:div {:class (stl/css-case :prototype-pill true
                                :double (some? description))}
     [:> icon-button* {:variant "secondary"
                       :class (stl/css :prototype-pill-button :left)
                       :aria-pressed is-left-button-active
                       :icon left-button-icon-id
                       :aria-label left-button-tooltip
                       :on-click on-left-button-click}]

     [:div {:class (stl/css :prototype-pill-main)}
      (if is-editable
        [:input {:type "text"
                 :class (stl/css :prototype-pill-input)
                 :ref ref
                 :default-value title
                 :on-focus handle-focus
                 :on-key-down handle-key-down
                 :on-blur handle-blur}]

        [:div {:class (stl/css :prototype-pill-center)}
         [:div {:class (stl/css :prototype-pill-info)}
          [:div {:class (stl/css :prototype-pill-name)} title]
          [:div {:class (stl/css :prototype-pill-description)} description]]])

      [:> icon-button* {:variant "secondary"
                        :class (stl/css :prototype-pill-button :right)
                        :aria-pressed is-right-button-active
                        :icon right-button-icon-id
                        :aria-label right-button-tooltip
                        :on-click on-right-button-click}]]]))

(mf/defc flow-item*
  [{:keys [flow]}]
  (let [start-flow
        (mf/use-fn
         (mf/deps flow)
         #(st/emit! (dcm/go-to-viewer {:section "interactions"
                                       :frame-id (:starting-frame flow)})))

        rename-flow
        (mf/use-fn
         (mf/deps flow)
         (fn [value]
           (when-not (str/empty? value)
             (st/emit! (dwi/rename-flow (:id flow) value)))))

        remove-flow
        (mf/use-fn
         (mf/deps flow)
         #(st/emit! (dwi/remove-flow (:id flow))))]

    [:> prototype-pill* {:title (:name flow "")
                         :is-editable true
                         :on-change rename-flow
                         :left-button-icon-id i/play
                         :left-button-tooltip (tr "workspace.options.flows.flow-start")
                         :on-left-button-click start-flow
                         :right-button-icon-id i/remove
                         :right-button-tooltip (tr "labels.remove")
                         :on-right-button-click remove-flow}]))

;; C2 Prototype Logic Runtime — condition builder helpers. Pure fns that
;; decompose a :condition expression node (produced by cexpr/build-condition)
;; into an editable [mode predicates] shape and back. A predicate is a
;; [op lhs rhs] node; an operand is a reference node (["get" name] / ["prop"
;; id prop] / ["error-state" id]) or a literal.
(defn- condition-mode
  [node]
  (cond
    (and (vector? node) (= (first node) "or"))  :any
    (and (vector? node) (= (first node) "and")) :all
    :else :all))

(defn- condition-predicates
  [node]
  (cond
    (and (vector? node) (contains? #{"and" "or"} (first node)))
    (into [] (rest node))

    (vector? node) [node]
    :else []))

(defn- predicate-parts
  [node]
  (if (vector? node)
    [(get node 0 "==") (get node 1) (get node 2)]
    ["==" nil nil]))

(defn- operand-info
  [node]
  (cond
    (and (vector? node) (= (first node) "get"))        {:type :variable    :name (second node)}
    (and (vector? node) (= (first node) "prop"))       {:type :property    :id (second node) :prop (nth node 2 nil)}
    (and (vector? node) (= (first node) "error-state")){:type :error-state :id (second node)}
    :else {:type :literal :value node}))

(defn- operand-node
  [info]
  (case (:type info)
    :variable    ["get" (:name info)]
    :property    ["prop" (:id info) (:prop info)]
    :error-state ["error-state" (:id info)]
    (:literal nil) (:value info)))

;; The action-types allowed inside a conditional's then/else action lists.
(def ^:private effect-action-types
  [:set-variable :set-style :set-error-state :navigate :open-overlay
   :close-overlay :scroll-to :open-url])

(defn- effect-action-options
  []
  (mapv (fn [v]
          {:value v
           :label (tr (str "workspace.options.interaction-"
                           (case v
                             :set-variable     "set-variable"
                             :set-style        "set-style"
                             :set-error-state  "set-error-state"
                             :navigate         "navigate-to"
                             :open-overlay     "open-overlay"
                             :close-overlay    "close-overlay"
                             :scroll-to        "scroll-to"
                             :open-url         "open-url")))})
        effect-action-types))

;; C2: fx expression dialog. A modal opened by an fx icon-button next to a
;; value input. Shows a textarea for the [[...]] expression string, a list of
;; available variables to insert as ${varname}, a live preview of the parsed
;; node via cexpr/parse-expression, and Save / Clear. Saves via the supplied
;; on-save callback (which calls the relevant ctsi/set-*-expression).
(mf/defc fx-dialog*
  {::mf/private true}
  [{:keys [initial variables on-save on-close]}]
  (let [text*     (mf/use-state #(or initial ""))
        text      (deref text*)
        node      (mf/with-memo [text]
                    (cexpr/parse-expression text))
        rendered  (mf/with-memo [node]
                    (try (cexpr/format-expression node)
                         (catch :default _ (str node))))

        handle-change
        (mf/use-fn
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value)]
             (reset! text* value))))

        handle-insert
        (mf/use-fn
         (mf/deps text)
         (fn [var-name]
           (reset! text* (str text "${" var-name "}"))))

        handle-save
        (mf/use-fn
         (mf/deps text on-save on-close)
         (fn [_]
           (on-save text)
           (on-close)))

        handle-clear
        (mf/use-fn
         (mf/deps on-save on-close)
         (fn [_]
           (on-save nil)
           (on-close)))

        handle-backdrop
        (mf/use-fn
         (mf/deps on-close)
         (fn [event]
           (when (identical? (dom/get-current-target event)
                              (dom/get-target event))
             (on-close))))]

    [:div {:class (stl/css :fx-dialog-backdrop)
           :on-click handle-backdrop}
     [:div {:class (stl/css :fx-dialog)
            :role "dialog"
            :aria-label (tr "workspace.options.interaction-fx-edit")}
      [:div {:class (stl/css :fx-dialog-header)}
       [:span {:class (stl/css :fx-dialog-title)}
        (tr "workspace.options.interaction-fx-edit")]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :icon i/close-small
                         :on-click on-close}]]

      [:div {:class (stl/css :fx-dialog-body)}
       [:textarea {:class (stl/css :fx-dialog-textarea)
                   :value text
                   :placeholder (tr "workspace.options.interaction-fx-placeholder")
                   :on-change handle-change}]

       [:div {:class (stl/css :fx-dialog-help)}
        (tr "workspace.options.interaction-fx-help")]

       (when (seq variables)
         [:div {:class (stl/css :fx-dialog-vars)}
          [:div {:class (stl/css :fx-dialog-vars-title)}
           (tr "workspace.options.interaction-fx-variable-ref")]
          (for [var-name variables]
            [:button {:key var-name
                      :type "button"
                      :class (stl/css :fx-dialog-var-chip)
                      :on-click #(handle-insert var-name)}
             (str "${" var-name "}")])])]

      [:div {:class (stl/css :fx-dialog-preview)}
       [:span {:class (stl/css :fx-dialog-preview-label)}
        (tr "workspace.options.interaction-fx-insert-variable")]
       [:code {:class (stl/css :fx-dialog-preview-code)}
        (or rendered "")]]

      [:div {:class (stl/css :fx-dialog-actions)}
       [:button {:type "button"
                 :class (stl/css :fx-dialog-btn :secondary)
                 :on-click handle-clear}
        (tr "workspace.options.interaction-fx-clear")]
       [:button {:type "button"
                 :class (stl/css :fx-dialog-btn :primary)
                 :on-click handle-save}
        (tr "workspace.options.interaction-fx-save")]]]]))

(mf/defc interaction-item*
  [{:keys [index shape interaction update-interaction remove-interaction]}]
  (let [objects              (deref refs/workspace-page-objects)
        destination          (get objects (:destination interaction))

        frames               (mf/with-memo [objects]
                               (ctt/get-viewer-frames objects {:all-frames? true}))
        shape-parent-ids     (mf/with-memo [objects]
                               (cfh/get-parent-ids objects (:id shape)))
        shape-parents        (mf/with-memo [frames shape]
                               (filter (comp (set shape-parent-ids) :id) frames))

        overlay-pos-type     (:overlay-pos-type interaction)
        close-click-outside? (:close-click-outside interaction false)
        background-overlay?  (:background-overlay interaction false)
        preserve-scroll?     (:preserve-scroll interaction false)

        way                  (-> interaction :animation :way)
        direction            (-> interaction :animation :direction)

        open-extended*       (mf/use-state false)
        open-extended?       (deref open-extended*)

        ext-delay-ref        (mf/use-ref nil)
        ext-duration-ref     (mf/use-ref nil)

        toggle-extended
        (mf/use-fn
         #(swap! open-extended* not))

        change-event-type
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-event-type % value shape)))))

        change-action-type
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-action-type % value)))))

        change-delay
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [value]
           (update-interaction index #(ctsi/set-delay % value))))

        change-destination
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value event
                 value (when (not= value "") (uuid/parse value))]
             (update-interaction index #(ctsi/set-destination % value)))))

        change-position-relative-to
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (uuid/parse* event)]
             (update-interaction index #(ctsi/set-position-relative-to % value)))))

        change-preserve-scroll
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-preserve-scroll % value)))))

        change-url
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [target      (dom/get-target event)
                 value       (dom/get-value target)
                 has-prefix? (or (str/starts-with? value "http://")
                                 (str/starts-with? value "https://"))
                 value       (if has-prefix?
                               value
                               (str "http://" value))]
             (when-not has-prefix?
               (dom/set-value! target value))
             (if (dom/valid? target)
               (do
                 (dom/remove-class! target "error")
                 (update-interaction index #(ctsi/set-url % value)))
               (dom/add-class! target "error")))))

        change-overlay-pos-type
        (mf/use-fn
         (mf/deps shape update-interaction)
         (fn [value]
           (let [shape-id (:id shape)]
             (update-interaction index #(ctsi/set-overlay-pos-type % value shape objects))
             (when (= value :manual)
               (update-interaction index #(ctsi/set-position-relative-to % shape-id))))))

        toggle-overlay-pos-type
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [pos-type (-> (dom/get-current-target event)
                              (dom/get-data "value")
                              (keyword))]
             (update-interaction index #(ctsi/toggle-overlay-pos-type % pos-type shape objects)))))

        change-close-click-outside
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-close-click-outside % value)))))

        change-background-overlay
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-background-overlay % value)))))

        change-animation-type
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (if (= "" event)
                         nil
                         (keyword event))]
             (update-interaction index #(ctsi/set-animation-type % value)))))

        change-duration
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [value]
           (update-interaction index #(ctsi/set-duration % value))))

        change-easing
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-easing % value)))))

        change-way
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-way % value)))))

        change-direction
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-direction % value)))))

        change-offset-effect
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-offset-effect % value)))))

        ;; Figma #73: per-interaction enable/disable.
        change-disabled
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-disabled % value)))))

        ;; Figma #33: key-down trigger filter (a key name like "Enter" or "a").
        change-key-code
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value str/trim)]
             (update-interaction index #(ctsi/set-key-code % (when-not (str/empty? value) value))))))

        ;; Figma #34: custom-bezier control points. Each coordinate lives in
        ;; the animation map at [:animation :bezier-ctrl]; updating one
        ;; coordinate leaves the others intact (and creates the map lazily).
        change-bezier-ctrl
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [axis]
           (fn [value]
             (update-interaction
              index
              #(update-in % [:animation :bezier-ctrl] assoc axis value)))))

        ;; Figma #10: change-to target variant id (a UUID string). Props
        ;; authoring (the property-name -> value map) is deferred — the schema
        ;; carries :change-to-props but the per-entry editor is non-trivial UI
        ;; and the runtime swap is also deferred, so v1 only authors the target.
        change-change-to-variant
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value str/trim)
                 value (when-not (str/empty? value) (uuid/parse* value))]
             (update-interaction index #(ctsi/set-change-to-variant % value)))))

        ;; C2: fx dialog state. fx-open* is nil / :variable / :style.
        fx-open*             (mf/use-state nil)
        fx-text*             (mf/use-state "")

        ;; C2: set-variable authoring.
        change-variable-id
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value event
                 value (when (and (some? value) (not= value ""))
                         (uuid/parse* value))]
             (update-interaction index #(ctsi/set-variable-id % value)))))

        change-variable-value
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value)]
             (update-interaction index #(ctsi/set-variable-value % value)))))

        open-fx-variable
        (mf/use-fn
         #(reset! fx-open* :variable))

        ;; C2: set-variable-mode authoring. The collection selector writes
        ;; :collection-id directly (no dedicated ctsi setter for it).
        change-mode-name
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value str/trim)]
             (update-interaction index #(ctsi/set-mode-name % (when-not (str/empty? value) value))))))

        change-collection-id
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value str/trim)
                 value (when-not (str/empty? value)
                         (uuid/parse* value))]
             (update-interaction index #(assoc % :collection-id value)))))

        ;; C2: set-style authoring.
        change-style-target
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-style-target % value)))))

        change-style-target-shape-id
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value event
                 value (when (and (some? value) (not= value ""))
                         (uuid/parse* value))]
             (update-interaction index #(ctsi/set-style-target-shape-id % value)))))

        change-style-property
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-style-property % value)))))

        change-style-value
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value)]
             (update-interaction index #(ctsi/set-style-value % value)))))

        open-fx-style
        (mf/use-fn
         #(reset! fx-open* :style))

        ;; C2: set-error-state authoring.
        change-error-state-target
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-error-state-target % value)))))

        change-error-state-target-shape-id
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value event
                 value (when (and (some? value) (not= value ""))
                         (uuid/parse* value))]
             (update-interaction index #(ctsi/set-error-state-target-shape-id % value)))))

        change-error-state-error
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (update-interaction index #(ctsi/set-error-state-error? % value)))))

        ;; C2: scroll-animate authoring.
        change-scroll-axis
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-scroll-axis % value)))))

        change-scroll-range-start
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [value]
           (update-interaction index #(ctsi/set-scroll-range-start % value))))

        change-scroll-range-end
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [value]
           (update-interaction index #(ctsi/set-scroll-range-end % value))))

        change-scroll-easing
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [event]
           (let [value (keyword event)]
             (update-interaction index #(ctsi/set-scroll-easing % value)))))

        add-keyframe
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [_]
           (update-interaction
            index
            #(ctsi/set-scroll-keyframes
              % (conj (or (:keyframes interaction) [])
                      {:offset 0.5 :props {}})))))

        remove-keyframe
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [idx]
           (update-interaction
            index
            (fn [i]
              (let [v (or (:keyframes i) [])
                    nv (vec (concat (subvec v 0 idx) (subvec v (inc idx))))]
                (ctsi/set-scroll-keyframes i nv))))))

        update-keyframe-offset
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [idx value]
           (update-interaction
            index
            (fn [i]
              (let [v (or (:keyframes i) [])]
                (ctsi/set-scroll-keyframes
                 i (assoc-in v [idx :offset] value)))))))

        update-keyframe-prop
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [idx prop-key value]
           (update-interaction
            index
            (fn [i]
              (let [v (or (:keyframes i) [])]
                (ctsi/set-scroll-keyframes
                 i (assoc-in v [idx :props prop-key] value)))))))

        ;; C2: fx dialog close + save callbacks.
        close-fx
        (mf/use-fn
         (mf/deps fx-open* fx-text*)
         (fn [& _]
           (reset! fx-open* nil)
           (reset! fx-text* "")))

        save-fx-variable
        (mf/use-fn
         (mf/deps index update-interaction close-fx)
         (fn [text]
           (update-interaction
            index
            #(ctsi/set-variable-expression
              % (when (and (some? text) (not (str/empty? text)))
                  (cexpr/parse-expression text))))))

        save-fx-style
        (mf/use-fn
         (mf/deps index update-interaction close-fx)
         (fn [text]
           (update-interaction
            index
            #(ctsi/set-style-expression
              % (when (and (some? text) (not (str/empty? text)))
                  (cexpr/parse-expression text))))))

        ;; C2: condition builder local state, decomposed from :condition.
        cond-mode*           (mf/use-state #(condition-mode (:condition interaction)))
        cond-preds*          (mf/use-state #(condition-predicates (:condition interaction)))

        write-condition!
        (mf/use-fn
         (mf/deps index update-interaction cond-mode* cond-preds*)
         (fn []
           (let [mode  @cond-mode*
                 preds @cond-preds*]
             (update-interaction
              index
              #(ctsi/set-condition % (cexpr/build-condition mode preds))))))

        change-cond-mode
        (mf/use-fn
         (mf/deps cond-mode* write-condition!)
         (fn [event]
           (let [value (keyword event)]
             (reset! cond-mode* value)
             (write-condition!))))

        add-predicate
        (mf/use-fn
         (mf/deps cond-preds* write-condition!)
         (fn [_]
           (reset! cond-preds* (conj @cond-preds* ["==" "" ""]))
           (write-condition!)))

        remove-predicate
        (mf/use-fn
         (mf/deps cond-preds* write-condition!)
         (fn [idx]
           (let [v @cond-preds*
                 nv (vec (concat (subvec v 0 idx) (subvec v (inc idx))))]
             (reset! cond-preds* nv)
             (write-condition!))))

        update-predicate-op
        (mf/use-fn
         (mf/deps cond-preds* write-condition!)
         (fn [idx event]
           (let [op  (if (string? event) event (str event))
                 v   @cond-preds*
                 [_ lhs rhs] (get v idx)
                 nv (assoc v idx [op lhs rhs])]
             (reset! cond-preds* nv)
             (write-condition!))))

        update-predicate-operand
        (mf/use-fn
         (mf/deps cond-preds* write-condition!)
         (fn [idx side info]
           (let [v     @cond-preds*
                 [op lhs rhs] (get v idx)
                 node  (operand-node info)
                 nv    (assoc v idx
                             (case side
                               :lhs [op node rhs]
                               :rhs [op lhs node]))]
             (reset! cond-preds* nv)
             (write-condition!))))

        ;; C2: then / else action list authoring.
        add-then-action
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [_]
           (update-interaction
            index
            #(ctsi/add-then-action % {:action-type :set-variable
                                      :event-type (:event-type interaction :click)}))))

        remove-then-action
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [idx]
           (update-interaction index #(ctsi/remove-then-action % idx))))

        update-then-action
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [idx update-fn]
           (update-interaction index #(ctsi/update-then-action % idx update-fn))))

        add-else-action
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [_]
           (update-interaction
            index
            #(ctsi/add-else-action % {:action-type :set-variable
                                      :event-type (:event-type interaction :click)}))))

        remove-else-action
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [idx]
           (update-interaction index #(ctsi/remove-else-action % idx))))

        update-else-action
        (mf/use-fn
         (mf/deps index update-interaction)
         (fn [idx update-fn]
           (update-interaction index #(ctsi/update-else-action % idx update-fn))))

        change-then-action-type
        (mf/use-fn
         (mf/deps update-then-action)
         (fn [idx event]
           (let [value (keyword event)]
             (update-then-action idx #(ctsi/set-action-type % value)))))

        change-else-action-type
        (mf/use-fn
         (mf/deps update-else-action)
         (fn [idx event]
           (let [value (keyword event)]
             (update-else-action idx #(ctsi/set-action-type % value)))))

        change-then-action-destination
        (mf/use-fn
         (mf/deps update-then-action)
         (fn [idx event]
           (let [value event
                 value (when (and (some? value) (not= value "")) (uuid/parse* value))]
             (update-then-action idx #(ctsi/set-destination % value)))))

        change-else-action-destination
        (mf/use-fn
         (mf/deps update-else-action)
         (fn [idx event]
           (let [value event
                 value (when (and (some? value) (not= value "")) (uuid/parse* value))]
             (update-else-action idx #(ctsi/set-destination % value)))))

        change-then-action-variable
        (mf/use-fn
         (mf/deps update-then-action)
         (fn [idx event]
           (let [value event
                 value (when (and (some? value) (not= value "")) (uuid/parse* value))]
             (update-then-action idx #(ctsi/set-variable-id % value)))))

        change-else-action-variable
        (mf/use-fn
         (mf/deps update-else-action)
         (fn [idx event]
           (let [value event
                 value (when (and (some? value) (not= value "")) (uuid/parse* value))]
             (update-else-action idx #(ctsi/set-variable-id % value)))))

        change-then-action-url
        (mf/use-fn
         (mf/deps update-then-action)
         (fn [idx event]
           (let [value (-> event dom/get-target dom/get-value)]
             (update-then-action idx #(ctsi/set-url % value)))))

        change-else-action-url
        (mf/use-fn
         (mf/deps update-else-action)
         (fn [idx event]
           (let [value (-> event dom/get-target dom/get-value)]
             (update-else-action idx #(ctsi/set-url % value)))))


        event-type-options   (-> [{:value :click       :label (tr "workspace.options.interaction-on-click")}
                                  ;; Figma #33: re-enabled per parity hint (were commented out).
                                  {:value :mouse-over  :label (tr "workspace.options.interaction-while-hovering")}
                                  {:value :mouse-press :label (tr "workspace.options.interaction-while-pressing")}
                                  {:value :mouse-enter :label (tr "workspace.options.interaction-mouse-enter")}
                                  {:value :mouse-leave :label (tr "workspace.options.interaction-mouse-leave")}
                                  ;; Figma #33: keyboard + input-change triggers.
                                  {:value :key-down    :label (tr "workspace.options.interaction-on-key-down")}
                                  {:value :on-change   :label (tr "workspace.options.interaction-on-change")}
                                  ;; C2: reactive + scroll-driven triggers.
                                  {:value :variable-changed :label (tr "workspace.options.interaction-trigger-variable-changed")}
                                  {:value :while-scrolling  :label (tr "workspace.options.interaction-while-scrolling")}]
                                 (cond-> (cfh/frame-shape? shape)
                                   (conj {:value :after-delay :label (tr "workspace.options.interaction-after-delay")})))

        action-type-options [{:value :navigate       :label (tr "workspace.options.interaction-navigate-to")}
                             {:value :open-overlay   :label (tr "workspace.options.interaction-open-overlay")}
                             {:value :toggle-overlay :label (tr "workspace.options.interaction-toggle-overlay")}
                             {:value :close-overlay  :label (tr "workspace.options.interaction-close-overlay")}
                             {:value :prev-screen    :label (tr "workspace.options.interaction-prev-screen")}
                             {:value :open-url       :label (tr "workspace.options.interaction-open-url")}
                             ;; Figma #10/#73: change-to variant, swap overlay, scroll to.
                             {:value :change-to      :label (tr "workspace.options.interaction-change-to")}
                             {:value :swap-overlay  :label (tr "workspace.options.interaction-swap-overlay")}
                             {:value :scroll-to     :label (tr "workspace.options.interaction-scroll-to")}
                             ;; C2: variable / conditional / style / error-state / scroll-animate.
                             {:value :set-variable      :label (tr "workspace.options.interaction-set-variable")}
                             {:value :set-variable-mode :label (tr "workspace.options.interaction-set-variable-mode")}
                             {:value :conditional       :label (tr "workspace.options.interaction-conditional")}
                             {:value :set-style         :label (tr "workspace.options.interaction-set-style")}
                             {:value :set-error-state   :label (tr "workspace.options.interaction-set-error-state")}
                             {:value :scroll-animate    :label (tr "workspace.options.interaction-scroll-animate")}]

        frames-opts         (get-frames-options frames shape)

        default-opts        [(if (= (:action-type interaction) :close-overlay)
                               {:value "" :label (tr "workspace.options.interaction-self")}
                               {:value "" :label (tr "workspace.options.interaction-none")})]
        destination-options
        (mf/with-memo [frames-opts default-opts]
          (let [sorted-frames-opts (sort-by :label frames-opts)]
            (d/concat-vec default-opts sorted-frames-opts)))

        shape-parents-opts (get-shared-frames-options shape-parents)

        relative-to-opts
        (mf/with-memo [shape-parents-opts]
          (if (not= (:overlay-pos-type interaction) :manual)
            (d/concat-vec [{:value "" :label (tr "workspace.options.interaction-auto")}]
                          shape-parents-opts
                          [{:value (str (:id shape)) :label (str (:name shape) " (" (tr "workspace.options.interaction-self") ")")}])
            [{:value (str (:id shape)) :label (str (:name shape) " (" (tr "workspace.options.interaction-self") ")")}]))

        overlay-position-opts [{:value :manual        :label (tr "workspace.options.interaction-pos-manual")}
                               {:value :center        :label (tr "workspace.options.interaction-pos-center")}
                               {:value :top-left      :label (tr "workspace.options.interaction-pos-top-left")}
                               {:value :top-right     :label (tr "workspace.options.interaction-pos-top-right")}
                               {:value :top-center    :label (tr "workspace.options.interaction-pos-top-center")}
                               {:value :bottom-left   :label (tr "workspace.options.interaction-pos-bottom-left")}
                               {:value :bottom-right  :label (tr "workspace.options.interaction-pos-bottom-right")}
                               {:value :bottom-center :label (tr "workspace.options.interaction-pos-bottom-center")}]

        basic-animation-opts [{:value ""        :label (tr "workspace.options.interaction-animation-none")}
                              {:value :dissolve :label (tr "workspace.options.interaction-animation-dissolve")}
                              {:value :slide    :label (tr "workspace.options.interaction-animation-slide")}]

        animation-opts
        (mf/with-memo [basic-animation-opts]
          (cond-> basic-animation-opts
            ;; Figma: push is only available for navigate actions.
            (ctsi/allow-push? (:action-type interaction))
            (d/concat-vec [{:value :push :label (tr "workspace.options.interaction-animation-push")}])

            ;; Figma #11: smart animate is only available for navigate, change-to
            ;; and swap-overlay actions. Runtime v1 falls back to the dissolve
            ;; crossfade in the viewer; matched-layer tweening is deferred.
            (ctsi/allow-smart-animate? (:action-type interaction))
            (d/concat-vec [{:value :smart-animate :label (tr "workspace.options.interaction-animation-smart-animate")}])

            :always
            vec))

        easing-options [{:icon :easing-linear      :value :linear      :label (tr "workspace.options.interaction-easing-linear")}
                        {:icon :easing-ease        :value :ease        :label (tr "workspace.options.interaction-easing-ease")}
                        {:icon :easing-ease-in     :value :ease-in     :label (tr "workspace.options.interaction-easing-ease-in")}
                        {:icon :easing-ease-out    :value :ease-out    :label (tr "workspace.options.interaction-easing-ease-out")}
                        {:icon :easing-ease-in-out :value :ease-in-out :label (tr "workspace.options.interaction-easing-ease-in-out")}
                        ;; Figma #34: custom cubic-bezier easing (4 control points).
                        {:icon :easing-ease-in-out :value :custom-bezier :label (tr "workspace.options.interaction-easing-custom-bezier")}]]

        ;; C2: file variables (tokens) for set-variable + condition operands.
        tokens-map          (mf/deref refs/workspace-all-tokens-map)
        variable-opts
        (mf/with-memo [tokens-map]
          (into [{:value "" :label (tr "workspace.options.interaction-variable-select-placeholder")}]
                (for [[vname token] tokens-map]
                  {:value (str (:id token)) :label (d/name vname)})))

        variable-names
        (mf/with-memo [tokens-map]
          (vec (map d/name (keys tokens-map))))

        ;; C2: every object on the page, for set-style / set-error-state /
        ;; condition property / error-state target selects.
        shape-opts
        (mf/with-memo [objects]
          (->> objects
               (filter (fn [[_ s]] (some? (:name s))))
               (map (fn [[id s]]
                      {:value (str id) :label (str (:name s))}))
               (into [{:value "" :label (tr "workspace.options.interaction-none")}])))

        style-target-opts [{:value :this  :label (tr "workspace.options.interaction-style-target-this")}
                           {:value :by-id :label (tr "workspace.options.interaction-style-target-by-id")}]

        style-property-opts [{:value :fill            :label (tr "workspace.options.interaction-style-fill")}
                             {:value :opacity         :label (tr "workspace.options.interaction-style-opacity")}
                             {:value :border-color    :label (tr "workspace.options.interaction-style-border-color")}
                             {:value :border-width    :label (tr "workspace.options.interaction-style-border-width")}
                             {:value :typography-size :label (tr "workspace.options.interaction-style-typography-size")}
                             {:value :radius          :label (tr "workspace.options.interaction-style-radius")}]

        scroll-axis-opts [{:value :vertical   :label (tr "workspace.options.interaction-scroll-axis-vertical")}
                          {:value :horizontal :label (tr "workspace.options.interaction-scroll-axis-horizontal")}]

        scroll-prop-opts [{:value :translate-y :label (tr "workspace.options.interaction-scroll-prop-translate-y")}
                          {:value :opacity     :label (tr "workspace.options.interaction-scroll-prop-opacity")}
                          {:value :scale       :label (tr "workspace.options.interaction-scroll-prop-scale")}
                          {:value :rotate      :label (tr "workspace.options.interaction-scroll-prop-rotate")}]

        operand-type-opts [{:value :literal     :label (tr "workspace.options.interaction-condition-value")}
                           {:value :variable    :label (tr "workspace.options.interaction-condition-variable")}
                           {:value :property    :label (tr "workspace.options.interaction-condition-property")}
                           {:value :error-state :label (tr "workspace.options.interaction-condition-error-state")}]

        operator-opts [{:value "==" :label "=="}
                       {:value "!=" :label "!="}
                       {:value ">"  :label ">"}
                       {:value "<"  :label "<"}
                       {:value ">=" :label ">="}
                       {:value "<=" :label "<="}]

        effect-options (mf/with-memo [] (effect-action-options))

        ;; C2: render an operand editor for a condition predicate. Plain fn
        ;; (not a hook) — closes over the stable update-predicate-operand +
        ;; option lists above.
        condition-operand-input
        (fn [idx side info]
          (case (:type info)
            :literal
            [:> input* {:type "text"
                        :default-value (some-> (:value info) str)
                        :on-blur #(update-predicate-operand
                                   idx side
                                   (assoc info :value (-> % dom/get-target dom/get-value)))}]

            :variable
            [:& select {:default-value (or (:name info) "")
                        :options variable-opts
                        :on-change #(update-predicate-operand idx side (assoc info :name %))}]

            :property
            [:div {:class (stl/css :condition-operand-prop)}
             [:& select {:default-value (or (str (:id info)) "")
                         :options shape-opts
                         :on-change #(update-predicate-operand
                                      idx side
                                      (assoc info :id (when (and (some? %) (not= % ""))
                                                        (uuid/parse* %))))}]
             [:> input* {:type "text"
                         :placeholder "prop"
                         :default-value (or (str (:prop info)) "")
                         :on-blur #(update-predicate-operand
                                    idx side
                                    (assoc info :prop (-> % dom/get-target dom/get-value)))}]]

            :error-state
            [:& select {:default-value (or (str (:id info)) "")
                        :options shape-opts
                        :on-change #(update-predicate-operand
                                     idx side
                                     (assoc info :id (when (and (some? %) (not= % ""))
                                                       (uuid/parse* %))))}]

            nil))

        ;; C2: render a compact effect-action editor row for a then/else list.
        effect-action-row
        (fn [idx action branch]
          (let [atype    (:action-type action)
                remove-h (if (= branch :then) remove-then-action remove-else-action)
                type-h   (if (= branch :then) change-then-action-type change-else-action-type)
                dest-h   (if (= branch :then) change-then-action-destination change-else-action-destination)
                var-h    (if (= branch :then) change-then-action-variable change-else-action-variable)
                url-h    (if (= branch :then) change-then-action-url change-else-action-url)]
            [:div {:key (str "eff-" (d/name branch) "-" idx)
                   :class (stl/css :effect-action-row)}
             [:& select {:default-value atype
                         :options effect-options
                         :on-change #(type-h idx %)}]
             (case atype
               :set-variable
               [:& select {:default-value (some-> (:variable-id action) str)
                           :options variable-opts
                           :on-change #(var-h idx %)}]
               (:navigate :open-overlay :scroll-to)
               [:& select {:default-value (str (:destination action))
                           :options destination-options
                           :on-change #(dest-h idx %)
                           :searchable? true}]
               :open-url
               [:> input* {:type "url"
                           :placeholder "http://example.com"
                           :default-value (or (:url action) "")
                           :on-blur #(url-h idx %)}]
               nil)
             [:> icon-button* {:variant "ghost"
                               :aria-label (tr "workspace.options.interaction-remove-action")
                               :icon i/remove
                               :on-click #(remove-h idx)}]]))

    [:div {:class (stl/css :interaction-item)}
     [:> prototype-pill* {:title (event-type-name interaction)
                          :description (action-summary interaction destination variable-opts)
                          :left-button-icon-id i/hsva
                          :left-button-tooltip (tr "labels.options")
                          :is-left-button-active open-extended?
                          :on-left-button-click toggle-extended
                          :right-button-icon-id i/remove
                          :right-button-tooltip (tr "labels.remove")
                          :on-right-button-click #(remove-interaction index)}]

     (when open-extended?
       [:*
        ;; Figma #73: enable/disable toggle (kept at the top of the panel so a
        ;; disabled interaction is still fully authored and re-enabled later).
        [:div {:class (stl/css :interaction-row)}
         [:div {:class (stl/css :interaction-row-checkbox)}
          [:> checkbox* {:id (str "disabled-" index)
                         :label (tr "workspace.options.interaction-disabled")
                         :checked (true? (:disabled interaction))
                         :on-change change-disabled}]]]

        ;; Trigger select
        [:div {:class (stl/css :interaction-row)}
         [:label {:class (stl/css :interaction-row-label)}
          [:div {:class (stl/css :interaction-row-name)}
           (tr "workspace.options.interaction-trigger")]]
         [:div {:class (stl/css :interaction-row-select)}
          [:& select {:default-value (:event-type interaction)
                      :options event-type-options
                      :on-change change-event-type}]]]

        ;; Delay
        (when (ctsi/has-delay interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-label)}
            [:div {:class (stl/css :interaction-row-name)}
             (tr "workspace.options.interaction-delay")]]
           [:div {:class (stl/css :interaction-row-input)}
            [:> numeric-input* {:ref ext-delay-ref
                                :icon i/character-m
                                :property (tr "workspace.options.interaction-ms")
                                :on-change change-delay
                                :value (:delay interaction)}]]])

        ;; Figma #33: key-down trigger filter (a key name like "Enter" or "a").
        (when (ctsi/has-key-code? interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-label)}
            [:div {:class (stl/css :interaction-row-name)}
             (tr "workspace.options.interaction-key-code")]]
           [:div {:class (stl/css :interaction-row-input)}
            [:> input* {:type "text"
                        :placeholder (tr "workspace.options.interaction-key-code-placeholder")
                        :default-value (or (:key-code interaction) "")
                        :on-blur change-key-code}]]])

        ;; Action select
        [:div {:class (stl/css :interaction-row)}
         [:div {:class (stl/css :interaction-row-label)}
          [:div {:class (stl/css :interaction-row-name)}
           (tr "workspace.options.interaction-action")]]
         [:div {:class (stl/css :interaction-row-select)}
          [:& select {:default-value (:action-type interaction)
                      :options action-type-options
                      :on-change change-action-type}]]]

        ;; Destination
        (when (ctsi/has-destination interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-label)}
            [:div {:class (stl/css :interaction-row-name)}
             (tr "workspace.options.interaction-destination")]]
           [:div {:class (stl/css :interaction-row-select)}
            [:& select {:default-value (str (:destination interaction))
                        :options destination-options
                        :on-change change-destination
                        :searchable? true
                        :search-placeholder (tr "workspace.options.interaction-destination")}]]])

        ;; Preserve scroll
        (when (ctsi/has-preserve-scroll interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-checkbox)}
            [:> checkbox* {:id (str "preserve-" index)
                           :label (tr "workspace.options.interaction-preserve-scroll")
                           :checked preserve-scroll?
                           :on-change change-preserve-scroll}]]])

        ;; URL
        (when (ctsi/has-url interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-label)}
            [:div {:class (stl/css :interaction-row-name)}
             (tr "workspace.options.interaction-url")]]
           [:div {:class (stl/css :interaction-row-input)}
            [:> input* {:type "url"
                        :placeholder "http://example.com"
                        :default-value (:url interaction)
                        :on-blur change-url}]]])

        ;; Figma #10: change-to variant action. v1 authors the target variant
        ;; id only — the property-name -> value override map (:change-to-props)
        ;; is carried by the schema but its per-entry editor is deferred (and
        ;; the runtime swap is also deferred; see viewer/shapes.cljs).
        (when (ctsi/has-change-to? interaction)
          [:div {:class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-label)}
            [:div {:class (stl/css :interaction-row-name)}
             (tr "workspace.options.interaction-change-to-variant")]]
           [:div {:class (stl/css :interaction-row-input)}
            [:> input* {:type "text"
                        :placeholder (tr "workspace.options.interaction-change-to-variant-placeholder")
                        :default-value (some-> (:change-to-variant-id interaction) str)
                        :on-blur change-change-to-variant}]]])

        ;; C2: set-variable action. Variable select + value input + fx button.
        (when (ctsi/has-set-variable? interaction)
          [:*
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-condition-variable")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:default-value (some-> (:variable-id interaction) str)
                         :options variable-opts
                         :on-change change-variable-id
                         :searchable? true
                         :search-placeholder (tr "workspace.options.interaction-variable-select-placeholder")}]]]
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-style-value")]]
            [:div {:class (stl/css :interaction-row-input)}
             [:div {:class (stl/css :fx-value-row)}
              [:> input* {:type "text"
                          :placeholder (tr "workspace.options.interaction-fx-placeholder")
                          :default-value (some-> (:value interaction) str)
                          :on-blur change-variable-value}]
              [:> icon-button* {:variant "ghost"
                                :class (stl/css :fx-button)
                                :aria-label (tr "workspace.options.interaction-fx-edit")
                                :icon i/effects
                                :on-click open-fx-variable}]]]]])

        ;; C2: set-variable-mode action. Mode-name input (+ optional collection
        ;; id text input). No dedicated collections ref is available in the
        ;; workspace refs, so the collection selector is a plain UUID text input.
        (when (ctsi/has-set-variable-mode? interaction)
          [:*
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-set-variable-mode")]]
            [:div {:class (stl/css :interaction-row-input)}
             [:> input* {:type "text"
                         :placeholder "light / dark"
                         :default-value (or (:mode-name interaction) "")
                         :on-blur change-mode-name}]]]
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-error-state-target")]]
            [:div {:class (stl/css :interaction-row-input)}
             [:> input* {:type "text"
                         :placeholder (tr "workspace.options.interaction-none")
                         :default-value (some-> (:collection-id interaction) str)
                         :on-blur change-collection-id}]]]])

        ;; C2: set-style action. Target + property + value (+ fx).
        (when (ctsi/has-set-style? interaction)
          [:*
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-style-target")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:default-value (or (:target interaction) :this)
                         :options style-target-opts
                         :on-change change-style-target}]]]
           (when (= (:target interaction) :by-id)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-style-target-by-id")]]
              [:div {:class (stl/css :interaction-row-select)}
               [:& select {:default-value (some-> (:target-shape-id interaction) str)
                           :options shape-opts
                           :on-change change-style-target-shape-id
                           :searchable? true}]]])
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-style-property")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:default-value (:property interaction :fill)
                         :options style-property-opts
                         :on-change change-style-property}]]]
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-style-value")]]
            [:div {:class (stl/css :interaction-row-input)}
             [:div {:class (stl/css :fx-value-row)}
              [:> input* {:type "text"
                          :placeholder ""
                          :default-value (some-> (:value interaction) str)
                          :on-blur change-style-value}]
              [:> icon-button* {:variant "ghost"
                                :class (stl/css :fx-button)
                                :aria-label (tr "workspace.options.interaction-fx-edit")
                                :icon i/effects
                                :on-click open-fx-style}]]]]])

        ;; C2: set-error-state action. Target + set/clear toggle.
        (when (ctsi/has-set-error-state? interaction)
          [:*
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-error-state-target")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:default-value (or (:target interaction) :this)
                         :options style-target-opts
                         :on-change change-error-state-target}]]]
           (when (= (:target interaction) :by-id)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-style-target-by-id")]]
              [:div {:class (stl/css :interaction-row-select)}
               [:& select {:default-value (some-> (:target-shape-id interaction) str)
                           :options shape-opts
                           :on-change change-error-state-target-shape-id
                           :searchable? true}]]])
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-checkbox)}
             [:> checkbox* {:id (str "error-state-" index)
                            :label (tr "workspace.options.interaction-error-state-set")
                            :checked (true? (:error? interaction))
                            :on-change change-error-state-error}]]]])

        ;; C2: scroll-animate action. Axis + range + easing + keyframes.
        (when (ctsi/has-scroll-animate? interaction)
          (let [keyframes (or (:keyframes interaction) [])]
            [:*
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-scroll-axis")]]
              [:div {:class (stl/css :interaction-row-select)}
               [:& select {:default-value (or (:axis interaction) :vertical)
                           :options scroll-axis-opts
                           :on-change change-scroll-axis}]]]
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-scroll-range-start")]]
              [:div {:class (stl/css :interaction-row-input)}
               [:> numeric-input* {:on-change change-scroll-range-start
                                   :value (:range-start interaction)}]]]
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-scroll-range-end")]]
              [:div {:class (stl/css :interaction-row-input)}
               [:> numeric-input* {:on-change change-scroll-range-end
                                   :value (:range-end interaction)}]]]
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-scroll-easing")]]
              [:div {:class (stl/css :interaction-row-select)}
               [:& select {:default-value (or (:easing interaction) :linear)
                           :options easing-options
                           :on-change change-scroll-easing}]]]
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-scroll-keyframes")]]
              [:div {:class (stl/css :interaction-row-input)}
               [:div {:class (stl/css :keyframes-list)}
                (for [idx (range (count keyframes))]
                  (let [kf (get keyframes idx)]
                    [:div {:key (str "kf-" idx)
                           :class (stl/css :keyframe-row)}
                     [:> numeric-input* {:placeholder "0..1"
                                         :on-change #(update-keyframe-offset idx %)
                                         :value (:offset kf)}]
                     (for [prop-opt scroll-prop-opts]
                       (let [prop-key (:value prop-opt)]
                         [:> numeric-input* {:key (str "kf-" idx "-" (d/name prop-key))
                                             :placeholder (:label prop-opt)
                                             :on-change #(update-keyframe-prop idx prop-key %)
                                             :value (get (:props kf) prop-key)}]))
                     [:> icon-button* {:variant "ghost"
                                       :aria-label (tr "workspace.options.interaction-scroll-remove-keyframe")
                                       :icon i/remove
                                       :on-click #(remove-keyframe idx)}]]))
                [:> icon-button* {:variant "ghost"
                                  :aria-label (tr "workspace.options.interaction-scroll-add-keyframe")
                                  :icon i/add
                                  :on-click add-keyframe}]]]]]))

        ;; C2: conditional action. Condition builder + then/else action lists.
        (when (ctsi/has-conditional? interaction)
          (let [then-actions (or (:then-actions interaction) [])
                else-actions (or (:else-actions interaction) [])]
            [:*
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-condition")]]
              [:div {:class (stl/css :interaction-row-select)}
               [:div {:class (stl/css :condition-mode-toggle)}
                [:button {:type "button"
                          :class (stl/css :condition-mode-btn
                                          (when (= @cond-mode* :all) :active))
                          :on-click #(change-cond-mode "all")}
                 (tr "workspace.options.interaction-condition-all")]
                [:button {:type "button"
                          :class (stl/css :condition-mode-btn
                                          (when (= @cond-mode* :any) :active))
                          :on-click #(change-cond-mode "any")}
                 (tr "workspace.options.interaction-condition-any")]]]]

             [:div {:class (stl/css :condition-predicates)}
              (for [idx (range (count @cond-preds*))]
                (let [pred      (get @cond-preds* idx)
                      [op lhs rhs] (predicate-parts pred)
                      lhs-info  (operand-info lhs)
                      rhs-info  (operand-info rhs)]
                  [:div {:key (str "pred-" idx)
                         :class (stl/css :condition-predicate-row)}
                   [:& select {:default-value (:type lhs-info :literal)
                               :options operand-type-opts
                               :on-change #(update-predicate-operand
                                            idx :lhs
                                            (assoc lhs-info :type (keyword %)))}]
                   (condition-operand-input idx :lhs lhs-info)
                   [:& select {:default-value op
                               :options operator-opts
                               :on-change #(update-predicate-op idx %)}]
                   [:& select {:default-value (:type rhs-info :literal)
                               :options operand-type-opts
                               :on-change #(update-predicate-operand
                                            idx :rhs
                                            (assoc rhs-info :type (keyword %)))}]
                   (condition-operand-input idx :rhs rhs-info)
                   [:> icon-button* {:variant "ghost"
                                     :aria-label (tr "workspace.options.interaction-condition-remove")
                                     :icon i/remove
                                     :on-click #(remove-predicate idx)}]]))
              [:> icon-button* {:variant "ghost"
                                :aria-label (tr "workspace.options.interaction-condition-add")
                                :icon i/add
                                :on-click add-predicate}]]

             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-then-actions")]]
              [:div {:class (stl/css :interaction-row-input)}
               [:div {:class (stl/css :effect-action-list)}
                (for [idx (range (count then-actions))]
                  (effect-action-row idx (get then-actions idx) :then))
                [:> icon-button* {:variant "ghost"
                                  :aria-label (tr "workspace.options.interaction-add-then-action")
                                  :icon i/add
                                  :on-click add-then-action}]]]]

             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-else-actions")]]
              [:div {:class (stl/css :interaction-row-input)}
               [:div {:class (stl/css :effect-action-list)}
                (for [idx (range (count else-actions))]
                  (effect-action-row idx (get else-actions idx) :else))
                [:> icon-button* {:variant "ghost"
                                  :aria-label (tr "workspace.options.interaction-add-else-action")
                                  :icon i/add
                                  :on-click add-else-action}]]]]]))

        ;; C2: fx expression dialog (rendered above the panel when open).
        (when-let [fx-target @fx-open*]
          (let [expr (:expression interaction)
                initial (if expr
                          (try (cexpr/format-expression expr)
                               (catch :default _ (str expr)))
                          "")]
            [:> fx-dialog* {:initial initial
                            :variables variable-names
                            :on-save (if (= fx-target :variable) save-fx-variable save-fx-style)
                            :on-close close-fx}]))

        (when (ctsi/has-overlay-opts interaction)
          [:*
           ;; Overlay position (select)
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-position")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:default-value (:overlay-pos-type interaction)
                         :options overlay-position-opts
                         :on-change change-overlay-pos-type}]]]

           ;; Overlay position relative-to (select)
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-relative-to")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:default-value  (str (:position-relative-to interaction))
                         :options relative-to-opts
                         :on-change change-position-relative-to}]]]

           ;; Overlay position (buttons)
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-position)}
             [:div {:class (stl/css :center)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :center)
                                :data-value "center"
                                :icon i/corner-center
                                :aria-label (tr "workspace.options.interaction-pos-center")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :top-left)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :top-left)
                                :data-value "top-left"
                                :icon i/corner-top-left
                                :aria-label (tr "workspace.options.interaction-pos-top-left")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :top-right)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :top-right)
                                :data-value "top-right"
                                :icon i/corner-top-right
                                :aria-label (tr "workspace.options.interaction-pos-top-right")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :top-center)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :top-center)
                                :data-value "top-center"
                                :icon i/corner-top
                                :aria-label (tr "workspace.options.interaction-pos-top-center")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :bottom-left)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :bottom-left)
                                :data-value "bottom-left"
                                :icon i/corner-bottom-left
                                :aria-label (tr "workspace.options.interaction-pos-bottom-left")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :bottom-right)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :bottom-right)
                                :data-value "bottom-right"
                                :icon i/corner-bottom-right
                                :aria-label (tr "workspace.options.interaction-pos-bottom-right")
                                :on-click toggle-overlay-pos-type}]]

             [:div {:class (stl/css :bottom-center)}
              [:> icon-button* {:variant "secondary"
                                :aria-pressed (= overlay-pos-type :bottom-center)
                                :data-value "bottom-center"
                                :icon i/corner-bottom
                                :aria-label (tr "workspace.options.interaction-pos-bottom-center")
                                :on-click toggle-overlay-pos-type}]]]]

           ;; Overlay click outside
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-checkbox)}
             [:> checkbox* {:id (str "close-" index)
                            :label (tr "workspace.options.interaction-close-outside")
                            :checked close-click-outside?
                            :on-change change-close-click-outside}]]]

           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-checkbox)}
             [:> checkbox* {:id (str "background-" index)
                            :label (tr "workspace.options.interaction-background")
                            :checked background-overlay?
                            :on-change change-background-overlay}]]]])

        (when (ctsi/has-animation? interaction)
          [:*
           ;; Animation select
           [:div {:class (stl/css :interaction-row)}
            [:div {:class (stl/css :interaction-row-label)}
             [:div {:class (stl/css :interaction-row-name)}
              (tr "workspace.options.interaction-animation")]]
            [:div {:class (stl/css :interaction-row-select)}
             [:& select {:class (stl/css :animation-select)
                         :default-value (or (-> interaction :animation :animation-type) "")
                         :options animation-opts
                         :on-change change-animation-type}]]]

           ;; Direction
           (when (ctsi/has-way? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-radio)}
               [:& radio-buttons {:selected (d/name way)
                                  :on-change change-way
                                  :name "animation-way"}
                [:& radio-button {:value "in"
                                  :id "animation-way-in"}]
                [:& radio-button {:id "animation-way-out"
                                  :value "out"}]]]])

           ;; Direction
           (when (ctsi/has-direction? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-radio)}
               [:& radio-buttons {:selected (d/name direction)
                                  :on-change change-direction
                                  :name "animation-direction"}
                [:& radio-button {:icon i/row
                                  :icon-class (stl/css :right)
                                  :value "right"
                                  :id "animation-right"}]
                [:& radio-button {:icon i/row-reverse
                                  :icon-class (stl/css :left)
                                  :id "animation-left"
                                  :value "left"}]
                [:& radio-button {:icon i/column
                                  :icon-class (stl/css :down)
                                  :id "animation-down"
                                  :value "down"}]
                [:& radio-button {:icon i/column-reverse
                                  :icon-class (stl/css :up)
                                  :id "animation-up"
                                  :value "up"}]]]])

           ;; Duration
           (when (ctsi/has-duration? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-duration")]]
              [:div {:class (stl/css :interaction-row-input)}
               [:> numeric-input* {:ref ext-duration-ref
                                   :icon i/character-m
                                   :property (tr "workspace.options.interaction-ms")
                                   :on-change change-duration
                                   :value (-> interaction :animation :duration)}]]])

           ;; Easing
           (when (ctsi/has-easing? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-label)}
               [:div {:class (stl/css :interaction-row-name)}
                (tr "workspace.options.interaction-easing")]]
              [:div {:class (stl/css :interaction-row-select)}
               [:& select {:class (stl/css :easing-select)
                           :dropdown-class (stl/css :dropdown-upwards)
                           :default-value (-> interaction :animation :easing)
                           :options easing-options
                           :on-change change-easing}]]])

           ;; Figma #34: custom cubic-bezier control points (x1 y1 x2 y2).
           (when (ctsi/has-bezier-ctrl? interaction)
             (let [ctrl (-> interaction :animation :bezier-ctrl)]
               [:div {:class (stl/css :interaction-row)}
                [:div {:class (stl/css :interaction-row-label)}
                 [:div {:class (stl/css :interaction-row-name)}
                  (tr "workspace.options.interaction-bezier-ctrl")]]
                [:div {:class (stl/css :interaction-row-input)}
                 [:> numeric-input* {:placeholder "x1"
                                     :on-change (change-bezier-ctrl :x1)
                                     :value (:x1 ctrl)}]
                 [:> numeric-input* {:placeholder "y1"
                                     :on-change (change-bezier-ctrl :y1)
                                     :value (:y1 ctrl)}]
                 [:> numeric-input* {:placeholder "x2"
                                     :on-change (change-bezier-ctrl :x2)
                                     :value (:x2 ctrl)}]
                 [:> numeric-input* {:placeholder "y2"
                                     :on-change (change-bezier-ctrl :y2)
                                     :value (:y2 ctrl)}]]]))

           ;; Offset effect
           (when (ctsi/has-offset-effect? interaction)
             [:div {:class (stl/css :interaction-row)}
              [:div {:class (stl/css :interaction-row-checkbox)}
               [:> checkbox* {:id (str "offset-effect-" index)
                              :label (tr "workspace.options.interaction-offset-effect")
                              :checked (-> interaction :animation :offset-effect)
                              :on-change change-offset-effect}]]])])])]))

(mf/defc page-flows*
  [{:keys [flows]}]
  (let [show-content* (mf/use-state true)
        show-content? (deref show-content*)

        toggle-content
        (mf/use-fn
         #(swap! show-content* not))]

    [:div {:class (stl/css :section)}
     [:div {:class (stl/css :title)}
      [:> title-bar* {:collapsable  (> (count flows) 0)
                      :collapsed    (not show-content?)
                      :on-collapsed toggle-content
                      :title        (tr "workspace.options.flows.flow-starts")
                      :class        (stl/css :title-bar)}]]
     (when show-content?
       [:div {:class (stl/css :content)}
        (for [[id flow] flows]
          [:> flow-item* {:key id
                          :flow flow}])])]))

;; Figma #72: prototype sections — titled groupings of frames for
;; prototype flows. Edited in-session via dwi/prototype-section events
;; (data/workspace/interactions.cljs); persistence to the page through the
;; changes pipeline is DEFERRED. The canvas band overlay is also DEFERRED
;; (high-blast on viewport.cljs). This panel is purely additive: it renders
;; a new collapsible section below the page flows and never touches existing
;; nodes. Empty by default, so an absent :prototype-sections field changes
;; nothing about the rest of the sidebar.
(mf/defc prototype-section-item*
  {::mf/private true}
  [{:keys [section frames-by-id add-frame-options]}]
  (let [section-id (:id section)
        frame-ids  (:frame-ids section [])

        rename-section
        (mf/use-fn
         (mf/deps section-id)
         (fn [value]
           (when-not (str/empty? value)
             (st/emit! (dwi/rename-prototype-section section-id value)))))

        remove-section
        (mf/use-fn
         (mf/deps section-id)
         #(st/emit! (dwi/remove-prototype-section section-id)))

        add-frame
        (mf/use-fn
         (mf/deps section-id)
         (fn [value]
           (when-not (str/empty? value)
             (let [frame-id (uuid/parse value)]
               (st/emit! (dwi/add-frame-to-prototype-section section-id frame-id))))))

        remove-frame
        (mf/use-fn
         (mf/deps section-id)
         (fn [frame-id]
           (st/emit! (dwi/remove-frame-from-prototype-section section-id frame-id))))]

    [:div {:class (stl/css :prototype-section-item)}
     [:> prototype-pill* {:title (:name section "")
                          :is-editable true
                          :on-change rename-section
                          :right-button-icon-id i/remove
                          :right-button-tooltip (tr "labels.remove")
                          :on-right-button-click remove-section}]
     [:div {:class (stl/css :content)}
      (for [fid frame-ids]
        (let [frame (get frames-by-id fid)]
          [:div {:key (str fid)
                 :class (stl/css :interaction-row)}
           [:div {:class (stl/css :interaction-row-name)}
            (or (:name frame)
                (tr "workspace.options.prototype-sections.unnamed-frame"))]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.remove")
                             :icon i/remove
                             :on-click #(remove-frame fid)}]]))
      (when (seq add-frame-options)
        [:div {:class (stl/css :interaction-row-select)}
         [:& select {:default-value ""
                     :options add-frame-options
                     :on-change add-frame
                     :searchable? true
                     :search-placeholder (tr "workspace.options.prototype-sections.add-frame")}]])]]))

(mf/defc prototype-sections*
  {::mf/private true}
  [{:keys [sections]}]
  (let [show-content* (mf/use-state true)
        show-content? (deref show-content*)

        toggle-content
        (mf/use-fn
         #(swap! show-content* not))

        add-section
        (mf/use-fn
         #(st/emit! (dwi/add-prototype-section)))

        frames       (mf/deref refs/workspace-frames)
        frames-by-id (mf/with-memo [frames]
                       (into {} (map (fn [f] [(:id f) f]) frames)))

        grouped-ids  (mf/with-memo [sections]
                       (set (mapcat #(seq (:frame-ids % [])) sections)))

        add-frame-options
        (mf/with-memo [frames grouped-ids]
          (->> frames
               (remove #(contains? grouped-ids (:id %)))
               (map (fn [f] {:value (str (:id f)) :label (:name f)}))
               (concat [{:value "" :label (tr "workspace.options.prototype-sections.add-frame")}])
               (into [])))]

    [:div {:class (stl/css :section)}
     [:div {:class (stl/css :title)}
      [:> title-bar* {:collapsable  (> (count sections) 0)
                      :collapsed    (not show-content?)
                      :on-collapsed toggle-content
                      :title        (tr "workspace.options.prototype-sections.title")
                      :class        (stl/css :title-bar)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.prototype-sections.add-section")
                         :on-click add-section
                         :icon i/add}]]]
     (when show-content?
       [:div {:class (stl/css :content)}
        (if (seq sections)
          (for [section sections]
            [:> prototype-section-item* {:key (str (:id section))
                                         :section section
                                         :frames-by-id frames-by-id
                                         :add-frame-options add-frame-options}])
          [:div {:class (stl/css :empty)}
           [:> empty-state* {:icon i/interaction
                             :text (tr "workspace.options.prototype-sections.empty")}]])])]))

(mf/defc shape-flows*
  [{:keys [flows shape]}]
  (let [show-content* (mf/use-state true)
        show-content? (deref show-content*)

        flow          (ctp/get-frame-flow flows (:id shape))

        toggle-content
        (mf/use-fn
         #(swap! show-content* not))

        add-flow
        (mf/use-fn
         #(st/emit! (dwi/add-flow-selected-frame)))]

    [:div {:class (stl/css :section)}
     [:div {:class (stl/css :title)}
      [:> title-bar* {:collapsable  (some? flow)
                      :collapsed    (not show-content?)
                      :on-collapsed toggle-content
                      :title        (tr "workspace.options.flows.flow")
                      :class        (stl/css :title-bar)}
       (when (nil? flow)
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.flows.add-flow-start")
                           :on-click add-flow
                           :icon i/add}])]]
     (when (and show-content? (some? flow))
       [:div {:class (stl/css :content)}
        [:> flow-item* {:key (:id flow)
                        :flow flow}]])]))

(mf/defc interactions*
  [{:keys [interactions shape]}]
  (let [show-content* (mf/use-state true)
        show-content? (deref show-content*)

        toggle-content
        (mf/use-fn
         #(swap! show-content* not))

        add-interaction
        (mf/use-fn
         (mf/deps shape)
         #(st/emit! (dwi/add-new-interaction shape)))

        remove-interaction
        (mf/use-fn
         (mf/deps shape)
         (fn [index]
           (st/emit! (dwi/remove-interaction shape index))))

        update-interaction
        (mf/use-fn
         (mf/deps shape)
         (fn [index update-fn]
           (st/emit! (dwi/update-interaction shape index update-fn))))]

    [:div {:class (stl/css :section)}
     [:div {:class (stl/css :title)}
      [:> title-bar* {:collapsable  (> (count interactions) 0)
                      :collapsed    (not show-content?)
                      :on-collapsed toggle-content
                      :title        (tr "workspace.options.interactions")
                      :class        (stl/css :title-bar)}
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "workspace.options.interactions.add-interaction")
                         :on-click add-interaction
                         :icon i/add}]]]

     (when show-content?
       [:div {:class (stl/css :content :content-interactions)}
        (for [[index interaction] (d/enumerate interactions)]
          [:> interaction-item* {:key (str (:id shape) "-" index)
                                 :index index
                                 :shape shape
                                 :interaction interaction
                                 :update-interaction update-interaction
                                 :remove-interaction remove-interaction}])])]))

(mf/defc interactions-menu*
  [{:keys [shape]}]
  (let [interactions  (get shape :interactions [])
        flows         (mf/deref refs/workspace-page-flows)
        ;; Figma #72: prototype sections — additive, in-session. Read from
        ;; the dedicated ref (falls back to the page's :prototype-sections).
        sections      (mf/deref refs/prototype-sections)
        framed-shape? (and shape (not (cfh/unframed-shape? shape)))]

    [:div {:class (stl/css :wrapper)}
     (if shape
       (when (cfh/frame-shape? shape)
         [:> shape-flows* {:flows flows
                           :shape shape}])
       (when flows
         [:> page-flows* {:flows flows}]))

     ;; Figma #72: prototype sections panel. Rendered only when no shape is
     ;; selected (i.e. the page-level flows view is showing), so it groups
     ;; alongside page-flows rather than under a single frame's options.
     (when-not shape
       [:> prototype-sections* {:sections sections}])

     (when framed-shape?
       [:> interactions* {:interactions interactions
                          :shape shape}])

     (when (= (count interactions) 0)
       [:div {:class (stl/css :section)}
        [:div {:class (stl/css :content)}
         [:div {:class (stl/css :empty)}
          (when framed-shape?
            [:> empty-state* {:icon i/add
                              :text (tr "workspace.options.add-interaction")}])
          [:> empty-state* {:icon i/interaction
                            :text (tr "workspace.options.select-a-shape")}]
          [:> empty-state* {:icon i/play
                            :text (tr "workspace.options.use-play-button")}]]]])]))
