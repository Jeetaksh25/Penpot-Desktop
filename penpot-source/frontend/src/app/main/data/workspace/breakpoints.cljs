;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.breakpoints
  "ALL_APPS_PARITY P0.15 — Responsive breakpoints data events.

  Each event mutates the selected frame's `:breakpoints` map via
  `dwsh/update-shapes`, which commits its own changes + undo step
  (`save-undo?` defaults true). When the frame has no `:breakpoints` yet
  it is initialized to the default set (desktop / tablet / mobile) before
  the requested mutation is applied. Empty selection → `rx/empty` (mirror
  of `app.main.data.workspace.rotate-copies`)."
  (:require
   [app.common.types.breakpoint :as ctbp]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- selected-frame-ids
  "Return the frame ids from the current selection (frames only)."
  [state]
  (let [page    (dsh/lookup-page state)
        objects (:objects page)
        ids     (dsh/lookup-selected state)]
    (into []
          (filter #(= :frame (get-in objects [% :type])))
          ids)))

(defn- ensure-breakpoints
  "If `shape` has no `:breakpoints`, init to defaults; otherwise leave as-is."
  [shape]
  (if (some? (:breakpoints shape))
    shape
    (assoc shape :breakpoints (ctbp/make-default-breakpoints))))

(defn add-breakpoint
  "Append a breakpoint (`{:width .. :name ..}`) to each selected frame's
  `:breakpoints`. Initializes defaults first when the frame has none."
  [{:keys [width name]}]
  (ptk/reify ::add-breakpoint
    ptk/WatchEvent
    (watch [_ state _]
      (let [ids (selected-frame-ids state)]
        (if (empty? ids)
          (rx/empty)
          (rx/of (dwsh/update-shapes
                  ids
                  (fn [shape]
                    (-> shape
                        ensure-breakpoints
                        (update :breakpoints
                                #(ctbp/add-breakpoint
                                  % (ctbp/make-breakpoint {:width width :name name})))))
                  {:attrs [:breakpoints]})))))))

(defn remove-breakpoint
  "Remove the breakpoint with `:id` (and its overrides) from each
  selected frame."
  [{:keys [id]}]
  (ptk/reify ::remove-breakpoint
    ptk/WatchEvent
    (watch [_ state _]
      (let [ids (selected-frame-ids state)]
        (if (empty? ids)
          (rx/empty)
          (rx/of (dwsh/update-shapes
                  ids
                  (fn [shape]
                    (-> shape
                        ensure-breakpoints
                        (update :breakpoints #(ctbp/remove-breakpoint % id)))))
                  {:attrs [:breakpoints]}))))))

(defn set-breakpoint-override
  "Merge `props` into the frame's `:breakpoints :overrides` for the given
  `:shape-id` + `:breakpoint-id`. A fresh override id is minted when none
  exists for that pair; an existing entry is updated in place."
  [{:keys [shape-id breakpoint-id props]}]
  (ptk/reify ::set-breakpoint-override
    ptk/WatchEvent
    (watch [_ state _]
      (let [ids (selected-frame-ids state)]
        (if (empty? ids)
          (rx/empty)
          (rx/of (dwsh/update-shapes
                  ids
                  (fn [shape]
                    (-> shape
                        ensure-breakpoints
                        (update-in [:breakpoints :overrides]
                                   (fn [overrides]
                                     (let [ov-map  (or overrides {})
                                           existing (some (fn [[oid entry]]
                                                            (when (and (= (:breakpoint-id entry) breakpoint-id)
                                                                       (= (get-in entry [:props :shape-id]) shape-id))
                                                              [oid entry]))
                                                          ov-map)]
                                       (if existing
                                         (let [[oid entry] existing]
                                           (assoc ov-map oid
                                                  (update entry :props #(merge % props))))
                                         (let [oid (uuid/next)]
                                           (assoc ov-map oid
                                                  {:id            oid
                                                   :breakpoint-id breakpoint-id
                                                   :props         (assoc props :shape-id shape-id)}))))))))
                  {:attrs [:breakpoints]})))))))

(defn clear-breakpoint-override
  "Drop the override (if any) for `:shape-id` + `:breakpoint-id` from the
  frame's `:breakpoints :overrides`."
  [{:keys [shape-id breakpoint-id]}]
  (ptk/reify ::clear-breakpoint-override
    ptk/WatchEvent
    (watch [_ state _]
      (let [ids (selected-frame-ids state)]
        (if (empty? ids)
          (rx/empty)
          (rx/of (dwsh/update-shapes
                  ids
                  (fn [shape]
                    (-> shape
                        ensure-breakpoints
                        (update-in [:breakpoints :overrides]
                                   (fn [overrides]
                                     (into {}
                                           (remove (fn [[_oid entry]]
                                                     (and (= (:breakpoint-id entry) breakpoint-id)
                                                          (= (get-in entry [:props :shape-id]) shape-id))))
                                           (or overrides {}))))))
                  {:attrs [:breakpoints]})))))))