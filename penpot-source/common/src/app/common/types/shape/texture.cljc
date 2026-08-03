;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.texture
  (:require
   [app.common.schema :as sm]))

;; Figma-parity texture effect (gap #63). A new effect schema. Group V
;; adds an opaque `[:texture]` vector slot ([:vector ::sm/any]) to
;; schema:shape-attrs in shape.cljc; this file defines the structure of a
;; single texture effect map. Max one texture effect per layer. The
;; renderer distress / texture overlay (clipped to shape bounds, with
;; shadow interaction) is deferred (no build to verify); the value
;; round-trips on the shape via dwsh/update-shapes.
(def schema:texture-effect
  [:map {:title "TextureEffect"}
   [:id ::sm/uuid]
   [:type [:enum :texture]]
   [:hidden :boolean]
   ;; Texture cell size on X / Y (in shape-space units).
   [:size-x {:optional true} ::sm/safe-number]
   [:size-y {:optional true} ::sm/safe-number]
   ;; Edge distress radius.
   [:radius {:optional true} ::sm/safe-number]
   ;; When true the texture is clipped to the shape bounds (drop shadows
   ;; interact with the clipped texture). Absent = clipped (Figma default).
   [:clip-to-shape {:optional true} :boolean]])

(def check-texture-effect
  (sm/check-fn schema:texture-effect))

(def valid-texture-effect?
  (sm/validator schema:texture-effect))