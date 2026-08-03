;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.bool
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.path.shapes-to-path :as dwps]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def ^:private flatten-icon
  (deprecated-icon/icon-xref :boolean-flatten (stl/css :flatten-icon)))

;; Lucide-style "maximize/expand" icon (stroke-width 2, currentColor)
;; used for the non-destructive Expand action. Defined as a plain hiccup
;; vector so it drops in exactly like `flatten-icon` above.
(def ^:private expand-icon
  [:svg {:class (stl/css :expand-icon)
         :width "16"
         :height "16"
         :viewBox "0 0 24 24"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "2"
         :stroke-linecap "round"
         :stroke-linejoin "round"}
   [:path {:d "M15 3h6v6"}]
   [:path {:d "M9 21H3v-6"}]
   [:path {:d "M21 3l-7 7"}]
   [:path {:d "M3 21l7-7"}]])

;; Non-destructive Expand: finalizes a live :bool into a static :path while
;; keeping a live copy of the original :bool. It composes two existing
;; potok events: (1) duplicate the selected :bool in place without changing
;; the selection, then (2) flatten the (still-selected) original to a path
;; via `convert-selected-to-path`. The duplicate remains as a sibling live
;; :bool, so the operation is recoverable even beyond Penpot's undo stack.
;;
;; Defined here (UI file) to keep all edits within `bool.cljs` per the task
;; constraints. For architectural consistency this handler is a candidate
;; to relocate to `app.main.data.workspace.bool` (see the follow-up note in
;; the task return); the signature `(expand-bool shape-id)` is stable.
(defn- expand-bool
  [shape-id]
  (ptk/reify ::expand-bool
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            shape   (get objects shape-id)]
        (when (and (some? shape) (cfh/bool-shape? shape))
          (rx/of (dws/duplicate-shapes [shape-id]
                                     :change-selection? false
                                     :move-delta? false)
                 (dwps/convert-selected-to-path [shape-id])))))))

