;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.blur
  (:require
   [app.common.schema :as sm]))

(def schema:blur
  [:map {:title "Blur"}
   [:id ::sm/uuid]
   [:type [:enum :layer-blur]]
   [:value ::sm/safe-number]
   [:hidden :boolean]
   ;; Figma-parity per-item blend modes (gap #9). Optional blend mode for
   ;; this layer blur; absent = :normal = today's compositing. The
   ;; renderer application is deferred; the field round-trips.
   [:blend-mode {:optional true}
    [::sm/one-of #{:normal :darken :multiply :color-burn
                   :lighten :screen :color-dodge :overlay
                   :soft-light :hard-light :difference :exclusion
                   :hue :saturation :color :luminosity}]]])
