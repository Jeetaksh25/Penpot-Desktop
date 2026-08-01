;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.tailwind
  "Generate a React (JSX) component styled with Tailwind CSS utility classes
  from a selection of Penpot shapes. Uses Tailwind v3+ JIT arbitrary-value
  syntax (bg-[#FF112233], rounded-[8px], shadow-[...], text-[14px],
  font-[family-name:Inter], ...) so the output needs NO tailwind.config
  entries — it drops into any Tailwind-enabled React/Next.js project as a
  single self-contained component file.

  Layout mirrors the React generator: a relative root container with
  absolutely positioned descendants, each placed relative to its parent
  (accounting for the parent's border, like the CSS padding box)."
  (:require
   ["react-dom/server" :as rds]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.config :as cfg]
   [app.main.ui.shapes.text.html-text :as text]
   [app.util.code-gen.frameworks.common :as fc]
   [app.util.code-gen.markup-svg :as markup-svg]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- indent [n] (str/repeat "  " n))

(defn- escape-jsx-text [s] (js/JSON.stringify (str s)))

(defn- quote-js [s] (str/concat "\"" (str/replace (str s) "\"" "\\\\\"") "\""))

(defn- tw-escape
  "Escape a string for use inside a Tailwind arbitrary value [...]: spaces
  become underscores (Tailwind's space syntax) and literal underscores are
  backslash-escaped so they survive verbatim."
  [s]
  (-> (str s)
      (str/replace "_" "\\\\_")
      (str/replace " " "_")))

;; ---------------------------------------------------------------------------
;; Visual property -> Tailwind class fragment(s)
;; ---------------------------------------------------------------------------

(defn- bg-class [bg]
  (cond
    (nil? bg)               nil
    (= (:type bg) :solid)   (dm/fmt "bg-[%]" (:color bg))
    (= (:type bg) :image)   (when (:image bg)
                             ;; Single-quoted url so it is safe inside the
                             ;; double-quoted JSX className attribute; the
                             ;; cover/center utilities size/position it.
                             (dm/str "bg-[url('" (tw-escape (:image bg)) "')] bg-cover bg-center"))
    (= (:type bg) :gradient)
    (let [dir   (if (= (:gradient-type bg) "radial") "radial-gradient" "linear-gradient")
          stops (tw-escape (fc/gradient-stops->string (:stops bg)))]
      (dm/fmt "bg-[%(%)]" dir stops))
    :else nil))

(defn- border-classes [border]
  (when border
    (let [style (or ({:solid "solid" :dashed "dashed" :dotted "dotted"} (:style border)) "solid")]
      [(dm/str "border-" style)
       (dm/str "border-[" (fc/fmt-num (:width border)) "px]")
       (dm/fmt "border-[%]" (:color border))])))

(defn- radius-classes [r]
  (cond
    (= r :circle) ["rounded-full"]
    (vector? r)   (let [[r1 r2 r3 r4] r]
                    (if (and (= r1 r2) (= r2 r3) (= r3 r4))
                      [(dm/str "rounded-[" (fc/fmt-num r1) "px]")]
                      [(dm/str "rounded-tl-[" (fc/fmt-num r1) "px]")
                       (dm/str "rounded-tr-[" (fc/fmt-num r2) "px]")
                       (dm/str "rounded-br-[" (fc/fmt-num r3) "px]")
                       (dm/str "rounded-bl-[" (fc/fmt-num r4) "px]")]))
    :else nil))

(defn- shadow-class [shadows]
  (when (seq shadows)
    (let [parts (->> shadows
                    (map (fn [{:keys [inner? x y blur spread color]}]
                           (dm/str (if inner? "inset_" "")
                                   (fc/fmt-num x) "px_"
                                   (fc/fmt-num y) "px_"
                                   (fc/fmt-num blur) "px_"
                                   (fc/fmt-num spread) "px_"
                                   color)))
                    (str/join ","))]
      [(dm/str "shadow-[" parts "]")])))

(defn- opacity-class [opacity]
  (when (some? opacity)
    (dm/str "opacity-[" (fc/fmt-num opacity) "]")))

(defn- rotate-class [rotation]
  (when (not (zero? rotation))
    (dm/str "rotate-[" (fc/fmt-num rotation) "deg]")))

(defn- box-classes
  "Position + size + visual Tailwind classes for a shape (joined string)."
  [objects shape origin]
  (let [pos      (fc/rel-position objects shape origin)
        size     (fc/shape-size shape)
        bg       (fc/shape-background shape)
        border   (fc/shape-stroke shape)
        radius   (fc/shape-radius shape)
        shadows  (fc/shape-shadow shape)
        opacity  (fc/shape-opacity shape)
        rotation (fc/rotation shape)
        bgc      (bg-class bg)
        base     [(dm/str "absolute left-[" (fc/fmt-num (:left pos)) "px]")
                 (dm/str "top-[" (fc/fmt-num (:top pos)) "px]")
                 (dm/str "w-[" (fc/fmt-num (:width size)) "px]")
                 (dm/str "h-[" (fc/fmt-num (:height size)) "px]")]
        classes  (cond-> base
                   (some? bgc)                                  (conj bgc)
                   (some? border)                               (into (border-classes border))
                   (some? radius)                               (into (radius-classes radius))
                   (seq shadows)                                (into (shadow-class shadows))
                   (some? opacity)                              (conj (opacity-class opacity))
                   (and (cfh/frame-shape? shape)
                        (not (:show-content shape)))            (conj "overflow-hidden")
                   (not (zero? rotation))                       (conj (rotate-class rotation)))]
    (str/join " " (remove str/blank? classes))))

(defn- text-classes [typo]
  (let [wt     (js/parseInt (or (:font-weight typo) "400") 10)
        weight (cond (>= wt 700) "font-bold"
                     (>= wt 600) "font-semibold"
                     (>= wt 500) "font-medium"
                     :else       "font-normal")
        talign ({"left" "text-left" "center" "text-center"
                 "right" "text-right" "justify" "text-justify"}
                (:text-align typo) "text-left")
        tcase  ({"uppercase" "uppercase" "lowercase" "lowercase"
                "capitalize" "capitalize"} (:text-transform typo))
        tdec   ({"line-through" "line-through" "underline" "underline"}
               (:text-decoration typo))
        valign ({"top" "justify-start" "center" "justify-center"
                 "bottom" "justify-end"}
                (:vertical-align typo) "justify-start")
        ls     (:letter-spacing typo)
        ls?    (and (not (str/blank? ls)) (not= ls "0"))
        ls-cls (when ls?
                 (dm/str "tracking-[" ls
                         (if (re-matches #"-?\d+(\.\d+)?" ls) "px" "") "]"))
        lh     (:line-height typo)
        lh-cls (when (not (str/blank? lh)) (dm/str "leading-[" lh "]"))
        fam    (:font-family typo)
        fam-cls (when (not (str/blank? fam))
                  (dm/fmt "font-[family-name:%]" (tw-escape fam)))
        base   [(dm/str "text-[" (:font-size typo) "px]")
                weight
                "flex flex-col"
                valign
                talign]]
    (-> base
        (cond-> (some? tcase)            (conj tcase)
                (some? tdec)             (conj tdec)
                (= (:font-style typo) "italic") (conj "italic")
                (some? fam-cls)          (conj fam-cls)
                (some? lh-cls)           (conj lh-cls)
                (some? ls-cls)           (conj ls-cls)
                (not (str/blank? (:color typo))) (conj (dm/fmt "text-[%]" (:color typo))))
        (->> (remove str/blank?) (str/join " ")))))

;; ---------------------------------------------------------------------------
;; Shape rendering
;; ---------------------------------------------------------------------------

(defn- svg-markup
  "Inline SVG string for an svg-shape, scaled to fill its container."
  [objects shape]
  (let [raw (markup-svg/generate-svg objects shape)]
    (-> raw
        (str/replace #"<svg " "<svg style=\"width:100%;height:100%;display:block\" " 1)
        (str/replace #"<svg>" "<svg style=\"width:100%;height:100%;display:block\">" 1))))

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
   (let [ind     (indent level)
         classes (box-classes objects shape origin)]
     (cond
       (fc/hidden? shape)
       nil

       (fc/svg-shape? shape)
       (dm/fmt "%<div className=\"%\" dangerouslySetInnerHTML={{__html: %}} />"
               ind classes (quote-js (or (svg-markup objects shape) "")))

       (cfh/text-shape? shape)
       (let [typo    (fc/extract-typography shape)
             txt     (fc/extract-text shape)
             html    (text-jsx shape)
             full    (dm/str classes " " (text-classes typo))]
         (if (str/blank? html)
           (dm/fmt "%<div className=\"%\">{%}</div>" ind full (escape-jsx-text txt))
           (dm/fmt "%<div className=\"%\" dangerouslySetInnerHTML={{__html: %}} />"
                   ind full (quote-js html))))

       (cfh/image-shape? shape)
       (let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))
             url  (when data (cfg/resolve-file-media data))]
         (if url
           (dm/fmt "%<img src=% className=\"% object-cover\" />" ind (quote-js url) classes)
           (dm/fmt "%<div className=\"%\" />" ind classes)))

       (fc/container? shape)
       (let [children   (fc/children-of objects shape)
             child-code (->> children
                            (keep #(render-shape objects % origin (inc level)))
                            (str/join "\n"))]
         (if (str/blank? child-code)
           (dm/fmt "%<div className=\"%\" />" ind classes)
           (dm/fmt "%<div className=\"%\">\n%\n%</div>" ind classes child-code (indent level))))

       :else
       (dm/fmt "%<div className=\"%\" />" ind classes)))))

;; ---------------------------------------------------------------------------
;; Component scaffolding
;; ---------------------------------------------------------------------------

(defn- tailwind-header [nextjs?]
  (dm/str
   "// Generated with Tailwind CSS v3+ (JIT arbitrary values).\n"
   "// No tailwind.config entries are required — every value is inline\n"
   "// as an arbitrary utility (bg-[...], rounded-[...], text-[...]).\n\n"
   (when nextjs? "\"use client\";\n\n")
   "import React from \"react\";\n\n"))

(defn generate
  "React (JSX) component styled with Tailwind CSS utility classes. When
  `nextjs?` is true, emits a Next.js App Router page (a 'use client'
  directive and the component named Page)."
  ([objects shapes] (generate objects shapes false))
  ([objects shapes nextjs?]
   (let [roots     (fc/root-originals objects shapes)
         origin    (fc/selection-origin roots)
         size      (fc/selection-size roots)
         body      (->> roots
                       (keep #(render-shape objects % origin 1))
                       (str/join "\n"))
         comp-name (if nextjs? "Page"
                     (or (some-> (seq roots) first fc/component-name) "Component"))
         header    (tailwind-header nextjs?)]
     (dm/fmt
      "%export default function %() {\n  return (\n    <div className=\"relative w-[%px] h-[%px]\">\n%\n    </div>\n  );\n}\n"
      header comp-name (fc/fmt-num (:width size)) (fc/fmt-num (:height size)) body))))

(defn generate-nextjs
  "Next.js page component (App Router, 'use client') styled with Tailwind
  CSS utility classes (JIT arbitrary values)."
  [objects shapes]
  (generate objects shapes true))