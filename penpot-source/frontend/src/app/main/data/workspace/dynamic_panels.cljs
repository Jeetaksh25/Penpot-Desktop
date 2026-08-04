;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.dynamic-panels
  "Dynamic Panels (P1.14): a generic N-state container decoupled from the
  component system. Any frame can carry named panel states; each state is
  a map of state-name -> list of child-shape-ids that are VISIBLE in that
  state (all other children are hidden when the state is active).

  Storage: shape plugin-data under namespace `:ovion` key `\"panel-states\"`,
  serialized with `pr-str` to a map `{state-name [child-id ...]}`. The
  active state at runtime is tracked in the viewer slice
  `:viewer-panel-state` (frame-id -> state-name), switched by the
  `:set-panel-state` interaction action (see viewer/shapes.cljs dispatch).

  Authoring: the `dynamic-panels` sidebar menu (ui.workspace.sidebar
  .options.menus.dynamic-panels) lets a user add named states to a
  selected frame and toggle which children are visible per state. This
  data module provides the read/write helpers + the undo-safe commit
  events that persist through the changes pipeline (mirrors
  data/workspace/collections.cljs for shape plugin-data)."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

;; --- Plugin-data slot constants --------------------------------------------

(def panel-namespace
  "Plugin-data namespace keyword under which dynamic-panel data is stored
  on the frame shape. Schema:plugin-data namespaces are keywords."
  :ovion)

(def panel-key
  "Plugin-data key (string) under `panel-namespace` for the panel-states
  map. Value is a pr-str of `{state-name [child-id ...]}`."
  "panel-states")

(def active-key
  "Plugin-data key (string) for the frame's default/active state-name at
  design time (the state shown when the panel is first rendered). The
  viewer slice `:viewer-panel-state` overrides this at runtime."
  "panel-active-state")

;; --- Read / write helpers --------------------------------------------------

(defn read-panel-states
  "Parse a frame's plugin-data panel-states slot back into a map
  `{state-name [child-id ...]}`. Accepts either a shape map (reads
  `:plugin-data`) or a raw stored string. Returns {} when absent or
  unparsable."
  ([]
   {})
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data panel-namespace panel-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       {}
       (try
         (reader/read-string raw)
         (catch :default _ {}))))))

(defn read-active-state
  "Read the frame's design-time active state-name (the state shown when
  the panel first renders), or nil when unset."
  [shape]
  (when (map? shape)
    (let [raw (dm/get-in shape [:plugin-data panel-namespace active-key])]
      (when (seq raw)
        (try
          (reader/read-string raw)
          (catch :default _ nil))))))

(defn- write-data
  [value]
  (pr-str value))

(defn- current-states
  "Read the current shape's panel-states from its plugin-data slot."
  [state shape-id]
  (let [page   (dsh/lookup-page state)
        objects (dsh/lookup-page-objects state (:current-page-id state))
        shape  (get objects shape-id)]
    (if (nil? shape) {} (read-panel-states shape))))

(defn- commit-shape-plugin-data
  "Build and commit a changeset that writes `value` to the shape's
  plugin-data slot under `panel-namespace`/`key`, inside one undo
  transaction. Returns an rx stream of potok events (or rx/empty when
  the shape is nil)."
  [it state shape-id key value]
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
                                             panel-namespace
                                             key
                                             (write-data value)))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn add-panel-state
  "Add a named state to frame `shape-id`. The new state starts empty
  (no children visible) — the author selects children per state in the
  menu. Idempotent: a duplicate name is a no-op."
  [{:keys [shape-id name]}]
  (ptk/reify ::add-panel-state
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states state shape-id)
            nm    (or name "State 1")]
        (if (contains? states nm)
          (rx/empty)
          (commit-shape-plugin-data it state shape-id panel-key
                                    (assoc states nm [])))))))

(defn remove-panel-state
  "Remove a named state from frame `shape-id`."
  [{:keys [shape-id name]}]
  (ptk/reify ::remove-panel-state
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states state shape-id)]
        (commit-shape-plugin-data it state shape-id panel-key
                                  (dissoc states name))))))

(defn rename-panel-state
  "Rename a state `old-name` to `new-name` on frame `shape-id`."
  [{:keys [shape-id old-name new-name]}]
  (ptk/reify ::rename-panel-state
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states state shape-id)]
        (if (or (contains? states new-name) (not (contains? states old-name)))
          (rx/empty)
          (let [kids    (get states old-name)
                without (dissoc states old-name)
                states' (assoc without new-name kids)]
            (commit-shape-plugin-data it state shape-id panel-key states')))))))

(defn set-state-children
  "Set the list of visible child-shape-ids for state `name` on frame
  `shape-id`. `child-ids` is a vector of uuids (the children visible in
  that state)."
  [{:keys [shape-id name child-ids]}]
  (ptk/reify ::set-state-children
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states state shape-id)]
        (commit-shape-plugin-data it state shape-id panel-key
                                  (assoc states name (vec child-ids)))))))

(defn set-active-panel-state
  "Set the frame's design-time active state (the state shown when the
  panel first renders, before any :set-panel-state action fires)."
  [{:keys [shape-id name]}]
  (ptk/reify ::set-active-panel-state
    ptk/WatchEvent
    (watch [it state _]
      (commit-shape-plugin-data it state shape-id active-key name))))

(defn make-panel
  "Initialize a frame as a dynamic panel with a single default `:base`
  state listing all current children as visible. Idempotent: if the
  frame already carries panel-states, this is a no-op."
  [{:keys [shape-id]}]
  (ptk/reify ::make-panel
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states state shape-id)]
        (if (seq states)
          (rx/empty)
          (let [page    (dsh/lookup-page state)
                objects (dsh/lookup-page-objects state (:current-page-id state))
                shape   (get objects shape-id)
                children (or (:shapes shape) [])
                base (into [] (keep #(when (uuid? %) %)) children)]
            (rx/concat
             (commit-shape-plugin-data it state shape-id panel-key
                                        {:base base})
             (commit-shape-plugin-data it state shape-id active-key :base))))))))