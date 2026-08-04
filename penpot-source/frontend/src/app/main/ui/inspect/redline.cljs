;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.inspect.redline
  "Automated redlines + shareable Inspect (ALL_APPS_PARITY P1.20).

  Renders an inspect-side redline diagram for the currently-selected
  shape: dimension lines (width / height), position-from-frame
  annotations (X / Y), and spacing-to-neighbour gaps on each side. The
  diagram is a self-contained SVG drawn from the shape's geometry and the
  frame's children, so it needs no viewport coordinate transform and
  renders identically in workspace and viewer Inspect.

  The share side (no-sign-in public Inspect, Jira/Confluence embed) needs
  a hosted public Inspect surface that ships with the publish_site hosting
  in C4. That hosting surface is a separate, deferred deliverable; here
  we only provide a real 'Copy as Jira/Confluence embed' button that
  copies an `<iframe>` snippet pointing at a placeholder public Inspect
  URL. The button is fully functional (writes to the clipboard); the URL
  is a placeholder until the public Inspect surface is hosted."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.main.data.event :as-alias ev]
   [app.main.store :as st]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; --- Geometry helpers ------------------------------------------------------

(defn- shape-rect
  "Return the axis-aligned bounding rect of `shape` in page coordinates,
  or nil when the shape lacks numeric geometry."
  [shape]
  (or (gsh/shape->rect shape)
      (when (some? (:selrect shape))
        (let [s (:selrect shape)]
          (grc/make-rect (:x s) (:y s) (:width s) (:height s))))))

