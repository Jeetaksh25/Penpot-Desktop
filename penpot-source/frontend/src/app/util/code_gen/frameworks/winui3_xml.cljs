;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.winui3-xml
  "Generate a WinUI 3 (Windows App SDK) XAML file from a selection of
  Penpot shapes. Uses a root Canvas with children positioned via
  Canvas.Left / Canvas.Top. Nested containers become nested Canvases so
  children stay positioned relative to their parent. Remote images are
  loaded via Image Source (WinUI supports http(s) URIs)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.config :as cfg]
   [app.util.code-gen.frameworks.common :as fc]
   [cuerdas.core :as str]))

(defn- indent [n] (str/repeat "  " n))

(defn- escape-xaml [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- argb [color-opacity]
  (if (str/blank? (:color color-opacity))
    "#00000000"
    (fc/fill->argb-hex color-opacity)))

(defn- fill-argb [shape]
  (let [fill (fc/first-fill shape)]
    (cond
      (nil? fill) nil
      (some? (:fill-image fill)) "image"
      (some? (:fill-color-gradient fill)) "gradient"
      :else (fc/fill->argb-hex fill))))

(defn- stroke-argb [shape]
  (let [stroke (first (:strokes shape))]
    (when (and stroke (not= :none (:stroke-style stroke)))
      {:color (fc/fill->argb-hex {:color (:stroke-color stroke)
                                  :opacity (:stroke-opacity stroke)})
       :width (d/nilv (:stroke-width stroke) 1)
       :style (d/name (or (:stroke-style stroke) :solid))})))

(defn- radius-xy [shape]
  (let [r (fc/shape-radius shape)]
    (cond
      (= r :circle) nil ;; use Ellipse element
      (vector? r) (let [[r1 r2 r3 r4] r]
                    (if (and (= r1 r2) (= r2 r3) (= r3 r4)) [r1 r1] [r1 r1])) ;; WinUI Rectangle has single RadiusX/RadiusY
      :else nil)))

(defn- pos-attrs [objects shape origin]
  (let [pos (fc/rel-position objects shape origin)
        size (fc/shape-size shape)]
    (dm/fmt "Canvas.Left=\"%\" Canvas.Top=\"%\" Width=\"%\" Height=\"%\""
            (fc/fmt-num (:left pos))
            (fc/fmt-num (:top pos))
            (fc/fmt-num (:width size))
            (fc/fmt-num (:height size)))))

(defn- font-weight [w]
  (if (str/blank? w) "Normal"
      (let [n (js/parseInt w 10)]
        (cond
          (js/isNaN n) (if (#{"bold" "Bold"} w) "Bold" "Normal")
          (>= n 600) "Bold"
          :else "Normal"))))

(defn- text-align [a]
  ({"left" "Left" "center" "Center" "right" "Right" "justify" "Justify"} a "Left"))

(defn- v-align [a]
  ({"top" "Top" "center" "Center" "bottom" "Bottom"} a "Top"))

(defn- rgba-string->argb [rgba-str]
  (let [nums (->> (re-seq #"\d+\.?\d*" (or rgba-str ""))
                  (map #(js/parseFloat %)))]
    (if (>= (count nums) 4)
      (fc/rgba->argb-hex (take 4 nums))
      "#FF000000")))

(defn- element-tag [shape]
  (cond
    (fc/svg-shape? shape) "Canvas"
    (cfh/text-shape? shape) "TextBlock"
    (cfh/image-shape? shape) "Image"
    (cfh/circle-shape? shape) "Ellipse"
    (fc/container? shape) "Canvas"
    :else "Rectangle"))

(defn- render-shape
  ([objects shape origin] (render-shape objects shape origin 1))
  ([objects shape origin level]
   (let [ind (indent level)
         tag (element-tag shape)
         pos (pos-attrs objects shape origin)
         opacity (fc/shape-opacity shape)
         rot (fc/rotation shape)
         opacity-attr (when opacity (dm/fmt " Opacity=\"%\"" (fc/fmt-num opacity)))
         rot-attr (when (not (zero? rot))
                    (dm/fmt " RenderTransformOrigin=\"0.5,0.5\"><Canvas.RenderTransform><RotateTransform Angle=\"%\"/></Canvas.RenderTransform>"
                             (fc/fmt-num rot)))]
     (cond
       (fc/hidden? shape) nil

       (cfh/text-shape? shape)
       (let [typo (fc/extract-typography shape)
             txt (fc/extract-text shape)]
         (dm/fmt "%<TextBlock % %\n        Text=\"%\"\n        FontFamily=\"%\"\n        FontSize=\"%\"\n        FontWeight=\"%\"\n        FontStyle=\"%\"\n        Foreground=\"%\"\n        TextAlignment=\"%\"\n        VerticalAlignment=\"%\"\n        TextWrapping=\"Wrap\" />"
                 ind pos (or opacity-attr "")
                 (escape-xaml txt)
                 (escape-xaml (:font-family typo))
                 (fc/fmt-num (:font-size typo))
                 (font-weight (:font-weight typo))
                 (if (= (:font-style typo) "italic") "Italic" "Normal")
                 (rgba-string->argb (:color typo))
                 (text-align (:text-align typo))
                 (v-align (:vertical-align typo))))

       (cfh/image-shape? shape)
       (let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))
             url (when data (cfg/resolve-file-media data))]
         (dm/fmt "%<Image % Source=\"%\" Stretch=\"UniformToFill\"% />"
                 ind pos (escape-xaml (or url "")) (or opacity-attr "")))

       (cfh/circle-shape? shape)
       (let [bg (fill-argb shape)]
         (dm/fmt "%<Ellipse % % % % />"
                 ind pos (or opacity-attr "")
                 (if bg (dm/fmt "Fill=\"%\" " bg) "")
                 (if-let [s (stroke-argb shape)]
                   (dm/fmt "Stroke=\"%\" StrokeThickness=\"%\" " (:color s) (fc/fmt-num (:width s))) "")))

       (fc/container? shape)
       (let [bg (fill-argb shape)
             image-bg? (= bg "image")
             data (when image-bg? (or (:metadata shape) (:fill-image shape)))
             url (when data (cfg/resolve-file-media data))
             bg-attr (cond
                       (nil? bg) ""
                       image-bg? ""
                       (= bg "gradient") ""
                       :else (dm/fmt " Background=\"%\"" bg))
             rot-str (or (when (not (zero? rot)) rot-attr) "")
             attr-str (dm/str (or opacity-attr "") bg-attr " " rot-str)
             children (fc/children-of objects shape)
             child-code (->> children
                            (keep #(render-shape objects % origin (inc level)))
                            (str/join "\n"))
             image-child (when image-bg?
                           (dm/fmt "%<Image Stretch=\"UniformToFill\" Source=\"%\" />"
                                   ind (escape-xaml (or url ""))))
             body (str/join "\n" (remove str/blank? [child-code image-child]))]
         (if (str/blank? body)
           (dm/fmt "%<Canvas % % />"
                   ind pos attr-str)
           (dm/fmt "%<Canvas % %>\n%\n%</Canvas>"
                   ind pos attr-str body ind)))

       :else
       (let [bg (fill-argb shape)
             s (stroke-argb shape)
             [rx ry] (or (radius-xy shape) [nil nil])]
         (dm/fmt "%<Rectangle % % % % % % />"
                 ind pos (or opacity-attr "")
                 (if bg (dm/fmt "Fill=\"%\" " bg) "")
                 (if-let [st s] (dm/fmt "Stroke=\"%\" StrokeThickness=\"%\" " (:color st) (fc/fmt-num (:width st))) "")
                 (if rx (dm/fmt "RadiusX=\"%\" RadiusY=\"%\" " (fc/fmt-num rx) (fc/fmt-num ry)) "")
                 (when (not (zero? rot)) rot-attr)))))))

(defn generate
  [objects shapes]
  (let [roots (fc/root-originals objects shapes)
        origin (fc/selection-origin roots)
        size (fc/selection-size roots)
        body (->> roots
                  (keep #(render-shape objects % origin 1))
                  (str/join "\n"))]
    (dm/fmt
     "<!-- WinUI 3 (Windows App SDK) XAML. Place under a Page or UserControl. -->\n<Canvas Width=\"%\" Height=\"%\" Background=\"White\">\n%\n</Canvas>\n"
     (fc/fmt-num (:width size))
     (fc/fmt-num (:height size))
     body)))

;; ---------------------------------------------------------------------------
;; Multi-file WinUI 3 project (Feature 2 code export)
;; ---------------------------------------------------------------------------

(defn- comp-name-from [roots]
  (or (some-> (seq roots) first fc/component-name) "ExportPage"))

(defn- page-xaml [comp-name body]
  (dm/fmt
   "<!-- WinUI 3 (Windows App SDK) XAML page. -->\n<Page\n    x:Class=\"PenpotExport.%\"\n    xmlns=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\"\n    xmlns:x=\"http://schemas.microsoft.com/winfx/2006/xaml\">\n%\n</Page>\n"
   comp-name body))

(defn- page-xaml-cs [comp-name]
  (dm/fmt
   "using Microsoft.UI.Xaml.Controls;\n\nnamespace PenpotExport;\n\npublic sealed partial class % : Page\n{\n    public %()\n    {\n        InitializeComponent();\n    }\n}\n"
   comp-name comp-name))

(defn- winui-readme [comp-name]
  (dm/fmt
   "# %\n\nGenerated with Ovion Desktop (WinUI 3 / Windows App SDK).\n\n## Use\n\nPlace `%.xaml` and `%.xaml.cs` in a WinUI 3 project (unpackaged or\nMSIX) under the `PenpotExport` namespace. The layout uses a root\n`Canvas` with `Canvas.Left` / `Canvas.Top` for absolute positioning.\n\nRemote images load via `Image Source` (WinUI supports http(s) URIs).\n"
   comp-name comp-name comp-name))

(defn generate-project
  "Multi-file WinUI 3 page. The `:primary` file (`<Page>.xaml`) wraps the\nsingle-string `generate` output (the `<Canvas>` fragment) inside a\n`<Page>` with an `x:Class`. The code-behind (`<Page>.xaml.cs`) wires up\nthe partial class; a README explains integration."
  [objects shapes opts]
  (let [roots (fc/root-originals objects shapes)
        comp-name (comp-name-from roots)
        primary-path (dm/str comp-name ".xaml")
        body (generate objects shapes)]
    {:files {primary-path (page-xaml comp-name body)
             (dm/str comp-name ".xaml.cs") (page-xaml-cs comp-name)
             "README.md" (winui-readme comp-name)}
     :binary-assets []
     :raster-requests []
     :primary primary-path
     :label "WinUI 3 XAML"
     :uses-rn-svg? false
     :uses-masked-view? false}))