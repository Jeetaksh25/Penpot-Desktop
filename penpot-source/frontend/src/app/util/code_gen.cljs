;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen
  (:require
   [app.common.data.macros :as dm]
   [app.util.code-beautify :as cb]
   [app.util.code-gen.frameworks.android-xml :as android-xml]
   [app.util.code-gen.frameworks.flutter :as flutter]
   [app.util.code-gen.frameworks.react :as react]
   [app.util.code-gen.frameworks.react-native :as react-native]
   [app.util.code-gen.frameworks.tailwind :as tailwind]
   [app.util.code-gen.frameworks.winui3-xml :as winui3-xml]
   [app.util.code-gen.markup-html :as html]
   [app.util.code-gen.markup-svg :as svg]
   [app.util.code-gen.style-css :as css]
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]))

;; ---------------------------------------------------------------------------
;; Classic HTML / SVG markup + CSS style generation (Inspect "Code" panel)
;; ---------------------------------------------------------------------------

(defn generate-markup-code
  [objects type shapes]
  (let [generate-markup
        (case type
          "html" html/generate-markup
          "svg"  svg/generate-markup)]
    (generate-markup objects shapes)))

(defn generate-formatted-markup-code
  [objects type shapes]
  (let [markup (generate-markup-code objects type shapes)]
    (cb/format-code markup type)))

(defn generate-style-code
  ([objects type root-shapes all-shapes]
   (generate-style-code objects type root-shapes all-shapes nil))
  ([objects type root-shapes all-shapes options]
   (let [generate-style
         (case type
           "css" css/generate-style)]
     (generate-style objects root-shapes all-shapes options))))

(defn prelude
  [type]
  (case type
    "css" css/prelude))

;; ---------------------------------------------------------------------------
;; UI-framework code generation (React, Next.js, React Native, Android XML,
;; WinUI 3 XAML, Flutter, Tailwind CSS). Each produces a single self-contained
;; source file.
;; ---------------------------------------------------------------------------

(def framework-types
  "Set of markup-type strings that are handled as UI-framework code export."
  #{"react" "nextjs" "react-native" "android-xml" "winui3-xml" "flutter" "tailwind"})

(defn framework?
  "True when `type` is one of the UI-framework code export targets (i.e. not
  plain html/svg markup)."
  [type]
  (contains? framework-types (str type)))

(def framework-meta
  "Metadata per framework type: file extension, MIME type and a friendly
  label. The MIME type is intentionally one `mtype->extension` does NOT
  know, so `dom/trigger-download-uri` leaves the supplied extension intact."
  {"react"        {:extension "jsx"  :mtype "text/jsx"          :label "React"}
   "nextjs"       {:extension "jsx"  :mtype "text/jsx"          :label "Next.js"}
   "react-native" {:extension "jsx"  :mtype "text/jsx"          :label "React Native"}
   "android-xml"  {:extension "xml"  :mtype "application/xml"    :label "Android XML"}
   "winui3-xml"   {:extension "xaml" :mtype "application/xaml+xml" :label "WinUI 3 XAML"}
   "flutter"      {:extension "dart" :mtype "text/x-dart"       :label "Flutter"}
   "tailwind"     {:extension "jsx"  :mtype "text/jsx"          :label "Tailwind CSS"}})

(defn framework-extension [type] (:extension (framework-meta (str type)) "txt"))
(defn framework-mtype [type] (:mtype (framework-meta (str type)) "text/plain"))
(defn framework-label [type] (:label (framework-meta (str type)) "Code"))

(defn generate-framework-code
  "Generate the source code for a UI-framework export. Returns a string."
  [objects type shapes]
  (let [type (str type)]
    (case type
      "react"        (react/generate objects shapes)
      "nextjs"       (tailwind/generate-nextjs objects shapes)
      "react-native" (react-native/generate objects shapes)
      "android-xml"  (android-xml/generate objects shapes)
      "winui3-xml"   (winui3-xml/generate objects shapes)
      "flutter"      (flutter/generate objects shapes)
      "tailwind"     (tailwind/generate objects shapes)
      "")))

(defn download-framework-code!
  "Trigger a browser download of generated framework code. `base-name` is
  used (sanitized) for the file name; the framework's extension is appended."
  [base-name type code]
  (let [type      (str type)
        extension (framework-extension type)
        mtype     (framework-mtype type)
        safe-name (-> (or base-name "export")
                      (str/replace #"[^a-zA-Z0-9\-_]+" "-")
                      (str/replace #"-+" "-")
                      (str/replace #"(^-+)|(-+$)" ""))
        filename  (dm/str (if (str/blank? safe-name) "export" safe-name)
                          "." extension)
        blob     (wapi/create-blob code mtype)
        uri      (wapi/create-uri blob)]
    ;; Bypass dom/trigger-download-uri so the exact extension is kept
    ;; (its mtype->extension lookup would otherwise append the wrong one).
    (let [link (dom/create-element "a")]
      (dom/set-attribute! link "href" uri)
      (dom/set-attribute! link "download" filename)
      (set! (.-style ^js link) "display:none")
      (.appendChild (.-body ^js js/document) link)
      (.click link)
      (.remove link))
    (js/queueMicrotask #(wapi/revoke-uri uri))))