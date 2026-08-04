;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.vector-sets
  "P2.38 Vector Sets + stroke-flow animation.

  A **Vector Set** is a curated, named stroke-style preset (Solid, Dashed,
  Dotted, Double, Inner Glow, Outer Glow, Sketchy, Neon) applied to a
  shape's first stroke in one undo. The selected set id is recorded on the
  shape as plugin-data `:ovion \"vector-set\"` so the sidebar can highlight
  the active set. The set spec is a small map of stroke attrs (style, dash,
  gap, caps, join, alignment) plus an optional `:width-multiplier`; an
  optional `:second-stroke` map is part of the schema (reserved for a
  future second-stroke set — no v1 set uses it). Applying a set is PURELY
  ADDITIVE: it only writes stroke attrs the set names, so a shape with no
  set applied renders byte-identically to today.

  **Stroke-flow animation** is a motion slot that animates `stroke-dashoffset`
  along the shape's stroked paths (marching-ants / flow). The config
  `{:speed ms-per-cycle :direction :forward/:reverse}` is stored as plugin-data
  `:ovion \"stroke-anim\"` (pr-str). The viewer runtime (viewer/shapes.cljs)
  reads it and calls `app.main.ui.workspace.ai-motion/run-stroke-flow-effect`
  on the shape's DOM node — GSAP tweens `strokeDashoffset` on every stroked
  descendant. Under `prefers-reduced-motion` the offset is forced to 0 (a
  static dash) and no tween runs. With no slot, the viewer effect is a no-op
  → byte-identical render.

  Read helpers accept a shape map (read :plugin-data) or a raw stored
  string, and return nil when absent/unparsable (nil = inactive)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def ovion-namespace :ovion)
(def vector-set-key "vector-set")
(def stroke-anim-key "stroke-anim")

(def ^:private valid-directions #{:forward :reverse})

;; --- Vector-set catalog ----------------------------------------------------
;;
;; Each set is a map:
;;   {:id <kw> :label-key <i18n msgid>
;;    :stroke <map of stroke attrs to merge into the first stroke>
;;    :width-multiplier <num> optional — multiply the current stroke width
;;    :second-stroke <map> optional — reserved for a 2nd-stroke set (v1: none)}
;;
;; The :stroke map only carries attrs the set explicitly sets; the user's
;; stroke color / opacity are NEVER touched (the set is a style preset, not
;; a color preset). :stroke-style :solid clears the dasharray at render
;; time (see app.main.ui.shapes.attrs/add-stroke! — :svg is the only style
;; that skips the dasharray, :solid yields an empty dasharray string).

(def vector-set-catalog
  [{:id :solid
    :label-key "workspace.options.vector-sets.solid"
    :stroke {:stroke-style :solid
             :stroke-cap-start :line
             :stroke-cap-end :line
             :stroke-join :miter}}

   {:id :dashed
    :label-key "workspace.options.vector-sets.dashed"
    :stroke {:stroke-style :dashed
             :stroke-dash 12
             :stroke-gap 8
             :stroke-cap-start :line
             :stroke-cap-end :line
             :stroke-join :miter}}

   {:id :dotted
    :label-key "workspace.options.vector-sets.dotted"
    :stroke {:stroke-style :dotted
             :stroke-cap-start :round
             :stroke-cap-end :round
             :stroke-join :round}}

   {:id :double
    :label-key "workspace.options.vector-sets.double"
    ;; :mixed renders as dash-gap-dot-gap (attrs.cljs calculate-dasharray),
    ;; which reads as a double-line / dash-dot pattern.
    :stroke {:stroke-style :mixed
             :stroke-dash 10
             :stroke-gap 6
             :stroke-cap-start :line
             :stroke-cap-end :line
             :stroke-join :miter}}

   {:id :inner-glow
    :label-key "workspace.options.vector-sets.inner-glow"
    :stroke {:stroke-style :dotted
             :stroke-alignment :inner
             :stroke-cap-start :round
             :stroke-cap-end :round
             :stroke-join :round}
    :width-multiplier 2}

   {:id :outer-glow
    :label-key "workspace.options.vector-sets.outer-glow"
    :stroke {:stroke-style :dotted
             :stroke-alignment :outer
             :stroke-cap-start :round
             :stroke-cap-end :round
             :stroke-join :round}
    :width-multiplier 2}

   {:id :sketchy
    :label-key "workspace.options.vector-sets.sketchy"
    :stroke {:stroke-style :dashed
             :stroke-dash 3
             :stroke-gap 4
             :stroke-cap-start :round
             :stroke-cap-end :round
             :stroke-join :round}}

   {:id :neon
    :label-key "workspace.options.vector-sets.neon"
    :stroke {:stroke-style :solid
             :stroke-cap-start :round
             :stroke-cap-end :round
             :stroke-join :round}
    :width-multiplier 1.5}])

(defn get-vector-set
  "Return the catalog entry for `set-id` (keyword), or nil if unknown."
  [set-id]
  (d/seek #(= (:id %) set-id) vector-set-catalog))

(defn vector-set-ids
  "Return the ordered seq of valid set-id keywords (for UI iteration)."
  []
  (map :id vector-set-catalog))

;; --- Read helpers ----------------------------------------------------------

(defn read-vector-set
  "Parse a shape's vector-set slot back into a set-id keyword. Accepts a
  shape map (reads :plugin-data) or a raw stored string. Returns nil when
  absent, unparsable, or not a known set id (nil = no set applied)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace vector-set-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (let [parsed (reader/read-string raw)]
           (when (keyword? parsed) parsed))
         (catch :default _ nil))))))

