;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-motion
  "GSAP + anime.js animation helpers for the Ovion AI surfaces.

  This is the single place that talks to the two animation libraries, so the
  rest of the AI UI can stay declarative. Every helper is **defensive**:
    - The npm libs are resolved at load time, but if a build ever ships without
      them (or a bundler tree-shakes them out) every helper degrades to a no-op
      instead of throwing — the UI still renders, just without motion.
    - `prefers-reduced-motion` is honored globally: when the user has reduced
      motion enabled, the helpers short-circuit to the end state (or do
      nothing) rather than animating. This is non-negotiable for a11y.

  Convention: opts maps use the libraries' native camelCase keys
  (`:duration`, `:ease`, `:easing`, `:translateY`, `:stagger`, …) and are
  passed through `clj->js`, so callers write idiomatic CLJS.

  GSAP v3's default export IS the `gsap` object (it carries `.to`, `.from`,
  `.set`, `.timeline`, `.killTweensOf`). anime.js v3.2's default export IS the
  `anime` function (with `.timeline`/`.stagger` attached). Both shapes are
  resolved once at require time and re-exposed as `gsap` / `anime`."

  (:require
   ["gsap" :as gsap-lib]
   ["animejs" :as anime-lib]))

;; ── Library resolution ───────────────────────────────────────────────────────
;;
;; shadow-cljs `:as` binds a CommonJS/ESM module's default export. Both gsap
;; and animejs ship a default export that is the usable object/function, but we
;; still guard: if a future bundler shape binds the *namespace* object instead,
;; we fall back to its `:default` / `:gsap` / `:anime` member.

(defonce ^:private gsap-raw gsap-lib)

(defonce ^:private anime-raw anime-lib)

(defn- resolve-gsap
  "Return the gsap core object (the one with `.to`/`.timeline`), or nil."
  []
  (let [g gsap-raw]
    (cond
      (nil? g) nil
      (and (object? g) (fn? (unchecked-get g "to"))) g
      :else
      (or (unchecked-get g "default")
          (unchecked-get g "gsap")
          nil))))

(defn- resolve-anime
  "Return the anime function (with `.timeline`), or nil."
  []
  (let [a anime-raw]
    (cond
      (nil? a) nil
      (fn? a) a
      :else
      (or (unchecked-get a "default")
          (unchecked-get a "anime")
          nil))))

(defonce gsap (resolve-gsap))
(defonce anime (resolve-anime))

;; ── Reduced motion ───────────────────────────────────────────────────────────

(defn reduced-motion?
  "True when the user has asked the OS/browser to minimize non-essential
  motion. Re-checked each call so a live settings change takes effect without
  a reload."
  []
  (try
    (let [mq (.matchMedia js/window "(prefers-reduced-motion: reduce)")]
      (boolean (.-matches mq)))
    (catch :default _ false)))

;; ── Public helpers ───────────────────────────────────────────────────────────

(defn gsap-ready?
  []
  (some? gsap))

(defn anime-ready?
  []
  (some? anime))

(defn to!
  "GSAP `gsap.to(el, opts)`. `el` may be a DOM node, selector string, or array
  of nodes. Under reduced motion, falls back to an instant `gsap-set` so the
  element still reaches the end state. No-op if gsap is unavailable."
  [el opts]
  (when (and gsap el)
    (let [o (clj->js opts)]
      (if (reduced-motion?)
        (.set gsap el o)
        (.to gsap el o)))))

(defn from!
  "GSAP `gsap.from(el, opts)` — animates FROM the given values TO current.
  No-op under reduced motion (the element is already at its current state)."
  [el opts]
  (when (and gsap el (not (reduced-motion?)))
    (.from gsap el (clj->js opts))))

(defn gsap-set
  "GSAP `gsap.set(el, opts)` — applies values instantly, no transition. Safe
  to use to reset an element to a known start state before a tween."
  [el opts]
  (when (and gsap el)
    (.set gsap el (clj->js opts))))

