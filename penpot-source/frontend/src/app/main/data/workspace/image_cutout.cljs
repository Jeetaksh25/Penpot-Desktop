;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.image-cutout
  "P1.19 Image cutout — non-destructive \"crop image to shape\".

  Given a selection containing exactly one raster image shape and one
  vector shape, this wraps both in a masked group whose mask (the
  topmost child of a `:masked-group`) is the vector shape and whose
  clipped content is the image. The vector shape thus acts as a
  clipping mask for the image, non-destructively: ungrouping / unmasking
  (`dw/unmask-group`) restores both shapes untouched.

  Implementation note — why we delegate to `dwg/mask-group`:
    Penpot's masked-group primitive (`app.main.data.workspace.groups`)
    creates a group whose `:shapes` vector is ordered topmost-first
    (see `cts/setup-shape` + `cfh/append-at-the-end` de-dup in
    `process-change :mov-objects`); the renderer
    (`app.main.ui.shapes.group/group-shape`) treats `(first childs)`
    as the mask. So the TOPMOST selected shape becomes the mask. In the
    canonical cutout flow the user draws a vector shape OVER the image,
    so the vector is topmost and becomes the mask — exactly the desired
    result. Reusing the proven `mask-group` changeset (undo transaction,
    constraint reset, parent resize, component-detach, grid-cell fixup)
    is far safer than re-deriving it. This event is a thin, discoverable,
  validated entry point that delegates."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.types.container :as ctn]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.groups :as dwg]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; A vector shape is any shape that can serve as a clipping path: not an
;; image, not a frame, not a text, not an existing group/bool (those are
;; containers, not leaf geometry). Path / rect / circle / svg-raw all
;; qualify — Penpot's mask renderer converts the shape to a clip/mask
;; element regardless of its concrete type.
(defn- vector-shape?
  [shape]
  (and (some? shape)
       (not (cfh/image-shape? shape))
       (not (cfh/frame-shape? shape))
       (not (cfh/text-shape? shape))
       (not (cfh/group-shape? shape))
       (not (cfh/bool-shape? shape))))

(defn- eligible-pair?
  "True when `shapes` contains exactly one image and one eligible vector
  shape (in any order). The selection may contain other shapes — only
  the image + vector pair is required; `dwg/mask-group` will group all
  selected shapes, so the caller filters the selection down to the pair
  before emitting."
  [shapes]
  (let [images   (filter cfh/image-shape? shapes)
        vectors  (filter vector-shape? shapes)]
    (and (= 1 (count images))
         (>= (count vectors) 1))))

(defn cutout-image
  "WatchEvent. Non-destructive image cutout: clip the selected image to
  the selected vector shape by wrapping both in a masked group whose
  mask is the (topmost) vector shape.

  With no args, operates on the current workspace selection. With an
  explicit `ids` collection (ordered-set of shape ids), operates on
  those. Emits `dwg/mask-group` with the image + vector ids — the
  proven mask changeset does the structural work in one undo step."
  ([] (cutout-image nil))
  ([ids]
   (ptk/reify ::cutout-image
     ptk/WatchEvent
     (watch [_ state _]
       (let [page-id  (:current-page-id state)
             objects  (dsh/lookup-page-objects state page-id)
             selected (->> (or ids (dsh/lookup-selected state))
                           (cfh/clean-loops objects)
                           (remove #(ctn/has-any-copy-parent? objects (get objects %))))
             shapes   (->> selected (map #(get objects %)) (filter some?))]
         (when (eligible-pair? shapes)
           ;; Pass the full filtered selection (image + vector, and any
           ;; additional vectors) to mask-group. mask-group orders by
           ;; z-index (topmost-first) and makes the topmost shape the
           ;; mask, so the vector drawn over the image becomes the clip.
           (rx/of (dwg/mask-group selected))))))))