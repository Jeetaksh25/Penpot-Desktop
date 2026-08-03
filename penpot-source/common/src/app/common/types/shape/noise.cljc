;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.noise
  (:require
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]))

;; Figma-parity noise effect (gap #62). A new effect schema. Group V adds
;; an opaque `[:noise]` vector slot ([:vector ::sm/any]) to
;; schema:shape-attrs in shape.cljc; this file defines the structure of a
;; single noise effect map. Max two noise effects per shape. The renderer
;; noise-texture overlay is deferred (no build to verify); the value
;; round-trips on the shape via dwsh/update-shapes.
(def color-modes #{:mono :duo :multi})

(def schema:noise-color
  [:map {:title "NoiseColor"}
   [:color ctc/schema:hex-color]
   [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]])

(def schema:noise-effect
  [:map {:title "NoiseEffect"}
   [:id ::sm/uuid]
   [:type [:enum :noise]]
   [:hidden :boolean]
   ;; Color mode controls how many colors participate in the noise.
   [:color-mode {:optional true} [::sm/one-of color-modes]]
   ;; Noise cell size on X / Y (in shape-space units).
   [:size-x {:optional true} ::sm/safe-number]
   [:size-y {:optional true} ::sm/safe-number]
   ;; Density / coverage of the noise (0..1).
   [:density {:optional true} [::sm/number {:min 0 :max 1}]]
   ;; Colors used by the noise (1 for :mono, 2 for :duo, 3+ for :multi).
   [:colors {:optional true}
    [:vector {:gen/max 3} schema:noise-color]]])

(def check-noise-effect
  (sm/check-fn schema:noise-effect))

(def valid-noise-effect?
  (sm/validator schema:noise-effect))