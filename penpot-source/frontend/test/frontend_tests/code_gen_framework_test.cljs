;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.code-gen-framework-test
  "Golden tests for Feature 2 code export (the UI-framework generators).

  Two layers are exercised:

    * `generate-framework-code` — the single-string Inspect panel preview
      path. Asserts each framework emits a non-empty, recognizable snippet.
    * `generate-framework-project` — the multi-file ZIP scaffold. Asserts
      each framework's `:files` key set, `:primary` path, `:label` and that
      no framework emits `:raster-requests` yet (the native-SVG / PNG
      raster phase is the next round), plus the `:fontfaces-css` opt-in
      behavior of the web frameworks.

  The fixture is intentionally simple (a frame + a rect) so the text /
  image / SVG branches — which pull in the live shape renderers — are not
  required to run here; the file-tree contract is what these tests lock."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.types.path :as path]
   [app.common.uuid :as uuid]
   [app.util.code-gen :as cg]
   [app.util.code-gen.frameworks.common :as fc]
   [app.util.code-gen.frameworks.components :as fcomp]
   [cljs.test :refer [deftest is testing] :include-macros true]
   [cuerdas.core :as str]))

;; --- Builders ------------------------------------------------------------

(defn- pts
  [x y w h]
  [(gpt/point x y)
   (gpt/point (+ x w) y)
   (gpt/point (+ x w) (+ y h))
   (gpt/point x (+ y h))])

(defn- frame
  [id & {:as extra}]
  (merge {:id id :name "Board" :type :frame
          :parent-id uuid/zero :frame-id uuid/zero
          :selrect (grc/make-rect 0 0 200 200)
          :points (pts 0 0 200 200)}
         extra))

(defn- rect
  [id parent-id & {:as extra}]
  (merge {:id id :name "Card" :type :rect :parent-id parent-id
          :selrect (grc/make-rect 10 10 50 50)
          :points (pts 10 10 50 50)
          :transform (gmt/matrix)}
         extra))

(defn- objects [& shapes]
  (into {} (map (juxt :id identity)) shapes))

(defn- fixture
  "A board holding one rect; the standard selection for these tests."
  []
  (let [pid (uuid/next)
        cid (uuid/next)]
    {:board (frame pid :shapes [cid])
     :rect  (rect cid pid)
     :objects (objects (frame pid :shapes [cid]) (rect cid pid))
     :shapes [{:id pid}]}))

;; --- Single-string preview (generate-framework-code) --------------------

(deftest framework-code-non-empty
  (testing "each framework emits a non-empty, recognizable component snippet"
    (let [{:keys [objects shapes]} (fixture)]
      (doseq [type ["react" "nextjs" "react-native" "android-xml"
                    "winui3-xml" "flutter" "tailwind"]]
        (let [code (cg/generate-framework-code objects type shapes)]
          (is (string? code) (str type " returns a string"))
          (is (not (str/blank? code)) (str type " code is non-empty")))))))

(deftest react-code-shape
  (testing "the React snippet uses absolute positioning and a default export"
    (let [{:keys [objects shapes]} (fixture)
          code (cg/generate-framework-code objects "react" shapes)]
      (is (str/includes? code "export default function"))
      (is (str/includes? code "position: \"relative\"")))))

(deftest tailwind-code-shape
  (testing "the Tailwind snippet emits className utility classes"
    (let [{:keys [objects shapes]} (fixture)
          code (cg/generate-framework-code objects "tailwind" shapes)]
      (is (str/includes? code "className="))
      (is (str/includes? code "relative")))))

(deftest android-code-is-xml
  (testing "the Android snippet is a FrameLayout XML layout"
    (let [{:keys [objects shapes]} (fixture)
          code (cg/generate-framework-code objects "android-xml" shapes)]
      (is (str/includes? code "<?xml"))
      (is (str/includes? code "FrameLayout")))))

(deftest flutter-code-is-widget
  (testing "the Flutter snippet is a StatelessWidget"
    (let [{:keys [objects shapes]} (fixture)
          code (cg/generate-framework-code objects "flutter" shapes)]
      (is (str/includes? code "StatelessWidget"))
      (is (str/includes? code "Stack")))))

(deftest winui-code-is-xaml-canvas
  (testing "the WinUI 3 snippet is a Canvas XAML fragment"
    (let [{:keys [objects shapes]} (fixture)
          code (cg/generate-framework-code objects "winui3-xml" shapes)]
      (is (str/includes? code "Canvas")))))

;; --- Multi-file project (generate-framework-project) ---------------------

(defn- project
  [type & [opts]]
  (let [{:keys [objects shapes]} (fixture)]
    (cg/generate-framework-project objects type shapes opts)))

(defn- file-keys [proj]
  (set (keys (:files proj))))

