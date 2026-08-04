;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.android-xml
  "Generate an Android layout XML from a selection of Penpot shapes. Uses
  nested FrameLayouts with layout_marginStart/layout_marginTop for
  absolute positioning. Coordinates are emitted in dp (unitless numbers).

  Known limitations (documented inline): corner radius / gradients /
  borders need a drawable resource; remote images need a loader such as
  Coil/Glide. The layout itself (positions, sizes, solid colors, text,
  images placeholders) is valid and compiles."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.config :as cfg]
   [app.util.code-gen.code-connect :as cc]
   [app.util.code-gen.frameworks.common :as fc]
   [cuerdas.core :as str]))

(defn- indent [n] (str/repeat "    " n))

(defn- attr [k v] (dm/fmt "\n        %=\"%\"" k v))

(defn- escape-xml [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- background-argb [shape]
  (let [fill (fc/first-fill shape)]
    (cond
      (nil? fill) nil
      (some? (:fill-image fill)) "image"
      (some? (:fill-color-gradient fill)) "gradient"
      :else (fc/fill->argb-hex fill))))

(defn- stroke-argb [shape]
  (let [stroke (first (:strokes shape))]
    (when (and stroke (not= :none (:stroke-style stroke)))
      (fc/fill->argb-hex {:color (:stroke-color stroke)
                         :opacity (:stroke-opacity stroke)}))))

(defn- box-attrs
  "Common layout attributes for a shape element (position, size, bg)."
  [objects shape origin level]
  (let [pos (fc/rel-position objects shape origin)
        size (fc/shape-size shape)
        bg (background-argb shape)
        opacity (fc/shape-opacity shape)]
    (str
     (dm/fmt "\n        android:layout_width=\"%dp\"" (fc/fmt-num (:width size)))
     (dm/fmt "\n        android:layout_height=\"%dp\"" (fc/fmt-num (:height size)))
     (dm/fmt "\n        android:layout_marginStart=\"%dp\"" (fc/fmt-num (:left pos)))
     (dm/fmt "\n        android:layout_marginTop=\"%dp\"" (fc/fmt-num (:top pos)))
     (when (and bg (not= bg "image") (not= bg "gradient"))
       (dm/fmt "\n        android:background=\"%\"" bg))
     (when (or (= bg "image") (= bg "gradient"))
       (dm/fmt "\n        android:background=\"#00000000\""))
     (when opacity (dm/fmt "\n        android:alpha=\"%\"" (fc/fmt-num opacity)))
     (when (cfh/frame-shape? shape)
       (dm/fmt "\n        android:clipChildren=\"%\"" (if (:show-content shape) "false" "true"))))))

(defn- text-attrs [typo]
  (str
   (dm/fmt "\n        android:textSize=\"%sp\"" (fc/fmt-num (:font-size typo)))
   (dm/fmt "\n        android:textColor=\"%\"" (when (:color typo)
                                                ;; rgba -> argb
                                                (let [[r g b a] (->> (re-seq #"\d+\.?\d*" (:color typo))
                                                                     (map #(js/parseFloat %)))]
                                                  (fc/rgba->argb-hex [r g b a]))))
   (dm/fmt "\n        android:fontFamily=\"%\"" (:font-family typo))
   (when (#{"bold" "700" "600" "800" "900"} (:font-weight typo))
     "\n        android:textStyle=\"bold\"")
   (dm/fmt "\n        android:gravity=\"%\""
           ({"left" "start|center_vertical"
             "center" "center"
             "right" "end|center_vertical"
             "justify" "center_vertical"} (:text-align typo) "start|center_vertical"))
   (dm/fmt "\n        android:letterSpacing=\"%\"" (when (not= (:letter-spacing typo) "0")
                                                     (/ (js/parseFloat (:letter-spacing typo))
                                                        (js/parseFloat (:font-size typo)))))))

(defn- element-tag [shape]
  (cond
    (fc/svg-shape? shape) "View"
    (cfh/text-shape? shape) "TextView"
    (cfh/image-shape? shape) "ImageView"
    (fc/container? shape) "FrameLayout"
    :else "View"))

;; ---------------------------------------------------------------------------
;; Native-SVG / PNG-raster (Phase C/D)
;; ---------------------------------------------------------------------------

;; Dynamic accumulator bound ONLY by `generate-project` while rendering
;; the layout tree. render-shape's svg branch records a VectorDrawable
;; (simple shapes) or a raster-request (complex shapes) into it and emits
;; an `<ImageView android:src="@drawable/<name>"/>` reference. When nil
;; (the single-string Inspect-panel preview) the svg branch emits a
;; placeholder `<View>` with a comment — no drawables are produced.
(def ^:dynamic *svg-acc* nil)

(defn- layout-attrs-only
  "Position + size + alpha layout attributes for an svg-shape, WITHOUT the
  `android:background` (the drawable itself carries the fill/stroke, so a
  background on the ImageView would double up or clash)."
  [objects shape origin]
  (let [pos (fc/rel-position objects shape origin)
        size (fc/shape-size shape)
        opacity (fc/shape-opacity shape)]
    (str
     (dm/fmt "\n        android:layout_width=\"%dp\"" (fc/fmt-num (:width size)))
     (dm/fmt "\n        android:layout_height=\"%dp\"" (fc/fmt-num (:height size)))
     (dm/fmt "\n        android:layout_marginStart=\"%dp\"" (fc/fmt-num (:left pos)))
     (dm/fmt "\n        android:layout_marginTop=\"%dp\"" (fc/fmt-num (:top pos)))
     (when opacity (dm/fmt "\n        android:alpha=\"%\"" (fc/fmt-num opacity))))))

(defn- vector-drawable
  "Build a VectorDrawable XML for a `simple-svg?` shape. The path's `d`
  string is in the shape's local coordinate space (origin at its bounding
  box top-left), so `viewportWidth/Height` are the shape's pixel size and
  no `<group>` transform is needed."
  [shape]
  (let [size (fc/shape-size shape)
        d (fc/path-d-string shape)
        fill (fc/solid-fill-hex shape)
        stroke (fc/simple-stroke shape)]
    (dm/str
     "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
     "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
     "    android:width=\"" (fc/fmt-num (:width size)) "dp\"\n"
     "    android:height=\"" (fc/fmt-num (:height size)) "dp\"\n"
     "    android:viewportWidth=\"" (fc/fmt-num (:width size)) "\"\n"
     "    android:viewportHeight=\"" (fc/fmt-num (:height size)) "\">\n"
     "    <path\n"
     "        android:pathData=\"" (escape-xml d) "\"\n"
     "        android:fillColor=\"" fill "\""
     (when stroke
       (dm/str "\n        android:strokeColor=\"" (:color stroke) "\"\n"
               "        android:strokeWidth=\"" (fc/fmt-num (:width stroke)) "\""))
     " />\n"
     "</vector>\n")))

(defn- drawable-name-for
  "A stable, unique lowercase drawable resource name for `shape`, de-duped
    against names already recorded in `*svg-acc*` (across both .xml and
    .png paths). Android resource names must be [a-z0-9_] and not start
    with a digit."
  [shape]
  (let [raw (fc/snake-name (or (:name shape) "shape"))
        base (cond (str/blank? raw) "shape"
                   (re-matches #"^\d.*" raw) (dm/str "s_" raw)
                   :else raw)]
    (loop [n base i 2]
      (let [used (:used @*svg-acc*)
            xml-path (dm/str "res/drawable/" n ".xml")
            png-path (dm/str "res/drawable/" n ".png")]
        (if (or (contains? used xml-path) (contains? used png-path))
          (recur (dm/str base "_" i) (inc i))
          n)))))

(defn- register-svg-shape
  "Record `shape` in `*svg-acc*` as a VectorDrawable (when `simple-svg?`)
  or a raster-request (otherwise), and return the drawable resource name
  the layout should reference via `@drawable/<name>`."
  [shape]
  (let [n (drawable-name-for shape)
        simple? (fc/simple-svg? shape)
        xml-path (dm/str "res/drawable/" n ".xml")
        png-path (dm/str "res/drawable/" n ".png")]
    (vswap! *svg-acc*
            (fn [a]
              (let [a (assoc a :used (conj (:used a) xml-path png-path))]
                (if simple?
                  (assoc-in a [:drawables xml-path] (vector-drawable shape))
                  (update a :rasters conj {:shape-id (:id shape)
                                           :name n
                                           :scale 2
                                           :binary-path png-path})))))
    n))

(defn- render-shape
  ([objects shape origin] (render-shape objects shape origin 1))
  ([objects shape origin level]
   (let [ind (indent level)
         tag (element-tag shape)
         attrs (box-attrs objects shape origin level)
         cc-binding (fc/code-connect-binding objects shape)]
     (cond
       (fc/hidden? shape) nil

       ;; Code Connect binding (P1.08): emit the mapped custom view tag with
       ;; layout params + authored props instead of a generic element.
       (some? cc-binding)
       (let [binding cc-binding]
         (let [tag (:tag binding)
               lattrs (layout-attrs-only objects shape origin)
               props (cc/format-props-xml binding)
               attr-str (if (str/blank? props) lattrs
                            (dm/str lattrs "\n        " props))]
           (dm/fmt "%<!-- Code Connect: % -->\n%<%\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"%\n        />"
                   ind tag ind tag attr-str)))

       (fc/svg-shape? shape)
       ;; Native SVG: simple shapes become a VectorDrawable in
       ;; `res/drawable/<name>.xml`; complex shapes are rasterized to a
       ;; PNG at `@drawable/<name>` (recorded as a `:raster-request` and
       ;; resolved by the export pipeline). In the Inspect-panel preview
       ;; (`*svg-acc*` unbound) a placeholder `<View>` + comment is emitted.
       (let [lattrs (layout-attrs-only objects shape origin)
             sname  (fc/snake-name (or (:name shape) "shape"))]
         (if (nil? *svg-acc*)
           (dm/str ind "<!-- SVG shape " sname " — export to project to emit res/drawable/" sname ".xml\n"
                   "     (VectorDrawable if simple, raster PNG if complex) -->\n"
                   ind "<View " lattrs "\n        android:background=\"#00000000\" />")
           (let [dname (register-svg-shape shape)]
             (dm/fmt "%<ImageView\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"%\n        android:src=\"@drawable/%\" />"
                     ind lattrs dname))))

       (cfh/text-shape? shape)
       (let [typo (fc/extract-typography shape)
             txt (fc/extract-text shape)]
         (dm/fmt "%<%\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"%\n        android:text=\"%\"%\n        />"
                 ind tag attrs (escape-xml txt) (text-attrs typo) ind))

       (cfh/image-shape? shape)
       (let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))
             url (when data (cfg/resolve-file-media data))]
         (dm/fmt "%<%\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"%\n        android:scaleType=\"centerCrop\"\n        android:contentDescription=\"%\"\n        tools:src=\"%\" />\n        <!-- Load the remote image with Coil/Glide: % -->"
                 ind tag attrs
                 (escape-xml (:name shape))
                 (escape-xml (or url ""))
                 (escape-xml (or url ""))))

       (fc/container? shape)
       (let [children (fc/children-of objects shape)
             child-code (->> children
                            (keep #(render-shape objects % origin (inc level)))
                            (str/join "\n"))]
         (if (str/blank? child-code)
           (dm/fmt "%<%\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"%\n        %\n        />"
                   ind tag attrs
                   (when (or (= (background-argb shape) "image")
                             (= (background-argb shape) "gradient"))
                     "\n        <!-- corner radius / gradient / border need a drawable resource -->"))
           (dm/fmt "%<%\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"%\n        >\n%\n%</%>"
                   ind tag attrs
                   (when (or (= (background-argb shape) "image")
                             (= (background-argb shape) "gradient"))
                     "\n        <!-- corner radius / gradient / border need a drawable resource -->")
                   child-code
                   (indent level) tag)))

       :else
       (dm/fmt "%<%\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"%\n        %\n        />"
               ind tag attrs
               (when (fc/shape-radius shape)
                 "\n        <!-- corner radius needs a drawable resource -->"))))))

(defn generate
  [objects shapes]
  (let [roots (fc/root-originals objects shapes)
        origin (fc/selection-origin roots)
        size (fc/selection-size roots)
        body (binding [fc/*framework-type* "android-xml"]
              (->> roots
                   (keep #(render-shape objects % origin 1))
                   (str/join "\n")))]
    (dm/fmt
     "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<FrameLayout\n    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    xmlns:tools=\"http://schemas.android.com/tools\"\n    android:layout_width=\"%dp\"\n    android:layout_height=\"%dp\"\n    android:clipChildren=\"false\">\n%\n</FrameLayout>\n"
     (fc/fmt-num (:width size))
     (fc/fmt-num (:height size))
     body)))

;; ---------------------------------------------------------------------------
;; Multi-file Android project tree (Feature 2 code export)
;; ---------------------------------------------------------------------------

(defn- comp-name-from [roots]
  (or (some-> (seq roots) first fc/component-name) "Export"))

(defn- colors-xml []
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <color name=\"black\">#FF000000</color>\n    <color name=\"white\">#FFFFFFFF</color>\n</resources>\n")

(defn- strings-xml [comp-name]
  (dm/fmt
   "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"app_name\">%</string>\n</resources>\n"
   comp-name))

(defn- dimens-xml []
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources />\n")

(defn- styles-xml []
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <style name=\"Theme.PenpotExport\" parent=\"android:Theme.Material.Light.NoActionBar\" />\n</resources>\n")

(defn- android-manifest []
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application\n        android:label=\"@string/app_name\"\n        android:theme=\"@style/Theme.PenpotExport\">\n        <activity\n            android:name=\".MainActivity\"\n            android:exported=\"true\">\n            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>\n")

(defn- android-build-gradle []
  "plugins {\n    id \"com.android.application\"\n}\n\nandroid {\n    namespace \"com.penpot.export\"\n    compileSdk 34\n\n    defaultConfig {\n        applicationId \"com.penpot.export\"\n        minSdk 24\n        targetSdk 34\n        versionCode 1\n        versionName \"1.0\"\n    }\n}\n\ndependencies {\n    implementation \"androidx.appcompat:appcompat:1.6.1\"\n}\n")

(defn- android-readme [comp-name]
  (dm/fmt
   "# %\n\nGenerated with Ovion Desktop (Android layout XML).\n\n## Use\n\nThe layout lives in `res/layout/%.xml`. Drop the `res/` tree and\n`AndroidManifest.xml` into an Android Studio project (or merge into an\nexisting module) and adjust `AndroidManifest.xml`'s `.MainActivity`.\n\nKnown limitations: corner radius / gradients / borders on containers and\ncomplex SVG shapes need a drawable resource (VectorDrawable / custom) —\nsee the inline comments. Remote images need a loader such as Coil/Glide.\n"
   comp-name (fc/snake-name comp-name)))

(defn generate-project
  "Multi-file Android project tree. The `:primary` file
  (`res/layout/<name>.xml`) is the layout for the selection. The scaffold
  adds the `res/values/` resource tables, a manifest stub, an app
  `build.gradle` and a README. Native-SVG (Phase C): every svg-shape in
  the tree becomes either a VectorDrawable in `res/drawable/<name>.xml`
  (simple — single path, solid fill, inner stroke, no transform/mask)
  or a raster-request (complex) resolved to `res/drawable/<name>.png` by
  the export pipeline; the layout references both as `@drawable/<name>`.
  Component-instance hoisting (Phase E) is NOT applied to Android —
  instances flatten inline (Android has no component primitive in XML)."
  [objects shapes opts]
  (let [roots (fc/root-originals objects shapes)
        comp-name (comp-name-from roots)
        layout-name (fc/snake-name comp-name)
        primary-path (dm/str "res/layout/" layout-name ".xml")
        acc (volatile! {:drawables {} :rasters [] :used #{}})
        primary (binding [*svg-acc* acc]
                  (generate objects shapes))
        final @acc]
    {:files (merge {primary-path primary
                    "res/values/colors.xml" (colors-xml)
                    "res/values/strings.xml" (strings-xml comp-name)
                    "res/values/dimens.xml" (dimens-xml)
                    "res/values/styles.xml" (styles-xml)
                    "AndroidManifest.xml" (android-manifest)
                    "build.gradle" (android-build-gradle)
                    "README.md" (android-readme comp-name)}
                   (:drawables final))
     :binary-assets []
     :raster-requests (:rasters final)
     :primary primary-path
     :label "Android XML"
     :uses-rn-svg? false
     :uses-masked-view? false}))