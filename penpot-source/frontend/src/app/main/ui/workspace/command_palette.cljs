;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.command-palette
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; Figma-parity command palette (gap #47). A minimal, self-contained
;; overlay: type to filter a small fixed list of workspace commands,
;; Enter runs the highlighted one, Up/Down move the highlight, Escape
;; closes (by toggling the :command-palette layout flag back off). It is
;; mounted only under that flag (see viewport.cljs) so it is
;; byte-identical-off by default. The list is intentionally short and
;; uses only confirmed-exported data events (toggle-layout-flag / zoom-*);
;; extending it later is purely additive.

(def ^:private commands
  [{:id :zoom-fit-all  :label (tr "workspace.command-palette.zoom-fit-all")           :run #(st/emit! (dw/zoom-to-fit-all))}
   {:id :zoom-selected :label (tr "workspace.command-palette.zoom-selected")         :run #(st/emit! (dw/zoom-to-selected-shape))}
   {:id :zoom-reset    :label (tr "workspace.command-palette.zoom-reset")            :run #(st/emit! (dw/reset-zoom))}
   {:id :zoom-in       :label (tr "workspace.command-palette.zoom-in")               :run #(st/emit! (dw/increase-zoom))}
   {:id :zoom-out      :label (tr "workspace.command-palette.zoom-out")              :run #(st/emit! (dw/decrease-zoom))}
   {:id :outline-mode  :label (tr "workspace.command-palette.toggle-outline-mode")    :run #(st/emit! (dw/toggle-layout-flag :outline-mode))}
   {:id :pixel-preview :label (tr "workspace.command-palette.toggle-pixel-preview")   :run #(st/emit! (dw/toggle-layout-flag :pixel-preview))}
   {:id :wireframe-mode :label (tr "workspace.command-palette.toggle-wireframe-mode") :run #(st/emit! (dw/toggle-layout-flag :wireframe-mode))}
   {:id :snap-pixel    :label (tr "workspace.command-palette.toggle-snap-pixel-grid") :run #(st/emit! (dw/toggle-layout-flag :snap-pixel-grid))}
   {:id :lasso         :label (tr "workspace.command-palette.toggle-lasso")          :run #(st/emit! (dw/toggle-layout-flag :lasso-mode))}])

(defn- close!
  []
  (st/emit! (dw/toggle-layout-flag :command-palette)))

(mf/defc command-palette*
  {:wrap [mf/memo]}
  [_props]
  (let [query*    (mf/use-state "")
        query     (deref query*)
        index*    (mf/use-state 0)
        index     (deref index*)
        input-ref (mf/use-ref nil)

        filtered
        (mf/with-memo [query]
          (if (str/blank? query)
            commands
            (let [q (str/lower query)]
              (filter #(str/includes? (str/lower (:label %)) q) commands))))

        n (count filtered)

        on-query-change
        (mf/use-fn
         (fn [event]
           (let [value (dom/get-value (dom/get-target event))]
             (reset! query* value)
             (reset! index* 0))))

        run-command
        (mf/use-fn
         (mf/deps filtered index)
         (fn []
           (when-let [cmd (nth filtered index nil)]
             ((:run cmd))
             (close!))))

        on-key-down
        (mf/use-fn
         (mf/deps filtered index n)
         (fn [event]
           (cond
             (kbd/esc? event)        (do (dom/prevent-default event) (close!))
             (kbd/enter? event)      (do (dom/prevent-default event) (run-command))
             (kbd/down-arrow? event) (do (dom/prevent-default event)
                                         (reset! index* (mod (inc index) (max 1 n))))
             (kbd/up-arrow? event)   (do (dom/prevent-default event)
                                         (reset! index* (mod (dec index) (max 1 n)))))))

        on-item-click
        (mf/use-fn
         (mf/deps filtered)
         (fn [event]
           (let [idx (-> (dom/get-current-target event)
                         (dom/get-data "idx")
                         (d/read-string))]
             (when-let [cmd (nth filtered idx nil)]
               ((:run cmd))
               (close!)))))]

    (mf/with-effect []
      ;; Autofocus the input on mount so the user can type immediately.
      (some-> (mf/ref-val input-ref) dom/focus!))

    [:div {:class (stl/css :command-palette-overlay)
           :on-pointer-down (fn [event]
                              (when (= (dom/get-target event)
                                       (dom/get-current-target event))
                                (close!)))}
     [:div {:class (stl/css :command-palette)
            :on-key-down on-key-down}
      [:input {:class (stl/css :command-palette-input)
               :type "text"
               :ref input-ref
               :placeholder (tr "workspace.command-palette.placeholder")
               :value query
               :on-change on-query-change}]
      [:ul {:class (stl/css :command-palette-list)}
       (for [idx (range n)]
         (let [cmd (nth filtered idx)]
           [:li {:key (:id cmd)
                 :data-idx (str idx)
                 :class (stl/css-case :command-palette-item true
                                     :selected (= idx index))
                 :on-click on-item-click}
            (:label cmd)]))]]]))