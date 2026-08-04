;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.auto-helpers
  "Auto smart helpers (ALL_APPS_PARITY P1.07) — Lunacy-style ambient
  automation, each a toggleable per-shape helper. Heuristics only (NO LLM).

  Four helpers:
    1. auto shape color   — recolor a shape by picking a palette fill that
                            contrasts the layer beneath (nearest ancestor
                            solid fill; fallback black/white by contrast).
    2. auto z-index       — smaller dragged layers land on top: reorder a
                            shape above any larger sibling it overlaps.
    3. auto text color    — set the text glyph fill to black/white by WCAG
                            contrast against the layer beneath's fill.
    4. auto-refresh       — on duplicate of an AI-generated shape, heuristically
                            regenerate content (append a copy-suffix to text /
                            stamp a fresh random avatar seed) — NO LLM.

  Toggle persistence (per shape, undo/redo-safe):
    shape :plugin-data :ovion \"auto-helpers\" -> EDN string of
      {:auto-color bool :auto-z-index bool :auto-text-color bool :auto-refresh bool}
    via `pcb/set-plugin-data` (object-type :shape), mirroring
    `data/workspace/notes.cljs` (the widget-notes slot).

  AI-generated detection:
    No existing tag marks AI-generated shapes, so this module gates on a
    plugin-data flag `:ovion \"ai-generated\"` whose value is the string
    \"true\". The producer of AI-generated shapes (e.g. ai_gen.cljs /
    ai_tools.cljs) must stamp that flag when a shape is generated; see
    `ai-generated?` below and the cross-reference note in the report.

  The four pure heuristic fns are exported for unit testing / reuse:
    `auto-shape-color`, `auto-text-color`, `auto-z-index-changes`,
    `auto-refresh-on-duplicate`."

  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.text :as txt]
   [app.main.data.changes :as dch]
   [app.main.data.event :as-alias ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as-alias dws]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [clojure.string :as cstr]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def helpers-namespace
  "Plugin-data namespace keyword under which the auto-helpers toggle map is
  stored on the shape."
  :ovion)

(def helpers-key
  "Plugin-data key (string) under `helpers-namespace` for the toggle map."
  "auto-helpers")

(def ai-gen-namespace
  "Plugin-data namespace keyword under which the AI-generated flag + seed
  are stored on the shape."
  :ovion)

(def ai-gen-key
  "Plugin-data key (string) marking a shape as AI-generated (value \"true\")."
  "ai-generated")

(def ai-gen-seed-key
  "Plugin-data key (string) holding a random avatar seed for non-text
  AI-generated shapes, re-randomized on duplicate."
  "ai-gen-seed")

;; --- Toggle map helpers -----------------------------------------------------

(defn default-toggles
  "A fresh toggle map with every helper disabled. Kept as a function so
  callers never mutate a shared reference."
  []
  {:auto-color      false
   :auto-z-index    false
   :auto-text-color false
   :auto-refresh    false})

(def ^:private toggle-keys
  [:auto-color :auto-z-index :auto-text-color :auto-refresh])

(defn read-auto-helpers
  "Parse a shape's plugin-data auto-helpers slot back into a toggle map.
  Accepts either a shape map (reads `:plugin-data`) or a raw stored string.
  Returns `default-toggles` when the slot is absent or unparsable, and
  guarantees every toggle key is present (fills missing with false)."
  ([]
   (default-toggles))
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data helpers-namespace helpers-key])
               shape-or-str)
         parsed
         (if (or (nil? raw) (cstr/blank? raw))
           (default-toggles)
           (try
             (reader/read-string raw)
             (catch :default _
               (default-toggles))))]
     (into (default-toggles)
           (keep (fn [k] (when (contains? parsed k)
                           [k (boolean (get parsed k))])))
           toggle-keys))))

(defn- write-auto-helpers
  "Serialize a toggle map to the plugin-data slot string form. A map with
  every toggle false serializes to nil so the slot is cleared (a nil value
  in :set-plugin-data removes the key, per process-change)."
  [toggles]
  (let [toggles (into (default-toggles) toggles)]
    (if (every? false? (vals toggles))
      nil
      (pr-str toggles))))

