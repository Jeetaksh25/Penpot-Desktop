;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.connectors
  "Connectors (P2.14): visual layer-to-layer relationship / flow lines
  independent of prototype navigation. A connector is a managed :path
  shape carrying plugin-data `:ovion`/`\"connector\"` = (pr-str
  {:from <shape-id> :to <shape-id> :style {:color :width :dash
  :arrow-end? :orthogonal?}}). The connector re-renders its path
  geometry whenever either endpoint layer moves — see `reconnect-all`,
  emitted as a follow-up from the transform commit chokepoint
  (`app.main.data.workspace.modifiers/apply-modifiers*`).

  Byte-identical-when-inactive: shapes with no connector plugin-data
  render exactly as today. The feature is purely additive — a new tool,
  a per-shape inspector menu, and a read-only reconnect pass that is a
  no-op (rx/empty) when no connector references a moved id. No new core
  shape type is registered; a connector is just a :path with metadata."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def ovion-namespace :ovion)
(def connector-key "connector")

(def ^:private default-style
  {:color "#7d7d7d"
   :width 1
   :dash :solid
   :arrow-end? false
   :orthogonal? false})

(def dash-options #{:solid :dashed :dotted})

;; --- Read / write helpers ---------------------------------------------------

(defn read-connector
  "Parse a shape's connector slot back into a map
  `{:from <id> :to <id> :style {...}}`. Accepts a shape map (reads
  :plugin-data) or a raw stored string. Returns nil when absent or
  unparsable (nil = not a connector — the shape renders normally)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace connector-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (reader/read-string raw)
         (catch :default _ nil))))))

(defn connector?
  "True when `shape` carries the connector plugin-data slot."
  [shape]
  (some? (read-connector shape)))

(defn- write-connector-data
  [data]
  (pr-str data))

;; --- Geometry ---------------------------------------------------------------

(defn- absolute-origin
  "Sum of `:x`/`:y` of every ancestor's selrect — the absolute (page-root)
  offset of `shape`'s parent. Nil-safe. Correct for non-rotated frame
  hierarchies (the dominant case); rotated ancestors are approximated.
  Used so a connector attached to the page root can address endpoint
  centers in absolute coordinates."
  [objects shape]
  (loop [pid  (:parent-id shape)
         ax   0.0
         ay   0.0]
    (if (or (nil? pid) (= pid uuid/zero) (nil? (get objects pid)))
      (gpt/point ax ay)
      (let [parent (get objects pid)
            sr     (:selrect parent)]
        (recur (:parent-id parent)
               (+ ax (double (:x sr)))
               (+ ay (double (:y sr))))))))

(defn- absolute-center
  "Absolute (page-root) center of `shape`, by adding the parent chain's
  accumulated origin to the shape's selrect center (which is in parent-
  local space). Nil-safe (returns nil when shape is nil)."
  [objects shape]
  (when (some? shape)
    (let [local (gsh/shape->center shape)
          origin (absolute-origin objects shape)]
      (gpt/point (+ (double (:x local)) (double (:x origin)))
                 (+ (double (:y local)) (double (:y origin)))))))

(defn- orthogonal-content
  "Build a 3-point L-shaped (orthogonal elbow) path content from
  `from` to `to`. The elbow turns at (to.x, from.y) — a horizontal-then
  vertical route. Returns a path content vector."
  [from to]
  (let [corner (gpt/point (:x to) (:y from))]
    (path/points->content [from corner to])))

(defn connector->geometry
  "Pure function returning the path geometry for a connector between
  `from-shape` and `to-shape`. Returns a map `{:content :selrect
  :points}` suitable for placing on a :path shape, or nil if either
  shape is missing. Honors `:orthogonal?` from the optional `style`
  map to switch between a straight 2-point line and an L-shaped elbow.

  This 2/3-arity form uses the shapes' `:selrect` centers directly —
  i.e. it assumes both endpoints already share a common coordinate
  space (e.g. same parent frame). For the cross-frame case use
  `connector->geometry-absolute`, which projects centers to page-root
  coordinates via the parent-chain walk."
  ([from-shape to-shape]
   (connector->geometry from-shape to-shape nil))
  ([from-shape to-shape {:keys [orthogonal?]}]
   (when (and (some? from-shape) (some? to-shape))
     (let [from-c (gsh/shape->center from-shape)
           to-c   (gsh/shape->center to-shape)]
       (when (and (some? from-c) (some? to-c))
         (let [content (if orthogonal?
                         (orthogonal-content from-c to-c)
                         (path/points->content [from-c to-c]))
               selrect (path/calc-selrect content)
               points  (grc/rect->points selrect)]
           {:content content
            :selrect selrect
            :points  points}))))))

