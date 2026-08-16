;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.forms
  "ALL_APPS_PARITY P1.23 — Native forms builder menu (frame-level).

  A sidebar panel for marking a frame as a form and configuring its fields
  + Ovion Cloud submission target. Form config is persisted as plugin-data
  on the frame (see `data.workspace.forms`). Fields can be bound to the
  frame's child shapes (the input shapes); a 'Test submit' button fires
  the Tauri `submit_form` command end-to-end so the form is functional in
  the desktop design tool, not just on a published site.

  Coral accent #f28b82 (matches the rest of the Ovion rebrand); grey
  #7d7d7d for secondary text. Lucide icons inlined (viewBox 0 0 24 24,
  stroke-width 2, currentColor). Reduced-motion guarded in forms.scss."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.forms :as dwf]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :as i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Last submit result ref (global, set by form-submit-succeeded/failed) -
;; Kept local to this menu so the shared refs.cljs stays untouched. The
;; events write under `[:forms :last-submit-result]`; this derived lens reads
;; it so the menu re-renders when a test submit resolves.

(def ^:private last-submit-result
  (l/derived (fn [s] (get-in s [:forms :last-submit-result]))
             st/state
             =))

;; --- Palette ---------------------------------------------------------------

(def ^:private coral "#f28b82")
(def ^:private grey "#7d7d7d")

(def ^:private coral-chip
  {:display "inline-flex"
   :align-items "center"
   :gap "6px"
   :padding "2px 8px"
   :border-radius "999px"
   :background "rgba(242,139,130,0.14)"
   :color coral
   :font-size "11px"
   :font-weight 500
   :line-height 1.4})

(def ^:private coral-btn
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :width "26px"
   :height "26px"
   :border-radius "6px"
   :border "1px solid rgba(242,139,130,0.45)"
   :background "rgba(242,139,130,0.08)"
   :color coral
   :cursor "pointer"})

(def ^:private row-style
  {:display "flex"
   :align-items "center"
   :gap "6px"
   :margin-bottom "6px"})

(def ^:private label-style
  {:font-size "11px"
   :color grey
   :margin-bottom "2px"})

(def ^:private input-style
  {:flex "1"
   :min-width "0"})

(def ^:private field-type-label-key
  {:text     "workspace.options.forms.field-type.text"
   :email    "workspace.options.forms.field-type.email"
   :number   "workspace.options.forms.field-type.number"
   :tel      "workspace.options.forms.field-type.tel"
   :textarea "workspace.options.forms.field-type.textarea"
   :select   "workspace.options.forms.field-type.select"
   :checkbox "workspace.options.forms.field-type.checkbox"})

(defn- field-type-label
  [t]
  (tr (get field-type-label-key t :text)))

(def ^:private field-type-options
  (mapv (fn [t] {:value (d/name t) :label t}) dwf/field-types))

;; --- Inline Lucide icons (viewBox 0 0 24 24, stroke-width 2, currentColor) -

(defn- lucide-icon
  "Render an inline Lucide-style SVG icon. `children` is a vector of
  Hiccup children. Uses currentColor so the icon inherits text color."
  [children]
  (hic/el (into [:svg {:viewBox "0 0 24 24"
                       :fill "none"
                       :stroke "currentColor"
                       :stroke-width 2
                       :stroke-linecap "round"
                       :stroke-linejoin "round"
                       :width 16
                       :height 16
                       :style {:flex-shrink 0}}]
                children)))

(defn- icon-form []
  (lucide-icon [[:rect {:x 3 :y 4 :width 18 :height 4 :rx 1}]
                [:rect {:x 3 :y 12 :width 18 :height 8 :rx 1}]]))
