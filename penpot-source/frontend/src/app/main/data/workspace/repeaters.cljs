;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.repeaters
  "Data-bound Repeaters (ALL_APPS_PARITY P1.12).

  A repeater binds a template shape (a text shape, or a frame/group whose
  text children carry field tags) to a named data set (see
  data/workspace/data_binding.cljs). `apply-repeater` duplicates the
  template once per data row and lays the copies out in a positional grid
  (rows x cols, computed from the row count), then fills each copy's
  bound text fields from the corresponding row's columns.

  Storage (all shape-level plugin-data, namespace `:ovion`):
    `\"repeater\"`          -> pr-str `{:data-set <name>
                                         :fields {<field-key> <column>}
                                         :gap-w <px> :gap-h <px>}`
    `\"repeater-children\"` -> pr-str `[<generated-child-id>...]`
    `\"field\"`             -> pr-str `<field-key>` (on each TEMPLATE text
                                  child that should be data-bound)

  Mechanism (mirrors data/workspace/repeat_grid.cljs):
    * Reuses `cll/generate-duplicate-changes` (+ `...-update-indices`) so
      component / variant / flow / library machinery is preserved.
    * One duplicate batch per data row (the template stays as cell (0,0)
      so the original is recoverable; the generated children are the rows
      1..N-1 — row 0 reuses the template itself, its bound fields are
      filled in place). `objects` is evolved across batches so index
      fixing sees the prior copies.
    * Each batch's translation is baked via `pcb/update-shapes` +
      `gsh/transform-shape` with `ctm/move-modifiers` (the documented
      bake pattern). The pitch is the template's bounding-rect size plus
      an optional gap (`:gap-w` / `:gap-h`, default 0 = touching copies).
    * After each batch, the copy's bound text fields are filled: the
      batch's freshly-added shapes are walked for text shapes carrying
      `:ovion \"field\"`; the field-key is looked up in `:fields`; the
      column index is resolved from the data set's `:headers`; the text
      content is rebuilt via `txt/change-text` and committed with
      `pcb/update-shapes` (same changeset).
    * All batch changesets concatenate into ONE `dch/commit-changes`
      wrapped in `dwu/start/commit-undo-transaction` -> single undo step.
      The generated child ids are recorded on the template shape's
      `:ovion \"repeater-children\"` slot (one extra changeset in the same
      transaction) so `clear-repeater` can remove them.
    * The template's own bound fields (row 0) are filled in the same
      transaction via a final `update-shapes` on the template id.

  Nil-safe: no data set / no slot / no rows / template gone = no-op. With
  no repeater slot the shape renders normally (byte-identical)."

  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.types.modifiers :as ctm]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.data-binding :as dwdb]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def ovion-namespace :ovion)
(def repeater-key "repeater")
(def repeater-children-key "repeater-children")
(def field-key "field")

;; --- Read helpers -----------------------------------------------------------

(defn read-repeater
  "Parse a shape's repeater slot back into a config map
  `{:data-set :fields :gap-w :gap-h}`. Accepts a shape map (reads
  :plugin-data) or a raw stored string. Returns nil when absent or
  unparsable (nil = no repeater -> shape renders normally)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace repeater-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (reader/read-string raw)
         (catch :default _ nil))))))

(defn read-repeater-children
  "Parse a shape's repeater-children slot back into a vector of child ids,
  or nil. Accepts a shape map or a raw stored string."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace repeater-children-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (let [v (reader/read-string raw)]
           (if (vector? v) v nil))
         (catch :default _ nil))))))

(defn read-field-tag
  "Parse a text shape's field-tag slot back into a field key (keyword or
  string), or nil. Accepts a shape map or a raw stored string."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace field-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (reader/read-string raw)
         (catch :default _ nil))))))

;; --- Field-binding helper --------------------------------------------------

(defn- column-index
  "Resolve a column spec (`<column-name>` string/keyword or `<index>` int)
  to a row index using `headers`. Returns nil when unresolved."
  [headers column]
  (cond
    (int? column)        column
    (string? column)     (let [i (.indexOf headers column)]
                           (when (>= i 0) i))
    (keyword? column)    (let [nm (d/name column)
                               i (.indexOf headers nm)]
                           (when (>= i 0) i))
    :else                nil))

