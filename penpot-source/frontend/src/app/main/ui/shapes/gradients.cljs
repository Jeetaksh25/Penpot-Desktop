;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.gradients
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.export :as ed]
   [app.common.types.color :as clr]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn- add-metadata!
  [props gradient]
  (-> props
      (obj/set! "penpot:gradient" "true")
      (obj/set! "penpot:start-x" (:start-x gradient))
      (obj/set! "penpot:start-y" (:start-y gradient))
      (obj/set! "penpot:end-x"   (:end-x gradient))
      (obj/set! "penpot:end-y"   (:end-y gradient))
      (obj/set! "penpot:width"   (:width gradient))))

(mf/defc linear-gradient
  {::mf/wrap-props false}
  [{:keys [id gradient shape force-transform]}]
  (let [transform (mf/with-memo [shape]
                    (when force-transform
                      (gsh/transform-matrix shape nil (gpt/point 0.5 0.5))))

        metadata? (mf/use-ctx ed/include-metadata-ctx)
        props     #js {:id id
                       :x1 (:start-x gradient)
                       :y1 (:start-y gradient)
                       :x2 (:end-x gradient)
                       :y2 (:end-y gradient)
                       :gradientTransform (dm/str transform)}]

    (when ^boolean metadata?
      (add-metadata! props gradient))

    [:> :linearGradient props
     (for [[index {:keys [offset color opacity]}] (d/enumerate (sort-by :offset (:stops gradient)))]
       [:stop {:key (dm/str id "-stop-" index)
               :offset (d/nilv offset 0)
               :stop-color color
               :stop-opacity opacity}])]))

(mf/defc radial-gradient
  {::mf/wrap-props false}
  [{:keys [id gradient shape]}]
  (let [path?         (cfh/path-shape? shape)

        transform     (when ^boolean path?
                        (dm/get-prop shape :transform))
        transform     (d/nilv transform gmt/base)

        transform-inv (when ^boolean path?
                        (dm/get-prop shape :transform-inverse))
        transform-inv (d/nilv transform-inv gmt/base)

        {:keys [start-x start-y end-x end-y] gwidth :width} gradient

        gstart-pt     (gpt/point start-x start-y)
        gend-pt       (gpt/point end-x end-y)
        gradient-vec  (gpt/to-vec gstart-pt gend-pt)

        angle         (+ (gpt/angle gradient-vec) 90)

        points        (dm/get-prop shape :points)
        bounds        (mf/with-memo [points]
                        (grc/points->rect points))
        selrect       (dm/get-prop shape :selrect)

        ;; Paths don't have a transform in SVG because we transform
        ;; the points we need to compensate the difference between the
        ;; original rectangle and the transformed one. This factor is
        ;; that calculation.
        factor        (if ^boolean path?
                        (/ (dm/get-prop selrect :height)
                           (dm/get-prop bounds :height))
                        1.0)

        transform     (mf/with-memo [gradient transform transform-inv factor]
                        (-> (gmt/matrix)
                            (gmt/translate gstart-pt)
                            (gmt/multiply transform)
                            (gmt/rotate angle)
                            (gmt/scale (gpt/point gwidth factor))
                            (gmt/multiply transform-inv)
                            (gmt/translate (gpt/negate gstart-pt))))

        metadata?     (mf/use-ctx ed/include-metadata-ctx)

        props         #js {:id id
                           :cx start-x
                           :cy start-y
                           :r (gpt/length gradient-vec)
                           :gradientTransform transform}]

    (when ^boolean metadata?
      (add-metadata! props gradient))

    [:> :radialGradient props
     (for [[index {:keys [offset color opacity]}] (d/enumerate (:stops gradient))]
       [:stop {:key (dm/str id "-stop-" index)
               :offset (d/nilv offset 0)
               :stop-color color
               :stop-opacity opacity}])]))

