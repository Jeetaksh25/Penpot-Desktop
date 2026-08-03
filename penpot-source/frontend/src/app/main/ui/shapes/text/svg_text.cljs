;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.text.svg-text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.config :as cf]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.main.ui.shapes.fills :as fills]
   [app.main.ui.shapes.gradients :as grad]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def fill-attrs [:fill-color :fill-color-gradient :fill-opacity])

(defn set-white-fill
  [shape]
  (let [update-color
        (fn [data]
          (-> data
              (dissoc :fill-color :fill-opacity :fill-color-gradient)
              (assoc :fills [{:fill-color "#FFFFFF" :fill-opacity 1}])))]
    (-> shape
        (d/update-when :position-data #(mapv update-color %))
        (assoc :stroke-color "#FFFFFF" :stroke-opacity 1))))

(mf/defc text-shape
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]

  (let [render-id (mf/use-ctx muc/render-id)
        shape (obj/get props "shape")
        shape (cond-> shape (:is-mask? shape) set-white-fill)

        {:keys [x y width height position-data]} shape

        transform (gsh/transform-str shape)

        ;; These position attributes are not really necessary but they are convenient for for the export
        group-props (-> #js {:transform transform
                             :className "text-container"
                             :x x
                             :y y
                             :width width
                             :height height}
                        (attrs/add-fill-props! shape render-id)
                        (attrs/add-border-props! shape))
        get-gradient-id
        (fn [index]
          (str render-id "-" (:id shape) "-" index))]

    [:*
     ;; Definition of gradients for partial elements
     (when (d/seek :fill-color-gradient position-data)
       [:defs
        (for [[index data] (d/enumerate position-data)]
          (when (some? (:fill-color-gradient data))
            (let [id (dm/str "fill-color-gradient-" (get-gradient-id index))]
              [:& grad/gradient {:id id
                                 :key id
                                 :attr :fill-color-gradient
                                 :shape data}])))])

     [:> :g group-props
      (for [[index data] (d/enumerate position-data)]
        (let [rtl? (= "rtl" (:direction data))

              browser-props
              (cond
                (cf/check-browser? :safari)
                #js {:dominantBaseline "hanging"
                     :dy "0.2em"
                     :y (- (:y data) (:height data))})

              ;; Feature 50 — leading-punctuation glyphs that, when
              ;; `:hanging-punctuation` is on, should hang outside the text-box
              ;; bounds. Chromium WebView2 does not implement the CSS property,
              ;; so the SVG does the hang itself via a negative `:dx`.
              hanging-chars #{"‘" "’" "“" "”" "「" "」" "『" "』" "、" "。" "（" "）" "【" "】" "《" "》" "—"}

              ;; Feature 17 — truncation ellipsis. `last-visible?` is the final
              ;; laid-out block; `overflowed?` is exact for a single-paragraph
              ;; text box (line count > max-lines) and approximate for
              ;; multi-paragraph. The ellipsis is appended ONLY when
              ;; `:text-overflow` is `truncate`, `:max-lines` is a positive
              ;; number, this is the last block, and the text overflowed —
              ;; otherwise `display-text` is verbatim `(:text data)`, so an
              ;; absent slot is byte-identical.
              last-visible? (= index (dec (count position-data)))
              max-lines-num (some-> (:max-lines data) js/parseFloat)
              overflowed?   (and (some? max-lines-num)
                                 (pos? max-lines-num)
                                 (> (count position-data) max-lines-num))
              truncate?     (and (= (:text-overflow data) "truncate")
                                 (some? (:max-lines data))
                                 (pos? (js/parseFloat (:max-lines data)))
                                 last-visible?
                                 overflowed?)
              display-text  (if truncate? (str (:text data) "…") (:text data))

              props (-> #js {:key (dm/str "text-" (:id shape) "-" index)
                             :x (if rtl? (+ (:x data) (:width data)) (:x data))
                             :y (:y data)
                             :dominantBaseline "ideographic"
                             :textLength (:width data)
                             :lengthAdjust "spacingAndGlyphs"
                             :style (-> #js {:fontFamily (:font-family data)
                                             :fontSize (:font-size data)
                                             :fontWeight (:font-weight data)
                                             :textTransform (:text-transform data)
                                             :textDecoration (:text-decoration data)
                                             :letterSpacing (:letter-spacing data)
                                             :fontStyle (:font-style data)
                                             :direction (:direction data)
                                             :whiteSpace "pre"}
                                        (obj/set! "fill" (str "url(#fill-" index "-" render-id ")"))
                                        (cond->
                                          ;; Feature 12 / 13 — OpenType &
                                          ;; variable-font axes. Absent (`nil`)
                                          ;; -> no `style` key -> byte-identical.
                                          (some? (:font-feature-settings data))
                                          (obj/set! "fontFeatureSettings"
                                                    (:font-feature-settings data))
                                          (some? (:font-variation-settings data))
                                          (obj/set! "fontVariationSettings"
                                                    (:font-variation-settings data))))}
                        (cond-> browser-props
                          (obj/merge! browser-props)

                          ;; Feature 78 — super/sub. Only `super`/`sub` (never
                          ;; the default `baseline`) set a `:dy`; absent ->
                          ;; no `:dy` -> byte-identical.
                          (#{"super" "sub"} (:baseline-shift data))
                          (obj/set! "dy"
                                    (if (= "super" (:baseline-shift data))
                                      "-0.5em"
                                      "0.3em"))

                          ;; Feature 50 — hang leading punctuation. A negative
                          ;; `:dx` approximates the glyph advance (half the
                          ;; font-size, em heuristic). Absent / false /
                          ;; non-hanging first char -> no `:dx` ->
                          ;; byte-identical.
                          (and (:hanging-punctuation data)
                               (contains? hanging-chars (first (:text data))))
                          (obj/set! "dx"
                                    (str "-"
                                          (or (* 0.5 (js/parseFloat (:font-size data)))
                                              0)
                                          "px"))))
              shape (-> shape
                        (assoc :fills (:fills data))
                        ;; The text elements have the shadow and blur already applied in the
                        ;; group parent.
                        (dissoc :shadow :blur))

              ;; Need to create new render-id per text-block
              render-id (dm/str render-id "-" index)]

          [:& (mf/provider muc/render-id) {:key index :value render-id}
           ;; Text fills definition. Need to be defined per-text block
           [:defs
            [:& fills/fills          {:shape shape :render-id render-id}]]

           [:& shape-custom-strokes {:shape shape :position index :render-id render-id}
            ;; Feature 18 — hyperlink. Wrap the <text> in an <a> when the block
            ;; carries a `:hyperlink` map; absent -> bare <text>,
            ;; byte-identical.
            (if-let [hl (:hyperlink data)]
              [:> :a #js {:href (:url hl) :key (dm/str "hl-" index)}
               [:> :text props display-text]]
              [:> :text props display-text])]]))]]))
