;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.fade
  (:require
   [app.common.schema :as sm]))

;; Figma-parity fade effect (gap #60 fade). A single-map MASK slot on the
;; shape (see :fade in app.common.types.shape). The renderer
;; (app.main.ui.shapes.fade) emits a <mask mask-type="alpha"> whose alpha
;; ramps along :direction (degrees, SVG convention: 0=right, 90=down) from
;; :start-opacity to :end-opacity, and applies mask="url(#fade-<render-id>)"
;; to the shape element. Fade is a MASK, not an SVG filter, so it does NOT
;; enter the 3-gate filter lockstep (bounds.cljc / filters.cljs / filter-str)
;; and does NOT grow the filter region — a mask only hides content, it never
;; extends bounds.
;;
;; Defaults (applied defensively in the renderer with `or`, so a partial map
;; still renders): :direction 90 (fade downward), :start-opacity 1, :end-opacity 0
;; -> visible at the top edge, transparent at the bottom edge.
;; Absent or :hidden slot -> no mask attr, no <mask> def -> byte-identical.
(def schema:fade
  [:map {:title "Fade"}
   [:id ::sm/uuid]
   [:type [:enum :fade]]
   [:hidden :boolean]
   ;; Direction of the falloff in degrees (0..360). 0 = fade toward the
   ;; right, 90 = fade downward, 180 = left, 270 = up. Optional; renderer
   ;; defaults to 90.
   [:direction {:optional true} ::sm/safe-number]
   ;; Opacity (0..1) at the start of the ramp. Optional; defaults to 1.
   [:start-opacity {:optional true} ::sm/safe-number]
   ;; Opacity (0..1) at the end of the ramp. Optional; defaults to 0.
   [:end-opacity {:optional true} ::sm/safe-number]])