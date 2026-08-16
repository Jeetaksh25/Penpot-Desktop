;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.on-page-edit
  "On-page edit menu (ALL_APPS_PARITY P1.24) — Inspect panel action.

  A single 'Enter on-page edit mode' action that opens the on-page edit
  preview overlay (`ui.workspace.on-page-preview`). Page-level concern:
  reads the current page's CMS bindings (via a derived ref over
  `refs/workspace-page`, the same pattern `menus/cms` uses) and
  SELF-HIDES when the page has no CMS-bound text shapes
  (`dope/read-cms-bound-keys` empty) — so on a page with no CMS the
  menu renders nothing (byte-identical to the prior render).

  Takes `{:keys [shapes]}` for idiom consistency with neighboring
  menus; the CMS-bound check is page-level (independent of the current
  selection). Lucide `mouse-pointer-click` icon (stroke-width 2,
  currentColor). Coral accent #f28b82 for the action button + focus
  ring. Reduced-motion: the button uses no motion (a plain hover color
  shift), and the popover open is instant under reduced-motion.

  i18n keys are placeholders the lead adds to en.po."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.collections :as dwc]
   [app.main.data.workspace.on-page-edit :as dope]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Page CMS-bound-keys ref ----------------------------------------------
;; Derived over `refs/workspace-page` (same pattern as `menus/cms`).
;; Returns the vector of CMS-bound text-shape keys for the current page;
;; empty when the page has no CMS bindings / no text shapes bound.

(def cms-bound-keys
  (l/derived
   (fn [page]
     (dope/read-cms-bound-keys (dwc/read-cms-data page) (:objects page)))
   refs/workspace-page
   =))

;; --- Inline Lucide icon (viewBox 0 0 24 24, stroke-width 2, currentColor) --

(defn- lucide-icon
  [children]
  (hic/el (into [:svg {:viewBox "0 0 24 24"
                       :fill "none"
                       :stroke "currentColor"
                       :stroke-width 2
                       :stroke-linecap "round"
                       :stroke-linejoin "round"
                       :width 14
                       :height 14
                       :style {:flex-shrink "0"}}]
                children)))

(defn- icon-cursor-click []
  ;; Lucide `mouse-pointer-click` — on-page edit / direct manipulation.
  (lucide-icon
   [[:path {:d "M9 9l5 12 1.8-5.2L21 14Z"}]
    [:path {:d "M7.2 2.2 8 5.1"}]
    [:path {:d "m5.1 8-2.9-.8"}]
    [:path {:d "M14 4.1 12 6"}]
    [:path {:d "m6 12-1.9 2"}]]))

;; --- Coral accent (Ovion brand) --------------------------------------------

(def ^:private coral "#f28b82")

(def ^:private action-button-style
  {:display "inline-flex"
   :align-items "center"
   :justify-content "center"
   :gap "8px"
   :width "100%"
   :padding "8px 12px"
   :border (str "1px solid " coral)
   :border-radius "6px"
   :background "rgba(242,139,130,0.10)"
   :color coral
   :font-size "12px"
   :font-weight "600"
   :cursor "pointer"
   :line-height "1.4"
   :transition "background 120ms ease, color 120ms ease"})

(def ^:private hint-style
  {:margin-top "6px"
   :color "var(--token-color-neutral-600, #7d7d7d)"
   :font-size "11px"
   :line-height "1.4"})

;; --- Component --------------------------------------------------------------

(mf/defc on-page-edit-menu*
  {::mf/props :obj
   ::mf/wrap [#(mf/memo' %)]}
  [{:keys [shapes]}]
  ;; Page-level CMS bindings. The `shapes` arg is accepted for idiom
  ;; consistency but the self-hide check is page-level (on-page edit is
  ;; a page-level action, not a per-selection one).
  (let [bound-keys (mf/deref cms-bound-keys)
        has-bound? (seq bound-keys)

        on-enter
        (mf/use-fn
         (fn []
           (st/emit! (dope/toggle-on-page-edit true))))]

    (when has-bound?
      [:div {:class (stl/css :element-set)}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable true
                        :collapsed false
                        :title (tr "workspace.options.on-page-edit.title")}]]
       [:div {:class (stl/css :element-set-content)}
        [:div {:class (stl/css :element-group)}
         [:button {:type "button"
                   :style action-button-style
                   :on-click on-enter}
          (icon-cursor-click)
          (tr "workspace.options.on-page-edit.enter")]
         [:div {:style hint-style}
          (tr "workspace.options.on-page-edit.hint")]]]])))