(defn- row-value
  "Return the string value at `column` for `row`, or the empty string when
  the column / index is out of range. Coerces to string."
  [headers row column]
  (let [idx (column-index headers column)]
    (if (or (nil? idx) (nil? row))
      ""
      (let [v (get row idx)]
        (if (nil? v) "" (str v))))))

(defn- text-shape-with-field?
  "True when `shape` is a text shape carrying an `:ovion \"field\"` tag."
  [shape]
  (and (cfh/text-shape? shape)
       (some? (read-field-tag shape))))

(defn- bind-text-content
  "Given the template's repeater config `cfg`, the data set, and a map of
  shapes (the copy's subtree keyed by id), return a map
  `{<text-shape-id> <new-content>}` for every text shape in `shapes` that
  carries a field tag present in `cfg`'s :fields. `row` is the data row."
  [cfg dataset shapes row]
  (let [headers (:headers dataset)
        fields  (:fields cfg)]
    (if (or (nil? fields) (empty? fields) (nil? headers))
      {}
      (into {}
            (keep (fn [[id shape]]
                    (when (text-shape-with-field? shape)
                      (let [field-key (read-field-tag shape)
                            column    (get fields field-key)
                            text      (row-value headers row column)
                            content   (:content shape)
                            new-c     (when (some? content)
                                        (txt/change-text content text))]
                        (when (some? new-c)
                          [id new-c])))))
            shapes))))

;; --- Grid layout helpers ---------------------------------------------------

(defn- grid-dims
  "Compute `[rows cols]` for `n` cells. cols = ceil(sqrt(n)), rows = ceil(n
  / cols). Always at least 1x1."
  [n]
  (let [n (max 1 (int n))
        cols (max 1 (int (.ceil js/Math (.sqrt js/Math n))))
        rows (max 1 (int (.ceil js/Math (/ n cols))))]
    [rows cols]))

(defn- cell-position
  "Return `[dx dy]` for cell index `idx` (0-based) given `cols` and the
  cell pitch `[pitch-w pitch-h]`. Cell 0 is (0,0)."
  [idx cols pitch-w pitch-h]
  (let [row (int (/ idx cols))
        col (mod idx cols)]
    [(* col pitch-w) (* row pitch-h)]))

;; --- Commit helper ---------------------------------------------------------

(defn- commit-plugin-data
  "Build and commit a changeset that writes `value` (pr-str'd; nil/empty
  clears) to shape `shape-id`'s plugin-data slot `key-str` under
  `:ovion`. One undo transaction. Mirrors motion_effects.cljs's
  `commit-plugin-data`."
  [it state shape-id key-str value]
  (let [page-id   (:current-page-id state)
        page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (or (nil? page) (nil? shape-id))
      (rx/empty)
      (let [v       (if (or (nil? value) (and (coll? value) (empty? value)))
                      nil
                      (pr-str value))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/with-page page)
                        (pcb/set-plugin-data :shape
                                             shape-id
                                             page-id
                                             ovion-namespace
                                             key-str
                                             v))]
        (rx/of changes)))))

;; --- Events ----------------------------------------------------------------

