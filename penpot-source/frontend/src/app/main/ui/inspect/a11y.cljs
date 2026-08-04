;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.inspect.a11y
  "Figma-parity accessibility insights (gap #77) + ARIA authoring
  (ALL_APPS_PARITY P1.06).

  Two additive Inspect-panel sections:

  1. `a11y-contrast*` — a WCAG contrast checker shown for a single
     selected text shape: computes the contrast ratio between the text
     color and the background behind it (nearest ancestor solid fill,
     falling back to white) and reports AA / AAA pass/fail for normal
     and large text. Pure math + read-only UI. Renders ONLY for a single
     text shape, so every other selection is byte-identical to before.

  2. `a11y-authoring*` — ARIA authoring for ANY single selected shape:
     a text input for the accessible name (aria-label) and a dropdown of
     ARIA roles, persisted on the shape under an additive `:a11y` map
     via `dwga/set-a11y-label` / `dwga/set-a11y-role`. Renders ONLY for a
     single shape, so multi-shape selections are byte-identical."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.main.data.workspace.a11y :as dwga]
   [app.main.store :as st]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.code-gen.frameworks.common :as fc]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

;; ---------------------------------------------------------------------------
;; Color parsing + WCAG contrast math
;; ---------------------------------------------------------------------------

(defn- rgba-string->channels
  "Parse an 'rgba(r,g,b,a)' string into [r g b a] with r/g/b in 0..255
  and a in 0..1. Falls back to opaque black."
  [s]
  (let [nums (->> (re-seq #"\d+\.?\d*" (or s ""))
                  (map #(js/parseFloat %)))]
    (if (>= (count nums) 4)
      (let [[r g b a] nums] [r g b a])
      [0 0 0 1])))

(defn- channel->linear
  "Linearize a 0..1 sRGB channel per WCAG."
  [c]
  (if (<= c 0.03928)
    (/ c 12.92)
    (js/Math.pow (/ (+ c 0.055) 1.055) 2.4)))

(defn- relative-luminance
  "WCAG relative luminance for [r g b a] (r/g/b 0..255, a 0..1). Alpha is
  composited over white (the assumed page backdrop) so a translucent text
  or background is measured at its effective opacity."
  [[r g b a]]
  (let [a (d/nilv a 1)
        ;; composite over white (1,1,1) then linearize.
        comp (fn [ch] (+ (* a (/ ch 255)) (* (- 1 a) 1)))
        lr (channel->linear (comp r))
        lg (channel->linear (comp g))
        lb (channel->linear (comp b))]
    (+ (* 0.2126 lr) (* 0.7152 lg) (* 0.0722 lb))))

(defn- contrast-ratio
  "WCAG contrast ratio between two [r g b a] colors, as a number >= 1."
  [c1 c2]
  (let [l1 (relative-luminance c1)
        l2 (relative-luminance c2)
        lighter (js/Math.max l1 l2)
        darker  (js/Math.min l1 l2)]
    (/ (+ lighter 0.05) (+ darker 0.05))))

(defn- round2
  [n]
  (/ (js/Math.round (* n 100)) 100))

;; ---------------------------------------------------------------------------
;; Text + background resolution
;; ---------------------------------------------------------------------------

(defn- first-solid-fill-color
  "The first non-hidden solid fill of `shape` as an rgba string, or nil
  when the shape has no solid fill (image / gradient / none)."
  [shape]
  (when-let [fill (fc/first-fill shape)]
    (when (and (some? (:fill-color fill))
               (nil? (:fill-color-gradient fill))
               (nil? (:fill-image fill)))
      (fc/fill-color-rgba {:color (:fill-color fill)
                           :opacity (:fill-opacity fill)}))))

(defn- ancestor-background
  "Walk the parent chain (via `objects`) from `shape` and return the first
  solid fill color rgba string found on an ancestor, or nil (caller falls
  back to white)."
  [objects shape]
  (loop [parent-id (:parent-id shape)]
    (when (some? parent-id)
      (let [parent (get objects parent-id)]
        (if-let [bg (and parent (first-solid-fill-color parent))]
          bg
          (recur (:parent-id parent)))))))

(defn- text-color-channels
  "The text color of a text shape (from its first text node's fill), as
  [r g b a]. Falls back to opaque black."
  [shape]
  (let [typo (fc/extract-typography shape)]
    (rgba-string->channels (:color typo))))

(defn- background-color-channels
  "The background color behind a text shape: nearest ancestor solid fill,
  else white. Returns [r g b a]."
  [objects shape]
  (if-let [bg (ancestor-background objects shape)]
    (rgba-string->channels bg)
    [255 255 255 1]))

;; ---------------------------------------------------------------------------
;; UI
;; ---------------------------------------------------------------------------

(defn- pass-badge
  [ok?]
  [:span {:class (stl/css-case :a11y-badge true
                               :a11y-pass ok?
                               :a11y-fail (not ok?))}
   (if ok? (tr "inspect.a11y.pass") (tr "inspect.a11y.fail"))])

(mf/defc a11y-contrast*
  "WCAG contrast checker for a single selected text shape. Returns nil
  (renders nothing) for any non-text or multi-shape selection, so the
  feature is purely additive and guarded."
  {::mf/private true}
  [{:keys [objects shapes]}]
  (let [open*  (mf/use-state true)
        open?  (deref open*)
        toggle (mf/use-fn #(swap! open* not))

        single-text?
        (and (= (count shapes) 1)
             (cfh/text-shape? (first shapes)))

        result
        (mf/use-memo
         (mf/deps objects shapes single-text?)
         (fn []
           (when single-text?
             (let [shape (first shapes)
                   tc (text-color-channels shape)
                   bc (background-color-channels objects shape)
                   ratio (contrast-ratio tc bc)
                   font-size (js/parseFloat (or (-> (fc/extract-typography shape) :font-size) "14"))
                   large? (or (>= font-size 18)
                              (and (>= font-size 14)
                                   (#{:bold "700" "800" "900"}
                                     (-> (fc/extract-typography shape) :font-weight))))]
               {:ratio ratio
                :large? large?
                :aa-normal (>= ratio 4.5)
                :aa-large  (>= ratio (if large? 3.0 4.5))
                :aaa-normal (>= ratio 7.0)
                :aaa-large  (>= ratio (if large? 4.5 7.0))}))))]

    (when (some? result)
      (let [{:keys [ratio aa-normal aa-large aaa-normal aaa-large]} result]
        [:div {:class (stl/css :a11y-section)}
         [:> title-bar* {:collapsable true
                         :collapsed (not open?)
                         :on-collapsed toggle
                         :title (tr "inspect.a11y.contrast")
                         :class (stl/css :a11y-title-bar)}]
         (when open?
           [:div {:class (stl/css :a11y-content)}
            [:div {:class (stl/css :a11y-ratio-row)}
             [:span {:class (stl/css :a11y-ratio-label)}
              (tr "inspect.a11y.ratio")]
             [:span {:class (stl/css :a11y-ratio-value)}
              (str (round2 ratio) ":1")]]
            [:div {:class (stl/css :a11y-row)}
             [:span {:class (stl/css :a11y-row-label)}
              (tr "inspect.a11y.aa.normal")]
             [pass-badge aa-normal]]
            [:div {:class (stl/css :a11y-row)}
             [:span {:class (stl/css :a11y-row-label)}
              (tr "inspect.a11y.aa.large")]
             [pass-badge aa-large]]
            [:div {:class (stl/css :a11y-row)}
             [:span {:class (stl/css :a11y-row-label)}
              (tr "inspect.a11y.aaa.normal")]
             [pass-badge aaa-normal]]
            [:div {:class (stl/css :a11y-row)}
             [:span {:class (stl/css :a11y-row-label)}
              (tr "inspect.a11y.aaa.large")]
             [pass-badge aaa-large]]
            [:div {:class (stl/css :a11y-hint)}
             (tr "inspect.a11y.hint")]])]))))

;; ---------------------------------------------------------------------------
;; ARIA authoring (P1.06)
;; ---------------------------------------------------------------------------
;;
;; Renders for any SINGLE selected shape (text or otherwise): a text
;; input for the accessible name (aria-label) and a dropdown of ARIA
;; roles. Values are persisted on the shape under an additive `:a11y`
;; map. The local input draft is resynced from the shape whenever the
;; selection (shape id) or the persisted label changes, so external
;; edits / undo / redo are reflected; commits happen on blur / Enter so
;; typing does not spam undo steps.

(def ^:private aria-roles
  "Vector of `{:value :role :label (tr ...)}` options for the role
  dropdown. Values are keywords so the persisted `:a11y` map reads as
  `{:role :button}`. `none` is the explicit no-role option (the dropdown
  has no blank state — `none` IS the default)."
  [{:value :button        :label (tr "inspect.a11y.role.button")}
   {:value :link          :label (tr "inspect.a11y.role.link")}
   {:value :heading       :label (tr "inspect.a11y.role.heading")}
   {:value :image         :label (tr "inspect.a11y.role.image")}
   {:value :navigation    :label (tr "inspect.a11y.role.navigation")}
   {:value :region        :label (tr "inspect.a11y.role.region")}
   {:value :list          :label (tr "inspect.a11y.role.list")}
   {:value :listitem      :label (tr "inspect.a11y.role.listitem")}
   {:value :checkbox      :label (tr "inspect.a11y.role.checkbox")}
   {:value :switch        :label (tr "inspect.a11y.role.switch")}
   {:value :none          :label (tr "inspect.a11y.role.none")}
   {:value :presentation  :label (tr "inspect.a11y.role.presentation")}])

(defn- role->option
  "Find the dropdown option matching `role` (a keyword or string),
  defaulting to `:none` so the select always has a valid current value."
  [role]
  (let [role (if (string? role) (keyword role) role)
        role (if (nil? role) :none role)]
    (or (some #(when (= (:value %) role) %) aria-roles)
        {:value :none :label (tr "inspect.a11y.role.none")})))

(mf/defc a11y-authoring*
  "ARIA authoring panel for a single selected shape. Returns nil
  (renders nothing) for a multi-shape selection, so the feature is
  purely additive and guarded."
  {::mf/private true}
  [{:keys [shapes]}]
  (let [single?       (= (count shapes) 1)
        shape         (first shapes)
        shape-id      (:id shape)
        a11y          (:a11y shape)
        cur-label     (:label a11y "")
        cur-role      (:role a11y)

        open*         (mf/use-state true)
        label*        (mf/use-state cur-label)

        ;; Resync the local draft when the selection (shape id) or the
        ;; persisted label changes (undo / redo / external edit). The
        ;; deps vector is the dependency; the effect runs on mount and
        ;; whenever a dep changes.
        _             (mf/use-effect
                       (mf/deps shape-id cur-label)
                       (fn [] (reset! label* cur-label)))

        toggle        (mf/use-fn #(swap! open* not))

        commit-label
        (mf/use-fn
         (mf/deps shape-id cur-label)
         (fn []
           (let [draft @label*]
             ;; Only emit when the draft actually differs from the
             ;; persisted value, so a no-op blur (focus + immediate
             ;; blur) does not create a spurious undo step. `cur-label`
             ;; is in the deps so the closure is fresh after every
             ;; commit / undo / redo.
             (when (not= draft cur-label)
               (st/emit! (dwga/set-a11y-label shape-id draft))))))

        on-label-change
        (mf/use-fn
         (fn [event]
           (reset! label* (dom/get-target-val event))))

        on-label-key-down
        (mf/use-fn
         (mf/deps commit-label)
         (fn [event]
           (cond
             (kbd/enter? event)
             (do (dom/prevent-default event)
                 (dom/blur! (dom/get-target event)))
             (kbd/esc? event)
             (do (reset! label* cur-label)
                 (dom/blur! (dom/get-target event))))))

        on-label-blur
        (mf/use-fn
         (mf/deps commit-label)
         (fn [] (commit-label)))

        on-role-change
        (mf/use-fn
         (mf/deps shape-id)
         (fn [value]
           (let [role (if (string? value) (keyword value) value)]
             (st/emit! (dwga/set-a11y-role shape-id role)))))]

    (when single?
      [:div {:class (stl/css :a11y-section)}
       [:> title-bar* {:collapsable true
                       :collapsed (not @open*)
                       :on-collapsed toggle
                       :title (tr "inspect.a11y.authoring")
                       :class (stl/css :a11y-title-bar)}]
       (when @open?
         [:div {:class (stl/css :a11y-content)}
          [:div {:class (stl/css :a11y-field)}
           [:label {:class (stl/css :a11y-field-label)
                    :for "a11y-aria-label-input"}
            (tr "inspect.a11y.name")]
           [:input {:id "a11y-aria-label-input"
                    :class (stl/css :a11y-text-input)
                    :type "text"
                    :value @label*
                    :placeholder (tr "inspect.a11y.name.placeholder")
                    :on-change on-label-change
                    :on-key-down on-label-key-down
                    :on-blur on-label-blur}]]

          [:div {:class (stl/css :a11y-field)}
           [:span {:class (stl/css :a11y-field-label)}
            (tr "inspect.a11y.role")]
           [:& select {:default-value (role->option cur-role)
                       :options aria-roles
                       :on-change on-role-change
                       :class (stl/css :a11y-role-select)}]]

          [:div {:class (stl/css :a11y-hint)}
           (tr "inspect.a11y.authoring.hint")]])])))