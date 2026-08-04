;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.site-audit
  "In-design accessibility + hierarchy audit (parity gap P2.35).

  Pure, read-only scan of the current page's shape tree. Walks every
  shape (recursing into frames / groups) and reports four categories of
  issue as maps:

    {:severity :error|:warning
     :category  :contrast|:alt-text|:hierarchy|:touch-target
     :shape-id  uuid
     :message   string
     :suggestion string}

  Nothing is persisted: the UI component owns the result and re-runs the
  scan on demand. The WCAG contrast math is re-implemented here (rather
  than calling the private `defn-` helpers in `ui.inspect.a11y`) so the
  data namespace stays self-contained and free of UI/Rumext deps.

  Checks:
    1. Contrast — every text shape vs. nearest ancestor solid fill
       (else white). Flag < 4.5:1 (normal) / < 3:1 (large text, >= 18px
       or >= 14px bold).
    2. Missing alt text — image shapes with no :name AND no :a11y
       :label annotation.
    3. Hierarchy — frames/groups with >= 2 text children but no heading
       (no role :heading, no text >= 20px), plus text-size jumps that
       skip an intermediate level (adjacent distinct sizes within a
       frame whose ratio > 2.5).
    4. Touch target — interactive-looking shapes (a11y role in
       button/link/checkbox/switch, or name matching button|btn|link|cta)
       whose width OR height is below 44px.

  Analytics (A/B testing, funnels, click tracking) is intentionally NOT
  implemented — it requires server-side hosting that the desktop shell
  does not provide. See the wiring spec / deferral note in the UI ns."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [app.util.code-gen.frameworks.common :as fc]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; WCAG contrast math (mirrors ui.inspect.a11y; kept private here so the
;; data ns has no UI dep)
;; ---------------------------------------------------------------------------

