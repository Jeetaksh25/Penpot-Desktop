;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.filters
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.math :as mth]
   [app.common.types.color :as cc]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn get-filter-id []
  (dm/str "filter-" (uuid/next)))

;; SHADER (#64) — SVG-expressible preset set. Only these three named
;; presets get a first-class SVG filter entry (shader-preset-filter*);
;; arbitrary/prompt-built presets fall through to the WebGL2 canvas path
;; (shader-canvas*) and deliberately do NOT enter the SVG filter chain.
(def ^:private svg-expressible-presets #{:clouds :halftone :noise})

(defn svg-expressible-preset?
  "True iff `preset` is one of the named presets expressible as a pure
  SVG filter. Used to gate both the shape->filters append (bounds.cljc)
  and filter-str / add-fill-props!, so a non-SVG or absent preset leaves
  the filters vector at its baseline count and no :filter attr is set."
  [preset]
  (boolean (contains? svg-expressible-presets preset)))

(defn filter-str
  [filter-id shape]
  (when (or (seq (->> (:shadow shape) (remove :hidden)))
            (seq (->> (:noise shape) (remove :hidden)))
            (seq (->> (:texture shape) (remove :hidden)))
            (and (:blur shape)
                 (-> shape :blur :hidden not)
                 (= :layer-blur (-> shape :blur :type)))
            ;; STACKED-BLUR (#60) — :blurs is a VECTOR slot. The gate MUST
            ;; match bounds.cljc shape->filters exactly: at least one
            ;; non-hidden entry with a non-nil :value (the radius). A
            ;; non-hidden entry with nil :value must NOT trigger a url —
            ;; otherwise bounds appends no entry (count stays 2, no
            ;; <filter>) while filter-str returns a url -> dangling :filter
            ;; attr. (keep :value ...) mirrors bounds' (vec (keep :value
            ;; items)) inner (when (seq radii)) gate. Absent/empty/all-
            ;; hidden/all-nil-value -> guard false -> no url -> byte-identical.
            (seq (keep :value (remove :hidden (:blurs shape))))
            ;; GLASS (#61) — :glass is a VECTOR slot. Gate MUST match
            ;; bounds.cljc shape->filters EXACTLY, including the
            ;; :type=:glass predicate apply-filters applies: at least one
            ;; non-hidden entry whose :type is :glass. (The shape slot is
            ;; [:vector ::sm/any] — opaque — and valid-glass-effect? is
            ;; defined but never called on the write path, so the schema
            ;; does NOT enforce :type=:glass; only create-glass sets it.
            ;; Without this predicate, a schema-invalid entry with
            ;; :type!=:glass would make this gate emit a url while bounds
            ;; appends no <filter> -> dangling :filter attr. The predicate
            ;; is a no-op for valid data and gives true 3-gate lockstep
            ;; independent of the permissive slot.) A hidden-only slot
            ;; must NOT trigger a url — otherwise bounds appends no entry
            ;; (apply-filters does (remove :hidden) -> count stays 2 -> no
            ;; <filter>) while filter-str returns a url -> dangling
            ;; :filter attr. Absent/empty/all-hidden/non-glass-type ->
            ;; guard false -> no url -> byte-identical.
            (seq (filter #(= :glass (:type %)) (remove :hidden (:glass shape))))
            ;; SHADER (#64) — :shader-effect VECTOR slot, gated on the
            ;; first non-hidden item's :shader-preset being SVG-expressible.
            ;; The shape->filters append in bounds.cljc uses the same
            ;; guard, so filter-str returns a url exactly when an entry was
            ;; added -> count > 2 -> <filter> emitted. Absent/empty/all-
            ;; hidden/non-SVG preset -> guard false -> no url -> no :filter
            ;; attr -> byte-identical.
            (let [se (first (remove :hidden (:shader-effect shape)))]
              (and (some? se) (svg-expressible-preset? (:shader-preset se)))))
    (str/ffmt "url(#%)" filter-id)))

(mf/defc color-matrix*
  [{:keys [color]}]
  (let [{:keys [color opacity]} color
        [r g b a] (cc/hex->rgba color opacity)
        [r g b] [(/ r 255) (/ g 255) (/ b 255)]]
    [:feColorMatrix
     {:type "matrix"
      :values (str/ffmt "0 0 0 0 % 0 0 0 0 % 0 0 0 0 % 0 0 0 % 0" r g b a)}]))

(mf/defc drop-shadow-filter*
  [{:keys [filter-in filter-id params]}]

  (let [{:keys [color offset-x offset-y blur spread]} params]
    [:*
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"}]
     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "dilate"
                       :in "SourceAlpha"
                       :result filter-id}])

     (when (< spread 0)
       [:feMorphology {:radius (- spread)
                       :operator "erode"
                       :in "SourceAlpha"
                       :result filter-id}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]
     [:> color-matrix* {:color color}]

     [:feBlend {:mode "normal"
                :in2 filter-in
                :result filter-id}]]))

(mf/defc inner-shadow-filter*
  [{:keys [filter-in filter-id params]}]

  (let [{:keys [color offset-x offset-y blur spread]} params]
    [:*
     [:feColorMatrix {:in "SourceAlpha" :type "matrix"
                      :values "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0"
                      :result "hardAlpha"}]

     (when (> spread 0)
       [:feMorphology {:radius spread
                       :operator "erode"
                       :in "SourceAlpha"
                       :result filter-id}])

     [:feOffset {:dx offset-x :dy offset-y}]
     [:feGaussianBlur {:stdDeviation (/ blur 2)}]

     [:feComposite {:in2 "hardAlpha"
                    :operator "arithmetic"
                    :k2 "-1"
                    :k3 "1"}]

     [:> color-matrix* {:color color}]

     [:feBlend {:mode "normal"
                :in2 filter-in
                :result filter-id}]]))

(mf/defc background-blur-filter*
  [{:keys [filter-id params]}]
  [:*
   [:feGaussianBlur {:in "BackgroundImage"
                     :stdDeviation (/ (:value params) 2)}]
   [:feComposite {:in2 "SourceAlpha"
                  :operator "in"
                  :result filter-id}]])

(mf/defc layer-blur-filter*
  [{:keys [filter-id params]}]

  [:feGaussianBlur {:stdDeviation (:value params)
                    :result filter-id}])

(mf/defc image-fix-filter* [{:keys [filter-id]}]
  [:feFlood {:flood-opacity 0 :result filter-id}])

(mf/defc blend-filters* [{:keys [filter-id filter-in]}]
  [:feBlend {:mode "normal"
             :in "SourceGraphic"
             :in2 filter-in
             :result filter-id}])

;; NOISE (#62) — fractal-noise overlay clipped to the shape alpha.
;; Emits ONLY behind a (seq (remove :hidden (:noise shape))) guard in
;; shape->filters / filter-str / add-fill-props!, so an absent/empty
;; slot leaves the filters vector at the baseline count of 2 and the
;; <filter> element / :filter attr are not emitted (byte-identical).
(mf/defc noise-filter*
  [{:keys [filter-id filter-in params]}]
  (let [{:keys [size-x size-y density color-mode colors seed]} params
        size-x     (or size-x 100)
        size-y     (or size-y 100)
        density    (or density 1)
        color-mode (or color-mode :mono)
        seed       (or seed 0)
        base-freq  (dm/str (/ 1 size-x) " " (/ 1 size-y))]
    [:*
     [:feTurbulence {:type "fractalNoise"
                     :baseFrequency base-freq
                     :numOctaves 2
                     :seed seed
                     :result "noiseRaw"}]

     ;; colorize: :mono -> one feColorMatrix mapping luminance->alpha of
     ;; (first colors); :duo/:multi -> feComponentTransfer with discrete
     ;; tableFuncs posterizing the noise into (count colors) levels.
     (case color-mode
       :mono
       (let [color     (first colors)
             hex       (if (map? color) (:color color) nil)
             opacity   (if (map? color) (:opacity color) nil)
             [r g b a] (cc/hex->rgba (or hex "#000000") (or opacity 1))
             [cr cg cb] [(/ r 255) (/ g 255) (/ b 255)]]
         [:feColorMatrix
          {:in "noiseRaw"
           :type "matrix"
           :values (str/ffmt "0 0 0 0 % 0 0 0 0 % 0 0 0 0 % 0.2125 0.7154 0.0721 0 %" cr cg cb a)
           :result "noiseColor"}])

       (let [n     (max 2 (count colors))
             step  (/ 1 (dec n))
             table (apply str (interpose " " (map #(str (* step %)) (range n))))]
         [:feComponentTransfer
          {:in "noiseRaw" :result "noiseColor"}
          [:feFuncR {:type "discrete" :tableValues table}]
          [:feFuncG {:type "discrete" :tableValues table}]
          [:feFuncB {:type "discrete" :tableValues table}]]))

     ;; clip to shape alpha
     [:feComposite {:in "noiseColor"
                    :in2 "SourceAlpha"
                    :operator "in"
                    :result "noiseClipped"}]

     ;; modulate alpha by density
     [:feComponentTransfer {:in "noiseClipped" :result "noiseModulated"}
      [:feFuncA {:type "linear" :slope density :intercept 0}]]

     [:feBlend {:mode "normal"
                :in2 filter-in
                :result filter-id}]]))

;; TEXTURE (#63) — feDisplacementMap driven by fractal noise, optionally
;; clipped back to the shape alpha. Placed in shape->filters BEFORE the
;; drop-shadow entries so shadows compute from the textured alpha. Like
;; noise, emits ONLY behind a (seq (remove :hidden (:texture shape)))
;; guard, so an absent slot is byte-identical.
(mf/defc texture-filter*
  [{:keys [filter-id params]}]
  (let [{:keys [size-x size-y radius clip-to-shape]} params
        size-x    (or size-x 100)
        size-y    (or size-y 100)
        radius    (or radius 0)
        base-freq (dm/str (/ 1 size-x) " " (/ 1 size-y))]
    [:*
     [:feTurbulence {:type "fractalNoise"
                     :baseFrequency base-freq
                     :numOctaves 3
                     :result "distress"}]
     [:feDisplacementMap {:in "SourceGraphic"
                          :in2 "distress"
                          :scale radius
                          :result (if clip-to-shape "distressed" filter-id)}]
     (when clip-to-shape
       [:feComposite {:in "distressed"
                      :in2 "SourceAlpha"
                      :operator "in"
                      :result filter-id}])]))

;; STACKED-BLUR (#60) — a stack of N (capped at 4) chained Gaussian
;; blurs merged back together. Mirrors layer-blur-filter* (a single
;; feGaussianBlur with :result) multiplied: each blur feeds the next
;; (first takes filter-in, subsequent take the previous result), then a
;; feMerge recombines all N results in order. Emits ONLY behind a
;; (seq (remove :hidden slot)) guard in shape->filters / filter-str /
;; add-fill-props!, so an absent/empty slot leaves the filters vector at
;; the baseline count of 2 and the <filter> element / :filter attr are
;; not emitted (byte-identical).
(mf/defc stacked-blur-filter*
  [{:keys [filter-id filter-in params]}]
  (let [radii (vec (take 4 (seq (:radii params))))
        n     (count radii)]
    (if (zero? n)
      ;; Degenerate (guarded upstream so unreachable in practice):
      ;; passthrough so the filter chain still resolves filter-id.
      [:feBlend {:mode "normal" :in2 filter-in :result filter-id}]
      [:*
       (for [i (range n)]
         [:feGaussianBlur
          {:stdDeviation (nth radii i)
           :in           (if (zero? i) filter-in (dm/str filter-id "-" (dec i)))
           :result       (dm/str filter-id "-" i)}])
       [:feMerge {:result filter-id}
        (for [i (range n)]
          [:feMergeNode {:in (dm/str filter-id "-" i)}])]])))

;; GLASS (#61, APPROX) — frosted-glass: fractal-noise frost, backdrop
;; refraction via feDisplacementMap, approximate chromatic dispersion
;; (R/G/B split + offset + screen-blend recombine), and a specular
;; highlight from a point light positioned by :light-angle. True Figma
;; glass needs backdrop sampling that only works over content; the
;; dispersion here is an approximation (per-channel offset, not true
;; wavelength spread). Emits ONLY behind a (seq (remove :hidden slot))
;; guard, so an absent/empty slot is byte-identical.
(mf/defc glass-filter*
  [{:keys [filter-id filter-in params]}]
  (let [frost            (or (:frost params) 0.005)
        frost-blur       (or (:frost-blur params) 1)
        refraction       (or (:refraction params) 0)
        dispersion       (or (:dispersion params) 0)
        light-angle      (or (:light-angle params) 45)
        light-intensity  (or (:light-intensity params) 0.5)
        splay            (or (:splay params) 0)
        depth            (or (:depth params) 0)
        ang              (* (/ js/Math.PI 180) light-angle)
        lx               (* (js/Math.cos ang) 100)
        ly               (* (js/Math.sin ang) 100)]
    [:*
     ;; (1) frost: fractal noise blurred by frost-blur.
     [:feTurbulence {:type "fractalNoise"
                     :baseFrequency frost
                     :numOctaves 2
                     :result "frostNoise"}]
     [:feGaussianBlur {:in "frostNoise"
                       :stdDeviation frost-blur
                       :result "frostBlur"}]

     ;; (2) refraction: sample backdrop (mirroring background-blur-filter*'s
     ;; feGaussianBlur on BackgroundImage), then displace the BLURRED backdrop
     ;; by the BLURRED frost noise (so frost-blur softens both the backdrop
     ;; sample and the displacement map — without this the frost-blur param
     ;; had no effect and both blurred primitives were computed + discarded).
     [:feGaussianBlur {:in "BackgroundImage"
                       :stdDeviation frost-blur
                       :result "bgBlur"}]
     [:feDisplacementMap {:in "bgBlur"
                          :in2 "frostBlur"
                          :scale refraction
                          :result "refracted"}]

     ;; depth (#61): extra frost-noise displacement of the refracted
     ;; backdrop, approximating glass thickness — deeper glass bends the
     ;; seen content more. scale = depth * 4 (the UI/schema bound is 0..1,
     ;; so a raw depth would be sub-pixel and imperceptible; the x4 brings
     ;; the max to a visible ~4px wobble). depth = 0 -> scale 0 -> a
     ;; pixel-identical no-op per the SVG spec and the chain is unchanged;
     ;; the create-glass default :depth 0.5 yields a ~2px wobble on top of
     ;; refraction. Reuses frostBlur (already produced) so no new noise
     ;; pass. The x4 multiplier MUST match bounds.cljc's :glass grow term
     ;; (depth*4) or the filter region would clip the displaced content.
     ;; Downstream dispersion reads "refractedD".
     [:feDisplacementMap {:in "refracted"
                          :in2 "frostBlur"
                          :scale (* depth 4)
                          :result "refractedD"}]

     ;; (3) dispersion (approx): split refractedD into R/G/B via three
     ;; feColorMatrix (mirroring color-matrix*'s feColorMatrix shape),
     ;; offset R by -dispersion and B by +dispersion, screen-blend back.
     [:feColorMatrix {:in "refractedD" :type "matrix"
                      :values "1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0"
                      :result "chanR"}]
     [:feColorMatrix {:in "refractedD" :type "matrix"
                      :values "0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0"
                      :result "chanG"}]
     [:feColorMatrix {:in "refractedD" :type "matrix"
                      :values "0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0"
                      :result "chanB"}]
     [:feOffset {:in "chanR" :dx (- dispersion) :dy 0 :result "chanRoff"}]
     [:feOffset {:in "chanB" :dx dispersion :dy 0 :result "chanBoff"}]
     [:feBlend {:in "chanG" :in2 "chanRoff" :mode "screen" :result "chanGR"}]
     [:feBlend {:in "chanGR" :in2 "chanBoff" :mode "screen" :result "dispersed"}]

     ;; (4) light/splay: specular highlight from a point light positioned
     ;; by light-angle, intensity-modulated via specularConstant, masked
     ;; to the (splayed) alpha and screen-blended over the dispersed backdrop.
     ;; splay (#61): dilate the alpha that drives the specular highlight so
     ;; the glass edge highlight spreads outward (thickens). radius = splay
     ;; * 3 (UI/schema bound 0..1; raw splay is sub-pixel, the x3 brings the
     ;; max to a visible ~3px edge spread). splay = 0 -> radius 0 -> a
     ;; pixel-identical no-op per the SVG spec -> splayedAlpha = SourceAlpha
     ;; -> the specular highlight is byte-identical to the pre-splay chain.
     ;; create-glass defaults :splay 0, so nothing changes unless the user
     ;; raises it. The x3 multiplier MUST match bounds.cljc's :glass grow
     ;; term (splay*3) or the filter region would clip the dilated edge.
     [:feMorphology {:in "SourceAlpha"
                     :operator "dilate"
                     :radius (* splay 3)
                     :result "splayedAlpha"}]
     [:feSpecularLighting {:in "splayedAlpha"
                           :specularExponent 20
                           :specularConstant light-intensity
                           :lighting-color "white"
                           :result "specOut"}
      [:fePointLight {:x lx :y ly :z 50}]]
     [:feComposite {:in "specOut" :in2 "splayedAlpha" :operator "in" :result "specMasked"}]
     ;; Compose over filter-in (the prior filter chain) so preceding
     ;; effects (drop-shadow / blur / texture / etc.) are NOT discarded
     ;; when glass is the last entry. Glass refraction (dispersed) is
     ;; blended over the prior chain, then the specular highlight is
     ;; screen-blended on top -> filter-id. Without this the final feBlend
     ;; referenced only specMasked + dispersed and dropped filter-in.
     [:feBlend {:in "dispersed" :in2 filter-in :mode "normal" :result "glassComp"}]
     [:feBlend {:in "specMasked" :in2 "glassComp" :mode "screen" :result filter-id}]]))

;; SHADER (#64, SVG preset path) — first-class SVG filter approximation
;; for the three named presets. clouds = feTurbulence (fractalNoise, low
;; baseFrequency, 4 octaves) + feColorMatrix luminance->alpha + feBlend;
;; halftone = feTurbulence + feComponentTransfer discrete (posterize to N
;; bands) + feGaussianBlur small + feBlend; noise = delegates to
;; noise-filter* (A1) so it is pixel-identical to the :noise slot. Like the
;; other effect filters, it chains via filter-in -> filter-id and emits
;; ONLY behind the two-layer guard in shape->filters / filter-str /
;; add-fill-props! (slot non-hidden AND preset in the SVG-expressible set),
;; so an absent/hidden/non-SVG slot adds no entry -> filters vector stays
;; at baseline count of 2 -> filters* count-guard false -> no <filter>
;; (byte-identical).
(mf/defc shader-preset-filter*
  [{:keys [filter-id filter-in params]}]
  (let [preset (or (:preset params) :noise)
        seed   (or (:seed params) 0)]
    (case preset
      :noise
      ;; Same SVG as the :noise slot (A1) — delegate so the two paths
      ;; can never drift. params carry the same keys noise-filter* reads.
      [:> noise-filter* {:filter-id filter-id :filter-in filter-in :params params}]

      :clouds
      (let [base-freq (or (:base-frequency params) 0.012)
            octaves   (or (:octaves params) 4)]
        [:*
         [:feTurbulence {:type "fractalNoise"
                         :baseFrequency base-freq
                         :numOctaves octaves
                         :seed seed
                         :result "cloudRaw"}]
         ;; luminance -> alpha (RGB zeroed, alpha = perceptual luminance).
         [:feColorMatrix {:in "cloudRaw"
                          :type "matrix"
                          :values "0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0.2125 0.7154 0.0721 0 0"
                          :result "cloudAlpha"}]
         ;; overlay the cloud texture over the prior filter output.
         [:feBlend {:in "cloudAlpha"
                    :in2 filter-in
                    :mode "normal"
                    :result filter-id}]])

      :halftone
      (let [base-freq (or (:base-frequency params) 0.05)
            octaves   (or (:octaves params) 2)
            bands     (max 2 (or (:bands params) 4))
            step      (/ 1 (dec bands))
            table     (apply str (interpose " " (map #(str (* step %)) (range bands))))
            blur      (or (:blur params) 0.4)]
        [:*
         [:feTurbulence {:type "fractalNoise"
                         :baseFrequency base-freq
                         :numOctaves octaves
                         :seed seed
                         :result "halfRaw"}]
         ;; posterize the noise into N discrete bands per channel.
         [:feComponentTransfer {:in "halfRaw" :result "halfPoster"}
          [:feFuncR {:type "discrete" :tableValues table}]
          [:feFuncG {:type "discrete" :tableValues table}]
          [:feFuncB {:type "discrete" :tableValues table}]
          [:feFuncA {:type "discrete" :tableValues table}]]
         [:feGaussianBlur {:in "halfPoster"
                           :stdDeviation blur
                           :result "halfBlur"}]
         [:feBlend {:in "halfBlur"
                    :in2 filter-in
                    :mode "normal"
                    :result filter-id}]])

      ;; Any other preset is NOT SVG-expressible and is handled by
      ;; shader-canvas* (WebGL2); it never reaches this component because
      ;; shape->filters only appends a :shader-effect entry for presets in
      ;; the SVG-expressible set. Passthrough so the chain still resolves
      ;; filter-id if ever reached (defensive, unreachable in practice).
      [:feBlend {:mode "normal" :in2 filter-in :result filter-id}])))

;; SHADER (#64, WebGL2-in-foreignObject fallback) — for arbitrary/prompt-
;; built shaders that are NOT expressible as SVG filters, render a <canvas>
;; inside a <foreignObject> sized to the shape selrect and run a WebGL2
;; fragment shader. This path is RASTER and breaks SVG export, so it is
;; hard-gated: the component returns nil (no foreignObject, no canvas)
;; unless ALL of (a) the :shader-effect slot is non-hidden, (b) the preset
;; is NOT in the SVG-expressible set, and (c) a WebGL2 context class is
;; available in the current host. When any guard fails the component emits
;; nothing -> the shape falls back to its underlying fill with zero bytes
;; changed (byte-identical).
;;
;; NOTE: full WebGPU is NOT feasible under the no-build + CLJS-SVG renderer
;; constraints; WebGL2-in-foreignObject is the substitute for arbitrary
;; shaders, and the named presets via SVG filters (shader-preset-filter*)
;; are the preferred zero-dependency path. The foreignObject+canvas scaffold
;; below is the byte-identical-when-gated mount point; the actual WebGL2
;; fragment-shader compile + draw loop is intentionally left as a TODO
;; (no-build constraint prevents validating GLSL against a real context).
;; A follow-up with a build will attach the program here — until then the
;; capability gate keeps the output byte-identical for every shape whose
;; slot is absent, hidden, or SVG-expressible.
(mf/defc shader-canvas*
  [{:keys [shape]}]
  (let [slot    (:shader-effect shape)
        preset  (:preset slot)
        selrect (:selrect shape)
        x       (dm/get-prop selrect :x)
        y       (dm/get-prop selrect :y)
        w       (dm/get-prop selrect :width)
        h       (dm/get-prop selrect :height)
        ;; Conservative capability probe: WebGL2 is only reachable when the
        ;; host exposes a WebGL2RenderingContext class. TODO (with build):
        ;; upgrade to a real (.getContext canvas "webgl2") probe.
        webgl2? (and (exists? js/WebGL2RenderingContext)
                     (some? js/WebGL2RenderingContext))]
    (when (and (some? slot)
               (not ^boolean (:hidden slot))
               (not (svg-expressible-preset? preset))
               ^boolean webgl2?
               (some? selrect)
               (pos? w)
               (pos? h))
      ;; TODO (with build): obtain the WebGL2 context from canvas-ref,
      ;; compile a fragment shader built from (-> slot :source / :prompt),
      ;; and draw a fullscreen quad sized to w x h. The scaffold below is
      ;; intentionally minimal so the guarded mount point is stable.
      (let [canvas-ref (mf/use-ref nil)]
        [:> :foreignObject
         {:x x :y y :width w :height h}
         [:canvas {:ref canvas-ref :width w :height h}]]))))

(mf/defc filter-entry* [{:keys [entry]}]
  (let [props #js {:filter-id (:id entry)
                   :filter-in (:filter-in entry)
                   :params (:params entry)}]
    (case (:type entry)
      :drop-shadow [:> drop-shadow-filter* props]
      :inner-shadow [:> inner-shadow-filter* props]
      :background-blur [:> background-blur-filter* props]
      :layer-blur [:> layer-blur-filter* props]
      :image-fix [:> image-fix-filter* props]
      :blend-filters [:> blend-filters* props]
      :noise [:> noise-filter* props]
      :texture [:> texture-filter* props]
      :stacked-blur [:> stacked-blur-filter* props]
      :glass [:> glass-filter* props]
      :shader-effect [:> shader-preset-filter* props])))

(defn change-filter-in
  "Adds the previous filter as `filter-in` parameter"
  [filters]
  (map #(assoc %1 :filter-in %2) filters (cons nil (map :id filters))))

(defn filter-coords
  [bounds selrect padding]
  (if (or (mth/close? 0.01 (:width selrect))
          (mth/close? 0.01 (:height selrect)))

    ;; We cannot use "objectBoundingbox" if the shape doesn't have width/height
    ;; From the SVG spec (https://www.w3.org/TR/SVG11/coords.html#ObjectBoundingBox
    ;; Keyword objectBoundingBox should not be used when the geometry of the applicable element
    ;; has no width or no height, such as the case of a horizontal or vertical line, even when
    ;; the line has actual thickness when viewed due to having a non-zero stroke width since
    ;; stroke width is ignored for bounding box calculations. When the geometry of the
    ;; applicable element has no width or height and objectBoundingBox is specified, then
    ;; the given effect (e.g., a gradient or a filter) will be ignored.
    (let [filter-width  (+ (:width bounds) (* 2 (:horizontal padding)))
          filter-height (+ (:height bounds) (* 2 (:vertical padding)))
          filter-x      (- (:x bounds) #_(:x selrect) (:horizontal padding))
          filter-y      (- (:y bounds) #_(:y selrect) (:vertical padding))
          filter-units  "userSpaceOnUse"]
      [filter-x filter-y filter-width filter-height filter-units])

    ;; If the width/height is not zero we use objectBoundingBox as it's more stable
    (let [filter-width  (/ (+ (:width bounds) (* 2 (:horizontal padding))) (:width selrect))
          filter-height (/ (+ (:height bounds) (* 2 (:vertical padding))) (:height selrect))
          filter-x      (/ (- (:x bounds) (:x selrect) (:horizontal padding)) (:width selrect))
          filter-y      (/ (- (:y bounds) (:y selrect) (:vertical padding)) (:height selrect))
          filter-units  "objectBoundingBox"]
      [filter-x filter-y filter-width filter-height filter-units])))

(mf/defc filters*
  [{:keys [filter-id shape]}]

  (let [shape'        (update shape :shadow reverse)
        filters       (-> shape' gsb/shape->filters change-filter-in)
        bounds        (gsb/get-rect-filter-bounds (:selrect shape) filters (or (-> shape :blur :value) 0))
        padding       (gsb/calculate-padding shape)
        selrect       (:selrect shape)

        [filter-x filter-y filter-width filter-height filter-units]
        (filter-coords bounds selrect padding)]

    (when (> (count filters) 2)
      [:filter {:id          filter-id
                :x           filter-x
                :y           filter-y
                :width       filter-width
                :height      filter-height
                :filterUnits filter-units
                :color-interpolation-filters "sRGB"}
       (for [[index entry] (d/enumerate filters)]
         [:> filter-entry* {:key (dm/str filter-id "-" index)
                            :entry entry}])])))

