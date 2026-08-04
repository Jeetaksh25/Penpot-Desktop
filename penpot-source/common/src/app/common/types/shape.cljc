;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.proportions :as gpr]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.record :as cr]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.transit :as t]
   [app.common.types.color :as clr]
   [app.common.types.fills :refer [schema:fills fill->color]]
   [app.common.types.breakpoint :as ctbp]
   [app.common.types.grid :as ctg]
   [app.common.types.component-property :as ctcp]
   [app.common.types.path :as path]
   [app.common.types.plugins :as ctpg]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.common.types.shape.background-blur :as ctsbb]
   [app.common.types.shape.blur :as ctsb]
   [app.common.types.shape.export :as ctse]
   [app.common.types.shape.fade :as ctsf]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.shape.polygon :as ctsp]
   [app.common.types.shape.shadow :as ctss]
   [app.common.types.shape.text :as ctsx]
   [app.common.types.stroke :as stroke]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [clojure.set :as set]))

(defonce ^:dynamic *shape-changes* nil)
(defonce wasm-enabled? false)
(defonce wasm-create-shape (constantly nil))

;; Marker protocol
(defprotocol IShape)

(cr/defrecord Shape [id name type x y width height rotation selrect points
                     transform transform-inverse parent-id frame-id flip-x flip-y]
  IShape)

(defn shape?
  [o]
  #?(:cljs (implements? IShape o)
     :clj  (instance? Shape o)))

(defn create-shape
  "A low level function that creates a Shape data structure
  from a attrs map without performing other transformations"
  [attrs]
  #?(:cljs (if ^boolean wasm-enabled?
             (^function wasm-create-shape attrs)
             (map->Shape attrs))
     :clj  (map->Shape attrs)))

(def stroke-caps-line stroke/stroke-caps-line)
(def stroke-caps-marker stroke/stroke-caps-marker)
(def stroke-caps (conj (set/union stroke-caps-line stroke-caps-marker) nil))

(def shape-types
  #{:frame
    :group
    :bool
    :rect
    :path
    :text
    :circle
    :svg-raw
    :image
    ;; Figma-parity slice tool: a rect-shaped export region that renders
    ;; as a translucent dashed overlay (no visible content of its own) and
    ;; is exported using its bounding box as the export bounds.
    :slice
    ;; Figma-parity sticky notes (gap #44). A colored rectangle carrying
    ;; freeform text; renders in shapes.cljs as a filled rect + text.
    :note
    ;; Figma-parity polygon + star tools (gap #58). Regular polygon / star
    ;; shapes drawn as SVG paths; point-count/inner-radius/corner-radius
    ;; live in shape/polygon.cljc (NEW) and are merged here.
    :polygon
    :star})

(def blend-modes
  #{:normal
    :darken
    :multiply
    :color-burn
    :lighten
    :screen
    :color-dodge
    :overlay
    :soft-light
    :hard-light
    :difference
    :exclusion
    :hue
    :saturation
    :color
    :luminosity})

