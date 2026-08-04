;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.shadow
  (:require
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]))

(def styles #{:drop-shadow :inner-shadow})

(def schema:color
  [:merge {:title "ShadowColor"}
   ctc/schema:color-attrs
   ctc/schema:plain-color])

(def color-attrs
  (sm/keys schema:color))

(def schema:shadow
  [:map {:title "Shadow"}
   [:id [:maybe ::sm/uuid]]
   [:style [::sm/one-of styles]]
   [:offset-x ::sm/safe-number]
   [:offset-y ::sm/safe-number]
   [:blur ::sm/safe-number]
   [:spread ::sm/safe-number]
   [:hidden :boolean]
   [:color schema:color]
   ;; Figma-parity per-item blend modes (gap #9). Optional blend mode for
   ;; this single shadow; absent = :normal = today's compositing. The
   ;; renderer applies the shadow with its own mix-blend-mode — that
   ;; wiring is deferred (high blast-radius compositing change, no build
   ;; to verify); the field round-trips on the shadow.
   [:blend-mode {:optional true}
    [::sm/one-of #{:normal :darken :multiply :color-burn
                   :lighten :screen :color-dodge :overlay
                   :soft-light :hard-light :difference :exclusion
                   :linear-burn :linear-dodge :vivid-light :linear-light :pin-light :hard-mix :subtract :divide :hue :saturation :color :luminosity}]]
   ;; Figma-parity grain on shadows (gap #65). Optional grain overlay per
   ;; shadow: :intensity (0..1) and :size (cell size). Absent = no grain =
   ;; today's rendering. The renderer grain overlay on the shadow paint is
   ;; deferred (no build to verify); the value round-trips on the shadow.
   [:grain {:optional true}
    [:map {:title "ShadowGrain" :closed true}
     [:intensity {:optional true} [::sm/number {:min 0 :max 1}]]
     [:size {:optional true} ::sm/safe-number]]]])

(def check-shadow
  (sm/check-fn schema:shadow))

(def valid-shadow?
  (sm/validator schema:shadow))

