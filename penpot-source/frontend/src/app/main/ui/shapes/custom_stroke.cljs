;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.custom-stroke
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.geom.shapes.text :as gst]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path-h]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.embed :as embed]
   [app.main.ui.shapes.gradients :as grad]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; FIXME: this clearly should be renamed to something different, this
;; namespace has also fill related code

(mf/defc inner-stroke-clip-path
  {::mf/wrap-props false}
  [{:keys [shape render-id index]}]
  (let [shape-id (dm/get-prop shape :id)
        suffix   (if (some? index) (dm/str "-" index) "")
        clip-id  (dm/str "inner-stroke-" render-id "-" shape-id suffix)
        href     (dm/str "#stroke-shape-" render-id "-" shape-id suffix)]
    [:> "clipPath" {:id clip-id}
     [:use {:href href}]]))

(mf/defc outer-stroke-mask
  {::mf/wrap-props false}
  [{:keys [shape stroke render-id index]}]
  (let [shape-id       (dm/get-prop shape :id)
        suffix         (if (some? index) (dm/str "-" index) "")
        mask-id        (dm/str "outer-stroke-" render-id "-" shape-id suffix)
        shape-id       (dm/str "stroke-shape-" render-id "-" shape-id suffix)
        href           (dm/str "#" shape-id)

        stroke-width   (case (:stroke-alignment stroke :center)
                         :center (/ (:stroke-width stroke 0) 2)
                         :outer (:stroke-width stroke 0)
                         0)
        stroke-margin  (gsb/shape-stroke-margin shape stroke-width)

        ;; NOTE: for performance reasons we may can delimit a bit the
        ;; dependencies to really useful shape attrs instead of using
        ;; the shepe as-is.
        selrect       (mf/with-memo [shape]
                        (if (cfh/text-shape? shape)
                          (gst/shape->rect shape)
                          (grc/points->rect (:points shape))))

        x             (- (dm/get-prop selrect :x) stroke-margin)
        y             (- (dm/get-prop selrect :y) stroke-margin)
        w             (+ (dm/get-prop selrect :width) (* 2 stroke-margin))
        h             (+ (dm/get-prop selrect :height) (* 2 stroke-margin))]

    [:mask {:id mask-id
            :x x
            :y y
            :width w
            :height h
            :maskUnits "userSpaceOnUse"}
     [:use
      {:href href
       :style {:fill "none"
               :stroke "white"
               :strokeWidth (* stroke-width 2)}}]

     [:use
      {:href href
       :style {:fill "black"
               :stroke "none"}}]]))

