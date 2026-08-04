;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.cms
  "CMS Collections authorable menu (ALL_APPS_PARITY P0.05).

  Sidebar panel for creating collections, adding fields/items, and
  binding the currently-selected shape to a collection field. Bound
  shapes show a purple (#a855f7) chip — the Figma/Webflow CMS
  convention. CMS data is persisted through the changes pipeline as
  page plugin-data (see data/workspace/collections.cljs); on-page item
  editing waits on hosting the collection editor in a modal (DEFERRED)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.collection :as ctcol]
   [app.main.data.workspace.collections :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.i18n :refer [tr]]
   [clojure.string :as cstr]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Session CMS-data ref --------------------------------------------------
;; Reads the page's plugin-data slot populated by
;; `app.main.data.workspace.collections`. Falls back to empty-cms-data
;; when the slot is unset (new page / never edited). Kept local to this
;; menu so the shared refs.cljs stays untouched.

(def cms-data
  (l/derived
   (fn [page]
     (dwc/read-cms-data page))
   refs/workspace-page
   =))

;; --- Field-type metadata ---------------------------------------------------

(def ^:private field-type-label-key
  {:text            "workspace.options.cms.field-type.text"
   :image           "workspace.options.cms.field-type.image"
   :number          "workspace.options.cms.field-type.number"
   :date            "workspace.options.cms.field-type.date"
   :color           "workspace.options.cms.field-type.color"
   :reference       "workspace.options.cms.field-type.reference"
   :multi-reference "workspace.options.cms.field-type.multi-reference"})

(def ^:private field-type-options
  (mapv (fn [t] {:value (d/name t) :label t})
        (keys field-type-label-key)))

(defn- field-type-label
  [t]
  (tr (get field-type-label-key t :text)))

;; --- Inline Lucide icons (viewBox 0 0 24 24, stroke-width 2, currentColor) -

(defn- lucide-icon
  "Render an inline Lucide-style SVG icon. `children` is a vector of
  Hiccup children ([:path ...] / [:rect ...] / [:circle ...]). Uses
  currentColor so the icon inherits text color."
  [children]
  [:svg {:viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width 2
         :stroke-linecap "round"
         :stroke-linejoin "round"
         :width 16
         :height 16
         :style {:flex-shrink 0}}
   children])

(defn- icon-plus []     (lucide-icon [[:path {:d "M12 5v14"}] [:path {:d "M5 12h14"}]]))
(defn- icon-chevron []  (lucide-icon [[:path {:d "m6 9 6 6 6-6"}]]))
(defn- icon-database [] (lucide-icon [[:ellipse {:cx 12 :cy 5 :rx 9 :ry 3}]
                                      [:path {:d "M3 5v14a9 3 0 0 0 18 0V5"}]
                                      [:path {:d "M3 12a9 3 0 0 0 18 0"}]]))
(defn- icon-unlink []   (lucide-icon [[:path {:d "m9 17-3 3a4 4 0 0 1-6-6l3-3"}]
                                      [:path {:d "m15 7 3-3a4 4 0 0 1 6 6l-3 3"}]
                                      [:line {:x1 9 :y1 9 :x2 15 :y2 15}]]))
(defn- icon-layers []   (lucide-icon [[:path {:d "m12 2 9 5-9 5-9-5 9-5Z"}]
                                      [:path {:d "m3 12 9 5 9-5"}]
                                      [:path {:d "m3 17 9 5 9-5"}]]))
(defn- icon-grid []     (lucide-icon [[:rect {:x 3 :y 3 :width 7 :height 7 :rx 1}]
                                      [:rect {:x 14 :y 3 :width 7 :height 7 :rx 1}]
                                      [:rect {:x 3 :y 14 :width 7 :height 7 :rx 1}]
                                      [:rect {:x 14 :y 14 :width 7 :height 7 :rx 1}]]))

;; --- Purple CMS accent (Figma/Webflow convention) --------------------------

(def ^:private cms-purple "#a855f7")

(def ^:private purple-chip-style
  {:display "inline-flex"
   :align-items "center"
   :gap "6px"
   :padding "2px 8px"
   :border-radius "999px"
   :background "rgba(168,85,247,0.12)"
   :color cms-purple
   :font-size "11px"
   :font-weight "500"
   :line-height "1.4"})