(defn timeline
  "GSAP `gsap.timeline(opts)` — returns a timeline, or nil if gsap is missing
  or reduced motion is on (caller should guard before chaining `.to`)."
  ([]
   (timeline nil))
  ([opts]
   (when (and gsap (not (reduced-motion?)))
     (if opts
       (.timeline gsap (clj->js opts))
       (.timeline gsap)))))

(defn kill-tweens!
  "GSAP `gsap.killTweensOf(el)` — stop any in-flight tweens on `el` (e.g. before
  starting a fresh entrance so two animations don't fight)."
  [el]
  (when (and gsap el)
    (try (.killTweensOf gsap el)
         (catch :default _ nil))))

(defn animate!
  "anime.js entry: `(anime {:targets el :duration 600 :easing \"easeOutCubic\" …})`.
  Returns the animation instance (or nil under reduced motion / missing lib)."
  [opts]
  (when (and anime (not (reduced-motion?)))
    (try
      (anime (clj->js opts))
      (catch :default _ nil))))

(defn anime-timeline
  "anime.js `anime.timeline(opts)`. Returns the timeline or nil."
  ([]
   (anime-timeline nil))
  ([opts]
   (when (and anime (not (reduced-motion?)))
     (try
       (if opts
         (.timeline anime (clj->js opts))
         (.timeline anime))
       (catch :default _ nil)))))

(defn stagger
  "anime.js stagger helper: `(anime.stagger value opts)`. Returns a value
  usable as a property in an anime opts map. nil if anime missing."
  ([value]
   (stagger value nil))
  ([value opts]
   (when anime
     (try
       (if opts
         (.stagger anime value (clj->js opts))
         (.stagger anime value))
       (catch :default _ nil)))))

(defn path-length
  "Return the total length of an SVG path element (cached on the element as
  `._aiLen` so repeated draw-on animations don't re-measure). 0 if unavailable.
  Uses `getTotalLength()` — the standard SVGPathElement method (the
  `.totalLength` property alias is non-standard and absent in some engines)."
  [path-el]
  (if (nil? path-el)
    0
    (let [cached (unchecked-get path-el "_aiLen")]
      (if (number? cached)
        cached
        (try
          (let [n (.getTotalLength path-el)]
            (unchecked-set path-el "_aiLen" (if (js/isNaN n) 0 n))
            (if (js/isNaN n) 0 n))
          (catch :default _ 0))))))

(defn point-along
  "Return `[x y]` (in the path's own user coordinate system) at `frac` (0..1)
  along the SVG path. nil if the path can't be measured."
  [path-el frac]
  (when path-el
    (try
      (let [len (path-length path-el)
            pt (. path-el (getPointAtLength (* len frac)))]
        [(.-x pt) (.-y pt)])
      (catch :default _ nil))))

;; ── Hover / press micro-interactions (calm Operate feedback) ──────────────────
;;
;; The signature interaction on the AI surfaces: the coral inset ring quietly
;; intensifies on hover, and on press the inset deepens a touch more while the
;; small-control drop shadow flattens — a tactile "sink" with NO transform /
;; NO scale (the user explicitly banned size-change animations). Motion owns
;; box-shadow only; color is left to CSS so the two never fight.
;;
;; Two "faces" match the two control surfaces in the reference:
;;   :white — white pill controls (mode pill, screen pill, icon circles, chips):
;;            coral inset glow + grey→ink text on hover.
;;   :coral — the solid coral send button: WHITE inset glow on hover/press.
;;
;; GSAP drives hover (mouseenter/leave) AND press (mousedown/up): both go
;; through `to!`, and `kill-tweens!` runs first so a rapid hover→press→release
;; sequence never stacks competing tweens on the same element. Reduced motion
;; is honored inside `to!` (it falls back to an instant `.set`), so these
;; handlers are safe to attach unconditionally.