(mf/defc cap-markers
  {::mf/wrap-props false}
  [{:keys [stroke render-id index]}]
  (let [id-prefix (dm/str "marker-" render-id)

        gradient  (:stroke-color-gradient stroke)
        image     (:stroke-image stroke)
        cap-start (:stroke-cap-start stroke)
        cap-end   (:stroke-cap-end stroke)

        color     (cond
                    (some? gradient)
                    (str/ffmt "url(#stroke-color-gradient-%-%)" render-id index)

                    (some? image)
                    (str/ffmt "url(#stroke-fill-%-%)" render-id index)

                    :else
                    (:stroke-color stroke))

        opacity   (when-not (some? gradient)
                    (:stroke-opacity stroke))]

    [:*
     (when (or (= cap-start :line-arrow)
               (= cap-end :line-arrow))
       [:marker {:id (dm/str id-prefix "-line-arrow")
                 :viewBox "0 0 3 6"
                 :refX "2"
                 :refY "3"
                 :markerWidth "8.5"
                 :markerHeight "8.5"
                 :orient "auto-start-reverse"
                 :fill color
                 :fillOpacity opacity}
        [:path {:d "M 0.5 0.5 L 3 3 L 0.5 5.5 L 0 5 L 2 3 L 0 1 z"}]])

     (when (or (= cap-start :triangle-arrow)
               (= cap-end :triangle-arrow))
       [:marker {:id (dm/str id-prefix "-triangle-arrow")
                 :viewBox "0 0 3 6"
                 :refX "2"
                 :refY "3"
                 :markerWidth "8.5"
                 :markerHeight "8.5"
                 :orient "auto-start-reverse"
                 :fill color
                 :fillOpacity opacity}
        [:path {:d "M 0 0 L 3 3 L 0 6 z"}]])

     (when (or (= cap-start :square-marker)
               (= cap-end :square-marker))
       [:marker {:id (dm/str id-prefix "-square-marker")
                 :viewBox "0 0 6 6"
                 :refX "3"
                 :refY "3"
                 :markerWidth "4.2426" ;; diagonal length of a 3x3 square
                 :markerHeight "4.2426"
                 :orient "auto-start-reverse"
                 :fill color
                 :fillOpacity opacity}
        [:rect {:x 0 :y 0 :width 6 :height 6}]])

     (when (or (= cap-start :circle-marker)
               (= cap-end :circle-marker))
       [:marker {:id (dm/str id-prefix "-circle-marker")
                 :viewBox "0 0 6 6"
                 :refX "3"
                 :refY "3"
                 :markerWidth "4"
                 :markerHeight "4"
                 :orient "auto-start-reverse"
                 :fill color
                 :fillOpacity opacity}
        [:circle {:cx "3" :cy "3" :r "3"}]])

     (when (or (= cap-start :diamond-marker)
               (= cap-end :diamond-marker))
       [:marker {:id (dm/str id-prefix "-diamond-marker")
                 :viewBox "0 0 6 6"
                 :refX "3"
                 :refY "3"
                 :markerWidth "6"
                 :markerHeight "6"
                 :orient "auto-start-reverse"
                 :fill color
                 :fillOpacity opacity}
        [:path {:d "M 3 0 L 6 3 L 3 6 L 0 3 z"}]])

     ;; If the user wants line caps but different in each end,
     ;; simulate it with markers.
     (when (and (or (= cap-start :round)
                    (= cap-end :round))
                (not= cap-start cap-end))
       [:marker {:id (dm/str id-prefix "-round")
                 :viewBox "0 0 6 6"
                 :refX "3"
                 :refY "3"
                 :markerWidth "6"
                 :markerHeight "6"
                 :orient "auto-start-reverse"
                 :fill color
                 :fillOpacity opacity}
        [:path {:d "M 3 2.5 A 0.5 0.5 0 0 1 3 3.5 "}]])

     (when (and (or (= cap-start :square)
                    (= cap-end :square))
                (not= cap-start cap-end))
       [:marker {:id (dm/str id-prefix "-square")
                 :viewBox "0 0 6 6"
                 :refX "3"
                 :refY "3"
                 :markerWidth "6"
                 :markerHeight "6"
                 :orient "auto-start-reverse"
                 :fill color
                 :fillOpacity opacity}
        [:rect {:x 3 :y 2.5 :width 0.5 :height 1}]])]))