(def ^:private purple-btn-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :width "26px"
   :height "26px"
   :border-radius "6px"
   :border "1px solid rgba(168,85,247,0.4)"
   :background "rgba(168,85,247,0.08)"
   :color cms-purple
   :cursor "pointer"})

(def ^:private neutral-600 "var(--token-color-neutral-600, #7d7d7d)")

;; --- Expanded collection body (sub-component to keep nesting shallow) ------

(mf/defc collection-body*
  [{:keys [col first-id binding on-bind on-add-field on-add-item]}]
  (let [field-name*  (mf/use-state "")
        field-name   (deref field-name*)
        field-type*  (mf/use-state "text")
        field-type   (deref field-type*)
        coll-id      (:id col)
        fields       (:fields col)
        already-bound? (and binding (= (:collection-id binding) coll-id))]

    [:div {:style {:padding "4px 0 6px 18px"
                   :display "flex"
                   :flex-direction "column"
                   :gap "6px"}}

     ;; Fields
     (if (seq fields)
       (for [fld fields]
         [:div {:key (str (:id fld))
                :style {:display "flex"
                        :align-items "center"
                        :gap "6px"
                        :font-size "11px"}}
          [:span {:style {:flex "1"}} (:name fld)]
          [:span {:style purple-chip-style}
           (field-type-label (:type fld))]])
       [:div {:style {:font-size "11px" :color neutral-600}}
        (tr "workspace.options.cms.empty")])

     ;; Add field row
     [:div {:style {:display "flex" :gap "6px" :align-items "center"}}
      [:input {:type "text"
               :value field-name
               :placeholder (tr "workspace.options.cms.add-field")
               :class (stl/css :type-input)
               :style {:flex "1"}
               :on-change #(reset! field-name* (.. % -target -value))}]
      [:select {:value field-type
                :class (stl/css :type-input)
                :style {:width "92px"}
                :on-change #(reset! field-type* (.. % -target -value))}
       (for [opt field-type-options]
         [:option {:key (:value opt)
                   :value (:value opt)}
          (field-type-label (:label opt))])]
      [:button {:type "button"
                :style purple-btn-style
                :title (tr "workspace.options.cms.add-field")
                :on-click (fn []
                            (let [nm (cstr/trim field-name)]
                              (when (seq nm)
                                (on-add-field coll-id nm (keyword field-type))
                                (reset! field-name* "")
                                (reset! field-type* "text"))))}
       (icon-plus)]]

     ;; Add item + bind selected
     [:div {:style {:display "flex" :gap "6px" :align-items "center"
                    :flex-wrap "wrap"}}
      [:button {:type "button"
                :style purple-btn-style
                :title (tr "workspace.options.cms.add-item")
                :on-click #(on-add-item coll-id)}
       (icon-grid)]
      (when (seq fields)
        (if first-id
          [:button {:type "button"
                    :style (merge purple-btn-style
                                  {:width "auto"
                                   :padding "0 10px"
                                   :font-size "11px"
                                   :font-weight "500"})
                    :disabled already-bound?
                    :on-click #(on-bind coll-id (-> fields first :id))}
           (tr (if already-bound?
                 "workspace.options.cms.bound-to-short"
                 "workspace.options.cms.bind"))]
          [:span {:style {:font-size "10px" :color neutral-600}}
           (tr "workspace.options.cms.select-shape")]))]]))

;; --- Collection row (header + expandable body) -----------------------------

(mf/defc collection-row*
  [{:keys [col open? first-id binding on-toggle on-bind on-add-field on-add-item]}]
  (let [coll-id (:id col)]
    [:div {:key (str coll-id)
           :style {:border-top "1px solid var(--token-color-neutral-200, #e5e5e5)"
                   :padding-top "6px"
                   :margin-top "6px"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "6px"
                    :cursor "pointer"
                    :padding "2px 0"}
            :on-click #(on-toggle coll-id)}
      [:span {:style {:color (if open? cms-purple "currentColor")}}
       (icon-chevron)]
      [:span {:style {:flex "1" :font-weight "500" :font-size "12px"}}
       (:name col)]
      [:span {:style {:font-size "10px" :color neutral-600}}
       (tr "workspace.options.cms.items" (count (:items col)))]]
     (when open?
       [:& collection-body*
        {:col col
         :first-id first-id
         :binding binding
         :on-bind on-bind
         :on-add-field on-add-field
         :on-add-item on-add-item}])]))

;; --- Component --------------------------------------------------------------

(mf/defc cms-menu*
  [{:keys [shapes]}]
  (let [cms       (mf/deref cms-data)
        selected  (mf/deref refs/selected-shapes)
        ;; `shapes` is the prop the options panel passes (vector of
        ;; selected shape maps). `selected` is the selected-shapes ref
        ;; (an ordered-set of ids). Resolve the primary selected shape
        ;; id from either source so binding works regardless of how the
        ;; menu is mounted.
        first-id  (or (some-> shapes first :id) (first selected))

        binding   (when first-id (ctcol/get-binding cms first-id))
        bound-col (when binding (ctcol/get-collection cms (:collection-id binding)))
        bound-fld (when bound-col (ctcol/get-field bound-col (:field-id binding)))

        open*     (mf/use-state nil)
        open-id   (deref open*)

        new-name* (mf/use-state "")
        new-name  (deref new-name*)

        on-toggle
        (mf/use-fn
         (fn [coll-id]
           (swap! open* (fn [cur] (when (not= cur coll-id) coll-id)))))

        on-new-collection
        (mf/use-fn
         (fn []
           (let [nm (cstr/trim new-name)]
             (st/emit! (dwc/create-collection (when (seq nm) nm)))
             (reset! new-name* ""))))

        on-add-field
        (mf/use-fn
         (fn [coll-id nm ty]
           (st/emit! (dwc/add-collection-field
                      {:collection-id coll-id :name nm :type ty}))))

        on-add-item
        (mf/use-fn
         (fn [coll-id]
           (st/emit! (dwc/add-collection-item {:collection-id coll-id}))))

        on-bind
        (mf/use-fn
         (fn [coll-id field-id]
           (when first-id
             (st/emit! (dwc/bind-shape
                        {:shape-id first-id
                         :collection-id coll-id
                         :field-id field-id})))))

        on-unbind
        (mf/use-fn
         (fn []
           (when first-id
             (st/emit! (dwc/unbind-shape {:shape-id first-id})))))]

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable true
                      :collapsed   false
                      :title       (tr "workspace.options.cms.title")}]]

     [:div {:class (stl/css :element-set-content)}

      ;; Bound-shape chip
      (when (and binding bound-col bound-fld)
        [:div {:style {:margin-bottom "8px"
                       :display "flex"
                       :align-items "center"
                       :gap "8px"}}
         [:span {:style purple-chip-style}
          (icon-database)
          (tr "workspace.options.cms.bound-to"
              (:name bound-col) (:name bound-fld))]
         [:button {:type "button"
                   :style purple-btn-style
                   :title (tr "workspace.options.cms.unbind")
                   :on-click on-unbind}
          (icon-unlink)]])

      ;; New collection row
      [:div {:class (stl/css :element-group)
             :style {:display "flex"
                     :flex-direction "column"
                     :gap "6px"}}
       [:div {:style {:display "flex" :gap "6px" :align-items "center"}}
        [:input {:type "text"
                 :value new-name
                 :placeholder (tr "workspace.options.cms.new-collection")
                 :class (stl/css :type-input)
                 :style {:flex "1"}
                 :on-change #(reset! new-name* (.. % -target -value))}]
        [:button {:type "button"
                  :style purple-btn-style
                  :title (tr "workspace.options.cms.new-collection")
                  :on-click on-new-collection}
         (icon-plus)]]

       (when (empty? (:collections cms))
         [:div {:style {:color neutral-600
                        :font-size "11px"
                        :padding "4px 0"}}
          (tr "workspace.options.cms.empty")])

       (for [col (:collections cms)]
         [:& collection-row*
          {:col col
           :open? (= open-id (:id col))
           :first-id first-id
           :binding binding
           :on-toggle on-toggle
           :on-bind on-bind
           :on-add-field on-add-field
           :on-add-item on-add-item}])]

      ;; Repeatable-region hint
      [:div {:style {:margin-top "8px"
                     :display "flex"
                     :gap "6px"
                     :align-items "flex-start"
                     :color neutral-600
                     :font-size "11px"
                     :line-height "1.4"}}
       [:span {:style {:color cms-purple :margin-top "1px"}} (icon-layers)]
       [:span (tr "workspace.options.cms.repeatable-hint")]]]]))

