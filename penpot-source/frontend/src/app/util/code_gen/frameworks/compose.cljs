;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.compose
  "Generate a Jetpack Compose (Kotlin) `@Composable` from a selection of
  Penpot shapes. Uses a `Box` root with absolutely-positioned children via
  `Modifier.offset(x.dp, y.dp).size(w.dp, h.dp)` (offset is top-left based,
  so no center conversion is needed — unlike the SwiftUI emitter). Colors
  are emitted as `Color(0xAARRGGBB)` literals. Rectangles become `Box` with
  `.background(color, shape)`, circles use `CircleShape`, text becomes
  `Text(...)`, frames/groups become nested `Box`es with a `.background`
  fill, images use Coil `AsyncImage`.

  Known limitations (documented inline): gradients on containers are
  rendered via `Brush.linearGradient`/`radialGradient` on a `Box`;
  complex SVG shapes have no built-in Compose equivalent, so in the
  Inspect-panel preview a placeholder `Box` + comment is emitted, and in
  the multi-file project they are rasterized to `res/drawable/<name>.png`
  (recorded as a `:raster-request`) and referenced via
  `Image(painter = painterResource(R.drawable.<name>))`. Component
  hoisting (Phase E) emits one `@Composable` per hoisted component and
  replaces every instance with a `CompName()` call."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.config :as cfg]
   [app.util.code-gen.code-connect :as cc]
   [app.util.code-gen.frameworks.common :as fc]
   [app.util.code-gen.frameworks.components :as fcomp]
   [cuerdas.core :as str]))

(defn- indent [n] (str/repeat "    " n))

(defn- escape-kotlin [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\r" "")
      (str/replace "\n" "\\n")))

(defn- kotlin-string [s] (dm/str "\"" (escape-kotlin s) "\""))

(defn- argb-hex->color
  "Convert an `#AARRGGBB` hex (from `fc/fill->argb-hex`) to a Compose
  `Color(0xAARRGGBB)` literal. Transparent when blank."
  [hex]
  (if (str/blank? hex)
    "Color(0x00000000)"
    (dm/str "Color(0x" (str/replace hex #"^#" "") ")")))

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
                    (if (and (= r1 r2) (= r2 r3) (= r3 r4)) r1
                        [r1 r2 r3 r4]))
      :else nil)))

(defn- corner-shape
  "The Compose `Shape` expression for a shape's corner radius. Circles map
  to `CircleShape`; a uniform radius maps to `RoundedCornerShape(r.dp)`;
  per-corner maps to `RoundedCornerShape(topStart, topEnd, bottomEnd,
  bottomStart)` (Compose orders corners TL, TR, BR, BL — same as Penpot's
  r1 r2 r3 r4). nil radius → nil (no clip)."
  [r]
  (cond
    (= r :circle) "CircleShape"
    (number? r) (dm/str "RoundedCornerShape(" (fc/fmt-num r) ".dp)")
    (vector? r) (let [[r1 r2 r3 r4] r]
                  (dm/str "RoundedCornerShape("
                          (fc/fmt-num r1) ".dp, "
                          (fc/fmt-num r2) ".dp, "
                          (fc/fmt-num r3) ".dp, "
                          (fc/fmt-num r4) ".dp)"))
    :else nil))

(defn- stroke-attrs [shape]
  (let [stroke (first (:strokes shape))]
    (when (and stroke (not= :none (:stroke-style stroke)))
      {:color (argb-hex->color (fc/fill->argb-hex {:color (:stroke-color stroke)
                                                   :opacity (:stroke-opacity stroke)}))
       :width (d/nilv (:stroke-width stroke) 1)})))

(defn- gradient-brush
  "Build a Compose `Brush.linearGradient`/`radialGradient` expression from
  the structured gradient in `fc/shape-background`. Stops are emitted as a
  `listOf(Color(0x..), Color(0x..))`."
  [grad]
  (let [gtype (d/name (or (:gradient-type grad) :linear))
        stops (:stops grad)
        colors (->> stops
                    (map (fn [{:keys [color opacity]}]
                           (argb-hex->color (fc/fill->argb-hex
                                             {:color (or color "#000000")
                                              :opacity (d/nilv opacity 1)}))))
                    (str/join ", "))
        fn-name (if (= gtype "radial") "radialGradient" "linearGradient")]
    (dm/str "Brush." fn-name "(listOf(" colors "))")))

