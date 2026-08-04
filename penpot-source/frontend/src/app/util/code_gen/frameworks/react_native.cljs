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
   [app.util.code-gen.code-connect :as cc]
   [app.util.code-gen.frameworks.common :as fc]
   [app.util.code-gen.frameworks.components :as fcomp]
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
         style-str (style->js (box-style objects shape origin))
         cc-binding (fc/code-connect-binding objects shape)]
     (cond
       (fc/hidden? shape) nil

       ;; Code Connect binding (P1.08): emit the mapped code component tag
       ;; with positioning style + authored props instead of a generic View.
       (some? cc-binding)
       (let [binding cc-binding]
         (let [props (cc/format-props-jsx binding)
               style-str (style->js (box-style objects shape origin))
               attrs (cond-> (dm/str "style={{" style-str "}")
                       (str/not-blank? props) (dm/str " " props))
               tag (:tag binding)]
           (dm/fmt "%{/* Code Connect: % */}\n%<% % />"
                   ind tag ind tag attrs)))

       ;; Hoisted component instance → emit a reference instead of recursing.
       (fc/hoisted-instance? shape)
       (let [comp-name (fc/hoisted-name shape)
             pos (fc/rel-position objects shape origin)
             size (fc/shape-size shape)]
         (dm/fmt "%<% style={{position: \"absolute\", left: %, top: %, width: %, height: %}} />"
                 ind comp-name
                 (fc/fmt-num (:left pos)) (fc/fmt-num (:top pos))
                 (fc/fmt-num (:width size)) (fc/fmt-num (:height size))))

       (fc/svg-shape? shape)
       ;; Native SVG via react-native-svg `SvgXml`, fed the same `<svg>`
       ;; string the web frameworks produce (carries viewBox, transform,
       ;; fills, masks). Works for every svg-shape — no PNG fallback needed.
       (let [xml (fc/svg-xml objects shape)
             size (fc/shape-size shape)]
         (if (str/blank? xml)
           (dm/fmt "%<View style={{%}} />" ind style-str)
           (dm/fmt "%<View style={{%}}>\n%  <SvgXml xml={`%`} width={%} height={%} />\n%</View>"
                   ind style-str ind xml
                   (fc/fmt-num (:width size)) (fc/fmt-num (:height size)) ind)))

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

(defn- has-svg?
  "True when any svg-shape is reachable from `shapes` through `objects`."
  [objects shapes]
  (letfn [(walk [s] (or (fc/svg-shape? s)
                        (some walk (fc/children-of objects s))))]
    (some walk shapes)))

(defn- rn-imports
  "Build the RN import block, conditionally adding `SvgXml` from
  `react-native-svg` and one import line per hoisted component name."
  [svg? comp-names]
  (dm/str
   "import React from \"react\";\n"
   "import { View, Text, Image, ImageBackground } from \"react-native\";\n"
   (when svg? "import { SvgXml } from \"react-native-svg\";\n")
   (str/join "" (map #(dm/str "import " % " from \"./components/" % "\";\n") comp-names))
   "\n"))

(defn- render-component
  "Render a RN component source string. `hoist-map` (when non-nil) makes
  `render-shape` emit `<CompName/>` references for hoisted instances;
  `comp-names` is the list of those names (for the import block). When
  both are nil/empty this is byte-identical to the legacy single-file
  `generate`."
  [objects shapes hoist-map comp-names]
  (let [roots (fc/root-originals objects shapes)
        origin (fc/selection-origin roots)
        size (fc/selection-size roots)
        svg? (has-svg? objects roots)
        body (binding [fc/*hoist-map* hoist-map
                       fc/*framework-type* "react-native"]
              (->> roots
                   (keep #(render-shape objects % origin 1))
                   (str/join "\n")))
        comp-name (or (some-> (seq roots) first fc/component-name) "Component")
        imports (rn-imports svg? comp-names)]
    (dm/fmt
     "%export default function %() {\n  return (\n    <View style={{position: \"relative\", width: %, height: %}}>\n%\n    </View>\n  );\n}\n"
     imports comp-name
     (fc/fmt-num (:width size))
     (fc/fmt-num (:height size))
     body)))

(defn generate
  "Single-string Inspect-panel preview (no hoisting — the preview can't
  represent multi-file components)."
  [objects shapes]
  (render-component objects shapes nil nil))

(defn- component-root-style
  "The def instance's box style as a RELATIVE root (drop absolute
  position/left/top, keep width/height/background/border/radius). Used for
  a hoisted component's root container."
  [objects shape origin]
  (let [pairs (box-style objects shape origin)
        kept (->> pairs (remove #(#{:position :left :top} (first %))))]
    (into [[:position "relative"]] kept)))

(defn- render-hoisted-component
  "Render one hoisted component as its own `components/<name>.jsx` source
  string. The definition instance's children are rendered relative to
  the instance head's origin (with `*hoist-map*` rebound to nil so nested
  instances are not re-hoisted), wrapped in a relative root carrying the
  instance's own background/border."
  [objects spec]
  (let [{:keys [comp-name def children origin size]} spec
        body (binding [fc/*hoist-map* nil
                       fc/*framework-type* "react-native"]
              (->> children
                   (keep #(render-shape objects % origin 1))
                   (str/join "\n")))
        root-style (style->js (component-root-style objects def origin))
        svg? (has-svg? objects [def])
        imports (rn-imports svg? [])]
    (dm/fmt
     "%export default function %() {\n  return (\n    <View style={{%}}>\n%\n    </View>\n  );\n}\n"
     imports comp-name root-style body)))

;; ---------------------------------------------------------------------------
;; Multi-file React Native project (Feature 2 code export)
;; ---------------------------------------------------------------------------

(defn- comp-name-from [roots]
  (or (some-> (seq roots) first fc/component-name) "App"))

(defn- rn-app-json [comp-name]
  (dm/fmt
   "{\n  \"name\": \"%\",\n  \"displayName\": \"%\"\n}\n"
   (fc/kebab-name comp-name) comp-name))

(defn- rn-babel-config []
  "module.exports = {\n  presets: [\"module:metro-react-native-babel-preset\"],\n};\n")

(defn- rn-package-json [comp-name uses-svg? uses-masked-view?]
  (dm/str
   "{\n"
     "  \"name\": \"" (fc/kebab-name comp-name) "\",\n"
     "  \"version\": \"0.0.1\",\n"
     "  \"private\": true,\n"
     "  \"scripts\": {\n"
     "    \"start\": \"react-native start\",\n"
     "    \"android\": \"react-native run-android\",\n"
     "    \"ios\": \"react-native run-ios\"\n"
     "  },\n"
     "  \"dependencies\": {\n"
     "    \"react\": \"18.3.1\",\n"
     "    \"react-native\": \"0.75.0\""
     (when uses-svg? ",\n    \"react-native-svg\": \"^15.3.0\"")
     (when uses-masked-view? ",\n    \"@react-native-masked-view/masked-view\": \"^0.3.1\"")
     "\n  }\n"
     "}\n"))

(defn- rn-readme [comp-name]
  (dm/fmt
   "# %\n\nGenerated with Ovion Desktop (React Native).\n\n## Run\n\n```bash\nnpm install\nnpx react-native run-android   # or: run-ios\n```\n\nThe component lives in `%.jsx`. Register it in your app entry:\n\n```js\nimport { AppRegistry } from \"react-native\";\nimport % from \"./%.jsx\";\nAppRegistry.registerComponent(\"%\", () => %);\n```\n\nAll children are absolutely positioned relative to the selection's bounding\nbox (React Native supports `position: \"absolute\"`).\n"
   comp-name comp-name comp-name comp-name (fc/kebab-name comp-name) comp-name))

(defn generate-project
  "Multi-file React Native project. The `:primary` file (`<Comp>.jsx`) is
  the component for the selection; native SVG shapes are emitted via
  `react-native-svg` `SvgXml` (so `:uses-rn-svg?` is true whenever an
  svg-shape is reachable). Component-instance hoisting (Phase E) emits
  one `components/<Comp>.jsx` per hoisted component and replaces every
  instance with a `<CompName/>` reference."
  [objects shapes opts]
  (let [roots (fc/root-originals objects shapes)
        comp-name (comp-name-from roots)
        hoist (fcomp/collect-hoistable objects roots)
        comp-names (mapv :comp-name (:specs hoist))
        primary-path (dm/str comp-name ".jsx")
        primary (render-component objects shapes (:hoist-map hoist) comp-names)
        comp-files (into {}
                         (for [spec (:specs hoist)]
                           [(dm/str "components/" (:comp-name spec) ".jsx")
                            (render-hoisted-component objects spec)]))
        uses-svg? (boolean (or (:uses-rn-svg? opts) (has-svg? objects roots)))
        uses-masked-view? (boolean (:uses-masked-view? opts))]
    {:files (merge {primary-path primary
                    "package.json" (rn-package-json comp-name uses-svg? uses-masked-view?)
                    "app.json" (rn-app-json comp-name)
                    "babel.config.js" (rn-babel-config)
                    "README.md" (rn-readme comp-name)}
                   comp-files)
     :binary-assets []
     :raster-requests []
     :primary primary-path
     :label "React Native"
     :uses-rn-svg? uses-svg?
     :uses-masked-view? uses-masked-view?}))