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
   [app.main.data.workspace.bool-color :as dwbc]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.path.shapes-to-path :as dwps]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.features :as features]
   [app.main.refs :as refs]
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
  (mf/html [:svg {:class (stl/css :expand-icon)
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
            [:path {:d "M3 21l7-7"}]]))

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
;; P2.12 — Color application during merge.
;;
;; A color-source segmented control is added to the bool menu so the user
;; can choose what fill the merged :bool result carries. The DEFAULT mode
;; `:internal` keeps the existing internal-rule fill: the bool menu emits
;; the raw `dwb/create-bool` EXACTLY as before (byte-identical). Any other
;; mode routes the bool op through `dwbc/bool-with-color-event`, which
;; wraps `dwb/create-bool` + a fill-apply in one undo transaction. A cursor
;; swatch preview (a 16x16 coral-bordered chip following the mouse) is
;; shown while a non-:internal mode is armed so the user can see the color
;; that WILL be applied. Reduced-motion: the swatch is shown statically
;; (no fade transition).

(defn- reduced-motion?
  "True when the OS/browser asked to minimize non-essential motion. Re-checked
  each call so a live settings change takes effect without a reload."
  []
  (try
    (let [mq (.matchMedia js/window "(prefers-reduced-motion: reduce)")]
      (boolean (.-matches mq)))
    (catch :default _ false)))

;; Color-source modes shown in the bool menu, as `[label mode]` pairs in
;; display order. Labels are short (the segmented control is compact);
;; full descriptions live in the title tooltips (i18n keys below).
(def ^:private color-mode-options
  [["Auto"   :internal]
   ["1st"    :inherit-first]
   ["2nd"    :inherit-second]
   ["Swatch" :active-swatch]
   ["Pick"   :custom]])

(defn- color-mode-tooltip
  "i18n key for a color-source mode's title tooltip."
  [mode]
  (case mode
    :internal       "workspace.shape.bool.color-source.internal"
    :inherit-first  "workspace.shape.bool.color-source.inherit-first"
    :inherit-second "workspace.shape.bool.color-source.inherit-second"
    :active-swatch  "workspace.shape.bool.color-source.active-swatch"
    :custom         "workspace.shape.bool.color-source.custom"))

