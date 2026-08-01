;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.react
  "Generate React (JSX) and Next.js components from a selection of Penpot
  shapes. Uses absolute positioning with inline styles so the output is a
  single self-contained component file."
  (:require
   ["react-dom/server" :as rds]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.config :as cfg]
   [app.main.ui.shapes.text.html-text :as text]
   [app.util.code-gen.common :as cgc]
   [app.util.code-gen.frameworks.common :as fc]
   [app.util.code-gen.markup-svg :as markup-svg]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- indent [n] (str/repeat "  " n))

(defn- quote-js [s] (str/concat "\"" (str/replace (str s) "\"" "\\\\\"") "\""))

(defn- style-val [v]
  (cond
    (number? v) (dm/str v)
    (keyword? v) (quote-js (d/name v))
    (string? v) (if (re-matches #"^-?\d+(\.\d+)?$" v) v (quote-js v))
    :else (quote-js (str v))))

(defn- style->js
  "Turn a list of [camel-key value] pairs into a JSX style object body
  string (without the enclosing braces)."
  [pairs]
  (->> pairs
       (keep (fn [[k v]]
               (when (some? v)
                 (dm/str (d/name k) ": " (style-val v)))))
       (str/join ", ")))

(defn- radius-style [r]
  (cond
    (= r :circle) [["borderRadius" "50%"]]
    (vector? r) (let [[r1 r2 r3 r4] r]
                  (if (and (= r1 r2) (= r2 r3) (= r3 r4))
                    [["borderRadius" r1]]
                    [["borderRadius" (dm/str (fc/fmt-num r1) "px "
                                             (fc/fmt-num r2) "px "
                                             (fc/fmt-num r3) "px "
                                             (fc/fmt-num r4) "px")]]))
    :else nil))

(defn- background-style [bg]
  (when bg
    (case (:type bg)
      :solid   [["backgroundColor" (:color bg)]]
      :image   [["backgroundImage" (dm/fmt "url(%)" (:image bg))]
                ["backgroundSize" "cover"]
                ["backgroundPosition" "center"]
                ["backgroundRepeat" "no-repeat"]
                (when-not (= (:opacity bg) 1)
                  ["opacity" (:opacity bg)])]
      :gradient (let [dir (if (= (:gradient-type bg) "radial") "radial" "linear")
                       stops (fc/gradient-stops->string (:stops bg))]
                  [["backgroundImage" (dm/fmt "%-gradient(%)" dir stops)]])
      nil)))

(defn- border-style [border]
  (when border
    [["borderWidth" (:width border)]
     ["borderStyle" (or ({:solid "solid" :dashed "dashed" :dotted "dotted"}
                         (:style border)) "solid")]
     ["borderColor" (:color border)]
     ["boxSizing" "border"]]))

(defn- shadow-style [shadows]
  (when (seq shadows)
    [["boxShadow"
      (->> shadows
           (map (fn [{:keys [inner? x y blur spread color]}]
                  (dm/fmt "% % % % % %"
                          (if inner? "inset " "")
                          (fc/fmt-num x) (fc/fmt-num y)
                          (fc/fmt-num blur) (fc/fmt-num spread) color)))
           (str/join ", "))]]))

(defn- box-style
  "Position + size + visual style pairs for a shape."
  [objects shape origin]
  (let [pos (fc/rel-position objects shape origin)
        size (fc/shape-size shape)
        bg (fc/shape-background shape)
        border (fc/shape-stroke shape)
        radius (fc/shape-radius shape)
        shadows (fc/shape-shadow shape)
        opacity (fc/shape-opacity shape)
        rotation (fc/rotation shape)]
    (cond-> [[:position "absolute"]
             [:left (:left pos)]
             [:top (:top pos)]
             [:width (:width size)]
             [:height (:height size)]]
      (seq bg)        (into (background-style bg))
      (seq border)    (into (border-style border))
      (seq radius)    (into (radius-style radius))
      (seq shadows)   (into (shadow-style shadows))
      opacity         (conj [:opacity opacity])
      (and (cfh/frame-shape? shape) (not (:show-content shape)))
      (conj [:overflow "hidden"])
      (not (zero? rotation))
      (conj [:transform (dm/fmt "rotate(%deg)" (fc/fmt-num rotation))]))))

(defn- svg-markup
  "Inline SVG string for an svg-shape, scaled to fill its container."
  [objects shape]
  (let [raw (markup-svg/generate-svg objects shape)]
    (-> raw
        (str/replace #"<svg " "<svg style=\"width:100%;height:100%;display:block\" " 1)
        (str/replace #"<svg>" "<svg style=\"width:100%;height:100%;display:block\">" 1))))

(defn- escape-jsx-text
  "JSON-encode a text string so it can be placed inside a JSX string
  expression {\"...\"} without breaking the parser."
  [s]
  (js/JSON.stringify (str s)))

(defn- text-jsx
  "Render a text shape's content to an HTML string (rich text with inline
  styles on spans) so React can render it via dangerouslySetInnerHTML."
  [shape]
  (try
    (rds/renderToStaticMarkup
     (mf/element text/text-shape* #js {:shape shape :isCode true}))
    (catch :default _ nil)))

(defn- render-shape
  ([objects shape origin]
   (render-shape objects shape origin 1))
  ([objects shape origin level]
   (let [ind (indent level)
         style-pairs (box-style objects shape origin)
         style-str (style->js style-pairs)]
     (cond
       (fc/hidden? shape)
       nil

       (fc/svg-shape? shape)
       (dm/fmt "%<div style={{%}} dangerouslySetInnerHTML={{__html: %}} />"
               ind style-str (quote-js (or (svg-markup objects shape) "")))

       (cfh/text-shape? shape)
       (let [typo (fc/extract-typography shape)
             txt (fc/extract-text shape)
             html (text-jsx shape)
             text-pairs [[:display "flex"]
                         [:flexDirection "column"]
                         [:justifyContent ({:top "flex-start" :center "center" :bottom "flex-end"}
                                           (:vertical-align typo))]
                         [:whiteSpace "pre-wrap"]
                         [:fontFamily (quote-js (:font-family typo))]
                         [:fontSize (:font-size typo)]
                         [:fontWeight (:font-weight typo)]
                         [:fontStyle (:font-style typo)]
                         [:lineHeight (:line-height typo)]
                         [:letterSpacing (:letter-spacing typo)]
                         [:textAlign (:text-align typo)]
                         [:textTransform (:text-transform typo)]
                         [:textDecoration (:text-decoration typo)]
                         [:color (:color typo)]]
             full-pairs (into style-pairs text-pairs)]
         (if (str/blank? html)
           (dm/fmt "%<div style={{%}}>{%}</div>"
                   ind (style->js full-pairs) (escape-jsx-text txt))
           ;; Rich text: embed the rendered HTML so per-span styling is kept.
           (dm/fmt "%<div style={{%}} dangerouslySetInnerHTML={{__html: %}} />"
                   ind (style->js full-pairs) (quote-js html))))

       (cfh/image-shape? shape)
       (let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))
             url (when data (cfg/resolve-file-media data))]
         (if url
           (dm/fmt "%<img src=% style={{%}} />"
                   ind (quote-js url) style-str)
           (dm/fmt "%<div style={{%}} />" ind style-str)))

       (fc/container? shape)
       (let [children (fc/children-of objects shape)
             child-code (->> children
                             (keep #(render-shape objects % origin (inc level)))
                             (str/join "\n"))]
         (if (str/blank? child-code)
           (dm/fmt "%<div style={{%}} />" ind style-str)
           (dm/fmt "%<div style={{%}}>\n%\n%</div>"
                   ind style-str child-code (indent level))))

       :else
       (dm/fmt "%<div style={{%}} />" ind style-str)))))

(defn- imports [nextjs?]
  (if nextjs?
    "\"use client\";\n\nimport React from \"react\";\n"
    "import React from \"react\";\n"))

(defn generate-react
  ([objects shapes] (generate-react objects shapes false))
  ([objects shapes nextjs?]
   (let [roots (fc/root-originals objects shapes)
         _ (when (empty? roots) nil)
         origin (fc/selection-origin roots)
         size (fc/selection-size roots)
         body (->> roots
                   (keep #(render-shape objects % origin 1))
                   (str/join "\n"))
         comp-name (if nextjs? "Page"
                    (some-> (seq roots) first fc/component-name (or "Component")))]
     (dm/fmt
      "%\nexport default function %() {\n  return (\n    <div style={{position: \"relative\", width: %, height: %}}>\n%\n    </div>\n  );\n}\n"
      (imports nextjs?)
      comp-name
      (fc/fmt-num (:width size))
      (fc/fmt-num (:height size))
      body))))

(defn generate
  "React (JSX) component."
  [objects shapes]
  (generate-react objects shapes false))

(defn generate-nextjs
  "Next.js page component (App Router, 'use client')."
  [objects shapes]
  (generate-react objects shapes true))