;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.fade
  (:require
   [app.common.data.macros :as dm]
   [rumext.v2 :as mf]))

;; Figma-parity fade effect (gap #60 fade). Fade is a MASK, not an SVG
;; filter — it does NOT enter the 3-gate filter lockstep (bounds.cljc /
;; filters.cljs / filter-str) and does NOT grow the filter region. It is a
;; 2-site lockstep instead:
;;   site A (attrs.cljs add-fill-props!)  -> sets mask="url(#fade-<rid>)"
;;   site B (fade-mask* here, mounted in shape.cljs [:defs]) -> emits the
;;           <mask id="fade-<rid>"> + the <linearGradient> it paints.
;; Both sites gate on the SAME predicate — (:fade shape) present AND
;; (not (:hidden (:fade shape))) — so an absent/hidden slot sets no attr
;; AND emits no def (no dangling url(#fade-..) reference). byte-identical.
;;
;; The mask is mask-type="alpha": the mask content is a single <rect> filled
;; with a <linearGradient> whose stop-opacity ramps from :start-opacity to
;; :end-opacity. The rect's alpha therefore ramps the same way, and an alpha
;; mask uses that alpha as the shape's visibility -> a smooth directional
;; fade. (A default luminance mask would NOT work here: a white->transparent
;; gradient keeps RGB=white everywhere, so luminance is 1 everywhere and
;; nothing would fade. mask-type="alpha" is required.)
;;
;; Coordinate space: mask="url(#fade-<rid>)" is set on the SHAPE ELEMENT
;; (via add-fill-props!, on the rect/path/<g> itself, not the shape-container
;; wrapper), so maskUnits="userSpaceOnUse" resolves in the element's LOCAL
;; user space — the same space in which the shape geometry is authored (the
;; selrect x/y/width/height). The gradient therefore spans the selrect and
;; rotates with the shape (the shape's own :direction axis), which is the
;; intended behavior. For a group the mask is set on the group <g> and
;; spans the group selrect.

(defn fade-mask-id
  [render-id]
  (dm/str "fade-" render-id))

(defn fade-mask-url
  [render-id]
  (dm/str "url(#" (fade-mask-id render-id) ")"))

(defn- fade-grad-id
  [render-id]
  (dm/str "fade-grad-" render-id))

(mf/defc fade-mask*
  {::mf/wrap-props false}
  [props]
  (let [shape     (unchecked-get props "shape")
        render-id (unchecked-get props "render-id")
        fade      (get shape :fade)]
    ;; Site B gate — MUST mirror site A (attrs.cljs add-fill-props!) exactly:
    ;; present AND non-hidden. Absent/hidden -> emit nothing (byte-identical).
    ;; The (some? selrect) term is defense-in-depth: :selrect is a required
    ;; shape-schema field so it is always present for a valid shape, but if a
    ;; malformed shape ever reached here with nil selrect, the (+ x (/ w 2))
    ;; arithmetic below would throw — so bail to no-mask instead.
    (when (and (some? fade)
               (not ^boolean (:hidden fade))
               (some? (dm/get-prop shape :selrect)))
      (let [selrect   (dm/get-prop shape :selrect)
            x         (dm/get-prop selrect :x)
            y         (dm/get-prop selrect :y)
            w         (dm/get-prop selrect :width)
            h         (dm/get-prop selrect :height)

            ;; Direction in degrees (SVG: 0=right, 90=down). Default 90.
            dir       (or (:direction fade) 90)
            rad       (* dir (/ (.-PI js/Math) 180))
            dx        (.cos js/Math rad)
            dy        (.sin js/Math rad)

            ;; Center of the selrect.
            cx        (+ x (/ w 2))
            cy        (+ y (/ h 2))

            ;; Half-extent of the selrect projected onto the direction axis.
            ;; The gradient line spans the full bounds along :direction so
            ;; the ramp reaches both edges regardless of angle.
            half      (/ (+ (.abs js/Math (* w dx))
                           (.abs js/Math (* h dy))) 2)

            ;; Start point (stop 0, start-opacity) is the edge OPPOSITE the
            ;; direction; end point (stop 1, end-opacity) is the edge IN the
            ;; direction. So :direction 90 (down) -> start at top (opacity
            ;; start), end at bottom (opacity end) = classic fade-out-bottom.
            x1        (- cx (* dx half))
            y1        (- cy (* dy half))
            x2        (+ cx (* dx half))
            y2        (+ cy (* dy half))

            start-op  (or (:start-opacity fade) 1)
            end-op    (or (:end-opacity fade) 0)
            grad-url  (dm/str "url(#" (fade-grad-id render-id) ")")]

        [:*
         [:linearGradient {:id            (fade-grad-id render-id)
                           :gradient-units "userSpaceOnUse"
                           :x1            x1
                           :y1            y1
                           :x2            x2
                           :y2            y2}
          [:stop {:offset       0
                  :stop-color   "#FFFFFF"
                  :stop-opacity start-op}]
          [:stop {:offset       1
                  :stop-color   "#FFFFFF"
                  :stop-opacity end-op}]]

         [:mask {:id         (fade-mask-id render-id)
                 :mask-units "userSpaceOnUse"
                 :mask-type  "alpha"
                 :x          x
                 :y          y
                 :width      w
                 :height     h}
          [:rect {:x      x
                  :y      y
                  :width  w
                  :height h
                  :fill   grad-url}]]]))))