(defn ai-generated?
  "True when `shape` carries the plugin-data flag `:ovion \"ai-generated\"`
  with value \"true\". The producer of an AI-generated shape must stamp this
  flag (see module docstring)."
  [shape]
  (boolean
   (when-let [raw (dm/get-in shape [:plugin-data ai-gen-namespace ai-gen-key])]
     (= "true" raw))))

;; --- WCAG contrast math (mirrored from ui.inspect.a11y) ---------------------
;;
;; `app.main.ui.inspect.a11y` defines `relative-luminance` and
;; `contrast-ratio` as private fns. They are replicated here (not imported)
;; to avoid editing the shared a11y namespace, and because the auto-helpers
;; data layer must not depend on a UI namespace. The math is identical.

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
  (let [a (d/nilv a 1)
        comp (fn [ch] (+ (* a (/ ch 255)) (* (- 1 a) 1)))
        lr (channel->linear (comp r))
        lg (channel->linear (comp g))
        lb (channel->linear (comp b))]
    (+ (* 0.2126 lr) (* 0.7152 lg) (* 0.0722 lb))))

(defn- contrast-ratio
  "WCAG contrast ratio between two [r g b a] colors, as a number >= 1."
  [c1 c2]
  (let [l1 (relative-luminance c1)
        l2 (relative-luminance c2)
        lighter (js/Math.max l1 l2)
        darker  (js/Math.min l1 l2)]
    (/ (+ lighter 0.05) (+ darker 0.05))))

(defn- hex->channels
  "Parse a #rrggbb hex string into [r g b 1] (alpha 1). Falls back to
  opaque black when parsing fails (delegates to `clr/hex->rgb`)."
  [hex]
  (let [[r g b] (clr/hex->rgb (or hex "#000000"))]
    [r g b 1]))

;; --- Layer-beneath fill resolution ------------------------------------------

(defn- ancestor-beneath-fill-color
  "Walk the parent chain (via `objects`) from `shape` and return the first
  solid fill hex color found on an ancestor, or nil (caller falls back to
  white). Mirrors a11y's `ancestor-background` but returns the hex string."
  [objects shape]
  (loop [parent-id (:parent-id shape)]
    (when (some? parent-id)
      (let [parent (get objects parent-id)]
        (if-let [fill (and parent
                           (->> (:fills parent)
                                (remove :hidden)
                                first))]
          (if (and (some? (:fill-color fill))
                   (nil? (:fill-color-gradient fill))
                   (nil? (:fill-image fill)))
            (:fill-color fill)
            (recur (:parent-id parent)))
          (recur (:parent-id parent)))))))

;; --- Pure heuristic fns -----------------------------------------------------

