;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.inspect.a11y
  "Figma-parity accessibility insights (gap #77). A WCAG contrast checker
  shown in the Inspect panel for a selected text shape: computes the
  contrast ratio between the text color and the background behind it
  (nearest ancestor solid fill, falling back to white) and reports
  AA / AAA pass/fail for normal and large text.

  Pure math + read-only UI. Renders ONLY for a single text shape, so
  every other selection is byte-identical to before."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.util.code-gen.frameworks.common :as fc]
   [app.util.i18n :refer [tr]]
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