(def horizontal-constraint-types
  #{:left :right :leftright :center :scale})

(def vertical-constraint-types
  #{:top :bottom :topbottom :center :scale})

(def text-align-types
  #{"left" "right" "center" "justify"})

(def bool-types
  #{:union
    :difference
    :exclude
    :intersection})

(def grow-types
  #{:auto-width
    :auto-height
    :fixed})

(def schema:points
  [:vector {:gen/max 4 :gen/min 4} ::gpt/point])

(def valid-stroke-attrs
  "A set used for proper check if color should contain only one of the
  attrs listed in this set."
  #{:stroke-image :stroke-color :stroke-color-gradient})

(defn has-valid-stroke-attrs?
  "Check if color has correct color attrs"
  [color]
  (let [attrs  (set (keys color))
        result (set/intersection attrs valid-stroke-attrs)]
    (= 1 (count result))))

(def schema:stroke-attrs
  [:map {:title "StrokeAttrs" :closed true}
   [:stroke-color-ref-file {:optional true} ::sm/uuid]
   [:stroke-color-ref-id {:optional true} ::sm/uuid]
   [:stroke-opacity {:optional true} ::sm/safe-number]
   [:stroke-style {:optional true}
    [::sm/one-of #{:solid :dotted :dashed :mixed}]]
   [:stroke-width {:optional true} ::sm/safe-number]
   ;; Figma-parity: per-side stroke widths (top/right/bottom/left) for
   ;; rect/frame. :stroke-width-mode :uniform (default, absent) keeps the
   ;; legacy single-width behavior; :per-side renders four independent
   ;; edge strokes (see custom_stroke.cljs per-side-stroke).
   [:stroke-width-mode {:optional true}
    [::sm/one-of #{:uniform :per-side}]]
   [:stroke-top {:optional true} ::sm/safe-number]
   [:stroke-right {:optional true} ::sm/safe-number]
   [:stroke-bottom {:optional true} ::sm/safe-number]
   [:stroke-left {:optional true} ::sm/safe-number]
   [:stroke-dash {:optional true} ::sm/safe-number]
   [:stroke-gap {:optional true} ::sm/safe-number]
   [:stroke-alignment {:optional true}
    [::sm/one-of #{:center :inner :outer}]]
   [:stroke-cap-start {:optional true}
    [::sm/one-of stroke-caps]]
   [:stroke-cap-end {:optional true}
    [::sm/one-of stroke-caps]]
   ;; Figma-parity: stroke join (miter/round/bevel) and miter limit.
   ;; SVG defaults are :miter and 4, which match Figma's defaults, so both
   ;; stay optional — an unset join renders identically to today.
   [:stroke-join {:optional true}
    [::sm/one-of #{:miter :round :bevel}]]
   [:stroke-miter-limit {:optional true} ::sm/safe-number]
   [:stroke-color {:optional true} clr/schema:hex-color]
   [:stroke-color-gradient {:optional true} clr/schema:gradient]
   [:stroke-image {:optional true} clr/schema:image]
   ;; Figma-parity per-item blend modes (gap #9). Optional blend mode for
   ;; this single stroke; absent = :normal = today's compositing. The
   ;; renderer must composite the stroke paint with its own
   ;; mix-blend-mode (SVG) — that wiring is deferred (high blast-radius
   ;; compositing change, no build to verify); the field round-trips.
   [:blend-mode {:optional true}
    [::sm/one-of blend-modes]]
   ;; Figma-parity dynamic strokes (gap #54). Optional variation map applied
   ;; to the stroke outline for a hand-drawn feel. :type is :wiggle or
   ;; :noise; :amplitude (0..N px) is the max outline displacement;
   ;; :frequency (> 0) is the wiggle/noise spatial frequency along the
   ;; path; :seed is a deterministic noise seed. All optional; absent =
   ;; a plain uniform stroke (byte-identical with today). The renderer
   ;; per-segment jitter is deferred (significant outline work, no build
   ;; to verify); the value round-trips on the stroke via change-stroke-attrs.
   [:variation {:optional true}
    [:map {:title "StrokeVariation" :closed true}
     [:type {:optional true} [::sm/one-of #{:wiggle :noise}]]
     [:amplitude {:optional true} ::sm/safe-number]
     [:frequency {:optional true} ::sm/safe-number]
     [:seed {:optional true} ::sm/int]]]
   [:hidden {:optional true} :boolean]])

(def stroke-attrs
  "A set of attrs that corresponds to stroke data type"
  (sm/keys schema:stroke-attrs))

(def schema:stroke
  [:and schema:stroke-attrs
   [:fn has-valid-stroke-attrs?]])

(def check-stroke
  (sm/check-fn schema:stroke))

(def schema:shape-base-attrs
  [:map {:title "ShapeMinimalRecord"}
   [:id ::sm/uuid]
   [:name :string]
   [:type [::sm/one-of shape-types]]
   [:selrect ::grc/rect]
   [:points schema:points]
   [:transform ::gmt/matrix]
   [:transform-inverse ::gmt/matrix]
   [:parent-id ::sm/uuid]
   [:frame-id ::sm/uuid]])

(def schema:shape-geom-attrs
  [:map {:title "ShapeGeometryAttrs"}
   [:x ::sm/safe-number]
   [:y ::sm/safe-number]
   [:width ::sm/safe-number]
   [:height ::sm/safe-number]])

(def schema:shape-generic-attrs
  [:map {:title "ShapeGenericAttrs"}
   [:page-id {:optional true} ::sm/uuid]
   [:component-id {:optional true}  ::sm/uuid]
   [:component-file {:optional true} ::sm/uuid]
   [:component-root {:optional true} :boolean]
   [:main-instance {:optional true} :boolean]
   [:remote-synced {:optional true} :boolean]
   [:shape-ref {:optional true} ::sm/uuid]
   [:touched {:optional true} [:maybe [:set :keyword]]]
   ;; Figma-parity typed component properties (gap #1). Instance root
   ;; shapes carry a map of property-name -> value that overrides the
   ;; rendered sub-tree. Optional: a plain shape carries no key, so this
   ;; is fully backward compatible.
   [:component-property-values {:optional true} ctcp/schema:component-property-values]
   [:blocked {:optional true} :boolean]
   [:collapsed {:optional true} :boolean]
   [:locked {:optional true} :boolean]
   [:hidden {:optional true} :boolean]
   [:masked-group {:optional true} :boolean]
   ;; Figma-parity mask variants (gap #26). :alpha (default/absent) is the
   ;; existing alpha-channel mask (byte-identical when this key is absent).
   ;; :vector is a hard-edged clip by the mask outline (SVG <clipPath>).
   ;; :luminance masks by brightness (SVG <mask mask-type="luminance">).
   ;; Only meaningful on a mask group (:masked-group true); ignored on
   ;; plain shapes, so it is fully backward compatible.
   [:mask-mode {:optional true}
    [::sm/one-of #{:alpha :vector :luminance}]]
   [:fills {:optional true} schema:fills]
   [:proportion {:optional true} ::sm/safe-number]
   [:proportion-lock {:optional true} :boolean]
   [:constraints-h {:optional true}
    [::sm/one-of horizontal-constraint-types]]
   [:constraints-v {:optional true}
    [::sm/one-of vertical-constraint-types]]
   [:fixed-scroll {:optional true} :boolean]
   [:r1 {:optional true} ::sm/safe-number]
   [:r2 {:optional true} ::sm/safe-number]
   [:r3 {:optional true} ::sm/safe-number]
   [:r4 {:optional true} ::sm/safe-number]
   ;; Figma-style corner smoothing (superellipse), 0..1. 0 reproduces a
   ;; circular arc; higher values flatten the corner toward the
   ;; rectangle. Whole-shape property (no per-corner smoothing).
   [:corner-smoothing {:optional true} [::sm/safe-number {:min 0 :max 1}]]
   [:opacity {:optional true} ::sm/safe-number]
   [:grids {:optional true}
    [:vector {:gen/max 2} ctg/schema:grid]]
   [:exports {:optional true}
    [:vector {:gen/max 2} ctse/schema:export]]
   [:strokes {:optional true}
    [:vector {:gen/max 2} schema:stroke]]
   [:blend-mode {:optional true}
    [::sm/one-of blend-modes]]
   [:interactions {:optional true}
    [:vector {:gen/max 2} ctsi/schema:interaction]]
   [:shadow {:optional true}
    [:vector {:gen/max 1} ctss/schema:shadow]]
   [:blur {:optional true} ctsb/schema:blur]
   [:background-blur {:optional true} ctsbb/schema:background-blur]
   ;; Figma-parity fade effect (gap #60 fade). A single-map MASK slot
   ;; (like :blur, NOT a vector). When present and non-hidden the renderer
   ;; emits a gradient <mask mask-type="alpha"> fading the shape along
   ;; :direction from :start-opacity to :end-opacity and sets
   ;; mask="url(#fade-<render-id>)" on the shape element. Fade is a MASK,
   ;; not a filter, so it bypasses the 3-gate filter lockstep entirely
   ;; (no bounds.cljc / filters.cljs / filter-str involvement). Absent or
   ;; :hidden -> no mask attr, no <mask> def -> byte-identical to today.
   [:fade {:optional true} ctsf/schema:fade]
   ;; Figma-parity advanced effects (gaps #61/#62/#63). These are OPAQUE
   ;; effect-vector slots owned by group V but consumed by group E2.
   ;; Each holds a vector of effect maps; ::sm/any keeps the slot opaque so
   ;; E2 can define schema:glass-effect / schema:noise-effect /
   ;; schema:texture-effect in NEW files (shape/glass.cljc,
   ;; shape/noise.cljc, shape/texture.cljc) and read/write these vectors
   ;; WITHOUT editing this file. Absent key = no effect = today's render
   ;; (byte-identical). The renderer compositing for each effect is
   ;; deferred (high blast-radius GPU work, no build to verify); the
   ;; values round-trip on the shape via dwsh/update-shapes.
   [:glass {:optional true} [:vector ::sm/any]]
   [:noise {:optional true} [:vector ::sm/any]]
   [:texture {:optional true} [:vector ::sm/any]]
   ;; Figma-parity stacked layer blurs (gap #74). An OPAQUE vector of
   ;; additional blur maps stacked on top of the existing single :blur /
   ;; :background-blur. Absent :blurs = single-blur behavior unchanged
   ;; (byte-identical with today); when present the renderer applies the
   ;; stack in order (multi-blur compositing is deferred — significant
   ;; GPU work, no build to verify; the values round-trip on the shape
   ;; via dwsh/update-shapes). Kept opaque so the dedicated stack schema
   ;; can live in shape/blur.cljc without editing this file.
   [:blurs {:optional true} [:vector ::sm/any]]
   ;; Figma-parity shader effects (gap #64). An OPAQUE vector of
   ;; shader-effect maps (bloom / chromatic-metal / dither / halftone /
   ;; hatching / lens-distortion / warp / pixelate ...). Absent = no
   ;; shader effect = today's render (byte-identical). The WebGPU shader
   ;; pipeline is a massive lift → renderer DEFERRED; this slot +
   ;; shader_row.cljs preset picker are a thin scaffold. Kept opaque so
   ;; the effect schema can be defined in a NEW file without editing
   ;; this one.
   [:shader-effect {:optional true} [:vector ::sm/any]]
   ;; Figma-parity 3D transforms (gap #66). Optional 3D transform params:
   ;; :rotation-x / :rotation-y / :rotation-z are degrees;
   ;; :perspective is the CSS-style perspective distance (px). All
   ;; optional; all absent = a flat 2D shape = today's behavior
   ;; (byte-identical). The renderer 3D matrix projection (SVG fallback:
   ;; CSS transform3d) is deferred (large renderer lift, no build to
   ;; verify); the values round-trip on the shape via dwsh/update-shapes
   ;; and transform_3d_row.cljs exposes the controls.
   [:transform-3d {:optional true}
    [:map {:title "Transform3D" :closed true}
     [:rotation-x {:optional true} ::sm/safe-number]
     [:rotation-y {:optional true} ::sm/safe-number]
     [:rotation-z {:optional true} ::sm/safe-number]
     [:perspective {:optional true} ::sm/safe-number]]]
   ;; Figma-parity variable-width strokes (gap #53) + brush strokes.
   ;; :segment-widths is a per-node map {segment-index width} consumed by
   ;; custom_stroke.cljs `variable-width-stroke` (path/get-segment-width
   ;; returns the fallback when an index is absent, so per-segment lookups
   ;; are safe). :brush-id references a brush asset used by the mesh/brush
   ;; stroke-defs case owned by group B. Both :optional — absent/empty =
   ;; existing cond branches in shape-custom-stroke fire = byte-identical
   ;; with today. Kept permissive so the dedicated schemas can live in NEW
   ;; files without editing this one; round-trip safe via dwsh/update-shapes.
   [:segment-widths {:optional true} [:maybe [:map-of ::sm/int ::sm/safe-number]]]
   [:brush-id {:optional true} ::sm/uuid]
   [:grow-type {:optional true}
    [::sm/one-of grow-types]]
   [:applied-tokens {:optional true} cto/schema:applied-tokens]
   [:plugin-data {:optional true} ctpg/schema:plugin-data]])

(def schema:group-attrs
  [:map {:title "GroupAttrs"}
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]])

(def ^:private schema:frame-attrs
  [:map {:title "FrameAttrs"}
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]
   [:hide-fill-on-export {:optional true} :boolean]
   [:show-content {:optional true} :boolean]
   [:hide-in-viewer {:optional true} :boolean]
   ;; Figma-parity device frames (gap #35). Optional preset name (e.g.
   ;; :iphone-14, :pixel-7, :desktop-1080p). Absent = no device chrome,
   ;; which is the existing behavior. The viewer chrome render is deferred
   ;; (needs SVG bezel assets + a viewer pass); the field round-trips here
   ;; and a picker is exposed in measures.cljs.
   [:device-frame {:optional true} :keyword]
   ;; ALL_APPS_PARITY P0.15 — responsive breakpoints. Optional; absent =
   ;; no breakpoints (byte-identical with prior frames). See
   ;; app.common.types.breakpoint.
   [:breakpoints {:optional true} ctbp/schema:breakpoints]])

(def ^:private schema:bool-attrs
  [:map {:title "BoolAttrs"}
   [:shapes [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]
   [:bool-type [::sm/one-of bool-types]]
   [:content path/schema:content]])

(def ^:private schema:rect-attrs
  [:map {:title "RectAttrs"}])

(def ^:private schema:slice-attrs
  [:map {:title "SliceAttrs"}])

(def ^:private schema:note-attrs
  [:map {:title "NoteAttrs"}
   ;; Figma-parity sticky notes (gap #44). All optional; absent = a plain
   ;; colored rect (the fallback render). :note-text is the freeform body
   ;; text; :note-color is a hex fill color for the sticky; :note-text-color
   ;; is the body text color. The shape is rect-shaped (x/y/width/height)
   ;; and renders as a filled rect + wrapped text in shapes.cljs.
   [:note-text {:optional true} :string]
   [:note-color {:optional true} clr/schema:hex-color]
   [:note-text-color {:optional true} clr/schema:hex-color]])

(def ^:private schema:polygon-attrs
  [:map {:title "PolygonAttrs"}
   ;; Figma-parity polygon tool (gap #58). :point-count = number of sides
   ;; (3..60). :corner-radius rounds the vertices (0 = sharp). Both
   ;; optional; absent = a 5-point polygon with sharp corners (rendered
   ;; as a regular-polygon SVG path in shapes.cljs). See shape/polygon.cljc.
   [:point-count {:optional true} [::sm/int {:min 3 :max 60}]]
   [:corner-radius {:optional true} ::sm/safe-number]])

(def ^:private schema:star-attrs
  [:map {:title "StarAttrs"}
   ;; Figma-parity star tool (gap #58). :point-count = number of points
   ;; (3..60). :inner-radius = ratio of inner to outer radius (0..1).
   ;; :corner-radius rounds the outer points (0 = sharp). All optional;
   ;; absent = a 5-point star with inner-radius 0.5, sharp points. See
   ;; shape/polygon.cljc for the shared polygon/star path builder.
   [:point-count {:optional true} [::sm/int {:min 3 :max 60}]]
   [:inner-radius {:optional true} [::sm/safe-number {:min 0 :max 1}]]
   [:corner-radius {:optional true} ::sm/safe-number]])

(def ^:private schema:circle-attrs
  [:map {:title "CircleAttrs"}
   ;; Figma-parity arc / pie / ring / donut (gap #59). All optional; absent
   ;; = a plain full ellipse (byte-identical with today). :arc-start and
   ;; :arc-end are angles in degrees (0 = +x axis, clockwise). When both
   ;; are present the renderer (shapes.cljs circle branch) emits an SVG
   ;; path arc (A command) instead of <ellipse>; :inner-radius (0..1 of
   ;; the radius) turns an arc/pie into a ring/donut.
   [:arc-start {:optional true} ::sm/safe-number]
   [:arc-end {:optional true} ::sm/safe-number]
   [:inner-radius {:optional true} [::sm/safe-number {:min 0 :max 1}]]])

(def ^:private schema:svg-raw-attrs
  [:map {:title "SvgRawAttrs"}])

(def schema:image-attrs
  [:map {:title "ImageAttrs"}
   [:metadata
    [:map
     [:width {:gen/gen (sg/small-int :min 1)} ::sm/int]
     [:height {:gen/gen (sg/small-int :min 1)} ::sm/int]
     [:mtype {:optional true
              :gen/gen (sg/elements ["image/jpeg"
                                     "image/png"])}
      [:maybe :string]]
     [:id ::sm/uuid]]]])

(def ^:private schema:path-attrs
  [:map {:title "PathAttrs"}
   [:content path/schema:content]])

(def ^:private schema:text-attrs
  [:map {:title "TextAttrs"}
   [:position-data {:optional true} [:maybe ctsx/schema:position-data]]
   [:content {:optional true} [:maybe ctsx/schema:content]]])

(defn- decode-shape
  [o]
  (if (map? o)
    (create-shape o)
    o))

(defn- shape-generator
  "Get the shape generator."
  []
  (->> (sg/generator schema:shape-base-attrs)
       (sg/mcat (fn [{:keys [type] :as shape}]
                  (sg/let [attrs1 (sg/generator schema:shape-generic-attrs)
                           attrs2 (sg/generator schema:shape-geom-attrs)
                           attrs3 (case type
                                    :text    (sg/generator schema:text-attrs)
                                    :path    (sg/generator schema:path-attrs)
                                    :svg-raw (sg/generator schema:svg-raw-attrs)
                                    :image   (sg/generator schema:image-attrs)
                                    :circle  (sg/generator schema:circle-attrs)
                                    :rect    (sg/generator schema:rect-attrs)
                                    :slice   (sg/generator schema:slice-attrs)
                                    :note    (sg/generator schema:note-attrs)
                                    :polygon (sg/generator schema:polygon-attrs)
                                    :star    (sg/generator schema:star-attrs)
                                    :bool    (sg/generator schema:bool-attrs)
                                    :group   (sg/generator schema:group-attrs)
                                    :frame   (sg/generator schema:frame-attrs))]
                    (if (or (= type :path)
                            (= type :bool))
                      (merge attrs1 shape attrs3)
                      (merge attrs1 shape attrs2 attrs3)))))
       (sg/fmap create-shape)))

(def schema:shape-attrs
  [:multi {:dispatch :type
           :decode/json (fn [shape]
                          (update shape :type keyword))
           :title "Shape"}
   [:group
    [:merge {:title "GroupShape"}
     ctsl/schema:layout-child-attrs
     schema:group-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:frame
    [:merge {:title "FrameShape"}
     ctsl/schema:layout-child-attrs
     ctsl/schema:layout-attrs
     schema:frame-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs
     ctv/schema:variant-shape
     ctv/schema:variant-container]]

   [:bool
    [:merge {:title "BoolShape"}
     ctsl/schema:layout-child-attrs
     schema:bool-attrs
     schema:shape-generic-attrs
     schema:shape-base-attrs]]

   [:rect
    [:merge {:title "RectShape"}
     ctsl/schema:layout-child-attrs
     schema:rect-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:slice
    [:merge {:title "SliceShape"}
     ctsl/schema:layout-child-attrs
     schema:slice-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:note
    [:merge {:title "NoteShape"}
     ctsl/schema:layout-child-attrs
     schema:note-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:polygon
    [:merge {:title "PolygonShape"}
     ctsl/schema:layout-child-attrs
     schema:polygon-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:star
    [:merge {:title "StarShape"}
     ctsl/schema:layout-child-attrs
     schema:star-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:circle
    [:merge {:title "CircleShape"}
     ctsl/schema:layout-child-attrs
     schema:circle-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:image
    [:merge {:title "ImageShape"}
     ctsl/schema:layout-child-attrs
     schema:image-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:svg-raw
    [:merge {:title "SvgRawShape"}
     ctsl/schema:layout-child-attrs
     schema:svg-raw-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]

   [:path
    [:merge {:title "PathShape"}
     ctsl/schema:layout-child-attrs
     schema:path-attrs
     schema:shape-generic-attrs
     schema:shape-base-attrs]]

   [:text
    [:merge {:title "TextShape"}
     ctsl/schema:layout-child-attrs
     schema:text-attrs
     schema:shape-generic-attrs
     schema:shape-geom-attrs
     schema:shape-base-attrs]]])

(def schema:shape
  [:and {:title "Shape"
         :gen/gen (shape-generator)
         :decode/json {:leave decode-shape}}
   [:fn shape?]
   schema:shape-attrs])

(def check-shape-generic-attrs
  (sm/check-fn schema:shape-generic-attrs))

(def check-shape-attrs
  (sm/check-fn schema:shape-attrs))

(def check-shape
  (sm/check-fn schema:shape
               :hint "expected valid shape"))

(def valid-shape?
  (sm/lazy-validator schema:shape))

(def explain-shape
  (sm/lazy-explainer schema:shape))

(defn has-images?
  [{:keys [fills strokes]}]
  (or (some :fill-image fills)
      (some :stroke-image strokes)))

;; Valid attributes for keeping on a switch
(def ^:private allowed-shape-attrs
  #{:page-id :component-id :component-file :component-root :main-instance
    :remote-synced :shape-ref :touched :blocked :collapsed :locked
    :hidden :masked-group :mask-mode :fills :proportion :proportion-lock :constraints-h
    :constraints-v :fixed-scroll :r1 :r2 :r3 :r4 :corner-smoothing :rotation :opacity :grids :exports
    :strokes :blend-mode :interactions :shadow :blur :background-blur :fade :grow-type :applied-tokens
    :plugin-data
    ;; Figma-parity advanced effects (gaps #61/#62/#63): opaque effect
    ;; vectors owned by group V, consumed by group E2. Round-trip safe.
    :glass :noise :texture
    ;; Figma-parity stacked blurs (#74), shader effects (#64) and 3D
    ;; transforms (#66). All optional + opaque/round-trip safe.
    :blurs :shader-effect :transform-3d
    ;; Figma-parity variable-width strokes (#53) + brush strokes. Optional
    ;; + round-trip safe; absent = today's render (byte-identical).
    :segment-widths :brush-id})

(def ^:private allowed-shape-geom-attrs #{:x :y :width :height})
(def ^:private allowed-shape-base-attrs #{:id :name :type :selrect :points :transform
                                          :transform-inverse :parent-id :frame-id})
(def ^:private allowed-bool-attrs #{:shapes :bool-type :content})
(def ^:private allowed-group-attrs #{:shapes})
(def ^:private allowed-frame-attrs #{:shapes :hide-fill-on-export :show-content :hide-in-viewer
                                     :device-frame :breakpoints
                                     :layout :layout-flex-dir :layout-gap-type :layout-gap
                                     :layout-align-items :layout-justify-content :layout-align-content
                                     :layout-wrap-type :layout-padding-type :layout-padding
                                     :layout-grid-dir :layout-justify-items :layout-grid-columns
                                     :layout-grid-rows})
(def ^:private allowed-image-attrs #{:metadata})
(def ^:private allowed-svg-attrs #{:content})
(def ^:private allowed-path-attrs #{:content})
(def ^:private allowed-text-attrs #{:content})
(def ^:private allowed-generic-attrs (set/union allowed-shape-attrs allowed-shape-geom-attrs allowed-shape-base-attrs))

(defn is-allowed-switch-keep-attr?
  [attr type]
  (case type
    :group   (or (contains? allowed-group-attrs attr)
                 (contains? allowed-generic-attrs attr))
    :frame   (or (contains? allowed-frame-attrs attr)
                 (contains? allowed-generic-attrs attr))
    :bool    (or (contains? allowed-bool-attrs attr)
                 (contains? allowed-shape-attrs attr)
                 (contains? allowed-shape-base-attrs attr))
    :rect    (contains? allowed-generic-attrs attr)
    :slice   (contains? allowed-generic-attrs attr)
    :note    (contains? allowed-generic-attrs attr)
    :polygon (contains? allowed-generic-attrs attr)
    :star    (contains? allowed-generic-attrs attr)
    :circle  (contains? allowed-generic-attrs attr)
    :image   (or (contains? allowed-image-attrs attr)
                 (contains? allowed-generic-attrs attr))
    :svg-raw (or (contains? allowed-svg-attrs attr)
                 (contains? allowed-generic-attrs attr))
    :path    (or (contains? allowed-path-attrs attr)
                 (contains? allowed-shape-attrs attr)
                 (contains? allowed-shape-base-attrs attr))
    :text    (or (contains? allowed-text-attrs attr)
                 (contains? allowed-generic-attrs attr))))




;; --- Initialization

(def ^:private minimal-rect-attrs
  {:type :rect
   :name "Rectangle"
   :fills [{:fill-color default-color
            :fill-opacity 1}]
   :strokes []
   :r1 0
   :r2 0
   :r3 0
   :r4 0})

(def ^:private minimal-image-attrs
  {:type :image
   :r1 0
   :r2 0
   :r3 0
   :r4 0
   :fills []
   :strokes []})

(def ^:private minimal-frame-attrs
  {:frame-id uuid/zero
   :fills [{:fill-color clr/white
            :fill-opacity 1}]
   :strokes []
   :name "Board"
   :shapes []
   :r1 0
   :r2 0
   :r3 0
   :r4 0
   :hide-fill-on-export false})

(def ^:private minimal-circle-attrs
  {:type :circle
   :name "Ellipse"
   :fills [{:fill-color default-color
            :fill-opacity 1}]
   :strokes []})

(def ^:private minimal-group-attrs
  {:type :group
   :name "Group"
   :fills []
   :strokes []
   :shapes []})

(def ^:private minimal-bool-attrs
  {:type :bool
   :name "Bool"
   :fills []
   :strokes []
   :shapes []})

(def ^:private minimal-text-attrs
  {:type :text
   :name "Text"})

(def ^:private minimal-path-attrs
  {:type :path
   :name "Path"
   :fills []
   :strokes [{:stroke-style :solid
              :stroke-alignment :inner
              :stroke-width 1
              :stroke-color clr/black
              :stroke-opacity 1}]})

(def ^:private minimal-svg-raw-attrs
  {:type :svg-raw
   :fills []
   :strokes []})

(def ^:private minimal-slice-attrs
  {:type :slice
   :name "Slice"
   ;; A slice renders no content of its own — it is an export region. We
   ;; give it empty fills/strokes so generic handlers don't choke. Export
   ;; specs are added by the user via the inspect panel (:exports lives on
   ;; the generic shape attrs, shared by every type).
   :fills []
   :strokes []})

(def ^:private minimal-note-attrs
  {:type :note
   :name "Sticky note"
   ;; Figma-parity sticky note (gap #44). A colored rect carrying text.
   ;; Default yellow (#FFE680) with dark text — the classic sticky-note
   ;; look. Rendered in shapes.cljs :note wrapper as a filled rect +
   ;; wrapped text.
   :fills [{:fill-color "#ffe680"
            :fill-opacity 1}]
   :strokes []
   :note-text ""
   :note-color "#ffe680"
   :note-text-color "#333333"})

(def ^:private minimal-polygon-attrs
  {:type :polygon
   :name "Polygon"
   ;; Figma-parity polygon (gap #58). Default 5-sided, sharp corners.
   ;; Rendered as a regular-polygon SVG path in shapes.cljs :polygon.
   :fills [{:fill-color default-color
            :fill-opacity 1}]
   :strokes []
   :point-count 5
   :corner-radius 0})

(def ^:private minimal-star-attrs
  {:type :star
   :name "Star"
   ;; Figma-parity star (gap #58). Default 5-point, inner-radius 0.5,
   ;; sharp points. Rendered as a star SVG path in shapes.cljs :star.
   :fills [{:fill-color default-color
            :fill-opacity 1}]
   :strokes []
   :point-count 5
   :inner-radius 0.5
   :corner-radius 0})

(def ^:private minimal-multiple-attrs
  {:type :multiple})

(defn- get-minimal-shape
  [type]
  (case type
    :rect minimal-rect-attrs
    :image minimal-image-attrs
    :circle minimal-circle-attrs
    :path minimal-path-attrs
    :frame minimal-frame-attrs
    :bool minimal-bool-attrs
    :group minimal-group-attrs
    :text minimal-text-attrs
    :svg-raw minimal-svg-raw-attrs
    :slice minimal-slice-attrs
    :note minimal-note-attrs
    :polygon minimal-polygon-attrs
    :star minimal-star-attrs
    ;; NOTE: used for create ephimeral shapes for multiple selection
    :multiple minimal-multiple-attrs))

(defn- make-minimal-shape
  [type]
  (let [type  (if (= type :curve) :path type)
        attrs (get-minimal-shape type)
        attrs (cond-> attrs
                (and (not= :path type)
                     (not= :bool type))
                (-> (assoc :x 0)
                    (assoc :y 0)
                    (assoc :width 0.01)
                    (assoc :height 0.01)))
        attrs  (-> attrs
                   (assoc :id (uuid/next))
                   (assoc :frame-id uuid/zero)
                   (assoc :parent-id uuid/zero)
                   (assoc :rotation 0))]

    (create-shape attrs)))

(defn setup-rect
  "Initializes the selrect and points for a shape."
  [{:keys [selrect points transform] :as shape}]
  (let [selrect   (or selrect (gsh/shape->rect shape))
        center    (grc/rect->center selrect)
        transform (or transform (gmt/matrix))
        points    (or points
                      (->  selrect
                           (grc/rect->points)
                           (gsh/transform-points center transform)))]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points))))

(defn setup-path
  [{:keys [content selrect points] :as shape}]
  (let [selrect (or selrect
                    (path/calc-selrect content)
                    (grc/make-rect))
        points  (or points
                    (grc/rect->points selrect))
        ;; Ensure we hace correct type here for Path Data
        content (path/content content)]
    (-> shape
        (assoc :selrect selrect)
        (assoc :points points)
        (assoc :content content))))

(defn- setup-image
  [{:keys [metadata] :as shape}]
  (-> shape
      (assoc :proportion (float (/ (:width metadata)
                                   (:height metadata))))
      (assoc :proportion-lock true)))

(defn setup-shape
  "A function that initializes the geometric data of the shape. The props must
  contain at least :x :y :width :height."
  [{:keys [type] :as props}]
  (let [shape (make-minimal-shape type)

        ;; The props can be custom records that does not
        ;; work properly with without-nils, so we first make
        ;; it plain map for proceed
        props (d/without-nils (into {} props))
        shape (merge shape (d/without-nils (into {} props)))
        shape (case (:type shape)
                (:bool :path)  (setup-path shape)
                :image (-> shape setup-rect setup-image)
                (setup-rect shape))]
    (-> shape
        (cond-> (nil? (:transform shape))
          (assoc :transform (gmt/matrix)))
        (cond-> (nil? (:transform-inverse shape))
          (assoc :transform-inverse (gmt/matrix)))
        (gpr/setup-proportions))))

;; --- SHAPE SERIALIZATION

(t/add-handlers!
 {:id "shape"
  :class Shape
  :wfn #(into {} %)
  :rfn create-shape})

#?(:clj
   (fres/add-handlers!
    {:name "penpot/shape"
     :class Shape
     :wfn fres/write-map-like
     :rfn (comp map->Shape fres/read-map-like)}))

;; --- SHAPE COPY/PASTE PROPS

;; Copy/paste properties:
;;  - Fill
;;  - Stroke
;;  - Opacity
;;  - Layout (Grid & Flex)
;;  - Flex element
;;  - Flex board
;;  - Text properties
;;  - Contraints
;;  - Shadow
;;  - Blur
;;  - Background blur
;;  - Border radius
(def ^:private basic-extract-props
  #{:fills
    :strokes
    :opacity

    ;; Layout Item
    :layout-item-margin
    :layout-item-margin-type
    :layout-item-h-sizing
    :layout-item-v-sizing
    :layout-item-max-h
    :layout-item-min-h
    :layout-item-max-w
    :layout-item-min-w
    :layout-item-absolute
    :layout-item-z-index

    ;; Constraints
    :constraints-h
    :constraints-v

    :shadow
    :blur
    :background-blur

    ;; Radius
    :r1
    :r2
    :r3
    :r4
    :corner-smoothing})

(defn extract-props
  "Retrieves an object with the 'pasteable' properties for a shape."
  [shape]
  (letfn [(assoc-props
            [props node attrs]
            (->> attrs
                 (reduce
                  (fn [props attr]
                    (cond-> props
                      (and (not (contains? props attr))
                           (some? (get node attr)))
                      (assoc attr (get node attr))))
                  props)))

          (extract-text-props
            [props shape]
            (->> (txt/node-seq (:content shape))
                 (reduce
                  (fn [result node]
                    (cond-> result
                      (txt/is-root-node? node)
                      (assoc-props node txt/root-attrs)

                      (txt/is-paragraph-node? node)
                      (assoc-props node txt/paragraph-attrs)

                      (txt/is-text-node? node)
                      (assoc-props node txt/text-node-attrs)))
                  props)))

          (extract-layout-attrs [props shape]
            (d/patch-object props (select-keys shape ctsl/layout-attrs)))]

    (let [;; For texts we don't extract the fill
          extract-props
          (cond-> basic-extract-props (cfh/text-shape? shape) (disj :fills))]
      (-> shape
          (select-keys extract-props)
          (cond-> (cfh/text-shape? shape) (extract-text-props shape))
          (cond-> (ctsl/any-layout? shape) (extract-layout-attrs shape))))))

(defn patch-props
  "Given the object of `extract-props` applies it to a shape. Adapt the shape if necessary"
  [shape props objects]

  (letfn [(patch-text-props [shape props]
            (-> shape
                (update
                 :content
                 (fn [content]
                   (->> content
                        (txt/transform-nodes
                         (fn [node]
                           (cond-> node
                             (txt/is-root-node? node)
                             (d/patch-object (select-keys props txt/root-attrs))

                             (txt/is-paragraph-node? node)
                             (d/patch-object (select-keys props txt/paragraph-attrs))

                             (txt/is-text-node? node)
                             (d/patch-object (select-keys props txt/text-node-attrs))))))))))

          (patch-layout-props [shape props]
            (let [shape (d/patch-object shape (select-keys props ctsl/layout-attrs))]
              (cond-> shape
                (ctsl/grid-layout? shape)
                (ctsl/assign-cells objects))))]

    (-> shape
        (d/patch-object (select-keys props basic-extract-props))
        (cond-> (cfh/text-shape? shape) (patch-text-props props))
        (cond-> (cfh/frame-shape? shape) (patch-layout-props props)))))



(defn- set-fill-color
  [shape position color opacity gradient image]
  (update-in shape [:fills position]
             (fn [fill]
               (d/without-nils (assoc fill
                                      :fill-color color
                                      :fill-opacity opacity
                                      :fill-color-gradient gradient
                                      :fill-image image)))))


(defn- attach-fill-color
  [shape position ref-id ref-file]
  (d/update-in-when shape [:fills position]
                    (fn [fill]
                      (-> fill
                          (assoc :fill-color-ref-file ref-file)
                          (assoc :fill-color-ref-id ref-id)))))

(defn- detach-fill-color
  [shape position]
  (d/update-in-when shape [:fills position] dissoc :fill-color-ref-id :fill-color-ref-file))


(defn- set-stroke-color
  [shape position color opacity gradient image]
  (d/update-in-when shape [:strokes position]
                    (fn [stroke]
                      (-> stroke
                          (assoc :stroke-color color)
                          (assoc :stroke-opacity opacity)
                          (assoc :stroke-color-gradient gradient)
                          (assoc :stroke-image image)
                          (d/without-nils)))))

(defn- attach-stroke-color
  [shape position ref-id ref-file]
  (d/update-in-when shape [:strokes position]
                    (fn [stroke]
                      (-> stroke
                          (assoc :stroke-color-ref-id ref-id)
                          (assoc :stroke-color-ref-file ref-file)))))

(defn- detach-stroke-color
  [shape position]
  (d/update-in-when shape [:strokes position] dissoc :stroke-color-ref-id :stroke-color-ref-file))

(defn- set-shadow-color
  [shape position color opacity gradient]
  (d/update-in-when shape [:shadow position :color]
                    (fn [shadow-color]
                      (-> shadow-color
                          (assoc :color color)
                          (assoc :opacity opacity)
                          (assoc :gradient gradient)
                          (d/without-nils)))))

(defn- attach-shadow-color
  [shape position ref-id ref-file]
  (d/update-in-when shape [:shadow position :color]
                    (fn [color]
                      (-> color
                          (assoc :ref-id ref-id)
                          (assoc :ref-file ref-file)))))

(defn- detach-shadow-color
  [shape position]
  (d/update-in-when shape [:shadow position :color] dissoc :ref-id :ref-file))

(defn- set-grid-color
  [shape position color opacity gradient]
  (d/update-in-when shape [:grids position :params :color]
                    (fn [grid-color]
                      (-> grid-color
                          (assoc :color color)
                          (assoc :opacity opacity)
                          (assoc :gradient gradient)
                          (d/without-nils)))))

(defn- attach-grid-color
  [shape position ref-id ref-file]
  (d/update-in-when shape [:grids position :params :color]
                    (fn [color]
                      (-> color
                          (assoc :ref-id ref-id)
                          (assoc :ref-file ref-file)))))

(defn- detach-grid-color
  [shape position]
  (d/update-in-when shape [:grids position :params :color] dissoc :ref-id :ref-file))

(defn process-shape-colors
  "Execute an update function on all colors of a shape."
  [shape process-fn]
  (let [process-fill (fn [shape [position fill]]
                       (process-fn shape
                                   position
                                   (fill->color fill)
                                   set-fill-color
                                   attach-fill-color
                                   detach-fill-color))

        process-stroke (fn [shape [position stroke]]
                         (process-fn shape
                                     position
                                     (clr/stroke->color stroke)
                                     set-stroke-color
                                     attach-stroke-color
                                     detach-stroke-color))

        process-shadow (fn [shape [position shadow]]
                         (process-fn shape
                                     position
                                     (clr/shadow->color shadow)
                                     set-shadow-color
                                     attach-shadow-color
                                     detach-shadow-color))

        process-grid (fn [shape [position grid]]
                       (process-fn shape
                                   position
                                   (clr/grid->color grid)
                                   set-grid-color
                                   attach-grid-color
                                   detach-grid-color))

        process-text-node (fn [node]
                            (as-> node $
                              (reduce process-fill $ (d/enumerate (:fills $)))
                              (reduce process-stroke $ (d/enumerate (:strokes $)))))

        process-text (fn [shape]
                       (let [content     (:content shape)
                             new-content (txt/transform-nodes process-text-node content)]
                         (if (not= content new-content)
                           (assoc shape :content new-content)
                           shape)))]

    (as-> shape $
      (reduce process-fill $ (d/enumerate (:fills $)))
      (reduce process-stroke $ (d/enumerate (:strokes $)))
      (reduce process-shadow $ (d/enumerate (:shadow $)))
      (reduce process-grid $ (d/enumerate (:grids $)))
      (process-text $))))

(defn- get-text-node-colors
  "Get all colors used by a node of a text shape"
  [node]
  (concat (map fill->color (:fills node))
          (map clr/stroke->color (:strokes node))))

(defn get-all-colors
  "Get all colors used by a shape, in any section."
  [shape]
  ;; FIXME: all this functions should be really in color?
  (concat (map fill->color (:fills shape))
          (map clr/stroke->color (:strokes shape))
          (map clr/shadow->color (:shadow shape))
          (when (= (:type shape) :frame)
            (map clr/grid->color (:grids shape)))
          (when (= (:type shape) :text)
            (reduce (fn [colors node]
                      (concat colors (get-text-node-colors node)))
                    ()
                    (txt/node-seq (:content shape))))))

(defn uses-library-color?
  "Check if the shape uses the given library color."
  [shape library-id color-id]
  (let [all-colors (get-all-colors shape)]
    (some #(and (= (:ref-id %) color-id)
                (= (:ref-file %) library-id))
          all-colors)))

(defn uses-library-colors?
  "Check if the shape uses any color in the given library."
  [shape library-id]
  (let [all-colors (get-all-colors shape)]
    (some #(and (some? (:ref-id %))
                (= (:ref-file %) library-id))
          all-colors)))

(defn remap-colors
  "Change the shape so that any use of the given color now points to
  the given library."
  [shape library-id color]
  (letfn [(remap-color [shape position shape-color _ attach-fn _]
            (if (= (:ref-id shape-color) (:id color))
              (attach-fn shape
                         position
                         (:id color)
                         library-id)
              shape))]

    (process-shape-colors shape remap-color)))