(defn- palette-hexes
  "Extract the plain hex color strings from a recent-colors palette vector.
  Each entry is a color map with a `:color` key; only `#rrggbb` entries are
  kept (library refs / gradients are skipped)."
  [palette]
  (into []
        (comp (map :color)
              (filter string?)
              (filter #(cstr/starts-with? % "#")))
        palette))

(defn auto-shape-color
  "Pick a solid fill color for `shape` from `palette` (a vector of
  recent-color maps) that has the highest WCAG contrast against the layer
  beneath (nearest ancestor solid fill; falls back to a white backdrop).
  When the palette has no usable hex, falls back to whichever of black/white
  contrasts the background more. Returns a new `:fills` vector for the
  shape, or nil when no color can be derived (never returns nil in practice
  — the fallback always resolves)."
  [shape objects palette]
  (let [bg-hex (or (ancestor-beneath-fill-color objects shape) "#ffffff")
        bg-ch  (hex->channels bg-hex)
        hexes  (palette-hexes palette)
        chosen-hex
        (if (seq hexes)
          (let [best
                (reduce
                 (fn [best hex]
                   (let [ch    (hex->channels hex)
                         ratio (contrast-ratio ch bg-ch)]
                     (if (or (nil? best) (> ratio (:ratio best)))
                       {:hex hex :ratio ratio}
                       best)))
                 nil
                 hexes)]
            (:hex best))
          ;; fallback: black or white, whichever contrasts bg more
          (if (>= (contrast-ratio [0 0 0 1] bg-ch)
                  (contrast-ratio [255 255 255 1] bg-ch))
            "#000000"
            "#ffffff"))]
    (when (some? chosen-hex)
      [{:type :fill :fill-color chosen-hex :fill-opacity 1}])))

(defn- set-text-content-fill
  "Return `content` with every text node's `:fills` set to a single solid
  fill of `hex` at full opacity. Preserves all other node attrs (font,
  size, weight, …). Text-node fills use the Penpot text-fill shape
  `{:fill-color :fill-opacity}` (no `:type`), matching `default-text-fills`."
  [content hex]
  (txt/transform-nodes
   txt/is-text-node?
   (fn [node] (assoc node :fills [{:fill-color hex :fill-opacity 1}]))
   content))

(defn auto-text-color
  "Set the text glyph color of a text `shape` to black or white, whichever
  has the higher WCAG contrast against the layer beneath's solid fill
  (fallback white background). Returns updated `:content` (text-node fills
  replaced), or nil when `shape` is not a text shape or has no content."
  [shape objects]
  (when (and (cfh/text-shape? shape) (some? (:content shape)))
    (let [bg-hex (or (ancestor-beneath-fill-color objects shape) "#ffffff")
          bg-ch  (hex->channels bg-hex)
          black  [0 0 0 1]
          white  [255 255 255 1]
          chosen-hex (if (>= (contrast-ratio black bg-ch)
                             (contrast-ratio white bg-ch))
                       "#000000"
                       "#ffffff")]
      (set-text-content-fill (:content shape) chosen-hex))))

(defn auto-z-index-changes
  "Reorder `shape` above any LARGER sibling it overlaps, so smaller dragged
  layers land on top. `changes` is the in-progress changeset (must carry
  objects + page-id, i.e. built via `pcb/empty-changes` + `with-file-data`
  + `with-page`). Returns `changes` with a `:reorder-children` change
  appended when a reorder is needed, else `changes` unchanged. Area is
  measured from `:selrect` width*height; overlap via `gsh/overlaps?`."
  [shape objects changes]
  (let [parent-id  (:parent-id shape)
        parent     (get objects parent-id)
        siblings   (:shapes parent)]
    (if (or (nil? parent) (nil? siblings))
      changes
      (let [shape-id   (:id shape)
            shape-rect (or (:selrect shape) {})
            shape-area (* (:width shape-rect 0) (:height shape-rect 0))
            shape-idx  (.indexOf siblings shape-id)]
        (if (neg? shape-idx)
          changes
          (let [above-ids (subvec siblings (inc shape-idx))
                bigger-overlapping
                (filterv
                 (fn [sid]
                   (let [sib (get objects sid)]
                     (and (some? sib)
                          (gsh/overlaps? sib shape-rect)
                          (let [sr (:selrect sib)]
                            (> (* (:width sr 0) (:height sr 0)) shape-area)))))
                 above-ids)]
            (if (empty? bigger-overlapping)
              changes
              (let [topmost     (peek bigger-overlapping)
                    without     (filterv #(not= % shape-id) siblings)
                    insert-idx  (inc (.indexOf without topmost))
                    before      (subvec without 0 insert-idx)
                    after       (subvec without insert-idx)
                    new-children (into before (cons shape-id after))]
                (pcb/reorder-children changes parent-id new-children)))))))))

(defn auto-refresh-on-duplicate
  "Heuristically refresh the `duplicate` shape's generated content (NO LLM).
  `original` is accepted for symmetry / future use but not required (may be
  nil).

    - text shapes: append a short \" (copy)\" suffix to the text content
      (via `txt/change-text`, which preserves the first paragraph/text-node
      styles so font/size/weight/align are inherited).
    - other AI-generated shapes: stamp a fresh random avatar seed into the
      duplicate's `:plugin-data` under `:ovion \"ai-gen-seed\"` (the caller
      commits the seed via `set-plugin-data`; see `refresh-ai-duplicate`).

  Returns the updated duplicate shape map (with `:content` or `:plugin-data`
  changed), or the duplicate unchanged when neither branch applies."
  [original duplicate]
  (let [content (:content duplicate)]
    (cond
      (and (cfh/text-shape? duplicate) (some? content))
      (let [text        (txt/content->text content)
            new-text    (str text " (copy)")
            new-content (txt/change-text content new-text)]
        (assoc duplicate :content new-content))

      :else
      (let [seed (str (js/Math.floor (* (js/Math.random) 1000000)))]
        (assoc-in duplicate [:plugin-data ai-gen-namespace ai-gen-seed-key] seed)))))

;; --- Commit helper ----------------------------------------------------------

(defn- base-changes
  "Build the base changeset for `page-id` / `file-id`: empty-changes with
  file-data + page attached so `pcb/update-shapes` / `pcb/set-plugin-data`
  can resolve objects + container-id."
  [it state page-id file-id]
  (let [file-data (dsh/lookup-file-data state file-id)
        page      (dsh/lookup-page state page-id)]
    (-> (pcb/empty-changes it page-id)
        (pcb/with-file-data file-data)
        (pcb/with-page page))))

;; --- Events -----------------------------------------------------------------

(defn toggle-auto-helper
  "Persist a single auto-helper toggle on every shape in `shape-ids`.
  `toggle-key` is one of `:auto-color` / `:auto-z-index` / `:auto-text-color`
  / `:auto-refresh`; `enabled?` is the new boolean. Commit is undo/redo-safe
  via one undo transaction wrapping all shapes. Mirrors
  `notes/set-widget-notes`."
  [{:keys [shape-ids toggle-key enabled?]}]
  (ptk/reify ::toggle-auto-helper
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id  (:current-page-id state)
            file-id  (:current-file-id state)
            objects  (dsh/lookup-page-objects state page-id)]
        (if (or (nil? page-id) (empty? shape-ids))
          (rx/empty)
          (let [undo-id (js/Symbol)
                changes
                (reduce
                 (fn [changes shape-id]
                   (let [shape       (get objects shape-id)
                         toggles     (read-auto-helpers shape)
                         new-toggles (assoc toggles toggle-key enabled?)
                         value       (write-auto-helpers new-toggles)]
                     (pcb/set-plugin-data changes :shape shape-id page-id
                                          helpers-namespace helpers-key value)))
                 (base-changes it state page-id file-id)
                 shape-ids)]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (dwu/commit-undo-transaction undo-id))))))))

