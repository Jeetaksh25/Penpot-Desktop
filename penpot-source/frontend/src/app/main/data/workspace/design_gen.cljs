;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.design-gen
  "Feature 3 + 4 — the frontend half of the AI design pipeline.

  The closed AI layer (`src-tauri/src/llm.rs`) returns a DesignSpec JSON.
  This namespace turns that spec into live Penpot shapes and commits them
  to the canvas as a SINGLE undo transaction:

    1. keywordize the JS spec (`js->clj`) and validate with `check-design-spec`
    2. expand via `cds/spec->shape-tree` → {:objects :order :id-map
       :interactions :flows}
    3. bake :interactions onto each shape (so `add-object` validates them in
       one pass — `update-shapes` after `add-object` would miss the just-added
       shape because the builder's `lookup-objects` reads the pre-batch file
       data, not the running redo-changes)
    4. for region updates (target \"update-selection\"): delete the current
       selection and translate the generated top-level frames to the
       selection's origin so the new board lands exactly where the old
       content was
    5. commit via `pcb` + `dch/commit-changes` inside one undo transaction
    6. attach prototype flows to the page via `pcb/set-flow`

  Also provides:
    - `selection->snippet` — serialize the current selection into the JSON
      the backend `SelectionContext.shapes` field expects (region-update).
    - `spec->preview-svg` — a crude SVG render of the spec for the preview
      modal (so the user can accept/reject before it hits the canvas)."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.transforms :as gtr]
   [app.common.types.design-spec :as cds]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.site-gen :as site-gen]
   [app.main.data.workspace.undo :as dwu]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;; ── Public helpers ──────────────────────────────────────────────────────────