(defn- background-mod
  "The `.background(...)` modifier fragment for a shape's fill, or \"\" when
  no fill. `level` is the indent depth used to continuation-indent a
  multi-line modifier chain."
  [shape level]
  (let [bg (fill-color shape)
        ind (indent level)]
    (cond
      (nil? bg) ""
      (= bg "image") (dm/str "\n" ind "            .background(Color(0x00000000)) // image fill")
      (= bg "gradient")
      (let [sb (fc/shape-background shape)
            brush (when (= (:type sb) :gradient) (gradient-brush (:gradient sb)))]
        (if brush
          (dm/str "\n" ind "            .background(" brush ")")
          (dm/str "\n" ind "            .background(Color(0x00000000)) // gradient fill")))
      :else (dm/str "\n" ind "            .background(" (argb-hex->color bg) ")"))))

(defn- border-mod
  "The `.border(...)` modifier fragment for a shape's stroke, shaped to
  follow the corner radius so the outline rounds with the box. \"\" when
  no stroke."
  [shape level]
  (if-let [s (stroke-attrs shape)]
    (let [r (radius shape)
          shape-expr (or (corner-shape r) "RectangleShape")
          ind (indent level)]
      (dm/str "\n" ind "            .border(" (fc/fmt-num (:width s)) ".dp, "
              (:color s) ", " shape-expr ")"))
    ""))

(defn- offset-size-mod
  "The `.offset(x.dp, y.dp).size(w.dp, h.dp)` modifier fragment for a
  shape placed relative to `origin`. Compose `offset` is top-left based."
  [objects shape origin level]
  (let [pos (fc/rel-position objects shape origin)
        size (fc/shape-size shape)
        ind (indent level)]
    (dm/str "\n" ind "            .offset(" (fc/fmt-num (:left pos)) ".dp, "
            (fc/fmt-num (:top pos)) ".dp)"
            "\n" ind "            .size(" (fc/fmt-num (:width size)) ".dp, "
            (fc/fmt-num (:height size)) ".dp)")))

(defn- font-weight [w]
  (let [n (js/parseInt (or w "400") 10)]
    (cond
      (>= n 800) "FontWeight.Black"
      (>= n 700) "FontWeight.Bold"
      (>= n 600) "FontWeight.SemiBold"
      (>= n 500) "FontWeight.Medium"
      :else "FontWeight.Normal")))

(defn- text-align [a]
  ({"left" "TextAlign.Start" "center" "TextAlign.Center"
    "right" "TextAlign.End" "justify" "TextAlign.Justify"} a "TextAlign.Start"))

