;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.frameworks.components
  "Component-instance hoisting for code export (Feature 2 Phase E).

  Detects Penpot component instances that can be hoisted into reusable
  components and replaced by references, working ONLY from the page
  `objects` map (the export pipeline does not carry the file or the
  component libraries, so we cannot resolve masters cross-file).

  A component is hoistable when the selection contains two or more
  instances of the SAME local component, all untouched (no `:touched`
  overrides), none a variant container, none the main instance, none a
  subinstance head. The FIRST instance in z-order is the 'definition':
  its subtree (rendered relative to the instance head's origin) becomes
  the reusable component body, and EVERY instance in the group —
  including the definition — is replaced by a `<CompName/>` reference in
  the primary tree.

  Cross-file instances (`:component-file` present), variants, touched
  instances and single-use instances are NOT hoisted; they flatten inline
  exactly as before. This keeps hoisting strictly safe: when nothing is
  hoisted, `:hoist-map` is empty and the generators behave identically to
  the pre-hoisting code.

  This namespace produces pure DATA (`:hoist-map` + `:specs`); each
  framework's `generate-project` renders the bodies and emits the
  component files in its own syntax, with `fc/*hoist-map*` bound during
  the primary render so `render-shape` emits references."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.util.code-gen.frameworks.common :as fc]))

(defn- reachable-shapes
  "All shapes reachable from `roots` through `:shapes` (children), in a
  stable z-order pre-order walk, roots included."
  [objects roots]
  (let [out (volatile! (transient []))]
    (letfn [(walk [shape]
              (vswap! out conj! shape)
              (doseq [child (fc/children-of objects shape)]
                (walk child)))]
      (run! walk roots)
      (persistent! @out))))

(defn- hoistable-instance?
  "A top-level instance head that is safe to hoist from `objects` alone:
  local (no `:component-file`), untouched, not a variant container, not
  the main instance. Subinstances (`:component-root` nil) are excluded by
  requiring `:component-root` true."
  [shape]
  (and (ctk/instance-root? shape)
       (nil? (:component-file shape))
       (empty? (:touched shape))
       (not (true? (:is-variant-container shape)))
       (not (true? (:main-instance shape)))))

(defn- unique-comp-name
  "Derive a PascalCase component name from the instance shape's `:name`,
  de-duplicated against `used` (a set). Appends a numeric suffix on
  collision. Returns `[name updated-used]`."
  [shape used]
  (let [base (fc/component-name shape)
        base (if (str/blank? base) "Component" base)]
    (loop [n base i 1]
      (if (contains? used n)
        (recur (dm/str base i) (inc i))
        [n (conj used n)])))))

(defn collect-hoistable
  "Detect hoistable component-instance groups among the reachable shapes
  of `roots`. Returns:

    {:hoist-map {instance-id -> comp-name}   ; every instance → its ref name
     :specs     [{:comp-name :def :children :origin :size}]}

  `:specs` describes one reusable component per group, with `:def` the
  definition instance (the first in z-order), `:children` its children,
  `:origin` `(selection-origin [def])` and `:size` `(shape-size def)`. The
  framework renders `:children` relative to `:origin` to form the body and
  wraps it in a relative container of `:size`.

  When nothing is hoistable, returns `{:hoist-map {} :specs []}`."
  [objects roots]
  (let [reachable (reachable-shapes objects roots)
        inst-roots (filter hoistable-instance? reachable)
        groups     (d/group-by :component-id inst-roots)]
    (loop [gs        (seq groups)
           hoist-map {}
           specs     []
           used      #{}]
      (if (nil? gs)
        {:hoist-map hoist-map :specs specs}
        (let [[_cid instances] (first gs)]
          (if (>= (count instances) 2)
            (let [def-inst (first instances)
                  [comp-name used'] (unique-comp-name def-inst used)
                  hoist-map' (reduce #(assoc %1 (:id %2) comp-name)
                                     hoist-map instances)
                  spec {:comp-name comp-name
                        :def def-inst
                        :children (fc/children-of objects def-inst)
                        :origin (fc/selection-origin [def-inst])
                        :size (fc/shape-size def-inst)}
                  specs' (conj specs spec)]
              (recur (next gs) hoist-map' specs' used'))
            (recur (next gs) hoist-map specs used)))))))