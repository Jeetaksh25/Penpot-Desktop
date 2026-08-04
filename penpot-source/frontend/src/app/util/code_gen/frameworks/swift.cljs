;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.swift
  "Generate a SwiftUI `View` from a selection of Penpot shapes. Uses a
  `ZStack` with `.frame(width:height:)` + `.position(x:y:)` for absolute
  layout (`.position` is center-based, so x = left + width/2). Colors are
  emitted as `Color(red:green:blue:opacity:)` with 0..1 channel values.
  Rectangles become `Rectangle().fill(...)`, circles become `Circle()`,
  text becomes `Text(...).font(.system(size:...))`, frames/groups become
  nested `ZStack`s with a `.background(...)` fill.

  Known limitations (documented inline): gradients / complex SVG shapes
  are emitted as a placeholder `Rectangle` with a comment (SwiftUI has no
  built-in SVG view; a real path/drawable needs SwiftSVG or a raster
  asset — deferred). Remote images use `AsyncImage` (iOS 15+). Component
  hoisting is NOT applied (instances flatten inline)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.config :as cfg]
   [app.util.code-gen.code-connect :as cc]
   [app.util.code-gen.frameworks.common :as fc]
   [cuerdas.core :as str]))

(defn- indent [n] (str/repeat "    " n))

(defn- escape-swift [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\r" "")
      (str/replace "\n" "\\n")))

(defn- swift-string [s] (dm/str "\"" (escape-swift s) "\""))

(defn- clamp-unit
  "Clamp a 0..255 channel to 0..1 with 4-decimal precision for SwiftUI
  `Color(red:green:blue:opacity:)`."
  [x]
  (let [v (js/Math.max 0 (js/Math.min 255 (or x 0)))]
    (/ (js/Math.round (* (/ v 255) 10000)) 10000)))

