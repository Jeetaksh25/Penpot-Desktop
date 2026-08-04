;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.collection
  "CMS Collections (authorable half) — ALL_APPS_PARITY P0.05.

  Pure-data schemas + helper fns for the Ovion CMS feature. A
  `cms-data` map lives at the page level (`:cms-data` on the page)
  and holds two vectors: `:collections` (authorable schemas + items)
  and `:bindings` (shape-id -> collection.field references).

  IMPORTANT: CMS bindings are NOT stored on shape attrs. They live in
  this page-level map so the shape schema (shape.cljc / attrs.cljc) is
  never touched. Persistence through the shared changes pipeline is
  DEFERRED (needs a new :mod-cms-data change type in changes.cljc,
  high-blast — same precedent as :prototype-sections); the workspace
  events in `app.main.data.workspace.collections` keep an in-session
  copy in `:workspace-local :cms-data` and initialize from the page's
  `:cms-data` field on first edit."
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:field-type
  [:enum :text :image :number :date :color :reference :multi-reference])

(def schema:field
  [:map {:title "CmsField"}
   [:id ::sm/uuid]
   [:name :string]
   [:type schema:field-type]
   [:reference-collection-id {:optional true} ::sm/uuid]
   [:default {:optional true} :any]])

(def schema:item
  [:map {:title "CmsItem"}
   [:id ::sm/uuid]
   [:fields [:map-of ::sm/uuid :any]]])

(def schema:collection
  [:map {:title "CmsCollection"}
   [:id ::sm/uuid]
   [:name :string]
   [:fields [:vector schema:field]]
   [:items [:vector schema:item]]])

(def schema:binding
  [:map {:title "CmsBinding"}
   [:shape-id ::sm/uuid]
   [:collection-id ::sm/uuid]
   [:field-id ::sm/uuid]
   [:item-id {:optional true} ::sm/uuid]])

(def schema:cms-data
  [:map {:title "CmsData"}
   [:collections [:vector schema:collection]]
   [:bindings [:vector schema:binding]]])

(def check-cms-data
  (sm/check-fn schema:cms-data))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFAULTS & CONSTRUCTORS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def empty-cms-data
  {:collections []
   :bindings []})

(defn make-collection
  "Build a fresh, empty collection map with a new id."
  ([]
   (make-collection nil))
  ([name]
   (make-collection (uuid/next) name))
  ([id name]
   {:id      id
    :name    (or name "Collection")
    :fields  []
    :items   []}))

(defn make-field
  "Build a field map. `type` is a schema:field-type keyword; for
  :reference / :multi-reference a `reference-collection-id` may be
  supplied."
  ([name type]
   (make-field (uuid/next) name type nil))
  ([name type reference-collection-id]
   (make-field (uuid/next) name type reference-collection-id))
  ([id name type reference-collection-id]
   (cond-> {:id    id
            :name  (or name "Field")
            :type  (or type :text)}
     (some? reference-collection-id)
     (assoc :reference-collection-id reference-collection-id))))

(defn make-item
  "Build an empty item map (field-id -> value, initially empty)."
  ([]
   (make-item (uuid/next)))
  ([id]
   {:id     id
    :fields {}}))

(defn make-binding
  "Build a binding map from a shape to a collection.field. `item-id`
  is optional (nil for collection-list repeatable regions)."
  ([shape-id collection-id field-id]
   (make-binding shape-id collection-id field-id nil))
  ([shape-id collection-id field-id item-id]
   (cond-> {:shape-id      shape-id
            :collection-id collection-id
            :field-id      field-id}
     (some? item-id)
     (assoc :item-id item-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOOKUPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-collection
  "Return the collection with `collection-id` from `cms`, or nil."
  [cms collection-id]
  (d/seek #(= (:id %) collection-id) (:collections cms)))

(defn get-field
  "Return the field with `field-id` from `collection`, or nil."
  [collection field-id]
  (d/seek #(= (:id %) field-id) (:fields collection)))

(defn get-binding
  "Return the binding for `shape-id` from `cms`, or nil. A shape may
  have at most one binding (enforced by add-binding)."
  [cms shape-id]
  (d/seek #(= (:shape-id %) shape-id) (:bindings cms)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MUTATORS (pure — return new cms-data)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-collection
  "Append `collection` (from make-collection) to `cms`."
  [cms collection]
  (update cms :collections d/concat-vec [collection]))

(defn add-field
  "Append `field` (from make-field) to the collection `collection-id`."
  [cms collection-id field]
  (update cms :collections
          (fn [cols]
            (mapv (fn [c]
                    (if (= (:id c) collection-id)
                      (update c :fields d/concat-vec [field])
                      c))
                  cols))))

(defn add-item
  "Append `item` (from make-item) to the collection `collection-id`."
  [cms collection-id item]
  (update cms :collections
          (fn [cols]
            (mapv (fn [c]
                    (if (= (:id c) collection-id)
                      (update c :items d/concat-vec [item])
                      c))
                  cols))))

(defn add-binding
  "Add `binding` to `cms`, replacing any existing binding for the same
  shape-id (a shape binds to at most one field)."
  [cms binding]
  (-> cms
      (update :bindings
              (fn [bs]
                (filterv #(not= (:shape-id %) (:shape-id binding)) bs)))
      (update :bindings d/concat-vec [binding])))

(defn remove-binding
  "Remove the binding for `shape-id` from `cms`, if any."
  [cms shape-id]
  (update cms :bindings
          (fn [bs]
            (filterv #(not= (:shape-id %) shape-id) bs))))