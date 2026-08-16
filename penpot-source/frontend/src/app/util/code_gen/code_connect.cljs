;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.code-connect
  "Figma-parity Code Connect — template-body emission (gap #40, P1.08).

  A main component may carry a `:code-connect` map keyed by framework id
  (\"react\", \"nextjs\", \"react-native\", \"android-xml\", \"winui3-xml\",
  \"flutter\", \"tailwind\", \"swift\"). Each entry is an authored string.

  This namespace interprets an entry as a Code Connect BINDING when it is a
  JSON object of the form:

      {\"tag\": \"Button\", \"props\": {\"variant\": \"primary\", \"size\": 42}}

  `parse-binding` returns `{:tag \"Button\" :props {\"variant\" \"primary\" ...}}`
  for such an entry, or nil for a plain free-text template (which the Inspect
  panel still surfaces as a read-only banner — see `code.cljs`).

  The per-framework emitters in `app.util.code-gen.frameworks.*` call
  `binding-for` to resolve a binding for a shape on the current framework,
  and the `format-props-*` helpers to render the authored props in the
  framework's native syntax (JSX attributes, Dart named args, Swift args,
  XML attributes, XAML attributes). Positioning is left to each framework
  (it reuses its own box-style / Positioned / .frame+.position helpers) so a
  mapped component stays laid out exactly like the design.

  Resolution works purely from the page `objects` map — the same lookup the
  Inspect-panel display banner uses (`cg/component-code-connect-template`):
  the component definition backing an instance is read as
  `(get objects (:component-id shape))` and its `:code-connect` field is
  indexed by framework id. When no binding exists the emitter falls through
  to the generic markup path, so the output is byte-identical to the
  pre-Code-Connect behavior for components without a binding."
  (:require
   [app.common.data.macros :as dm]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Binding resolution + parsing
;; ---------------------------------------------------------------------------

(defn component-code-connect
  "Return the raw `:code-connect` map authored on the component definition
  backing `shape` (looked up in `objects` by `:component-id`), or nil. This
  mirrors `app.util.code-gen/component-code-connect-template`'s lookup so
  the emitter and the Inspect-panel banner stay consistent."
  [objects shape]
  (when-let [component-id (:component-id shape)]
    (when-let [component (get objects component-id)]
      (:code-connect component))))

(defn template-for
  "The raw authored string for `type` on `shape`'s component, or nil."
  [objects type shape]
  (when-let [cc (component-code-connect objects shape)]
    (get cc (str type))))

(defn parse-binding
  "Interpret an authored Code Connect entry as a tag+props binding. Returns
  `{:tag <str> :props <str-keyed map>}` when `template-str` is a JSON object
  with a string `tag` (and an optional object `props`), otherwise nil — a
  nil result means the entry is a free-text template shown only in the
  Inspect-panel banner, and the emitters fall back to generic markup."
  [template-str]
  (try
    (let [s (str/trim (str template-str))]
      (when (and (str/starts-with? s "{") (str/ends-with? s "}"))
        (let [obj (js/JSON.parse s)]
          (when (and (object? obj)
                     (not (array? obj))
                     (string? (.-tag obj))
                     (not (str/blank? (str/trim (.-tag obj)))))
            (let [raw-props (.-props obj)
                  props (if (and (some? raw-props)
                                (object? raw-props)
                                (not (array? raw-props)))
                          (js->clj raw-props :keywordize-keys false)
                          {})]
              {:tag (str/trim (.-tag obj))
               :props props})))))
    (catch :default _ nil)))

(defn binding-for
  "Resolve the Code Connect binding for `type` on `shape` from `objects`.
  Returns `{:tag :props}` or nil (no binding → emitter falls back)."
  [objects type shape]
  (some-> (template-for objects type shape) parse-binding))

;; ---------------------------------------------------------------------------
;; Prop value formatting (per-framework syntax)
;; ---------------------------------------------------------------------------

;; The authored prop values come from a JSON config, so they are JSON
;; primitives (string / number / boolean / null) or plain objects / arrays.
;; Each formatter renders them in its framework's native attribute syntax.

(defn- jsx-attr [k v]
  (let [kn (name k)]
    (cond
      (string? v)          (dm/str kn "=" (js/JSON.stringify (str v)))
      (boolean? v)         (dm/str kn "={" (if v "true" "false") "}")
      (number? v)          (dm/str kn "={" v "}")
      (nil? v)             (dm/str kn "={null}")
      (or (array? v) (object? v)) (dm/str kn "={" (js/JSON.stringify v) "}")
      :else                (dm/str kn "=" (js/JSON.stringify (str v))))))

(defn format-props-jsx
  "Render `binding`'s props as a space-separated JSX attribute string
  (e.g. `variant=\"primary\" size={42} disabled={true}`). Empty when the
  binding has no props."
  [binding]
  (let [props (:props binding)]
    (->> (seq props)
         (map (fn [[k v]] (jsx-attr k v)))
         (str/join " "))))

(defn- dart-string [s]
  (dm/str "'" (-> (str s)
                  (str/replace "\\" "\\\\")
                  (str/replace "'" "\\'")) "'"))

(defn- dart-attr [k v]
  (let [kn (name k)]
    (cond
      (string? v)          (dm/str kn ": " (dart-string v))
      (boolean? v)         (dm/str kn ": " (if v "true" "false"))
      (number? v)          (dm/str kn ": " v)
      (nil? v)             (dm/str kn ": null")
      :else                (dm/str kn ": " (dart-string (js/JSON.stringify v))))))

(defn format-props-dart
  "Render `binding`'s props as a comma-separated Dart named-argument body
  (e.g. `variant: 'primary', size: 42`). Empty when no props."
  [binding]
  (let [props (:props binding)]
    (->> (seq props)
         (map (fn [[k v]] (dart-attr k v)))
         (str/join ", "))))

(defn- swift-string [s]
  (dm/str "\"" (-> (str s)
                   (str/replace "\\" "\\\\")
                   (str/replace "\"" "\\\"")) "\""))

(defn- swift-attr [k v]
  (let [kn (name k)]
    (cond
      (string? v)          (dm/str kn ": " (swift-string v))
      (boolean? v)         (dm/str kn ": " (if v "true" "false"))
      (number? v)          (dm/str kn ": " v)
      (nil? v)             (dm/str kn ": nil")
      :else                (dm/str kn ": " (swift-string (js/JSON.stringify v))))))

(defn format-props-swift
  "Render `binding`'s props as a comma-separated Swift argument body
  (e.g. `variant: \"primary\", size: 42`). Empty when no props."
  [binding]
  (let [props (:props binding)]
    (->> (seq props)
         (map (fn [[k v]] (swift-attr k v)))
         (str/join ", "))))

(defn- xml-escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- xml-attr [k v]
  (let [kn (name k)]
    (cond
      (string? v)          (dm/str kn "=" "\"" (xml-escape v) "\"")
      (boolean? v)         (dm/str kn "=\"" (if v "true" "false") "\"")
      (number? v)          (dm/str kn "=\"" v "\"")
      (nil? v)             (dm/str kn "=\"\"")
      :else                (dm/str kn "=\"" (xml-escape (js/JSON.stringify v)) "\""))))

(defn format-props-xml
  "Render `binding`'s props as a space-separated XML attribute string
  (e.g. `variant=\"primary\" size=\"42\"`). Empty when no props."
  [binding]
  (let [props (:props binding)]
    (->> (seq props)
         (map (fn [[k v]] (xml-attr k v)))
         (str/join " "))))

(defn- kotlin-string [s]
  (dm/str "\"" (-> (str s)
                   (str/replace "\\" "\\\\")
                   (str/replace "\"" "\\\"")
                   (str/replace "\r" "")
                   (str/replace "\n" "\\n")) "\""))

(defn- kotlin-attr [k v]
  (let [kn (name k)]
    (cond
      (string? v)          (dm/str kn " = " (kotlin-string v))
      (boolean? v)         (dm/str kn " = " (if v "true" "false"))
      (number? v)          (dm/str kn " = " v)
      (nil? v)             (dm/str kn " = null")
      :else                (dm/str kn " = " (kotlin-string (js/JSON.stringify v))))))

(defn format-props-kotlin
  "Render `binding`'s props as a comma-separated Kotlin named-argument body
  (e.g. `variant = \"primary\", size = 42`) suitable for a `@Composable`
  call. Empty when the binding has no props."
  [binding]
  (let [props (:props binding)]
    (->> (seq props)
         (map (fn [[k v]] (kotlin-attr k v)))
         (str/join ", "))))

(defn- pascal-key [s]
  (let [s (str s)]
    (if (str/blank? s) s
        (dm/str (str/upper (subs s 0 1)) (subs s 1)))))

(defn- xaml-attr [k v]
  (let [kn (pascal-key (name k))]
    (cond
      (string? v)          (dm/str kn "=\"" (xml-escape v) "\"")
      (boolean? v)         (dm/str kn "=\"" (if v "true" "false") "\"")
      (number? v)          (dm/str kn "=\"" v "\"")
      (nil? v)             (dm/str kn "=\"\"")
      :else                (dm/str kn "=\"" (xml-escape (js/JSON.stringify v)) "\""))))

(defn format-props-xaml
  "Render `binding`'s props as a space-separated XAML attribute string with
  PascalCased keys (e.g. `Variant=\"primary\" Size=\"42\"`). Empty when no
  props."
  [binding]
  (let [props (:props binding)]
    (->> (seq props)
         (map (fn [[k v]] (xaml-attr k v)))
         (str/join " "))))