(def sh-rest-white  "1px 1px 2px 0px rgba(0,0,0,0.30), inset 0 0 2px 1px rgba(242,139,130,0.40)")
(def sh-hover-white "1px 2px 5px 0px rgba(0,0,0,0.32), inset 0 0 3px 2px rgba(242,139,130,0.45)")
(def sh-press-white "1px 0px 1px 0px rgba(0,0,0,0.24), inset 0 0 4px 2px rgba(242,139,130,0.60)")

(def sh-rest-coral  "1px 1px 2px 0px rgba(0,0,0,0.30), inset 0 0 2px 1px rgba(255,255,255,0.40)")
(def sh-hover-coral "1px 2px 5px 0px rgba(0,0,0,0.32), inset 0 0 2px 1px rgba(255,255,255,0.55)")
(def sh-press-coral "1px 0px 1px 0px rgba(0,0,0,0.24), inset 0 0 3px 1px rgba(255,255,255,0.60)")

(defn- ^boolean disabled-el?
  "True if the element (or an ancestor button) is disabled — don't animate
  disabled controls."
  [el]
  (loop [node el]
    (cond
      (nil? node) false
      (unchecked-get node "disabled") true
      :else (recur (unchecked-get node "parentNode")))))

(defn- tween-sh
  "Kill in-flight tweens on `el`, then GSAP-tween its box-shadow to `sh`."
  [el sh dur ease]
  (when (and el (not (disabled-el? el)))
    (kill-tweens! el)
    (to! el {:boxShadow sh :duration dur :ease ease})))

;; White-face controls (mode pill, screen pill, icon circles, chips, close).
(defn hov-white-in  [e] (tween-sh (.. e -currentTarget) sh-hover-white 0.22 "power3.out"))
(defn hov-white-out [e] (tween-sh (.. e -currentTarget) sh-rest-white  0.18 "power3.out"))
(defn press-white-in  [e] (tween-sh (.. e -currentTarget) sh-press-white 0.09 "power2.out"))
(defn press-white-out [e] (tween-sh (.. e -currentTarget) sh-hover-white 0.16 "power3.out"))

;; Coral-face controls (the send button).
(defn hov-coral-in  [e] (tween-sh (.. e -currentTarget) sh-hover-coral 0.22 "power3.out"))
(defn hov-coral-out [e] (tween-sh (.. e -currentTarget) sh-rest-coral  0.18 "power3.out"))
(defn press-coral-in  [e] (tween-sh (.. e -currentTarget) sh-press-coral 0.09 "power2.out"))
(defn press-coral-out [e] (tween-sh (.. e -currentTarget) sh-hover-coral 0.16 "power3.out"))

(defn pop-in
  "anime.js one-shot entrance for a popover/menu element: a calm rise + fade
  from just below its anchor (translateY 6px → 0, opacity 0 → 1). No scale.
  The caller renders the element at CSS opacity:0 so the first frame is
  invisible; this tweens it in. Under reduced motion OR missing anime, the
  element is forced to the visible end state directly (so it can never get
  stuck invisible) — reduced-motion users simply skip the entrance."
  [el]
  (when el
    (kill-tweens! el)
    (if (and anime (not (reduced-motion?)))
      (animate! {:targets el
                 :opacity [0 1]
                 :translateY [6 0]
                 :duration 220
                 :easing "easeOutCubic"})
      (try
        (set! (.. el -style -opacity) "1")
        (set! (.. el -style -transform) "translateY(0px)")
        (catch :default _ nil)))))

;; ── P1.16 Effects system: Appear / Loop / Drag ───────────────────────────────
;;
;; First-class design-time effect primitives rendered via GSAP/AnimeJS in
;; the viewer. Each effect is a small config map stored as shape plugin-data
;; (see data/workspace/motion_effects.cljs). The viewer reads it and calls
;; these helpers on the shape's DOM node.
;;
;; Every helper is defensive (no-op when the libs are missing) AND honors
;; `prefers-reduced-motion` globally: under reduced motion, Appear forces the
;; end state (visible), Loop is skipped entirely (continuous motion is the
;; kind of motion reduced-motion asks to suppress), and Drag is skipped
;; (drag is gesture-driven, not auto-motion, but we still skip to be safe).
;;
;; Each helper returns the animation instance (or nil) so the caller can
;; store and kill it on unmount — see viewer/shapes.cljs `run-motion-effect`.

