;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.shape
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes.transforms-3d :as gsh3d]
   [app.main.refs :as refs]
   [app.main.ui.context :as muc]
   [app.main.ui.hooks :as h]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.export :as ed]
   [app.main.ui.shapes.fade :as fade]
   [app.main.ui.shapes.fills :as fills]
   [app.main.ui.shapes.filters :as filters]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.svg-defs :as defs]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

;; FIXME: revisit this:
(defn propagate-wrapper-styles-child
  [child wrapper-props]
  (when (some? child)
    (let [child-props-childs
          (-> (obj/get child "props")
              (obj/clone)
              (-> (obj/get "childs")))

          child-props-childs
          (->> child-props-childs
               (map #(assoc % :wrapper-styles (obj/get wrapper-props "style"))))

          child-props
          (-> (obj/get child "props")
              (obj/clone)
              (obj/set! "childs" child-props-childs))]

      (-> (obj/clone child)
          (obj/set! "props" child-props)))))

(defn propagate-wrapper-styles
  ([children wrapper-props]
   (if ^boolean (obj/array? children)
     (->> children (map #(propagate-wrapper-styles-child % wrapper-props)))
     (-> children (propagate-wrapper-styles-child wrapper-props)))))

(mf/defc shape-container
  {::mf/forward-ref true
   ::mf/wrap-props false}
  [props ref]

  (let [shape            (unchecked-get props "shape")
        children         (unchecked-get props "children")
        pointer-events   (unchecked-get props "pointer-events")
        shape-id         (dm/get-prop shape :id)

        preview-blend-mode-ref
        (mf/with-memo [shape-id] (refs/workspace-preview-blend-by-id shape-id))

        blend-mode       (-> (mf/deref preview-blend-mode-ref)
                             (or (:blend-mode shape)))

        type             (dm/get-prop shape :type)
        render-id        (h/use-render-id)

        ;; 3D transform (gap #66). APPROX: CSS matrix3d on the wrapping
        ;; <g>. nil when the :transform-3d slot is absent or empty, so the
        ;; cond-> below adds NO new style keys and the <g style> is
        ;; byte-identical to today.
        t3d-css          (gsh3d/transform-3d-css-str shape)

        styles           (-> (obj/create)
                             (obj/set! "pointerEvents" pointer-events)
                             (cond-> (not (cfh/frame-shape? shape))
                               (obj/set! "opacity" (:opacity shape)))
                             (cond-> (:hidden shape)
                               (obj/set! "display" "none"))
                             (cond-> (and blend-mode (not= blend-mode :normal))
                               (obj/set! "mixBlendMode" (d/name blend-mode)))
                             (cond-> (some? t3d-css)
                               (-> (obj/set! "transform" t3d-css)
                                   (obj/set! "transformOrigin" (gsh3d/transform-3d-origin shape))
                                   (obj/set! "transformStyle" "preserve-3d"))))

        include-metadata? (mf/use-ctx ed/include-metadata-ctx)

        shape-without-blur (dissoc shape :blur :blurs :stacked-blur :glass :noise :texture :shader-effect)
        shape-without-shadows (-> shape (assoc :shadow []) (dissoc :blurs :stacked-blur :glass :noise :texture :shader-effect))

        filter-id        (dm/str "filter-" render-id)
        filter-str
        (when (or (cfh/group-shape? shape)
                  (cfh/svg-raw-shape? shape))
          (filters/filter-str filter-id shape))

        wrapper-props
        (-> (obj/clone props)
            (obj/unset! "shape")
            (obj/unset! "children")
            (obj/set! "ref" ref)
            (obj/set! "id" (dm/fmt "shape-%" shape-id))
            (obj/set! "style" styles))

        wrapper-props
        (cond-> wrapper-props
          ;; NOTE: This is added for backward compatibility
          (and (cfh/text-shape? shape)
               (empty? (:position-data shape)))
          (-> (obj/set! "x" (:x shape))
              (obj/set! "y" (:y shape))
              (obj/set! "width" (:width shape))
              (obj/set! "height" (:height shape)))

          (= :group type)
          (-> (attrs/add-fill-props! shape render-id)
              (attrs/add-border-props! shape))

          (some? filter-str)
          (obj/set! "filter" filter-str))

        svg-group?
        (and (contains? shape :svg-attrs) (= :group type))

        children
        (cond-> children
          svg-group?
          (propagate-wrapper-styles wrapper-props))]

    [:> (mf/provider muc/render-id) {:value render-id}
     [:> :g wrapper-props
      (when include-metadata?
        [:> ed/export-data* {:shape shape}])

      [:defs
       [:> defs/svg-defs*         {:shape shape :render-id render-id}]

       ;; The filters for frames should be setup inside the container.
       (when-not (cfh/frame-shape? shape)
         [:*
          [:> filters/filters*       {:shape shape :filter-id filter-id}]
          [:> filters/filters*       {:shape shape-without-blur :filter-id (dm/fmt "filter-shadow-%" render-id)}]
          [:> filters/filters*       {:shape shape-without-shadows :filter-id (dm/fmt "filter-blur-%" render-id)}]])

       ;; FADE (#60 fade) — site B of the 2-site MASK lockstep. Emits the
       ;; <mask id="fade-<render-id>"> + its <linearGradient> when :fade is
       ;; present and non-hidden (gate inside fade-mask* mirrors site A in
       ;; attrs.cljs add-fill-props! exactly). Mounted only for non-frame
       ;; shapes — frames render via frame.cljs and the fade UI is hidden
       ;; for frames, so a frame never carries :fade and this is a no-op for
       ;; them (byte-identical). Fade is a mask, not a filter, so it lives
       ;; outside the filters* block above and never enters the 3-gate
       ;; filter lockstep.
       (when-not (cfh/frame-shape? shape)
         [:> fade/fade-mask* {:shape shape :render-id render-id}])

       [:& frame/frame-clip-def   {:shape shape :render-id render-id}]

       ;; Text fills need to be defined afterwards because they are specified per text-block
       (when-not (cfh/text-shape? shape)
         [:& fills/fills            {:shape shape :render-id render-id}])]

      children]]))
