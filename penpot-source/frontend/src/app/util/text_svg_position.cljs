;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.text-svg-position
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.transit :as transit]
   [app.main.fonts :as fonts]
   [app.util.dom :as dom]
   [app.util.text-position-data :as tpd]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn parse-text-nodes
  "Given a text node retrieves the rectangles for everyone of its paragraphs and its text."
  [parent-node direction text-node text-align]
  (letfn [(parse-entry [^js entry]
            (when (some? (.-position entry))
              {:node      (.-node entry)
               :position  (dom/bounding-rect->rect (.-position entry))
               :text      (.-text entry)
               :direction direction}))]
    (into []
          (keep parse-entry)
          (tpd/parse-text-nodes parent-node text-node text-align))))

(def load-promises (atom {}))

(defn load-font
  [font]
  (if (contains? @load-promises font)
    (get @load-promises font)
    (let [load-promise (dom/load-font font)]
      (swap! load-promises assoc font load-promise)
      load-promise)))

(defn resolve-font
  [node]
  (let [styles  (dom/get-computed-styles node)
        font    (dom/get-property-value styles "font")
        font    (if (or (not font) (empty? font))
                  ;; Firefox 95 won't return the font correctly.
                  ;; We can get the font shorthand with the font-size + font-family
                  (str/ffmt "% %"
                            (dom/get-property-value styles "font-size")
                            (dom/get-property-value styles "font-family"))
                  font)

        font-id (dom/get-property-value styles "--font-id")]

    (->> (fonts/ensure-loaded! font-id)
         (p/fmap (fn []
                   (when-not ^boolean (dom/check-font? font)
                     (load-font font))))
         (p/merr (fn [_cause]
                   (js/console.error (str/ffmt "Cannot load font %" font-id))
                   (p/resolved nil))))))


(defn- process-text-node
  [parent-node]
  (let [root       (dom/get-parent-with-selector parent-node ".text-node-html")
        paragraph  (dom/get-parent-with-selector parent-node ".paragraph")
        shape-x    (d/parse-double (dom/get-attribute root "data-x"))
        shape-y    (d/parse-double (dom/get-attribute root "data-y"))
        direction  (.-direction ^js (dom/get-computed-styles parent-node))
        text-align (.-textAlign ^js (dom/get-computed-styles paragraph))]

    (sequence
     (comp
      (mapcat #(parse-text-nodes parent-node direction % text-align))
      (map #(-> %
                (update-in [:position :x] + shape-x)
                (update-in [:position :y] + shape-y))))
     (seq (.-childNodes parent-node)))))

(defn- calc-text-node-positions
  [shape-id]
  (let [text-nodes (-> (dom/query (dm/fmt "#html-text-node-%" shape-id))
                       (dom/query-all ".text-node"))]
    (->> (p/all (map resolve-font text-nodes))
         (p/fmap #(mapcat process-text-node text-nodes)))))

(defn calc-position-data
  [shape-id]
  (letfn [(get-prop [styles prop]
            (let [value (.getPropertyValue styles prop)]
              (when (and (some? value) (not= value ""))
                value)))

          (transform-data [{:keys [node position text direction]}]
            (let [styles   (dom/get-computed-styles node)
                  position (assoc position :y (+ (dm/get-prop position :y)
                                                 (dm/get-prop position :height)))

                  paragraph   (dom/get-parent-with-selector node ".paragraph")
                  para-styles (when (some? paragraph) (dom/get-computed-styles paragraph))

                  ;; Feature 17 — truncation. `generate-paragraph-styles` sets
                  ;; `-webkit-line-clamp` (via `WebkitLineClamp`) ONLY when
                  ;; `:text-overflow` is `truncate` and `:max-lines` is a
                  ;; positive number. Reading it back is additive: when the
                  ;; clamp is absent the computed value is ""/`none`, both
                  ;; `:text-overflow` and `:max-lines` become nil and are
                  ;; dropped by `(filter val)` — byte-identical position-data.
                  max-lines-raw (when (some? para-styles) (get-prop para-styles "-webkit-line-clamp"))
                  max-lines-num (some-> max-lines-raw js/parseFloat)
                  text-overflow (when (and (some? max-lines-num) (pos? max-lines-num)) "truncate")
                  max-lines     (when (some? text-overflow) (dm/str max-lines-num))

                  ;; Feature 50 — hanging punctuation. The paragraph emits the
                  ;; real CSS `hanging-punctuation: first last` when the model
                  ;; flag is true. We carry a boolean forward so the SVG
                  ;; renderer can hang leading punctuation itself (Chromium
                  ;; WebView2 does not render the CSS property). Absent /
                  ;; `none` / "" -> false -> dropped by `(filter val)` ->
                  ;; byte-identical.
                  hanging-punctuation
                  (when (some? para-styles)
                    (let [v (get-prop para-styles "hanging-punctuation")]
                      (and (some? v) (not= v "") (not= v "none"))))]

              (into position (filter val)
                    {:direction       direction
                     :font-family     (dm/str (get-prop styles "font-family"))
                     :font-size       (dm/str (get-prop styles "font-size"))
                     :font-weight     (dm/str (get-prop styles "font-weight"))
                     :text-transform  (dm/str (get-prop styles "text-transform"))
                     :text-decoration (dm/str (get-prop styles "text-decoration"))
                     :letter-spacing  (dm/str (get-prop styles "letter-spacing"))
                     :font-style      (dm/str (get-prop styles "font-style"))
                     :fills           (transit/decode-str (get-prop styles "--fills"))
                     :text            text

                     ;; Feature 78 — super/sub. Maps to CSS `vertical-align`;
                     ;; the computed default is `baseline`, which the SVG
                     ;; renderer ignores (guard `#{"super" "sub"}`), so
                     ;; carrying it through is byte-identical when absent.
                     :baseline-shift  (dm/str (get-prop styles "vertical-align"))

                     ;; Feature 18 — hyperlink. Round-tripped as the
                     ;; `--hyperlink` transit custom property (set in
                     ;; `generate-text-styles`). Absent -> nil -> dropped.
                     :hyperlink       (transit/decode-str (get-prop styles "--hyperlink"))

                     ;; Feature 12 / 13 — OpenType & variable-font axes. The
                     ;; computed default is `normal`; we drop it so the SVG
                     ;; `style` object stays byte-identical when no axes are
                     ;; configured.
                     :font-feature-settings
                     (let [v (get-prop styles "font-feature-settings")]
                       (when (and (some? v) (not= v "normal"))
                         (dm/str v)))
                     :font-variation-settings
                     (let [v (get-prop styles "font-variation-settings")]
                       (when (and (some? v) (not= v "normal"))
                         (dm/str v)))

                     ;; Feature 17 / 50 — paragraph-level fields carried into
                     ;; every block of the paragraph (nil/false when the
                     ;; feature is absent -> dropped by `(filter val)`).
                     :text-overflow       text-overflow
                     :max-lines           max-lines
                     :hanging-punctuation hanging-punctuation})))]

    (when (some? shape-id)
      (->> (calc-text-node-positions shape-id)
           (p/fmap (fn [text-data]
                     (mapv transform-data text-data)))))))
