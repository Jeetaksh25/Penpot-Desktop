;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.selection-colors
  "Selection Colors aggregator + bulk-replace (ALL_APPS_PARITY P2.33).

  When two or more layers are selected, aggregates every fill + stroke
  color across the selection into a sortable list (by frequency or by
  color), and lets the user bulk-replace one color across all selected
  shapes in a single undo transaction.

  Pure aggregation (`aggregate-colors`) walks each selected shape's
  `:fills` and `:strokes` vectors, keys solid colors by uppercased hex
  and gradients by a stable signature (first stop hex, prefixed so they
  group separately from solids), and returns a seq of

    {:color <key-string> :kind :solid|:gradient :hex <repr-hex>
     :type :fill|:stroke :count n :shape-ids [...]}

  Image / pattern fills carry no swappable color and are skipped.

  The `replace-color` event rewrites every matching fill/stroke across
  the selected shape-ids in ONE undo transaction via pcb/empty-changes +
  pcb/update-shapes + dch/commit-changes. It is nil-safe and a no-op
  (rx/empty) when old==new so no spurious undo step is created. Mirrors
  data/workspace/notes.cljs for the changes-pipeline idiom."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.types.color :as clr]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; --- Color key helpers ------------------------------------------------------

(defn- solid-key
  "Aggregation key for a solid color. Uppercased hex so `#ff0000` and
  `#FF0000` group together (Penpot stores fill-colors lowercased but
  user-entered values may differ). Returns nil when `hex` is nil/blank."
  [hex]
  (when (and (string? hex) (seq hex))
    (str/upper hex)))

(defn- gradient-key
  "Aggregation key for a gradient, keyed by the uppercased hex of its
  first stop so gradients sharing a starting color group together. The
  `grad:` prefix keeps gradient keys disjoint from solid keys. Returns
  nil when the gradient has no stops."
  [gradient]
  (when (map? gradient)
    (let [stop (-> gradient :stops first)
          hex  (some-> stop :color solid-key)]
      (when hex
        (str "grad:" hex)))))

(defn- representative-hex
  "A hex string suitable for swatch rendering + hue/lightness sorting.
  For solids this is the color itself; for gradients the first stop hex.
  Falls back to `#000000` when unobtainable so sorting never throws."
  [kind hex-or-grad]
  (or hex-or-grad
      (when (= :gradient kind) "#000000")
      "#000000"))

;; --- Fill / stroke color extraction ----------------------------------------

(defn- fill-color-entry
  "Return `[kind key hex extra]` for a fill map, or nil when the fill
  carries no swappable color (image / pattern fills). `extra` is a map
  with `:gradient` (the gradient map, for gradient entries) and
  `:opacity`, used by the UI to seed the color picker."
  [fill]
  (cond
    (some? (:fill-color-gradient fill))
    (let [g (:fill-color-gradient fill)
          k (gradient-key g)
          h (some-> g :stops first :color)]
      (when k [:gradient k h {:gradient g :opacity (:fill-opacity fill 1)}]))

    (some? (:fill-color fill))
    (let [h (:fill-color fill)
          k (solid-key h)]
      (when k [:solid k h {:opacity (:fill-opacity fill 1)}]))

    :else nil))

(defn- stroke-color-entry
  "Return `[kind key hex extra]` for a stroke map, or nil when the
  stroke carries no swappable color."
  [stroke]
  (cond
    (some? (:stroke-color-gradient stroke))
    (let [g (:stroke-color-gradient stroke)
          k (gradient-key g)
          h (some-> g :stops first :color)]
      (when k [:gradient k h {:gradient g :opacity (:stroke-opacity stroke 1)}]))

    (some? (:stroke-color stroke))
    (let [h (:stroke-color stroke)
          k (solid-key h)]
      (when k [:solid k h {:opacity (:stroke-opacity stroke 1)}]))

    :else nil))

;; --- Aggregation -----------------------------------------------------------