(deftest react-project-tree
  (testing "React (Vite) scaffold: component + entry + index.html + configs"
    (let [p (project "react")]
      (is (= "src/Board.jsx" (:primary p)))
      (is (= "React" (:label p)))
      (is (= #{"src/Board.jsx" "src/main.jsx" "index.html" "vite.config.js"
                "package.json" "README.md"}
             (file-keys p)))
      (is (empty? (:raster-requests p)))
      (is (false? (:uses-rn-svg? p))))))

(deftest nextjs-project-tree
  (testing "Next.js (App Router) + Tailwind scaffold"
    (let [p (project "nextjs")]
      (is (= "app/page.jsx" (:primary p)))
      (is (= "Next.js" (:label p)))
      (is (= #{"app/page.jsx" "app/layout.jsx" "app/globals.css" "package.json"
                "tailwind.config.js" "postcss.config.js" "next.config.mjs"
                ".gitignore" "README.md"}
             (file-keys p)))
      ;; globals.css is always present (it carries the @tailwind directives).
      (is (str/includes? (get-in p [:files "app/globals.css"]) "@tailwind base"))
      (is (empty? (:raster-requests p))))))

(deftest tailwind-project-tree
  (testing "Tailwind (Vite) scaffold"
    (let [p (project "tailwind")]
      (is (= "src/Board.jsx" (:primary p)))
      (is (= "Tailwind CSS" (:label p)))
      (is (= #{"src/Board.jsx" "src/main.jsx" "src/index.css" "index.html"
                "vite.config.js" "tailwind.config.js" "postcss.config.js"
                "package.json" "README.md"}
             (file-keys p)))
      (is (str/includes? (get-in p [:files "src/index.css"]) "@tailwind base")))))

(deftest react-native-project-tree
  (testing "React Native scaffold named after the component (Board.jsx)"
    (let [p (project "react-native")]
      (is (= "Board.jsx" (:primary p)))
      (is (= "React Native" (:label p)))
      (is (= #{"Board.jsx" "package.json" "app.json" "babel.config.js"
                "README.md"}
             (file-keys p)))
      ;; No SVG shapes in the fixture → the react-native-svg dep is omitted.
      (is (false? (:uses-rn-svg? p)))
      (is (not (str/includes? (get-in p [:files "package.json"]) "react-native-svg"))))))

(deftest android-project-tree
  (testing "Android res/ tree + manifest + build.gradle"
    (let [p (project "android-xml")]
      (is (= "res/layout/board.xml" (:primary p)))
      (is (= "Android XML" (:label p)))
      (is (= #{"res/layout/board.xml" "res/values/colors.xml"
                "res/values/strings.xml" "res/values/dimens.xml"
                "res/values/styles.xml" "AndroidManifest.xml" "build.gradle"
                "README.md"}
             (file-keys p)))))

  (testing "the layout XML references the PenpotExport theme"
    (let [p (project "android-xml")]
      (is (str/includes? (get-in p [:files "AndroidManifest.xml"]) "Theme.PenpotExport")))))

(deftest flutter-project-tree
  (testing "Flutter lib/ + pubspec (flutter_svg) scaffold"
    (let [p (project "flutter")]
      (is (= "lib/board.dart" (:primary p)))
      (is (= "Flutter" (:label p)))
      (is (= #{"lib/board.dart" "pubspec.yaml" "analysis_options.yaml"
                "README.md"}
             (file-keys p)))
      (is (str/includes? (get-in p [:files "pubspec.yaml"]) "flutter_svg")))))

