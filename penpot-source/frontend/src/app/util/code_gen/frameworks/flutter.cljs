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
   [app.util.code-gen.code-connect :as cc]
   [app.util.code-gen.frameworks.common :as fc]
   [app.util.code-gen.frameworks.components :as fcomp]
   [cuerdas.core :as str]))

(defn- indent [n] (str/repeat "  " n))

(defn- escape-dart [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")
      (str/replace "$" "\\$")))

(defn- dart-string [s] (dm/fmt "'%'" (escape-dart s)))

(defn- escape-dart-ml
  "Escape a string for a single-quoted Dart string literal, also turning
  real newlines into `\\n` escapes (a single-quoted Dart literal cannot
  span a physical line). Used for embedding raw SVG markup."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\'")
      (str/replace "$" "\\$")
      (str/replace "\r" "")
      (str/replace "\n" "\\n")))

(defn- dart-svg-string [s] (dm/fmt "'%'" (escape-dart-ml s)))

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
         opacity-wrap (fn [c] (if opacity (dm/fmt "Opacity(opacity: %, child: %)" (fc/fmt-num opacity) c) c))
         cc-binding (fc/code-connect-binding objects shape)]
     (cond
       (fc/hidden? shape) nil

       ;; Code Connect binding (P1.08): emit the mapped code component widget
       ;; `Tag(props)` wrapped in Positioned for layout, instead of a generic
       ;; Container/Stack.
       (some? cc-binding)
       (let [binding cc-binding]
         (let [ci (indent (+ level 1))
               tag (:tag binding)
               props (cc/format-props-dart binding)]
           (positioned objects shape origin
                       (opacity-wrap
                        (dm/fmt "%// Code Connect: %\n%%(%)"
                                ci tag ci tag props)))))

       ;; Hoisted component instance → emit a reference instead of recursing.
       (fc/hoisted-instance? shape)
       (positioned objects shape origin
                   (opacity-wrap
                    (dm/fmt "%()" (fc/hoisted-name shape))))

       (fc/svg-shape? shape)
       ;; Native SVG via flutter_svg `SvgPicture.string`, fed the same `<svg>`
       ;; string the web frameworks produce (carries viewBox, transform,
       ;; fills, masks). Works for every svg-shape — no PNG fallback needed.
       (let [xml (fc/svg-xml objects shape)
             size (fc/shape-size shape)]
         (positioned objects shape origin
                     (opacity-wrap
                      (if (str/blank? xml)
                        "SizedBox()"
                        (dm/fmt "SvgPicture.string(\n          %,\n          width: %,\n          height: %,\n        )"
                                (dart-svg-string xml)
                                (fc/fmt-num (:width size))
                                (fc/fmt-num (:height size)))))))

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

(defn- has-svg?
  "True when any svg-shape is reachable from `shapes` through `objects`."
  [objects shapes]
  (letfn [(walk [s] (or (fc/svg-shape? s)
                        (some walk (fc/children-of objects s))))]
    (some walk shapes)))

(defn- render-widget
  "Render a Flutter `StatelessWidget` source string for `shapes` placed
  relative to `origin`, sized to `size`. `hoist-map` (when non-nil) makes
  `render-shape` emit `CompName()` references for hoisted instances, and
  `comp-names` adds one `import 'widgets/<name>.dart';` line per hoisted
  component so those references resolve. The `flutter_svg` import is
  emitted only when an svg-shape is reachable, so a non-SVG, non-hoisted
  selection is byte-identical to the legacy single-file output."
  [objects shapes origin size comp-name hoist-map comp-names]
  (let [svg? (has-svg? objects shapes)
        imports (dm/str "import 'package:flutter/material.dart';\n"
                        (when svg? "import 'package:flutter_svg/flutter_svg.dart';\n")
                        (str/join "" (map #(dm/str "import 'widgets/" (fc/snake-name %) ".dart';\n") comp-names)))
        body (binding [fc/*hoist-map* hoist-map
                       fc/*framework-type* "flutter"]
              (->> shapes
                    (keep #(render-shape objects % origin 2))
                    (map #(str/concat (indent 1) %))
                    (str/join ",\n")))]
    (dm/fmt
     "%\nclass % extends StatelessWidget {\n  const %({super.key});\n\n  @override\n  Widget build(BuildContext context) {\n    return SizedBox(\n      width: %,\n      height: %,\n      child: Stack(\n        children: [\n%,\n        ],\n      ),\n    );\n  }\n}\n"
     imports comp-name comp-name
     (fc/fmt-num (:width size))
     (fc/fmt-num (:height size))
     body)))

(defn generate
  "Single-string Inspect-panel preview (no hoisting — the preview can't
  represent multi-file components)."
  [objects shapes]
  (let [roots (fc/root-originals objects shapes)]
    (render-widget objects roots
                   (fc/selection-origin roots)
                   (fc/selection-size roots)
                   (comp-name-from roots) nil nil)))

(defn- render-hoisted-component
  "Render one hoisted component as its own `lib/widgets/<name>.dart`
  source string. The definition instance's children are rendered relative
  to the instance head's origin (with `*hoist-map*` rebound to nil so
  nested instances are not re-hoisted; no sibling imports — a hoisted
  component flattens its instances inline)."
  [objects spec]
  (let [{:keys [comp-name children origin size]} spec]
    (render-widget objects children origin size comp-name nil nil)))

;; ---------------------------------------------------------------------------
;; Multi-file Flutter project (Feature 2 code export)
;; ---------------------------------------------------------------------------

(defn- comp-name-from [roots]
  (or (some-> (seq roots) first fc/component-name) "ExportedWidget"))

(defn- pubspec-yaml []
  "name: penpot_export\ndescription: Generated with Ovion Desktop.\npublish_to: 'none'\nversion: 1.0.0+1\n\nenvironment:\n  sdk: ^3.5.0\n\ndependencies:\n  flutter:\n    sdk: flutter\n  flutter_svg: ^2.0.10\n\nflutter:\n  uses-material-design: true\n")

(defn- analysis-options []
  "include: package:flutter_lints/flutter.yaml\n")

(defn- flutter-readme [comp-name]
  (dm/fmt
   "# %\n\nGenerated with Ovion Desktop (Flutter).\n\n## Run\n\n```bash\nflutter pub get\nflutter run\n```\n\nThe widget lives in `lib/%.dart` — a `StatelessWidget` using a `Stack`\nwith `Positioned` children for absolute layout.\n"
   comp-name (fc/snake-name comp-name)))

(defn generate-project
  "Multi-file Flutter project. The `:primary` file (`lib/<name>.dart`) is
  the widget for the selection; native SVG shapes are emitted via
  `flutter_svg` `SvgPicture.string` (so `:uses-rn-svg?` is true whenever an
  svg-shape is reachable — `pubspec.yaml` already declares `flutter_svg`).
  Component-instance hoisting (Phase E) emits one `lib/widgets/<name>.dart`
  per hoisted component and replaces every instance with a `CompName()`
  reference."
  [objects shapes opts]
  (let [roots (fc/root-originals objects shapes)
        comp-name (comp-name-from roots)
        file-name (fc/snake-name comp-name)
        primary-path (dm/str "lib/" file-name ".dart")
        hoist (fcomp/collect-hoistable objects roots)
        comp-names (mapv :comp-name (:specs hoist))
        primary (render-widget objects roots
                               (fc/selection-origin roots)
                               (fc/selection-size roots)
                               comp-name (:hoist-map hoist) comp-names)
        comp-files (into {}
                        (for [spec (:specs hoist)]
                          [(dm/str "lib/widgets/" (fc/snake-name (:comp-name spec)) ".dart")
                           (render-hoisted-component objects spec)]))
        uses-svg? (boolean (or (:uses-rn-svg? opts) (has-svg? objects roots)))]
    {:files (merge {primary-path primary
                    "pubspec.yaml" (pubspec-yaml)
                    "analysis_options.yaml" (analysis-options)
                    "README.md" (flutter-readme comp-name)}
                   comp-files)
     :binary-assets []
     :raster-requests []
     :primary primary-path
     :label "Flutter"
     :uses-rn-svg? uses-svg?
     :uses-masked-view? false}))