(defn apply-auto-helpers
  "Run every ENABLED helper on the current selection (`shape-ids`) inside one
  undo transaction. For each selected shape, read its toggle map and apply:
    - :auto-color      -> auto-shape-color (non-text shapes; sets :fills)
    - :auto-text-color -> auto-text-color  (text shapes; sets :content)
    - :auto-z-index    -> auto-z-index-changes (reorders within parent)
  (:auto-refresh is triggered on duplicate, not by this event.) The project
  palette is drawn from the file's recent-colors."
  [{:keys [shape-ids]}]
  (ptk/reify ::apply-auto-helpers
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            file-id (:current-file-id state)
            objects (dsh/lookup-page-objects state page-id)
            palette (or (get-in state [:recent-colors file-id]) [])]
        (if (or (nil? page-id) (empty? shape-ids))
          (rx/empty)
          (let [undo-id (js/Symbol)
                changes
                (reduce
                 (fn [changes shape-id]
                   (let [shape   (get objects shape-id)
                         toggles (read-auto-helpers shape)
                         changes
                         (if (and (:auto-color toggles)
                                  (not (cfh/text-shape? shape)))
                           (pcb/update-shapes changes [shape-id]
                            (fn [s]
                              (if-let [fills (auto-shape-color s objects palette)]
                                (assoc s :fills fills)
                                s))
                            {:attrs [:fills]})
                           changes)
                         changes
                         (if (and (:auto-text-color toggles)
                                  (cfh/text-shape? shape))
                           (pcb/update-shapes changes [shape-id]
                            (fn [s]
                              (if-let [content (auto-text-color s objects)]
                                (assoc s :content content)
                                s))
                            {:attrs [:content]})
                           changes)]
                     changes))
                 (base-changes it state page-id file-id)
                 shape-ids)
                ;; auto-z-index may reorder parents; apply after the
                ;; fill/content passes so it operates on the same objects.
                changes
                (reduce
                 (fn [changes shape-id]
                   (let [shape   (get objects shape-id)
                         toggles (read-auto-helpers shape)]
                     (if (:auto-z-index toggles)
                       (auto-z-index-changes shape objects changes)
                       changes)))
                 changes
                 shape-ids)]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (dwu/commit-undo-transaction undo-id))))))))