(mf/defc angular-gradient
  {::mf/wrap-props false}
  [{:keys [id gradient shape]}]
  ;; Figma "angular"/conic gradient. SVG has no native conic gradient, so we
  ;; approximate it with N thin wedge sectors swept around the center, each
  ;; filled with the gradient color interpolated at its midpoint angle. The
  ;; wedges live in a userSpaceOnUse <pattern> sized to the shape bounds, so
  ;; `fill="url(#id)"` paints the shape with the sweep. The pattern clips
  ;; wedges to the bounds, which is correct because the fill only shows
  ;; inside the shape geometry anyway. 90 wedges (4° each) is visually smooth
  ;; at typical zoom; raising the step count converges to the exact conic.
  ;; start-x/y is the conic center; end-x/y defines the 0° ray direction.
  (let [x      (dm/get-prop shape :x)
        y      (dm/get-prop shape :y)
        w      (dm/get-prop shape :width)
        h      (dm/get-prop shape :height)

        cx     (+ x (* (:start-x gradient) w))
        cy     (+ y (* (:start-y gradient) h))
        ex     (+ x (* (:end-x gradient) w))
        ey     (+ y (* (:end-y gradient) h))
        start  (js/Math.atan2 (- ey cy) (- ex cx))
        r      (js/Math.sqrt (+ (* w w) (* h h)))
        steps  90
        stops  (vec (sort-by :offset (:stops gradient)))

        wedge (fn [a1 a2]
                (let [x1 (+ cx (* r (js/Math.cos a1)))
                      y1 (+ cy (* r (js/Math.sin a1)))
                      x2 (+ cx (* r (js/Math.cos a2)))
                      y2 (+ cy (* r (js/Math.sin a2)))]
                  (dm/str "M " cx " " cy " L " x1 " " y1 " A " r " " r " 0 0 1 " x2 " " y2 " Z")))]

    [:> :pattern {:id id
                  :patternUnits "userSpaceOnUse"
                  :x x :y y :width w :height h}
     (for [i (range steps)
           :let [a1   (+ start (* i (/ (* 2 js/Math.PI) steps)))
                 a2   (+ start (* (inc i) (/ (* 2 js/Math.PI) steps)))
                 off  (/ (+ i 0.5) steps)
                 col  (clr/interpolate-gradient stops off)]]
       [:path {:key (dm/str id "-w-" i)
               :d (wedge a1 a2)
               :fill (:color col)
               :fill-opacity (:opacity col)}])]))

