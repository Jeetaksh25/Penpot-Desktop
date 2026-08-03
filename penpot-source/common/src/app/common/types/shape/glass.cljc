;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.glass
  (:require
   [app.common.schema :as sm]))

;; Figma-parity glass effect (gap #61). A new effect schema alongside
;; shadow / blur. Group V adds an opaque `[:glass]` vector slot
;; ([:vector ::sm/any]) to schema:shape-attrs in shape.cljc; this file
;; defines the structure of a single glass effect map for documentation,
;; validation, and UI authoring. Max one glass effect per layer. The
;; renderer glass shader (refraction + dispersion + frost noise) is
;; deferred (significant GPU work, no build to verify); the value
;; round-trips on the shape via dwsh/update-shapes.
(def schema:glass-effect
  [:map {:title "GlassEffect"}
   [:id ::sm/uuid]
   [:type [:enum :glass]]
   [:hidden :boolean]
   ;; Refraction bends light through the glass (0..1 normalized strength).
   [:refraction {:optional true} [::sm/number {:min 0 :max 1}]]
   ;; Dispersion splits the light spectrum (0..1, chromatic aberration).
   [:dispersion {:optional true} [::sm/number {:min 0 :max 1}]]
   ;; Frost blurs / diffuses the surface (0..1).
   [:frost {:optional true} [::sm/number {:min 0 :max 1}]]
   ;; Splay spreads the glass edge (0..1).
   [:splay {:optional true} [::sm/number {:min 0 :max 1}]]
   ;; Light angle in degrees (0..360).
   [:light-angle {:optional true} [::sm/number {:min 0 :max 360}]]
   ;; Light intensity (0..1).
   [:light-intensity {:optional true} [::sm/number {:min 0 :max 1}]]
   ;; Depth of the glass (0..1, how far the refracted content is offset).
   [:depth {:optional true} [::sm/number {:min 0 :max 1}]]])

(def check-glass-effect
  (sm/check-fn schema:glass-effect))

(def valid-glass-effect?
  (sm/validator schema:glass-effect))