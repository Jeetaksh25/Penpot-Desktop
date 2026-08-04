;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.element-states
  "Per-element multi-State system (P2.29) and component hover/pressed
  state overrides (P2.24). Both are additive override layers DECOUPLED
  from the component variant system — they live as shape plugin-data
  under namespace `:ovion`.

  P2.29 element-states: stored on the shape under key `\"element-states\"`
  as a pr-str of `{state-name {prop value}}` with a special `:base` state.
  Non-base states inherit unset props from base (merge base ++ active).
  The active state at runtime is tracked in the viewer slice
  `:viewer-element-state` (shape-id -> state-name), switched by the
  `:set-element-state` interaction action.

  P2.24 state-overrides: stored on a component INSTANCE under key
  `\"state-overrides\"` as a pr-str of `{state {prop value}}` where state
  is in #{:hover :pressed}. At runtime the viewer attaches DOM
  mouseenter/mousedown listeners that apply the overridden props via the
  existing :set-style runtime slice (reusing dv/set-style), restoring on
  leave/up. Kept decoupled from variants — an additive override layer on
  any component instance.

  Authoring: the `states` sidebar menu (ui.workspace.sidebar.options
  .menus.states) hosts both surfaces. This data module provides the
  read/write helpers + undo-safe commit events (mirrors
  data/workspace/collections.cljs + dynamic-panels.cljs)."
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

(def ovion-namespace :ovion)

(def element-states-key "element-states")
(def state-overrides-key "state-overrides")

;; The base state name — special: non-base states inherit unset props from base.
(def base-state :base)

;; Component state-override states (P2.24).
(def override-states #{:hover :pressed})

;; --- Read helpers ----------------------------------------------------------

(defn read-element-states
  "Parse a shape's element-states slot back into a map
  `{state-name {prop value}}`. Accepts a shape map (reads :plugin-data)
  or a raw stored string. Returns {} when absent or unparsable."
  ([]
   {})
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace element-states-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       {}
       (try
         (reader/read-string raw)
         (catch :default _ {}))))))

(defn read-state-overrides
  "Parse a component instance's state-overrides slot back into a map
  `{state {prop value}}` where state is :hover/:pressed. Returns {} when
  absent or unparsable."
  ([]
   {})
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace state-overrides-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       {}
       (try
         (reader/read-string raw)
         (catch :default _ {}))))))

(defn merge-state
  "Merge a named state's props over the base state's props, modelling
  the base->all propagation: non-base states inherit unset props from
  base. Returns the merged `{prop value}` map for `state-name`. When
  `state-name` is :base (or absent) returns the base props alone."
  [states state-name]
  (let [base  (get states base-state)
        named (get states state-name)]
    (if (or (nil? state-name) (= state-name base-state))
      (or base {})
      (merge base named))))

;; --- Internal commit helper (shared) --------------------------------------

(defn- current-states*
  [state shape-id]
  (let [page    (dsh/lookup-page state)
        objects (dsh/lookup-page-objects state)
        shape   (get objects shape-id)]
    (if (nil? shape) {} (read-element-states shape))))

(defn- current-overrides*
  [state shape-id]
  (let [page    (dsh/lookup-page state)
        objects (dsh/lookup-page-objects state)
        shape   (get objects shape-id)]
    (if (nil? shape) {} (read-state-overrides shape))))

(defn- commit-plugin-data
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
                                             ovion-namespace
                                             key
                                             (pr-str value)))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- P2.29 element-states events -------------------------------------------

(defn add-element-state
  "Add a named state to shape `shape-id`. The new state starts empty —
  it inherits everything from base until the author overrides props.
  Idempotent: a duplicate name is a no-op. The :base state is seeded
  automatically on first use."
  [{:keys [shape-id name]}]
  (ptk/reify ::add-element-state
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states* state shape-id)
            states (if (not (contains? states base-state))
                     (assoc states base-state {})
                     states)
            nm     (or name "State 1")]
        (if (contains? states nm)
          (rx/empty)
          (commit-plugin-data it state shape-id element-states-key
                               (assoc states nm {})))))))

(defn remove-element-state
  "Remove a named state from shape `shape-id`. The :base state cannot
  be removed (no-op)."
  [{:keys [shape-id name]}]
  (ptk/reify ::remove-element-state
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states* state shape-id)]
        (if (or (= name base-state) (not (contains? states name)))
          (rx/empty)
          (commit-plugin-data it state shape-id element-states-key
                               (dissoc states name)))))))

(defn set-element-state-props
  "Set the full prop map for state `name` on shape `shape-id`. `props`
  is a map `{prop value}` (the authoring menu writes one prop at a
  time, building up this map)."
  [{:keys [shape-id name props]}]
  (ptk/reify ::set-element-state-props
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states* state shape-id)]
        (if (not (contains? states name))
          (rx/empty)
          (commit-plugin-data it state shape-id element-states-key
                               (assoc states name props)))))))

(defn update-element-state-prop
  "Set a single `prop` -> `value` on state `name` for shape `shape-id`.
  When `value` is nil the prop is removed from that state (so it falls
  back to base)."
  [{:keys [shape-id name prop value]}]
  (ptk/reify ::update-element-state-prop
    ptk/WatchEvent
    (watch [it state _]
      (let [states (current-states* state shape-id)]
        (if (not (contains? states name))
          (rx/empty)
          (let [cur    (get states name)
                props  (if (nil? value)
                         (dissoc cur prop)
                         (assoc cur prop value))]
            (commit-plugin-data it state shape-id element-states-key
                                 (assoc states name props))))))))

;; --- P2.24 component state-overrides events --------------------------------

(defn set-state-override
  "Set a single `prop` -> `value` override for `state` (#{:hover :pressed})
  on component instance `shape-id`. When `value` is nil the prop is
  removed from that state's override map."
  [{:keys [shape-id state prop value]}]
  (ptk/reify ::set-state-override
    ptk/WatchEvent
    (watch [it state* _]
      (let [ovs (current-overrides* state* shape-id)]
        (if (not (contains? override-states state))
          (rx/empty)
          (let [cur   (get ovs state)
                ovs'  (if (nil? value)
                        (assoc ovs state (dissoc cur prop))
                        (assoc ovs state (assoc cur prop value)))]
            (commit-plugin-data it state* shape-id state-overrides-key ovs')))))))

(defn clear-state-overrides
  "Remove all state-overrides from component instance `shape-id`."
  [{:keys [shape-id]}]
  (ptk/reify ::clear-state-overrides
    ptk/WatchEvent
    (watch [it state _]
      (commit-plugin-data it state shape-id state-overrides-key {}))))