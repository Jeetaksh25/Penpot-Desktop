;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.shapes.code-component
  "P0.14 — Code-component canvas render (ALL_APPS_PARITY). Emits an SVG
  `<foreignObject>` wrapping a sandboxed `<iframe>` that loads the
  registered component bundle. Props are delivered to the bundle via the
  iframe src URL fragment:

    #ovion-props=<base64-of-pr-str-props>

  so the loaded bundle reads them from `location.hash`. The foreignObject
  is sized to the shape's bounding box (x/y/width/height + transform),
  mirroring ui.shapes.video.

  Nil-safe: when the shape has no slot, or the registry id is missing, or
  the bundle-url is absent, a coral placeholder rect with the component
  name + 'missing bundle' is rendered instead (never crashes).

  Byte-identical-when-inactive: this component only mounts when the
  carrier shape's renderer detects a code-component slot (see
  ui.shapes.rect). A shape without the slot never reaches this code, so
  its SVG is byte-identical to today.

  Reduced-motion: the iframe renders regardless (it is content, not
  motion); no entrance animation is applied under any motion preference."
  (:require
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.code-components :as dcc]
   [app.main.refs :as refs]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private coral "#f28b82")
(def ^:private grey "#7d7d7d")

(defn- base64-encode-utf8
  "Base64-encode a UTF-8 string safely (handles non-ASCII by routing
  through encodeURIComponent/unescape before js/btoa)."
  [s]
  (if (nil? s)
    ""
    (try
      (js/btoa (js/unescape (js/encodeURIComponent (str s))))
      (catch :default _
        ;; Fallback: plain btoa on the raw string (may fail on non-ASCII
        ;; but is correct for ASCII-only props).
        (try (js/btoa (str s)) (catch :default _ ""))))))

(defn- props-to-fragment
  "Build the URL fragment `#ovion-props=<base64>` for a props map."
  [props]
  (let [payload (if (or (nil? props) (empty? props)) {} props)]
    (str "#ovion-props=" (base64-encode-utf8 (pr-str payload)))))

(defn- append-fragment
  "Append a URL fragment to `url`, replacing any existing fragment."
  [url fragment]
  (if (or (nil? url) (empty? url))
    url
    (let [s     (str url)
          ;; strip an existing hash
          base  (or (first (str/split s "#")) s)]
      (str base fragment))))

(mf/defc code-component-foreign-object*
  "Render a `<foreignObject>` wrapping a sandboxed `<iframe>` for the
  carrier `shape`. Reads the code-component slot; when absent or the
  registry id / bundle-url is missing, renders a coral placeholder rect
  with the component name + 'missing bundle' label.

  Props are passed to the bundle via the iframe src URL fragment
  `#ovion-props=<base64-of-pr-str-props>`.

  Reduced-motion: the iframe always renders (it is content). No entrance
  animation is applied. The reduced-motion guard is honored trivially
  since no motion is emitted."
  {::mf/wrap-props false}
  [props]
  (let [shape     (unchecked-get props "shape")
        ;; file-data is optional — when not passed by the caller we read
        ;; it from the workspace file-data ref (reactive). Only rects
        ;; with a slot mount this component, so only they subscribe.
        file-data (or (unchecked-get props "file-data")
                      (mf/deref refs/workspace-data))
        slot      (dcc/read-slot shape)
        reg-id    (when (map? slot) (:id slot))
        props-map (when (map? slot) (or (:props slot) {}))
        bundle    (when (some? reg-id) (dcc/bundle-url-for reg-id file-data))]

    (let [x        (dm/get-prop shape :x)
          y        (dm/get-prop shape :y)
          w        (dm/get-prop shape :width)
          h        (dm/get-prop shape :height)
          transform (gsh/transform-str shape)

          fo-props (-> (obj/create)
                       (obj/set! "x" x)
                       (obj/set! "y" y)
                       (obj/set! "width" w)
                       (obj/set! "height" h)
                       (obj/set! "transform" transform)
                       (obj/set! "style" #js {:pointerEvents "none"}))]

      (if (and (some? bundle) (not (empty? bundle)))
        ;; Happy path: render the sandboxed iframe with props in the URL fragment.
        (let [src      (append-fragment bundle (props-to-fragment props-map))
              iframe-props (-> (obj/create)
                               (obj/set! "src" src)
                               (obj/set! "sandbox" "allow-scripts")
                               (obj/set! "scrolling" "no")
                               (obj/set! "title" (str reg-id))
                               (obj/set! "style" #js {:width "100%"
                                                      :height "100%"
                                                      :border "none"
                                                      :display "block"
                                                      :pointerEvents "none"
                                                      :overflow "hidden"}))]
          [:> :foreignObject fo-props
           [:> :iframe iframe-props]])

        ;; Missing bundle / missing registry id: coral placeholder rect.
        ;; Never crashes — renders an HTML div with the component name.
        (let [name (if (some? reg-id)
                     (let [registry (dcc/read-registry file-data)]
                       (if (and (map? registry) (contains? registry reg-id))
                         (or (dm/get-in registry [reg-id :name]) (name reg-id))
                         (name reg-id)))
                     "component")]
          [:> :foreignObject fo-props
           [:> :div #js {:style #js {:width "100%"
                                     :height "100%"
                                     :display "flex"
                                     :flexDirection "column"
                                     :alignItems "center"
                                     :justifyContent "center"
                                     :gap "4px"
                                     :backgroundColor "rgba(242,139,130,0.10)"
                                     :border (str "1px solid " coral)
                                     :borderRadius "4px"
                                     :color coral
                                     :fontFamily "Helvetica, Arial, sans-serif"
                                     :fontSize "11px"
                                     :fontWeight "500"
                                     :textAlign "center"
                                     :padding "6px"
                                     :boxSizing "border-box"
                                     :overflow "hidden"}}
            [:> :span #js {:style #js {:color coral :fontWeight "600"}} name]
            [:> :span #js {:style #js {:color grey :fontSize "10px" :fontWeight "400"}}
             "missing bundle"]]])))))