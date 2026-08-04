;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.repeat-grid
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.workspace.repeat-grid :as rg]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Figma/Lunacy-parity "Repeat Grid" modal (ALL_APPS_PARITY P1.31).
;; Collects rows / cols / horizontal gap / vertical gap and emits
;; `rg/repeat-grid`, which duplicates the current selection into a rows x cols
;; grid. The gap inputs are the SPACING between copies; the modal converts
;; spacing into a cell pitch (selection size + spacing) on the event side.
;; Structure mirrors `ui/workspace/rotate_copies.cljs`; the footer action
;; buttons mirror `ui/delete_shared.cljs`.
(mf/defc repeat-grid-modal
  {::mf/register modal/components
   ::mf/register-as :repeat-grid}
  []
  (let [rows*  (mf/use-state 2)
        cols*  (mf/use-state 2)
        gapw*  (mf/use-state 0)
        gaph*  (mf/use-state 0)

        cancel!
        (mf/use-fn
         (fn [_]
           (modal/hide!)))

        apply!
        (mf/use-fn
         (mf/deps @rows* @cols* @gapw* @gaph*)
         (fn [_]
           (let [r (max 1 (js/Math.round (js/Number @rows*)))
                 c (max 1 (js/Math.round (js/Number @cols*)))]
             (st/emit!
              (rg/repeat-grid
               {:rows  r
                :cols  c
                :gap-w (js/Number @gapw*)
                :gap-h (js/Number @gaph*)}))
             (modal/hide!))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "modals.repeat-grid-title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click cancel!} deprecated-icon/close]]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "repeat-grid-rows"}
         (tr "modals.repeat-grid-rows")]
        [:> deprecated-input/numeric-input* {:min 1
                                             :max 100
                                             :step 1
                                             :id "repeat-grid-rows"
                                             :value @rows*
                                             :on-change (fn [v] (reset! rows* v))}]]

       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "repeat-grid-cols"}
         (tr "modals.repeat-grid-cols")]
        [:> deprecated-input/numeric-input* {:min 1
                                             :max 100
                                             :step 1
                                             :id "repeat-grid-cols"
                                             :value @cols*
                                             :on-change (fn [v] (reset! cols* v))}]]

       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "repeat-grid-gap-w"}
         (tr "modals.repeat-grid-gap-w")]
        [:> deprecated-input/numeric-input* {:id "repeat-grid-gap-w"
                                             :value @gapw*
                                             :on-change (fn [v] (reset! gapw* v))}]]

       [:div {:class (stl/css :input-wrapper)}
        [:label {:class (stl/css :modal-msg)
                 :for "repeat-grid-gap-h"}
         (tr "modals.repeat-grid-gap-h")]
        [:> deprecated-input/numeric-input* {:id "repeat-grid-gap-h"
                                             :value @gaph*
                                             :on-change (fn [v] (reset! gaph* v))}]]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input {:class (stl/css :cancel-button)
                 :type "button"
                 :value (tr "modals.repeat-grid-cancel")
                 :on-click cancel!}]
        [:input {:class (stl/css-case :accept-btn true
                                      :primary true)
                 :type "button"
                 :value (tr "modals.repeat-grid-apply")
                 :on-click apply!}]]]]]))