(defn- aggregate-one
  "Fold helper: add one `[kind key hex extra]` entry for `shape-id` of
  `type` into the `acc` map keyed by `[:type key]`. The first-seen
  gradient map + opacity are kept on the entry as `:gradient` /
  `:opacity` so the UI can seed the color picker for the group."
  [acc type shape-id [kind key hex extra]]
  (let [path [type key]]
    (if-let [existing (find acc path)]
      (let [v (val existing)]
        (assoc acc path
               (-> v
                   (update :count inc)
                   (update :shape-ids conj shape-id))))
      (assoc acc path
             {:color key
              :kind kind
              :hex (representative-hex kind hex)
              :type type
              :count 1
              :shape-ids [shape-id]
              :gradient (:gradient extra)
              :opacity (:opacity extra 1)}))))

(defn aggregate-colors
  "Pure aggregation over the fills + strokes of the selected shapes.
  `objects` is the page objects map (or any id->shape map); `ids` is the
  collection of selected shape ids. Returns a seq of aggregated color
  entries (see ns docstring). Shapes missing from `objects` and fills /
  strokes with no swappable color are skipped. Order is unspecified —
  callers sort via `sort-colors`."
  [objects ids]
  (let [acc
        (reduce
         (fn [acc id]
           (let [shape (get objects id)]
             (if (nil? shape)
               acc
               (let [fills   (or (:fills shape) [])
                     strokes (or (:strokes shape) [])
                     acc
                     (reduce (fn [acc fill]
                               (if-let [e (fill-color-entry fill)]
                                 (aggregate-one acc :fill id e)
                                 acc))
                             acc fills)]
                 (reduce (fn [acc stroke]
                           (if-let [e (stroke-color-entry stroke)]
                             (aggregate-one acc :stroke id e)
                             acc))
                         acc strokes)))))
         {}
         ids)]
    (vals acc)))

;; --- Sorting ---------------------------------------------------------------

(defn- color-sort-key
  "Comparable tuple for `:by-color` sorting: hue then lightness then hex.
  Greys / achromatic colors share hue 0; the hex tiebreaker keeps the
  order stable across equal hue+lightness."
  [entry]
  (let [hex (:hex entry)
        [hue _sat lum] (clr/hex->hsl hex)]
    [hue lum hex]))

(defn sort-colors
  "Sort an aggregated seq per `mode`. `:by-frequency` → count desc then
  color key (stable tiebreak). `:by-color` → hue asc then lightness asc
  then hex. Any other mode falls back to by-frequency."
  [mode entries]
  (case mode
    :by-color
    (sort-by color-sort-key entries)

    :by-frequency
    (sort-by (fn [e] [(- (:count e 0)) (:color e)]) entries)

    (sort-by (fn [e] [(- (:count e 0)) (:color e)]) entries)))

;; --- Replacement helpers ---------------------------------------------------

(defn- fill-matches?
  "True when `fill`'s aggregation key equals `key` (a solid or gradient
  key string)."
  [fill key]
  (let [[_ k] (fill-color-entry fill)]
    (= k key)))

(defn- stroke-matches?
  "True when `stroke`'s aggregation key equals `key`."
  [stroke key]
  (let [[_ k] (stroke-color-entry stroke)]
    (= k key)))

(defn- apply-new-color-to-fill
  "Return a new fill map with its color fields replaced by `new-color`
  (a picker color map: `{:color \"#hex\" :opacity 1}` or
  `{:gradient {...} :opacity 1}`). Library refs are dropped because the
  picker returns a plain color. Image / pattern fields are cleared so the
  fill remains a valid single-kind color."
  [fill new-color]
  (cond
    (some? (:gradient new-color))
    (-> fill
        (assoc :fill-color-gradient (:gradient new-color))
        (assoc :fill-opacity (:opacity new-color 1))
        (dissoc :fill-color :fill-image
                :fill-color-ref-id :fill-color-ref-file))

    (some? (:color new-color))
    (-> fill
        (assoc :fill-color (:color new-color))
        (assoc :fill-opacity (:opacity new-color 1))
        (dissoc :fill-color-gradient :fill-image
                :fill-color-ref-id :fill-color-ref-file))

    :else fill))

