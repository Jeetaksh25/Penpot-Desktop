;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.video
  "Video/GIF playback slot (ALL_APPS_PARITY P2.39): a rect (or any shape
  carrying the slot) whose render emits an HTML5 `<video>` element (or
  `<img>` for an animated GIF) so a prototype can play a video/GIF on the
  canvas. The slot is stored as shape plugin-data under namespace `:ovion`
  key `\"video\"` as a pr-str of:

    {:src      <url-or-data-uri>
     :poster   <url-or-data-uri | nil>
     :loop?    <bool>
     :muted?   <bool>
     :controls? <bool>
     :autoplay? <bool>}

  Absent slot = no video = the shape renders exactly as today
  (byte-identical-when-inactive). The render helper lives in
  `app.main.ui.shapes.video`; the inspector menu lives in
  `ui.workspace.sidebar.options.menus.video`. This data module provides the
  read helper + undo-safe commit events (mirrors motion-effects.cljs)."
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
(def video-key "video")

;; --- Read helper -----------------------------------------------------------

(defn read-video-slot
  "Parse a shape's video slot back into a config map
  `{:src :poster :loop? :muted? :controls? :autoplay?}`. Accepts a shape
  map (reads :plugin-data) or a raw stored string. Returns nil when absent
  or unparsable (nil = no video — the shape renders normally)."
  ([]
   nil)
  ([shape-or-str]
   (let [raw (if (map? shape-or-str)
               (dm/get-in shape-or-str [:plugin-data ovion-namespace video-key])
               shape-or-str)]
     (if (or (nil? raw) (empty? raw))
       nil
       (try
         (reader/read-string raw)
         (catch :default _ nil))))))

(defn- current-video
  [state shape-id]
  (let [page    (dsh/lookup-page state)
        objects (dsh/lookup-page-objects state)
        shape   (get objects shape-id)]
    (if (nil? shape) nil (read-video-slot shape))))

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
                                             video-key
                                             (if (nil? value) "" (pr-str value))))]
        (rx/of (dwu/start-undo-transaction undo-id)
               (dch/commit-changes changes)
               (dwu/commit-undo-transaction undo-id))))))

;; --- Events ----------------------------------------------------------------

(defn set-video-config
  "Set the video config on shape `shape-id`. `config` is
  `{:src :poster :loop? :muted? :controls? :autoplay?}` or nil to clear.
  A config with a nil/empty :src is treated as a clear (no point rendering
  a video with no source)."
  [{:keys [shape-id config]}]
  (ptk/reify ::set-video-config
    ptk/WatchEvent
    (watch [it state _]
      (let [cfg (if (and (map? config) (seq (:src config))) config nil)]
        (commit-plugin-data it state shape-id cfg)))))

(defn clear-video-config
  "Remove the video slot from shape `shape-id`."
  [{:keys [shape-id]}]
  (ptk/reify ::clear-video-config
    ptk/WatchEvent
    (watch [it state _]
      (commit-plugin-data it state shape-id nil))))

(defn add-video-config
  "Add the video slot to shape `shape-id` with `config` (the default
  empty-src config), UNCONDITIONALLY — unlike `set-video-config` this
  does NOT treat an empty :src as a clear, so the inspector 'Add video'
  button actually creates the slot (writing the pr-str'd config map, not
  the empty string) and reveals the full URL/poster/loop/muted/controls/
  autoplay controls. Subsequent edits go through `set-video-config`
  (which clears on empty :src). One undo."
  [{:keys [shape-id config]}]
  (ptk/reify ::add-video-config
    ptk/WatchEvent
    (watch [it state _]
      (let [cfg (if (map? config) config nil)]
        (commit-plugin-data it state shape-id cfg)))))