;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.motion-effects
  "Effects system (P1.16): Appear, Loop, and Drag as first-class
  design-time effect types, stored as shape plugin-data and rendered
  via GSAP/AnimeJS in the viewer (see ui.viewer.shapes motion-effect
  runtime + ui.workspace.ai-motion helpers).

  Each effect is a small config map stored on the shape under namespace
  `:ovion` key `\"motion-effect\"` as a pr-str of:
    {:type <appear|loop|drag> :config {...}}
  Appear: {:direction :left/:right/:up/:down/:fade/:scale
           :duration ms :delay ms}
  Loop:   {:kind :rotate/:pulse/:slide :duration ms}
  Drag:   {:axis :x/:y/:both :constraint :none/:lock}

  The viewer reads the slot and runs the corresponding GSAP/AnimeJS
  timeline on the shape's DOM node (reduced-motion guarded — disabled
  under prefers-reduced-motion). The `effects` sidebar menu
  (ui.workspace.sidebar.options.menus.effects) authors it on a
  selected shape. This data module provides the read/write helpers +
  undo-safe commit events (mirrors dynamic-panels.cljs)."
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
(def motion-effect-key "motion-effect")

(def effect-types #{:appear :loop :drag})

;; --- Read helper -----------------------------------------------------------

(defn read-motion-effect
  "Parse a shape's motion-effect slot back into a config map
  `{:type ... :config {...}}`. Accepts a shape map (reads :plugin-data)
  or a raw stored string. Returns nil when absent or unparsable (nil =
  no effect — the viewer renders the shape normally)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace motion-effect-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (reader/read-string raw)
         (catch :default _ nil))))))

(defn- current-effect
  [state shape-id]
  (let [page    (dsh/lookup-page state)
        objects (dsh/lookup-page-objects state)
        shape   (get objects shape-id)]
    (if (nil? shape) nil (read-motion-effect shape))))

(defn- commit-plugin-data
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
                                             motion-effect-key
                                             (if (nil? value) "" (pr-str value))))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn set-motion-effect
  "Set the motion-effect config on shape `shape-id`. `effect` is
  `{:type ... :config {...}}` or nil to clear. Validates :type against
  effect-types; an invalid type is a no-op."
  [{:keys [shape-id effect]}]
  (ptk/reify ::set-motion-effect
    ptk/WatchEvent
    (watch [it state _]
      (let [t (:type effect)]
        (if (and (some? effect) (not (contains? effect-types t)))
          (rx/empty)
          (commit-plugin-data it state shape-id effect))))))

(defn clear-motion-effect
  "Remove the motion-effect from shape `shape-id`."
  [{:keys [shape-id]}]
  (ptk/reify ::clear-motion-effect
    ptk/WatchEvent
    (watch [it state _]
      (commit-plugin-data it state shape-id nil))))