(defn- siblings
  "Return the sibling shapes of `shape` (same parent, direct children of
  the parent), excluding `shape` itself, paired with their bounding
  rects. Used for spacing-to-neighbour annotations."
  [objects shape]
  (let [parent-id (:parent-id shape)
        parent    (and (some? parent-id) (get objects parent-id))
        child-ids (:shapes parent)]
    (when (seq child-ids)
      (into []
            (comp
             (map #(get objects %))
             (filter some?)
             (remove #(= (:id %) (:id shape)))
             (keep (fn [s] (when-let [r (shape-rect s)] [s r]))))
            child-ids))))

(defn- nearest-gap
  "Given the selected shape's rect `self` and a side (:left/:right/:top/
  :bottom), find the smallest gap to any sibling rect on that side. Returns
  nil when no sibling sits on that side of `self`."
  [self side sib-pairs]
  (let [sibs (case side
               :left   (filter #(< (:x2 (second %)) (:x1 self)) sib-pairs)
               :right  (filter #(> (:x1 (second %)) (:x2 self)) sib-pairs)
               :top    (filter #(< (:y2 (second %)) (:y1 self)) sib-pairs)
               :bottom (filter #(> (:y1 (second %)) (:y2 self)) sib-pairs))]
    (when (seq sibs)
      (case side
        :left   (let [x (:x1 self)]
                  (apply min (map #(- x (:x2 (second %))) sibs)))
        :right  (let [x (:x2 self)]
                  (apply min (map #(- (:x1 (second %)) x) sibs)))
        :top    (let [y (:y1 self)]
                  (apply min (map #(- y (:y2 (second %))) sibs)))
        :bottom (let [y (:y2 self)]
                  (apply min (map #(- (:y1 (second %)) y) sibs)))))))

(defn- round1
  "Round to one decimal place, dropping trailing zeros for cleaner labels."
  [n]
  (let [r (/ (js/Math.round (* n 10)) 10)]
    (str r)))

;; --- SVG diagram geometry --------------------------------------------------
;;
;; The diagram draws `self` plus its dimension / position / spacing
;; annotations inside a fixed-width SVG. We compute a uniform scale that
;; fits the shape's rect (padded to include the spacing arrows) into the
;; panel width, then project every page-space coordinate into SVG space.

(def ^:private svg-width 248)
(def ^:private pad 32)
(def ^:private dim-color "#f28b82")
(def ^:private shape-color "#7d7d7d")
(def ^:private gap-color "#9aa0a6")

(defn- union-bounds
  "Return a rect covering `rect` expanded by `extra` on every side, so the
  spacing arrows fit inside the diagram viewport."
  [rect extra]
  (grc/make-rect (- (:x1 rect) extra)
                 (- (:y1 rect) extra)
                 (+ (:width rect) (* 2 extra))
                 (+ (:height rect) (* 2 extra))))

(defn- make-transform
  "Compute `{:scale :bx :by :svg-height}` that maps page-space rect `bounds`
  into the SVG viewport with `pad` margin on every side."
  [bounds]
  (let [bw (:width bounds)
        bh (:height bounds)
        avail-w (- svg-width (* 2 pad))
        scale   (if (pos? bw) (min 1.0 (/ avail-w bw)) 1.0)
        draw-h  (* bh scale)
        svg-h   (+ draw-h (* 2 pad))]
    {:scale scale :bx (:x1 bounds) :by (:y1 bounds) :svg-height svg-h}))

(defn- px
  "Project a page-space x coordinate into SVG space using transform `t`."
  [t x]
  (+ pad (* (:scale t) (- x (:bx t)))))

(defn- py
  "Project a page-space y coordinate into SVG space using transform `t`."
  [t y]
  (+ pad (* (:scale t) (- y (:by t)))))

;; --- Jira / Confluence embed snippet ---------------------------------------

(def ^:private placeholder-inspect-url
  "https://inspect.ovion.app/share/INSPECT-TOKEN")

(defn- embed-snippet
  "Build the `<iframe>` Jira/Confluence embed snippet pointing at the
  (placeholder) public Inspect URL. The URL is replaced once the public
  Inspect hosting surface ships in C4; the snippet shape is stable."
  [shape-name]
  (str "<iframe src=\"" placeholder-inspect-url "\" "
       "width=\"480\" height=\"360\" frameborder=\"0\" "
       "title=\"Ovion Inspect"
       (when (seq shape-name) (str " — " shape-name))
       "\"></iframe>"))

;; --- Component --------------------------------------------------------------

(mf/defc redline*
  {::mf/private true}
  [{:keys [shapes objects frame]}]
  (let [single? (and (= (count shapes) 1))
        shape   (first shapes)
        frame   (or frame (cfh/get-frame objects shape))
        self    (shape-rect shape)
        frame-r (shape-rect frame)

        ;; Position from the containing frame's origin.
        pos-x   (when (and self frame-r) (- (:x1 self) (:x1 frame-r)))
        pos-y   (when (and self frame-r) (- (:y1 self) (:y1 frame-r)))

        ;; Spacing to nearest sibling on each side.
        sibs    (when self (siblings objects shape))
        gaps    (when self
                  {:left   (nearest-gap self :left sibs)
                   :right  (nearest-gap self :right sibs)
                   :top    (nearest-gap self :top sibs)
                   :bottom (nearest-gap self :bottom sibs)})

        ;; Diagram transform. Expand the shape rect so spacing arrows fit.
        t       (when self (make-transform (union-bounds self 1)))

        copy-embed-fn
        (mf/use-fn
         (mf/deps shape)
         (fn [] (embed-snippet (:name shape))))

        on-embed-copied
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "copy-inspect-embed"}))))]

    (when (and single? self t)
      (let [sx1 (px t (:x1 self))
            sy1 (py t (:y1 self))
            sx2 (px t (:x2 self))
            sy2 (py t (:y2 self))
            sw  (- sx2 sx1)
            sh  (- sy2 sy1)
            cx  (/ (+ sx1 sx2) 2)
            cy  (/ (+ sy1 sy2) 2)]
        [:div {:class (stl/css :redline-section)}
         [:> inspect-title-bar*
          {:title  (tr "inspect.redline.title")
           :class  (stl/css :redline-title-bar)}]

         [:div {:class (stl/css :redline-diagram-wrap)}
          [:svg {:class (stl/css :redline-svg)
                 :viewBox (dm/str "0 0 " svg-width " " (:svg-height t))
                 :width "100%"
                 :preserveAspectRatio "xMidYMid meet"}
           ;; Shape outline
           [:rect {:x sx1 :y sy1 :width sw :height sh
                   :fill "none" :stroke shape-color :stroke-width 1.5
                   :stroke-dasharray "3 2"}]

           ;; Width dimension line (below the shape)
           (let [dy (+ sy2 16)]
             [:g {:stroke dim-color :stroke-width 1 :fill dim-color}
              [:line {:x1 sx1 :y1 dy :x2 sx2 :y2 dy}]
              [:line {:x1 sx1 :y1 (- dy 4) :x2 sx1 :y2 (+ dy 4)}]
              [:line {:x1 sx2 :y1 (- dy 4) :x2 sx2 :y2 (+ dy 4)}]
              [:text {:x cx :y (+ dy 14)
                      :text-anchor "middle"
                      :font-size "10"
                      :font-family "Helvetica Now Display, sans-serif"
                      :stroke "none"}
               (dm/str "W " (round1 (:width self)))]])
           ;; Height dimension line (right of the shape)
           (let [dx (+ sx2 16)]
             [:g {:stroke dim-color :stroke-width 1 :fill dim-color}
              [:line {:x1 dx :y1 sy1 :x2 dx :y2 sy2}]
              [:line {:x1 (- dx 4) :y1 sy1 :x2 (+ dx 4) :y1 sy1}]
              [:line {:x1 (- dx 4) :y1 sy2 :x2 (+ dx 4) :y2 sy2}]
              [:text {:x (+ dx 4) :y (+ cy 4)
                      :text-anchor "start"
                      :font-size "10"
                      :font-family "Helvetica Now Display, sans-serif"
                      :stroke "none"}
               (dm/str "H " (round1 (:height self)))]])

           ;; Spacing arrows on each side (gap to nearest sibling)
           (when-let [g (:left gaps)]
             (let [gx (- sx1 (min (max 4 (* (:scale t) g)) (- pad 4)))]
               [:g {:stroke gap-color :stroke-width 1 :fill gap-color}
                [:line {:x1 gx :y1 cy :x2 sx1 :y2 cy :stroke-dasharray "2 2"}]
                [:text {:x (/ (+ gx sx1) 2) :y (- cy 4)
                        :text-anchor "middle"
                        :font-size "9"
                        :font-family "Helvetica Now Display, sans-serif"
                        :stroke "none"}
                 (round1 g)]]))
           (when-let [g (:right gaps)]
             (let [gx (+ sx2 (min (max 4 (* (:scale t) g)) (- pad 4)))]
               [:g {:stroke gap-color :stroke-width 1 :fill gap-color}
                [:line {:x1 sx2 :y1 cy :x2 gx :y2 cy :stroke-dasharray "2 2"}]
                [:text {:x (/ (+ sx2 gx) 2) :y (- cy 4)
                        :text-anchor "middle"
                        :font-size "9"
                        :font-family "Helvetica Now Display, sans-serif"
                        :stroke "none"}
                 (round1 g)]]))
           (when-let [g (:top gaps)]
             (let [gy (- sy1 (min (max 4 (* (:scale t) g)) (- pad 4)))]
               [:g {:stroke gap-color :stroke-width 1 :fill gap-color}
                [:line {:x1 cx :y1 gy :x2 cx :y2 sy1 :stroke-dasharray "2 2"}]
                [:text {:x cx :y (/ (+ gy sy1) 2)
                        :text-anchor "middle"
                        :font-size "9"
                        :font-family "Helvetica Now Display, sans-serif"
                        :stroke "none"}
                 (round1 g)]]))
           (when-let [g (:bottom gaps)]
             (let [gy (+ sy2 (min (max 4 (* (:scale t) g)) (- pad 4)))]
               [:g {:stroke gap-color :stroke-width 1 :fill gap-color}
                [:line {:x1 cx :y1 sy2 :x2 cx :y2 gy :stroke-dasharray "2 2"}]
                [:text {:x cx :y (/ (+ sy2 gy) 2)
                        :text-anchor "middle"
                        :font-size "9"
                        :font-family "Helvetica Now Display, sans-serif"
                        :stroke "none"}
                 (round1 g)]]))]

          ;; Position-from-frame readout
          (when (and (some? pos-x) (some? pos-y))
            [:div {:class (stl/css :redline-pos-row)}
             [:span {:class (stl/css :redline-pos-label)}
              (tr "inspect.redline.position")]
             [:span {:class (stl/css :redline-pos-value)}
              (dm/str "X " (round1 pos-x) "   Y " (round1 pos-y))]])]

         ;; Copy as Jira/Confluence embed
         [:div {:class (stl/css :redline-embed-row)}
          [:> copy-button* {:data copy-embed-fn
                            :aria-label (tr "inspect.redline.copy-embed")
                            :class (stl/css :redline-copy-btn)
                            :on-copied on-embed-copied}
           [:span {:class (stl/css :redline-copy-label)}
            (tr "inspect.redline.copy-embed")]]
          [:div {:class (stl/css :redline-embed-hint)}
           (tr "inspect.redline.embed-hint")]]]))))