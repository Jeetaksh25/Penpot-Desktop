;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.code-gen.markup-html
  (:require
   ["react-dom/server" :as rds]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape.layout :as ctl]
   [app.config :as cfg]
   [app.main.ui.shapes.text.html-text :as text]
   [app.util.code-gen.common :as cgc]
   [app.util.code-gen.markup-svg :refer [generate-svg]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; ── Scroll-driven web export (ALL_APPS_PARITY Phase 4) ──────────────────────
;;
;; The Penpot interactions schema (app.common.types.shape.interactions)
;; defines ONE scroll-driven trigger/action pair:
;;   * trigger  `:event-type  :while-scrolling`
;;   * action   `:action-type :scroll-animate`
;; with a payload of `:axis` (:vertical/:horizontal), `:range-start`,
;; `:range-end`, `:keyframes` (a vector of `{:offset 0..1 :props ...}`)
;; and `:easing`. There is NO `:scroll-into-view` trigger in the schema
;; (only `:while-scrolling`), so this export maps `:while-scrolling` to a
;; vanilla scroll listener that drives keyframe interpolation. An
;; IntersectionObserver-based `bindReveal` helper is also shipped for
;; forward compatibility and as a no-external-dep fallback.
;;
;; The export is production-safe: NO GSAP / Motion One dependency. It uses
;; only the IntersectionObserver + scroll listener APIs. Every per-shape
;; snippet is individually wrapped in a `prefers-reduced-motion` guard, and
;; the shared init script also bails under reduced-motion.

(defn- scroll-interactions
  "Return the subset of `shape`'s `:interactions` that are scroll-driven
  (`:while-scrolling` trigger + `:scroll-animate` action)."
  [shape]
  (let [interactions (get shape :interactions [])]
    (filterv (fn [i]
               (and (= (:event-type i) :while-scrolling)
                    (= (:action-type i) :scroll-animate)))
             interactions)))

(defn- shape->query-selector
  "Build a CSS selector targeting the shape's emitted wrapper element.
  `cgc/shape->selector` returns a single dash-separated class token, so
  `.<selector>` is a valid CSS class selector."
  [shape]
  (dm/str "." (cgc/shape->selector shape)))

;; Shared init script — emitted ONCE at the top of `generate-markup` output.
;; Defines `window.__ovionScroll` with `bindScrollAnimate` (scroll-progress
;; keyframe interpolation) and `bindReveal` (IntersectionObserver enter/leave).
;; Bails early under `prefers-reduced-motion: reduce`.
(def ^:private scroll-init-script
  "<script>
(function(){
  if(window.__ovionScrollInit)return;
  window.__ovionScrollInit=true;
  if(window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;
  function ease(t,k){switch(k){case 'ease-in':return t*t;case 'ease-out':return t*(2-t);case 'ease-in-out':return t<.5?2*t*t:-1+(4-2*t)*t;default:return t;}}
  function interp(a,b,p){if(a==null)return b;if(b==null)return a;return a+(b-a)*p;}
  function applyProps(el,p){if(!p)return;var op=p.opacity;if(op!=null)el.style.opacity=op;var tx=p['translate-x'],ty=p['translate-y'],sc=p.scale,rot=p.rotate;var t='';if(tx!=null)t+='translateX('+tx+'px) ';if(ty!=null)t+='translateY('+ty+'px) ';if(sc!=null)t+='scale('+sc+') ';if(rot!=null)t+='rotate('+rot+'deg) ';el.style.transform=t.trim();}
  function findSeg(ks,p){for(var i=0;i<ks.length-1;i++){var a=ks[i],b=ks[i+1];if(p>=a.offset&&p<=b.offset){var span=b.offset-a.offset||1;return {a:a,b:b,lp:(p-a.offset)/span};}}return null;}
  function bindScrollAnimate(el,kfs,axis,rs,re,easing){
    if(!kfs||!kfs.length)return;
    kfs=kfs.slice().sort(function(a,b){return a.offset-b.offset;});
    rs=rs||0;re=re||1;easing=easing||'linear';
    function upd(){var sp=axis==='horizontal'?(window.scrollX||window.pageXOffset):(window.scrollY||window.pageYOffset);var p=(sp-rs)/(re-rs);p=p<0?0:(p>1?1:p);p=ease(p,easing);var seg=findSeg(kfs,p);if(!seg){applyProps(el,kfs[kfs.length-1].props);return;}var m={};var ks=Object.keys(seg.a.props||{});for(var i=0;i<ks.length;i++){var k=ks[i],v0=seg.a.props[k],v1=seg.b.props?seg.b.props[k]:null;m[k]=interp(v0,v1,seg.lp);}applyProps(el,m);}
    window.addEventListener('scroll',upd,{passive:true});
    upd();
  }
  function bindReveal(el,enter,leave){if(!(window.IntersectionObserver)){applyProps(el,enter);return;}var io=new IntersectionObserver(function(es){for(var i=0;i<es.length;i++){var e=es[i];applyProps(el,e.isIntersecting?enter:(leave||enter));}},{threshold:0.15});io.observe(el);}
  window.__ovionScroll={bindScrollAnimate:bindScrollAnimate,bindReveal:bindReveal,applyProps:applyProps};
})();
</script>")

(defn- scroll-snippet
  "Emit one per-shape `<script>` tag per scroll interaction. Each is
  individually wrapped in a `prefers-reduced-motion` guard (per the task
  hard-constraint) and calls the shared `window.__ovionScroll` helper.
  `interactions` is the seq returned by `scroll-interactions`."
  [shape interactions]
  (->> interactions
       (keep
        (fn [i]
          (let [sel      (shape->query-selector shape)]
            (when (seq sel)
              (let [kfs      (or (:keyframes i) [])
                    kfs-json (js/JSON.stringify (clj->js kfs))
                    axis     (name (or (:axis i) :vertical))
                    rs       (or (:range-start i) 0)
                    re       (or (:range-end i) 1)
                    easing   (name (or (:easing i) :linear))]
                (dm/str
                 "<script>(function(){"
                 "if(window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches)return;"
                 "var el=document.querySelector(\"" sel "\");"
                 "if(!el||!window.__ovionScroll)return;"
                 "window.__ovionScroll.bindScrollAnimate(el," kfs-json ",\"" axis "\"," rs "," re ",\"" easing "\");"
                 "})();</script>"))))))
       (str/join "\n")))

(defn generate-html
  ([objects shape]
   (generate-html objects shape 0))

  ([objects shape level]
   (when (and (some? shape) (some? (:selrect shape)))
     (let [indent (str/repeat "  " level)

           shape-html
           (cond
             (cgc/svg-markup? shape)
             (let [svg-markup (generate-svg objects shape)]
               (dm/fmt "%<div class=\"%\">\n%\n%</div>"
                       indent
                       (dm/str "shape " (d/name (:type shape)) " "
                               (cgc/shape->selector shape))
                       svg-markup
                       indent))

             (cfh/text-shape? shape)
             (let [text-shape-html (rds/renderToStaticMarkup (mf/element text/text-shape* #js {:shape shape :isCode true}))
                   text-shape-html (str/replace text-shape-html #"style\s*=\s*[\"'][^\"']*[\"']" "")]
               (dm/fmt "%<div class=\"%\">\n%\n%</div>"
                       indent
                       (dm/str "shape " (d/name (:type shape)) " "
                               (cgc/shape->selector shape))
                       text-shape-html
                       indent))

             (cfh/image-shape? shape)
             (let [data (or (:metadata shape) (:fill-image shape))
                   image-url (cfg/resolve-file-media data)]
               (dm/fmt "%<img src=\"%\" class=\"%\">\n%</img>"
                       indent
                       image-url
                       (dm/str "shape " (d/name (:type shape)) " "
                               (cgc/shape->selector shape))
                       indent))

             (empty? (:shapes shape))
             (dm/fmt "%<div class=\"%\">\n%</div>"
                     indent
                     (dm/str "shape " (d/name (:type shape)) " "
                             (cgc/shape->selector shape))
                     indent)

             :else
             (let [children (->> shape :shapes (map #(get objects %)))
                   reverse? (ctl/any-layout? shape)
                   ;; The order for layout elements is the reverse of SVG order
                   children (cond-> children reverse? reverse)]
               (dm/fmt "%<div class=\"%\">\n%\n%</div>"
                       indent
                       (dm/str (d/name (:type shape)) " "
                               (cgc/shape->selector shape))
                       (->> children
                            (map #(generate-html objects % (inc level)))
                            (str/join "\n"))
                       indent)))

           shape-html
           (let [scroll-ints (scroll-interactions shape)]
             (if (seq scroll-ints)
               (dm/str shape-html "\n" (scroll-snippet shape scroll-ints))
               shape-html))

           shape-html
           (if (cgc/has-wrapper? objects shape)
             (dm/fmt  "<div class=\"%\">%</div>"
                      (dm/str (cgc/shape->selector shape) "-wrapper")
                      shape-html)

             shape-html)]
       (dm/fmt "%<!-- % -->\n%" indent (dm/str (d/name (:type shape)) ": " (:name shape)) shape-html)))))

(defn- any-scroll-shape?
  "Walk the shape tree (via the objects index) and return true if any shape
  carries a `:while-scrolling` + `:scroll-animate` interaction. Used to gate
  the shared init script so non-scroll exports don't ship a dead script block."
  [objects shapes]
  (letfn [(walk [sh]
            (or (seq (scroll-interactions sh))
                (some (fn [id] (some-> (get objects id) walk))
                      (:shapes sh))))]
    (some walk shapes)))

(defn generate-markup
  [objects shapes]
  ;; Prepend the shared scroll-init script ONCE, but only when at least one
  ;; shape in the tree carries a scroll interaction. It is idempotent and
  ;; self-guards under `prefers-reduced-motion: reduce`.
  (let [body (->> shapes
                  (keep #(generate-html objects %))
                  (str/join "\n"))]
    (if (any-scroll-shape? objects shapes)
      (dm/str scroll-init-script "\n" body)
      body)))
