;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

;; Figma-parity typed component properties (Figma_Parity.md gap #1).
;;
;; A main component declares named, TYPED properties (boolean / text /
;; instance-swap / variant / slot). Instances carry a map of
;; property-name -> value that overrides the rendered sub-tree. This
;; module holds the schema + pure helpers; the override-resolution
;; primitive (`apply-property-overrides`) is deliberately PURE and is
;; intended to be called by the render/sync path under a guard (only
;; when an instance carries :component-property-values).
;;
;; v1 scope (this round): the authoring surface is fully wired
;; (schema + UI + events + i18n). Runtime override APPLICATION is exposed
;; as the pure `apply-property-overrides` helper, but is NOT yet wired
;; into the component render/sync path — that wiring is high blast-radius
;; (touches core instance rendering) and is deferred to the polish round
;; (#9) where it can be verified with a build. Only :boolean applies a
;; real override in the helper (a safe :hidden toggle); the other types
;; are stored/round-tripped and their visible effect is documented as
;; deferred. This mirrors the established DONE-v1-with-documented-limits
;; pattern used by the conic-gradient / image-crop / slice features.

(ns app.common.types.component-property
  (:require [app.common.data :as d]
            [app.common.schema :as sm]
            [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def property-types
  "The five Figma component-property types."
  #{:boolean :text :instance-swap :variant :slot})

(def schema:component-property
  "A typed property definition living on a main component."
  [:map
   [:id ::sm/uuid]
   [:name :string]
   [:type [::sm/one-of property-types]]
   ;; Type-dependent default value. Kept loose so each type stores its
   ;; own value shape (boolean -> bool, text -> string, instance-swap ->
   ;; component-id uuid, variant -> string, slot -> shape id).
   [:default-value {:optional true} :any]
   ;; Sub-shape ids within the component's own :objects that this
   ;; property controls (the override targets). Empty = authored but
   ;; not yet bound to a layer.
   [:targets {:optional true} [:vector ::sm/uuid]]
   ;; instance-swap: optional list of preferred component ids to offer.
   [:preferred-instances {:optional true} [:vector ::sm/uuid]]])

(def schema:component-properties
  [:vector schema:component-property])

(def schema:component-property-values
  "A map of property-name -> value carried on an instance root shape."
  [:map-of :string :any])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-property
  "Create a new typed property definition."
  [{:keys [name type default-value targets preferred-instances]}]
  {:id (uuid/uuid)
   :name (or name "Property 1")
   :type (or type :boolean)
   :default-value default-value
   :targets (or targets [])
   :preferred-instances preferred-instances})

(defn property-by-name
  [properties name]
  (d/seek #(= (:name %) name) properties))

(defn property-by-id
  [properties id]
  (d/seek #(= (:id %) id) properties))

(defn default-values
  "Compute the default property-value map for a freshly placed instance,
  from the component's property definitions."
  [properties]
  (into {}
        (for [p properties
              :when (some? (:default-value p))]
          {(:name p) (:default-value p)})))

(defn validate-value
  "Coerce a value against a property's type. Returns the (possibly
  coerced) value or nil when the value is absent."
  [property value]
  (when (some? value)
    (case (:type property)
      :boolean (boolean value)
      :text (str value)
      :instance-swap value   ;; a component id (uuid) or nil
      :variant (str value)
      :slot value            ;; a content override shape id or nil
      value)))

(defn apply-property-overrides
  "PURE override-resolution primitive. Given the component's property
  definitions, an instance's property-value map, and a single sub-shape,
  return the sub-shape with overrides applied for any property whose
  :targets include the sub-shape's id.

  - :boolean  -> toggle :hidden on the target (value true hides it)
  - :text     -> v1: no-op (text :content is a complex tree; rebuilding it
                from a string needs the text-content path — deferred)
  - :instance-swap -> v1: no-op (swap needs component-file resolution by the
                sync path — deferred)
  - :variant  -> v1: sets :variant-name on the target (a string axis value)
  - :slot     -> v1: no-op (slot content override deferred)

  Returns the shape unchanged when no property targets it. This is the
  single override primitive; the render/sync path is responsible for
  calling it under a guard (only when the instance root carries a
  non-empty :component-property-values map). See Figma_Parity.md #1."
  [properties values shape]
  (reduce-kv
   (fn [shape prop-name value]
     (let [prop (property-by-name properties prop-name)]
       (if (and prop (contains? (into #{} (:targets prop)) (:id shape)))
         (case (:type prop)
           :boolean        (assoc shape :hidden (true? value))
           :text           shape   ;; v1 deferred
           :instance-swap  shape   ;; v1 deferred
           :variant        (assoc shape :variant-name (str value))
           :slot           shape   ;; v1 deferred
           shape)
         shape)))
   shape
   values))