(defn apply-repeater
  "Apply the repeater bound to `shape-id`: read the shape's `:ovion
  \"repeater\"` slot, look up the bound data set, and for each row create
  a duplicate of the template laid out in a positional grid, with each
  duplicate's bound text fields filled from the row. Row 0 is the template
  itself (filled in place); rows 1..N-1 are duplicates. One undo batch.
  Nil-safe (no slot / no data set / no rows / template gone = no-op)."
  [{:keys [shape-id]}]
  (ptk/reify ::apply-repeater
    ptk/WatchEvent
    (watch [it state _]
      (let [page      (dsh/lookup-page state)
            objects   (:objects page)
            shape     (get objects shape-id)
            cfg       (read-repeater shape)]
        (cond
          (nil? shape)  (rx/empty)   ; template gone
          (nil? cfg)    (rx/empty)   ; no repeater slot
          :else
          (let [file-id     (:current-file-id state)
                file-data   (dsh/lookup-file-data state file-id)
                dataset     (dwdb/read-data-set file-data (:data-set cfg))
                rows        (:rows dataset)]
            (cond
              (nil? dataset)  (rx/empty) ; no data set
              (empty? rows)   (rx/empty) ; no rows
              :else
              (let [libraries    (dsh/lookup-libraries state)
                    library-data (dsh/lookup-file-data state file-id)
                    ids          [shape-id]
                    delta        (gpt/point 0 0)

                    rect         (or (gsh/shapes->rect [shape])
                                     (grc/make-rect 0 0 0 0))
                    rect-w       (:width  rect 0)
                    rect-h       (:height rect 0)
                    gap-w        (or (:gap-w cfg) 0)
                    gap-h        (or (:gap-h cfg) 0)
                    pitch-w      (+ rect-w gap-w)
                    pitch-h      (+ rect-h gap-h)

                    n            (count rows)
                    [_ cols]     (grid-dims n)

                    ;; Cells 1..n-1 are duplicates; cell 0 is the template.
                    cell-idxs    (range 1 n)

                    batches
                    (reduce
                     (fn [{:keys [objects changes all-child-ids]} idx]
                       (let [[dx dy] (cell-position idx cols pitch-w pitch-h)
                             batch-ch    (-> (pcb/empty-changes it)
                                             (cll/generate-duplicate-changes
                                              objects page ids delta
                                              libraries library-data file-id {})
                                             (cll/generate-duplicate-changes-update-indices
                                              objects ids))
                             add-entries (filter #(= (:type %) :add-obj)
                                                 (:redo-changes batch-ch))
                             new-objs    (into {}
                                          (keep #(let [o (:obj %)]
                                                   (when (some? o) [(:id o) o])))
                                          add-entries)
                             new-ids     (into #{} (keys new-objs))
                             objects'    (into objects new-objs)
                             ;; Bake the translation into each new shape.
                             batch-ch    (-> batch-ch
                                             (pcb/with-objects objects')
                                             (pcb/update-shapes
                                              new-ids
                                              (fn [s]
                                                (gsh/transform-shape
                                                 s
                                                 (ctm/move-modifiers dx dy)))))
                             ;; Bind the copy's text fields from this row.
                             row         (get rows idx)
                             bindings    (bind-text-content cfg dataset new-objs row)
                             batch-ch    (if (empty? bindings)
                                           batch-ch
                                           (-> batch-ch
                                               (pcb/update-shapes
                                                (into #{} (keys bindings))
                                                (fn [s]
                                                  (assoc s :content
                                                         (get bindings (:id s)))))))]
                         {:objects       objects'
                          :changes       (pcb/concat-changes changes batch-ch)
                          :all-child-ids (into all-child-ids new-ids)}))
                     {:objects       objects
                      :changes       (pcb/empty-changes it)
                      :all-child-ids #{}}
                     cell-idxs)

                    changes       (:changes batches)
                    all-child-ids (:all-child-ids batches)

                    ;; Fill the template's own bound fields (row 0).
                    row0          (get rows 0)
                    tmpl-bindings (bind-text-content cfg dataset
                                                      {shape-id shape} row0)
                    changes       (if (empty? tmpl-bindings)
                                    changes
                                    (-> changes
                                        (pcb/with-objects objects)
                                        (pcb/update-shapes
                                         #{shape-id}
                                         (fn [s]
                                           (assoc s :content
                                                  (get tmpl-bindings (:id s)))))))

                    undo-id       (js/Symbol)]
                (rx/concat
                 (rx/of (dwu/start-undo-transaction undo-id))
                 (rx/of (dch/commit-changes changes))
                 ;; Record the generated child ids on the template so
                 ;; clear-repeater can remove them. Separate changeset in
                 ;; the SAME undo transaction.
                 (if (seq all-child-ids)
                   (let [page-id    (:current-page-id state)
                         page       (dsh/lookup-page state)
                         file-id2   (:current-file-id state)
                         file-data2 (dsh/lookup-file-data state file-id2)
                         child-ch   (-> (pcb/empty-changes it)
                                        (pcb/with-file-data file-data2)
                                        (pcb/with-page page)
                                        (pcb/set-plugin-data
                                         :shape shape-id page-id
                                         ovion-namespace repeater-children-key
                                         (pr-str (vec all-child-ids))))]
                     (rx/of (dch/commit-changes child-ch)
                            (dwu/commit-undo-transaction undo-id)))
                   (rx/of (dwu/commit-undo-transaction undo-id))))))))))))

(defn clear-repeater
  "Remove the repeater slot from `shape-id` AND delete the generated child
  shapes recorded in `:ovion \"repeater-children\"`. One undo batch. No-op
  when there is no slot / no children."
  [{:keys [shape-id]}]
  (ptk/reify ::clear-repeater
    ptk/WatchEvent
    (watch [it state _]
      (let [page      (dsh/lookup-page state)
            objects   (:objects page)
            shape     (get objects shape-id)
            cfg       (read-repeater shape)
            child-ids (read-repeater-children shape)]
        (cond
          (nil? shape)   (rx/empty)
          (and (nil? cfg) (or (nil? child-ids) (empty? child-ids)))
          (rx/empty)
          :else
          (let [page-id    (:current-page-id state)
                file-id    (:current-file-id state)
                file-data  (dsh/lookup-file-data state file-id)
                undo-id    (js/Symbol)

                ;; 1) Delete the generated children (if any). Reuse
                ;; pcb/remove-objects (handles del-obj + undo add-obj +
                ;; parent reindexing in one call).
                live-children (into [] (filter #(contains? objects %)) child-ids)
                del-ch
                (if (empty? live-children)
                  (pcb/empty-changes it)
                  (-> (pcb/empty-changes it)
                      (pcb/with-file-data file-data)
                      (pcb/with-page page)
                      (pcb/with-objects objects)
                      (pcb/remove-objects live-children)))

                ;; 2) Clear both slots on the template.
                clr-ch (-> (pcb/empty-changes it)
                           (pcb/with-file-data file-data)
                           (pcb/with-page page)
                           (pcb/set-plugin-data :shape shape-id page-id
                                                ovion-namespace
                                                repeater-key nil)
                           (pcb/set-plugin-data :shape shape-id page-id
                                                ovion-namespace
                                                repeater-children-key nil))]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes (pcb/concat-changes del-ch clr-ch))
                   (dwu/commit-undo-transaction undo-id))))))))

(defn set-repeater
  "Set the repeater config on shape `shape-id`. `cfg` is
  `{:data-set :fields :gap-w :gap-h}` or nil to clear the slot only (does
  NOT delete children — use clear-repeater for that). One undo."
  [{:keys [shape-id cfg]}]
  (ptk/reify ::set-repeater
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id    (:current-page-id state)
            page       (dsh/lookup-page state)
            file-id    (:current-file-id state)
            file-data  (dsh/lookup-file-data state file-id)]
        (if (or (nil? page) (nil? shape-id))
          (rx/empty)
          (let [v       (when (some? cfg) (pr-str cfg))
                changes (-> (pcb/empty-changes it)
                            (pcb/with-file-data file-data)
                            (pcb/with-page page)
                            (pcb/set-plugin-data :shape shape-id page-id
                                                 ovion-namespace
                                                 repeater-key v))
                undo-id (js/Symbol)]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (dwu/commit-undo-transaction undo-id))))))))

(defn set-field-tag
  "Tag a text shape with a field key so a repeater can bind it. `field-key`
  is a keyword/string, or nil to clear. One undo."
  [{:keys [shape-id field-key]}]
  (ptk/reify ::set-field-tag
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            page      (dsh/lookup-page state)
            file-id   (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)]
        (if (or (nil? page) (nil? shape-id))
          (rx/empty)
          (let [v       (when (some? field-key) (pr-str field-key))
                changes (-> (pcb/empty-changes it)
                            (pcb/with-file-data file-data)
                            (pcb/with-page page)
                            (pcb/set-plugin-data :shape shape-id page-id
                                                 ovion-namespace
                                                 field-key v))
                undo-id (js/Symbol)]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (dwu/commit-undo-transaction undo-id))))))))