(mf/defc stroke-defs
  {::mf/wrap-props false}
  [{:keys [shape stroke render-id index]}]
  (let [open-path?    (and ^boolean (cfh/path-shape? shape)
                           ^boolean (path/shape-with-open-path? shape))
        gradient      (:stroke-color-gradient stroke)
        alignment     (:stroke-alignment stroke :center)
        width         (:stroke-width stroke 0)

        props         #js {:id (dm/str "stroke-color-gradient-" render-id "-" index)
                           :gradient gradient
                           :shape shape
                           :force-transform (cfh/path-shape? shape)}
        stroke-image  (:stroke-image stroke)
        uri           (when stroke-image (cf/resolve-file-media stroke-image))
        embed         (embed/use-data-uris [uri])

        stroke-width  (case (:stroke-alignment stroke :center)
                        :center (/ (:stroke-width stroke 0) 2)
                        :outer (:stroke-width stroke 0)
                        0)
        margin        (gsb/shape-stroke-margin stroke stroke-width)

        selrect       (mf/with-memo [shape]
                        (if (cfh/text-shape? shape)
                          (gst/shape->rect shape)
                          (grc/points->rect (:points shape))))

        stroke-margin (+ stroke-width margin)

        w             (+ (dm/get-prop selrect :width) (* 2 stroke-margin))
        h             (+ (dm/get-prop selrect :height) (* 2 stroke-margin))
        image-props   #js {:href (get embed uri uri)
                           :preserveAspectRatio "xMidYMid slice"
                           :width 1
                           :height 1
                           :id (dm/str "stroke-image-" render-id "-" index)}]
    [:*
     (when (some? gradient)
       (case (:type gradient)
         :linear  [:> grad/linear-gradient props]
         :radial  [:> grad/radial-gradient props]
         :angular [:> grad/angular-gradient props]
         ;; Figma-parity mesh gradient (gap #21) — tessellated <pattern>
         ;; of bilinearly-interpolated filled quads (see gradients.cljs).
         ;; Only reached when (:type gradient) is :mesh; legacy stroke
         ;; gradients never carry :type :mesh, so existing arms render
         ;; byte-identically.
         :mesh    [:> grad/mesh-gradient props]
         ;; diamond approximated as radial in v1 (see gradients.cljs).
         :diamond [:> grad/radial-gradient props]))

     (when (:stroke-image stroke)
       ;; We need to make the pattern size and the image fit so it's not repeated
       [:pattern {:id (dm/str "stroke-fill-" render-id "-" index)
                  :patternContentUnits "objectBoundingBox"
                  :x (- (/ stroke-margin (dm/get-prop selrect :width)))
                  :y (- (/ stroke-margin (dm/get-prop selrect :height)))
                  :width (/ w (dm/get-prop selrect :width))
                  :height (/ h (dm/get-prop selrect :height))
                  :viewBox "0 0 1 1"
                  :preserveAspectRatio "xMidYMid slice"
                  :patternTransform (when (cfh/path-shape? shape) (gsh/transform-str shape))}
        [:> :image image-props]])

     (cond
       (and (not open-path?)
            (= :inner alignment)
            (> width 0))
       [:& inner-stroke-clip-path {:shape shape
                                   :render-id render-id
                                   :index index}]

       (and (not open-path?)
            (= :outer alignment)
            (> width 0))
       [:& outer-stroke-mask {:shape shape
                              :stroke stroke
                              :render-id render-id
                              :index index}]

       (or (some? (:stroke-cap-start stroke))
           (some? (:stroke-cap-end stroke)))
       [:& cap-markers {:stroke stroke
                        :render-id render-id
                        :index index}])]))

;; Outer alignment: display the shape in two layers. One without
;; stroke (only fill), and another one only with stroke at double
;; width (transparent fill) and passed through a mask that shows the
;; whole shape, but hides the original shape without stroke

(mf/defc outer-stroke
  {::mf/wrap-props false}
  [{:keys [children shape stroke index]}]
  (let [shape-id     (dm/get-prop shape :id)
        render-id    (mf/use-ctx muc/render-id)

        props        (obj/get children "props")
        style        (obj/get props "style")

        stroke-width (:stroke-width stroke 0)

        suffix       (if (some? index) (dm/str "-" index) "")
        mask-id      (dm/str "outer-stroke-" render-id "-" shape-id suffix)
        shape-id     (dm/str "stroke-shape-" render-id "-" shape-id suffix)
        href         (dm/str "#" shape-id)]

    [:g.outer-stroke-shape
     [:defs
      [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]
      (let [type  (obj/get children "type")
            style (-> (obj/clone style)
                      (obj/unset! "fill")
                      (obj/unset! "fillOpacity")
                      (obj/unset! "stroke")
                      (obj/unset! "strokeWidth")
                      (obj/unset! "strokeOpacity")
                      (obj/unset! "strokeStyle")
                      (obj/unset! "strokeDasharray"))
            props (-> (obj/clone props)
                      (obj/set! "id" shape-id)
                      (obj/set! "style" style))]

        [:> type props])]

     [:use {:href href
            :mask (dm/str "url(#" mask-id ")")
            :style (-> (obj/clone style)
                       (obj/set! "strokeWidth" (* stroke-width 2))
                       (obj/set! "fill" "none")
                       (obj/unset! "fillOpacity"))}]

     [:use {:href href
            :style (-> (obj/clone style)
                       (obj/set! "stroke" "none"))}]]))


