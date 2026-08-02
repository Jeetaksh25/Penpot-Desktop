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
   [app.common.geom.shapes :as gsh]
   [app.common.types.design-spec :as cds]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
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
                         :content (-> s :content :text)}))))
            selected))))

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
          interactions))

(defn- offset-top-frames
  "Translate top-level frames (parent-id == uuid/zero) by [ox oy]. Used for
   region updates so the new board lands where the selection was."
  [obj-map ox oy]
  (if (and (zero? ox) (zero? oy))
    obj-map
    (reduce-kv (fn [m id s]
                (if (= uuid/zero (:parent-id s))
                  (assoc m id (assoc s
                                     :x (+ (or (:x s) 0) ox)
                                     :y (+ (or (:y s) 0) oy)))
                  m))
              obj-map
              obj-map)))

;; ── The apply event ──────────────────────────────────────────────────────────

(defn apply-design-spec
  "Commit a DesignSpec (already-keywordized CLJS data) to the current page.

  Options:
    :spec        the DesignSpec map (required)
    :target      \"full\" | \"update-selection\" (default \"full\")
    :select?     whether to select the new top-level frames after commit
                 (default true)

  For region updates the placement origin is computed from the current
  selection bounds HERE (from potok state), so the caller does not need state
  access. Emits one undo transaction. On invalid spec, emits a warning toast
  and aborts cleanly (no partial commit)."
  [{:keys [spec target select?]
    :or {target "full" select? true}
    :as opts}]
  (ptk/reify ::apply-design-spec
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            page    (dsh/lookup-page state)
            objects (dsh/lookup-page-objects state)]
        (if (or (nil? spec) (not (try (cds/check-design-spec spec)
                                     (catch :default _ false))))
          ;; Invalid/absent spec — surface to the user, do not touch the canvas.
          (rx/of (ntf/info (tr "workspace.ai.bar.invalid-spec")))

          (let [tree        (cds/spec->shape-tree spec)
                is-update   (= target "update-selection")
                ;; Region update origin = current selection top-left.
                bounds      (when is-update (selection-bounds state))
                ox          (or (some-> bounds :x) 0)
                oy          (or (some-> bounds :y) 0)
                obj-map     (-> (:objects tree)
                                (bake-interactions (:interactions tree))
                                (offset-top-frames ox oy))
                order       (:order tree)
                flows       (:flows tree)
                selected    (when is-update (dsh/lookup-selected state))
                undo-id     (uuid/next)

                changes (-> (pcb/empty-changes it page-id)
                            (pcb/with-page page)
                            (pcb/with-objects objects))

                ;; Region update: delete the selection first.
                changes (if (and is-update (seq selected))
                          (pcb/remove-objects changes (into [] selected))
                          changes)

                ;; Add every shape parent-first (order is pre-order).
                changes (reduce (fn [ch id]
                                  (if-let [s (get obj-map id)]
                                    (pcb/add-object ch s)
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
                   (dwu/commit-undo-transaction undo-id))))))))

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