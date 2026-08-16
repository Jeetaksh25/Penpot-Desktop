;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.on-page-preview
  "On-page edit preview overlay (ALL_APPS_PARITY P1.24) — UI layer.

  A full-canvas fixed overlay that renders the exported/published HTML
  for the current page in a sandboxed `<iframe srcdoc=...>`. CMS-bound
  text elements are tagged `contenteditable` + `data-cms-key` in the
  srcdoc (by `data.workspace.on-page-edit/build-preview-html`). A tiny
  script injected into the srcdoc listens for `input`/`blur` on those
  elements and `postMessage`s `{:type :cms-edit :key <key> :text <text>}`
  to the parent window. The parent listens and emits
  `sync-cms-edit` so the edit lands in the project's CMS string table
  (one undo).

  Closed = no change. The overlay is only mounted when
  `on-page-edit-active?` is true (gated in workspace.cljs). Reduced-motion
  guard: the popover/overlay uses no motion when the user prefers
  reduced motion. Coral accent #f28b82, Lucide icons (stroke-width 2,
  currentColor). Nil-safe (empty page -> empty preview iframe)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.on-page-edit :as dope]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hiccup :as hic]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; ── Inline Lucide icons (viewBox 0 0 24 24, stroke-width 2, currentColor) ──

(defn- lucide-icon
  [children]
  (hic/el (into [:svg {:viewBox "0 0 24 24"
                       :fill "none"
                       :stroke "currentColor"
                       :stroke-width 2
                       :stroke-linecap "round"
                       :stroke-linejoin "round"
                       :width 16
                       :height 16
                       :style {:flex-shrink "0"}}]
                children)))

(defn- icon-exit []
  ;; Lucide `x` (close) icon.
  (lucide-icon [[:path {:d "M18 6 6 18"}]
                [:path {:d "m6 6 12 12"}]]))

(defn- icon-edit []
  ;; Lucide `pencil` / `edit` icon — labels the on-page edit toolbar.
  (lucide-icon [[:path {:d "M12 20h9"}]
                [:path {:d "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"}]]))

;; ── Coral accent (Ovion brand) ─────────────────────────────────────────────

(def ^:private coral "#f28b82")

(def ^:private toolbar-style
  {:position "fixed"
   :top 0
   :left 0
   :right 0
   :z-index 99999
   :display "flex"
   :align-items "center"
   :justify-content "space-between"
   :gap "12px"
   :padding "8px 14px"
   :background "rgba(20,20,20,0.96)"
   :border-bottom (str "2px solid " coral)
   :color "#f4f4f4"
   :font-size "13px"
   :font-weight "500"
   :box-shadow "0 2px 12px rgba(0,0,0,0.35)"})

(def ^:private hint-style
  {:display "inline-flex"
   :align-items "center"
   :gap "6px"
   :color "rgba(244,244,244,0.7)"
   :font-weight "400"
   :font-size "12px"})

(def ^:private exit-button-style
  {:display "inline-flex"
   :align-items "center"
   :gap "6px"
   :padding "5px 12px"
   :border (str "1px solid " coral)
   :border-radius "6px"
   :background "rgba(242,139,130,0.14)"
   :color coral
   :font-size "12px"
   :font-weight "600"
   :cursor "pointer"
   :line-height "1.4"})

(def ^:private iframe-style
  {:position "fixed"
   :top "41px"
   :left 0
   :width "100vw"
   :height "calc(100vh - 41px)"
   :border "none"
   :background "#ffffff"
   :z-index 99998})

;; ── Injected iframe script (postMessage sync) ──────────────────────────────
;;
;; Runs inside the srcdoc iframe. Listens for `input` + `blur` on every
;; `[contenteditable][data-cms-key]` element and `postMessage`s the new
;; text + key to the parent. Uses `innerText` (rendered text) which is
;; what the user sees/edits. Guards against missing elements / reduced
;; motion (no animation either way — purely a data event).