;; Inner alignment: display the shape with double width stroke, and
;; clip the result with the original shape without stroke.

(mf/defc inner-stroke
  {::mf/wrap-props false}
  [props]
  (let [child        (unchecked-get props "children")
        shape        (unchecked-get props "shape")
        stroke       (unchecked-get props "stroke")
        index        (unchecked-get props "index")

        shape-id     (dm/get-prop shape :id)
        render-id    (mf/use-ctx muc/render-id)

        type         (obj/get child "type")

        props        (-> (obj/get child "props") obj/clone)
        ;; FIXME: check if style need to be cloned
        style        (-> (obj/get props "style") obj/clone)
        transform    (obj/get props "transform")

        stroke-width (:stroke-width stroke 0)

        suffix       (if (some? index) (dm/str "-" index) "")
        clip-id      (dm/str "inner-stroke-" render-id "-" shape-id suffix)
        shape-id     (dm/str "stroke-shape-" render-id "-" shape-id suffix)
        clip-path    (dm/str "url('#" clip-id "')")

        style        (obj/set! style "strokeWidth" (* stroke-width 2))

        props        (-> props
                         (obj/set! "id" (dm/str shape-id))
                         (obj/set! "style" style)
                         (obj/unset! "transform"))]

    [:g.inner-stroke-shape
     {:transform transform}
     [:defs
      [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]
      [:> type props]]

     [:use {:href (dm/str "#" shape-id)
            :clipPath clip-path}]]))

;; The SVG standard does not implement yet the 'stroke-alignment'
;; attribute, to define the position of the stroke relative to the
;; stroke axis (inner, center, outer). Here we implement a patch to be
;; able to draw the stroke in the three cases. See discussion at:
;; https://stackoverflow.com/questions/7241393/can-you-control-how-an-svgs-stroke-width-is-drawn

;; Figma-parity: per-side stroke widths. SVG has no per-side stroke
;; support, so for :stroke-width-mode :per-side on rect/frame we draw
;; four independent <line> elements (one per edge) in the shape's local
;; space, inside the shape transform. Square line-caps extend each edge
;; half its own width past the corner point, filling the corners so that
;; equal widths render a clean miter and unequal widths let the wider
;; edge dominate — a close match to Figma's per-side stroke rendering.
;; Stroke alignment is honored as a perpendicular outward/inward offset
;; of each edge by half its own width. Corner radius is NOT followed
;; (edges are straight corner-to-corner); when radius>0 the fill stays
;; rounded while the stroke is square-cornered — a known limitation.
;; Dash style is applied per edge using that edge's own width. Join/
;; miter-limit/caps (arrow markers) do not apply to rect edges and are
;; ignored in per-side mode.
(defn- per-side-dasharray
  [style width dash gap]
  (let [w+5  (+ 5 width)
        w+1  (+ 1 width)
        w+10 (+ 10 width)]
    (case style
      :mixed  (str/concat "" w+5 "," w+5 "," w+1 "," w+5)
      :dotted (str/concat "0," w+5)
      :dashed (str/concat "" (or dash w+10) "," (or gap w+10))
      "")))

