;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.tools
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.changes :as changes]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn process-path-tool
  "Generic function that executes path transformations with the content and selected nodes"
  ([tool-fn]
   (process-path-tool nil tool-fn))
  ([points tool-fn]
   (ptk/reify ::process-path-tool
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id (get state :current-page-id)
             objects (dsh/lookup-page-objects state page-id)

             shape   (st/get-path state)
             id      (st/get-path-id state)

             selected-points
             (dm/get-in state [:workspace-local :edit-path id :selected-points] #{})

             points
             (or points selected-points)]

         (when (and (seq points) (some? shape))
           (let [new-content
                 (-> (tool-fn (:content shape) points)
                     (path/close-subpaths))

                 changes
                 (changes/generate-path-changes it objects page-id shape (:content shape) new-content)]

             (rx/concat
              (rx/of (dwsh/update-shapes [id] path/convert-to-path)
                     (dch/commit-changes changes))
              (when (empty? new-content)
                (rx/of (dwe/clear-edition-mode)))))))))))

(defn make-corner
  ([]
   (make-corner nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (reduce path/make-corner-point content points)))))

(defn make-curve
  ([]
   (make-curve nil))
  ([point]
   (process-path-tool
    (when point #{point})
    (fn [content points]
      (reduce path/make-curve-point content points)))))

(defn add-node []
  (process-path-tool (fn [content points] (path/split-segments content points 0.5))))

(defn remove-node []
  (process-path-tool path/remove-nodes))

(defn merge-nodes []
  (process-path-tool path/merge-nodes))

(defn join-nodes []
  (process-path-tool path/join-nodes))

(defn separate-nodes []
  (process-path-tool path/separate-nodes))

(defn toggle-snap []
  (ptk/reify ::toggle-snap
    ptk/UpdateEvent
    (update [_ state]
      (let [id (st/get-path-id state)]
        (update-in state [:workspace-local :edit-path id :snap-toggled] not)))))

;; ALL_APPS_PARITY P2.18 — explicit 4 vector point-type system. Sets the
;; point-type of every selected node: adjusts handle geometry (pure CLJC,
;; see path/set-point-type-point) AND persists the explicit type in the
;; shape's :point-types map (keyed by rounded coordinate) so the Inspector
;; reflects the user's choice between edits. Mirrors the process-path-tool
;; commit pipeline but adds a shape :point-types update alongside the
;; content change.
(defn- point-type-key
  "Stable string key for a path node, used as the :point-types map key.
  Rounded to 0.01 so the same coordinate yields the same key despite
  float drift."
  [p]
  (dm/str (mth/round (dm/get-prop p :x) 0.01) "," (mth/round (dm/get-prop p :y) 0.01)))

(defn set-point-type
  ([]
   (set-point-type nil))
  ([ptype]
   (ptk/reify ::set-point-type
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id (get state :current-page-id)
             objects (dsh/lookup-page-objects state page-id)
             shape   (st/get-path state)
             id      (st/get-path-id state)
             selected-points (dm/get-in state [:workspace-local :edit-path id :selected-points] #{})
             points  (not-empty selected-points)]
         (when (and (seq points) (some? shape) (some? ptype))
           (let [ptype-k     (keyword ptype)
                 old-content (:content shape)
                 new-content (-> (reduce #(path/set-point-type-point %1 %2 ptype-k)
                                         old-content points)
                                 (path/close-subpaths))
                 new-pt-types (as-> (get shape :point-types {}) pt
                               (reduce #(assoc %1 (point-type-key %2) ptype-k) pt points))
                 changes     (-> (changes/generate-path-changes it objects page-id shape old-content new-content)
                                 (pcb/update-shapes [id] (fn [s] (assoc s :point-types new-pt-types))))]
             (rx/concat
              (rx/of (dwsh/update-shapes [id] path/convert-to-path)
                     (dch/commit-changes changes))))))))))
