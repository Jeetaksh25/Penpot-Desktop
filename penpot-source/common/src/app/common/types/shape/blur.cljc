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
                   :hue :saturation :color :luminosity}]]
   ;; Figma-parity progressive blur (gap #60). When :progressive? is true
   ;; the blur falloff varies across the shape (gradient-like blur): the
   ;; layer is blurred at :start-radius along the start edge and ramps to
   ;; sharp (radius 0) along the end edge, across the [:start-offset,
   ;; :end-offset] falloff region (0..1 of the selrect, normalized).
   ;; :direction (degrees, 0=right 90=down 180=left 270=up, quantized to
   ;; the nearest cardinal by the renderer) sets the falloff axis.
   ;; Absent :progressive? (or false) = :value is a uniform blur = today's
   ;; behavior (byte-identical). The renderer emits an N-band stacked
   ;; feGaussianBlur graph (filters.cljs progressive-blur-bands); the
   ;; fields round-trip on the blur map.
   [:progressive? {:optional true} :boolean]
   [:direction {:optional true} ::sm/safe-number]
   [:start-radius {:optional true} ::sm/safe-number]
   [:start-offset {:optional true} ::sm/safe-number]
   [:end-offset {:optional true} ::sm/safe-number]])
