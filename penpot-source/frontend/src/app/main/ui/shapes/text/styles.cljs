;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.text.styles
  (:require
   [app.common.data :as d]
   [app.common.transit :as transit]
   [app.common.types.color :as cc]
   [app.common.types.text :as txt]
   [app.main.fonts :as fonts]
   [app.main.ui.formats :as fmt]
   [app.util.color :as uc]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(defn generate-root-styles
  ([props node]
   (generate-root-styles props node false))
  ([{:keys [width height]} node code?]
   (let [valign (:vertical-align node "top")
         base   #js {:height (when-not code? (fmt/format-pixels height))
                     :width  (when-not code? (fmt/format-pixels width))
                     :display "flex"
                     :whiteSpace "break-spaces"}]
     (cond-> base
       (= valign "top")     (obj/set! "alignItems" "flex-start")
       (= valign "center")  (obj/set! "alignItems" "center")
       (= valign "bottom")  (obj/set! "alignItems" "flex-end")))))

(defn generate-paragraph-set-styles
  [{:keys [grow-type] :as shape}]
  ;; This element will control the auto-width/auto-height size for the
  ;; shape. The properties try to adjust to the shape and "overflow" if
  ;; the shape is not big enough.
  ;; We `inherit` the property `justify-content` so it's set by the root where
  ;; the property it's known.
  ;; `inline-flex` is similar to flex but `overflows` outside the bounds of the
  ;; parent
  (let [auto-width?  (= grow-type :auto-width)]
    #js {:display "inline-flex"
         :flexDirection "column"
         :justifyContent "inherit"
         :minWidth (when-not auto-width? "100%")
         :marginRight "1px"
         :verticalAlign "top"}))

(defn generate-paragraph-styles
  [_shape data]
  (let [line-height (:line-height data)
        line-height
        (if (and (some? line-height) (not= "" line-height))
          line-height
          (:line-height txt/default-typography))

        ;; Feature 14 — line-height modes. Absent / "auto" = legacy unitless
        ;; multiplier (existing behavior); "percent" = value/100 multiplier;
        ;; "px" = absolute pixels. Additive: only reinterprets when a mode is
        ;; explicitly set.
        line-height-mode (:line-height-mode data)
        line-height-css
        (cond
          (= line-height-mode "percent")
          (let [v (js/parseFloat line-height)]
            (if (js/isFinite v) (str (/ v 100)) line-height))

          (= line-height-mode "px")
          (str line-height "px")

          :else line-height)

        text-align  (:text-align data "start")

        ;; Feature 15 — paragraph spacing (margin between paragraphs) and
        ;; paragraph indentation (first-line indent). Additive.
        paragraph-spacing (:paragraph-spacing data)
        paragraph-indent  (:paragraph-indent data)

        ;; Feature 50 — hanging punctuation. Paragraph-level boolean. When
        ;; true emit the real CSS `hanging-punctuation` property so opening
        ;; quotes / bullet markers hang outside the text-box bounds for
        ;; cleaner edges (Figma Type Settings > Indentation). Additive:
        ;; absent / false = no CSS emitted, existing rendering unchanged.
        ;; Renderer positioning of leading punctuation outside the bounds
        ;; is deferred — the CSS property alone is the export here.
        hanging-punctuation (true? (:hanging-punctuation data))

        ;; Feature 17 — max-lines + truncation. Per-paragraph -webkit-line-clamp
        ;; approximates Figma whole-box clamping (exact whole-box behavior is a
        ;; v1 limitation; single-paragraph text boxes — the common case — are
        ;; exact). Only applied when truncate? and a positive max-lines are set.
        text-overflow (:text-overflow data)
        truncate?     (= text-overflow "truncate")
        max-lines     (:max-lines data)
        max-lines-num (js/parseFloat max-lines)

        base        #js {;; Fix a problem when exporting HTML
                         :fontSize 0
                         :lineHeight line-height-css
                         :margin 0}]

    (cond-> base
      (some? line-height-css)  (obj/set! "lineHeight" line-height-css)
      (some? text-align)       (obj/set! "textAlign" text-align)

      ;; Feature 15
      (and (some? paragraph-spacing) (not= "" paragraph-spacing))
      (obj/set! "marginBottom" (str paragraph-spacing "px"))

      (and (some? paragraph-indent) (not= "" paragraph-indent))
      (obj/set! "textIndent" (str paragraph-indent "px"))

      ;; Feature 50 — hanging punctuation. `first last` pulls opening and
      ;; closing punctuation outside the box edges; the leading-punctuation
      ;; repositioning in the renderer is deferred (CSS property only).
      hanging-punctuation
      (obj/set! "hangingPunctuation" "first last")

      ;; Feature 17
      (and truncate? (some? max-lines) (not= "" max-lines) (pos? max-lines-num))
      (-> (obj/set! "display" "-webkit-box")
          (obj/set! "WebkitBoxOrient" "vertical")
          (obj/set! "WebkitLineClamp" max-lines-num)
          (obj/set! "overflow" "hidden")

      ;; Feature 16 — list styles. `:list-type` / `:list-spacing` are
      ;; paragraph-level attrs that persist + round-trip through the editor
      ;; DOM, but rendering actual bullet/number markers requires editor
      ;; content-model list nodes (deferred — additive optional fields only).
      ;; So no CSS is emitted here yet; the attrs are simply preserved.
      ))))