(mf/defc per-side-stroke
  {::mf/wrap-props false}
  [{:keys [shape stroke index render-id]}]
  (let [x      (dm/get-prop shape :x)
        y      (dm/get-prop shape :y)
        w      (dm/get-prop shape :width)
        h      (dm/get-prop shape :height)
        t      (gsh/transform-str shape)

        style  (:stroke-style stroke :solid)
        base-w (:stroke-width stroke 0)
        top    (or (:stroke-top stroke) base-w 0)
        right  (or (:stroke-right stroke) base-w 0)
        bottom (or (:stroke-bottom stroke) base-w 0)
        left   (or (:stroke-left stroke) base-w 0)

        align  (:stroke-alignment stroke :center)
        ;; Outward offset of each edge by half its own width.
        ;; center = 0, outer = +half (outward), inner = -half (inward).
        oy-top    (case align :outer (- 0 (/ top 2))    :inner (/ top 2)    0)
        ox-right  (case align :outer (/ right 2)       :inner (- 0 (/ right 2))  0)
        oy-bottom (case align :outer (/ bottom 2)      :inner (- 0 (/ bottom 2)) 0)
        ox-left   (case align :outer (- 0 (/ left 2))   :inner (/ left 2)   0)

        gradient (:stroke-color-gradient stroke)
        image    (:stroke-image stroke)
        color    (:stroke-color stroke)
        opacity  (:stroke-opacity stroke)
        paint    (cond
                   (some? gradient) (dm/fmt "url(#stroke-color-gradient-%-%)" render-id index)
                   (some? image)    (dm/fmt "url(#stroke-fill-%-%)" render-id index)
                   (some? color)    color
                   :else            "none")

        dash-top    (per-side-dasharray style top    (:stroke-dash stroke) (:stroke-gap stroke))
        dash-right  (per-side-dasharray style right (:stroke-dash stroke) (:stroke-gap stroke))
        dash-bottom (per-side-dasharray style bottom (:stroke-dash stroke) (:stroke-gap stroke))
        dash-left   (per-side-dasharray style left   (:stroke-dash stroke) (:stroke-gap stroke))

        line-props (fn [x1 y1 x2 y2 width dash]
                     #js {:x1 x1 :y1 y1 :x2 x2 :y2 y2
                          :stroke paint
                          :strokeWidth width
                          :strokeOpacity opacity
                          :strokeLinecap "square"
                          :strokeDasharray dash})]

    [:g.stroke-shape {:transform t}
     [:defs
      [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]]
     [:> :line (line-props x              (+ y oy-top)    (+ x w)        (+ y oy-top)    top    dash-top)]
     [:> :line (line-props (+ x w ox-right) y              (+ x w ox-right) (+ y h)        right  dash-right)]
     [:> :line (line-props (+ x w)         (+ y h oy-bottom) x            (+ y h oy-bottom) bottom dash-bottom)]
     [:> :line (line-props (+ x ox-left)   (+ y h)        (+ x ox-left)   y              left   dash-left)]]))

