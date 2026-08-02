;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-bar
  "Feature 3 + 4 — the AI design bar.

  A bottom-floating input that drives the closed AI backend:
    - prompt textarea + reference URL input + image attachments
    - Max quality / Auto mode toggle (the two GLM/Kimi orchestration modes;
      the underlying models are NOT named in the UI, per spec)
    - frame preset selector (mobile / web / …) so generations land at the
      right canvas size for the project type
    - selection-aware region update: when shapes are selected, the bar
      offers 'update only the selection' so the AI regenerates just that
      region in place
    - generate button with spinner + live stage text (from the backend's
      ai-progress events), cancel button
    - preview modal: renders the generated DesignSpec as a crude SVG before
      it hits the canvas; Apply commits it (one undo transaction) /
      Regenerate / Cancel

  Styled with an injected <style> block + inline :style (no scss pipeline
  dependency, so it compiles without the build-generated .css.json that
  `stl/css` needs)."
  (:require
   [cuerdas.core :as str]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.data.workspace.design-gen :as dg]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.ai-settings :refer [ai-settings*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; ── Injected CSS ─────────────────────────────────────────────────────────────
;;
;; One <style> block, scoped under a unique root class so it cannot collide
;; with Penpot's own styles. Rendered once at the top of the bar.

(def ^:private ai-css
  "
.ai-root, .ai-root * { box-sizing: border-box; font-family: inherit; }
.ai-bar {
  position: absolute; left: 50%; transform: translateX(-50%);
  bottom: 18px; z-index: 60; width: min(760px, calc(100vw - 48px));
  background: rgba(255,255,255,0.96); backdrop-filter: blur(12px);
  border: 1px solid rgba(15,23,42,0.10); border-radius: 16px;
  box-shadow: 0 12px 40px rgba(15,23,42,0.18);
  padding: 12px 14px; display: flex; flex-direction: column; gap: 10px;
}
.ai-row { display: flex; align-items: flex-end; gap: 8px; }
.ai-grow { flex: 1 1 auto; min-width: 0; }
.ai-textarea {
  width: 100%; min-height: 44px; max-height: 160px; resize: none;
  border: 1px solid rgba(15,23,42,0.12); border-radius: 10px;
  padding: 9px 11px; font-size: 14px; line-height: 1.35; color: #0f172a;
  background: #fff; outline: none;
}
.ai-textarea:focus { border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99,102,241,0.15); }
.ai-input {
  border: 1px solid rgba(15,23,42,0.12); border-radius: 10px;
  padding: 8px 11px; font-size: 13px; color: #334155; background: #fff; outline: none; width: 100%;
}
.ai-input:focus { border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99,102,241,0.15); }
.ai-controls { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ai-seg { display: inline-flex; border: 1px solid rgba(15,23,42,0.12); border-radius: 9px; overflow: hidden; }
.ai-seg button {
  border: none; background: #fff; color: #475569; padding: 6px 12px;
  font-size: 12.5px; font-weight: 600; cursor: pointer; border-right: 1px solid rgba(15,23,42,0.08);
}
.ai-seg button:last-child { border-right: none; }
.ai-seg button.on { background: #4f46e5; color: #fff; }
.ai-select {
  border: 1px solid rgba(15,23,42,0.12); border-radius: 9px;
  padding: 6px 8px; font-size: 12.5px; color: #334155; background: #fff; cursor: pointer;
}
.ai-chk { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; color: #475569; cursor: pointer; }
.ai-chk input { margin: 0; }
.ai-btn {
  border: none; border-radius: 10px; padding: 9px 16px; font-size: 13.5px;
  font-weight: 700; cursor: pointer; display: inline-flex; align-items: center; gap: 7px;
  white-space: nowrap;
}
.ai-btn-primary { background: #4f46e5; color: #fff; }
.ai-btn-primary:hover { background: #4338ca; }
.ai-btn-primary:disabled { background: #c7d2fe; cursor: not-allowed; }
.ai-btn-ghost { background: transparent; color: #64748b; border: 1px solid rgba(15,23,42,0.12); }
.ai-btn-ghost:hover { background: #f8fafc; }
.ai-icon-btn {
  border: 1px solid rgba(15,23,42,0.12); background: #fff; border-radius: 10px;
  width: 38px; height: 38px; display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer; color: #475569; font-size: 16px;
}
.ai-icon-btn:hover { background: #f8fafc; }
.ai-attach { display: inline-flex; gap: 6px; flex-wrap: wrap; }
.ai-thumb {
  width: 40px; height: 40px; border-radius: 8px; object-fit: cover;
  border: 1px solid rgba(15,23,42,0.12); cursor: pointer; position: relative;
}
.ai-thumb-x {
  position: absolute; top: -6px; right: -6px; width: 16px; height: 16px;
  background: #ef4444; color: #fff; border-radius: 50%; font-size: 10px;
  display: flex; align-items: center; justify-content: center; line-height: 1;
}
.ai-stage { font-size: 11.5px; color: #6b7280; min-height: 14px; display: flex; align-items: center; gap: 7px; }
.ai-err { font-size: 12px; color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca;
  border-radius: 8px; padding: 6px 9px; }
.ai-spin { width: 14px; height: 14px; border: 2px solid rgba(255,255,255,0.5); border-top-color: #fff;
  border-radius: 50%; animation: ai-spin 0.7s linear infinite; }
@keyframes ai-spin { to { transform: rotate(360deg); } }
.ai-overlay { position: fixed; inset: 0; background: rgba(15,23,42,0.55); z-index: 200;
  display: flex; align-items: center; justify-content: center; padding: 24px; }
.ai-modal { background: #fff; border-radius: 16px; max-width: 920px; width: 100%;
  max-height: 88vh; display: flex; flex-direction: column; overflow: hidden;
  box-shadow: 0 24px 70px rgba(15,23,42,0.4); }
.ai-modal-head { padding: 16px 20px; border-bottom: 1px solid #eef2f7; display: flex; align-items: center; justify-content: space-between; }
.ai-modal-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.ai-modal-body { padding: 20px; overflow: auto; flex: 1; background: #f8fafc; }
.ai-modal-foot { padding: 14px 20px; border-top: 1px solid #eef2f7; display: flex; gap: 10px; justify-content: flex-end; }
.ai-badge { font-size: 11px; font-weight: 600; color: #6366f1; background: #eef2ff;
  padding: 3px 8px; border-radius: 6px; }
")

(defn- style-block
  "Render the injected <style> once."
  []
  [:style {:dangerouslySetInnerHTML #js {:__html ai-css}}])

;; ── Small presentational bits ───────────────────────────────────────────────

(def ^:private presets
  [["auto" "Auto"]
   ["mobile" "Mobile"]
   ["mobile-sm" "Mobile small"]
   ["tablet" "Tablet"]
   ["web" "Web"]
   ["web-wide" "Web wide"]
   ["desktop" "Desktop"]
   ["watch" "Watch"]])

(defn- spinner [] [:span.ai-spin])

;; ── The bar ──────────────────────────────────────────────────────────────────

(mf/defc ai-bar*
  []
  (let [prompt*        (mf/use-state "")
        url*           (mf/use-state "")
        quality*       (mf/use-state "auto")        ; "max" | "auto"
        preset*       (mf/use-state "auto")
        update-sel?*  (mf/use-state true)          ; region-update toggle
        attachments*  (mf/use-state [])            ; [{:file :name :preview}]
        stage*        (mf/use-state nil)
        show-settings* (mf/use-state false)
        file-input*   (mf/use-ref nil)

        busy          (mf/deref refs/ai-busy)
        preview       (mf/deref refs/ai-preview)
        error*        (mf/deref refs/ai-error)
        selected      (mf/deref refs/selected-shapes)
        has-sel?      (boolean (seq selected))

        prompt        (deref prompt*)
        url           (deref url*)
        quality       (deref quality*)
        preset        (deref preset*)
        update-sel?   (deref update-sel?*)
        attachments   (deref attachments*)
        stage         (deref stage*)
        show-settings (deref show-settings*)

        ;; Effective target: region update only when something is selected AND
        ;; the user hasn't disabled it. Otherwise "new-board" — the backend's
        ;; documented placement value (it injects `[placement target: …]` into
        ;; the LLM prompt), so the model sees a value it was taught.
        target (if (and has-sel? update-sel?) "update-selection" "new-board")

        on-prompt  (mf/use-fn (fn [e] (reset! prompt* (.. e -target -value))))
        on-url     (mf/use-fn (fn [e] (reset! url* (.. e -target -value))))
        on-quality (mf/use-fn (mf/deps quality*) (fn [q] (reset! quality* q)))
        on-preset  (mf/use-fn (fn [e] (reset! preset* (.. e -target -value))))
        on-toggle-sel (mf/use-fn (fn [e] (reset! update-sel?* (.. e -target -checked))))

        on-pick-files
        (mf/use-fn
         (fn [e]
           (let [files (array-seq (.. e -target -files))]
             (when (seq files)
               (let [new (mapv (fn [f]
                                 {:file f
                                  :name (.-name f)
                                  :preview (js/URL.createObjectURL f)})
                               files)]
                 (reset! attachments* (into (deref attachments*) new)))
               ;; reset so picking the same file again re-fires change
               (set! (.. e -target -value) "")))))

        open-picker
        (mf/use-fn
         (mf/deps file-input*)
         (fn []
           (some-> (mf/ref-val file-input*) dom/click)))

        remove-attachment
        (mf/use-fn
         (fn [idx]
           (let [cur (deref attachments*)
                 item (get cur idx)]
             (when (:preview item) (js/URL.revokeObjectURL (:preview item)))
             (reset! attachments* (into [] (keep-indexed #(when (not= %1 idx) %2) cur))))))

        on-generate
        (mf/use-fn
         (mf/deps prompt url attachments quality preset target)
         (fn []
           (let [full-prompt (ai/attach-url prompt url)]
             (if (and (str/empty? full-prompt) (empty? attachments))
               (st/emit! (ai/set-ai-error (tr "workspace.ai.bar.need-prompt")))
               (-> (ai/files->inputs (mapv :file attachments))
                   (p/then
                    (fn [inputs]
                      (st/emit! (ai/set-ai-error nil)
                                (ai/generate-design
                                 {:prompt full-prompt
                                  :files  inputs
                                  :options {:target       target
                                            :quality      quality
                                            :frame-preset preset
                                            :use-memory   true}}))))
                   (p/catch
                    (fn [e] (st/emit! (ai/set-ai-error (str e))))))))))

        on-cancel
        (mf/use-fn (fn []
                     ;; Clear the local stage text immediately: a cancelled
                     ;; generation's HTTP request can't be interrupted, so the
                     ;; backend may never emit its "done" progress event (it
                     ;; returns early when it notices the abort). Without this
                     ;; the spinner would spin beside the (now-hidden) busy flag.
                     (reset! stage* nil)
                     (st/emit! (ai/cancel-generation))))

        on-apply
        (mf/use-fn
         (mf/deps preview)
         (fn []
           (let [{:keys [spec target]} preview]
             (st/emit! (dg/apply-design-spec {:spec spec :target target})
                       (ai/clear-ai-preview)))))

        on-cancel-preview
        (mf/use-fn (fn [] (st/emit! (ai/clear-ai-preview))))

        on-regenerate
        (mf/use-fn
         (mf/deps on-generate)
         (fn []
           (st/emit! (ai/clear-ai-preview))
           (on-generate)))]

    ;; Subscribe to backend ai-progress events → local stage text. Empty deps
    ;; so it subscribes once on mount and cleans up on unmount.
    (mf/with-effect
      []
      (let [unp (ai/subscribe-progress
                  (fn [payload]
                    (let [s (.-stage payload)
                          d (.-detail payload)]
                      (cond
                        (= s "done")     (reset! stage* nil)
                        (= s "starting") (reset! stage* (tr "workspace.ai.bar.stage-starting"))
                        (= s "fetching-url") (reset! stage* (tr "workspace.ai.bar.stage-fetching"))
                        (= s "scouting")  (reset! stage* (tr "workspace.ai.bar.stage-scouting"))
                        (= s "generating") (reset! stage* (tr "workspace.ai.bar.stage-generating"))
                        (= s "finalizing") (reset! stage* (tr "workspace.ai.bar.stage-finalizing"))
                        :else (reset! stage* (str d))))))]
        (fn [] (-> unp
                   (p/then (fn [u] (when (fn? u) (u))))
                   (p/catch (fn [_] nil)))))

    ;; On error the backend returns Err BEFORE emitting the "done" progress
    ;; event, so the stage* set by "generating"/"finalizing" would never clear
    ;; and the spinner would spin forever beside the red error box. Clear the
    ;; stage text whenever an error appears.
    (mf/with-effect [error*]
      (when error* (reset! stage* nil)))

    ;; Single root: a display:contents wrapper so the absolute bar + fixed
    ;; modals position against the workspace :section / viewport, not this div.
    [:div {:style #js {"display" "contents"}}
     (style-block)

     (when show-settings
       [:> ai-settings* {:on-close #(reset! show-settings* false)}])

     [:div.ai-root
      [:div.ai-bar
       ;; prompt row
       [:div.ai-row
        [:div.ai-grow
         [:textarea.ai-textarea
          {:placeholder (tr "workspace.ai.bar.placeholder")
           :value prompt
           :on-change on-prompt
           :rows 2}]]]

       ;; attachments row (only when there are some)
       (when (seq attachments)
         [:div.ai-attach
          (for [idx (range (count attachments))]
            (let [a (get attachments idx)]
              [:div.ai-thumb {:key idx}
               [:img {:src (:preview a) :style #js {"width" "40px" "height" "40px" "object-fit" "cover"
                                                    "borderRadius" "8px"}
                      :alt (:name a)}]
               [:span.ai-thumb-x {:on-click #(remove-attachment idx)} "×"]]))])

       ;; reference URL row
       [:div.ai-row
        [:div.ai-grow
         [:input.ai-input
          {:type "url"
           :placeholder (tr "workspace.ai.bar.url-placeholder")
           :value url
           :on-change on-url}]]]

       ;; controls row
       [:div.ai-controls
        [:button.ai-icon-btn {:on-click open-picker :title (tr "workspace.ai.bar.attach")} "+"]
        [:div.ai-seg
         [:button {:class (when (= quality "max") "on") :on-click #(on-quality "max")}
          (tr "workspace.ai.bar.mode-max")]
         [:button {:class (when (= quality "auto") "on") :on-click #(on-quality "auto")}
          (tr "workspace.ai.bar.mode-auto")]]
        [:select.ai-select {:value preset :on-change on-preset}
         (for [[v label] presets]
           [:option {:key v :value v} label])]
        (when has-sel?
          [:label.ai-chk
           [:input {:type "checkbox" :checked update-sel? :on-change on-toggle-sel}]
           (tr "workspace.ai.bar.update-selection")])
        [:div.ai-grow]
        [:button.ai-icon-btn {:on-click #(reset! show-settings* true)
                              :title (tr "workspace.ai.bar.settings")} "⚙"]
        (if busy
          [:button.ai-btn.ai-btn-ghost {:on-click on-cancel}
           (spinner) (tr "workspace.ai.bar.cancel")]
          [:button.ai-btn.ai-btn-primary {:on-click on-generate
                                          :disabled (and (str/empty? prompt)
                                                          (str/empty? url)
                                                          (empty? attachments))}
           (tr "workspace.ai.bar.generate")])]

       ;; stage + error
       (when (or stage busy)
         [:div.ai-stage (spinner) (or stage (tr "workspace.ai.bar.working"))])
       (when error*
         [:div.ai-err error*])

       ;; hidden file input
       [:input {:type "file" :accept "image/*" :multiple true
                :ref file-input* :style #js {"display" "none"}
                :on-change on-pick-files}]]]

     ;; preview modal
     (when-let [p preview]
       [:div.ai-overlay {:on-click on-cancel-preview}
        [:div.ai-modal {:on-click #(.stopPropagation %)}
         [:div.ai-modal-head
          [:div {:style #js {"display" "flex" "alignItems" "center" "gap" "10px"}}
           [:span.ai-modal-title (tr "workspace.ai.bar.preview-title")]
           [:span.ai-badge (if (= (:target p) "update-selection")
                             (tr "workspace.ai.bar.preview-region")
                             (tr "workspace.ai.bar.preview-full"))]]
          [:button.ai-icon-btn {:on-click on-cancel-preview} "×"]]
         [:div.ai-modal-body
          (dg/spec->preview (:spec p))]
         [:div.ai-modal-foot
          [:button.ai-btn.ai-btn-ghost {:on-click on-regenerate}
           (tr "workspace.ai.bar.regenerate")]
          [:button.ai-btn.ai-btn-ghost {:on-click on-cancel-preview}
           (tr "workspace.ai.bar.cancel")]
          [:button.ai-btn.ai-btn-primary {:on-click on-apply}
           (tr "workspace.ai.bar.apply")]]]])]))