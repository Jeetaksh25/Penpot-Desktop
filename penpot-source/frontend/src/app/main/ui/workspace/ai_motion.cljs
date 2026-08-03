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
            pt (. path-el -getPointAtLength (* len frac))]
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