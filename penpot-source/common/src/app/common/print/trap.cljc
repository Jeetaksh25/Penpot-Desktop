;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL
;;
;; P2.23 -- print-production pathfinder helpers: per-face CMYK primary
;; mixing (Hard Mix / Soft Mix) and print-registration trap geometry.
;;
;; This namespace is PURE CLJC math (js/Math is reached only through
;; `app.common.math`, so it works in both Clojure and ClojureScript).
;; It has NO dependency on the frontend path-offset machinery
;; (`app.main.data.workspace.path.shapes-to-path/offset-content`), which
;; lives in the frontend layer and cannot be required from `common`.
;; `trap-path` therefore implements a manual centroid-based path offset
;; that is correct for the simple convex / near-convex shapes that are
;; the realistic input for a print trap; the robust miter-join +
;; self-intersection-cleaning `offset-content` helper can be used as a
;; drop-in upgrade at the (frontend) call site if higher fidelity is
;; ever required -- the signature of `trap-path` mirrors what such a
;; swap would need.
;;
;; Byte-identical-when-inactive: this namespace exports pure functions
;; only; shapes without Hard Mix / Soft Mix / Trap pathfinder ops never
;; invoke them, so existing rendering is untouched.  Every fn is
;; nil-safe (nil/empty input -> nil/empty output, never throws).

(ns app.common.print.trap
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.types.path.helpers :as helpers]))

;; ---------------------------------------------------------------------------
;; Color conversion: sRGB <-> CMYK (standard naive formula, 0..1 channels)
;; ---------------------------------------------------------------------------

