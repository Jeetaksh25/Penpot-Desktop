;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.brush
  "Figma-parity brush tool + custom brushes (gap #52).

  A brush is a reusable definition that stamps a closed-vector source
  shape along a freehand path with stretch / scatter / spacing params
  plus size and opacity. Brush definitions are stored as a new optional
  asset collection on the file (see file.cljc :brushes). The brush tool
  itself lives in top_toolbar.cljs; the settings panel is brush_row.cljs.

  The renderer path-following shape repetition (stamping the source
  shape along the drawn path with stretch/scatter) is significant render
  work and is DEFERRED — this namespace provides the data schema only;
  the value round-trips on the file asset and on a shape's brush slot."
  (:require
   [app.common.schema :as sm]))

(def brush-modes
  "The two Figma brush application modes. :stretch elongates the source
  style along the stroke; :scatter repeats the source style along the
  stroke."
  #{:stretch :scatter})

(def schema:brush
  "A single reusable brush definition (a file asset). :id is the asset
  key (mirrors :colors / :typographies map-of keys). :source-shape-id
  references the closed-vector shape used as the brush stamp. :mode is
  :stretch or :scatter. :spacing is the stamp spacing along the path
  (px); :scatter is the max random offset of each stamp (px); :size and
  :opacity scale the stamped source. All optional fields absent = sensible
  defaults; the asset itself is optional on the file (absent :brushes =
  no custom brushes = today's behavior)."
  [:map {:title "Brush" :closed true}
   [:id ::sm/uuid]
   [:name {:optional true} :string]
   [:source-shape-id {:optional true} ::sm/uuid]
   [:mode {:optional true} [::sm/one-of brush-modes]]
   [:spacing {:optional true} ::sm/safe-number]
   [:scatter {:optional true} ::sm/safe-number]
   [:size {:optional true} ::sm/safe-number]
   [:opacity {:optional true} [::sm/number {:min 0 :max 1}]]])

(def schema:brushes
  "A map-of brush-id -> brush definition, mirroring schema:colors /
  schema:typographies so the file asset plumbing (add / rename / delete
  / absorb) can ride the existing asset paths."
  [:map-of {:gen/max 5} ::sm/uuid schema:brush])