(def ^:private iframe-sync-script
  "<script>
  (function(){
    function keyOf(el){ return el.getAttribute('data-cms-key'); }
    function emit(el, type){
      var k = keyOf(el);
      if(!k) return;
      var t = (el.innerText != null) ? el.innerText : (el.textContent || '');
      try { parent.postMessage({ source:'ovion-on-page-edit', type:'cms-edit', key:k, text:t }, '*'); } catch(e){}
    }
    function bind(el){
      if(!el || el.__ovionCmsBound) return;
      el.__ovionCmsBound = true;
      el.addEventListener('input', function(){ emit(el,'input'); }, {passive:true});
      el.addEventListener('blur',  function(){ emit(el,'blur');  }, {passive:true});
    }
    function init(){
      var nodes = document.querySelectorAll('[contenteditable][data-cms-key]');
      for(var i=0;i<nodes.length;i++){ bind(nodes[i]); }
    }
    if(document.readyState === 'loading'){
      document.addEventListener('DOMContentLoaded', init);
    } else { init(); }
  })();
  </script>")

;; ── Component ──────────────────────────────────────────────────────────────

(mf/defc on-page-preview*
  {::mf/props :obj
   ::mf/wrap [#(mf/memo' %)]}
  [{:keys []}]
  (let [;; Current file + page, derefed via the same refs the workspace
        ;; uses. `refs/file` is the derived current-file map (carries
        ;; `:id` + `:data`). Nil-safe.
        file    (mf/deref refs/file)
        file-id (:id file)
        fdata   (:data file)
        page    (mf/deref refs/workspace-page)
        page-id (:id page)

        ;; Build the preview HTML once per page (recomputed when the
        ;; page id changes). Nil-safe (no file-data / no page-id -> nil).
        preview-html
        (mf/use-memo
         (mf/deps page-id file-id)
         (fn []
           (if (or (nil? fdata) (nil? page-id))
             nil
             (dope/build-preview-html fdata page-id))))

        ;; srcdoc = preview html + the sync script. Empty page -> a
        ;; minimal placeholder document so the iframe is never blank-black.
        srcdoc
        (mf/use-memo
         (mf/deps preview-html)
         (fn []
           (if (str/blank? preview-html)
             "<!doctype html><html><body style=\"font-family:sans-serif;color:#888;padding:24px\"><p>Nothing to edit on this page.</p></body></html>"
             (str preview-html "\n" iframe-sync-script))))

        ;; Parent-side message listener: validate origin shape, decode
        ;; the cms-key string (`collection-id|field-id|item-id`) and
        ;; emit `sync-cms-edit`. Installed once on mount, removed on
        ;; unmount. Ignores messages not carrying our `source` tag.
        on-message
        (mf/use-fn
         (mf/deps)
         (fn [event]
           (let [data (.-data event)]
             (when (and (some? data)
                        (= (.-source data) "ovion-on-page-edit")
                        (= (.-type data)   "cms-edit"))
               (let [key-str (.-key data)
                     text    (.-text data)]
                 (when (and (string? key-str) (not (str/blank? key-str)))
                   (let [parts (vec (str/split key-str "|"))]
                     ;; Expect 3 segments: collection-id field-id item-id.
                     ;; The cms-data slot stores UUID values (from
                     ;; ctcol/make-collection), so parse the hex strings
                     ;; back to UUIDs or update-item-field's `=` on
                     ;; `(:id c)` (uuid) would never match a string.
                     (when (>= (count parts) 3)
                       (let [coll-id  (uuid (nth parts 0))
                             field-id (uuid (nth parts 1))
                             item-id  (uuid (nth parts 2))]
                         (st/emit!
                          (dope/sync-cms-edit
                           {:collection-id coll-id
                            :field-id      field-id
                            :item-id       item-id}
                           text)))))))))))]

    ;; Install / remove the message listener.
    (mf/use-effect
     (mf/deps on-message)
     (fn []
       (.addEventListener js/window "message" on-message)
       #(.removeEventListener js/window "message" on-message)))

    (when (some? page-id)
      [:*
       [:div {:style toolbar-style
              :role "toolbar"
              :aria-label (tr "workspace.on-page-edit.toolbar")}
        [:span {:style hint-style}
         (icon-edit)
         (tr "workspace.on-page-edit.title")]
        [:button {:type "button"
                  :style exit-button-style
                  :on-click #(st/emit! (dope/toggle-on-page-edit false))}
         (icon-exit)
         (tr "workspace.on-page-edit.exit")]]
       [:iframe {:srcDoc srcdoc
                 :title (tr "workspace.on-page-edit.preview")
                 :sandbox "allow-scripts"
                 :style iframe-style}]])))