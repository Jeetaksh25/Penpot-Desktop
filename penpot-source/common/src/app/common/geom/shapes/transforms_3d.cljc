;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

;; Figma-parity 3D transforms (gap #66). APPROXIMATE renderer: SVG has no
;; native 3D transform, so we project via a CSS `matrix3d(...)` applied to
;; the wrapping `<g>` element. Chromium/WebView2 (the Tauri WebView) GPUs
;; rasterize this as a 3D projection of the SVG subtree. This is NOT
;; spec-pure SVG, but it is the only way to honor the :transform-3d slot
;; without a full WebGPU/WebGL renderer (which is deferred).
;;
;; CORE INVARIANT (byte-identical when the slot is absent/empty): every
;; public fn here returns nil when `(:transform-3d shape)` is absent or
;; when none of :rotation-x/:rotation-y/:rotation-z/:perspective is set.
;; The frontend mount sites (shape.cljs shape-container, frame.cljs
;; frame-container-wrapper) guard on the return value of
;; `transform-3d-css-str`, so when it is nil NO `transform` /
;; `transformOrigin` / `transformStyle` key is emitted on the wrapping
;; `<g>` — the element is byte-for-byte identical to today.

(ns app.common.geom.shapes.transforms-3d
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.transforms :as gt]
   [app.common.math :as mth]
   [clojure.string :as cstr]))

#?(:clj (set! *warn-on-reflection* true))

;; --- 4x4 matrix helpers (column-major, as CSS matrix3d expects)
;;
;; A 4x4 matrix is stored as a 16-element vector in COLUMN-MAJOR order:
;;   index = col*4 + row
;; i.e. [m11 m12 m13 m14  m21 m22 m23 m24  m31 m32 m33 m34  m41 m42 m43 m44]
;; which is exactly the argument order CSS `matrix3d(...)` specifies.
;; https://www.w3.org/TR/css-transforms-2/#funcdef-transform-matrix3d

(defn- identity-matrix
  []
  [1 0 0 0
   0 1 0 0
   0 0 1 0
   0 0 0 1])

(defn- rx-matrix
  "Rotation about the X axis by `deg` degrees (column-major 4x4)."
  [deg]
  (let [a (mth/radians deg)
        c (mth/cos a)
        s (mth/sin a)]
    [1 0    0     0
     0 c    s     0
     0 (- s) c    0
     0 0    0     1]))

(defn- ry-matrix
  "Rotation about the Y axis by `deg` degrees (column-major 4x4)."
  [deg]
  (let [a (mth/radians deg)
        c (mth/cos a)
        s (mth/sin a)]
    [c    0 (- s) 0
     0    1 0     0
     s    0 c     0
     0    0 0     1]))

(defn- rz-matrix
  "Rotation about the Z axis by `deg` degrees (column-major 4x4)."
  [deg]
  (let [a (mth/radians deg)
        c (mth/cos a)
        s (mth/sin a)]
    [c    s    0 0
     (- s) c    0 0
     0    0    1 0
     0    0    0 1]))

(defn- persp-matrix
  "CSS-style perspective matrix for a perspective distance `p` (px).
  Returns identity when `p` is nil or non-positive (perspective disabled)."
  [p]
  (if (and (some? p) (pos? p))
    (let [d (/ -1.0 p)]
      [1 0 0 0
       0 1 0 0
       0 0 1 0
       0 0 d 1])
    (identity-matrix)))

(defn- mat-get
  "Row `r`, col `c` (0-indexed) of a column-major 16-element matrix."
  [m r c]
  (aget m (+ (* c 4) r)))

(defn- mat-mul
  "Multiply two 4x4 column-major matrices A*B, returning a 16-element
  column-major vector. (A*B)[row][col] = sum_k A[row][k]*B[k][col]."
  [a b]
  (letfn [(cell [r c]
            (+ (* (mat-get a r 0) (mat-get b 0 c))
               (* (mat-get a r 1) (mat-get b 1 c))
               (* (mat-get a r 2) (mat-get b 2 c))
               (* (mat-get a r 3) (mat-get b 3 c))))]
    [(cell 0 0) (cell 1 0) (cell 2 0) (cell 3 0)
     (cell 0 1) (cell 1 1) (cell 2 1) (cell 3 1)
     (cell 0 2) (cell 1 2) (cell 2 2) (cell 3 2)
     (cell 0 3) (cell 1 3) (cell 2 3) (cell 3 3)]))

(defn- transform-3d->matrix
  "Build the combined 4x4 column-major matrix (Rx*Ry*Rz*P) for a
  :transform-3d slot map. Returns nil when no rotation/perspective is
  set — this is the load-bearing nil that keeps absent/empty slots
  byte-identical to today's flat 2D render."
  [t3d]
  (let [rx (some? (:rotation-x t3d))
        ry (some? (:rotation-y t3d))
        rz (some? (:rotation-z t3d))
        ps (and (some? (:perspective t3d)) (pos? (:perspective t3d)))]
    (when (or rx ry rz ps)
      (-> (identity-matrix)
          (cond-> rx (mat-mul (rx-matrix (:rotation-x t3d))))
          (cond-> ry (mat-mul (ry-matrix (:rotation-y t3d))))
          (cond-> rz (mat-mul (rz-matrix (:rotation-z t3d))))
          (cond-> ps (mat-mul (persp-matrix (:perspective t3d))))))))

(defn transform-3d-css-str
  "Returns a CSS `matrix3d(m11,m12,...,m44)` string (16 values,
  column-major as CSS expects) for the :transform-3d slot of `shape`, or
  nil when the slot is absent or has no rotation/perspective set.

  Mirrors the guard convention of `gsh/transform-str` (transforms.cljc),
  which returns `\"\"`/nil when nothing is set; here we return nil so the
  mount sites can guard with `some?` and emit zero new attributes."
  [shape]
  (let [t3d (:transform-3d shape)]
    (when (some? t3d)
      (when-let [m (transform-3d->matrix t3d)]
        (let [vals (cstr/join ","
                              (map (fn [v]
                                     (let [rv (mth/round-to-zero v)]
                                       (if (number? rv) rv 0)))
                                   m))]
          (dm/str "matrix3d(" vals ")"))))))

(defn transform-3d-origin
  "Returns the shape center as a CSS transform-origin string
  `\"Xpx Ypx\"`, suitable for `transform-origin` alongside `matrix3d`.
  Returns nil when the shape has no resolvable center."
  [shape]
  (let [center (or (gsh/shape->center shape) (gpt/point 0 0))
        cx     (dm/get-prop center :x)
        cy     (dm/get-prop center :y)]
    (when (and (some? cx) (some? cy))
      (dm/str cx "px " cy "px"))))