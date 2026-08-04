;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL
;;
;; P2.12 — Color application during merge.
;;
;; Adds a "color-source" mode to the existing boolean-merge path so the
;; user can choose what fill the merged :bool result carries, instead of
;; always accepting Penpot's internal "inherit the head shape's fills"
;; rule. The modes are:
;;
;;   :internal       (DEFAULT) — keep the existing internal-rule fill.
;;                              The wrap event emits the raw `dwb/create-bool`
;;                              EXACTLY as the bool menu does today, so the
;;                              merge commits byte-identically when this mode
;;                              is active (or when the chosen source resolves
;;                              to no fill — nil-safety).
;;   :inherit-first  — inherit the first (topmost / first-selected) shape's
;;                     first solid fill.
;;   :inherit-second — inherit the second shape's first solid fill.
;;   :active-swatch  — use the user's currently active fill color from the
;;                     colorpicker state (`refs/colorpicker` -> current color).
;;   :custom         — a plain hex string chosen inline in the bool menu.
;;
;; The wrap event (`bool-with-color-event`) pre-generates the bool result
;; id, emits the EXISTING `dwb/create-bool` with `:force-shape-id`, then
;; emits a follow-up `dwsh/update-shapes` that sets `:fills` on that id.
;; Both commits are wrapped in a single undo transaction
;; (`dwu/start-undo-transaction` / `commit-undo-transaction`) so the whole
;; operation is one undo step. When the resolved fill is nil OR the mode is
;; `:internal`, the wrap event falls back to emitting the raw
;; `dwb/create-bool` with no `:force-shape-id` — i.e. byte-identical to the
;; pre-feature behavior.

(ns app.main.data.workspace.bool_color
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cph]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.bool :as dwb]
   [app.main.data.workspace.colors :as dwc]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; ── Pure helpers ─────────────────────────────────────────────────────────────

(defn- first-solid-fill
  "Return the first solid (non-gradient, non-image) fill map of `shape`, or
  nil when the shape has no solid fill."
  [shape]
  (some #(when (some? (:fill-color %)) %)
        (:fills shape)))

(defn- normalize-fill
  "Reduce a fill map to the minimal solid-color slots so the override is
  always a clean `{:fill-color :fill-opacity}` (no stray ref/gradient keys
  carried over from the source shape). nil-safe."
  [fill]
  (when (some? fill)
    (let [hex (:fill-color fill)
          op  (:fill-opacity fill)]
      (when (some? hex)
        (cond-> {:fill-color hex}
          (some? op) (assoc :fill-opacity op))))))

(defn color-source-for
  "Pure fn. Return the fill map `{:fill-color <hex> :fill-opacity <n>}` that
  the merged :bool result should carry for the given color-source `mode`,
  or nil.

  A nil return is the signal to the wrap event to fall back to the existing
  internal-rule fill (byte-identical to the pre-feature behavior). This
  happens for `:internal` by design, and for any other mode when the
  chosen source resolves to nothing (nil-safety: e.g. the second shape has
  no solid fill, or the colorpicker has no active color).

  `shapes` is the selection in z-index (topmost-first) order — the same
  vector `dwb/create-bool` builds internally. `active-swatch` is the
  colorpicker's current color object (`{:color <hex> :opacity <n>}`) as
  returned by `dwc/get-color-from-colorpicker-state`. `custom-color` is a
  hex string for the `:custom` mode."
  [mode shapes active-swatch custom-color]
  (case mode
    :internal       nil
    :inherit-first  (normalize-fill (first-solid-fill (first shapes)))
    :inherit-second (normalize-fill (first-solid-fill (second shapes)))
    :active-swatch  (when (some? (:color active-swatch))
                      (cond-> {:fill-color (:color active-swatch)}
                        (some? (:opacity active-swatch))
                        (assoc :fill-opacity (:opacity active-swatch))))
    :custom         (when (some? custom-color)
                      {:fill-color custom-color :fill-opacity 1})
    nil))

(defn cursor-swatch-color
  "Pure fn. Return the color the cursor-swatch preview should show for the
  given color-source `mode`, or nil.

  Same as `color-source-for` except `:internal` (and any nil result) falls
  back to the first selected shape's solid fill so the preview still shows
  something useful when the user is exploring the menu. Returns nil only
  when no shape in the selection carries a solid fill at all."
  [mode shapes active-swatch custom-color]
  (or (color-source-for mode shapes active-swatch custom-color)
      (normalize-fill (first-solid-fill (first shapes)))))

;; ── Wrap event ───────────────────────────────────────────────────────────────

(defn- selection-shapes
  "Build the same `shapes` vector `dwb/create-bool` builds internally for
  the current selection (frames / variants / copy-parents removed, ordered
  by z-index). Returns nil when nothing valid is selected."
  [state]
  (let [objects (dsh/lookup-page-objects state)
        ids     (->> (dsh/get-selected-ids state)
                     (dsh/process-selected objects))
        xform   (comp
                 (map (d/getf objects))
                 (remove cph/frame-shape?)
                 (remove ctc/is-variant?)
                 (remove #(ctn/has-any-copy-parent? objects %)))]
    (->> (cph/order-by-indexed-shapes objects ids)
         (into [] xform)
         (not-empty))))

(defn bool-with-color-event
  "A potok event that wraps the EXISTING `dwb/create-bool` for boolean op
  `op` (one of :union/:difference/:intersection/:exclude/:add) and, when the
  color-source `mode` resolves to a concrete fill, applies that fill to the
  resulting :bool shape.

  Behavior:
    - `mode = :internal` OR resolved fill is nil  -> emit the raw
      `dwb/create-bool` (byte-identical to the pre-feature bool menu).
    - otherwise  -> pre-generate the bool result id, emit
      `dwb/create-bool` with `:force-shape-id`, then `dwsh/update-shapes`
      setting `:fills` on that id, all inside one undo transaction
      (`dwu/start-undo-transaction` + `commit-undo-transaction`).

  `custom-color` is a hex string for the `:custom` mode (ignored
  otherwise). nil-safe: a missing selection / missing swatch / missing
  custom color all fall through to the raw `dwb/create-bool`."
  [op mode custom-color]
  (ptk/reify ::bool-with-color
    ptk/WatchEvent
    (watch [it state _]
      (let [shapes (selection-shapes state)
            swatch (some-> state :colorpicker
                           dwc/get-color-from-colorpicker-state)
            fill   (color-source-for mode shapes swatch custom-color)]
        (if (or (nil? shapes)
                (= mode :internal)
                (nil? fill))
          ;; byte-identical fallback: the exact event the bool menu emits today.
          (rx/of (dwb/create-bool op))
          (let [shape-id (uuid/next)
                undo-id  (uuid/next)]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dwb/create-bool op :force-shape-id shape-id)
                   (dwsh/update-shapes [shape-id]
                                       #(assoc % :fills [fill])
                                       {:attrs        #{:fills}
                                        :reg-objects? true})
                   (dwu/commit-undo-transaction undo-id))))))))