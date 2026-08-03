;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.page
  (:refer-clojure :exclude [empty?])
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as-alias gpt]
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]
   [app.common.types.grid :as ctg]
   [app.common.types.plugins :as ctpg]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:flow
  [:map {:title "Flow"}
   [:id ::sm/uuid]
   [:name :string]
   [:starting-frame ::sm/uuid]])

(def schema:flows
  [:map-of {:gen/max 2} ::sm/uuid schema:flow])

(def schema:guide
  [:map {:title "Guide"}
   [:id ::sm/uuid]
   [:axis [::sm/one-of #{:x :y}]]
   [:position ::sm/safe-number]
   [:frame-id {:optional true} [:maybe ::sm/uuid]]
   [:color {:optional true} [:maybe ctc/schema:hex-color]]])

(def schema:guides
  [:map-of {:gen/max 2} ::sm/uuid schema:guide])

(def schema:objects
  [:map-of {:gen/max 5} ::sm/uuid cts/schema:shape])

(def schema:comment-thread-position
  [:map {:title "CommentThreadPosition"}
   [:frame-id ::sm/uuid]
   [:position ::gpt/point]])

;; Figma-parity canvas sections (gap #39). A section is a titled,
;; non-rendering organizational region that groups frames for navigation.
;; It is purely a canvas overlay (no shape in :objects); the viewer
;; renders only the title + bounds. :bounds is a plain rect in page
;; coordinates. Optional :collapsed hides the section's frames in the
;; layers panel (deferred — UI not wired in v1).
(def schema:section
  [:map {:title "Section"}
   [:id ::sm/uuid]
   [:name :string]
   [:bounds
    [:map
     [:x ::sm/safe-number]
     [:y ::sm/safe-number]
     [:width ::sm/safe-number]
     [:height ::sm/safe-number]]]
   [:collapsed {:optional true} :boolean]])

(def schema:sections
  [:map-of {:gen/max 2} ::sm/uuid schema:section])

;; Figma #72: prototype sections. A titled region that groups frames for
;; organization within a prototype flow. Distinct from canvas :sections
;; (gap #39, which are bounds-based overlay regions): a prototype section
;; carries the explicit list of frame ids it groups, so the interactions
;; panel can list/rename/resection them. Optional vector on the page;
;; absent = no sections = existing behavior. :frame-ids is optional so a
;; freshly-created (still empty) section validates.
(def schema:prototype-section
  [:map {:title "PrototypeSection"}
   [:id ::sm/uuid]
   [:name :string]
   [:frame-ids {:optional true} [:vector ::sm/uuid]]])

(def schema:prototype-sections
  [:vector schema:prototype-section])

(def schema:page
  [:map {:title "FilePage"}
   [:id ::sm/uuid]
   [:name :string]
   [:index {:optional true} ::sm/int]
   [:objects schema:objects]
   [:default-grids {:optional true} ctg/schema:default-grids]
   [:flows {:optional true} schema:flows]
   [:guides {:optional true} schema:guides]
   ;; Figma-parity canvas sections (gap #39). Optional map of section-id
   ;; -> section. Absent = no sections = existing behavior. The viewer
   ;; overlay (viewport.cljs) renders section titles only when this is
   ;; present and non-empty.
   [:sections {:optional true} schema:sections]
   ;; Figma #72: prototype sections (titled frame groupings for prototype
   ;; flows). Optional vector; absent = no sections = existing behavior.
   ;; Persistence to the page through the changes pipeline is DEFERRED
   ;; (needs a new :mod-prototype-section change type in changes.cljc,
   ;; high-blast); the interactions panel edits these in-session via
   ;; workspace-local state, see data/workspace/interactions.cljs.
   [:prototype-sections {:optional true} schema:prototype-sections]
   [:plugin-data {:optional true} ctpg/schema:plugin-data]
   [:background {:optional true} ctc/schema:hex-color]
   ;; Per-page pixel grid color. Falls back to a hardcoded default when
   ;; unset so existing files render identically to before.
   [:pixel-grid-color {:optional true} ctc/schema:hex-color]
   [:pixel-grid-opacity {:optional true} ::sm/safe-number]
   ;; Figma-parity variable modes (gap #31). Optional per-page mode name.
   ;; Objects on the page default to Auto (inherit the page mode); a nil /
   ;; absent value = no mode assignment = existing behavior. Per-frame /
   ;; per-object mode attr needs shape.cljc (not owned) and is DEFERRED;
   ;; the propagation.cljs mode-resolution runtime is also DEFERRED.
   [:variable-mode {:optional true} [:maybe :string]]

   [:comment-thread-positions {:optional true}
    [:map-of ::sm/uuid schema:comment-thread-position]]])

(def valid-guide?
  (sm/lazy-validator schema:guide))

(def check-page
  (sm/check-fn schema:page))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT & HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialization

(def root uuid/zero)

(def empty-page-data
  {:objects {root
             (cts/setup-shape {:id root
                               :type :frame
                               :parent-id root
                               :frame-id root
                               :name "Root Frame"})}})

(defn make-empty-page
  [{:keys [id name background]}]
  (-> empty-page-data
      (assoc :id (or id (uuid/next)))
      (assoc :name (d/nilv name "Page 1"))
      (cond-> background
        (assoc :background background))))

(defn get-frame-flow
  [flows frame-id]
  (d/seek #(= (:starting-frame %) frame-id) (vals flows)))

(defn is-empty?
  "Check if page is empty or contains shapes"
  [page]
  (= 1 (count (:objects page))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PROTOTYPE SECTIONS (Figma #72)
;;
;; Pure helpers over a prototype-sections vector. The interactions panel
;; edits these in-session (workspace-local); the page-level :prototype-sections
;; field is the persistence target. All helpers are total: a missing/nil
;; sections vector is treated as empty, so callers never need to special-case
;; an absent field.

(defn make-prototype-section
  "Build a fresh prototype section map with a new id."
  ([name]
   (make-prototype-section (uuid/next) name))
  ([id name]
   {:id        id
    :name      (or name "Section")
    :frame-ids []}))

(defn get-prototype-sections
  "Return the page's prototype-sections vector (never nil)."
  [page]
  (or (:prototype-sections page) []))

(defn add-prototype-section
  "Append `section` to the `sections` vector."
  [sections section]
  (conj (or sections []) section))

(defn rename-prototype-section
  "Return `sections` with the named section's :name replaced."
  [sections section-id name]
  (mapv #(if (= (:id %) section-id)
           (assoc % :name name)
           %)
        (or sections [])))

(defn remove-prototype-section
  "Return `sections` without the section of `section-id`."
  [sections section-id]
  (filterv #(not= (:id %) section-id) (or sections [])))

(defn add-frame-to-prototype-section
  "Add `frame-id` to a section's :frame-ids (idempotent)."
  [sections section-id frame-id]
  (mapv #(if (= (:id %) section-id)
           (let [fids (vec (:frame-ids % []))]
             (if (some #(= % frame-id) fids)
               %
               (assoc % :frame-ids (conj fids frame-id))))
           %)
        (or sections [])))

(defn remove-frame-from-prototype-section
  "Remove `frame-id` from a section's :frame-ids."
  [sections section-id frame-id]
  (mapv #(if (= (:id %) section-id)
           (assoc % :frame-ids (filterv #(not= % frame-id) (:frame-ids % [])))
           %)
        (or sections [])))

(defn frame-in-prototype-section?
  "True when `frame-id` is grouped under any prototype section."
  [sections frame-id]
  (some #(some (fn [fid] (= fid frame-id)) (:frame-ids % []))
        (or sections [])))
