;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.path
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.helpers :as cpf]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.math :as mth]
   [app.common.types.path.bool :as bool]
   [app.common.types.path.helpers :as helpers]
   [app.common.types.path.impl :as impl]
   [app.common.types.path.segment :as segment]
   [app.common.types.path.shape-to-path :as stp]
   [app.common.types.path.subpath :as subpath]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:const bool-group-style-properties bool/group-style-properties)
(def ^:const bool-style-properties bool/style-properties)

(defn get-default-bool-fills
  []
  (bool/get-default-fills))

(def schema:content impl/schema:content)
(def schema:segments impl/schema:segments)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONSTRUCTORS & TYPE METHODS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn content?
  [o]
  (impl/path-data? o))

(defn content
  "Create path content from plain data or bytes, returns itself if it
  is already PathData instance"
  [data]
  (impl/path-data data))

(defn from-bytes
  [data]
  (impl/from-bytes data))

(defn from-string
  [data]
  (impl/from-string data))

(defn from-plain
  [data]
  (impl/from-plain data))

(defn check-content
  [content]
  (impl/check-content content))

(defn get-byte-size
  "Get byte size of a path content"
  [content]
  (impl/-get-byte-size content))

(defn write-to
  [content buffer offset]
  (impl/-write-to content buffer offset))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TRANSFORMATIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn close-subpaths
  "Given a content, searches a path for possible subpaths that can
  create closed loops and merge them; then return the transformed path
  content as PathData instance"
  [content]
  (-> (subpath/close-subpaths content)
      (impl/from-plain)))

(defn merge-touching-subpaths
  "Given a content, fold consecutive subpaths whose endpoints coincide
  into a single continuous subpath, returning a PathData instance.

  Conservative counterpart of `close-subpaths`: only adjacent subpaths
  are merged and none are reversed, so fill rules and stroke-dasharray
  semantics are preserved. Used at SVG-import time on stroke-only paths
  to recover the `stroke-linejoin` rendering when authoring tools split
  a continuous polyline into adjacent `M..L M..L` subpaths."
  [content]
  (-> (subpath/merge-touching-subpaths content)
      (impl/from-plain)))

(defn apply-content-modifiers
  "Apply delta modifiers over the path content"
  [content modifiers]
  (assert (impl/check-content content))

  (letfn [(apply-to-index [content [index params]]
            (if (contains? content index)
              (cond-> content
                (and
                 (or (:c1x params) (:c1y params) (:c2x params) (:c2y params))
                 (= :line-to (get-in content [index :command])))

                (-> (assoc-in [index :command] :curve-to)
                    (assoc-in [index :params]
                              (helpers/make-curve-params
                               (get-in content [index :params])
                               (get-in content [(dec index) :params]))))

                (:x params) (update-in [index :params :x] + (:x params))
                (:y params) (update-in [index :params :y] + (:y params))

                (:c1x params) (update-in [index :params :c1x] + (:c1x params))
                (:c1y params) (update-in [index :params :c1y] + (:c1y params))

                (:c2x params) (update-in [index :params :c2x] + (:c2x params))
                (:c2y params) (update-in [index :params :c2y] + (:c2y params)))
              content))]

    (if (some? modifiers)
      (impl/path-data
       (reduce apply-to-index (vec content) modifiers))
      content)))

(defn transform-content
  "Applies a transformation matrix over content and returns a new
  content as PathData instance."
  [content transform]
  (segment/transform-content content transform))

(defn move-content
  [content move-vec]
  (if (gpt/zero? move-vec)
    content
    (segment/move-content content move-vec)))