(defn- rgba-string->color [rgba-str]
  (let [nums (->> (re-seq #"\d+\.?\d*" (or rgba-str ""))
                  (map #(js/parseFloat %)))]
    (if (>= (count nums) 4)
      (let [[r g b a] nums]
        (dm/str "Color(red: " (clamp-unit r)
                ", green: " (clamp-unit g)
                ", blue: " (clamp-unit b)
                ", opacity: " (d/nilv a 1) ")"))
      "Color(red: 0, green: 0, blue: 0, opacity: 1)")))

(defn- argb-hex->color [hex]
  (if (str/blank? hex)
    "Color(red: 0, green: 0, blue: 0, opacity: 0)"
    (let [h (str/replace hex #"^#" "")
          n (count h)]
      ;; Accept #RRGGBB or #AARRGGBB.
      (cond
        (= n 6) (dm/str "Color(red: " (clamp-unit (js/parseInt (subs h 0 2) 16))
                        ", green: " (clamp-unit (js/parseInt (subs h 2 4) 16))
                        ", blue: " (clamp-unit (js/parseInt (subs h 4 6) 16))
                        ", opacity: 1)")
        (= n 8) (let [a (/ (js/parseInt (subs h 0 2) 16) 255)]
                  (dm/str "Color(red: " (clamp-unit (js/parseInt (subs h 2 4) 16))
                          ", green: " (clamp-unit (js/parseInt (subs h 4 6) 16))
                          ", blue: " (clamp-unit (js/parseInt (subs h 6 8) 16))
                          ", opacity: " a ")"))
        :else "Color(red: 0, green: 0, blue: 0, opacity: 1)"))))

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

(defn- stroke-attrs [shape]
  (let [stroke (first (:strokes shape))]
    (when (and stroke (not= :none (:stroke-style stroke)))
      {:color (argb-hex->color (fc/fill->argb-hex {:color (:stroke-color stroke)
                                                   :opacity (:stroke-opacity stroke)}))
       :width (d/nilv (:stroke-width stroke) 1)})))

(defn- frame+position
  "Return the `.frame(...)` + `.position(...)` modifier string for a
  shape placed relative to `origin`. `.position` is center-based. The
  returned string begins with `.frame` (no leading indent) so it can be
  appended directly to a view expression on the same line."
  [objects shape origin level]
  (let [pos (fc/rel-position objects shape origin)
        size (fc/shape-size shape)
        cx (+ (:left pos) (/ (:width size) 2))
        cy (+ (:top pos) (/ (:height size) 2))
        ind (indent level)]
    (dm/str ".frame(width: " (fc/fmt-num (:width size))
            ", height: " (fc/fmt-num (:height size)) ")\n" ind
            ".position(x: " (fc/fmt-num cx)
            ", y: " (fc/fmt-num cy) ")")))

(defn- overlay-stroke
  "Emit a `.overlay(...)` modifier for a shape's stroke, stroking a view
  that matches the filled shape's geometry (Circle / RoundedRectangle /
  UnevenRoundedRectangle / Rectangle) so the outline follows the shape
  instead of a sharp bounding rect. Returns \"\" when no stroke."
  [shape level]
  (if-let [s (stroke-attrs shape)]
    (let [r (radius shape)
          stroke-view (cond
                         (= r :circle) "Circle()"
                         (number? r) (dm/str "RoundedRectangle(cornerRadius: " (fc/fmt-num r) ")")
                         (vector? r) (let [[r1 r2 r3 r4] r]
                                       (dm/str "UnevenRoundedRectangle(cornerRadius: 0"
                                               ", topLeadingCornerRadius: " (fc/fmt-num r1)
                                               ", topTrailingCornerRadius: " (fc/fmt-num r2)
                                               ", bottomTrailingCornerRadius: " (fc/fmt-num r3)
                                               ", bottomLeadingCornerRadius: " (fc/fmt-num r4) ")"))
                         :else "Rectangle()")]
      (dm/str "\n" (indent level)
              ".overlay(" stroke-view ".stroke(" (:color s)
              ", lineWidth: " (fc/fmt-num (:width s)) "))"))
    ""))

(defn- render-shape
  ([objects shape origin] (render-shape objects shape origin 1))
  ([objects shape origin level]
   (let [ind (indent level)
         opacity (fc/shape-opacity shape)
         frame-mod (frame+position objects shape origin level)
         cc-binding (fc/code-connect-binding objects shape)]
     (cond
       (fc/hidden? shape) nil

       ;; Code Connect binding (P1.08): emit the mapped code component
       ;; `Tag(props)` with the shape's frame + position modifiers, instead
       ;; of recursing into a generic ZStack.
       (some? cc-binding)
       (let [binding cc-binding]
         (let [tag (:tag binding)
               props (cc/format-props-swift binding)]
           (dm/str ind "// Code Connect: " tag "\n"
                   ind tag "(" props ")\n"
                   ind "        " (frame+position objects shape origin level))))

       (fc/svg-shape? shape)
       ;; SwiftUI has no built-in SVG view. Emit a placeholder Rectangle
       ;; (transparent) with a comment pointing at the SVG export. A real
       ;; implementation needs SwiftSVG or a raster asset — deferred.
       (dm/str ind "// SVG shape — export the project to embed the SVG asset\n"
               ind "Rectangle()\n" ind "        .fill(Color(red: 0, green: 0, blue: 0, opacity: 0))"
               frame-mod (overlay-stroke shape level))

       (cfh/text-shape? shape)
       (let [typo (fc/extract-typography shape)
             txt (fc/extract-text shape)
             color (rgba-string->color (:color typo))
             align-kw ({"left" "leading" "center" "center" "right" "trailing" "justify" "leading"}
                       (:text-align typo) "leading")]
         (dm/str ind "Text(" (swift-string txt) ")\n"
                 ind "        .font(.system(size: " (fc/fmt-num (:font-size typo)) "))\n"
                 ind "        .foregroundColor(" color ")\n"
                 ind "        .multilineTextAlignment(." align-kw ")\n"
                 ind "        " frame-mod))

       (cfh/image-shape? shape)
       (let [data (or (:metadata shape) (:fill-image shape) (-> shape :fills first :fill-image))
             url (when data (cfg/resolve-file-media data))]
         (if url
           (dm/str ind "AsyncImage(url: URL(string: " (swift-string url)
                   ")) { image in image.resizable() } placeholder: { Color.gray }\n"
                   ind "        .aspectRatio(contentMode: .fill)\n"
                   ind "        " frame-mod)
           (dm/str ind "Color.gray\n" ind "        " frame-mod)))

       (fc/container? shape)
       (let [children (fc/children-of objects shape)
             child-code (->> children
                             (keep #(render-shape objects % origin (+ level 2)))
                             (str/join "\n"))
             bg (fill-color shape)
             bg-mod (cond
                      (or (= bg "image") (= bg "gradient"))
                      (dm/str "\n" ind "        .background(Color(red: 0, green: 0, blue: 0, opacity: 0)) // gradient/image fill — drawable needed")
                      (some? bg)
                      (dm/str "\n" ind "        .background(" (argb-hex->color bg) ")")
                      :else "")
             opacity-mod (if opacity
                           (dm/str "\n" ind "        .opacity(" (fc/fmt-num opacity) ")")
                           "")
             inner (if (str/blank? child-code)
                     "Color.clear"
                     (dm/str "ZStack {\n" child-code "\n" ind "    }"))]
         (dm/str ind inner bg-mod opacity-mod "\n" ind "        " frame-mod))

       :else
       ;; Plain rectangle / rounded rectangle / circle.
       (let [r (radius shape)
             bg (fill-color shape)
             shape-view (cond
                          (= r :circle) "Circle()"
                          (number? r) (dm/str "RoundedRectangle(cornerRadius: " (fc/fmt-num r) ")")
                          (vector? r) (let [[r1 r2 r3 r4] r]
                                        (dm/str "UnevenRoundedRectangle(cornerRadius: 0"
                                                ", topLeadingCornerRadius: " (fc/fmt-num r1)
                                                ", topTrailingCornerRadius: " (fc/fmt-num r2)
                                                ", bottomTrailingCornerRadius: " (fc/fmt-num r3)
                                                ", bottomLeadingCornerRadius: " (fc/fmt-num r4) ")"))
                          :else "Rectangle()")
             fill-mod (cond
                        (nil? bg) ".fill(Color.clear)"
                        (= bg "image") ".fill(Color(red: 0, green: 0, blue: 0, opacity: 0)) // image fill"
                        (= bg "gradient") ".fill(Color(red: 0, green: 0, blue: 0, opacity: 0)) // gradient fill"
                        :else (dm/str ".fill(" (argb-hex->color bg) ")"))]
         (dm/str ind shape-view fill-mod (overlay-stroke shape level) frame-mod))))))

(defn- comp-name-from [roots]
  (or (some-> (seq roots) first fc/component-name) "ExportedView"))

(defn- render-view
  "Render a SwiftUI `View` struct source string for `shapes` placed
  relative to `origin`, sized to `size`."
  [objects shapes origin size comp-name]
  (let [body (binding [fc/*framework-type* "swift"]
              (->> shapes
                   (keep #(render-shape objects % origin 2))
                   (str/join "\n")))
        inner (if (str/blank? body) "Color.clear"
                  (dm/str "ZStack {\n" body "\n    }"))]
    (dm/str "import SwiftUI\n\n"
            "struct " comp-name ": View {\n"
            "    var body: some View {\n"
            "        " inner "\n"
            "            .frame(width: " (fc/fmt-num (:width size))
            ", height: " (fc/fmt-num (:height size)) ")\n"
            "    }\n}\n\n"
            "#Preview {\n"
            "    " comp-name "()\n}\n")))

(defn generate
  "Single-string Inspect-panel preview (no hoisting — the preview can't
  represent multi-file components)."
  [objects shapes]
  (let [roots (fc/root-originals objects shapes)]
    (render-view objects roots
                 (fc/selection-origin roots)
                 (fc/selection-size roots)
                 (comp-name-from roots))))

;; ---------------------------------------------------------------------------
;; Multi-file SwiftUI project (Feature 2 code export)
;; ---------------------------------------------------------------------------

(defn- swift-readme [comp-name]
  (dm/str
   "# " comp-name "\n\n"
   "Generated with Ovion Desktop (SwiftUI).\n\n"
   "## Use\n\n"
   "The view lives in `" (fc/snake-name comp-name) ".swift` — a `View` struct using a `ZStack` with\n"
   "`.frame` + `.position` for absolute layout. Drop the file into an Xcode project (iOS 15+ for `AsyncImage`).\n\n"
   "Known limitations: gradients / complex SVG shapes are emitted as placeholder `Rectangle`s\n"
   "(SwiftUI has no built-in SVG view) — embed a raster or SwiftSVG asset for those. Component instances flatten inline.\n"))

(defn- package-swift [comp-name]
  (dm/str
   "// swift-tools-version: 5.9\n"
   "import PackageDescription\n\n"
   "let package = Package(\n"
   "    name: \"" comp-name "\",\n"
   "    platforms: [.iOS(.v15)],\n"
   "    products: [.library(name: \"" comp-name "\", targets: [\"" comp-name "\"])],\n"
   "    targets: [.target(name: \"" comp-name "\", path: \"Sources\")]\n)\n"))

(defn generate-project
  "Multi-file SwiftUI project. The `:primary` file (`Sources/<name>.swift`)
  is the `View` for the selection; the scaffold adds a `Package.swift` and
  a README. Component-instance hoisting is NOT applied — instances flatten
  inline (SwiftUI has no shared-component primitive that maps cleanly from
  Penpot's component model without a build-time code-split)."
  [objects shapes opts]
  (let [roots (fc/root-originals objects shapes)
        comp-name (comp-name-from roots)
        file-name (fc/pascal (fc/snake-name comp-name))
        primary-path (dm/str "Sources/" file-name ".swift")
        primary (generate objects shapes)]
    {:files {primary-path primary
             "Package.swift" (package-swift file-name)
             "README.md" (swift-readme comp-name)}
     :binary-assets []
     :raster-requests []
     :primary primary-path
     :label "SwiftUI"
     :uses-rn-svg? false
     :uses-masked-view? false}))