(deftest winui-project-tree
  (testing "WinUI 3 Page + code-behind scaffold"
    (let [p (project "winui3-xml")]
      (is (= "Board.xaml" (:primary p)))
      (is (= "WinUI 3 XAML" (:label p)))
      (is (= #{"Board.xaml" "Board.xaml.cs" "README.md"} (file-keys p)))
      (is (str/includes? (get-in p [:files "Board.xaml"]) "<Page"))
      (is (str/includes? (get-in p [:files "Board.xaml.cs"]) "partial class Board")))))

;; --- @font-face opt-in (web frameworks) ----------------------------------

(deftest react-fontface-css-opt-in
  (testing "React only ships src/index.css (and imports it) when :fontfaces-css is given"
    (let [with-font (project "react" {:fontfaces-css "@font-face{font-family:Test;src:url(/x.woff2)}"})
          without   (project "react")]
      (is (contains? (file-keys with-font) "src/index.css"))
      (is (not (contains? (file-keys without) "src/index.css")))
      (is (str/includes? (get-in with-font [:files "src/main.jsx"]) "index.css"))
      (is (not (str/includes? (get-in without [:files "src/main.jsx"]) "index.css"))))))

(deftest nextjs-fontface-appended-to-globals
  (testing "Next.js appends the @font-face block to app/globals.css"
    (let [p (project "nextjs" {:fontfaces-css "@font-face{font-family:Test}"})]
      (is (str/includes? (get-in p [:files "app/globals.css"]) "@font-face{font-family:Test}")))))

(deftest tailwind-fontface-appended-to-index-css
  (testing "Tailwind appends the @font-face block to src/index.css"
    (let [p (project "tailwind" {:fontfaces-css "@font-face{font-family:Test}"})]
      (is (str/includes? (get-in p [:files "src/index.css"]) "@font-face{font-family:Test}")))))

;; --- Non-web frameworks ignore :fontfaces-css ----------------------------

(deftest non-web-frameworks-ignore-fontface-css
  (testing "RN/Android/Flutter/WinUI ignore :fontfaces-css (platform font loading)"
    (doseq [type ["react-native" "android-xml" "flutter" "winui3-xml"]]
      (let [p (project type {:fontfaces-css "@font-face{font-family:Test}"})
            body (str/join "\n" (vals (:files p)))]
        (is (not (str/includes? body "@font-face"))
            (str type " must not embed @font-face CSS"))))))

;; --- Empty selection degrades gracefully ---------------------------------

(deftest empty-selection-still-scaffolds
  (testing "an empty selection still produces a full scaffold with a default name"
    (let [p (cg/generate-framework-project (objects) "react" [])]
      (is (= "src/Component.jsx" (:primary p)))
      (is (contains? (file-keys p) "package.json")))))

(deftest unknown-framework-falls-back-to-empty-project
  (testing "an unknown framework type returns the blank project map"
    (let [p (cg/generate-framework-project (objects) "nope" [])]
      (is (empty? (:files p)))
      (is (nil? (:primary p)))
      (is (= "Code" (:label p))))))

;; ---------------------------------------------------------------------------
;; Phase C — native SVG / PNG raster (Android VectorDrawable + raster-requests)
;; Phase E — component-instance hoisting
;;
;; These tests use pure-data fixtures (paths built via path/from-plain,
;; bool / component-instance shapes) so they NEVER invoke the live React
;; SVG renderer (markup-svg/generate-svg) that the RN/Flutter svg-shape
;; branches use — that renderer is not safe in the unit-test environment.
;; ---------------------------------------------------------------------------

(defn- tri-segments
  "A simple closed triangle path in local coordinates."
  []
  [{:command :move-to  :params {:x 0  :y 0}}
   {:command :line-to  :params {:x 50 :y 0}}
   {:command :line-to  :params {:x 50 :y 50}}
   {:command :line-to  :params {:x 0  :y 50}}
   {:command :close-path}])

(defn- path-shape
  [id & {:as extra}]
  (merge {:id id :name "Tri" :type :path :parent-id uuid/zero :frame-id uuid/zero
          :selrect (grc/make-rect 10 10 50 50) :points (pts 10 10 50 50)
          :transform (gmt/matrix)
          :fills [{:fill-color "#FF0000" :fill-opacity 1}]
          :strokes []
          :content (path/from-plain (tri-segments))}
         extra))

(defn- bool-shape
  [id & {:as extra}]
  (merge {:id id :name "Union" :type :bool :parent-id uuid/zero :frame-id uuid/zero
          :selrect (grc/make-rect 10 10 60 60) :points (pts 10 10 60 60)
          :shapes []}
         extra))

(defn- instance
  "A hoistable component-instance head named Button, owning `child-ids`."
  [id comp-id child-ids & {:as extra}]
  (merge {:id id :name "Button" :type :frame :parent-id uuid/zero :frame-id uuid/zero
          :component-id comp-id :component-root true :component-file nil
          :touched #{} :is-variant-container false :main-instance false
          :selrect (grc/make-rect 0 0 40 20) :points (pts 0 0 40 20)
          :shapes child-ids}
         extra))

;; --- simple-svg? predicate ------------------------------------------------

(deftest simple-svg-predicate
  (testing "a lone path with a single solid fill at identity transform is simple"
    (is (true? (fc/simple-svg? (path-shape (uuid/next))))))
  (testing "a gradient fill, svg-attrs, a non-path shape, or a transform is NOT simple"
    (is (false? (fc/simple-svg?
                  (path-shape (uuid/next)
                              :fills [{:fill-color-gradient {:type :linear :stops []}}]))))
    (is (false? (fc/simple-svg? (path-shape (uuid/next) :svg-attrs {:foo :bar}))))
    ;; a bool shape is an svg-shape but never simple-svg? (not a :path).
    (is (false? (fc/simple-svg? (bool-shape (uuid/next)))))
    ;; a rotated path has a non-identity transform → not simple.
    (let [r (assoc (gmt/matrix) :a 0.5 :b 0.866 :c -0.866 :d 0.5)]
      (is (false? (fc/simple-svg? (path-shape (uuid/next) :transform r)))))))

;; --- Android VectorDrawable + raster-request -------------------------------

(deftest android-vector-drawable-for-simple-svg
  (testing "a simple path becomes a res/drawable/<name>.xml VectorDrawable, no raster"
    (let [pid (uuid/next)
          objs (objects (path-shape pid))
          p (cg/generate-framework-project objs "android-xml" [{:id pid}])
          drawable "res/drawable/tri.xml"]
      (is (contains? (file-keys p) drawable))
      (is (str/includes? (get-in p [:files drawable]) "<vector"))
      (is (str/includes? (get-in p [:files drawable]) "android:pathData="))
      (is (str/includes? (get-in p [:files (:primary p)]) "@drawable/tri"))
      (is (empty? (:raster-requests p))))))

(deftest android-raster-request-for-complex-svg
  (testing "a complex svg-shape (bool) records a :raster-request, no VectorDrawable"
    (let [bid (uuid/next)
          objs (objects (bool-shape bid))
          p (cg/generate-framework-project objs "android-xml" [{:id bid}])]
      (is (= 1 (count (:raster-requests p))))
      (is (= "res/drawable/union.png"
             (:binary-path (first (:raster-requests p)))))
      (is (str/includes? (get-in p [:files (:primary p)]) "@drawable/union"))
      (is (not (contains? (file-keys p) "res/drawable/union.xml"))))))

;; --- Phase E — collect-hoistable (pure data) -------------------------------

(defn- two-instances
  "A frame 'Login' holding two same-component Button instances, each with a
  child rect — the realistic hoisting fixture (no name clash: the primary
  component is Login, the hoisted component is Button)."
  []
  (let [cid  (uuid/next)
        i1   (uuid/next)  i2 (uuid/next)
        c1   (uuid/next)  c2 (uuid/next)
        fid  (uuid/next)
        rect1 (rect c1 i1)  rect2 (rect c2 i2)
        inst1 (instance i1 cid [c1])
        inst2 (instance i2 cid [c2])
        frame1 (frame fid :name "Login" :shapes [i1 i2])]
    {:cid cid :frame frame1 :i1 inst1 :i2 inst2
     :c1 rect1 :c2 rect2
     :objects (objects frame1 inst1 inst2 rect1 rect2)
     :shapes [{:id fid}]}))

(deftest collect-hoistable-dedups-same-component
  (testing "two untouched local instances of one component are hoisted together"
    (let [{:keys [objects i1 i2]} (two-instances)
          {:keys [hoist-map specs]} (fcomp/collect-hoistable objects
                                                            (vals (select-keys objects [(:id i1) (:id i2)])))]
      (is (= 1 (count specs)))
      (is (= 2 (count hoist-map)))
      (is (contains? hoist-map (:id i1)))
      (is (contains? hoist-map (:id i2)))
      (is (= "Button" (:comp-name (first specs)))))))

(deftest collect-hoistable-skips-single-touched-crossfile
  (testing "a single instance, a touched instance and a cross-file instance are not hoisted"
    (let [cid (uuid/next)
          only  (instance (uuid/next) cid [])
          touched (instance (uuid/next) cid [] :touched #{:main-position})
          cross   (instance (uuid/next) cid [] :component-file (uuid/next))]
      (is (empty? (:specs (fcomp/collect-hoistable (objects only) [only]))))
      (is (empty? (:specs (fcomp/collect-hoistable (objects touched) [touched]))))
      (is (empty? (:specs (fcomp/collect-hoistable (objects cross) [cross])))))))

;; --- Phase E — Flutter + React Native hoisting (multi-file) ---------------

(deftest flutter-hoists-component-instance
  (testing "Flutter emits lib/widgets/<name>.dart, an import, and a CompName() ref"
    (let [{:keys [objects shapes]} (two-instances)
          p (cg/generate-framework-project objects "flutter" shapes)
          primary (get-in p [:files (:primary p)])]
      (is (= "lib/login.dart" (:primary p)))
      (is (contains? (file-keys p) "lib/widgets/button.dart"))
      (is (str/includes? primary "import 'widgets/button.dart';"))
      (is (str/includes? primary "Button()"))
      (is (str/includes? (get-in p [:files "lib/widgets/button.dart"]) "class Button")))))

(deftest react-native-hoists-component-instance
  (testing "RN emits components/<Comp>.jsx, an import, and a <Comp/> ref"
    (let [{:keys [objects shapes]} (two-instances)
          p (cg/generate-framework-project objects "react-native" shapes)
          primary (get-in p [:files (:primary p)])]
      (is (= "Login.jsx" (:primary p)))
      (is (contains? (file-keys p) "components/Button.jsx"))
      (is (str/includes? primary "from \"./components/Button\""))
      (is (str/includes? primary "<Button "))
      (is (str/includes? (get-in p [:files "components/Button.jsx"]) "export default function Button")))))