(defn- color-mode-btn-style
  "Inline style for one color-source segmented button. Active = coral
  accent (#f28b82); inactive = neutral. Kept inline so no SCSS edit is
  needed (the bool menu's own .scss is out of scope for this wave). Plain
  map so Rumext converts kebab-case keys to React camelCase."
  [active?]
  {:flex "1 1 0"
   :height "26px"
   :padding "0"
   :border "1px solid"
   :border-color (if active? "#f28b82" "var(--inspector-border-color, #e6e6e6)")
   :border-radius "4px"
   :background (if active? "rgba(242,139,130,0.18)" "transparent")
   :color (if active? "#f28b82" "var(--inspector-text-color, #7d7d7d)")
   :cursor "pointer"
   :font-size "11px"
   :font-weight (if active? "600" "400")
   :line-height "1"
   :text-align "center"
   :transition "background 120ms ease, border-color 120ms ease, color 120ms ease"})

(defn- cursor-swatch-style
  "Inline style for the cursor swatch chip. `hex` is the preview color or
  nil. Reduced-motion users get the chip with no transition (static).
  Plain map so Rumext converts kebab-case keys to React camelCase."
  [x y hex reduce-motion?]
  {:position "fixed"
   :left (str (or x 0) "px")
   :top (str (or y 0) "px")
   :width "16px"
   :height "16px"
   :margin "12px"
   :border-radius "3px"
   :background (if (some? hex) hex "transparent")
   :border "1.5px solid #f28b82"
   :box-shadow "0 1px 3px rgba(0,0,0,0.25)"
   :pointer-events "none"
   :z-index "99999"
   :transition (if reduce-motion? "none" "background 120ms ease")})

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

        ;; ── P2.12 color-source state ───────────────────────────────────────
        ;; `color-mode` defaults to :internal so the bool merge commits
        ;; byte-identically to the pre-feature behavior until the user
        ;; explicitly picks another source. `custom-color` seeds the native
        ;; color input with the coral accent.
        color-mode*   (mf/use-state :internal)
        custom-color* (mf/use-state "#f28b82")
        mouse-pos*    (mf/use-state nil)

        color-mode-non-internal? (not= @color-mode* :internal)

        ;; Reactive colorpicker state so we can resolve the active swatch
        ;; for the cursor-swatch preview (`refs/colorpicker` -> current
        ;; color object via `dwc/get-color-from-colorpicker-state`).
        colorpicker-state (mf/deref refs/colorpicker)
        active-swatch      (some-> colorpicker-state
                                   dwc/get-color-from-colorpicker-state)

        ;; Color the cursor-swatch chip should show right now. nil when no
        ;; shape in the selection carries a solid fill at all.
        preview-fill (dwbc/cursor-swatch-color @color-mode* shapes
                                               active-swatch @custom-color*)
        preview-hex  (:fill-color preview-fill)

        reduce-motion? (reduced-motion?)

        on-color-mode
        (mf/use-fn
         (mf/deps @color-mode*)
         (fn [mode]
           (when (not= mode @color-mode*)
             (reset! color-mode* mode))))

        on-custom-color
        (mf/use-fn
         (mf/deps @custom-color*)
         (fn [event]
           (let [val (.. event -target -value)]
             (when (some? val)
               (reset! custom-color* val)))))

        ;; Track the cursor while a non-:internal color-source is armed so
        ;; the swatch chip can follow the mouse. Added/removed on a window
        ;; listener; cleaned up when the mode changes back to :internal or
        ;; the component unmounts.
        mouse-move-handler
        (mf/use-fn
         (mf/deps @color-mode*)
         (fn [event]
           (let [x (.. event -clientX)
                 y (.. event -clientY)]
             (reset! mouse-pos* {:x x :y y}))))

        on-change
        (mf/use-fn
         (mf/deps total-selected is-group? is-bool? head-id head-bool-type @color-mode* @custom-color*)
         (fn [bool-type]
           (let [bool-type (keyword bool-type)]
             (cond
               (> total-selected 1)
               ;; P2.12: when a non-:internal color-source is armed, route
               ;; the bool op through the wrap event so the result gets the
               ;; chosen fill. :internal (default) -> raw `dwb/create-bool`,
               ;; byte-identical to the pre-feature behavior.
               (if (not= @color-mode* :internal)
                 (st/emit! (dwbc/bool-with-color-event bool-type @color-mode* @custom-color*))
                 (st/emit! (dwb/create-bool bool-type)))

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
             (st/emit! (expand-bool head-id)))))

        ;; Arm/disarm the window mousemove listener that drives the cursor
        ;; swatch chip. Only active while a non-:internal color-source is
        ;; armed; cleaned up on mode change or unmount.
        _
        (mf/use-effect
         (mf/deps color-mode-non-internal?)
         (fn []
           (when color-mode-non-internal?
             (.addEventListener js/window "mousemove" mouse-move-handler)
             #(.removeEventListener js/window "mousemove" mouse-move-handler))))]

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
                           :id "bool-opt-exclude"}]
         [:& radio-button {:icon i/boolean-add
                           :value "add"
                           :disabled disabled-bool-btns
                           :title (str (tr "workspace.shape.menu.add") " (" (sc/get-tooltip :bool-add) ")")
                           :id "bool-opt-add"}]]]

       ;; P2.12: color-source control. Only shown for multi-selection,
       ;; since the color-source only applies to the create-bool path.
       ;; Default :internal -> byte-identical to the pre-feature behavior.
       (when (> total-selected 1)
         [:*
          [:div {:style {:display "flex"
                         :align-items "center"
                         :gap "6px"
                         :margin-top "8px"
                         :margin-bottom "4px"}}
           [:span {:style {:flex "0 0 auto"
                           :font-size "11px"
                           :color "var(--inspector-text-color, #7d7d7d)"}}
            (tr "workspace.shape.bool.color-source")]
           [:div {:style {:display "flex"
                          :flex "1 1 auto"
                          :gap "4px"}}
            (for [[label mode] color-mode-options]
              [:button
               {:key label
                :type "button"
                :title (tr (color-mode-tooltip mode))
                :on-click #(on-color-mode mode)
                :style (color-mode-btn-style (= @color-mode* mode))}
               label])]]
          (when (= @color-mode* :custom)
            [:div {:style {:display "flex"
                           :align-items "center"
                           :gap "6px"
                           :margin-bottom "6px"}}
             [:input
              {:type "color"
               :value (or @custom-color* "#f28b82")
               :on-change on-custom-color
               :title (tr "workspace.shape.bool.color-source.custom")
               :style {:width "28px"
                       :height "22px"
                       :padding "0"
                       :border "1px solid #f28b82"
                       :border-radius "4px"
                       :background "transparent"
                       :cursor "pointer"}}]
             [:span {:style {:font-size "11px"
                             :color "var(--inspector-text-color, #7d7d7d)"
                             :font-family "monospace"}}
              (or @custom-color* "#f28b82")]])])

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
                             :title (tr "workspace.shape.bool.child-mode.exclusion")
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
         flatten-icon]]

       ;; P2.12: cursor swatch preview. A 16x16 coral-bordered chip fixed to
       ;; the cursor, shown while a non-:internal color-source is armed and
       ;; the mouse has moved at least once over the window. Reduced-motion:
       ;; shown statically (no transition). nil-safe: an empty/transparent
       ;; swatch is shown when no fill resolves.
       (when (and color-mode-non-internal? (some? @mouse-pos*))
         [:div {:style (cursor-swatch-style (:x @mouse-pos*) (:y @mouse-pos*)
                                            preview-hex reduce-motion?)}])])))