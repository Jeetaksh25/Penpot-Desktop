;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.a11y
  "ARIA authoring events (ALL_APPS_PARITY P1.06).

  Persists an accessible name (aria-label) and an ARIA role on the
  selected shape under an additive `:a11y` map (`{:label \"...\" :role
  :button}`). The `:a11y` key is assoc'd straight onto the shape via
  `dwsh/update-shapes`, so no shape-record / schema refactor is needed:
  Penpot shapes are `cr/defrecord Shape` instances whose `__extmap`
  round-trips any extra key (and `wasm-enabled?` is `false` in the
  desktop shell, so `map->Shape` preserves the slot end-to-end).

  Each event delegates to `dwsh/update-shapes`, which commits its own
  changeset with `save-undo?` defaulted to true -> exactly one undo
  step per authoring action. Additive: a shape with no `:a11y` map is
  byte-identical to before."
  (:require
   [app.main.data.workspace.shapes :as dwsh]))

(defn- assoc-a11y
  "Build a `dwsh/update-shapes` event that merges `patch` into the
  shape's `:a11y` map. `patch` is a map like `{:label \"...\"}` or
  `{:role :button}`. A nil / empty `:a11y` map is seeded with `{}` so
  the merge always has a base."
  [shape-id patch]
  (dwsh/update-shapes
   [shape-id]
   (fn [shape]
     (let [a11y (or (:a11y shape) {})
           a11y (merge a11y patch)]
       (assoc shape :a11y a11y)))))

(defn set-a11y-label
  "Persist `label` (string) as the accessible name (aria-label) of the
  shape `shape-id`. One undo step. Nil / empty strings are stored
  as-is so the caller controls clearing."
  [shape-id label]
  (assoc-a11y shape-id {:label label}))

(defn set-a11y-role
  "Persist `role` (keyword or string) as the ARIA role of the shape
  `shape-id`. One undo step."
  [shape-id role]
  (assoc-a11y shape-id {:role role}))

(defn clear-a11y
  "Remove the `:a11y` map from `shape-id` entirely (dissoc rather than
  empty), restoring byte-identical-to-before state. One undo step."
  [shape-id]
  (dwsh/update-shapes
   [shape-id]
   (fn [shape] (dissoc shape :a11y))))