(defn update-geometry
  "Update shape with new geometry calculated from provided content"
  ([shape content]
   (update-geometry (assoc shape :content content)))
  ([shape]
   (let [flip-x
         (get shape :flip-x)

         flip-y
         (get shape :flip-y)

         ;; NOTE: we ensure that content is PathData instance
         content
         (impl/path-data
          (get shape :content))

         ;; Ensure plain format once
         transform
         (cond-> (:transform shape (gmt/matrix))
           flip-x (gmt/scale (gpt/point -1 1))
           flip-y (gmt/scale (gpt/point 1 -1)))

         transform-inverse
         (cond-> (gmt/matrix)
           flip-x (gmt/scale (gpt/point -1 1))
           flip-y (gmt/scale (gpt/point 1 -1))
           :always (gmt/multiply (:transform-inverse shape (gmt/matrix))))

         center
         (or (some-> (dm/get-prop shape :selrect) grc/rect->center)
             (segment/content-center content))

         base-content
         (segment/transform-content content (gmt/transform-in center transform-inverse))

         ;; Calculates the new selrect with points given the old center
         points
         (-> (segment/content->selrect base-content)
             (grc/rect->points)
             (gco/transform-points center transform))

         points-center
         (gco/points->center points)

         ;; Points is now the selrect but the center is different so we can create the selrect
         ;; through points
         selrect
         (-> points
             (gco/transform-points points-center transform-inverse)
             (grc/points->rect))]

     (-> shape
         (assoc :content content)
         (assoc :points points)
         (assoc :selrect selrect)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PATH SHAPE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-points
  "Returns points for the given content. Accepts PathData instances or
  plain segment vectors."
  [content]
  (let [content (impl/path-data content)]
    (segment/get-points content)))

(defn calc-selrect
  "Calculate selrect from a content. The content can be in a PathData
  instance or plain vector of segments."
  [content]
  (let [content (impl/path-data content)]
    (segment/content->selrect content)))

(defn get-handlers
  "Retrieve a map where for every point will retrieve a list of the
  handlers that are associated with that point.
  point -> [[index, prefix]]"
  [content]
  (let [content (impl/path-data content)]
    (segment/get-handlers content)))

(defn get-handler-point
  "Given a content, segment index and prefix, get a handler point."
  [content index prefix]
  (let [content (impl/path-data content)]
    (segment/get-handler-point content index prefix)))

(defn get-handler
  "Given a segment (command map) and a prefix, returns the handler
  coordinate map {:x ... :y ...} from its params, or nil when absent."
  [command prefix]
  (segment/get-handler command prefix))

(defn handler->node
  "Given a content, index and prefix, returns the path node (anchor
  point) that the handler belongs to."
  [content index prefix]
  (let [content (impl/path-data content)]
    (segment/handler->node content index prefix)))

(defn opposite-index
  "Calculates the opposite handler index given a content, index and
  prefix."
  [content index prefix]
  (let [content (impl/path-data content)]
    (segment/opposite-index content index prefix)))

(defn point-indices
  "Returns the indices of all segments whose endpoint matches point."
  [content point]
  (let [content (impl/path-data content)]
    (segment/point-indices content point)))

(defn handler-indices
  "Returns [[index prefix] ...] of all handlers associated with point."
  [content point]
  (let [content (impl/path-data content)]
    (segment/handler-indices content point)))

(defn next-node
  "Calculates the next node segment to be inserted when drawing."
  [content position prev-point prev-handler]
  (let [content (impl/path-data content)]
    (segment/next-node content position prev-point prev-handler)))

(defn append-segment
  "Appends a segment to content, accepting PathData or plain vector."
  [content segment]
  (let [content (impl/path-data content)]
    (segment/append-segment content segment)))

(defn points->content
  "Given a vector of points generate a path content."
  [points & {:keys [close]}]
  (segment/points->content points :close close))

(defn closest-point
  "Returns the closest point in the path to position, at a given precision."
  [content position precision]
  (let [content (impl/path-data content)]
    (when (pos? (count content))
      (segment/closest-point content position precision))))

(defn make-corner-point
  "Changes the content to make a point a corner."
  [content point]
  (let [content (impl/path-data content)]
    (segment/make-corner-point content point)))

(defn make-curve-point
  "Changes the content to make a point a curve."
  [content point]
  (let [content (impl/path-data content)]
    (segment/make-curve-point content point)))

(defn split-segments
  "Given a content, splits segments between points with new segments."
  [content points value]
  (let [content (impl/path-data content)]
    (segment/split-segments content points value)))

(defn remove-nodes
  "Removes the given points from content, reconstructing paths as needed."
  [content points]
  (let [content (impl/path-data content)]
    (segment/remove-nodes content points)))

(defn merge-nodes
  "Reduces contiguous segments at the given points to a single point."
  [content points]
  (let [content (impl/path-data content)]
    (segment/merge-nodes content points)))

(defn join-nodes
  "Creates new segments between points that weren't previously connected."
  [content points]
  (let [content (impl/path-data content)]
    (segment/join-nodes content points)))

(defn separate-nodes
  "Removes the segments between the given points."
  [content points]
  (let [content (impl/path-data content)]
    (segment/separate-nodes content points)))

(defn- calc-bool-content*
  "Calculate the boolean content from shape and objects. Returns plain
  vector of segments"
  [shape objects]
  (let [extract-content-xf
        (comp (map (d/getf objects))
              (remove :hidden)
              (remove cpf/svg-raw-shape?)
              (map #(stp/convert-to-path % objects))
              (map :content))

        contents
        (sequence extract-content-xf (:shapes shape))

        ;; Per-child shape-mode support (optional, backward compatible).
        ;; When ANY child of this :bool carries a `:shape-mode` (one of
        ;; :union/:difference/:intersection/:exclusion), the combination fold
        ;; uses each child's own mode instead of the single group `:bool-type`.
        ;; When no child has a `:shape-mode`, the original group-:bool-type
        ;; code path is used unchanged (byte-identical behavior).
        modes
        (into []
              (comp (map (d/getf objects))
                    (remove :hidden)
                    (remove cpf/svg-raw-shape?)
                    (map :shape-mode))
              (:shapes shape))

        has-per-child-mode?
        (some some? modes)]

    (ex/try!
     (if has-per-child-mode?
       (bool/calculate-content (:bool-type shape) contents modes)
       (bool/calculate-content (:bool-type shape) contents))

     :on-exception
     (fn [cause]
       (ex/raise :type :internal
                 :code :invalid-path-content
                 :hint (str "unable to calculate bool content for shape " (:id shape))
                 :shapes (:shapes shape)
                 :type (:bool-type shape)
                 :content (vec contents)
                 :cause cause)))))

(def wasm:calc-bool-content
  "A overwrite point for setup a WASM version of the `calc-bool-content*` function"
  nil)

(defn calc-bool-content
  "Calculate the boolean content from shape and objects. Returns a
  packed PathData instance"
  [shape objects]
  (let [content (calc-bool-content* shape objects)]
    (impl/path-data content)))

(defn update-bool-shape
  "Calculates the selrect+points for the boolean shape"
  [shape objects]
  (let [content (if (fn? wasm:calc-bool-content)
                  (wasm:calc-bool-content shape objects)
                  (calc-bool-content shape objects))
        shape   (assoc shape :content content)]
    (update-geometry shape)))

(defn shape-with-open-path?
  [shape]
  (let [svg? (contains? shape :svg-attrs)
        ;; No close subpaths for svgs imported
        maybe-close (if svg? identity subpath/close-subpaths)]
    (and (= :path (:type shape))
         (not (->> shape
                   :content
                   (maybe-close)
                   (subpath/get-subpaths)
                   (every? subpath/is-closed?))))))

(defn convert-to-path
  "Transform a shape to a path shape"
  ([shape]
   (convert-to-path shape {}))
  ([shape objects]
   (let [shape' (stp/convert-to-path shape objects)]
     (if (identical? shape shape')
       shape'
       (update shape' :content impl/path-data)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VARIABLE-WIDTH STROKES (Figma-parity #53)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Per-node variable-width stroke data. The path's binary segment format
;; (impl.cljc, fixed 28-byte layout) has no room for a width field, so the
;; width data lives OUTSIDE the content as a plain map
;;   {segment-index width}
;; keyed by the index of the segment whose endpoint is the node. An absent
;; map (or absent index) means "uniform stroke width" — the existing
;; behavior — so the feature is purely additive. Persistence on the shape
;; (shape.cljc :stroke-width is a single scalar) and the renderer conversion
;; to a filled outline path (SVG has no native variable-width stroke) are
;; DEFERRED — see the editor.cljs width-handle render guard and the note on
;; `width-handles`. These accessors are pure so they can be reused by a
;; future shape slot / renderer pass without change.

(defn make-segment-widths
  "Create an empty per-node segment-widths map. Additive: an empty/absent
  map means uniform stroke width."
  []
  {})

(defn get-segment-width
  "Get the variable-width value for a segment index, or `fallback` when
  the widths map is absent or has no entry for `index`."
  ([widths index]
   (get-segment-width widths index nil))
  ([widths index fallback]
   (if (and (map? widths) (contains? widths index))
     (get widths index)
     fallback)))

(defn set-segment-width
  "Associate a width value with a segment index in the widths map."
  [widths index width]
  (assoc widths index width))

(defn width-handles
  "Given path content and a per-node width map {segment-index width},
  compute width-handle positions for the vector editor. Each handle is
  placed perpendicular to the incoming segment at the node, offset by half
  the node's width. Returns a vector of {:index :width :point :position}
  (point = node, position = handle). Entries for :close-path segments or
  out-of-range indices are skipped. Pure and additive — only meaningful
  when a non-empty widths map is supplied; returns nil otherwise."
  [content widths]
  (when (and (map? widths) (pos? (count widths)))
    (let [content (impl/path-data content)]
      (into []
            (keep
             (fn [[index width]]
               (when-let [segment (nth content index nil)]
                 (let [node (helpers/segment->point segment)]
                   (when (some? node)
                     (let [prev (when (pos? index) (nth content (dec index) nil))
                           prev-point (some-> prev helpers/segment->point)
                           tangent (if (and prev-point (not= prev-point node))
                                     (gpt/unit (gpt/to-vec prev-point node))
                                     (gpt/point 1 0))
                           normal (gpt/perpendicular tangent)
                           offset (gpt/scale normal (/ width 2))]
                       {:index index
                        :width width
                        :point node
                        :position (gpt/add node offset)}))))))
            widths))))

(dm/export impl/decode-segments)