(defn selection-bounds
  "Bounding rect {:x :y :width :height} of the currently selected shapes,
   or nil when nothing is selected. Used to size+place a region update."
  [state]
  (let [objects    (dsh/lookup-page-objects state)
        selected   (dsh/lookup-selected state)
        shapes     (keep objects selected)
        ;; shape->rect returns nil when a shape lacks numeric geometry; drop
        ;; those so `apply min` never sees a nil coordinate.
        rects      (keep gsh/shape->rect shapes)]
    (when (seq rects)
      {:x      (apply min (map :x rects))
       :y      (apply min (map :y rects))
       :width  (- (apply max (map #(+ (:x %) (:width %)) rects))
                  (apply min (map :x rects)))
       :height (- (apply max (map #(+ (:y %) (:height %)) rects))
                  (apply min (map :y rects)))})))

(defn selection->snippet
  "Serialize the current selection into the shape context the AI backend
   consumes for region updates. Returns a vector of plain maps (string keys
   after `clj->js`) or nil if nothing is selected."
  [state]
  (let [objects  (dsh/lookup-page-objects state)
        selected (dsh/lookup-selected state)]
    (when (seq selected)
      (into []
            (keep (fn [id]
                    (let [s (get objects id)]
                      (when s
                        {:id     (str id)
                         :type   (name (:type s :rect))
                         :name   (:name s "")
                         :x      (:x s 0)
                         :y      (:y s 0)
                         :width  (:width s 0)
                         :height (:height s 0)
                         :fills  (into []
                                      (map (fn [f]
                                             {:fill-color   (:fill-color f "#cccccc")
                                              :fill-opacity (:fill-opacity f 1)}))
                                      (:fills s))
                         ;; Use the canonical content->text so paragraph
                         ;; boundaries are preserved (paragraphs join with
                         ;; \"\\n\"). The naive (:content :text) is nil (no
                         ;; top-level :text) and a flat walk glues paragraphs
                         ;; together with no separator.
                         :content (if-let [c (:content s)]
                                    (txt/content->text c)
                                    "")}))))
            selected))))

;; ── Scene serialization (agent visibility) ──────────────────────────────────
;;
;; A pure, read-only, token-bounded walk of the current page's shape map for
;; the agent loop's "understand what's visible via code" channel. Emits one
;; compact, depth-indented record per shape, grounded in shape.cljc's schema.
;; It NEVER calls pcb/dch/dws/dwu — it cannot mutate the canvas. A visited-id
;; guard makes it safe on cyclic component-instance graphs.

(def ^:private scene-defaults
  {:max-shapes 200 :max-depth 6 :max-content-chars 500 :max-total-chars 12000})

(defn- scene-fill-summary
  "Summarize one fill. When `token-name` is present and resolves to a color
   token in `color-tokens` (name -> hex), emit `{:token name}` instead of the
   raw hex (token awareness for the agent scene channel). Defensive: falls back
   to the raw value when the token is absent or not a color token."
  ([f] (scene-fill-summary f nil nil))
  ([f token-name color-tokens]
   (cond
     (:fill-image f)          {:image true}
     (:fill-color-gradient f) {:gradient true}
     :else                    (let [tok (when (and token-name
                                                   (contains? color-tokens token-name))
                                          token-name)]
                                (cond-> {:opacity (:fill-opacity f 1)}
                                  tok  (assoc :token tok)
                                  (nil? tok) (assoc :color (:fill-color f "#cccccc")))))))

(defn- scene-stroke-summary
  ([strokes] (scene-stroke-summary strokes nil nil))
  ([strokes token-name color-tokens]
   (when-let [s (first strokes)]
     (let [tok (when (and token-name
                          (contains? color-tokens token-name))
                 token-name)]
       (cond-> {:width   (:stroke-width s)
                :opacity (:stroke-opacity s)
                :style   (:stroke-style s)}
         tok  (assoc :token tok)
         (nil? tok) (assoc :color (:stroke-color s)))))))

(defn- scene-typography [shape]
  (when (= :text (:type shape))
    (some (fn [node]
            (when (and (map? node) (or (:font-family node) (:font-size node)))
              {:family (:font-family node)
               :size   (:font-size node)
               :weight (:font-weight node)
               :align  (:text-align node)}))
          (txt/node-seq (:content shape)))))

(defn- scene-layout-summary [shape]
  (let [grid? (some? (:layout-grid-dir shape))
        flex? (some? (:layout-flex-dir shape))]
    (when (or flex? grid?)
      (cond-> {:kind (if grid? "grid" "flex")}
        (some? (:layout-flex-dir shape))        (assoc :dir (name (:layout-flex-dir shape)))
        (some? (:layout-grid-dir shape))        (assoc :dir (name (:layout-grid-dir shape)))
        (some? (:layout-gap shape))             (assoc :gap (:layout-gap shape))
        (some? (:layout-align-items shape))     (assoc :align (name (:layout-align-items shape)))
        (some? (:layout-justify-content shape)) (assoc :justify (name (:layout-justify-content shape)))))))

(defn- scene-shape-record [shape selected-set max-content-chars color-tokens]
  (let [typo    (scene-typography shape)
        layout  (scene-layout-summary shape)
        content (when (= :text (:type shape))
                  (when-let [c (:content shape)]
                    (let [t (txt/content->text c)]
                      (when (pos? (count t))
                        (if (> (count t) max-content-chars)
                          (str (subs t 0 max-content-chars) "…")
                          t)))))
        applied (:applied-tokens shape)
        fill-tok   (get applied :fill)
        stroke-tok (get applied :stroke-color)]
    (cond-> {:id          (str (:id shape))
             :type        (name (:type shape :rect))
             :name        (:name shape "")
             :x           (:x shape 0)
             :y           (:y shape 0)
             :w           (:width shape 0)
             :h           (:height shape 0)
             :rotation    (:rotation shape 0)
             :fills       (mapv #(scene-fill-summary % fill-tok color-tokens)
                                (:fills shape))
             :stroke      (scene-stroke-summary (:strokes shape) stroke-tok color-tokens)
             :opacity     (:opacity shape)
             :r1          (:r1 shape)
             :hidden      (boolean (:hidden shape))
             :selected    (boolean (contains? selected-set (:id shape)))
             :children    (mapv (fn [cid] (str cid)) (:shapes shape []))
             :componentId (when (:component-id shape) (str (:component-id shape)))}
      (some? content) (assoc :content content)
      (some? typo)    (assoc :typography typo)
      (some? layout)  (assoc :layout layout))))

(defn- color-tokens-from-state
  "Return a map {token-name hex-string} of the active color tokens, or nil when
   the file has no tokens-lib. Defensive: any failure yields nil so the scene
   serializer falls back to raw fill/stroke values."
  [state]
  (try
    (let [tlib (-> (dsh/lookup-file-data state) :tokens-lib)]
      (when tlib
        (let [active (ctob/get-tokens-in-active-sets tlib)]
          (into {}
                (keep (fn [[name tok]]
                        (when (= :color (:type tok))
                          (let [v (:value tok)]
                            (when (string? v) [name v])))))
                active))))
    (catch :default _ nil)))

(defn serialize-scene
  "Walk the current page's shape map and emit a token-bounded, depth-indented
  structured description of the visible scene so the AI agent can reason about
  the canvas before generating / region-updating. Pure + read-only — it never
  emits dw*/pcb/dch/dwu.

  Token awareness: when a shape's `:applied-tokens` references a color token
  that exists in the active token sets, the fill/stroke summary emits
  `{:token \"color.primary\"}` instead of the raw hex. Defensive — falls back
  to raw values when no tokens-lib / no attribution.

  Returns a string (one compact shape record per line), or nil when the page
  has no shapes. Bounded by max-shapes / max-depth / max-content-chars /
  max-total-chars so the prompt cannot overflow the model context. A trailing
  `[truncated …]` marker is appended when a cap is hit."
  ([state] (serialize-scene state scene-defaults))
  ([state {:keys [max-shapes max-depth max-content-chars max-total-chars]
           :or {max-shapes 200 max-depth 6 max-content-chars 500 max-total-chars 12000}}]
   (let [objects  (dsh/lookup-page-objects state)
         selected (into #{} (dsh/lookup-selected state))
         color-tokens (color-tokens-from-state state)]
     (if-not (seq objects)
       nil
       (let [marker-budget 80
             budget        (max 0 (- max-total-chars marker-budget))
             lines         (volatile! #js [])
             chars         (volatile! 0)
             cnt           (volatile! 0)
             truncated     (volatile! false)
             visited       (volatile! #{})
             push!         (fn [line]
                             (let [c (count line)]
                               (if (<= (+ @chars c) budget)
                                 (do (vswap! lines #(doto % (.push line)))
                                     (vswap! chars + c 1)
                                     true)
                                 (do (vreset! truncated true) false))))
             walk          (fn walk [id depth]
                             (when (and (not @truncated)
                                        (< @cnt max-shapes)
                                        (<= depth max-depth)
                                        (not (contains? @visited id)))
                               (when-let [s (get objects id)]
                                 (when-not (:hidden s)
                                   (vswap! visited conj id)
                                   (let [rec  (scene-shape-record s selected
                                                                   max-content-chars
                                                                   color-tokens)
                                         line (str (apply str (repeat depth "  "))
                                                   (pr-str rec))]
                                     (when (push! line)
                                       (vswap! cnt inc)
                                       (when (>= @cnt max-shapes) (vreset! truncated true))
                                       (doseq [cid (:shapes s [])]
                                         (walk cid (inc depth)))))))))]
         (doseq [id (keys objects)]
           (when (= uuid/zero (get-in objects [id :parent-id]))
             (walk id 0)))
         (when @truncated
           (vswap! lines #(doto % (.push (str "[truncated: shapes=" @cnt "]")))))
         (let [arr @lines]
           (if (zero? (.-length arr)) nil (.join arr "\n"))))))))

;; ── Internal: tree preparation ─────────────────────────────────────────────

(defn- bake-interactions
  "Attach the spec's :interactions onto the shapes they reference, returning
   a new objects map. Each interaction is already validated by
   `cds/spec->interaction`; we just conj it onto the shape's :interactions
   vector. Done BEFORE `add-object` so the builder validates the whole shape
   (with its interactions) in one pass."
  [obj-map interactions]
  (reduce (fn [m {:keys [shape-id interaction]}]
            (if-not shape-id
              m
              (update m shape-id
                      (fn [s]
                        (assoc s :interactions
                               (conj (vec (get s :interactions)) interaction)))))
          obj-map
          interactions)))

(defn- translate-tree
  "Translate EVERY shape in the generated tree by [ox oy] using the canonical
   `gtr/move`, which keeps :selrect/:points/:x/:y/:position-data (and path
   :content) consistent as a unit. Used for region updates so the new board
   lands exactly where the selection was.

   Two correctness points vs. a naive `assoc :x :y`:
     - Render/hit geometry lives in :selrect/:points, NOT :x/:y. `gtr/move`
       updates them together, so the renderer draws at the offset position.
       A bare `assoc :x :y` leaves :points at the spec origin → the board
       renders at (0,0) instead of (ox,oy).
     - In the spec every shape's :x/:y is page-absolute (children are NOT
       relative to their frame), so ALL shapes — frames and descendants —
       must be translated, not just top-level frames."
  [obj-map ox oy]
  (if (and (zero? ox) (zero? oy))
    obj-map
    (let [delta (gpt/point ox oy)]
      (reduce-kv (fn [m id s]
                   (assoc m id (gtr/move s delta)))
                 obj-map
                 obj-map))))

;; ── Design-system constraint layer (Phase 2) ────────────────────────────────
;;
;; `apply-design-constraints` post-processes the shape tree produced by
;; `cds/spec->shape-tree` BEFORE the pcb build, so generated geometry honors
;; the workspace's design system (tokens) + free-form guidelines. It is ONLY
;; called when the AI config's `:design-system-guidelines` is non-blank
;; (byte-identical-when-inactive: blank guidelines → this fn never runs and the
;; tree is committed verbatim, exactly as before).
;;
;; It is PURE and DEFENSIVE: any failure inside a per-shape transform is
;; swallowed so a malformed token / component entry never aborts the whole
;; generation. It returns a new tree (same shape as the input plus an optional
;; `:reuse-markers` map {uuid component-id}).

(defn- hex->rgb
  "Parse a #rrggbb / #rgb hex string into [r g b] ints, or nil."
  [hex]
  (when (and (string? hex) (str/starts-with? hex "#"))
    (let [h (subs hex 1)]
      (cond
        (= (count h) 6) [(js/parseInt (subs h 0 2) 16)
                         (js/parseInt (subs h 2 4) 16)
                         (js/parseInt (subs h 4 6) 16)]
        (= (count h) 3) [(js/parseInt (str (nth h 0) (nth h 0)) 16)
                         (js/parseInt (str (nth h 1) (nth h 1)) 16)
                         (js/parseInt (str (nth h 2) (nth h 2)) 16)]
        :else nil))))

(defn- color-distance
  "Squared euclidean distance between two hex colors. nil when either is
   unparseable (so the caller skips resolution for that fill)."
  [a b]
  (when-let [[r1 g1 b1] (hex->rgb a)]
    (when-let [[r2 g2 b2] (hex->rgb b)]
      (+ (* (- r1 r2) (- r1 r2))
         (* (- g1 g2) (- g1 g2))
         (* (- b1 b2) (- b1 b2))))))

(defn- nearest-color-token
  "Given a hex literal and a map {token-name hex}, return the [name hex] pair
   with the smallest RGB distance, or nil when the map is empty / no token
   parses. An exact match short-circuits."
  [hex color-tokens]
  (when (and hex (seq color-tokens))
    (reduce
     (fn [best [name tok-hex]]
       (let [d (color-distance hex tok-hex)]
         (cond
           (nil? d) best
           (= d 0)  (reduced [name tok-hex])
           (or (nil? best) (< d (best 2))) [name tok-hex d]
           :else best)))
     nil
     color-tokens)))

(defn- snap-to-grid
  "Round `v` to the nearest multiple of `unit` (default 8). Non-numbers and
   non-positive units pass through unchanged."
  ([v] (snap-to-grid v 8))
  ([v unit]
   (if (and (number? v) (number? unit) (pos? unit))
     (* (Math/round (/ v unit)) unit)
     v)))

(defn- resolve-fills
  "Replace raw hex fill colors with the nearest color token's hex and stamp
   `:applied-tokens {:fill token-name}` on the shape. Returns the shape
   unchanged when no color tokens exist."
  [shape color-tokens]
  (if (or (nil? color-tokens) (empty? color-tokens))
    shape
    (let [fills (:fills shape)
          new-fills (mapv
                     (fn [f]
                       (if-let [fc (:fill-color f)]
                         (if-let [[name tok-hex] (nearest-color-token fc color-tokens)]
                           (assoc f :fill-color tok-hex
                                    :fill-token name)
                           f)
                         f))
                     fills)]
      (cond-> shape
        (not= new-fills fills)
        (-> (assoc :fills new-fills)
            (assoc :applied-tokens
                   (merge (:applied-tokens shape)
                          (into {}
                                (keep (fn [f] (when (:fill-token f)
                                                [:fill (:fill-token f)])))
                                new-fills)))
            ;; drop the transient marker so it never reaches pcb validation.
            (as-> s (assoc s :fills
                           (mapv #(dissoc % :fill-token) (:fills s)))))))))

(defn- snap-shape
  "Snap position/size/gap/radii to the spacing grid. Defensive: only numbers
   are snapped; nil keys are left alone."
  [shape spacing-unit]
  (if (or (nil? spacing-unit) (not (pos? spacing-unit)))
    shape
    (let [snap (fn [v] (snap-to-grid v spacing-unit))]
      (-> shape
          (update :x        snap)
          (update :y        snap)
          (update :width    snap)
          (update :height   snap)
          (update :layout-gap snap)
          (update :r1       snap)
          (update :r2       snap)
          (update :r3       snap)
          (update :r4       snap)))))

(defn- component-reuse-markers
  "Build {uuid component-id} for shapes whose `:name` matches a library
   component name. Returns {} when there are no matches / no components."
  [obj-map component-name->id]
  (if (empty? component-name->id)
    {}
    (reduce-kv
     (fn [m id shape]
       (let [nm (:name shape)]
         (if-let [cid (get component-name->id nm)]
           (assoc m id cid)
           m)))
     {}
     obj-map)))

(defn apply-design-constraints
  "Post-process the `tree` (output of `cds/spec->shape-tree`) so the generated
   geometry honors the workspace design system. PURE + DEFENSIVE.

   Args:
     tree                  the {:objects :order :id-map :interactions :flows} map
     guidelines            the AI config `:design-system-guidelines` string
                           (already known non-blank by the caller)
     color-tokens          {token-name hex} of active color tokens (may be nil)
     spacing-unit          grid unit (number) for snapping, or nil to skip
     component-name->id    {component-name component-id} for reuse markers

   Returns a new tree with:
     - fills resolved to the nearest color token (+ `:applied-tokens`)
     - position/size/gap/radii snapped to the spacing grid
     - a `:reuse-markers` {uuid component-id} map when names match components

   Any per-shape failure is swallowed (the shape passes through unchanged) so a
   bad token / component entry never aborts the whole generation."
  [tree guidelines color-tokens spacing-unit component-name->id]
  (let [guidelines (or guidelines "")]
    (if (str/blank? guidelines)
      tree
      (let [objects (:objects tree)
            new-objects
            (reduce-kv
             (fn [m id shape]
               (let [s1 (try (resolve-fills shape color-tokens)
                          (catch :default _ shape))
                     s2 (try (snap-shape s1 spacing-unit)
                          (catch :default _ s1))]
                 (assoc m id s2)))
             (array-map)
             objects)
            markers (try (component-reuse-markers new-objects component-name->id)
                      (catch :default _ {}))]
        (-> tree
            (assoc :objects new-objects)
            (assoc :reuse-markers markers))))))

(defn- active-spacing-unit
  "Pick the spacing grid unit from the active spacing tokens: the smallest
   nonzero spacing token value, or nil when none. Defensive — any failure
   yields nil (caller then skips the snap step)."
  [state]
  (try
    (let [tlib (-> (dsh/lookup-file-data state) :tokens-lib)]
      (when tlib
        (let [active (ctob/get-tokens-in-active-sets tlib)]
          (->> (keep (fn [[_ tok]]
                       (when (= :spacing (:type tok))
                         (let [v (:value tok)]
                           (when (number? v) v))))
                     active)
               (filter pos?)
               seq
               (apply min)))))
    (catch :default _ nil)))

(defn- component-name->id
  "Build {component-name component-id} from the current file's component
   library. Returns {} when there are no components. Defensive."
  [state]
  (try
    (let [comps (-> (dsh/lookup-file-data state) :components)]
      (if (empty? comps)
        {}
        (into {}
              (keep (fn [[cid c]]
                      (let [nm (:name c)]
                        (when (and nm (not (str/blank? (str nm))))
                          [(str nm) cid]))))
              comps)))
    (catch :default _ {})))

;; ── The apply event ──────────────────────────────────────────────────────────

(defn apply-design-spec
  "Commit a DesignSpec (already-keywordized CLJS data) to the current page.

  Options:
    :spec        the DesignSpec map (required)
    :target      \"new-board\" | \"update-selection\" (default \"new-board\").
                 \"new-board\" = a fresh board (the backend's own default and
                 the value it injects into the LLM prompt); only
                 \"update-selection\" triggers region-update placement.
    :select?     whether to select the new top-level frames after commit
                 (default true)

  For region updates the placement origin is computed from the current
  selection bounds HERE (from potok state), so the caller does not need state
  access. Emits one undo transaction. On invalid spec, emits a warning toast
  and aborts cleanly (no partial commit)."
  [{:keys [spec target select?]
    :or {target "new-board" select? true}
    :as opts}]
  (ptk/reify ::apply-design-spec
    ptk/WatchEvent
    (watch [it state _]
      ;; P0.03 — multi-page delegation. If the spec carries a `:site`, hand
      ;; off to `site-gen/apply-site-spec` which fans the pages out across
      ;; real Penpot pages in one undo transaction. The single-page behavior
      ;; below is unchanged when `:site` is absent (the existing ai_bar.cljs
      ;; callsite path).
      (if (get spec :site)
        (rx/of (site-gen/apply-site-spec opts))
        (let [page-id (:current-page-id state)
              page    (dsh/lookup-page state)
              objects (dsh/lookup-page-objects state)
            ;; Validate AND expand in one guarded step. `check-design-spec`
            ;; returns the spec on success and throws ex-info on failure;
            ;; `spec->shape-tree` can also throw on malformed nested :shapes
            ;; (the schema validates children only as [:vector :any]). Any
            ;; throw → nil → invalid-spec toast, no partial commit.
            tree (try
                   (when (and spec (cds/check-design-spec spec))
                     (cds/spec->shape-tree spec))
                   (catch :default _ nil))]
        (if (nil? tree)
          ;; Invalid/absent spec — surface to the user, do not touch the canvas.
          (rx/of (ntf/info (tr "workspace.ai.bar.invalid-spec")))

          (let [is-update   (= target "update-selection")
                ;; Design-system constraints: ONLY applied when the AI config's
                ;; `:design-system-guidelines` opt is non-blank. Byte-identical-
                ;; when-inactive — blank/absent guidelines skip this entirely
                ;; and the tree is committed verbatim, exactly as before. The
                ;; UI bar passes `:design-system-guidelines` through from the
                ;; fetched `llm_get_config` view when applying a preview.
                guidelines  (:design-system-guidelines opts)
                tree-c      (if (and guidelines (not (str/blank? guidelines)))
                              (apply-design-constraints
                               tree guidelines
                               (color-tokens-from-state state)
                               (active-spacing-unit state)
                               (component-name->id state))
                              tree)
                ;; Region update origin = current selection top-left.
                bounds      (when is-update (selection-bounds state))
                ox          (or (some-> bounds :x) 0)
                oy          (or (some-> bounds :y) 0)
                obj-map     (-> (:objects tree-c)
                                (bake-interactions (:interactions tree-c))
                                (translate-tree ox oy))
                order       (:order tree-c)
                flows       (:flows tree-c)
                selected    (when is-update (dsh/lookup-selected state))
                undo-id     (uuid/next)

                changes (-> (pcb/empty-changes it page-id)
                            (pcb/with-page page)
                            (pcb/with-objects objects))

                ;; Region update: delete the selection first.
                changes (if (and is-update (seq selected))
                          (pcb/remove-objects changes (into [] selected))
                          changes)

                ;; Add every shape parent-first (order is pre-order). Each
                ;; AI-generated shape is stamped with the plugin-data flag
                ;; `:ovion "ai-generated" = "true"` so P1.07 auto-refresh can
                ;; detect duplicates of generated content. Additive: the flag
                ;; is a plain map entry on the stored object, byte-identical
                ;; for any non-AI caller of this code path.
                changes (reduce (fn [ch id]
                                  (if-let [s (get obj-map id)]
                                    (pcb/add-object ch (assoc-in s [:plugin-data :ovion "ai-generated"] "true"))
                                    ch))
                                changes order)

                ;; Attach prototype flows to the page (flows is a vector;
                ;; the page :flows slot is a map keyed by flow id).
                changes (reduce (fn [ch flow]
                                  (pcb/set-flow ch (:id flow) flow))
                                changes flows)

                ;; New top-level frame ids — for post-commit selection.
                top-ids (into []
                              (keep (fn [id]
                                      (let [s (get obj-map id)]
                                        (when (= uuid/zero (:parent-id s)) id))))
                              order)]

            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (when (and select? (seq top-ids))
                     (dws/select-shapes (apply d/ordered-set top-ids)))
                   (dwu/commit-undo-transaction undo-id)))))))))

;; ── Preview (crude SVG) ──────────────────────────────────────────────────────
;;
;; A minimal, dependency-free SVG render of a DesignSpec for the preview
;; modal. It walks frames → shapes and emits <rect>/<circle>/<text>. It is
;; intentionally crude: real fidelity is the canvas commit, this is just a
;; "looks roughly right?" sanity check before Apply.

(defn- fill-attrs
  [fills default]
  (let [f (or (first fills) {:fill-color default :fill-opacity 1})]
    {:fill (or (:fill-color f) default)
     :fill-opacity (or (:fill-opacity f) 1)}))

(defn- shape->svg
  [s]
  (let [type (get s :type :rect)
        x    (or (:x s) 0)
        y    (or (:y s) 0)
        w    (or (:width s) 0)
        h    (or (:height s) 0)]
    (case type
      :circle
      [:circle (merge {:cx (+ x (/ w 2)) :cy (+ y (/ h 2))
                       :r  (min (/ w 2) (/ h 2))}
                      (fill-attrs (:fills s) "#cccccc"))]
      :text
      [:text (merge {:x (+ x 2) :y (+ y (or (:font-size s) 14))
                     :font-size (or (:font-size s) "14")
                     :font-family (or (:font-family s) "sans-serif")}
                    (fill-attrs (:fills s) "#000000"))
       ;; In the DesignSpec, :content is a raw string (not the Penpot text
       ;; content tree, which only exists after spec->shape-tree).
       (or (:content s) "")]
      [:rect (merge {:x x :y y :width w :height h :rx (or (:r1 s) 0)}
                    (fill-attrs (:fills s) "#cccccc"))]))) ; rects + unknown

(defn- frame->svg
  [frame]
  (let [fw (or (:width frame) 1440)
        fh (or (:height frame) 900)
        bg (or (:fill-color (first (:fills frame)) "#ffffff") "#ffffff")]
    [:div {:key (or (:id frame) "frame")
           :style #js {"width" "100%"}}
     [:svg {:width fw :height fh :viewBox (str "0 0 " fw " " fh)
            :style #js {"border" "1px solid #e5e7eb"
                        "borderRadius" "8px"
                        "maxWidth" "100%" "height" "auto"
                        "background" bg}}
      [:rect {:x 0 :y 0 :width fw :height fh
              :fill bg :fill-opacity 1}]
      (for [s (or (:shapes frame) [])]
        (shape->svg s))]]))

(defn spec->preview
  "Render a keywordized DesignSpec as hiccup (a column of per-frame SVGs)
   for the preview modal. Returns hiccup, not a string."
  [spec]
  (let [frames (or (:frames spec) [])]
    (if (empty? frames)
      [:div {:style #js {"padding" "24px" "color" "#6b7280"}}
       (tr "workspace.ai.bar.preview-empty")]
      [:div {:style #js {"display" "flex" "flexDirection" "column"
                         "gap" "16px" "padding" "16px"
                         "maxHeight" "70vh" "overflow" "auto"}}
       (for [f frames]
         (frame->svg f))])))