(mf/defc bool-options*
  [{:keys [total-selected shapes shapes-with-children]}]
  (let [head      (first shapes)
        head-id   (dm/get-prop head :id)

        is-group? (cfh/group-shape? head)
        is-bool?  (cfh/bool-shape? head)

        head-bool-type
        (and is-bool? (get head :bool-type))

        render-wasm-enabled?
        (features/use-feature "render-wasm/v1")

        has-invalid-shapes?
        (some (if render-wasm-enabled?
                cfh/frame-shape?
                #(or (cfh/frame-shape? %) (cfh/text-shape? %)))
              shapes-with-children)

        head-not-group-like?
        (and (= 1 total-selected)
             (not is-group?)
             (not is-bool?))

        disabled-bool-btns (or (zero? total-selected) has-invalid-shapes? head-not-group-like?)
        disabled-flatten   (or (zero? total-selected) has-invalid-shapes?)

        ;; Reactive page objects so we can resolve the selected shape's
        ;; parent and decide whether to show the per-child shape-mode row.
        objects-ref (mf/with-memo []
                      (l/derived #(dsh/lookup-page-objects %) st/state))
        objects     (mf/deref objects-ref)

        parent-id   (dm/get-prop head :parent-id)
        parent      (when (some? parent-id) (get objects parent-id))
        parent-bool? (cfh/bool-shape? parent)

        ;; Per-child shape-mode override (:union/:difference/:intersection/
        ;; :exclusion). Defaults to the parent :bool's group mode when the
        ;; child has no explicit override.
        child-mode  (or (get head :shape-mode)
                        (and parent-bool? (get parent :bool-type)))

        show-child-mode?
        (and (= 1 total-selected)
             parent-bool?
             (not is-bool?)
             (not is-group?))

        expand-disabled
        (or (not is-bool?)
            (not= total-selected 1)
            has-invalid-shapes?)

        on-change
        (mf/use-fn
         (mf/deps total-selected is-group? is-bool? head-id head-bool-type)
         (fn [bool-type]
           (let [bool-type (keyword bool-type)]
             (cond
               (> total-selected 1)
               (st/emit! (dwb/create-bool bool-type))

               (and (= total-selected 1) is-group?)
               (st/emit! (dwb/group-to-bool head-id bool-type))

               (and (= total-selected 1) is-bool?)
               (if (= head-bool-type bool-type)
                 (st/emit! (dwb/bool-to-group head-id))
                 (st/emit! (dwb/change-bool-type head-id bool-type)))))))

        on-child-mode-change
        (mf/use-fn
         (mf/deps head-id)
         (fn [mode]
           (let [mode (keyword mode)]
             (st/emit! (dwsh/update-shapes
                        [head-id]
                        #(assoc % :shape-mode mode)
                        {:reg-objects? true
                         :attrs #{:shape-mode}})))))

        flatten-objects
        (mf/use-fn  #(st/emit! (dwps/convert-selected-to-path)))

        on-expand
        (mf/use-fn
         (mf/deps head-id is-bool? total-selected has-invalid-shapes?)
         (fn []
           (when (and (= total-selected 1) is-bool? (not has-invalid-shapes?))
             (st/emit! (expand-bool head-id)))))]

    (when (not (and disabled-bool-btns disabled-flatten))
      [:div {:class (stl/css :boolean-options)}
       [:div {:class (stl/css :bool-group)}
        [:& radio-buttons {:selected (d/name head-bool-type)
                           :class (stl/css :boolean-radio-btn)
                           :on-change on-change
                           :name "bool-options"}
         [:& radio-button {:icon i/boolean-union
                           :value "union"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.union") " (" (sc/get-tooltip :bool-union) ")")
                           :id "bool-opt-union"}]
         [:& radio-button {:icon i/boolean-difference
                           :value "difference"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.difference") " (" (sc/get-tooltip :bool-difference) ")")
                           :id "bool-opt-differente"}]
         [:& radio-button {:icon i/boolean-intersection
                           :value "intersection"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.intersection") " (" (sc/get-tooltip :bool-intersection) ")")
                           :id "bool-opt-intersection"}]
         [:& radio-button {:icon i/boolean-exclude
                           :value "exclude"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.exclude") " (" (sc/get-tooltip :bool-exclude) ")")
                           :id "bool-opt-exclude"}]]]

       (when show-child-mode?
         [:div {:class (stl/css :bool-child-mode)}
          [:span {:class (stl/css :bool-child-mode-label)}
           (tr "workspace.shape.bool.child-mode")]
          [:& radio-buttons {:selected (d/name child-mode)
                             :class (stl/css :boolean-radio-btn)
                             :on-change on-child-mode-change
                             :name (dm/str "bool-child-mode-" head-id)}
           [:& radio-button {:icon i/boolean-union
                             :value "union"
                             :title (tr "workspace.shape.bool.child-mode.union")
                             :id (dm/str "bool-child-union-" head-id)}]
           [:& radio-button {:icon i/boolean-difference
                             :value "difference"
                             :title (tr "workspace.shape.bool.child-mode.difference")
                             :id (dm/str "bool-child-difference-" head-id)}]
           [:& radio-button {:icon i/boolean-intersection
                             :value "intersection"
                             :title (tr "workspace.shape.bool.child-mode.intersection")
                             :id (dm/str "bool-child-intersection-" head-id)}]
           [:& radio-button {:icon i/boolean-exclude
                             :value "exclude"
                             :title (tr "workspace.shape.bool.child-mode.exclude")
                             :id (dm/str "bool-child-exclude-" head-id)}]]])

       [:div {:class (stl/css :bool-actions)}
        [:button
         {:title (tr "workspace.shape.bool.expand")
          :class (stl/css-case
                  :expand-button true
                  :disabled expand-disabled)
          :disabled expand-disabled
          :on-click on-expand}
         expand-icon]
        [:button
         {:title (tr "workspace.shape.menu.flatten")
          :class (stl/css-case
                  :flatten-button true
                  :disabled disabled-flatten)
          :disabled disabled-flatten
          :on-click flatten-objects}
         flatten-icon]]])))