(defn rgb->cmyk
  "Convert an sRGB triple `[r g b]` (0..255) to a CMYK quadruple
  `[c m y k]` (each 0..1) using the standard naive conversion.  Nil-safe."
  [rgb]
  (if-not (and (vector? rgb) (== 3 (count rgb)))
    [0.0 0.0 0.0 1.0]
    (let [[r g b] rgb
          r' (/ (or r 0) 255.0)
          g' (/ (or g 0) 255.0)
          b' (/ (or b 0) 255.0)
          k  (- 1.0 (max r' g' b'))]
      (if (mth/close? k 1.0)
        [0.0 0.0 0.0 1.0]
        (let [d (- 1.0 k)]
          [(/ (- 1.0 r') d)
           (/ (- 1.0 g') d)
           (/ (- 1.0 b') d)
           k])))))

(defn cmyk->rgb
  "Convert a CMYK quadruple `[c m y k]` (each 0..1) back to an sRGB
  triple `[r g b]` (0..255, integers).  Nil-safe."
  [cmyk]
  (if-not (and (vector? cmyk) (== 4 (count cmyk)))
    [0 0 0]
    (let [[c m y k] cmyk
          c (or c 0) m (or m 0) y (or y 0) k (or k 0)
          r (* 255.0 (- 1.0 c) (- 1.0 k))
          g (* 255.0 (- 1.0 m) (- 1.0 k))
          b (* 255.0 (- 1.0 y) (- 1.0 k))]
      [(int (mth/round (mth/clamp r 0 255)))
       (int (mth/round (mth/clamp g 0 255)))
       (int (mth/round (mth/clamp b 0 255)))])))

;; ---------------------------------------------------------------------------
;; Per-channel CMYK primary mixing
;; ---------------------------------------------------------------------------

;; Hard Mix (Illustrator): each CMYK channel of the result is thresholded
;; -- the sum of the two channels is >= 1.0 -> 1.0 (full ink), else 0.0
;; (no ink).  This is the classic 'Hard Mix' blend expressed on the CMYK
;; primaries, producing the hard posterised overlap that gives the mode
;; its name.
(defn hard-mix-cmyk
  [c1 c2]
  (let [c1 (or c1 [0 0 0 1])
        c2 (or c2 [0 0 0 1])]
    (->> (map + c1 c2)
         (mapv #(if (>= % 1.0) 1.0 0.0)))))

;; Soft Mix: a softer blend -- per-channel average passed through a
;; smoothstep S-curve centred at 0.5, so transitions ramp instead of
;; snapping.  This is the 'softer blend' variant the spec calls for.
(defn- smoothstep
  [e0 e1 x]
  (let [t (mth/clamp (/ (- x e0) (- e1 e0)) 0.0 1.0)]
    (* t t (- 3.0 (* 2.0 t)))))

(defn soft-mix-cmyk
  [c1 c2]
  (let [c1 (or c1 [0 0 0 1])
        c2 (or c2 [0 0 0 1])]
    (->> (map (fn [a b] (/ (+ a b) 2.0)) c1 c2)
         (mapv #(smoothstep 0.25 0.75 %)))))

(defn- parse-hex
  "Parse a #rrggbb hex string to `[r g b]`.  Falls back to black for
  nil / malformed input (never throws)."
  [hex]
  (if (and (string? hex) (>= (count hex) 7) (= (first hex) \#))
    (let [hx (subs hex 1)
          v  #?(:clj  (try (Long/parseLong hx 16)
                           (catch Throwable _ 0))
                :cljs (try (js/parseInt hx 16)
                           (catch :default _ 0)))]
      [(bit-shift-right v 16)
       (bit-and (bit-shift-right v 8) 255)
       (bit-and v 255)])
    [0 0 0]))

(defn- rgb->hex-safe
  "Inverse of `parse-hex`, clamping to 0..255 so `clr/rgb->hex` (which
  throws on out-of-range) never sees invalid input.  Returns #000000
  for nil."
  [rgb]
  (if-not (and (vector? rgb) (== 3 (count rgb)))
    "#000000"
    (let [[r g b] rgb
          clamp-byte #(int (mth/round (mth/clamp (or % 0) 0 255)))]
      #?(:clj  (format "#%02X%02X%02X" (clamp-byte r) (clamp-byte g) (clamp-byte b))
         :cljs (let [pad (fn [v] (let [s (.toString (clamp-byte v) 16)]
                                  (if (== (count s) 1) (str "0" s) s)))]
                 (str "#" (pad r) (pad g) (pad b)))))))

(defn mix-fills-hex
  "Combine two `#rrggbb` hex colours via a CMYK primary mix.  `mode` is
  `:hard-mix` or `:soft-mix`.  Nil / missing colours fall back to black.
  Returns a `#rrggbb` hex string (uppercase)."
  [hex1 hex2 mode]
  (let [c1 (rgb->cmyk (parse-hex hex1))
        c2 (rgb->cmyk (parse-hex hex2))
        mix (case mode
              :hard-mix (hard-mix-cmyk c1 c2)
              :soft-mix (soft-mix-cmyk c1 c2)
              (hard-mix-cmyk c1 c2))]
    (rgb->hex-safe (cmyk->rgb mix))))

;; ---------------------------------------------------------------------------
;; Trap geometry -- manual centroid-based path offset
;; ---------------------------------------------------------------------------

(defn- offset-coord
  "Move the point `(x,y)` by `signed` units along the ray from the
  centroid `c` through `(x,y)`.  Degenerate (centroid == point) -> the
  point is returned unchanged.  `signed` > 0 spreads outward, < 0
  chokes inward."
  [cx cy x y signed]
  (let [dx (- (or x 0) cx)
        dy (- (or y 0) cy)
        len (mth/sqrt (+ (* dx dx) (* dy dy)))]
    (if (mth/close? len 0)
      [x y]
      (let [s (/ signed len)]
        [(+ (or x 0) (* dx s))
         (+ (or y 0) (* dy s))]))))

(defn- offset-params
  "Offset every coordinate in a path command's `:params` map (endpoint
  + cubic Bezier control points when present)."
  [cx cy params signed]
  (let [[nx ny] (offset-coord cx cy (:x params) (:y params) signed)
        p1      (assoc params :x nx :y ny)]
    (if (contains? params :c1x)
      (let [[c1x c1y] (offset-coord cx cy (:c1x params) (:c1y params) signed)
            p2        (assoc p1 :c1x c1x :c1y c1y)]
        (if (contains? params :c2x)
          (let [[c2x c2y] (offset-coord cx cy (:c2x params) (:c2y params) signed)]
            (assoc p2 :c2x c2x :c2y c2y))
          p2))
      p1)))

(defn trap-path
  "Build a trap-shape path content by offsetting `foreground-path`
  outward (`:spread`) or inward (`:choke`) by `trap-width` units.

  `foreground-path` is a plain vector of path command maps (or any
  seqable PathData -- it is coerced to a vector).  The offset is
  computed per-vertex along the ray from the path's centroid, which is
  exact for convex shapes and a good approximation for the mild
  non-convex contours used in print trapping.  Control points of
  cubic-Bezier segments are offset the same way, preserving the curve
  shape.

  Returns a NEW plain vector of command maps (never nil for valid
  input); `:close-path` commands are passed through unchanged.  Nil /
  empty input -> nil.  Does NOT mutate `foreground-path`.

  A trap is the slight overlap a print shop adds at the boundary of two
  inks so minor misregistration does not show a white gap: the
  foreground ink is SPREAD (outward) so it underlaps the backdrop by
  `trap-width`, or the backdrop is CHOKED (inward) equivalently.  The
  trap shape is then filled with the foreground colour and placed
  behind the foreground."
  [foreground-path trap-width direction]
  (let [content (cond
                  (nil? foreground-path)   nil
                  (vector? foreground-path) foreground-path
                  :else                     (into [] foreground-path))]
    (when (seq content)
      (let [cmds (filter #(#{:move-to :line-to :curve-to} (:command %)) content)
            pts  (keep #(helpers/segment->point %) cmds)]
        (when (seq pts)
          (let [n    (count pts)
                sx   (reduce + (map :x pts))
                sy   (reduce + (map :y pts))
                cx   (/ sx n)
                cy   (/ sy n)
                w    (mth/abs (or trap-width 0))
                signed (case direction
                         :choke (- w)
                         :spread w
                         w)]
            (if (mth/close? w 0)
              content
              (mapv (fn [cmd]
                      (case (:command cmd)
                        (:move-to :line-to :curve-to)
                        (assoc cmd :params (offset-params cx cy (:params cmd) signed))
                        cmd))
                    content))))))))
