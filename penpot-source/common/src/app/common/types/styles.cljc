;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

;; Figma-parity reusable styles (gap #32). Mirrors schema:library-color in
;; color.cljc: each style is a named library asset that wraps the same
;; configuration a shape carries, plus a ref-id/ref-file pair so an applied
;; style points back to its library entry. All three are OPTIONAL on
;; schema:data in file.cljc — absent = no styles = existing behavior.

(ns app.common.types.styles
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.plugins :as ctpg]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA: reusable effect / stroke / grid styles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; An effect style stores the full `:shadow`/`:blur`/`:background-blur`
;; config exactly as a shape carries it, so applying the style is a copy.
;; We keep `::sm/any` for the value to avoid coupling to the (large,
;; shape.cljc-owned) effect schemas; the renderer already understands the
;; shape effect representation.
(def schema:effect-style
  [:map {:title "LibraryEffectStyle" :closed true}
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:path {:optional true} :string]
   ;; The wrapped effect configuration. Stored opaquely so this namespace
   ;; does not need to import the shape effect schemas (owned elsewhere).
   [:effects {:optional true} ::sm/any]
   [:modified-at {:optional true} ::ct/inst]
   [:plugin-data {:optional true} ctpg/schema:plugin-data]])

;; A stroke style stores the full stroke config (color, width, style, caps,
;; joins, ...). Same opaque-value approach as effect styles.
(def schema:stroke-style
  [:map {:title "LibraryStrokeStyle" :closed true}
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:path {:optional true} :string]
   [:strokes {:optional true} ::sm/any]
   [:modified-at {:optional true} ::ct/inst]
   [:plugin-data {:optional true} ctpg/schema:plugin-data]])

;; A grid style stores a full grid definition (row/column/uniform) plus its
;; color, exactly as a frame carries one entry of `:grids`.
(def schema:grid-style
  [:map {:title "LibraryGridStyle" :closed true}
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:path {:optional true} :string]
   [:grid {:optional true} ::sm/any]
   [:modified-at {:optional true} ::ct/inst]
   [:plugin-data {:optional true} ctpg/schema:plugin-data]])

;; Collection schemas (map-of uuid -> style) used by file.cljc schema:data.
(def schema:effect-styles
  [:map-of {:gen/max 5} ::sm/uuid schema:effect-style])

(def schema:stroke-styles
  [:map-of {:gen/max 5} ::sm/uuid schema:stroke-style])

(def schema:grid-styles
  [:map-of {:gen/max 5} ::sm/uuid schema:grid-style])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- touch
  [style]
  (assoc style :modified-at (ct/now)))

(defn add-effect-style
  [file-data style]
  (update file-data :effect-styles assoc (:id style) (touch style)))

(defn add-stroke-style
  [file-data style]
  (update file-data :stroke-styles assoc (:id style) (touch style)))

(defn add-grid-style
  [file-data style]
  (update file-data :grid-styles assoc (:id style) (touch style)))

(defn get-effect-style
  [file-data style-id]
  (dm/get-in file-data [:effect-styles style-id]))

(defn get-stroke-style
  [file-data style-id]
  (dm/get-in file-data [:stroke-styles style-id]))

(defn get-grid-style
  [file-data style-id]
  (dm/get-in file-data [:grid-styles style-id]))

(defn delete-effect-style
  [file-data style-id]
  (update file-data :effect-styles dissoc style-id))

(defn delete-stroke-style
  [file-data style-id]
  (update file-data :stroke-styles dissoc style-id))

(defn delete-grid-style
  [file-data style-id]
  (update file-data :grid-styles dissoc style-id))