;; ---------------------------------------------------------------------------
;; Native-SVG / PNG-raster (mirrors android_xml's Phase C/D accumulator)
;; ---------------------------------------------------------------------------

;; Dynamic accumulator bound ONLY by `generate-project` while rendering the
;; composable tree. render-shape's svg branch records a raster-request into
;; it and emits an `Image(painter = painterResource(R.drawable.<name>))`
;; reference. When nil (the single-string Inspect-panel preview) the svg
;; branch emits a placeholder `Box` with a comment — no drawables produced.
(def ^:dynamic *svg-acc* nil)

(defn- drawable-name-for
  "A stable, unique lowercase drawable resource name for `shape`, de-duped
  against names already recorded in `*svg-acc*`. Android resource names
  must be [a-z0-9_] and not start with a digit."
  [shape]
  (let [raw (fc/snake-name (or (:name shape) "shape"))
        base (cond (str/blank? raw) "shape"
                   (re-matches #"^\d.*" raw) (dm/str "s_" raw)
                   :else raw)]
    (loop [n base i 2]
      (let [used (:used @*svg-acc*)
            png-path (dm/str "res/drawable/" n ".png")]
        (if (contains? used png-path)
          (recur (dm/str base "_" i) (inc i))
          n)))))

(defn- register-svg-shape
  "Record `shape` in `*svg-acc*` as a raster-request and return the
  drawable resource name the composable should reference via
  `R.drawable.<name>`."
  [shape]
  (let [n (drawable-name-for shape)
        png-path (dm/str "res/drawable/" n ".png")]
    (vswap! *svg-acc*
            (fn [a]
              (-> a
                  (assoc :used (conj (:used a) png-path))
                  (update :rasters conj {:shape-id (:id shape)
                                         :name n
                                         :scale 2
                                         :binary-path png-path}))))
    n))

(defn- render-shape
  ([objects shape origin] (render-shape objects shape origin 1))
  ([objects shape origin level]
   (let [ind (indent level)
         opacity (fc/shape-opacity shape)
         opacity-mod (if opacity
                       (dm/str "\n" ind "            .alpha(" (fc/fmt-num opacity) ")")
                       "")
         base-mod (dm/str (offset-size-mod objects shape origin level)
                          (background-mod shape level)
                          (border-mod shape level)
                          opacity-mod)]
     (cond
       (fc/hidden? shape) nil

       ;; Code Connect binding (P1.08): emit the mapped code component
       ;; `Tag(props)` with the shape's offset+size modifiers, instead of
       ;; recursing into a generic Box.
       (when-let [binding (fc/code-connect-binding objects shape)]
         (let [tag (:tag binding)
               props (cc/format-props-kotlin binding)]
           (dm/str ind "// Code Connect: " tag "\n"
                   ind tag "(modifier = Modifier" base-mod
                   (if (str/blank? props) "" (dm/str ", " props))
                   ")")))

       ;; Hoisted component instance → emit a reference instead of recursing.
       (fc/hoisted-instance? shape)
       (dm/str ind (fc/hoisted-name shape) "(modifier = Modifier" base-mod ")")

       (fc/svg-shape? shape)
       ;; Compose has no built-in SVG view. In the Inspect-panel preview
       ;; (`*svg-acc*` unbound) emit a placeholder `Box` + comment. In the
       ;; multi-file project, rasterize the shape to a PNG at
       ;; `res/drawable/<name>.png` (recorded as a `:raster-request`) and
       ;; reference it via `Image(painter = painterResource(R.drawable.<name>))`.
       (if (nil? *svg-acc*)
         (dm/str ind "// SVG shape — export to project to rasterize to res/drawable/"
                 (fc/snake-name (or (:name shape) "shape")) ".png\n"
                 ind "Box(Modifier" base-mod ")")
         (let [dname (register-svg-shape shape)]
           (dm/str ind "Image(\n"
                   ind "    painter = painterResource(R.drawable." dname "),\n"
                   ind "    contentDescription = null,\n"
                   ind "    contentScale = ContentScale.FillBounds,\n"
                   ind "    modifier = Modifier" base-mod "\n"
                   ind ")")))

       (cfh/text-shape? shape)
       (let [typo (fc/extract-typography shape)
             txt (fc/extract-text shape)
             color (rgba-string->color (:color typo))]
         (dm/str ind "Text(\n"
                 ind "    text = " (kotlin-string txt) ",\n"
                 ind "    fontSize = " (fc/fmt-num (:font-size typo)) ".sp,\n"
                 ind "    fontWeight = " (font-weight (:font-weight typo)) ",\n"
                 ind "    color = " color ",\n"
                 ind "    textAlign = " (text-align (:text-align typo)) ",\n"
                 ind "    modifier = Modifier" base-mod "\n"
                 ind ")"))

       (cfh/image-shape? shape)
       (let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))
             url (when data (cfg/resolve-file-media data))]
         (if url
           (dm/str ind "AsyncImage(\n"
                   ind "    model = " (kotlin-string url) ",\n"
                   ind "    contentDescription = null,\n"
                   ind "    contentScale = ContentScale.Crop,\n"
                   ind "    modifier = Modifier" base-mod "\n"
                   ind ")")
           (dm/str ind "Box(Modifier" base-mod ") // image placeholder")))

       (fc/container? shape)
       (let [children (fc/children-of objects shape)
             child-code (->> children
                             (keep #(render-shape objects % origin (+ level 1)))
                             (str/join "\n"))
             r (radius shape)
             clip-mod (when (or r (some? (fill-color shape)))
                        (let [se (corner-shape r)]
                          (if se
                            (dm/str "\n" ind "            .clip(" se ")")
                            "")))]
         (if (str/blank? child-code)
           (dm/str ind "Box(Modifier" base-mod clip-mod ")")
           (dm/str ind "Box(Modifier" base-mod clip-mod ") {\n"
                   child-code "\n"
                   ind "}")))

       :else
       ;; Plain rectangle / rounded rectangle / circle.
       (let [r (radius shape)
             clip-mod (let [se (corner-shape r)]
                        (if (and se (some? (fill-color shape)))
                          (dm/str "\n" ind "            .clip(" se ")")
                          ""))]
         (dm/str ind "Box(Modifier" base-mod clip-mod ")"))))))

(defn- comp-name-from [roots]
  (or (some-> (seq roots) first fc/component-name) "ExportedComposable"))