(defn- icon-plus []    (lucide-icon [[:path {:d "M12 5v14"}] [:path {:d "M5 12h14"}]]))
(defn- icon-trash []   (lucide-icon [[:path {:d "M3 6h18"}]
                                     [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"}]
                                     [:path {:d "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))
(defn- icon-send []    (lucide-icon [[:path {:d "m22 2-7 20-4-9-9-4Z"}]
                                     [:path {:d "M22 2 11 13"}]]))
(defn- icon-check []   (lucide-icon [[:path {:d "M20 6 9 17l-5-5"}]]))
(defn- icon-alert []    (lucide-icon [[:circle {:cx 12 :cy 12 :r 10}]
                                     [:line {:x1 12 :y1 8 :x2 12 :y2 12}]
                                     [:line {:x1 12 :y1 16 :x2 12.01 :y2 16}]]))

;; --- Field row (sub-component to keep nesting shallow) --------------------

(mf/defc field-row*
  {::mf/wrap [mf/memo]}
  [{:keys [field frame-id child-options on-update on-remove]}]
  (let [fld-id    (:id field)
        name      (:name field)
        fld-type  (:type field)
        required  (:required field)
        shape-id  (:shape-id field)
        test-val  (:test-value field)

        name*     (mf/use-state (or name ""))
        type*     (mf/use-state (d/name (or fld-type :text)))
        req*      (mf/use-state (true? required))
        shape-id* (mf/use-state shape-id)
        test*     (mf/use-state (or test-val ""))

        on-name   (mf/use-fn #(reset! name* (.. % -target -value)))
        on-type   (mf/use-fn #(reset! type* (.. % -target -value)))
        on-req    (mf/use-fn #(reset! req* (.. % -target -checked)))
        on-test   (mf/use-fn #(reset! test* (.. % -target -value)))
        on-shape  (mf/use-fn
                   (fn [e]
                     (let [v (.. e -target -value)
                           id (when (seq v) (uuid/uuid v))]
                       (reset! shape-id* id)
                       (on-update fld-id {:shape-id id}))))]

    ;; Commit name/type/required/test-value on blur so rapid typing does not
    ;; create one undo step per keystroke.
    (let [commit-name (mf/use-fn
                        (mf/deps on-update)
                        #(on-update fld-id {:name (cstr/trim (deref name*))}))
          commit-type (mf/use-fn
                        (mf/deps on-update)
                        #(on-update fld-id {:type (keyword (deref type*))}))
          commit-req  (mf/use-fn
                        (mf/deps on-update)
                        #(on-update fld-id {:required (deref req*)}))
          commit-test (mf/use-fn
                        (mf/deps on-update)
                        #(on-update fld-id {:test-value (deref test*)}))]

      [:div {:class (stl/css :forms-field-row)}
       [:div {:style row-style}
        ;; Field name
        [:div {:style input-style}
         [:div {:style label-style} (tr "workspace.options.forms.field-name")]
         [:input {:type "text"
                  :class (stl/css :type-input)
                  :value (deref name*)
                  :on-change on-name
                  :on-blur commit-name}]]
        ;; Type
        [:div {:style {:width "92px"}}
         [:div {:style label-style} (tr "workspace.options.forms.field-type")]
         [:select {:class (stl/css :type-input)
                   :value (deref type*)
                   :on-change on-type
                   :on-blur commit-type}
          (for [opt field-type-options]
            [:option {:key (:value opt) :value (:value opt)}
             (field-type-label (:label opt))])]]]

       [:div {:style row-style}
        ;; Required checkbox
        [:label {:style {:display "flex" :align-items "center" :gap "5px"
                         :font-size "11px" :color grey :cursor "pointer"}}
         [:input {:type "checkbox"
                  :checked (deref req*)
                  :on-change on-req
                  :on-blur commit-req}]
         (tr "workspace.options.forms.required")]
        ;; Remove
        [:button {:type "button"
                  :style (merge coral-btn {:margin-left "auto"})
                  :title (tr "workspace.options.forms.remove-field")
                  :on-click #(on-remove fld-id)}
         (icon-trash)]]

       ;; Bound shape dropdown
       [:div {:style (merge row-style {:margin-bottom "6px"})}
        [:div {:style input-style}
         [:div {:style label-style} (tr "workspace.options.forms.bound-shape")]
         [:select {:class (stl/css :type-input)
                   :value (if shape-id (str shape-id) "")
                   :on-change on-shape}
          [:option {:value ""} (tr "workspace.options.forms.none")]
          (for [opt child-options]
            [:option {:key (:value opt) :value (:value opt)}
             (:label opt)])]]]

       ;; Test value
       [:div {:style row-style}
        [:div {:style input-style}
         [:div {:style label-style} (tr "workspace.options.forms.test-value")]
         [:input {:type "text"
                  :class (stl/css :type-input)
                  :value (deref test*)
                  :placeholder (tr "workspace.options.forms.test-value-ph")
                  :on-change on-test
                  :on-blur commit-test}]]]])))

;; --- Menu ------------------------------------------------------------------

(mf/defc forms-menu*
  {::mf/wrap [mf/memo]}
  [{:keys [shapes]}]
  (let [selected        (mf/deref refs/selected-shapes)
        frame-id        (or (some-> shapes first :id) (first selected))
        objects        (mf/deref refs/workspace-page-objects)
        frame-shape     (when frame-id (get objects frame-id))
        config         (when frame-shape (dwf/read-form-config frame-shape))
        enabled?       (some? (and frame-shape
                                   (dm/get-in frame-shape
                                              [:plugin-data dwf/forms-namespace
                                               dwf/forms-key])))
        children       (when frame-shape
                        (cfh/get-immediate-children objects frame-id))
        child-options  (when (seq children)
                        (mapv (fn [c]
                                {:value (str (:id c))
                                 :label (or (:name c)
                                            (str (d/name (:type c))
                                                 " " (subs (str (:id c)) 0 6)))})
                              children))

        open?*         (mf/use-state true)
        open?          (deref open?*)
        toggle         (mf/use-fn #(swap! open?* not))

        action         (:action config)
        form-name      (:name action)
        endpoint       (:endpoint action)
        success-msg    (:success-message config)
        error-msg      (:error-message config)

        ;; Mutable local copies of the high-level inputs; committed on blur.
        name*          (mf/use-state (or form-name "form"))
        endpoint*      (mf/use-state (or endpoint ""))
        success*       (mf/use-state (or success-msg ""))
        error*         (mf/use-state (or error-msg ""))
        submitting?*   (mf/use-state false)

        ;; Read the last submit result from the store (set by the events).
        last-result    (mf/deref last-submit-result)

        ;; Clear the submitting spinner whenever a result lands.
        _              (mf/use-effect
                         (mf/deps last-result)
                         (fn []
                           (when last-result
                             (reset! submitting?* false))))]

    (let [enable-form  (mf/use-fn
                         (mf/deps frame-id)
                         (fn []
                           (st/emit! (dwf/set-form-config
                                      {:frame-id frame-id
                                       :config (dwf/empty-form-config)}))))
          disable-form (mf/use-fn
                         (mf/deps frame-id)
                         (fn []
                           (st/emit! (dwf/set-form-config
                                      {:frame-id frame-id :config nil}))))
          on-add-field (mf/use-fn
                         (mf/deps frame-id)
                         (fn []
                           (let [n (inc (count (:fields config)))]
                             (st/emit! (dwf/add-field
                                        {:frame-id frame-id
                                         :field {:name  (str "field" n)
                                                 :label (str "Field " n)}})))))
          on-update    (mf/use-fn
                         (mf/deps frame-id)
                         (fn [fid patch]
                           (st/emit! (dwf/update-field
                                      {:frame-id frame-id
                                       :field-id fid
                                       :patch patch}))))
          on-remove    (mf/use-fn
                         (mf/deps frame-id)
                         (fn [fid]
                           (st/emit! (dwf/remove-field
                                      {:frame-id frame-id :field-id fid}))))
          commit-name  (mf/use-fn
                         (mf/deps frame-id)
                         #(st/emit! (dwf/set-action
                                     {:frame-id frame-id
                                      :action {:name (cstr/trim (deref name*))}})))
          commit-endpoint (mf/use-fn
                            (mf/deps frame-id)
                            #(st/emit! (dwf/set-action
                                        {:frame-id frame-id
                                         :action {:endpoint
                                                   (let [v (cstr/trim (deref endpoint*))]
                                                     (when (seq v) v))}})))
          commit-success (mf/use-fn
                           (mf/deps frame-id)
                           #(st/emit! (dwf/set-messages
                                       {:frame-id frame-id
                                        :messages {:success-message (deref success*)}})))
          commit-error  (mf/use-fn
                          (mf/deps frame-id)
                          #(st/emit! (dwf/set-messages
                                      {:frame-id frame-id
                                       :messages {:error-message (deref error*)}})))
          on-test-submit (mf/use-fn
                           (mf/deps frame-id)
                           (fn []
                             (reset! submitting?* true)
                             (st/emit! (dwf/test-submit-form {:frame-id frame-id}))))]

      (when (seq shapes)
        [:div {:class (stl/css :element-set)}
         [:div {:class (stl/css :element-title)}
          [:> title-bar* {:collapsable true
                          :collapsed (not open?)
                          :on-collapsed toggle
                          :title (tr "workspace.options.forms.section")}
           [:& icon-form]]]

         (when open?
           [:div {:class (stl/css :element-set-content)}

            ;; Enable / disable toggle
            [:div {:style (merge row-style {:justify-content "space-between"})}
             [:span {:style coral-chip}
              (if enabled?
                (tr "workspace.options.forms.enabled")
                (tr "workspace.options.forms.disabled"))]
             [:button {:type "button"
                       :style (merge coral-btn
                                    {:width "auto" :padding "0 10px"
                                     :font-size "11px" :font-weight 500})
                       :on-click (if enabled? disable-form enable-form)}
              (tr (if enabled?
                    "workspace.options.forms.disable"
                    "workspace.options.forms.enable"))]]

            (when enabled?
              [:div {:style {:padding-top "6px"}}

               ;; Fields list
               (if (seq (:fields config))
                 (for [fld (:fields config)]
                   [:& field-row*
                    {:key (str (:id fld))
                     :field fld
                     :frame-id frame-id
                     :child-options child-options
                     :on-update on-update
                     :on-remove on-remove}])
                 [:div {:style {:font-size "11px" :color grey :padding "4px 0"}}
                  (tr "workspace.options.forms.no-fields")])

               ;; Add field
               [:div {:style (merge row-style {:margin-top "4px"})}
                [:button {:type "button"
                          :style (merge coral-btn
                                       {:width "auto" :padding "0 10px"
                                        :font-size "11px" :font-weight 500})
                          :on-click on-add-field}
                 (icon-plus)
                 [:span {:style {:margin-left "4px"}}
                  (tr "workspace.options.forms.add-field")]]]

               ;; Action / endpoint
               [:div {:style (merge row-style {:margin-top "8px" :flex-direction "column"
                                                :align-items "stretch"})}
                [:div {:style label-style} (tr "workspace.options.forms.form-name")]
                [:input {:type "text"
                         :class (stl/css :type-input)
                         :value (deref name*)
                         :placeholder "form"
                         :on-change #(reset! name* (.. % -target -value))
                         :on-blur commit-name}]]
               [:div {:style (merge row-style {:flex-direction "column"
                                                :align-items "stretch"})}
                [:div {:style label-style}
                 (tr "workspace.options.forms.endpoint")]
                [:input {:type "text"
                         :class (stl/css :type-input)
                         :value (deref endpoint*)
                         :placeholder (tr "workspace.options.forms.endpoint-ph")
                         :on-change #(reset! endpoint* (.. % -target -value))
                         :on-blur commit-endpoint}]]
               [:div {:style {:font-size "10px" :color grey
                              :line-height 1.4 :margin-bottom "8px"}}
                (tr "workspace.options.forms.endpoint-hint")]

               ;; Messages
               [:div {:style (merge row-style {:flex-direction "column"
                                                :align-items "stretch"})}
                [:div {:style label-style}
                 (tr "workspace.options.forms.success-message")]
                [:input {:type "text"
                         :class (stl/css :type-input)
                         :value (deref success*)
                         :on-change #(reset! success* (.. % -target -value))
                         :on-blur commit-success}]]
               [:div {:style (merge row-style {:flex-direction "column"
                                                :align-items "stretch"})}
                [:div {:style label-style}
                 (tr "workspace.options.forms.error-message")]
                [:input {:type "text"
                         :class (stl/css :type-input)
                         :value (deref error*)
                         :on-change #(reset! error* (.. % -target -value))
                         :on-blur commit-error}]]

               ;; Test submit
               [:div {:style (merge row-style {:margin-top "8px"})}
                [:button {:type "button"
                          :style (merge coral-btn
                                       {:width "auto" :padding "0 12px"
                                        :font-size "11px" :font-weight 500})
                          :disabled (or (empty? (:fields config))
                                        (deref submitting?*))
                          :on-click on-test-submit}
                 (icon-send)
                 [:span {:style {:margin-left "5px"}}
                  (tr "workspace.options.forms.test-submit")]]]

               ;; Result display
               (when last-result
                 [:div {:style (merge row-style
                                      {:margin-top "6px"
                                       :align-items "flex-start"
                                       :padding "6px 8px"
                                       :border-radius "6px"
                                       :background (if (:ok last-result)
                                                     "rgba(242,139,130,0.10)"
                                                     "rgba(220,38,38,0.08)")
                                       :font-size "11px"
                                       :line-height 1.4})}
                  [:span {:style {:color (if (:ok last-result) coral "#dc2626")
                                  :margin-top "1px"}}
                   (if (:ok last-result) (icon-check) (icon-alert))]
                  [:span
                   (if (:ok last-result)
                     (or (get-in last-result [:data :message])
                         (:success-message config)
                         (tr "workspace.options.forms.submit-ok"))
                     (or (:error last-result)
                         (:error-message config)
                         (tr "workspace.options.forms.submit-error")))]])])])]))))
