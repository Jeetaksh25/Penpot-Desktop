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

(defn- render-shape
  ([objects shape origin] (render-shape objects shape origin 1))
  ([objects shape origin level]
   (let [ind (indent level)
         tag (element-tag shape)
         attrs (box-attrs objects shape origin level)]
     (cond
       (fc/hidden? shape) nil

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
        body (->> roots
                  (keep #(render-shape objects % origin 1))
                  (str/join "\n"))]
    (dm/fmt
     "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<FrameLayout\n    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    xmlns:tools=\"http://schemas.android.com/tools\"\n    android:layout_width=\"%dp\"\n    android:layout_height=\"%dp\"\n    android:clipChildren=\"false\">\n%\n</FrameLayout>\n"
     (fc/fmt-num (:width size))
     (fc/fmt-num (:height size))
     body)))