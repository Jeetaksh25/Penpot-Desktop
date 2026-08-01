;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.flutter
  "Generate a Flutter (Dart) widget from a selection of Penpot shapes. Uses
  a Stack with Positioned children for absolute layout. Colors are emitted
  as Color(0xAARRGGBB). Remote images use Image.network."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.config :as cfg]
   [app.util.code-gen.frameworks.common :as fc]
   [cuerdas.core :as str]))

(defn- indent [n] (str/repeat "  " n))

(defn- escape-dart [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")
      (str/replace "$" "\\$")))

(defn- dart-string [s] (dm/fmt "'%'" (escape-dart s)))

(defn- rgba-string->color [rgba-str]
  (let [nums (->> (re-seq #"\d+\.?\d*" (or rgba-str ""))
                  (map #(js/parseFloat %)))]
    (if (>= (count nums) 4)
      (let [[r g b a] nums
            to2 (fn [x] (let [s (.toString (js/Math.round x) 16)]
                          (if (< (count s) 2) (str/concat "0" s) s)))
            alpha (js/Math.round (* (d/nilv a 1) 255))]
        (dm/str "Color(0x" (to2 alpha) (to2 r) (to2 g) (to2 b) ")"))
      "Color(0xFF000000)")))

(defn- argb-hex->color [hex]
  (if (str/blank? hex) "Color(0x00000000)"
      (dm/fmt "Color(0x%)" (str/replace hex #"^#" ""))))

(defn- fill-color [shape]
  (let [fill (fc/first-fill shape)]
    (cond
      (nil? fill) nil
      (some? (:fill-image fill)) "image"
      (some? (:fill-color-gradient fill)) "gradient"
      :else (fc/fill->argb-hex fill))))

(defn- radius [shape]
  (let [r (fc/shape-radius shape)]
    (cond
      (= r :circle) :circle
      (vector? r) (let [[r1 r2 r3 r4] r]
                    (if (and (= r1 r2) (= r2 r3) (= r3 r4)) [r1 r1 r1 r1]
                        [r1 r2 r3 r4]))
      :else nil)))

(defn- border [shape]
  (let [stroke (first (:strokes shape))]
    (when (and stroke (not= :none (:stroke-style stroke)))
      {:color (argb-hex->color (fc/fill->argb-hex {:color (:stroke-color stroke)
                                                  :opacity (:stroke-opacity stroke)}))
       :width (d/nilv (:stroke-width stroke) 1)})))

(defn- box-decoration [shape]
  (let [bg (fill-color shape)
        r (radius shape)
        br (border shape)]
    (cond
      (= bg :image) "BoxDecoration(color: Color(0x00000000))"
      (= bg :gradient) "BoxDecoration(color: Color(0x00000000)) // gradient"
      :else
      (let [color (when bg (dm/fmt "color: %, " (argb-hex->color bg)))
            radius-attr (cond
                          (= r :circle) "shape: BoxShape.circle, "
                          (vector? r) (let [[r1 r2 r3 r4] r]
                                        (if (and (= r1 r2) (= r2 r3) (= r3 r4))
                                          (dm/fmt "borderRadius: BorderRadius.circular(%), " (fc/fmt-num r1))
                                          (dm/fmt "borderRadius: BorderRadius.only(topLeft: Radius.circular(%), topRight: Radius.circular(%), bottomRight: Radius.circular(%), bottomLeft: Radius.circular(%)), " (fc/fmt-num r1) (fc/fmt-num r2) (fc/fmt-num r3) (fc/fmt-num r4))))
                          :else "")
            border-attr (when br
                          (dm/fmt "border: Border.all(color: %, width: %), " (:color br) (fc/fmt-num (:width br))))]
        (dm/str "BoxDecoration(" (or color "") radius-attr (or border-attr "") ")")))))

(defn- text-align [a]
  ({"left" "TextAlign.left" "center" "TextAlign.center" "right" "TextAlign.right" "justify" "TextAlign.justify"} a "TextAlign.left"))

(defn- v-align [a]
  ({"top" "Alignment.topLeft" "center" "Alignment.center" "bottom" "Alignment.bottomLeft"} a "Alignment.topLeft"))

(defn- font-weight [w]
  (let [n (js/parseInt (or w "400") 10)]
    (cond
      (>= n 800) "FontWeight.w900"
      (>= n 600) "FontWeight.w700"
      (>= n 500) "FontWeight.w500"
      :else "FontWeight.w400")))

(defn- positioned
  "Wrap a child widget string in a Positioned using the shape's box."
  [objects shape origin child]
  (let [pos (fc/rel-position objects shape origin)
        size (fc/shape-size shape)]
    (dm/fmt "Positioned(\n        left: %,\n        top: %,\n        width: %,\n        height: %,\n        child: %,\n      )"
            (fc/fmt-num (:left pos))
            (fc/fmt-num (:top pos))
            (fc/fmt-num (:width size))
            (fc/fmt-num (:height size))
            child)))

(defn- render-shape
  ([objects shape origin] (render-shape objects shape origin 1))
  ([objects shape origin level]
   (let [ind (indent level)
         opacity (fc/shape-opacity shape)
         opacity-wrap (fn [c] (if opacity (dm/fmt "Opacity(opacity: %, child: %)" (fc/fmt-num opacity) c) c))]
     (cond
       (fc/hidden? shape) nil

       (fc/svg-shape? shape)
       (positioned objects shape origin
                    (opacity-wrap
                     (dm/fmt "// SVG shape — render with flutter_svg\n        SizedBox()")))

       (cfh/text-shape? shape)
       (let [typo (fc/extract-typography shape)
             txt (fc/extract-text shape)]
         (positioned objects shape origin
                     (opacity-wrap
                      (dm/fmt "Align(\n          alignment: %,\n          child: Text(\n            %,\n            style: TextStyle(\n              fontFamily: %,\n              fontSize: %,\n              fontWeight: %,\n              fontStyle: %,\n              color: %,\n              decoration: TextDecoration.none,\n              height: %,\n              letterSpacing: %,\n            ),\n            textAlign: %,\n          ),\n        )"
                              (v-align (:vertical-align typo))
                              (dart-string txt)
                              (dart-string (:font-family typo))
                              (fc/fmt-num (:font-size typo))
                              (font-weight (:font-weight typo))
                              (if (= (:font-style typo) "italic") "FontStyle.italic" "FontStyle.normal")
                              (rgba-string->color (:color typo))
                              (:line-height typo)
                              (:letter-spacing typo)
                              (text-align (:text-align typo))))))

       (cfh/image-shape? shape)
       (let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))
             url (when data (cfg/resolve-file-media data))]
         (positioned objects shape origin
                     (opacity-wrap
                      (if url
                        (dm/fmt "Image.network(\n          %,\n          fit: BoxFit.cover,\n        )" (dart-string url))
                        "SizedBox()"))))

       (fc/container? shape)
       (let [children (fc/children-of objects shape)
             child-code (->> children
                            (keep #(render-shape objects % origin (+ level 2)))
                            (map #(str/concat (indent (+ level 1)) %))
                            (str/join ",\n"))
             inner (if (str/blank? child-code)
                     "SizedBox()"
                     (dm/fmt "Stack(\n          children: [\n%,\n          ],\n        )" child-code))
             bg (fill-color shape)
             inner (if (= bg :image)
                    (let [data (or (:metadata shape) (:fill-image shape))
                          url (when data (cfg/resolve-file-media data))]
                      (if url
                        (dm/fmt "Stack(\n          children: [\n            Image.network(%, fit: BoxFit.cover),\n%,\n          ],\n        )" (dart-string url) child-code)
                        "SizedBox()"))
                    inner)]
         (positioned objects shape origin
                     (opacity-wrap
                      (dm/fmt "Container(\n          decoration: %,\n          child: %,\n        )"
                              (box-decoration shape) inner))))

       :else
       (positioned objects shape origin
                   (opacity-wrap
                    (dm/fmt "Container(\n          decoration: %,\n        )" (box-decoration shape))))))))

(defn generate
  [objects shapes]
  (let [roots (fc/root-originals objects shapes)
        origin (fc/selection-origin roots)
        size (fc/selection-size roots)
        body (->> roots
                  (keep #(render-shape objects % origin 2))
                  (map #(str/concat (indent 1) %))
                  (str/join ",\n"))
        comp-name (or (some-> (seq roots) first fc/component-name) "ExportedWidget")]
    (dm/fmt
     "import 'package:flutter/material.dart';\n\nclass % extends StatelessWidget {\n  const %({super.key});\n\n  @override\n  Widget build(BuildContext context) {\n    return SizedBox(\n      width: %,\n      height: %,\n      child: Stack(\n        children: [\n%,\n        ],\n      ),\n    );\n  }\n}\n"
     comp-name comp-name
     (fc/fmt-num (:width size))
     (fc/fmt-num (:height size))
     body)))