(defn read-stroke-anim
  "Parse a shape's stroke-anim slot back into a config map
  `{:speed ms :direction :forward/:reverse}`. Accepts a shape map (reads
  :plugin-data) or a raw stored string. Returns nil when absent, unparsable,
  or malformed (nil = no animation — the viewer renders the stroke
  statically, byte-identical to today)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace stroke-anim-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (let [parsed (reader/read-string raw)]
           (when (map? parsed)
             (let [direction (:direction parsed)]
               (cond-> parsed
                 (not (contains? valid-directions direction))
                 (assoc :direction :forward)
                 (nil? (:speed parsed))
                 (assoc :speed 2000)))))
         (catch :default _ nil))))))

;; --- Internal: stroke update from a set spec --------------------------------

(defn- apply-set-to-stroke
  "Merge the vector-set's :stroke spec into a single stroke map, applying
  the set's :width-multiplier to the stroke's existing :stroke-width.
  Nil-safe: a nil stroke yields nil. The user's color/opacity are preserved
  (the set never names them)."
  [stroke set-spec]
  (when (some? stroke)
    (let [base-w    (or (:stroke-width stroke 1) 1)
          mult      (or (:width-multiplier set-spec) 1)
          new-w     (if (= mult 1) base-w (* base-w mult))
          specs     (:stroke set-spec)
          ;; drop nil-valued spec keys so we never overwrite with nil.
          specs     (into {} (remove (fn [[_ v]] (nil? v))) specs)]
      (-> stroke
          (merge specs)
          (assoc :stroke-width new-w)))))

(defn- apply-set-to-strokes
  "Apply `set-spec` to the first stroke of `strokes`. Returns strokes
  unchanged if empty. Nil-safe."
  [strokes set-spec]
  (if (or (nil? strokes) (empty? strokes))
    strokes
    (assoc strokes 0 (apply-set-to-stroke (first strokes) set-spec))))

;; --- Internal: single-change commit (one undo) -----------------------------

