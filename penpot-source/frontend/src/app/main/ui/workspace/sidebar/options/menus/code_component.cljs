;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.code-component
  "P0.14 — Code-component inspector menu (ALL_APPS_PARITY).

  Authors the `:ovion \"code-component\"` shape slot + the
  `:ovion \"code-components\"` file registry. Self-hides unless exactly
  one rect/frame is selected (or the selected shape already carries the
  slot). Provides:

    - a dropdown of registered components to pick/apply
    - when a slot is set, a props editor (one row per prop in the schema:
      text for :string, number for :number, checkbox for :boolean,
      color swatch for :color)
    - a 'Register new component' expandable form (name + bundle URL +
      dynamic props-schema rows [key + type + default + label])
    - 'Remove' to clear the slot + 'Unregister' for the registry entry

  Coral accent, Lucide icons (code-2 / boxes / trash-2 / plus),
  reduced-motion safe (no motion emitted)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.code-components :as dcc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Coral + grey accents (Ovion theme) — matches the video/effects menus.
(def ^:private coral "#f28b82")
(def ^:private grey  "#7d7d7d")

(def ^:private row-style
  {:display "flex" :align-items "center" :gap "8px" :width "100%"})

(def ^:private label-style
  {:font-size "11px" :color grey :width "72px" :flex-shrink "0"})

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

(def ^:private ghost-btn-style
  (merge coral-btn-style
         {:border "1px solid rgba(125,125,125,0.3)"
          :background "transparent"
          :color grey}))

(def ^:private select-style
  {:flex "1"
   :height "26px"
   :border "1px solid rgba(125,125,125,0.3)"
   :border-radius "6px"
   :background "transparent"
   :color "inherit"
   :font-size "11px"
   :padding "0 6px"})

(defn- slug-kw
  "Make a lowercase kebab-case keyword from a display name (e.g. 'My Button'
  -> :my-button). Used to derive a registry id from the component name."
  [name-str]
  (let [s (str name-str)
        s (cstr/lower-case s)
        s (cstr/replace s #"[^a-z0-9]+" "-")
        s (cstr/trim s "-")
        s (if (empty? s) "component" s)]
    (keyword s)))

;; --- Lucide icons (lucide.dev, MIT) — 24x24, stroke currentColor, sw 2 ---

(defn- icon-code-2
  []
  [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
         :stroke coral :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round"
         :style {:flex-shrink "0"}}
   [:path {:d "m18 16 4-4-4-4"}]
   [:path {:d "m6 8-4 4 4 4"}]
   [:path {:d "m14.5 4-5 16"}]])

(defn- icon-boxes
  []
  [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
         :stroke coral :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round"
         :style {:flex-shrink "0"}}
   [:path {:d "M2.97 12.92A2 2 0 0 0 2 14.63v3.24a2 2 0 0 0 .97 1.71l3 1.8a2 2 0 0 0 2.06 0L12 19v-5.5l-4.97 2.9"}]
   [:path {:d "m7 16-4-2.5"}]
   [:path {:d "m9.5 7.5 5 3"}]
   [:path {:d "M21.03 12.92A2 2 0 0 1 22 14.63v3.24a2 2 0 0 1-.97 1.71l-3 1.8a2 2 0 0 1-2.06 0L12 19v-5.5l4.97 2.9"}]
   [:path {:d "m17 16 4-2.5"}]
   [:path {:d "M7.97 4.92A2 2 0 0 0 7 6.63v4.24a2 2 0 0 0 .97 1.71l3 1.8a2 2 0 0 0 2.06 0l3-1.8a2 2 0 0 0 .97-1.71V6.63a2 2 0 0 0-.97-1.71l-3-1.8a2 2 0 0 0-2.06 0Z"}]
   [:path {:d "m11.5 10 5-3"}]])

