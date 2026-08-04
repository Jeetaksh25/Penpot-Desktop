;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.breakpoint
  "ALL_APPS_PARITY P0.15 — Responsive breakpoints.

  A breakpoint describes a target viewport width (and optionally height)
  for a frame. The frame stores a `:breakpoints` map of shape:

      {:items     [{:id .. :name .. :width .. :base? true} ...]
       :overrides {<override-id> {:id .. :breakpoint-id .. :props {..}}}}

  Per-breakpoint style overrides are written into `:overrides` keyed by a
  minted override id; `:props` is a map of the changed shape properties
  that apply only while the given breakpoint is active. Pure schemas +
  helper fns; no IO. Mirrors the `app.common.types.grid` style."
  (:require
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:breakpoint
  [:map {:title "Breakpoint" :closed true}
   [:id ::sm/uuid]
   [:name :string]
   [:width ::sm/int]
   [:height {:optional true} ::sm/int]
   [:base? {:optional true} :boolean]])

(def schema:breakpoint-override
  [:map {:title "BreakpointOverride" :closed true}
   [:id ::sm/uuid]
   [:breakpoint-id ::sm/uuid]
   [:props [:map-of :keyword :any]]])

(def schema:breakpoints
  [:map {:title "Breakpoints" :closed true}
   [:items [:vector schema:breakpoint]]
   [:overrides {:optional true} [:map-of ::sm/uuid schema:breakpoint-override]]])

(def check-breakpoints
  (sm/check-fn schema:breakpoints))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFAULTS & HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; NOTE: ids are minted inside the factory fn (`make-default-breakpoints`)
;; rather than inlined in a `def` — random ids at load time are not allowed
;; (they would differ on every load and break transit round-trips).

(defn make-breakpoint
  "Build a breakpoint map from a width + name (mints a fresh id)."
  [{:keys [width name height base?]}]
  {:id      (uuid/next)
   :name    (or name (str width "px"))
   :width   (int width)
   :base?   (boolean base?)})

(defn make-default-breakpoints
  "Mint the 3 default breakpoints (desktop base / tablet / mobile) with
  fresh ids. Called lazily by the UI / data events so ids are stable per
  session, not at load time."
  []
  {:items
   [(make-breakpoint {:width 1440 :name "Desktop" :base? true})
    (make-breakpoint {:width 768  :name "Tablet"})
    (make-breakpoint {:width 375  :name "Mobile"})]
   :overrides {}})

(defn add-breakpoint
  "Append `bp` (a breakpoint map) to the breakpoints collection. Returns
  the updated breakpoints (init to defaults when nil)."
  [breakpoints bp]
  (let [bps (or breakpoints (make-default-breakpoints))]
    (update bps :items (fn [items] (conj (or items []) bp)))))

(defn remove-breakpoint
  "Remove the breakpoint with `id` and drop any overrides tied to it."
  [breakpoints id]
  (let [bps  (or breakpoints (make-default-breakpoints))
        items    (:items bps)
        overrides (:overrides bps)]
    (-> bps
        (assoc :items (into [] (remove #(= (:id %) id)) items))
        (assoc :overrides
               (into {}
                     (remove (fn [[_override-id ov]]
                               (= (:breakpoint-id ov) id)))
                     overrides)))))

(defn get-override
  "Return the override map (the `:props`) for `shape-id` + `breakpoint-id`,
  or nil if none. `:overrides` is keyed by override id, so we scan for a
  matching entry."
  [breakpoints breakpoint-id shape-id]
  (let [bps  (or breakpoints (make-default-breakpoints))
        ov   (:overrides bps)]
    (some (fn [[_override-id entry]]
            (when (and (= (:breakpoint-id entry) breakpoint-id)
                       ;; `:props` may carry the target shape id under
                       ;; :shape-id; absent = frame-level override.
                       (= (get-in entry [:props :shape-id]) shape-id))
              (:props entry)))
          ov)))