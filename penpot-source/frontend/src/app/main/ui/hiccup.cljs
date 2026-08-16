;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.hiccup
  "Runtime hiccup → React element helper.

  Throughout Ovion, small inline SVG icons are authored as plain CLJS
  hiccup vectors and either stored in top-level `def`s or assembled at
  runtime by little `li`/`lucide-icon` helper functions that wrap a seq
  of path/circle children in an `<svg>` frame. Because that hiccup is
  built at RUNTIME — outside the `mf/html` macro that would compile it
  into `react/createElement` calls — a `lucide-*` / `icon-*` value is a
  CLJS PersistentVector, not a React element. Rumext passes a variable
  child such as `lucide-plus` (or the result of `(icon-x)`) straight to
  `react/createElement`; React then sees the vector is iterable (CLJS
  collections expose `Symbol.iterator`), walks INTO `[:svg {…} [:path …]]`,
  and tries to render the leading `:svg` tag KEYWORD as a child →
  Minified React error #31 (the keyword object: {ns, name, $fqn$, $hash$,
  …}). This was the Ovion blank-screen-on-file-open crash (the AI bar
  mounts on workspace load and renders these icons) and the AI-settings
  modal blank, and the same bug class is latent in every sidebar panel
  that inlines a Lucide glyph.

  `el` interprets one hiccup vector into a real React element using
  `rumext.v2.util/map->props` — the SAME prop-casing path the `:>`
  compiler uses — so the rendered SVG attributes (strokeWidth,
  strokeLinecap, viewBox, aria-hidden, className, nested style maps …)
  are byte-identical to an inline `[:> :svg …]`. It handles the
  `.class` tag shorthand, string/number/element children, and recurses
  for path|circle|rect|line|polyline children.

  Prefer wrapping a compile-time-literal icon def in `(mf/html …)`
  directly (no runtime cost); use `el` only when the children are
  assembled at runtime (the `li`/`lucide-icon`/`defn- [children]` shape)
  where `mf/html` cannot see them."
  (:require
   [clojure.string :as str]
   [goog.object :as gobj]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(defn el
  "Convert a hiccup vector `[:tag.class {:attrs …} & children]` into a
  real React element. Strings/numbers pass through; non-vector values
  pass through unchanged (so an already-built element or nil is fine)."
  [node]
  (cond
    (or (string? node) (number? node))
    node

    (vector? node)
    (let [head     (first node)
          tag-name (name head)
          dot      (str/index-of tag-name ".")
          class?   (and (some? dot) (pos? dot))
          cls      (when class? (str/replace (subs tag-name (inc dot)) "." " "))
          tag      (if class? (subs tag-name 0 dot) tag-name)
          after    (next node)
          attrs    (if (map? (first after)) (first after) {})
          kids     (if (map? (first after)) (next after) after)
          props    (mfu/map->props attrs)]
      (when (some? cls)
        (gobj/set props "className" cls))
      (if (seq kids)
        (apply mf/create-element tag props (map el kids))
        (mf/create-element tag props)))

    :else
    node))