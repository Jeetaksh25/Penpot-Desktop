;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.data-binding
  "Data-bound data sets (ALL_APPS_PARITY P1.12 / P2.06).

  P2.06 (CSV import) — `parse-csv` is a pure CLJS RFC-4180-ish CSV parser
  (quoted fields with embedded commas, double-quote escapes, and embedded
  newlines / CRLF / LF line endings) returning `{:headers [...] :rows [[...]]}`.
  `import-csv` parses a CSV string and persists the result as a NAMED data
  set on FILE-DATA plugin-data (namespace `:ovion`, key `\"data-sets\"`) as
  a pr-str of `{<name> {:headers [...] :rows [[...]]}}`. One undo transaction.

  P1.12 (data-bound repeaters) — repeaters (see data/workspace/repeaters.cljs)
  consume a named data set here to render repeated instances.

  Storage:
    file-data :plugin-data :ovion \"data-sets\" -> EDN string of
      `{<name> {:headers [col...] :rows [[v...]...]}}`.

  Plugin-data persistence mirrors data/workspace/ai_checklist.cljs (3-arg
  `pcb/set-plugin-data`, one undo transaction, object-type :file). The slot
  is absent on a fresh file → `read-data-sets` returns {} → byte-identical.
  All readers are nil-safe (absent / unparsable slot = no data)."

  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def data-sets-namespace
  "Plugin-data namespace keyword under which named data sets are stored
  on the file. Schema:plugin-data key namespaces are keywords."
  :ovion)

(def data-sets-key
  "Plugin-data key (string) under `data-sets-namespace` for the data-sets
  map `{<name> {:headers :rows}}`."
  "data-sets")

;; --- CSV parser -------------------------------------------------------------

(defn- char-at
  "Safe char-at. Returns nil past end / on nil input."
  [s i]
  (when (and (string? s) (int? i) (>= i 0) (< i (alength s)))
    (aget s i)))

(defn- append-field
  "Append the parsed field to the current row accumulator."
  [row field]
  (conj row field))

(defn- append-row
  "Append the completed row to the rows accumulator and start a new row."
  [rows row]
  (conj rows row))