(defn- dir-translate
  "Map an appear direction keyword to a [dx dy] offset vector (the start
  position relative to the rest position)."
  [direction]
  (case direction
    :left   [-24 0]
    :right  [24 0]
    :up     [0 -24]
    :down   [0 24]
    :fade   [0 0]
    :scale  [0 0]
    [0 0]))

(defn run-appear-effect
  "Appear (load/entrance) effect. Animates the element FROM an offset
  (direction) / fade / scale-up TO its rest state, once, on mount.
  `config` = {:direction :left/:right/:up/:down/:fade/:scale :duration ms
              :delay ms}. Under reduced motion the element is forced to the
  visible rest state. Returns the GSAP timeline (or nil)."
  [el config]
  (when el
    (let [direction (or (:direction config) :fade)
          duration (or (:duration config) 500)
          delay    (or (:delay config) 0)
          [dx dy]  (dir-translate direction)]
      (kill-tweens! el)
      (cond
        ;; Reduced motion: jump straight to the visible rest state.
        (reduced-motion?)
        (gsap-set el {:opacity 1 :x 0 :y 0 :scale 1})
        ;; Scale entrance uses GSAP from().
        (= direction :scale)
        (when gsap
          (.from gsap el (clj->js {:scale 0.85 :opacity 0
                                   :duration (/ duration 1000)
                                   :delay (/ delay 1000)
                                   :ease "power3.out"})))
        :else
        (when gsap
          (.from gsap el (clj->js {:x dx :y dy :opacity 0
                                   :duration (/ duration 1000)
                                   :delay (/ delay 1000)
                                   :ease "power3.out"})))))))

(defn run-loop-effect
  "Loop (continuous) effect. Runs an infinite GSAP timeline on the
  element. `config` = {:kind :rotate/:pulse/:slide :duration ms}. Under
  reduced motion OR missing gsap this is a no-op (continuous motion is
  the motion reduced-motion asks to suppress). Returns the timeline (or
  nil) — the caller MUST kill it on unmount to avoid leaks."
  [el config]
  (when (and el gsap (not (reduced-motion?)))
    (let [kind     (or (:kind config) :pulse)
          duration (or (:duration config) 1500)
          secs     (/ duration 1000)]
      (kill-tweens! el)
      (let [tl (.timeline gsap (clj->js {:repeat -1 :defaults {:ease "sine.inOut"}}))]
        (case kind
          :rotate (.fromTo tl el (clj->js {:rotation 0}) (clj->js {:rotation 360 :duration secs}))
          :pulse  (.fromTo tl el (clj->js {:scale 1 :opacity 1})
                           (clj->js {:scale 1.06 :opacity 0.85 :duration secs :yoyo true}))
          :slide  (.fromTo tl el (clj->js {:x 0})
                           (clj->js {:x 12 :duration secs :yoyo true}))
          nil)
        tl))))

