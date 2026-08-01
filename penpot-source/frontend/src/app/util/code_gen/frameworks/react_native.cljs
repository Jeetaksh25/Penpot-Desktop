;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.react-native
  "Generate a React Native component (JSX) from a selection of Penpot
  shapes. Uses absolute positioning (RN supports position: 'absolute')
  with inline style objects."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.config :as cfg]
   [app.util.code-gen.frameworks.common :as fc]
   [cuerdas.core :as str]))

(defn- indent [n] (str/repeat "  " n))

(defn- quote-js [s] (str/concat "\"" (str/replace (str s) "\"" "\\\\\"") "\""))

(defn- style-val [v]
  (cond
    (number? v) (dm/str v)
    (keyword? v) (quote-js (d/name v))
    (string? v) (if (re-matches #"^-?\d+(\.\d+)?$" v) v (quote-js v))
    :else (quote-js (str v))))

(defn- style->js [pairs]
  (->> pairs
       (keep (fn [[k v]] (when (some? v) (dm/str (d/name k) ": " (style-val v)))))
       (str/join ", ")))

(defn- radius-style [r]
  (cond
    (= r :circle) [["borderRadius" "50%"]]
    (vector? r) (let [[r1 r2 r3 r4] r]
                  (if (and (= r1 r2) (= r2 r3) (= r3 r4))
                    [["borderRadius" r1]]
                    [["borderTopLeftRadius" r1]
                     ["borderTopRightRadius" r2]
                     ["borderBottomRightRadius" r3]
                     ["borderBottomLeftRadius" r4]]))
    :else nil))

(defn- background-style [bg]
  (when bg
    (case (:type bg)
      :solid   [["backgroundColor" (:color bg)]]
      :image   [["backgroundColor" "transparent"]]
      :gradient [["backgroundColor" (:color (first (:stops bg)))]] ;; RN has no CSS gradient; approx with first stop.
      nil)))

(defn- border-style [border]
  (when border
    [["borderWidth" (:width border)]
     ["borderStyle" (or ({:solid "solid" :dashed "dashed" :dotted "dotted"} (:style border)) "solid")]
     ["borderColor" (:color border)]]))

(defn- shadow-style [shadows]
  (when (seq shadows)
    ;; RN (iOS) shadow props. Android would need elevation; emitted as a best-effort.
    (let [s (first shadows)]
      [["shadowColor" (:color s)]
       ["shadowOffset" (dm/fmt "{width: %, height: %}" (fc/fmt-num (:x s)) (fc/fmt-num (:y s)))]
       ["shadowOpacity" 1]
       ["shadowRadius" (:blur s)]])))

(defn- box-style [objects shape origin]
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
      (conj [:transform (dm/fmt "[{rotate: \"%deg\"}]" (fc/fmt-num rotation))]))))

(defn- text-style [typo]
  [[:fontFamily (quote-js (:font-family typo))]
   [:fontSize (:font-size typo)]
   [:fontWeight (quote-js (:font-weight typo))]
   [:fontStyle (:font-style typo)]
   [:lineHeight (:line-height typo)]
   [:letterSpacing (:letter-spacing typo)]
   [:textAlign (:text-align typo)]
   [:textTransform (:text-transform typo)]
   [:textDecorationLine (:text-decoration typo)]
   [:color (:color typo)]])

(defn- image-source [shape]
  (let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))]
    (when data
      (dm/fmt "{uri: %}" (quote-js (cfg/resolve-file-media data))))))

(defn- render-shape
  ([objects shape origin] (render-shape objects shape origin 1))
  ([objects shape origin level]
   (let [ind (indent level)
         style-str (style->js (box-style objects shape origin))]
     (cond
       (fc/hidden? shape) nil

       (fc/svg-shape? shape)
       (dm/fmt "%// SVG shape — render with react-native-svg\n%<View style={{%}} />"
               ind ind style-str)

       (cfh/text-shape? shape)
       (let [typo (fc/extract-typography shape)
             txt (fc/extract-text shape)
             full (style->js (into (box-style objects shape origin) (text-style typo)))]
         (dm/fmt "%<View style={{%}}>\n%  <Text style={{%}}>{%}</Text>\n%</View>"
                 ind full ind (style->js (text-style typo))
                 (escape-jsx-text txt) ind))

       (cfh/image-shape? shape)
       (let [src (image-source shape)]
         (if src
           (dm/fmt "%<Image source={{%}} style={{%}} resizeMode=\"cover\" />"
                   ind src style-str)
           (dm/fmt "%<View style={{%}} />" ind style-str)))

       (fc/container? shape)
       (let [bg (fc/shape-background shape)
             children (fc/children-of objects shape)
             child-code (->> children
                            (keep #(render-shape objects % origin (inc level)))
                            (str/join "\n"))]
         (if (= :image (:type bg))
           ;; Image background wrapping the children.
           (let [src (image-source shape)]
             (if (str/blank? child-code)
               (dm/fmt "%<ImageBackground source={{%}} style={{%}} resizeMode=\"cover\" />"
                       ind src style-str)
               (dm/fmt "%<ImageBackground source={{%}} style={{%}} resizeMode=\"cover\">\n%\n%</ImageBackground>"
                       ind src style-str child-code ind)))
           (if (str/blank? child-code)
             (dm/fmt "%<View style={{%}} />" ind style-str)
             (dm/fmt "%<View style={{%}}>\n%\n%</View>" ind style-str child-code ind))))

       :else
       (dm/fmt "%<View style={{%}} />" ind style-str)))))

(defn escape-jsx-text [s]
  (js/JSON.stringify (str s)))

(defn generate
  [objects shapes]
  (let [roots (fc/root-originals objects shapes)
        origin (fc/selection-origin roots)
        size (fc/selection-size roots)
        body (->> roots
                  (keep #(render-shape objects % origin 1))
                  (str/join "\n"))
        comp-name (or (some-> (seq roots) first fc/component-name) "Component")]
    (dm/fmt
     "import React from \"react\";\nimport { View, Text, Image, ImageBackground } from \"react-native\";\n\nexport default function %() {\n  return (\n    <View style={{position: \"relative\", width: %, height: %}}>\n%\n    </View>\n  );\n}\n"
     comp-name
     (fc/fmt-num (:width size))
     (fc/fmt-num (:height size))
     body)))