;; Figma-parity variable-width strokes (gap #53). SVG has no native
;; variable-width stroke, so for :path shapes carrying a non-empty
;; :segment-widths map we render the stroke as a FILLED outline polygon
;; built by offsetting the centerline ±(w/2) along the perpendicular of
;; the incoming tangent at each node. Curve-to segments are sampled at
;; several t values; each sample is offset along the curve-tangent
;; perpendicular with a linearly interpolated half-width. The outline is
;; closed (Z) so it renders as a solid filled band. PURELY ADDITIVE: the
;; caller (shape-custom-stroke) gates this on (cfh/path-shape? shape) AND
;; (some? :segment-widths) AND (pos? count) — legacy paths with no
;; :segment-widths fall through to the existing cond branches and emit
;; byte-identical SVG. path/get-segment-width returns base-w for any index
;; absent from the map, so per-segment lookups are safe even for partial
;; width maps. Returns nil when fewer than 2 usable samples exist; the
;; component then renders nothing.
(defn- variable-width-outline-d
  [shape stroke]
  (let [content (some-> shape :content seq)
        widths  (:segment-widths shape)
        base-w  (:stroke-width stroke 0)]
    (when (and content (map? widths))
      (let [n (count content)
            samples
            (loop [i 0 prev-pt nil move-start nil acc []]
              (if (>= i n)
                acc
                (let [seg    (nth content i)
                      cmd    (:command seg)
                      params (:params seg)
                      pt     (path-h/segment->point seg)]
                  (case cmd
                    :move-to
                    (recur (inc i) pt pt acc)

                    :line-to
                    (let [w     (path/get-segment-width widths i base-w)
                          tang  (if (and prev-pt pt (not= prev-pt pt))
                                  (gpt/unit (gpt/to-vec prev-pt pt))
                                  (gpt/point 1 0))]
                      (recur (inc i) pt move-start
                             (conj acc [pt (/ w 2) tang])))

                    :curve-to
                    (if (nil? prev-pt)
                      (recur (inc i) pt move-start acc)
                      (let [h1     (gpt/point (:c1x params) (:c1y params))
                            h2     (gpt/point (:c2x params) (:c2y params))
                            w-i    (path/get-segment-width widths i base-w)
                            w-prev (path/get-segment-width widths (dec i) base-w)
                            curve  [prev-pt pt h1 h2]
                            ts     [0.25 0.5 0.75]
                            acc'   (reduce
                                    (fn [a t]
                                      (let [sp   (path-h/curve-values curve t)
                                            sw   (+ w-prev (* (- w-i w-prev) t))
                                            tang (path-h/curve-tangent curve t)]
                                        (conj a [sp (/ sw 2) tang])))
                                    acc ts)]
                        (recur (inc i) pt move-start
                               (conj acc' [pt (/ w-i 2)
                                           (path-h/curve-tangent curve 1)]))))

                    :close-path
                    (let [w    (path/get-segment-width widths i base-w)
                          tang (if (and prev-pt move-start (not= prev-pt move-start))
                                 (gpt/unit (gpt/to-vec prev-pt move-start))
                                 (gpt/point 1 0))]
                      (recur (inc i) move-start move-start
                             (conj acc [move-start (/ w 2) tang])))

                    ;; unknown command — skip, keep prev-pt/move-start
                    (recur (inc i) prev-pt move-start acc)))))
            samples (vec samples)]
        (when (>= (count samples) 2)
          (let [pairs  (mapv (fn [[p hw tang]]
                                (let [norm  (gpt/perpendicular tang)
                                      left  (gpt/add p (gpt/scale norm hw))
                                      right (gpt/add p (gpt/scale norm (- 0 hw)))]
                                  [left right]))
                              samples)
                lefts  (mapv first pairs)
                rights (mapv second pairs)
                p0     (first lefts)
                body-l (str/join " L " (map gpt/point->str (next lefts)))
                body-r (str/join " L " (map gpt/point->str (rseq rights)))]
            (str "M " (gpt/point->str p0)
                 (when (seq body-l) (str " L " body-l))
                 (when (seq body-r) (str " L " body-r))
                 " Z")))))))

(mf/defc variable-width-stroke
  {::mf/wrap-props false}
  [{:keys [shape stroke render-id index]}]
  (let [outline-d (mf/with-memo [shape stroke]
                    (variable-width-outline-d shape stroke))
        gradient  (:stroke-color-gradient stroke)
        image     (:stroke-image stroke)
        color     (:stroke-color stroke)
        opacity   (:stroke-opacity stroke)
        paint     (cond
                    (some? gradient) (dm/fmt "url(#stroke-color-gradient-%-%)" render-id index)
                    (some? image)    (dm/fmt "url(#stroke-fill-%-%)" render-id index)
                    (some? color)    color
                    :else            "none")
        t         (gsh/transform-str shape)]
    (when (some? outline-d)
      [:g.stroke-shape
       [:defs
        [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]]
       [:path {:d outline-d
               :fill paint
               :fillOpacity opacity
               :stroke "none"
               :transform t}]])))