(defn run-drag-effect
  "Drag effect — a gesture-driven translate constraint, NOT auto-motion.
  Attaches pointer drag listeners that translate the element along the
  configured axis, with an optional lock constraint. Under reduced motion
  this is a no-op (drag is suppressed to keep motion minimal).
  `config` = {:axis :x/:y/:both :constraint :none/:lock}. Returns a
  teardown function the caller MUST call on unmount (removes listeners +
  resets the transform)."
  [el config]
  (when (and el (not (reduced-motion?)))
    (let [axis       (or (:axis config) :both)
          constraint (or (:constraint config) :none)
          lock?      (= constraint :lock)]
      (try
        (let [start-x*  (volatile! 0)
              start-y*  (volatile! 0)
              dragging* (volatile! false)]
          (letfn [(on-down [e]
                    (vreset! dragging* true)
                    (vreset! start-x* (.. e -clientX))
                    (vreset! start-y* (.. e -clientY))
                    (when gsap (gsap-set el {:cursor "grabbing"})))
                  (on-move [e]
                    (when @dragging*
                      (let [dx (- (.. e -clientX) @start-x*)
                            dy (- (.. e -clientY) @start-y*)
                            x? (or (= axis :x) (= axis :both))
                            y? (or (= axis :y) (= axis :both))
                            nx (if x? dx 0)
                            ny (if y? dy 0)]
                        (when gsap (gsap-set el {:x nx :y ny}))
                        (when (and lock? gsap)
                          (if (= axis :x)
                            (gsap-set el {:y 0})
                            (gsap-set el {:x 0}))))))
                  (on-up [_e]
                    (when @dragging*
                      (vreset! dragging* false)
                      (when gsap
                        (to! el {:x 0 :y 0 :duration 0.4 :ease "power3.out"})
                        (gsap-set el {:cursor "grab"}))))]
            (.addEventListener el "pointerdown" on-down)
            (.addEventListener js/window "pointermove" on-move)
            (.addEventListener js/window "pointerup" on-up)
            (when gsap (gsap-set el {:cursor "grab"}))
            (fn teardown
              []
              (.removeEventListener el "pointerdown" on-down)
              (.removeEventListener js/window "pointermove" on-move)
              (.removeEventListener js/window "pointerup" on-up)
              (when gsap (gsap-set el {:cursor "" :x 0 :y 0})))))
        (catch :default _
          (fn teardown [] nil))))))

(defn run-motion-effect
  "Dispatch a motion-effect config `{:type ... :config {...}}` to the
  matching effect runner on `el`. Returns either an animation instance
  (Appear/Loop) or a teardown function (Drag), or nil. The caller stores
  this and disposes it on unmount. Reduced-motion guarded inside each
  runner. No-op when `effect` is nil (no effect authored)."
  [el effect]
  (when (and el effect)
    (case (:type effect)
      :appear (run-appear-effect el (:config effect))
      :loop   (run-loop-effect el (:config effect))
      :drag   (run-drag-effect el (:config effect))
      nil)))

;; ── P2.36: spring physics easing ─────────────────────────────────────────────
;;
;; A true spring needs per-frame physics integration. The viewer's transition
;; engine (viewer/interactions.cljs) drives transitions through the Web
;; Animations API (`dom/animate!`), whose `easing` option only accepts CSS
;; cubic-bezier-compatible strings — no native spring. Two complementary
;; surfaces are exposed here:
;;   - `spring-easing->bezier` — a pure approximation of
;;     {stiffness damping mass} as a CSS cubic-bezier string, so the existing
;;     `easing-str` path can route `:spring` without changing the animation
;;     engine. Lossy but functional and zero blast-radius.
;;   - `run-spring-transition` — a GSAP-driven spring tween (gsap exposes
;;     `ease: "elastic.out"` which is spring-like) for callers that want true
;;     physics on a single element's transform/opacity.
;; Both are reduced-motion guarded (the bezier returns a flat "linear", the
;; tween short-circuits to gsap-set).