(defn- apply-new-color-to-stroke
  "Return a new stroke map with its color fields replaced by `new-color`."
  [stroke new-color]
  (cond
    (some? (:gradient new-color))
    (-> stroke
        (assoc :stroke-color-gradient (:gradient new-color))
        (assoc :stroke-opacity (:opacity new-color 1))
        (dissoc :stroke-color :stroke-image
                :stroke-color-ref-id :stroke-color-ref-file))

    (some? (:color new-color))
    (-> stroke
        (assoc :stroke-color (:color new-color))
        (assoc :stroke-opacity (:opacity new-color 1))
        (dissoc :stroke-color-gradient :stroke-image
                :stroke-color-ref-id :stroke-color-ref-file))

    :else stroke))

(defn- replace-in-shape
  "update-fn for pcb/update-shapes: rewrite every fill/stroke whose
  aggregation key equals `old-key` with `new-color`. Returns the shape
  unchanged (identical) when nothing matches so pcb skips it."
  [shape type old-key new-color]
  (case type
    :fill
    (let [fills (:fills shape)]
        (if (or (nil? fills) (empty? fills))
          shape
          (let [new-fills
                (reduce
                 (fn [acc fill]
                   (conj acc (if (fill-matches? fill old-key)
                               (apply-new-color-to-fill fill new-color)
                               fill)))
                 []
                 fills)]
            (if (identical? fills new-fills)
              shape
              (assoc shape :fills new-fills)))))

    :stroke
    (let [strokes (:strokes shape)]
        (if (or (nil? strokes) (empty? strokes))
          shape
          (let [new-strokes
                (reduce
                 (fn [acc stroke]
                   (conj acc (if (stroke-matches? stroke old-key)
                               (apply-new-color-to-stroke stroke new-color)
                               stroke)))
                 []
                 strokes)]
            (if (identical? strokes new-strokes)
              shape
              (assoc shape :strokes new-strokes)))))

    shape))

(defn- new-color-key
  "Compute the aggregation key for the replacement color so we can
  detect the byte-identical old==new no-op."
  [new-color]
  (cond
    (some? (:gradient new-color)) (gradient-key (:gradient new-color))
    (some? (:color new-color))    (solid-key (:color new-color))
    :else nil))

;; --- Event -----------------------------------------------------------------

(defn replace-color
  "Bulk-replace one aggregated color across every selected shape in a
  single undo transaction.

  Params:
    {:keys [shape-ids type old-key old-kind new-color]}
    shape-ids — seq of selected shape ids whose fills/strokes to rewrite
    type      — :fill or :stroke
    old-key   — aggregation key string of the color being replaced
    old-kind  — :solid or :gradient (the kind of old-key, for skip logic)
    new-color — picker color map to write in place of every match

  Nil-safe: returns rx/empty when there are no shapes, when old-key
  equals the new color's key (byte-identical no-op), or when new-color
  carries neither :color nor :gradient. Otherwise builds one changeset
  via pcb/empty-changes + pcb/update-shapes + dch/commit-changes wrapped
  in a single undo transaction (dwu/start-undo-transaction /
  dwu/commit-undo-transaction), exactly like data/workspace/notes.cljs."
  [{:keys [shape-ids type old-key old-kind new-color]}]
  (ptk/reify ::replace-color
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id    (:current-page-id state)
            file-id    (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
            page      (when page-id (dsh/lookup-page state page-id))
            ids       (into [] (filter some?) shape-ids)
            new-key   (new-color-key new-color)]
        (if (or (nil? page) (empty? ids)
                (nil? old-key)
                (nil? new-key)
                ;; byte-identical no-op: same kind + same key
                (and (= old-kind
                        (if (some? (:gradient new-color)) :gradient :solid))
                     (= old-key new-key)))
          (rx/empty)
          (let [undo-id  (js/Symbol)
                update-fn (fn [shape]
                            (replace-in-shape shape type old-key new-color))
                changes
                (-> (pcb/empty-changes it page-id)
                    (pcb/with-file-data file-data)
                    (pcb/with-page page)
                    (pcb/set-save-undo? true)
                    (pcb/update-shapes ids update-fn
                                       {:attrs (case type
                                                 :fill   #{:fills}
                                                 :stroke #{:strokes}
                                                 nil)}))]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (dwu/commit-undo-transaction undo-id))))))))