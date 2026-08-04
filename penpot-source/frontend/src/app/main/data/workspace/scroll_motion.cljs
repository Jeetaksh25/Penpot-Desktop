;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.scroll-motion
  "Scroll-triggered motion slots (ALL_APPS_PARITY P1.34):

  - `path-draw` on a path shape: `{:duration ms :direction :forward/:reverse}`
    → the path's stroke is animated from hidden to fully drawn as it
    scrolls into view (stroke-dasharray/dashoffset tween via GSAP).

  - `scroll-video` on a video shape: `{:trigger :scrub/:in-view
    :start :end}` → the `<video>` currentTime is driven by scroll position
    (scrubbing) OR plays when in view + pauses when out.

  Both slots are stored as shape plugin-data under namespace `:ovion`.
  Absent slot = the existing render (byte-identical-when-inactive). The
  viewer runtime (ui.viewer.shapes generic-wrapper) reads these and wires
  IntersectionObserver / scroll listeners + GSAP, reduced-motion guarded
  (see ui.workspace.ai-motion). This module provides read helpers + the
  slot constants. Commit events mirror video.cljs / motion-effects.cljs."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [potok.v2.core :as ptk]))

(def ovion-namespace :ovion)
(def path-draw-key "path-draw")
(def scroll-video-key "scroll-video")

(def path-draw-triggers #{:scrub :in-view})

;; --- Read helpers ----------------------------------------------------------

(defn- read-slot
  [shape-or-str key]
  (let [raw (if (map? shape-or-str)
              (dm/get-in shape-or-str [:plugin-data ovion-namespace key])
              shape-or-str)]
    (if (or (nil? raw) (empty? raw))
      nil
      (try
        (reader/read-string raw)
        (catch :default _ nil)))))

(defn read-path-draw
  "Parse a shape's path-draw slot → `{:duration ms :direction :forward/:reverse}`
  or nil when absent/unparsable."
  ([]
   nil)
  ([shape]
   (read-slot shape path-draw-key)))

(defn read-scroll-video
  "Parse a shape's scroll-video slot → `{:trigger :scrub/:in-view :start :end}`
  or nil when absent/unparsable."
  ([]
   nil)
  ([shape]
   (read-slot shape scroll-video-key)))

;; --- Commit helper (shared) ------------------------------------------------

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
                                             (if (nil? value) "" (pr-str value))))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn set-path-draw
  "Set the path-draw config on shape `shape-id`. `config` is
  `{:duration ms :direction :forward/:reverse}` or nil to clear."
  [{:keys [shape-id config]}]
  (ptk/reify ::set-path-draw
    ptk/WatchEvent
    (watch [it state _]
      (commit-plugin-data it state shape-id path-draw-key config))))

(defn clear-path-draw
  [{:keys [shape-id]}]
  (ptk/reify ::clear-path-draw
    ptk/WatchEvent
    (watch [it state _]
      (commit-plugin-data it state shape-id path-draw-key nil))))

(defn set-scroll-video
  "Set the scroll-video config on shape `shape-id`. `config` is
  `{:trigger :scrub/:in-view :start :end}` or nil to clear."
  [{:keys [shape-id config]}]
  (ptk/reify ::set-scroll-video
    ptk/WatchEvent
    (watch [it state _]
      (commit-plugin-data it state shape-id scroll-video-key config))))

(defn clear-scroll-video
  [{:keys [shape-id]}]
  (ptk/reify ::clear-scroll-video
    ptk/WatchEvent
    (watch [it state _]
      (commit-plugin-data it state shape-id scroll-video-key nil))))