(mf/defc mesh-gradient
  {::mf/wrap-props false}
  [{:keys [id gradient shape]}]
  ;; Figma-parity mesh gradient (gap #21). SVG has no native mesh gradient,
  ;; so we tessellate each grid cell into an N x N sub-grid and emit one
  ;; filled <path> quad per sub-cell, with bilinearly-interpolated color and
  ;; position across the 4 corner mesh-points. The paths live in a
  ;; userSpaceOnUse <pattern> sized to the shape bounds (same wrapper as
  ;; angular-gradient), so `fill="url(#id)"` paints the shape with the mesh.
  ;; :mesh-points is a flat row-major vector of {:color :opacity :x :y}
  ;; (x,y in 0..1) laid out as mesh-rows x mesh-cols. GUARD: the whole body
  ;; is wrapped in (when (seq mesh-points) ...) so a malformed/empty mesh
  ;; emits no def (nil return). Legacy linear/radial/angular/diamond
  ;; gradients never carry :type :mesh and never reach this component, so
  ;; they render byte-identically. Deterministic for a fixed N (no
  ;; Math/random): N = mesh-tessellation ? 16 : 8.
  (when (and (some? (:mesh-points gradient))
             (pos? (count (:mesh-points gradient))))
    (let [x        (dm/get-prop shape :x)
          y        (dm/get-prop shape :y)
          w        (dm/get-prop shape :width)
          h        (dm/get-prop shape :height)

          points   (mapv (fn [p]
                            (cond-> p (nil? (:opacity p)) (assoc :opacity 1)))
                          (vec (:mesh-points gradient)))
          npoints  (count points)
          cols     (int (or (:mesh-cols gradient)
                            (js/Math.sqrt npoints)))
          cols     (if (pos? cols) cols 1)
          rows     (int (or (:mesh-rows gradient)
                            (/ npoints cols)))
          rows     (if (pos? rows) rows 1)

          tess?    (boolean (:mesh-tessellation gradient))
          N        (if tess? 16 8)

          ;; clr/interpolate-color expects stop maps carrying :offset; mesh
          ;; points have none, so we pin the endpoints to offsets 0 and 1 and
          ;; pass the weight t in [0,1] as the offset. Returns a stop-like
          ;; map {:color :opacity :offset ...} with interpolated values.
          interp   (fn [c1 c2 t]
                     (clr/interpolate-color
                      (assoc c1 :offset 0)
                      (assoc c2 :offset 1)
                      t))

          pt       (fn [row col]
                     (get points (+ (* row cols) col)))

          ;; Bilinear interpolation of the 4 corner positions of a cell,
          ;; returned in shape-space coordinates.
          sub-pos  (fn [p00 p10 p01 p11 u v]
                     (let [top-x (+ (:x p00) (* (- (:x p10) (:x p00)) u))
                           top-y (+ (:y p00) (* (- (:y p10) (:y p00)) u))
                           bot-x (+ (:x p01) (* (- (:x p11) (:x p01)) u))
                           bot-y (+ (:y p01) (* (- (:y p11) (:y p01)) u))
                           px    (+ top-x (* (- bot-x top-x) v))
                           py    (+ top-y (* (- bot-y top-y) v))]
                       [(+ x (* px w)) (+ y (* py h))]))

          ;; Bilinear interpolation of the 4 corner colors.
          sub-col  (fn [p00 p10 p01 p11 u v]
                     (let [top (interp p00 p10 u)
                           bot (interp p01 p11 u)]
                       (interp top bot v)))]

      [:> :pattern {:id id
                    :patternUnits "userSpaceOnUse"
                    :x x :y y :width w :height h}
       (for [r (range 0 (dec rows))
             c (range 0 (dec cols))
             :let [p00 (pt r c)
                   p10 (pt r (inc c))
                   p01 (pt (inc r) c)
                   p11 (pt (inc r) (inc c))]
             :when (and p00 p10 p01 p11)]
         [:* {:key (dm/str id "-cell-" r "-" c)}
          (for [i (range N)
                j (range N)
                :let [u0 (/ i N)       v0 (/ j N)
                      u1 (/ (inc i) N) v1 (/ (inc j) N)
                      [ax ay] (sub-pos p00 p10 p01 p11 u0 v0)
                      [bx by] (sub-pos p00 p10 p01 p11 u1 v0)
                      [cx cy] (sub-pos p00 p10 p01 p11 u1 v1)
                      [dx dy] (sub-pos p00 p10 p01 p11 u0 v1)
                      col     (sub-col p00 p10 p01 p11 u0 v0)]]
            [:path {:key (dm/str id "-c-" r "-" c "-" i "-" j)
                    :d (dm/str "M " ax " " ay
                               " L " bx " " by
                               " L " cx " " cy
                               " L " dx " " dy " Z")
                    :fill (:color col)
                    :fill-opacity (:opacity col)}])])])))

(mf/defc gradient
  {::mf/wrap-props false}
  [props]
  (let [attr     (unchecked-get props "attr")
        shape    (unchecked-get props "shape")
        id       (unchecked-get props "id")
        rid      (mf/use-ctx muc/render-id)

        id       (if (some? id)
                   id
                   (dm/str (name attr) "-" rid))

        gradient (get shape attr)
        props    #js {:id id
                      :gradient gradient
                      :shape shape}]

    (when (some? gradient)
      (case (:type gradient)
        :linear [:> linear-gradient props]
        :radial [:> radial-gradient props]
        ;; Figma-parity conic (proper wedge approximation) + diamond
        ;; (approximated as radial in v1 — SVG has no native diamond
        ;; gradient and the wedge method does not apply; a true
        ;; Manhattan-distance diamond is deferred to the polish round).
        :angular [:> angular-gradient props]
        ;; Figma-parity mesh gradient (gap #21) — tessellated <pattern> of
        ;; bilinearly-interpolated filled quads (see mesh-gradient above).
        ;; Only reached when (:type gradient) is :mesh; legacy gradients
        ;; never carry :type :mesh, so this case is inert for them.
        :mesh [:> mesh-gradient props]
        :diamond [:> radial-gradient props]
        nil))))