(defn connector->geometry-absolute
  "Geometry fn for the cross-frame case: projects the endpoint centers
  to absolute (page-root) coordinates via `objects`'s parent-chain
  walk, so a connector attached to the page root (parent uuid/zero)
  addresses both endpoints regardless of their frame. Returns
  `{:content :selrect :points}` or nil when either shape is missing."
  ([objects from-shape to-shape]
   (connector->geometry-absolute objects from-shape to-shape nil))
  ([objects from-shape to-shape {:keys [orthogonal?]}]
   (when (and (some? from-shape) (some? to-shape))
     (let [from-c (absolute-center objects from-shape)
           to-c   (absolute-center objects to-shape)]
       (when (and (some? from-c) (some? to-c))
         (let [content (if orthogonal?
                         (orthogonal-content from-c to-c)
                         (path/points->content [from-c to-c]))
               selrect (path/calc-selrect content)
               points  (grc/rect->points selrect)]
           {:content content
            :selrect selrect
            :points  points}))))))

;; --- Connector shape construction ------------------------------------------

(defn- connector-strokes
  "Build the stroke vector for a connector from its `style` map."
  [{:keys [color width dash arrow-end?]}]
  (let [color (or color (:color default-style))
        width (or width (:width default-style))
        dash  (or dash (:dash default-style))]
    [(cond-> {:stroke-color color
              :stroke-opacity 1
              :stroke-style dash
              :stroke-alignment :center
              :stroke-width width}
       arrow-end? (assoc :stroke-cap-end :triangle-arrow))]))

(defn- build-connector-shape
  "Build a :path shape for a connector between `from-id` and `to-id`
  using `objects` to project endpoint centers to absolute coords. The
  shape is attached to the page root (parent uuid/zero). Returns the
  setup shape (with :content/:selrect/:points derived) or nil when the
  geometry cannot be computed."
  [objects from-id to-id style]
  (let [from-shape (get objects from-id)
        to-shape   (get objects to-id)
        geom       (connector->geometry-absolute objects from-shape to-shape style)]
    (when (some? geom)
      (cts/setup-shape
       {:type :path
        :name "Connector"
        :initialized? true
        :frame-id uuid/zero
        :parent-id uuid/zero
        :strokes (connector-strokes style)
        :fills []
        :content (:content geom)
        :selrect (:selrect geom)
        :points (:points geom)}))))

;; --- Events: create / delete / style ---------------------------------------

(defn create-connector
  "Create a connector :path shape between `from-id` and `to-id`, stamped
  with `:ovion`/`\"connector\"` plugin-data, as a sibling on the page
  root. One undo transaction. No-op (rx/empty) when either endpoint is
  missing or the geometry cannot be computed."
  [{:keys [from-id to-id style]}]
  (ptk/reify ::create-connector
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            file-id   (:current-file-id state)
            page      (dsh/lookup-page state)
            file-data (dsh/lookup-file-data state file-id)
            objects   (dsh/lookup-page-objects state page-id)
            from-shape (get objects from-id)
            to-shape   (get objects to-id)]
        (if (or (nil? page) (nil? from-shape) (nil? to-shape)
                (= from-id to-id))
          (rx/empty)
          (let [style-map (merge default-style style)
                shape     (build-connector-shape objects from-id to-id style-map)]
            (if (nil? shape)
              (rx/empty)
              (let [data     {:from from-id :to to-id :style style-map}
                    undo-id  (js/Symbol)
                    [_ changes]
                    (-> (pcb/empty-changes it page-id)
                        (pcb/with-file-data file-data)
                        (pcb/with-page page)
                        (pcb/with-objects objects)
                        (cfsh/prepare-add-shape shape objects))
                    changes
                    (pcb/set-plugin-data changes :shape
                                          (:id shape) page-id
                                          ovion-namespace connector-key
                                          (write-connector-data data))]
                (rx/of (dwu/start-undo-transaction undo-id)
                       (dch/commit-changes changes)
                       (dwu/commit-undo-transaction undo-id))))))))))

(defn delete-connector
  "Delete the connector shape `shape-id`. No-op when the shape is not a
  connector. Undo is managed by `dwsh/delete-shapes`."
  [{:keys [shape-id]}]
  (ptk/reify ::delete-connector
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            shape   (get objects shape-id)]
        (if (or (nil? shape) (not (connector? shape)))
          (rx/empty)
          ;; `dwsh/delete-shapes` expects a set of uuids and manages its
          ;; own undo transaction — no wrapper needed here.
          (rx/of (dwsh/delete-shapes #{shape-id})))))))

(defn set-connector-style
  "Update the `:style` map of connector `shape-id`. `style-update` is
  merged into the existing style. Also re-applies the stroke vector to
  the path so the change is immediately visible. No-op when the shape
  is not a connector. One undo transaction."
  [{:keys [shape-id style-update]}]
  (ptk/reify ::set-connector-style
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            file-id   (:current-file-id state)
            page      (dsh/lookup-page state)
            file-data (dsh/lookup-file-data state file-id)
            objects   (dsh/lookup-page-objects state page-id)
            shape     (get objects shape-id)
            conn      (read-connector shape)]
        (if (or (nil? conn) (nil? page))
          (rx/empty)
          (let [from-id   (:from conn)
                to-id     (:to conn)
                old-style (:style conn)
                new-style (merge old-style style-update)
                new-data  (assoc conn :style new-style)
                strokes   (connector-strokes new-style)
                undo-id   (js/Symbol)
                changes   (-> (pcb/empty-changes it page-id)
                              (pcb/with-file-data file-data)
                              (pcb/with-page page)
                              (pcb/with-objects objects)
                              (pcb/update-shapes [shape-id]
                                                 (fn [s]
                                                   (assoc s :strokes strokes)))
                              (pcb/set-plugin-data :shape
                                                   shape-id page-id
                                                   ovion-namespace connector-key
                                                   (write-connector-data new-data)))]
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (dwu/commit-undo-transaction undo-id))))))))