(defn spring-easing->bezier
  "Approximate a spring config `{stiffness damping mass}` as a CSS
  cubic-bezier string suitable for the Web Animations API `easing` option.
  Higher stiffness / lower damping = more overshoot (a sharper, bouncier
  curve). Returns \"linear\" under reduced motion so the transition is
    instant-by-platform rather than animated-by-us. Defaults: stiffness 170,
    damping 26, mass 1 (GSAP's elastic defaults)."
  [{:keys [stiffness damping mass] :or {stiffness 170 damping 26 mass 1}}]
  (if (reduced-motion?)
    "linear"
    (let [damping (max 1 damping)
          ;; Map damping/stiffness onto the two control-point Y values.
          ;; Low damping (bouncy) -> y1 dips below 0 (overshoot). High
          ;; damping (critically damped) -> smooth ease.
          y1 (max -0.3 (min 0.0 (/ (- damping) 60.0)))
          y2 (/ (+ stiffness) 600.0)
          y2 (max 0.0 (min 1.0 y2))]
      (str "cubic-bezier(0.2," y1 ",0.4," y2 ")"))))

(defn run-spring-transition
  "Drive a spring-like transition on `el` toward `opts` (a clj map of GSAP
  tween props, e.g. {:x 100 :opacity 1}) using GSAP's elastic ease. Under
  reduced motion, instantly sets the end state via gsap-set. Returns the
  gsap tween instance (or nil). `config` is the spring
  `{stiffness damping mass}` map; stiffness maps to duration (stiffer =
  faster)."
  [el opts config]
  (when (and gsap el)
    (let [stiffness (:stiffness config 170)
          damping   (:damping config 26)
          ;; Stiffer spring = shorter duration. Clamp to a sane window.
          duration  (max 200 (min 1200 (/ 60000.0 (max 50 stiffness))))
          ease      (if (reduced-motion?) "none"
                       (if (< damping 10) "elastic.out(1,0.4)" "back.out(1.4)"))]
      (if (reduced-motion?)
        (gsap-set el opts)
        (.to gsap el (clj->js (merge {:duration duration :ease ease} opts)))))))

;; ── P2.36: Flow List transition ──────────────────────────────────────────────
;;
;; A Flow List transition is a stack push/pop: the incoming frame slides in
;; from the right while fading in, and the outgoing frame slides slightly left
;; + fades out, reusing GSAP timelines. It mirrors the overlay/transition
;; system's two-viewport signature (incoming + outgoing) so it can be wired
;; into `animate-go-to-frame` / `animate-open-overlay` with a single case
;; branch. Reduced-motion guarded: degrades to instant set on both viewports.

(defn run-flow-list-transition
  "Flow List stack transition. `in-el` is the incoming viewport DOM node,
  `out-el` the outgoing viewport DOM node, `width` the travel distance in px
  (typically the wrapper width). On reduced motion, both viewports are set
  instantly. Returns a gsap timeline instance (or nil) so the caller can
  attach an onComplete (e.g. emit dv/complete-animation)."
  ([in-el out-el width]
   (run-flow-list-transition in-el out-el width nil))
  ([in-el out-el width on-complete]
   (if (or (reduced-motion?) (nil? gsap))
     (do (when in-el (gsap-set in-el {:x 0 :opacity 1}))
         (when out-el (gsap-set out-el {:x 0 :opacity 1}))
         (when (fn? on-complete) (on-complete)))
     (let [tl (.timeline gsap (clj->js {:onComplete on-complete}))]
       (when in-el
         (.set gsap in-el (clj->js {:x width :opacity 0}))
         (.to gsap in-el (clj->js {:x 0 :opacity 1 :duration 0.45 :ease "power2.out"})))
       (when out-el
         (.to gsap out-el (clj->js {:x (- (* 0.25 width)) :opacity 0 :duration 0.45 :ease "power2.out"})))
       tl))))

;; ── P2.38: stroke-flow (animated dash offset / marching ants) ────────────────
;;
;; A motion slot that animates `stroke-dashoffset` on every stroked descendant
;; of a shape's DOM node so a dashed / dotted / mixed stroke appears to flow
;; along the path. Stored as plugin-data `:ovion "stroke-anim"`
;; `{:speed ms-per-cycle :direction :forward/:reverse}` (see
;; data/workspace/vector_sets.cljs). The viewer runtime calls this helper on
;; `#shape-<id>`; it queries descendants with a `strokeDasharray` style and
;; tweens their `strokeDashoffset` from 0 → ±(dash pattern length) infinitely
;; via GSAP (seamless because the pattern repeats every `len` units).
;;
;; Reduced-motion is NON-NEGOTIABLE: under `prefers-reduced-motion` the offset
;; is forced to 0 on every stroked descendant (a static dash) and NO tween
;; runs — the stroke renders exactly as it would with no slot. With no gsap,
;; the static offset is still applied and no tween runs. Returns a teardown
;; function the caller MUST call on unmount (kills the tweens); nil when there
;; is nothing to animate.

