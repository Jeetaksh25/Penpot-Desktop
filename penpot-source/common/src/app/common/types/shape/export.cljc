;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.shape.export
  (:require
   [app.common.schema :as sm]))

(def types
  "Export target types. The first five are rendered asset formats handled
  by the backend :export RPC. The remainder are UI-framework code exports
  (React, Next.js, React Native, Android XML, WinUI 3 XAML, Flutter)
  which are generated entirely on the client and never reach the backend;
  they are included here so a shape's :exports spec validates when a code
  format is configured."
  #{:png :jpeg :webp :svg :pdf
    :react :nextjs :react-native :android-xml :winui3-xml :flutter})

(def schema:export
  [:map {:title "ShapeExport"}
   [:type [::sm/one-of types]]
   [:scale ::sm/safe-number]
   [:suffix :string]])