;; --- Reconnect on move (the re-renders-on-layer-move hook) -----------------

(defn- connectors-referencing
  "Return a vector of `[connector-id connector-data]` pairs for every
  connector on the current page whose :from or :to is in `moved-ids`.
  Nil-safe and empty when none match — the caller's reconnect pass is
  then a pure no-op."
  [objects moved-ids]
  (let [moved-set (into #{} (filter some?) moved-ids)]
    (if (empty? moved-set)
      []
      (into []
            (keep (fn [[id shape]]
                    (let [conn (read-connector shape)]
                      (when (and (some? conn)
                                 (or (contains? moved-set (:from conn))
                                     (contains? moved-set (:to conn))))
                        [id conn]))))
            objects))))

(defn reconnect-all
  "Recompute geometry for every connector whose :from or :to equals one
  of `moved-ids`, updating each connector path's :content/:selrect/
  :points via a single `dwsh/update-shapes` commit (no separate undo
  entry — folds into the surrounding transform's undo transaction).

  This is the re-renders-on-layer-move hook, emitted as a follow-up
  from `apply-modifiers*` after every transform commit. It is a no-op
  (rx/empty) when no connector references any moved id, so non-
  connector documents are byte-identical-when-inactive."
  [moved-ids]
  (ptk/reify ::reconnect-all
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            affected (connectors-referencing objects moved-ids)]
        (if (empty? affected)
          (rx/empty)
          (let [conn-ids (mapv first affected)
                conn-by-id (into {} affected)
                update-fn
                (fn [shape]
                  (let [conn (or (read-connector shape)
                                 (get conn-by-id (:id shape)))
                        from-shape (get objects (:from conn))
                        to-shape   (get objects (:to conn))
                        geom       (connector->geometry-absolute
                                    objects from-shape to-shape
                                    (:style conn))]
                    (if (nil? geom)
                      shape
                      (-> shape
                          (assoc :content (:content geom))
                          (assoc :selrect (:selrect geom))
                          (assoc :points  (:points geom))))))]
            ;; save-undo? false → no new undo point; folds into the
            ;; enclosing move/resize undo transaction.
            (rx/of (dwsh/update-shapes conn-ids update-fn
                                        {:save-undo? false
                                         :translation? true
                                         :update-layout? false}))))))))

;; --- Connector tool: click-A then click-B ----------------------------------
;;
;; The connector tool is click-based (no drag), so it cannot route
;; through the press-and-drag `start-drawing` flow (which would lock
;; after the first click and swallow the second). Instead the viewport
;; pointer-down handler (`ui.workspace.viewport.actions/on-pointer-down`)
;; intercepts `:connector` directly: it already holds the hovered shape
;; id (`id`), and emits `connector-click` with that id. The tool stays
;; active across multiple connectors (select-for-drawing preserves the
;; tool), so the user can draw connector after connector.

(defn- connector-first-id
  "Read the connector tool's pending first-click id from workspace-local
  state, or nil."
  [state]
  (dm/get-in state [:workspace-local :connector-tool-first]))

(defn- set-connector-first
  "Store the connector tool's pending first-click id."
  [id]
  (ptk/reify ::set-connector-first
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :connector-tool-first] id))))

(defn- clear-connector-first
  "Clear the connector tool's pending first-click id."
  []
  (ptk/reify ::clear-connector-first
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-local dissoc :connector-tool-first))))

(defn connector-click
  "Handle a single canvas click while the connector tool is active.
  `shape-id` is the hovered shape id under the cursor (provided by the
  viewport pointer-down handler). If no first endpoint is pending,
  store it; if a first endpoint is pending and the clicked shape
  differs, emit `create-connector` and clear the pending state. A
  second click on the same shape cancels the pending selection. No-op
  when `shape-id` is nil."
  [{:keys [shape-id]}]
  (ptk/reify ::connector-click
    ptk/WatchEvent
    (watch [_ state _]
      (if (nil? shape-id)
        (rx/empty)
        (let [first-id (connector-first-id state)]
          (cond
            (nil? first-id)
            (rx/of (set-connector-first shape-id))
            (= first-id shape-id)
            (rx/of (clear-connector-first))
            :else
            (rx/of (create-connector {:from-id first-id :to-id shape-id})
                   (clear-connector-first))))))))