(defn- rgba-string->channels
  "Parse an 'rgba(r,g,b,a)' string into [r g b a] with r/g/b in 0..255
  and a in 0..1. Falls back to opaque black."
  [s]
  (let [nums (->> (re-seq #"\d+\.?\d*" (or s ""))
                  (map #(js/parseFloat %)))]
    (if (>= (count nums) 4)
      (let [[r g b a] nums] [r g b a])
      [0 0 0 1])))

(defn- channel->linear
  "Linearize a 0..1 sRGB channel per WCAG."
  [c]
  (if (<= c 0.03928)
    (/ c 12.92)
    (js/Math.pow (/ (+ c 0.055) 1.055) 2.4)))

(defn- relative-luminance
  "WCAG relative luminance for [r g b a] (r/g/b 0..255, a 0..1). Alpha is
  composited over white (the assumed page backdrop)."
  [[r g b a]]
  (let [a     (d/nilv a 1)
        comp  (fn [ch] (+ (* a (/ ch 255)) (* (- 1 a) 1)))
        lr    (channel->linear (comp r))
        lg    (channel->linear (comp g))
        lb    (channel->linear (comp b))]
    (+ (* 0.2126 lr) (* 0.7152 lg) (* 0.0722 lb))))

(defn- contrast-ratio
  "WCAG contrast ratio between two [r g b a] colors, as a number >= 1."
  [c1 c2]
  (let [l1 (relative-luminance c1)
        l2 (relative-luminance c2)
        lighter (js/Math.max l1 l2)
        darker  (js/Math.min l1 l2)]
    (/ (+ lighter 0.05) (+ darker 0.05))))

(defn- round2
  [n]
  (/ (js/Math.round (* n 100)) 100))

;; ---------------------------------------------------------------------------
;; Text + background resolution (mirrors ui.inspect.a11y)
;; ---------------------------------------------------------------------------

(defn- first-solid-fill-color
  "The first non-hidden solid fill of `shape` as an rgba string, or nil
  when the shape has no solid fill (image / gradient / none). Reuses
  `fc/first-fill` + `fc/fill-color-rgba` so the color format matches the
  existing contrast checker exactly."
  [shape]
  (when-let [fill (fc/first-fill shape)]
    (when (and (some? (:fill-color fill))
               (nil? (:fill-color-gradient fill))
               (nil? (:fill-image fill)))
      (fc/fill-color-rgba {:color   (:fill-color fill)
                           :opacity (:fill-opacity fill)}))))

(defn- ancestor-background
  "Walk the parent chain (via `objects`) from `shape` and return the
  first solid fill color rgba string found on an ancestor, or nil
  (caller falls back to white)."
  [objects shape]
  (loop [parent-id (:parent-id shape)]
    (when (some? parent-id)
      (let [parent (get objects parent-id)]
        (if-let [bg (and parent (first-solid-fill-color parent))]
          bg
          (recur (:parent-id parent)))))))

(defn- text-color-channels
  "The text color of a text shape (from its first text node's fill), as
  [r g b a]. Falls back to opaque black."
  [shape]
  (let [typo (fc/extract-typography shape)]
    (rgba-string->channels (:color typo))))

(defn- background-color-channels
  "The background color behind a text shape: nearest ancestor solid
  fill, else white. Returns [r g b a]."
  [objects shape]
  (if-let [bg (ancestor-background objects shape)]
    (rgba-string->channels bg)
    [255 255 255 1]))

(defn- font-size-px
  "Parsed font-size in px of a text shape (defaults to 14). Returns 0
  when unparseable so callers can filter non-positive sizes."
  [shape]
  (let [raw (or (-> (fc/extract-typography shape) :font-size) "14")
        n   (js/parseFloat raw)]
    (if (js/isNaN n) 0 n)))

(defn- font-weight
  [shape]
  (-> (fc/extract-typography shape) :font-weight))

(defn- large-text?
  "WCAG 'large text': >= 18px, or >= 14px and bold (>= 700)."
  [shape]
  (let [fs (font-size-px shape)
        fw (font-weight shape)]
    (or (>= fs 18)
        (and (>= fs 14)
             (#{:bold "700" "800" "900"} fw)))))

;; ---------------------------------------------------------------------------
;; Individual checks
;; ---------------------------------------------------------------------------

(defn- contrast-issue
  "Returns an issue map for a text shape whose contrast against its
  effective background is below the WCAG AA threshold, else nil."
  [objects shape]
  (when (cfh/text-shape? shape)
    (let [tc        (text-color-channels shape)
          bc        (background-color-channels objects shape)
          ratio     (contrast-ratio tc bc)
          large?    (large-text? shape)
          threshold (if large? 3.0 4.5)]
      (when (< ratio threshold)
        {:severity   :error
         :category   :contrast
         :shape-id   (:id shape)
         :message    (str "Contrast " (round2 ratio) ":1 — below "
                           threshold ":1 minimum for "
                           (if large? "large" "normal") " text")
         :suggestion (if large?
                       "Raise contrast to at least 3:1 (large text AA)"
                       "Raise contrast to at least 4.5:1 (normal text AA)")}))))

(defn- alt-text-issue
  "Returns an issue map for an image shape with no accessible name (no
  :name AND no :a11y :label), else nil."
  [shape]
  (when (cfh/image-shape? shape)
    (let [a11y-label (-> shape :a11y :label)
          name       (:name shape)]
      (when (and (str/blank? a11y-label) (str/blank? name))
        {:severity   :warning
         :category   :alt-text
         :shape-id   (:id shape)
         :message    "Image has no accessible name"
         :suggestion "Add a descriptive :name or aria-label so screen readers can announce it"}))))

(def ^:private interactive-roles
  "ARIA roles (from the P1.06 authoring panel) that imply an interactive
  control and therefore a minimum touch-target expectation."
  #{:button :link :checkbox :switch})

(defn- interactive-looking?
  "Heuristic: does `shape` look like an interactive control? Considers
  the authored a11y role first, then the shape name (common button/btn/
  link/cta tokens), then a component instance whose name matches."
  [shape]
  (let [role (-> shape :a11y :role)]
    (or (interactive-roles role)
        (let [nm (or (:name shape) "")
              component? (some? (:component-id shape))]
          (or (re-find #"(?i)button|btn|link|cta" nm)
              (and component?
                   (re-find #"(?i)button|btn|link|cta" nm)))))))

(defn- shape-size
  "Returns [width height] from the shape's :selrect, or [0 0] when
  geometry is missing."
  [shape]
  (let [selrect (:selrect shape)]
    [(or (:width selrect) 0) (or (:height selrect) 0)]))

(defn- touch-target-issue
  "Returns an issue map for an interactive-looking shape whose width OR
  height is below the 44px touch-target minimum, else nil."
  [shape]
  (when (interactive-looking? shape)
    (let [[w h] (shape-size shape)]
      (when (and (pos? w) (pos? h) (or (< w 44) (< h 44)))
        {:severity   :warning
         :category   :touch-target
         :shape-id    (:id shape)
         :message    (str "Interactive target " (js/Math.round w) "×"
                          (js/Math.round h) "px — below 44×44px")
         :suggestion "Increase the tap target to at least 44×44px"}))))

(def ^:private heading-threshold-px
  "Font size at or above which a text shape is treated as a heading for
  the hierarchy check (when no explicit a11y role is authored)."
  20)

(defn- heading-text?
  "Is `shape` a text shape that acts as a heading? Honors an authored
  :a11y :role of :heading; otherwise treats text >= heading-threshold-px
  as a heading."
  [shape]
  (and (cfh/text-shape? shape)
       (or (#{:heading} (-> shape :a11y :role))
           (>= (font-size-px shape) heading-threshold-px))))

(defn- size-jump-issues
  "Within a frame/group, compare the distinct font sizes of direct text
  children (sorted descending). For each adjacent pair (big, small)
  whose ratio > 2.5, emit a warning that the size scale skips an
  intermediate level. The offending shape is the text child at the
  bigger size (clicking the issue selects it)."
  [objects frame]
  (let [children (cfh/get-immediate-children objects (:id frame))
        text-kids (filter cfh/text-shape? children)
        sized (keep (fn [s] (let [fs (font-size-px s)]
                              (when (pos? fs) [fs s]))) text-kids)]
    (if (< (count sized) 2)
      []
      (let [;; group sizes desc; map size -> first shape at that size
            by-size (into {} (for [[fs s] sized] [fs s]))
            sizes   (sort > (keys by-size))]
        (loop [rem sizes out []]
          (if (< (count rem) 2)
            out
            (let [big (first rem)
                  small (second rem)]
              (if (> (/ big small) 2.5)
                (recur (next rem)
                       (conj out
                             {:severity   :warning
                              :category   :hierarchy
                              :shape-id   (:id (by-size big))
                              :message    (str "Text size jumps from "
                                              (round2 big) "px to "
                                              (round2 small)
                                              "px — skips intermediate level")
                              :suggestion "Add an intermediate text size between these levels"}))
                (recur (next rem) out)))))))))

(defn- hierarchy-issues
  "Hierarchy issues for a single frame/group: (1) the frame contains
  >= 2 text children but none acts as a heading; (2) text-size jumps
  that skip an intermediate level among its direct text children."
  [objects frame]
  (let [children   (cfh/get-immediate-children objects (:id frame))
        text-kids  (filter cfh/text-shape? children)
        no-heading (and (>= (count text-kids) 2)
                        (not (some heading-text? text-kids)))
        jumps     (size-jump-issues objects frame)]
    (cond-> []
      no-heading
      (conj {:severity   :warning
             :category   :hierarchy
             :shape-id   (:id frame)
             :message    "Frame has text content but no heading"
             :suggestion "Add a heading-level text (>= 20px or role:heading)"})
      (seq jumps)
      (into jumps))))

;; ---------------------------------------------------------------------------
;; Tree walk + public entry point
;; ---------------------------------------------------------------------------

(defn- all-shapes
  "Lazy depth-first seq of every shape in `objects`, starting from the
  page roots (children of the uuid/zero root entry). Each shape is the
  full shape map from `objects`."
  [objects]
  (letfn [(walk [id]
            (when-let [shape (get objects id)]
              (cons shape (mapcat walk (:shapes shape)))))]
    (let [root-ids (-> objects (get uuid/zero) :shapes)]
      (mapcat walk (or root-ids [])))))

(defn run-audit
  "Scan every shape on the page (the `objects` map of the current page)
  and return a vector of accessibility issue maps (see ns docstring for
  the issue schema). Read-only — no state is mutated. Returns [] for an
  empty / nil objects map.

  Hierarchy checks are emitted per frame/group (the frame is the
  offending shape for 'no heading'; the larger text is the offending
  shape for a size jump). All other checks are per shape."
  [objects]
  (if-not (map? objects)
    []
    (let [shapes (all-shapes objects)
          per-frame (->> shapes
                         (filter #(or (cfh/frame-shape? %)
                                      (cfh/group-shape? %)))
                         (mapcat #(hierarchy-issues objects %)))]
      (into [] (concat per-frame
                       (for [shape  shapes
                             issue  (concat
                                     (some-> (contrast-issue objects shape) vector)
                                     (some-> (alt-text-issue shape) vector)
                                     (some-> (touch-target-issue shape) vector))]
                         issue))))))