(defn generate-text-styles
  ([shape data]
   (generate-text-styles shape data nil))

  ([{:keys [grow-type] :as shape} data {:keys [show-text?] :or {show-text? true}}]
   (let [letter-spacing  (:letter-spacing data 0)
         text-decoration (:text-decoration data)
         text-transform  (:text-transform data)

         font-id         (or (:font-id data)
                             (:font-id txt/default-typography))

         font-variant-id (:font-variant-id data)

         font-size       (:font-size data)

         fill-color      (or (-> data :fills first :fill-color) (:fill-color data))
         fill-opacity    (or (-> data :fills first :fill-opacity) (:fill-opacity data))
         fill-gradient   (or (-> data :fills first :fill-color-gradient) (:fill-color-gradient data))

         [r g b a]       (cc/hex->rgba fill-color fill-opacity)
         text-color      (when (and (some? fill-color) (some? fill-opacity))
                           (str/format "rgba(%s, %s, %s, %s)" r g b a))

         gradient?       (some? fill-gradient)

         text-color      (if gradient?
                           (uc/color->background {:gradient fill-gradient})
                           text-color)

         fontsdb         (deref fonts/fontsdb)

         base            #js {:textDecoration text-decoration
                              :textTransform text-transform
                              :fontSize font-size
                              :color (if (and show-text? (not gradient?)) text-color "transparent")
                              :background (when (and show-text? gradient?) text-color)
                              :caretColor (if (and (not gradient?) text-color) text-color "black")
                              :overflowWrap "initial"
                              :lineBreak "auto"
                              :whiteSpace "break-spaces"
                              :textRendering "geometricPrecision"}
         base            (cond-> base
                           (= (:line-height data) "0")
                           (-> (obj/set! "display" "inline-block")
                               (obj/set! "verticalAlign" "top")))
         fills
         (cond
           ;; DEPRECATED: still here for backward compatibility with
           ;; old penpot files that still has a single color.
           (or (some? (:fill-color data))
               (some? (:fill-opacity data))
               (some? (:fill-color-gradient data)))
           [(d/without-nils (select-keys data [:fill-color :fill-opacity :fill-color-gradient
                                               :fill-color-ref-id :fill-color-ref-file]))]

           (nil? (:fills data))
           [{:fill-color "#000000" :fill-opacity 1}]

           :else
           (:fills data))

         font (some->> font-id (get fontsdb))

         [font-family font-style font-weight]
         (when (some? font)
           (let [font-variant (d/seek #(= font-variant-id (:id %)) (:variants font))]
             [(str/quote (or (:family font) (:font-family data)))
              (or (:style font-variant) (:font-style data))
              (or (:weight font-variant) (:font-weight data))]))

         base (obj/set! base "--font-id" font-id)]

     (cond-> base
       (some? fills)
       (obj/set! "--fills" (transit/encode-str fills))

       (and (string? letter-spacing) (pos? (alength letter-spacing)))
       (obj/set! "letterSpacing" (str letter-spacing "px"))

       (and (string? font-size) (pos? (alength font-size)))
       (obj/set! "fontSize" (str font-size "px"))

       (some? font)
       (-> (obj/set! "fontFamily" font-family)
           (obj/set! "fontStyle" font-style)
           (obj/set! "fontWeight" font-weight))

       (= grow-type :auto-width)
       (obj/set! "whiteSpace" "pre")

       ;; Feature 20 — advanced underline controls (per-range). Additive: only
       ;; applied when the attr is present. Absent = existing behavior.
       (some? (:text-decoration-style data))
       (obj/set! "textDecorationStyle" (:text-decoration-style data))

       (and (some? (:text-decoration-thickness data))
            (not= "" (:text-decoration-thickness data)))
       (obj/set! "textDecorationThickness" (str (:text-decoration-thickness data) "px"))

       (some? (:text-decoration-offset data))
       (obj/set! "textUnderlineOffset" (str (:text-decoration-offset data) "px"))

       (some? (:text-decoration-skip-ink data))
       (obj/set! "textDecorationSkipInk" (:text-decoration-skip-ink data))

       (some? (:text-decoration-color data))
       (obj/set! "textDecorationColor" (:text-decoration-color data))

       ;; Feature 78 — superscript / subscript. `:baseline-shift` maps to the
       ;; CSS `vertical-align` property (super/sub). Additive.
       (some? (:baseline-shift data))
       (obj/set! "verticalAlign" (:baseline-shift data))

       ;; Feature 18 — hyperlink. v1 export/preview affordance: a linked range
       ;; gets a pointer cursor and (if no explicit underline/line-through) an
       ;; underline, mirroring Figma's default link styling. Prototype click
       ;; interception is deferred.
       (some? (:hyperlink data))
       (-> (obj/set! "cursor" "pointer")
           (obj/set! "textDecoration"
                     (if (or (= text-decoration "underline")
                             (= text-decoration "line-through")
                             (= text-decoration "overline"))
                       text-decoration
                       "underline")))

       ;; Feature 12 — OpenType features. The model attr is already a CSS
       ;; `font-feature-settings` string, so it is emitted verbatim. Additive:
       ;; only applied when present; absent = existing behavior. The live
       ;; contenteditable round-trips the same real CSS property.
       (some? (:font-feature-settings data))
       (obj/set! "fontFeatureSettings" (:font-feature-settings data))

       ;; Feature 13 — variable-font axes. The model attr is already a CSS
       ;; `font-variation-settings` string, emitted verbatim. Additive.
       (some? (:font-variation-settings data))
       (obj/set! "fontVariationSettings" (:font-variation-settings data))))))