(defn refresh-ai-duplicate
  "WatchEvent. Refresh a single duplicated AI-generated shape: reads the
  duplicate from state, applies `auto-refresh-on-duplicate`, and commits
  the result in one undo transaction. `original-id` may be nil (the
  heuristic uses the duplicate's own content). No-op (rx/empty) when the
  duplicate is missing, not AI-generated, or does not have auto-refresh
  enabled."
  [{:keys [original-id duplicate-id]}]
  (ptk/reify ::refresh-ai-duplicate
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            file-id   (:current-file-id state)
            objects   (dsh/lookup-page-objects state page-id)
            duplicate (get objects duplicate-id)]
        (if (or (nil? duplicate)
                (not (ai-generated? duplicate))
                (not (:auto-refresh (read-auto-helpers duplicate))))
          (rx/empty)
          (let [updated (auto-refresh-on-duplicate original-id duplicate)
                undo-id (js/Symbol)
                base    (base-changes it state page-id file-id)]
            (cond
              (not= (:content updated) (:content duplicate))
              (let [new-content (:content updated)
                    changes (pcb/update-shapes base [duplicate-id]
                               (fn [s] (assoc s :content new-content))
                               {:attrs [:content]})]
                (rx/of (dwu/start-undo-transaction undo-id)
                       (dch/commit-changes changes)
                       (dwu/commit-undo-transaction undo-id)))

              (not= (:plugin-data updated) (:plugin-data duplicate))
              (let [seed (dm/get-in updated [:plugin-data ai-gen-namespace ai-gen-seed-key])
                    changes (pcb/set-plugin-data base :shape duplicate-id page-id
                                                 ai-gen-namespace ai-gen-seed-key seed)]
                (rx/of (dwu/start-undo-transaction undo-id)
                       (dch/commit-changes changes)
                       (dwu/commit-undo-transaction undo-id)))

              :else (rx/empty))))))))

(defn refresh-selected-duplicates
  "WatchEvent. After a duplicate commits (the new shapes are now the
  current selection), refresh each selected shape that is AI-generated AND
  has auto-refresh enabled. Emits one `refresh-ai-duplicate` per matching
  shape. Intended to be emitted as a follow-up to a `::dws/duplicate-shapes`
  event (see `on-duplicate-refresh`)."
  []
  (ptk/reify ::refresh-selected-duplicates
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id  (:current-page-id state)
            objects  (dsh/lookup-page-objects state page-id)
            selected (dsh/lookup-selected state)]
        (if (empty? selected)
          (rx/empty)
          (->> (rx/from selected)
               (rx/filter
                (fn [dup-id]
                  (let [dup (get objects dup-id)]
                    (and (some? dup)
                         (ai-generated? dup)
                         (:auto-refresh (read-auto-helpers dup))))))
               (rx/map (fn [dup-id]
                         (refresh-ai-duplicate {:duplicate-id dup-id})))))))))

(defn on-duplicate-refresh
  "WatchEvent hook (install once on workspace mount). Listens on the global
  event stream for `::dws/duplicate-shapes` events and emits a follow-up
  `refresh-selected-duplicates`, which reads fresh (post-duplicate) state —
  by then the new duplicates are the current selection — and refreshes each
  AI-generated duplicate that has auto-refresh enabled. Returns a long-lived
  stream; the hook stays armed for the session."
  []
  (ptk/reify ::on-duplicate-refresh
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? ::dws/duplicate-shapes))
           (rx/map (fn [_evt] (refresh-selected-duplicates)))))))