(mf/defc shape-custom-stroke
  {::mf/wrap-props false}
  [props]
  (let [child           (unchecked-get props "children")
        shape           (unchecked-get props "shape")
        stroke          (unchecked-get props "stroke")
        index           (unchecked-get props "index")

        render-id       (mf/use-ctx muc/render-id)
        render-id       (d/nilv (unchecked-get props "render-id") render-id)

        stroke-width    (:stroke-width stroke 0)
        stroke-style    (:stroke-style stroke :none)
        stroke-position (:stroke-alignment stroke :center)

        has-stroke?     (and (> stroke-width 0)
                             (not= stroke-style :none))
        closed?         (or (not ^boolean (cfh/path-shape? shape))
                            (not ^boolean (path/shape-with-open-path? shape)))
        inner?          (= :inner stroke-position)
        outer?          (= :outer stroke-position)

        ;; Figma-parity per-side stroke widths (rect/frame only).
        per-side?       (and (not= stroke-style :none)
                             (= (:stroke-width-mode stroke) :per-side)
                             (or ^boolean (cfh/rect-shape? shape)
                                 ^boolean (cfh/frame-shape? shape))
                             (let [base  stroke-width
                                   top    (or (:stroke-top stroke)    base 0)
                                   right  (or (:stroke-right stroke)  base 0)
                                   bottom (or (:stroke-bottom stroke) base 0)
                                   left   (or (:stroke-left stroke)   base 0)]
                               (or (> top 0) (> right 0) (> bottom 0) (> left 0))))

        ;; Figma-parity variable-width strokes (gap #53). Only :path shapes
        ;; with a non-empty :segment-widths map. FIRST cond clause so legacy
        ;; paths (no :segment-widths) fall through untouched = byte-identical.
        variable-width? (and ^boolean (cfh/path-shape? shape)
                             (some? (:segment-widths shape))
                             (pos? (count (:segment-widths shape))))]

    (cond
      variable-width?
      [:& variable-width-stroke {:shape shape :stroke stroke :index index :render-id render-id}]

      per-side?
      [:& per-side-stroke {:shape shape :stroke stroke :index index :render-id render-id}]

      (and has-stroke? inner? closed?)
      [:& inner-stroke {:shape shape :stroke stroke :index index} child]

      (and has-stroke? outer? closed?)
      [:& outer-stroke {:shape shape :stroke stroke :index index} child]

      :else
      [:g.stroke-shape
       [:defs
        [:& stroke-defs {:shape shape :stroke stroke :render-id render-id :index index}]]
       child])))

(defn- build-fill-element
  [shape child position render-id]
  (let [type         (obj/get child "type")
        props        (-> (obj/get child "props")
                         (obj/clone))
        props        (attrs/add-fill-props! props shape position render-id)]
    (mf/html [:> type props])))

(defn- build-stroke-element
  [child value position render-id open-path?]
  (let [props (obj/get child "props")
        type  (obj/get child "type")

        style (-> (obj/get props "style")
                  (obj/clone)
                  (obj/set! "fill" "none")
                  (obj/set! "fillOpacity" "none")
                  (attrs/add-stroke! value render-id position open-path?))

        style (if (:stroke-image value)
                (obj/set! style "stroke" (dm/fmt "url(#stroke-fill-%-%)" render-id position))
                style)

        props (-> (obj/clone props)
                  (obj/unset! "fill")
                  (obj/unset! "fillOpacity")
                  (obj/set! "style" style))]

    (mf/html [:> type props])))

(mf/defc shape-fills
  {::mf/wrap-props false}
  [props]
  (let [child      (unchecked-get props "children")
        shape      (unchecked-get props "shape")

        shape-id   (dm/get-prop shape :id)

        position   (d/nilv (unchecked-get props "position") 0)

        render-id  (mf/use-ctx muc/render-id)
        render-id  (d/nilv (unchecked-get props "render-id") render-id)]

    [:g.fills {:id (dm/fmt "fills-%" shape-id)}
     (build-fill-element shape child position render-id)]))

(mf/defc shape-strokes
  {::mf/wrap-props false}
  [props]
  (let [child         (unchecked-get props "children")
        shape         (unchecked-get props "shape")

        shape-id      (dm/get-prop shape :id)

        render-id     (mf/use-ctx muc/render-id)
        render-id     (d/nilv (unchecked-get props "render-id") render-id)

        strokes       (get shape :strokes)

        ;; Generate a unique id when the strokes change. This way we can solve some
        ;; render issues in Safari https://tree.taiga.io/project/penpot/issue/9040
        prefix        (mf/use-memo (mf/deps strokes) #(dm/str (uuid/next)))

        stroke-id     (dm/str (dm/fmt "strokes-%-%" prefix shape-id))

        shape-blur   (get shape :blur)

        shape-fills   (get shape :fills)
        shape-shadow  (get shape :shadow)
        shape-strokes (not-empty strokes)

        svg-attrs     (attrs/get-svg-props shape render-id)

        style         (-> (obj/get props "style")
                          (obj/clone)
                          (obj/merge! (obj/get svg-attrs "style")))

        props        (mf/spread-props svg-attrs
                                      {:id stroke-id
                                       :className "strokes"
                                       :style style})

        open-path?    (and ^boolean (cfh/path-shape? shape)
                           ^boolean (path/shape-with-open-path? shape))]

    (when-not ^boolean (cfh/frame-shape? shape)
      (when (and (some? shape-blur)
                 (not ^boolean (:hidden shape-blur)))
        (obj/set! props "filter" (dm/fmt "url(#filter-blur-%)" render-id)))

      (when (and (empty? shape-fills)
                 (some? (->> shape-shadow (remove :hidden) not-empty)))
        (obj/set! props "filter" (dm/fmt "url(#filter-%)" render-id))))

    (when (some? shape-strokes)
      [:> :g props
       (for [[index value] (reverse (d/enumerate shape-strokes))
             :when (not (:hidden value))]
         [:& shape-custom-stroke {:shape shape
                                  :stroke value
                                  :index index
                                  :key (dm/str index "-" stroke-id)}
          (build-stroke-element child value index render-id open-path?)])])))

(mf/defc shape-custom-strokes
  {::mf/wrap-props false}
  [props]
  (let [shape    (unchecked-get props "shape")
        child    (unchecked-get props "children")
        outline? (mf/use-ctx muc/outline-mode?)
        ;; GUARD: emit the fallback ONLY under outline mode, when the shape
        ;; has NO visible strokes, and only for leaf shapes (not frame /
        ;; group, which render their children/strokes differently). When the
        ;; guard is false (context defaults false, non-workspace renders
        ;; never mount the provider -> outline? is false) NO new element is
        ;; emitted -> byte-identical with today.
        fallback? (and outline?
                       (empty? (->> (:strokes shape) (remove :hidden)))
                       (not ^boolean (cfh/frame-shape? shape))
                       (not ^boolean (cfh/group-shape? shape)))]
    [:*
     [:> shape-fills props]
     [:> shape-strokes props]
     (when fallback?
       (let [ctype   (obj/get child "type")
             cprops  (obj/get child "props")
             ovr     #js {:fill "none"
                          :stroke "currentColor"
                          :strokeWidth 1
                          :strokeOpacity 1}
             props'  (-> (obj/clone cprops)
                         (obj/set! "style" ovr))]
         ;; Wrapped in [:g.outline-fallback] as a stable hook. The
         ;; .outline-mode CSS `:is(...)` fill:transparent !important still
         ;; applies to the child, but fill:transparent is visually identical
         ;; to the inline fill:none; the guaranteed outline stroke
         ;; (currentColor, width 1) is untouched because the outline-mode
         ;; rule never sets stroke.
         [:g.outline-fallback
          [:> ctype props']]))]))
