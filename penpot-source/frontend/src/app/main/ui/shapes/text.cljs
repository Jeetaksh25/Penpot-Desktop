;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.text
  (:require
   [app.common.types.text :as txt]
   [app.main.data.workspace.localization :as loc]
   [app.main.fonts :as fonts]
   [app.main.ui.context :as ctx]
   [app.main.ui.shapes.text.fo-text :as fo]
   [app.main.ui.shapes.text.svg-text :as svg]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn- load-fonts!
  [content]
  (let [extract-fn (juxt :font-id :font-variant-id)
        default    (extract-fn txt/default-typography)]
    (->> (tree-seq map? :children content)
         (into #{default} (keep extract-fn))
         (run! (fn [[font-id variant-id]]
                 (when (some? font-id)
                   (fonts/ensure-loaded! font-id variant-id)))))))

(mf/defc text-shape
  {::mf/wrap-props false}
  [props]
  (let [raw-shape     (obj/get props "shape")
        ;; P2.25 — Localization. Subscribe to the canvas-wide active-locale
        ;; (nil when no locale has been switched = default content). The
        ;; derived ref only emits when `:active-locale` actually changes, so
        ;; unrelated state changes do NOT re-render text shapes.
        active-locale (mf/deref loc/active-locale-ref)
        loc-shape     (when (some? active-locale)
                        (loc/localized-shape raw-shape active-locale :en))
        ;; When localization applies, pass a cloned props carrying the
        ;; locale-substituted shape; otherwise pass the original props
        ;; unchanged (byte-identical render — same object).
        props         (if (some? loc-shape)
                        (obj/set! (obj/clone props) "shape" loc-shape)
                        props)
        shape         (or loc-shape raw-shape)
        {:keys [position-data content]} shape
        is-render?    (mf/use-ctx ctx/is-render?)
        is-component? (mf/use-ctx ctx/is-component?)]

    (mf/with-memo [content]
      (load-fonts! content))

    ;; Old components can have texts without position data that must be rendered via foreign key
    (cond
      (some? position-data)
      [:> svg/text-shape props]

      ;; Only use this for component preview, otherwise the dashboard thumbnails
      ;; will give a tainted canvas error because the `foreignObject` cannot be
      ;; rendered.
      (and (nil? position-data) (or is-component? is-render?))
      [:> fo/text-shape* props])))
