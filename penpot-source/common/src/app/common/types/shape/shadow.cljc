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
                   :hue :saturation :color :luminosity}]]])

(def check-shadow
  (sm/check-fn schema:shadow))

(def valid-shadow?
  (sm/validator schema:shadow))

