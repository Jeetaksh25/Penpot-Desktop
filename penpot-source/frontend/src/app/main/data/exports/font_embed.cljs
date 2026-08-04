;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.exports.font-embed
  "P2.20 — Fonts embedding in exported SVG documents.

  When the user enables the per-export \"Embed fonts\" toggle (default
  OFF, so exports with the toggle off are byte-identical to today), the
  SVG export is post-processed on the client: the distinct
  font-family/variant pairs used by the exported shapes' text are
  collected, their @font-face CSS is fetched, every url(...) in that CSS
  is fetched as a blob and re-encoded as a base64 data: URI, and the
  resulting self-contained <style> block is injected into the SVG so the
  file renders correctly without the viewer having the fonts installed.

  v1 notes:
    * No subsetting — the full glyph table of each variant is embedded.
      This keeps the implementation simple and lossless at the cost of
      larger files; a subset pass (Pyftsubset/harfbuzz) can be added
      later when a wasm helper is available.
    * PDF export is NOT touched: client-side PDF binary font embedding
      is out of scope for v1, so PDF output is byte-identical regardless
      of the toggle.
    * Only the single-export path is post-processed here. The batch
      (multi-export zip) path still downloads the backend zip as-is."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.main.fonts :as fonts]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [clojure.string :as cstr]
   [cuerdas.core :as str]))

(defn- collect-from-shape
  "Recursively collect the set of font refs ({:font-id :font-variant-id ...})
  used by `shape` and all of its descendants in `objects`."
  [objects shape]
  (let [self   (if (cfh/text-shape? shape)
                 (fonts/get-content-fonts (:content shape))
                 #{})
        children (cfh/get-children objects (:id shape))]
    (reduce into self (map (partial collect-from-shape objects) children))))

(defn collect-export-font-refs
  "Collect the distinct font refs used by every text shape in the export
  subtree rooted at each shape in `shapes`. `objects` is the page objects
  map so descendants can be resolved."
  [shapes objects]
  (reduce into #{} (map (partial collect-from-shape objects) shapes)))

(defn- fetch-data-uris
  "Fetch every font `url` as a blob and read it back as a base64 data:
  URI. Emits a vector of [original-url data-uri-or-nil] pairs (nil when a
  fetch fails, so the original url is left intact for that face)."
  [urls]
  (->> (rx/from urls)
       (rx/merge-map
        (fn [url]
          ;; http/fetch-data-uri emits {<url> <data-uri-string>} and is
          ;; cached for 4h, so repeated exports of the same font are cheap.
          (->> (http/fetch-data-uri url true)
               (rx/map (fn [m] [url (get m url)]))
               (rx/catch (fn [_] (rx/of [url nil]))))))
       (rx/reduce conj [])))

(defn- replace-urls
  "Substitute each original url(...) in `css` with its base64 data: URI,
  leaving faces whose fetch failed on their original url (a proxy url
  that still resolves on the same machine)."
  [css url-pairs]
  (reduce (fn [css [orig data-uri]]
            (if (some? data-uri)
              (cstr/replace css orig data-uri)
              css))
          css
          url-pairs))

(defn build-embedded-font-css
  "Return an rx that emits a single CSS string of @font-face rules for
  `font-refs` with every url(...) replaced by a base64 data: URI. Emits
  \"\" when no fonts are found, so the caller can skip injection."
  [font-refs]
  (if (or (nil? font-refs) (empty? font-refs))
    (rx/of "")
    (->> (fonts/render-font-styles font-refs)
         (rx/merge-map
          (fn [css]
            (let [urls (fonts/extract-fontface-urls css)]
              (if (empty? urls)
                (rx/of css)
                (->> (fetch-data-uris urls)
                     (rx/map (partial replace-urls css))))))))))

(defn inject-style-into-svg
  "Insert `css` as a <style> element immediately after the opening
  <svg ...> tag of `svg-text`. Returns `svg-text` unchanged when `css` is
  blank. We insert at the root (rather than inside <defs>) so the rule
  applies before any <text> is painted regardless of how the exporter
  structures <defs>."
  [svg-text css]
  (if (or (nil? css) (str/blank? css))
    svg-text
    (let [style-block (dm/str "<style type=\"text/css\">" css "</style>")
          match       (re-find #"<svg[^>]*>" svg-text)]
      (if (nil? match)
        ;; No <svg> root (malformed/non-SVG payload) — leave untouched.
        svg-text
        (let [idx (cstr/index-of svg-text match)
              cut (+ idx (count match))]
          ;; Insert the <style> right after the opening <svg ...> tag,
          ;; keeping any <?xml ...?> declaration and the root tag intact.
          (dm/str (subs svg-text 0 cut) style-block (subs svg-text cut)))))))

(defn embed-fonts-in-svg
  "Given the SVG text returned by the backend export and the
  shapes/objects being exported, return an rx that emits a new SVG
  string with all @font-face rules embedded as base64 data: URIs. When
  no text fonts are used the original string is emitted unchanged."
  [svg-text shapes objects]
  (let [font-refs (collect-export-font-refs shapes objects)]
    (->> (build-embedded-font-css font-refs)
         (rx/map (fn [css]
                   (inject-style-into-svg svg-text css))))))