(defn- commit-vector-set
  "Build a single changes object that both updates the shape's first stroke
  AND records the set id on plugin-data, then commit under one undo
  transaction. `set-id` nil clears the plugin-data slot (stroke untouched)."
  [it state shape-id set-id]
  (let [page-id   (:current-page-id state)
        page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (or (nil? page) (nil? shape-id))
      (rx/empty)
      (let [undo-id (js/Symbol)
            set-spec (when (some? set-id) (get-vector-set set-id))
            changes  (cond-> (-> (pcb/empty-changes it)
                                 (pcb/with-file-data file-data)
                                 (pcb/with-page page))
                       (some? set-spec)
                       (pcb/update-shapes [shape-id]
                         (fn [shape]
                           (let [strokes (get shape :strokes [])]
                             (assoc shape :strokes
                                    (apply-set-to-strokes strokes set-spec))))
                         {:attrs [:strokes]})
                       :always
                       (pcb/set-plugin-data :shape shape-id page-id
                                            ovion-namespace vector-set-key
                                            (if (nil? set-id) nil (pr-str set-id))))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

(defn- commit-stroke-anim
  "Commit a stroke-anim config (or clear) on shape `shape-id` under one
  undo transaction. Mirrors motion-effects commit-plugin-data."
  [it state shape-id value]
  (let [page-id   (:current-page-id state)
        page      (dsh/lookup-page state)
        file-id   (:current-file-id state)
        file-data (dsh/lookup-file-data state file-id)]
    (if (or (nil? page) (nil? shape-id))
      (rx/empty)
      (let [undo-id (js/Symbol)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-file-data file-data)
                        (pcb/with-page page)
                        (pcb/set-plugin-data :shape
                                             shape-id
                                             page-id
                                             ovion-namespace
                                             stroke-anim-key
                                             (if (nil? value) nil (pr-str value))))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn apply-vector-set
  "Apply vector set `set-id` to shape `shape-id`'s first stroke (one undo).
  Records the set id on plugin-data `:ovion \"vector-set\"`. Unknown set id
  is a no-op. The shape's stroke color/opacity are preserved; only the
  style/dash/caps/join/alignment (and an optional width multiplier) are
  written."
  [{:keys [shape-id set-id]}]
  (ptk/reify ::apply-vector-set
    ptk/WatchEvent
    (watch [it state _]
      (if (nil? (get-vector-set set-id))
        (rx/empty)
        (commit-vector-set it state shape-id set-id)))))

(defn clear-vector-set
  "Remove the vector-set plugin-data slot from shape `shape-id` (one undo).
  The stroke itself is left as the user last configured it; only the
  recorded set id is cleared (so the sidebar no longer highlights a set)."
  [{:keys [shape-id]}]
  (ptk/reify ::clear-vector-set
    ptk/WatchEvent
    (watch [it state _]
      (commit-vector-set it state shape-id nil))))

(defn set-stroke-anim
  "Set the stroke-flow animation config on shape `shape-id`. `config` is
  `{:speed ms-per-cycle :direction :forward/:reverse}` or nil to clear.
  An invalid :direction is normalized to :forward. Stored as plugin-data
  `:ovion \"stroke-anim\"` (pr-str). The viewer runtime reads it and
  animates stroke-dashoffset via GSAP (reduced-motion guarded)."
  [{:keys [shape-id config]}]
  (ptk/reify ::set-stroke-anim
    ptk/WatchEvent
    (watch [it state _]
      (let [cfg (if (nil? config)
                  nil
                  (let [direction (:direction config)]
                    (cond-> (assoc config :speed (or (:speed config) 2000))
                      (not (contains? valid-directions direction))
                      (assoc :direction :forward))))]
        (commit-stroke-anim it state shape-id cfg)))))

(defn clear-stroke-anim
  "Remove the stroke-anim plugin-data slot from shape `shape-id` (one undo).
  The viewer then renders the stroke statically (no dashoffset animation)."
  [{:keys [shape-id]}]
  (ptk/reify ::clear-stroke-anim
    ptk/WatchEvent
    (watch [it state _]
      (commit-stroke-anim it state shape-id nil))))