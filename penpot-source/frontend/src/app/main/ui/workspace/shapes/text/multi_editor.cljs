;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.shapes.text.multi-editor
  "Live editor that drives EVERY selected text shape in lock-step.

  Feature #49 — multi-edit text. When more than one text shape is
  selected, a single contenteditable surface is mounted once for the
  multi-edit session, rendering the lead (first) selected text shape's
  content. On every content change the new content is fanned out to all
  selected text shape ids via `dwt/update-text-content-multi`
  (`:save-undo? false` per keystroke, `:save-undo? true` on blur/commit).
  The other selected shapes re-render through the normal
  `svg_text.cljs` / `text-changes-renderer` path because their
  `:content` changes (which triggers a position-data recompute for each).

  ADDITIVE / GUARDED: the whole component returns nil unless
  `(> (count selected-text-ids) 1)`. When 0 or 1 text shape is selected
  no element, attribute, listener, or store event is emitted, so the
  existing `viewport-text-editing` / `text-changes-renderer` path renders
  byte-for-byte identically to today."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.shapes.text.html-text :as html]
   [app.util.dom :as dom]
   [app.util.text.content :as content]
   [rumext.v2 :as mf]))

(mf/defc multi-text-editor
  {::mf/wrap-props false}
  [_props]
  ;; `props` is accepted for mount-site symmetry (the parent mounts
  ;; `[:& multi-text-editor]` as a constant child of `viewport-texts`,
  ;; which stays pure-props / memoized via `check-props`). The selected
  ;; text ids and the shared content are derived here from reactive refs
  ;; so the parent does not need to deref selection state and so the
  ;; hooks below run unconditionally.
  (let [objects  (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)

        selected-text-shapes
        (mf/use-memo
         (mf/deps objects selected)
         (fn []
           (into []
                 (keep (fn [id]
                         (let [shape (get objects id)]
                           (when (cfh/text-shape? shape)
                             shape))))
                 selected)))

        selected-text-ids
        (mf/use-memo
         (mf/deps selected-text-shapes)
         #(into [] (map :id) selected-text-shapes))

        lead-shape (first selected-text-shapes)

        ;; GUARD: only engage the live multi-editor when more than one
        ;; text shape is selected. When 0 or 1 text shape is selected
        ;; this whole component returns nil — no element, attribute, or
        ;; listener is emitted — so the existing viewport-text-editing /
        ;; text-changes-renderer path renders byte-for-byte identically
        ;; to today (the `update-text-content-multi` event is purely
        ;; additive and only runs when explicitly emitted from here).
        ;; TODO: once an explicit multi-edit mode flag is added to
        ;; workspace-local state, additionally gate on it here. Gating on
        ;; `(> (count selected-text-ids) 1)` alone is sufficient today
        ;; and preserves the byte-identical-when-<=1 invariant.
        multi-edit? (> (count selected-text-ids) 1)

        vbox (mf/deref refs/vbox)
        zoom (mf/deref refs/selected-zoom)

        editor-ref (mf/use-ref nil)

        read-content
        (mf/use-fn
         (fn []
           (when-let [node (mf/ref-val editor-ref)]
             (when-let [root-el (dom/query node ".root.rich-text")]
               ;; `content/dom->cljs` reads the rich-text DOM tree back
               ;; into the CLJS content structure that
               ;; `update-text-content-multi` applies to every selected
               ;; text shape. Mirrors the v2 editor's
               ;; `(content/dom->cljs (dwt/get-editor-root instance))`
               ;; commit pattern.
               (content/dom->cljs root-el)))))

        on-input
        (mf/use-fn
         (mf/deps selected-text-ids)
         (fn [_event]
           ;; Per-keystroke intermediate update: no undo entry, so a
           ;; single multi-edit keystroke does not flood the undo stack.
           (when (seq selected-text-ids)
             (when-let [new-content (read-content)]
               (st/emit! (dwt/update-text-content-multi
                          selected-text-ids new-content
                          :save-undo? false))))))

        on-blur
        (mf/use-fn
         (mf/deps selected-text-ids)
         (fn [_event]
           ;; Commit: one undo entry for the whole multi-edit session.
           (when (seq selected-text-ids)
             (when-let [new-content (read-content)]
               (st/emit! (dwt/update-text-content-multi
                          selected-text-ids new-content
                          :save-undo? true))))))

        ;; Screen-space rect over the lead shape. The html text layer
        ;; lives inside the viewport foreignObject which maps workspace
        ;; coordinates to CSS pixels via `(coord - vbox) * zoom`.
        screen-rect
        (mf/use-memo
         (mf/deps lead-shape vbox zoom)
         (when (and lead-shape vbox)
           (let [vx (:x vbox 0)
                 vy (:y vbox 0)
                 z  (or zoom 1)
                 sx (dm/get-prop lead-shape :x)
                 sy (dm/get-prop lead-shape :y)
                 sw (dm/get-prop lead-shape :width)
                 sh (dm/get-prop lead-shape :height)]
             {:x      (* (- sx vx) z)
              :y      (* (- sy vy) z)
              :width  (* sw z)
              :height (* sh z)})))]

    (when (and multi-edit? lead-shape screen-rect)
      ;; A single visible, editable surface. We render the lead shape's
      ;; content with `html/render-node*` (the same rich-text renderer
      ;; used by `html/text-shape*`, minus the off-screen measurement
      ;; wrapper) so the user sees the shared content and edits it in
      ;; place. The `.root.rich-text` div produced by `render-root*` is
      ;; what `read-content` reads back on every input.
      [:div.multi-text-editor
       {:ref editor-ref
        :contentEditable true
        :suppressContentEditableWarning true
        :on-input on-input
        :on-blur on-blur
        :style #js {:position "fixed"
                    :left (dm/str (:x screen-rect) "px")
                    :top (dm/str (:y screen-rect) "px")
                    :width (dm/str (:width screen-rect) "px")
                    :height (dm/str (:height screen-rect) "px")
                    :opacity 1
                    :pointerEvents "all"
                    :zIndex 10
                    :background "white"
                    :overflow "hidden"}}
       [:> html/render-node*
        {:index 0
         :shape lead-shape
         :node (:content lead-shape)
         :is-code false}]])))