(defn parse-csv
  "Pure CLJS CSV parser. Parses `string` into `{:headers [...] :rows [[...]]}`.

  Handles the RFC-4180 edge cases that matter for user-imported CSV:
    * quoted fields (`\"a,b\"` -> one field `a,b`)
    * doubled double-quotes inside a quoted field (`\"a\"\"b\"` -> `a\"b`)
    * embedded newlines inside a quoted field
    * CRLF (`\\r\\n`) and LF (`\\n`) line endings (a lone CR is treated as a
      line break too, matching spreadsheet exports)
    * a trailing newline does NOT produce a spurious empty final row
    * an empty input string yields `{:headers [] :rows []}` (no crash)

  The first record becomes `:headers`; the rest become `:rows`. When the
  input has zero records both are empty vectors; when it has only a header
  row, `:rows` is an empty vector. Field values are always strings; missing
  trailing fields on a short row are NOT padded (a row may be shorter than
  headers — repeater column lookup must nil-safe this)."
  [string]
  (let [s (or string "")]
    (if (str/empty? s)
      {:headers [] :rows []}
      (let [n (alength s)]
        ;; Single pass state machine. State keys:
        ;;   :i       current index
        ;;   :field   current field buffer (string)
        ;;   :row     current row accumulator (vector of fields)
        ;;   :rows    completed rows (vector of vectors)
        ;;   :quoted? inside a quoted field?
        ;;   :just-opened? the previous char was the opening quote (so an
        ;;                 immediately-following quote is the empty quoted
        ;;                 field, not an escape) — handled via :field-started?
        ;;   :field-started? a non-empty field or a quote has begun
        (loop [i 0
               field (str "")
               row []
               rows []
               quoted? false
               field-started? false]
          (if (>= i n)
            ;; End of input. Flush the last field/row (unless the input ended
            ;; right after a row terminator, in which case row is [] and field
            ;; is "" and we must NOT emit a phantom empty row).
            (let [final-rows (if (and (str/empty? field) (empty? row) (not quoted?))
                               rows
                               (append-row rows (append-field row field)))]
              (if (empty? final-rows)
                {:headers [] :rows []}
                {:headers (first final-rows)
                 :rows (vec (rest final-rows))}))
            (let [c (aget s i)]
              (cond
                ;; Inside a quoted field.
                quoted?
                (if (= c "\"")
                  ;; Peek next char: doubled quote = literal quote, otherwise
                  ;; the closing quote.
                  (let [next-i (inc i)]
                    (if (= (char-at s next-i) "\"")
                      (recur (inc next-i)
                             (str field "\"")
                             row rows true true)
                      ;; Closing quote — end quoted mode. Stay on the position
                      ;; AFTER the closing quote; the next char (comma / newline
                      ;; / EOF) will be handled by the next iteration.
                      (recur next-i field row rows false true)))
                  ;; Ordinary char inside quotes (including CR / LF).
                  (recur (inc i) (str field c) row rows true true))

                ;; Not inside quotes.
                (and (= c "\"") (not field-started?))
                ;; Opening quote of a quoted field.
                (recur (inc i) field row rows true true)

                (= c ",")
                ;; Field terminator.
                (recur (inc i) (str "") (append-field row field) rows false false)

                (or (= c "\n") (= c "\r"))
                ;; Row terminator. Collapse a CRLF pair into one break.
                (let [i' (if (and (= c "\r") (= (char-at s (inc i)) "\n"))
                           (inc (inc i))
                           (inc i))]
                  (recur i' (str "") [] (append-row rows (append-field row field)) false false))

                ;; Ordinary char.
                :else
                (recur (inc i) (str field c) row rows false true)))))))))

;; --- Read helpers -----------------------------------------------------------

(defn read-data-sets
  "Parse the file-data data-sets slot back into a map of
  `{<name> {:headers [...] :rows [[...]]}}`. Returns {} when the slot is
  absent / unparsable. `file-data` is the file-data map (NOT the whole
  state). Nil-safe: nil input -> {}."
  [file-data]
  (let [raw (dm/get-in file-data [:plugin-data data-sets-namespace data-sets-key])]
    (if (or (nil? raw) (empty? raw))
      {}
      (try
        (let [v (reader/read-string raw)]
          (if (map? v) v {}))
        (catch :default _
          {})))))

(defn read-data-set
  "Return the named data set `{:headers :rows}` or nil. `file-data` is the
  file-data map. Nil-safe."
  [file-data name]
  (let [sets  (read-data-sets file-data)
        name' (if (keyword? name) name (str name))]
    (get sets name')))

(defn read-data-set-names
  "Return a sorted vector of data-set names (as strings). Nil-safe."
  [file-data]
  (let [sets (read-data-sets file-data)]
    (->> (keys sets)
         (map (fn [k] (if (keyword? k) (d/name k) (str k))))
         sort
         vec)))

;; --- Commit helper (pure; builds against the passed state) ------------------

(defn- commit-data-sets
  "Build and commit a changeset that writes `new-sets` to the file's
  data-sets slot, inside one undo transaction. nil/empty `new-sets` clears
  the slot. Returns an rx stream of potok events. Mirrors
  ai_checklist.cljs's `commit-checklist`."
  [it state new-sets]
  (let [file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (nil? file-data)
      (rx/empty)
      (let [undo-id (js/Symbol)
            value   (if (or (nil? new-sets) (empty? new-sets))
                      nil
                      (pr-str new-sets))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/set-plugin-data data-sets-namespace
                                              data-sets-key
                                              value))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events -----------------------------------------------------------------

(defn import-csv
  "Parse `csv-string` and persist it as a named data set `name` on the
  file's data-sets slot. One undo transaction. If `name` already exists it
  is overwritten. Empty / nil CSV yields a no-op (does not erase other
  data sets). The data set stored is `{:headers [...] :rows [[...]]}` plus
  a `:name` echo for UI convenience."
  [{:keys [name csv-string]}]
  (ptk/reify ::import-csv
    ptk/WatchEvent
    (watch [it state _]
      (let [parsed (parse-csv csv-string)]
        (if (empty? (:headers parsed))
          ;; Nothing to import — no-op (do not touch the slot).
          (rx/empty)
          (let [file-id   (:current-file-id state)
                file-data (dsh/lookup-file-data state file-id)
                sets      (read-data-sets file-data)
                name'     (if (keyword? name) (d/name name) (str name))
                ;; Store under a keyword name for stable EDN round-tripping.
                key       (keyword name')
                new-sets  (assoc sets key
                                 {:name    name'
                                  :headers (vec (:headers parsed))
                                  :rows    (vec (:rows parsed))})]
            (commit-data-sets it state new-sets)))))))

(defn delete-data-set
  "Remove the named data set from the file's data-sets slot. One undo.
  No-op when the name is absent."
  [{:keys [name]}]
  (ptk/reify ::delete-data-set
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id   (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
        sets      (read-data-sets file-data)
        name'     (if (keyword? name) name (keyword (str name)))
        new-sets  (if (contains? sets name')
                    (dissoc sets name')
                    sets)]
        (if (identical? new-sets sets)
          (rx/empty)
          (commit-data-sets it state new-sets))))))

(defn rename-data-set
  "Rename a data set from `old-name` to `new-name`. One undo. No-op when
  `old-name` is absent or `new-name` already exists / is blank."
  [{:keys [old-name new-name]}]
  (ptk/reify ::rename-data-set
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id   (:current-file-id state)
            file-data (dsh/lookup-file-data state file-id)
        sets      (read-data-sets file-data)
        old-key   (if (keyword? old-name) old-name (keyword (str old-name)))
        new-key   (if (keyword? new-name) new-name (keyword (str new-name)))
        new-name' (if (keyword? new-name) (d/name new-name) (str new-name))
        dataset   (get sets old-key)]
        (cond
          (nil? dataset)            (rx/empty) ; absent
          (str/empty? new-name')    (rx/empty) ; blank target
          (contains? sets new-key)  (rx/empty) ; target exists
          :else
          (let [new-sets (-> sets
                             (dissoc old-key)
                             (assoc new-key (assoc dataset :name new-name')))]
            (commit-data-sets it state new-sets)))))))

(defn clear-data-sets
  "Remove the entire data-sets slot from the file. One undo. Mainly for
  tests / a 'clear all' affordance."
  [_]
  (ptk/reify ::clear-data-sets
    ptk/WatchEvent
    (watch [it state _]
      (commit-data-sets it state nil))))