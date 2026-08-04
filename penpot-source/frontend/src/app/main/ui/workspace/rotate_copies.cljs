;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.rotate-copies
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.rotate-copies :as rc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma-parity "Rotate copies" modal (ALL_APPS_PARITY P2.32). Collects a
;; copy count N (>= 2) and a rotation pivot (x / y), defaulting the pivot to
;; the current selection's bounding-rect center, then emits
;; `rc/rotate-copies` which duplicates the selection N-1 times fanned evenly
;; around the pivot. Structure mirrors `ui/workspace/nudge.cljs`; the footer
;; action buttons mirror `ui/delete_shared.cljs`.
(mf/defc rotate-copies-modal
  {::mf/register modal/components
   ::mf/register-as :rotate-copies}
  []
  (let [objects        (mf/deref refs/workspace-page-objects)
        selected       (mf/deref refs/selected-shapes)
        shapes         (keep #(get objects %) selected)
        default-center (or (some-> (gsh/shapes->rect shapes) grc/rect->center)
                           (gpt/point 0 0))

        count* (mf/use-state 6)
        cx*    (mf/use-state (:x default-center))
        cy*    (mf/use-state (:y default-center))

        cancel!
        (mf/use-fn
         (fn [_]
           (modal/hide!)))

        apply!
        (mf/use-fn
         (mf/deps @count* @cx* @cy*)
         (fn [_]
           (let [n (max 2 (js/Math.round (js/Number @count*)))]
             (st/emit! (rc/rotate-copies
                        {:count n
                         :center (gpt/point (js/Number @cx*) (js/Number @cy*))}))
             (modal/hide!))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "modals.rotate-copies-title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click cancel!} deprecated-icon/close]]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "rotate-copies-count"}
         (tr "modals.rotate-copies-count")]
        [:> deprecated-input/numeric-input* {:min 2
                                             :max 360
                                             :step 1
                                             :id "rotate-copies-count"
                                             :value @count*
                                             :on-change (fn [v] (reset! count* v))}]]

       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "rotate-copies-cx"}
         (tr "modals.rotate-copies-center-x")]
        [:> deprecated-input/numeric-input* {:id "rotate-copies-cx"
                                             :value @cx*
                                             :on-change (fn [v] (reset! cx* v))}]]

       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "rotate-copies-cy"}
         (tr "modals.rotate-copies-center-y")]
        [:> deprecated-input/numeric-input* {:id "rotate-copies-cy"
                                             :value @cy*
                                             :on-change (fn [v] (reset! cy* v))}]]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input {:class (stl/css :cancel-button)
                 :type "button"
                 :value (tr "modals.rotate-copies-cancel")
                 :on-click cancel!}]
        [:input {:class (stl/css-case :accept-btn true
                                      :primary true)
                 :type "button"
                 :value (tr "modals.rotate-copies-apply")
                 :on-click apply!}]]]]]))