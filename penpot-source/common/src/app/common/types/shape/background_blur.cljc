;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.background-blur
  (:require
   [app.common.schema :as sm]))

(def schema:background-blur
  [:map {:title "BackgroundBlur"}
   [:id ::sm/uuid]
   [:type [:enum :background-blur]]
   [:value ::sm/safe-number]
   [:hidden :boolean]
   ;; Figma-parity per-item blend modes (gap #9). Optional blend mode for
   ;; this background blur; absent = :normal = today's compositing. The
   ;; renderer application is deferred; the field round-trips.
   [:blend-mode {:optional true}
    [::sm/one-of #{:normal :darken :multiply :color-burn
                   :lighten :screen :color-dodge :overlay
                   :soft-light :hard-light :difference :exclusion
                   :hue :saturation :color :luminosity}]]
   ;; Figma-parity progressive blur (gap #60). Same optional falloff params
   ;; as the layer blur; absent = :value is uniform = today's behavior. The
   ;; renderer gradient-blur kernel is deferred; the fields round-trip.
   [:progressive? {:optional true} :boolean]
   [:start-radius {:optional true} ::sm/safe-number]
   [:start-offset {:optional true} ::sm/safe-number]
   [:end-offset {:optional true} ::sm/safe-number]])