(defn- ^boolean has-dash?
  "True if element `n` has a non-empty `strokeDasharray` CSS property (set by
  app.main.ui.shapes.attrs/add-stroke! for :dashed/:dotted/:mixed styles)."
  [n]
  (try
    (let [st (unchecked-get n "style")]
      (and (some? st)
           (let [da (unchecked-get st "strokeDasharray")]
             (and (string? da) (pos? (count da))))))
    (catch :default _ false)))

(defn- collect-stroked-descendants
  "Return a vector of descendant elements of `el` that carry a
  `strokeDasharray` style. `el` itself is included if it qualifies. Robust
  against NodeList iteration differences across engines."
  [el]
  (if (nil? el)
    []
    (loop [i 0
           nl (.querySelectorAll el "*")
           acc []]
      (if (>= i (.-length nl))
        (if (has-dash? el) (conj acc el) acc)
        (let [n (.item nl i)]
          (recur (inc i) nl (if (has-dash? n) (conj acc n) acc)))))))

(defn- parse-dash-len
  "Parse a `strokeDasharray` CSS string (\"12,8\" / \"12 8\" / \"0,6\") into
  the sum of its numeric segments — the period over which `stroke-dashoffset`
  must travel to repeat seamlessly. Returns 0 for nil/empty/unparseable.
  Uses pure JS interop so no string namespace is required."
  [da]
  (if (or (nil? da) (empty? da))
    0
    (try
      (let [parts (-> da
                      (.replace "," " ")
                      (.split " "))]
        (loop [i 0 total 0]
          (if (>= i (.-length parts))
            total
            (let [t (.trim (aget parts i))
                  v (js/parseFloat t)]
              (recur (inc i)
                     (if (js/isNaN v) total (+ total v)))))))
      (catch :default _ 0))))

(defn run-stroke-flow-effect
  "P2.38 stroke-flow: animate `stroke-dashoffset` on every stroked descendant
  of `el` (the shape's DOM node) so a dashed/dotted/mixed stroke appears to
  flow along the path (marching ants). `config` = `{:speed ms-per-cycle
  :direction :forward/:reverse}`. Under reduced motion OR missing gsap, the
  offset is forced to 0 (static dash) and no animation runs — the stroke
  renders exactly as with no slot. Returns a teardown function the caller
  MUST call on unmount (kills the tweens); nil when there is nothing to
  animate (no stroked descendants)."
  [el config]
  (when el
    (let [speed     (or (:speed config) 2000)
          direction (or (:direction config) :forward)
          secs      (/ (max 250 speed) 1000)
          nodes     (collect-stroked-descendants el)]
      (cond
        ;; Reduced motion: force a static dash (offset 0) on every stroked
        ;; descendant, no tween. This is the non-negotiable a11y guarantee.
        (reduced-motion?)
        (do (doseq [n nodes]
              (try (gsap-set n {:strokeDashoffset 0})
                   (catch :default _ nil)))
            nil)

        (nil? gsap)
        (do (doseq [n nodes]
              (try (gsap-set n {:strokeDashoffset 0})
                   (catch :default _ nil)))
            nil)

        (empty? nodes)
        nil

        :else
        (let [tweens
              (into []
                    (keep (fn [n]
                            (let [da (unchecked-get (.. n -style) "strokeDasharray")
                                  len (parse-dash-len da)
                                  target (if (= direction :reverse) (- len) len)]
                              (try
                                (.to gsap n
                                     (clj->js {:strokeDashoffset target
                                               :duration secs
                                               :ease "none"
                                               :repeat -1}))
                                (catch :default _ nil)))))
                    nodes)]
          (fn teardown []
            (doseq [tw tweens]
              (try (.kill tw) (catch :default _ nil)))))))))