(defn- icon-trash
  []
  [:svg {:width 14 :height 14 :viewBox "0 0 24 24" :fill "none"
         :stroke coral :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round"
         :style {:flex-shrink "0"}}
   [:path {:d "M3 6h18"}]
   [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
   [:line {:x1 10 :y1 11 :x2 10 :y2 17}]
   [:line {:x1 14 :y1 11 :x2 14 :y2 17}]])

(defn- icon-plus
  []
  [:svg {:width 12 :height 12 :viewBox "0 0 24 24" :fill "none"
         :stroke coral :stroke-width 2 :stroke-linecap "round"
         :stroke-linejoin "round"
         :style {:flex-shrink "0"}}
   [:path {:d "M5 12h14"}]
   [:path {:d "M12 5v14"}]])

;; --- Derived refs -----------------------------------------------------------

(defn- registry-ref
  "Derived ref over the workspace file-data registry, so the menu re-renders
  when a component is registered/unregistered."
  []
  (l/derived
   (fn [file-data]
     (dcc/read-registry file-data))
   refs/workspace-data
   =))

(defn- slot-ref
  "Derived ref that reads the code-component slot for `shape-id` from the
  workspace page objects, so the menu re-renders when the slot changes."
  [shape-id]
  (l/derived
   (fn [page]
     (let [shape (get-in page [:objects shape-id])]
       (dcc/read-slot shape)))
   refs/workspace-page
   =))

;; --- Small presentational row helpers --------------------------------------

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

(mf/defc number-row*
  [{:keys [label value on-change]}]
  [:div {:style row-style}
   [:span {:style label-style} label]
   [:input {:type "number"
            :value (or value "")
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

(mf/defc color-row*
  [{:keys [label value on-change]}]
  [:div {:style row-style}
   [:span {:style label-style} label]
   [:input {:type "color"
            :value (or value "#f28b82")
            :style {:width "32px" :height "26px" :flex-shrink "0"
                    :border "1px solid rgba(125,125,125,0.3)"
                    :border-radius "4px" :cursor "pointer" :background "transparent"
                    :accent-color coral}
            :on-change on-change}]
   [:input {:type "text"
            :value (or value "")
            :placeholder "#f28b82"
            :class (stl/css :type-input)
            :style {:flex "1"}
            :on-change on-change}]])

;; --- Props editor -----------------------------------------------------------
;;
;; One row per prop in the component's props-schema. Edits are local state
;; and committed to the slot on each change via apply-to-shape-event.

(mf/defc props-editor*
  [{:keys [shape-id slot schema]}]
  (let [props (or (:props slot) {})]
    (when (and (map? schema) (seq schema))
      [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                     :padding "6px 0"}}
       (for [[prop-kw prop-def] schema]
         (let [ptype   (:type prop-def :string)
               default (:default prop-def)
               label   (or (:label prop-def) (name prop-kw))
               value   (get props prop-kw default)
               on-change
               (mf/use-fn
                (mf/deps shape-id prop-kw slot)
                (fn [e]
                  (let [new-props
                        (case ptype
                          :boolean
                          (assoc props prop-kw (.. e -target -checked))
                          :number
                          (let [raw (.. e -target -value)]
                            (assoc props prop-kw
                                   (if (or (nil? raw) (empty? raw))
                                     nil
                                     (let [n (js/Number raw)]
                                       (if (js/isNaN n) raw n)))))
                          :color
                          (assoc props prop-kw (.. e -target -value))
                          ;; :string default
                          (assoc props prop-kw (.. e -target -value)))]
                    (st/emit! (dcc/apply-to-shape-event
                               shape-id (:id slot) new-props)))))
               row
               (case ptype
                 :number
                 [:> number-row*
                  {:label label :value value :on-change on-change}]
                 :boolean
                 [:> check-row*
                  {:label label :checked (boolean value) :on-change on-change}]
                 :color
                 [:> color-row*
                  {:label label :value value :on-change on-change}]
                 ;; :string default
                 [:> text-row*
                  {:label label :value value :on-change on-change}])]
           (with-meta row {:key (str prop-kw)})))])))

;; --- Register new component form --------------------------------------------

(def ^:private prop-types [:string :number :boolean :color])

(mf/defc register-form*
  [{:keys [on-register]}]
  (let [name*        (mf/use-state "")
        bundle-url*  (mf/use-state "")
        ;; schema rows: vector of {:key "" :type :string :default "" :label ""}
        rows*        (mf/use-state [])
        expanded*    (mf/use-state false)]
    (let [expanded @expanded*
          on-toggle (mf/use-fn (mf/deps expanded) #(reset! expanded* (not expanded)))
          on-name   (mf/use-fn #(reset! name* (.. % -target -value)))
          on-url    (mf/use-fn #(reset! bundle-url* (.. % -target -value)))

          add-row
          (mf/use-fn
           #(swap! rows* conj {:key "" :type :string :default "" :label ""}))

          remove-row
          (mf/use-fn
           (mf/deps @rows*)
           (fn [idx]
             (fn [_]
               (swap! rows* (fn [rows] (into [] (keep-indexed #(when (not= %1 idx) %2) rows)))))))

          update-row
          (mf/use-fn
           (mf/deps @rows*)
           (fn [idx field]
             (fn [e]
               (let [v (if (= field :type)
                         (keyword (.. e -target -value))
                         (.. e -target -value))]
                 (swap! rows*
                        (fn [rows]
                          (mapv (fn [i r]
                                  (if (= i idx)
                                    (assoc r field v)
                                    r))
                                (range)
                                rows)))))))

          on-submit
          (mf/use-fn
           (mf/deps @name* @bundle-url* @rows*)
           (fn []
             (let [id-kw    (slug-kw @name*)
                   schema   (into {}
                                  (keep (fn [r]
                                          (let [rk (cstr/trim (str (:key r)))]
                                            (when (not (empty? rk))
                                              [(keyword rk)
                                               {:type (:type r :string)
                                                :default (:default r)
                                                :label (or (:label r) rk)}])))
                                  @rows*))
                   has-name (not (empty? (cstr/trim (str @name*))))
                   has-url  (not (empty? (cstr/trim (str @bundle-url*))))]
               (when (and has-name has-url)
                 (on-register id-kw @name* @bundle-url* schema)
                 (reset! name* "")
                 (reset! bundle-url* "")
                 (reset! rows* [])
                 (reset! expanded* false)))))]
      [:div {:style {:display "flex" :flex-direction "column" :gap "6px"
                     :padding "6px 0"}}
       [:div {:style row-style}
        [:button {:type "button" :style ghost-btn-style :on-click on-toggle}
         (icon-plus)
         [:span {:style {:margin-left "4px"}}
          (tr "workspace.options.code-component.register-new")]]]
       (when expanded
         [:*
          [:> text-row*
           {:label (tr "workspace.options.code-component.name")
            :value @name*
            :placeholder "Button"
            :on-change on-name}]
          [:> text-row*
           {:label (tr "workspace.options.code-component.bundle-url")
            :value @bundle-url*
            :placeholder "https://…/bundle.js"
            :on-change on-url}]
          [:div {:style {:font-size "11px" :color grey :padding "2px 0"}}
           (tr "workspace.options.code-component.props-schema")]
          (doall
           (for [idx (range (count @rows*))]
             (let [r (nth @rows* idx)]
               ^{:key (str "sch-" idx)}
               [:div {:style (merge row-style {:gap "4px"})}
                [:input {:type "text"
                         :value (:key r)
                         :placeholder "key"
                         :class (stl/css :type-input)
                         :style {:width "60px" :flex "0 0 60px"}
                         :on-change (update-row idx :key)}]
                [:select {:value (name (:type r :string))
                          :style (merge select-style {:flex "0 0 76px"})
                          :on-change (update-row idx :type)}
                 (for [pt prop-types]
                   ^{:key (str "pt-" pt)}
                   [:option {:value (name pt)} (name pt)])]
                [:input {:type "text"
                         :value (:default r)
                         :placeholder "default"
                         :class (stl/css :type-input)
                         :style {:flex "1"}
                         :on-change (update-row idx :default)}]
                [:input {:type "text"
                         :value (:label r)
                         :placeholder "label"
                         :class (stl/css :type-input)
                         :style {:flex "1"}
                         :on-change (update-row idx :label)}]
                [:button {:type "button" :style (merge ghost-btn-style
                                                       {:padding "0 6px"})
                          :title (tr "workspace.options.code-component.remove-prop")
                          :on-click (remove-row idx)}
                 (icon-trash)]])))
          [:div {:style row-style}
           [:button {:type "button" :style ghost-btn-style :on-click add-row}
            (icon-plus)
            [:span {:style {:margin-left "4px"}}
             (tr "workspace.options.code-component.add-prop")]]
           [:button {:type "button" :style (merge coral-btn-style {:margin-left "auto"})
                     :on-click on-submit}
            (tr "workspace.options.code-component.register")]]])])))

;; --- Main menu --------------------------------------------------------------

(mf/defc code-component-menu*
  "Inspector menu for the code-component slot + registry. `shapes` is the
  vector of currently selected shapes (one expected; the first is authored).
  Self-hides (returns nil) unless exactly one shape is selected AND it is a
  rect/frame (or already carries the slot, so it can be edited/cleared)."
  [{:keys [shapes]}]
  (let [shape    (first shapes)
        shape-id (:id shape)
        stype    (:type shape)
        ;; Show for rect/frame (the carriers) or any shape that already has
        ;; the slot (so it can be edited/cleared on whatever carries it).
        slot     (mf/deref (slot-ref shape-id))
        has-slot (some? slot)
        show?    (and (= 1 (count shapes))
                      (or (= :rect stype) (= :frame stype) has-slot))]
    (when show?
      (let [registry    (mf/deref (registry-ref))
            registry    (or registry {})
            reg-id      (when (map? slot) (:id slot))
            entry       (when (some? reg-id) (get registry reg-id))
            schema      (when (map? entry) (:props-schema entry))

            on-pick
            (mf/use-fn
             (mf/deps shape-id registry)
             (fn [e]
               (let [raw (.. e -target -value)
                     id-kw (if (or (nil? raw) (empty? raw)) nil (keyword raw))
                     picked-entry (when (some? id-kw) (get registry id-kw))
                     picked-schema (when (map? picked-entry) (:props-schema picked-entry))
                     defaults (when (map? picked-schema)
                                (into {}
                                      (map (fn [[k v]] [k (:default v)]))
                                      picked-schema))]
                 (st/emit! (dcc/apply-to-shape-event shape-id id-kw defaults)))))

            on-clear
            (mf/use-fn (mf/deps shape-id)
                       #(st/emit! (dcc/clear-from-shape-event shape-id)))

            on-unregister
            (mf/use-fn
             (mf/deps reg-id)
             (fn []
               (when (some? reg-id)
                 (st/emit! (dcc/unregister-component-event reg-id)))))

            on-register
            (mf/use-fn
             (fn [id name bundle-url schema]
               (st/emit! (dcc/register-component-event id name bundle-url schema))))]

        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-title)}
          [:> title-bar* {:collapsable true :collapsed false
                          :title (tr "workspace.options.code-component.title")}
           [:span {:style {:display "inline-flex" :align-items "center"
                           :margin-left "6px"}}
            (icon-code-2)]]]

         [:div {:class (stl/css :element-set-content)}
          [:div {:class (stl/css :element-group)
                 :style {:display "flex" :flex-direction "column" :gap "8px"}}

           ;; Component dropdown (registered components).
           [:div {:style row-style}
            [:span {:style label-style}
             (tr "workspace.options.code-component.component")]
            [:select {:value (if (some? reg-id) (name reg-id) "")
                      :style select-style
                      :on-change on-pick}
             [:option {:value ""}
              (tr "workspace.options.code-component.none")]
             (for [[id-kw ent] registry]
               ^{:key (str id-kw)}
               [:option {:value (name id-kw)}
                (or (:name ent) (name id-kw))])]]

           ;; Props editor when a slot is set + schema is known.
           (when (and has-slot (map? schema) (seq schema))
             [:> props-editor* {:shape-id shape-id :slot slot :schema schema}])

           ;; Register new component form.
           [:> register-form* {:on-register on-register}]

           ;; Remove slot / unregister entry buttons.
           (when has-slot
             [:div {:style (merge row-style {:justify-content "flex-end"
                                             :padding-top "4px"
                                             :gap "6px"})}
              [:button {:type "button" :style coral-btn-style
                        :title (tr "workspace.options.code-component.remove")
                        :on-click on-clear}
               (icon-trash)
               [:span {:style {:margin-left "4px"}}
                (tr "workspace.options.code-component.remove")]]
              (when (some? reg-id)
                [:button {:type "button" :style ghost-btn-style
                          :title (tr "workspace.options.code-component.unregister")
                          :on-click on-unregister}
                 (icon-boxes)
                 [:span {:style {:margin-left "4px"}}
                  (tr "workspace.options.code-component.unregister")]])])]]]))))