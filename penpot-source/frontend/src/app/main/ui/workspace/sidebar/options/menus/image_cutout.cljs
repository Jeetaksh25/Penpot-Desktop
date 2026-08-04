;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.image-cutout
  "P1.19 Image cutout — discoverable \"Cut out\" / \"Crop to shape\"
  affordance for the multi-selection case (one image + one vector shape).

  Renders a single coral-accent button (Lucide `scissors` glyph) that
  emits `dwic/cutout-image` when the selection contains an image and a
  vector shape. The event delegates to `dwg/mask-group`, wrapping both
  shapes in a masked group whose topmost child (the vector) is the clip
  mask — a non-destructive cutout that can be undone with one undo step
  or restored via `dw/unmask-group`."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.main.data.workspace.image-cutout :as dwic]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; Lucide `scissors` icon — stroke-width 2, round caps/joins, currentColor.
;; Matches the Lucide inline-SVG convention used across the AI surfaces
;; (viewBox 0 0 24 24, fill none). The stroke picks up the coral accent
;; via the `.image-cutout-btn` color rule in image_cutout.scss.
(def ^:private scissors-icon
  [:svg {:class           (stl/css :image-cutout-icon)
         :width           "16" :height "16"
         :viewBox         "0 0 24 24" :fill "none"
         :stroke          "currentColor" :stroke-width "2"
         :stroke-linecap  "round" :stroke-linejoin "round"
         :aria-hidden     true}
   [:circle {:cx "6" :cy "6" :r "3"}]
   [:circle {:cx "6" :cy "18" :r "3"}]
   [:line   {:x1 "20" :y1 "4"  :x2 "8.12" :y2 "15.88"}]
   [:line   {:x1 "14.47" :y1 "14.48" :x2 "20" :y2 "20"}]
   [:line   {:x1 "8.12" :y1 "8.12"  :x2 "12" :y2 "12"}]])

;; A vector shape eligible to be the clipping mask: leaf geometry, not a
;; container. Mirrors `dwic/vector-shape?` (kept private there) so the
;; button enables/disables consistently with the event's guard.
(defn- vector-shape?
  [shape]
  (and (some? shape)
       (not (cfh/image-shape? shape))
       (not (cfh/frame-shape? shape))
       (not (cfh/text-shape? shape))
       (not (cfh/group-shape? shape))
       (not (cfh/bool-shape? shape))))

(defn- has-cutout-pair?
  "True when `shapes` contains at least one image and at least one
  eligible vector shape — the minimum to perform a cutout."
  [shapes]
  (let [images  (filter cfh/image-shape? shapes)
        vectors (filter vector-shape? shapes)]
    (and (seq images) (seq vectors))))

(mf/defc image-cutout-menu*
  "Renders the Cut out button. Always mounted in the multi-selection
  panel; hidden entirely (returns nil) when the selection has no
  image+vector pair, so it never crowds unrelated selections."
  [{:keys [shapes]}]
  (let [shapes    (or shapes [])
        on-cutout (mf/use-fn
                   (mf/deps shapes)
                   (fn []
                     (st/emit! (dwic/cutout-image))))]
    (when (has-cutout-pair? shapes)
      [:div {:class (stl/css :image-cutout-options)}
       [:button {:type     "button"
                 :class    (stl/css :image-cutout-btn)
                 :title    (tr "workspace.shape.image.cutout")
                 :on-click on-cutout}
        scissors-icon
        [:span {:class (stl/css :image-cutout-label)}
         (tr "workspace.shape.image.cutout")]]])))