(defn- render-composable
  "Render a Jetpack Compose `@Composable` function source string for
  `shapes` placed relative to `origin`, sized to `size`. `hoist-map` (when
  non-nil) makes `render-shape` emit `CompName()` references for hoisted
  instances, and `comp-names` adds one import line per hoisted component
  so those references resolve."
  [objects shapes origin size comp-name hoist-map comp-names]
  (let [imports (dm/str
                 "import androidx.compose.foundation.background\n"
                 "import androidx.compose.foundation.border\n"
                 "import androidx.compose.foundation.layout.Box\n"
                 "import androidx.compose.foundation.layout.offset\n"
                 "import androidx.compose.foundation.layout.size\n"
                 "import androidx.compose.foundation.shape.CircleShape\n"
                 "import androidx.compose.foundation.shape.RoundedCornerShape\n"
                 "import androidx.compose.material3.MaterialTheme\n"
                 "import androidx.compose.material3.Text\n"
                 "import androidx.compose.runtime.Composable\n"
                 "import androidx.compose.ui.Modifier\n"
                 "import androidx.compose.ui.draw.alpha\n"
                 "import androidx.compose.ui.draw.clip\n"
                 "import androidx.compose.ui.graphics.Brush\n"
                 "import androidx.compose.ui.graphics.Color\n"
                 "import androidx.compose.ui.graphics.RectangleShape\n"
                 "import androidx.compose.ui.layout.ContentScale\n"
                 "import androidx.compose.ui.res.painterResource\n"
                 "import androidx.compose.ui.text.font.FontWeight\n"
                 "import androidx.compose.ui.text.style.TextAlign\n"
                 "import androidx.compose.ui.tooling.preview.Preview\n"
                 "import androidx.compose.ui.unit.dp\n"
                 "import androidx.compose.ui.unit.sp\n"
                 "import coil.compose.AsyncImage\n"
                 (str/join "" (map #(dm/str "import ovion.export.widgets." % "\n") comp-names)))
        body (binding [fc/*hoist-map* hoist-map
                       fc/*framework-type* "compose"]
               (->> shapes
                    (keep #(render-shape objects % origin 2))
                    (str/join "\n")))
        inner (if (str/blank? body) "" (dm/str "\n" body "\n    "))]
    (dm/str
     "package ovion.export\n\n"
     imports "\n"
     "@Composable\n"
     "fun " comp-name "(modifier: Modifier = Modifier) {\n"
     "    Box(modifier = modifier\n"
     "        .size(" (fc/fmt-num (:width size)) ".dp, " (fc/fmt-num (:height size)) ".dp)) {"
     inner "}\n"
     "}\n\n"
     "@Preview(showBackground = true)\n"
     "@Composable\n"
     "private fun " comp-name "Preview() {\n"
     "    " comp-name "()\n"
     "}\n")))

(defn generate
  "Single-string Inspect-panel preview (no hoisting — the preview can't
  represent multi-file components)."
  [objects shapes]
  (let [roots (fc/root-originals objects shapes)]
    (render-composable objects roots
                       (fc/selection-origin roots)
                       (fc/selection-size roots)
                       (comp-name-from roots) nil nil)))

(defn- render-hoisted-component
  "Render one hoisted component as its own `ovion/export/widgets/<name>.kt`
  source string. The definition instance's children are rendered relative
  to the instance head's origin (with `*hoist-map*` rebound to nil so
  nested instances are not re-hoisted)."
  [objects spec]
  (let [{:keys [comp-name children origin size]} spec]
    (render-composable objects children origin size comp-name nil nil)))

;; ---------------------------------------------------------------------------
;; Multi-file Jetpack Compose project (Feature 2 code export)
;; ---------------------------------------------------------------------------

(defn- build-gradle-kts []
  "plugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n}\n\nandroid {\n    namespace = \"ovion.export\"\n    compileSdk = 34\n\n    defaultConfig {\n        applicationId = \"ovion.export\"\n        minSdk = 24\n        targetSdk = 34\n        versionCode = 1\n        versionName = \"1.0\"\n    }\n\n    buildFeatures {\n        compose = true\n    }\n\n    composeOptions {\n        kotlinCompilerExtensionVersion = \"1.5.14\"\n    }\n}\n\ndependencies {\n    val composeBom = platform(\"androidx.compose:compose-bom:2024.09.03\")\n    implementation(composeBom)\n    implementation(\"androidx.compose.material3:material3\")\n    implementation(\"androidx.compose.ui:ui\")\n    implementation(\"androidx.compose.ui:ui-tooling-preview\")\n    implementation(\"io.coil-kt:coil-compose:2.7.0\")\n    debugImplementation(\"androidx.compose.ui:ui-tooling\")\n}\n")

(defn- color-kt [comp-name]
  "package ovion.export\n\nimport androidx.compose.ui.graphics.Color\n\n// Material 3 color tokens mapped from the Ovion selection.\n// Reassign these into a `lightColorScheme`/`darkColorScheme` in Theme.kt\n// to wire them through `MaterialTheme.colorScheme`.\nval OvionPrimary = Color(0xFF6750A4)\nval OvionOnPrimary = Color(0xFFFFFFFF)\nval OvionSurface = Color(0xFFFFFBFE)\nval OvionBackground = Color(0xFFFFFBFE)\n")

(defn- theme-kt [comp-name]
  "package ovion.export\n\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.lightColorScheme\nimport androidx.compose.runtime.Composable\n\nprivate val OvionColorScheme = lightColorScheme(\n    primary = OvionPrimary,\n    onPrimary = OvionOnPrimary,\n    surface = OvionSurface,\n    background = OvionBackground,\n)\n\n@Composable\nfun OvionTheme(content: @Composable () -> Unit) {\n    MaterialTheme(\n        colorScheme = OvionColorScheme,\n        content = content,\n    )\n}\n")

(defn- compose-readme [comp-name]
  (dm/str
   "# " comp-name "\n\n"
   "Generated with Ovion Desktop (Jetpack Compose / Kotlin).\n\n"
   "## Use\n\n"
   "The composable lives in `app/src/main/java/ovion/export/"
   (fc/snake-name comp-name) ".kt` — a `@Composable` function using a `Box`\n"
   "root with `Modifier.offset` + `.size` for absolute layout. Drop the\n"
   "`app/` tree into an Android Studio project with Jetpack Compose + Material 3\n"
   "enabled (see `build.gradle.kts` for the Compose BOM + Coil dependency).\n\n"
   "Material 3 color tokens are scaffolded in `Color.kt` and wired through\n"
   "`OvionTheme` in `Theme.kt` — reassign the `Ovion*` vals to match your brand.\n\n"
   "Known limitations: complex SVG shapes are rasterized to PNG drawables in\n"
   "`res/drawable/` and referenced via `painterResource`. Gradients are emitted\n"
   "as `Brush.linearGradient`/`radialGradient`. Remote images use Coil\n"
   "`AsyncImage`.\n"))

(defn generate-project
  "Multi-file Jetpack Compose project. The `:primary` file
  (`app/src/main/java/ovion/export/<name>.kt`) is the `@Composable` for the
  selection; the scaffold adds a Material 3 `Color.kt` + `Theme.kt`, an app
  `build.gradle.kts` and a README. Native-SVG (Phase C/D): every svg-shape
  in the tree is rasterized to `res/drawable/<name>.png` (recorded as a
  `:raster-request` resolved by the export pipeline) and referenced via
  `Image(painter = painterResource(R.drawable.<name>))`. Component-instance
  hoisting (Phase E) emits one `@Composable` per hoisted component in
  `app/src/main/java/ovion/export/widgets/<name>.kt` and replaces every
  instance with a `CompName()` call."
  [objects shapes opts]
  (let [roots (fc/root-originals objects shapes)
        comp-name (comp-name-from roots)
        file-name (fc/pascal (fc/snake-name comp-name))
        primary-path (dm/str "app/src/main/java/ovion/export/" (fc/snake-name file-name) ".kt")
        hoist (fcomp/collect-hoistable objects roots)
        comp-names (mapv :comp-name (:specs hoist))
        acc (volatile! {:rasters [] :used #{}})
        primary (binding [*svg-acc* acc]
                  (render-composable objects roots
                                     (fc/selection-origin roots)
                                     (fc/selection-size roots)
                                     comp-name (:hoist-map hoist) comp-names))
        final @acc
        comp-files (into {}
                        (for [spec (:specs hoist)]
                          [(dm/str "app/src/main/java/ovion/export/widgets/"
                                   (fc/snake-name (:comp-name spec)) ".kt")
                           (render-hoisted-component objects spec)]))]
    {:files (merge {primary-path primary
                    "app/src/main/java/ovion/export/Color.kt" (color-kt comp-name)
                    "app/src/main/java/ovion/export/Theme.kt" (theme-kt comp-name)
                    "app/build.gradle.kts" (build-gradle-kts)
                    "README.md" (compose-readme comp-name)}
                   comp-files)
     :binary-assets []
     :raster-requests (:rasters final)
     :primary primary-path
     :label "Jetpack Compose"
     :uses-rn-svg? false
     :uses-masked-view? false}))