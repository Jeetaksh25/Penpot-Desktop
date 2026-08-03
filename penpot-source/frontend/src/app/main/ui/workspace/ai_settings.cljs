;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.ai-settings
  "Feature 3 — the AI Settings modal. Edits the closed AI layer's config
  (provider, quality mode, model slugs, API keys, URL-fetch (Firecrawl),
  timeout, conversation memory). Reads via `llm_get_config` (which masks
  API keys to presence flags) and writes via `llm_set_config`.

  Because the backend structs use snake_case serde field names with no
  `rename_all`, the config map sent to `llm_set_config` uses underscore
  keywords (`:deepinfra_api_key` …). Blank API-key fields are preserved by
  the backend (`llm_set_config` keeps the existing key when the incoming one
  is blank), so the masked-key fields can stay empty unless the user enters a
  new one.

  Restyled to the Penpot-generated UI reference's visual world (see
  ai-design): white surfaces, the #f28b82 coral accent, Helvetica Now
  Display, Lucide close glyph, and the coral inset glow on the small
  controls. Motion is calm — a single modal entrance, no choreographed
  section stagger (Operate mode: don't make users wait through load
  choreography)."
  (:require
   [cuerdas.core :as str]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.ai-design :as ad]
   [app.main.ui.workspace.ai-motion :as aim]
   [app.util.i18n :as i18n :refer [tr]]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

;; Injected <style> block. Built on the reference-pinned tokens in
;; `ai-base-css` (coral #f28b82, grey #7d7d7d, white surfaces, Helvetica Now
;; Display) so the modal matches the AI bar's visual world exactly. No SCSS
;; pipeline dependency — this is a raw CSS string scoped under `.ais-root`.
(def ^:private settings-css
  "
.ais-overlay { position: fixed; inset: 0; background: var(--ai-overlay);
  z-index: 210; display: flex; align-items: center; justify-content: center; padding: 24px;
  animation: ai-overlay-in var(--ai-dur-base) var(--ai-ease-out) both; }
.ais-modal { background: var(--ai-white); border-radius: var(--ai-radius-md);
  width: min(560px, 100%); max-height: 88vh; display: flex; flex-direction: column;
  overflow: hidden; box-shadow: 0 24px 70px rgba(0, 0, 0, 0.4);
  font-family: var(--ai-font); color: var(--ai-ink);
  animation: ai-modal-in var(--ai-dur-slow) var(--ai-ease-out) both; }
.ais-head { padding: 16px 20px; border-bottom: 1px solid #ececec;
  display: flex; align-items: center; justify-content: space-between; }
.ais-title { font-size: 15px; font-weight: 700; color: var(--ai-ink); font-family: var(--ai-font); }
.ais-body { padding: 18px 20px; overflow: auto; flex: 1; display: flex; flex-direction: column; gap: 14px; }
.ais-foot { padding: 14px 20px; border-top: 1px solid #ececec;
  display: flex; gap: 10px; justify-content: space-between; }
.ais-field { display: flex; flex-direction: column; gap: 5px; }
.ais-label { font-size: 12px; font-weight: 600; color: var(--ai-ink); font-family: var(--ai-font); }
.ais-hint { font-size: 11px; color: var(--ai-grey-2); font-family: var(--ai-font); }
.ais-input { border: 1px solid #ececec; border-radius: 10px; padding: 8px 10px;
  font-size: 13px; font-family: var(--ai-font); color: var(--ai-ink);
  background: var(--ai-white); outline: none;
  transition: border-color var(--ai-dur-fast) var(--ai-ease-out),
              box-shadow var(--ai-dur-fast) var(--ai-ease-out); }
.ais-input:focus { border-color: var(--ai-coral);
  box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ais-row { display: flex; gap: 10px; }
.ais-row > * { flex: 1 1 0; }
.ais-section { font-size: 11px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase;
  color: var(--ai-grey); margin-top: 6px; font-family: var(--ai-font); }

.ais-btn { border: none; border-radius: var(--ai-radius-md); padding: 9px 16px; font-size: 13px;
  font-weight: 600; cursor: pointer; font-family: var(--ai-font);
  display: inline-flex; align-items: center; justify-content: center; gap: 8px; white-space: nowrap;
  transition: background var(--ai-dur-fast) var(--ai-ease-out),
              color var(--ai-dur-fast) var(--ai-ease-out); }
.ais-btn:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--ai-coral-faint); }
.ais-btn-primary { background: var(--ai-coral); color: var(--ai-white);
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-white); }
.ais-btn-primary:hover { background: var(--ai-coral-press); }
.ais-btn-primary:disabled { background: #f3c4be; cursor: not-allowed; box-shadow: var(--ai-shadow-btn); }
.ais-btn-ghost { background: var(--ai-white); color: var(--ai-grey-2);
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral); }
.ais-btn-ghost:hover { color: var(--ai-ink); }
.ais-btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }

.ais-close { width: 34px; height: 34px; flex: none; border: none; cursor: pointer;
  background: var(--ai-white); color: var(--ai-grey); border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.ais-close:hover { color: var(--ai-ink); }
.ais-close:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.ais-close .ais-i { width: 18px; height: 18px; }

/* ── Selection tools (relocated from the AI bar) ───────────────────────────
   The 'update only the selection' toggle + the Figma-#71 AI tools (rename /
   generate text) live here so the AI bar stays uncluttered. Same visual
   language: white pill chips with the coral inset glow. Hover/press box-shadow
   is owned by GSAP (ai-motion) — CSS keeps color only, no scale. ──────── */
.ais-tools { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
.ais-tool { display: inline-flex; align-items: center; gap: 6px; height: 34px; padding: 0 14px;
  background: var(--ai-white); color: var(--ai-grey-2); font-size: 12.5px; font-weight: 500;
  border: none; border-radius: var(--ai-radius-pill); cursor: pointer; user-select: none;
  font-family: var(--ai-font);
  box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral);
  transition: color var(--ai-dur-fast) var(--ai-ease-out); }
.ais-tool:hover { color: var(--ai-ink); }
.ais-tool:disabled { opacity: 0.5; cursor: not-allowed; }
.ais-tool:focus-visible { outline: none; box-shadow: var(--ai-shadow-btn), var(--ai-inset-coral), 0 0 0 3px var(--ai-coral-faint); }
.ais-tool .ais-i { width: 15px; height: 15px; }
.ais-spin { width: 14px; height: 14px; border: 2px solid rgba(242,139,130,0.3);
  border-top-color: var(--ai-coral); border-radius: 50%;
  animation: ai-spin 0.7s linear infinite; display: inline-block; }
.ais-sel-info { font-size: 11px; color: var(--ai-grey-2); font-family: var(--ai-font); }
.ais-sel-info b { color: var(--ai-ink); font-weight: 600; }
.ais-status { font-size: 11px; font-family: var(--ai-font); min-height: 14px; line-height: 1.4; }
.ais-status.is-err { color: #b3261e; }
.ais-status.is-ok { color: var(--ai-grey-2); }

.ais-note { font-size: 11px; color: var(--ai-ink); font-family: var(--ai-font);
  background: var(--ai-coral-faint); border: 1px solid #f3c4be;
  border-radius: var(--ai-radius-sm); padding: 8px 10px; line-height: 1.4; }

.ais-chk-row { display: inline-flex; align-items: center; gap: 8px; cursor: pointer;
  user-select: none; }
.ais-chk { appearance: none; -webkit-appearance: none; width: 16px; height: 16px; margin: 0;
  border: 1.5px solid var(--ai-grey); border-radius: 5px; background: var(--ai-white); cursor: pointer;
  display: inline-grid; place-content: center;
  transition: background var(--ai-dur-fast) var(--ai-ease-out),
              border-color var(--ai-dur-fast) var(--ai-ease-out); }
.ais-chk:checked { background: var(--ai-coral); border-color: var(--ai-coral); }
.ais-chk:checked::after { content: \"\"; width: 9px; height: 9px;
  background: var(--ai-white); clip-path: polygon(14% 44%, 0 65%, 50% 100%, 100% 16%, 80% 0, 43% 62%); }
.ais-chk:focus-visible { box-shadow: 0 0 0 3px var(--ai-coral-faint); }
")

;; Lucide close glyph (one family, stroke-width 2, currentColor) — matches
;; the AI bar's icon language.
(def ^:private lucide-x
  [:svg.ais-i {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
               :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
               :aria-hidden "true"}
   [:path {:d "M18 6 6 18"}]
   [:path {:d "m6 6 12 12"}]])

;; Lucide pencil + type glyphs for the relocated Selection tools.
(def ^:private lucide-pencil
  [:svg.ais-i {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
               :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
               :aria-hidden "true"}
   [:path {:d "M21.174 6.812a1 1 0 0 0-3.986-1.992L3.842 16.17a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"}]
   [:path {:d "m15 5 4 4"}]])

(def ^:private lucide-type
  [:svg.ais-i {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
               :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
               :aria-hidden "true"}
   [:path {:d "M4 7V4h16v3"}]
   [:path {:d "M9 20h6"}]
   [:path {:d "M12 4v16"}]])

(defn- spinner [] [:span.ais-spin])

(defn- safe-int
  "Parse `v` to a non-negative int in a sane range; if blank/NaN (e.g. the
  user cleared the number input before typing a new value) fall back to
  `default`. Prevents three failure classes against the backend's non-Option
  `u64`/`usize` fields: NaN → JSON null, a negative int (e.g. '-5' parses to
  -5, not NaN, and serde rejects `integer -5 expected u64`), and an absurdly
  large value (parseInt yields a float-exponent Number that overflows u64)."
  [v default]
  (let [n (js/parseInt v)]
    (cond
      (js/isNaN n) default
      (neg? n)    default
      :else       (min n 100000))))

(defn- field
  [{:keys [label hint value on-change type placeholder]}]
  [:div.ais-field
   [:span.ais-label label]
   [:input.ais-input
    ;; Controlled when a :value is supplied (text fields: model slug, base
    ;; URL — they bind to cfg so the loaded value renders). UNcontrolled when
    ;; no :value is supplied (the API-key/password fields: the backend masks
    ;; keys to a presence flag, so we show a "key set" placeholder and let the
    ;; user type freely — a forced :value "" would make these React controlled
    ;; inputs bound to the constant empty string and every keystroke would be
    ;; reverted, making it impossible to enter a key on a packaged install).
    (cond-> {:on-change on-change
             :placeholder placeholder}
      (some? value) (assoc :value (or value ""))
      type          (assoc :type type))]
   (when hint [:span.ais-hint hint])])

;; Registered as a modal so any surface (titlebar gear, AI bar gear) can open
;; it via `(st/emit! (modal/show {:type :ai-settings}))`. When opened through
;; the modal system `on-close` is nil and the close actions emit `modal/hide`;
;; the optional `on-close` prop is still honored for inline callers.
(mf/defc ai-settings*
  {::mf/register modal/components
   ::mf/register-as :ai-settings}
  [{:keys [on-close]}]
  (let [cfg*   (mf/use-state nil)   ; the LlmConfigView as CLJS (keyword keys)
        saving* (mf/use-state false)
        ;; Relocated Selection tools (Figma #71): local tool state, kept out of
        ;; the design-generation busy/preview flags so the two never collide.
        tool-busy*   (mf/use-state false)
        tool-error*  (mf/use-state nil)
        tool-result* (mf/use-state nil)
        file    (mf/deref refs/file)
        file-id (some-> file :id str)
        close   (mf/use-fn
                 (fn []
                   (when (fn? on-close) (on-close))
                   (st/emit! (modal/hide))))]

    ;; Load config once on mount.
    (mf/with-effect
      []
      (-> (ai/invoke-get-config)
          (p/then (fn [res] (reset! cfg* (js->clj res :keywordize-keys true))))
          (p/catch (fn [e] (st/emit! (ntf/error (str e)))))))

    (let [cfg    (deref cfg*)
          saving  (deref saving*)
          upd     (mf/use-fn
                   (fn [k]
                     (fn [e]
                       (let [v (.. e -target -value)]
                         (reset! cfg* (assoc (deref cfg*) k
                                             (if (contains? #{:memory_enabled} k)
                                               (.. e -target -checked)
                                               v)))))))
          save    (mf/use-fn
                   (mf/deps cfg)
                   (fn []
                     (reset! saving* true)
                     (let [payload {:provider              (:provider cfg "deepinfra")
                                   :mode                   (:mode cfg "auto")
                                   :deepinfra_api_key      (:deepinfra_api_key cfg "")
                                   :deepinfra_base         (:deepinfra_base cfg "")
                                   :deepinfra_glm_model    (:deepinfra_glm_model cfg "")
                                   :deepinfra_kimi_model   (:deepinfra_kimi_model cfg "")
                                   :ollama_url             (:ollama_url cfg "")
                                   :ollama_api_key          (:ollama_api_key cfg "")
                                   :ollama_glm_model        (:ollama_glm_model cfg "")
                                   :ollama_kimi_model       (:ollama_kimi_model cfg "")
                                   :firecrawl_api_key       (:firecrawl_api_key cfg "")
                                   :firecrawl_base          (:firecrawl_base cfg "")
                                   :ovion_cloud_endpoint   (:ovion_cloud_endpoint cfg "")
                                   :ovion_cloud_token       (:ovion_cloud_token cfg "")
                                   :timeout_secs            (safe-int (:timeout_secs cfg 240) 240)
                                   :memory_enabled          (boolean (:memory_enabled cfg true))
                                   :memory_max_turns        (safe-int (:memory_max_turns cfg 6) 6)}]
                       (-> (ai/invoke-set-config payload)
                           (p/then (fn [_]
                                     (reset! saving* false)
                                     (st/emit! (ntf/info (tr "workspace.ai.settings.saved")))
                                     (close)))
                           (p/catch (fn [e]
                                     (reset! saving* false)
                                     (st/emit! (ntf/error (str e)))))))))
          clear-mem (mf/use-fn
                     (mf/deps file-id)
                     (fn []
                       (-> (ai/invoke-clear-memory file-id)
                           (p/then (fn [_]
                                     (st/emit! (ntf/info (tr "workspace.ai.settings.memory-cleared")))))
                           (p/catch (fn [e] (st/emit! (ntf/error (str e))))))))

          ;; ── Relocated Selection tools ───────────────────────────────────
          ;; Reactive reads of the current selection + the shared update-sel
          ;; preference (refs/ai-update-sel, nil => default true).
          selected       (mf/deref refs/selected-shapes)
          objects        (mf/deref refs/workspace-page-objects)
          has-sel?       (boolean (seq selected))
          first-sel      (first selected)
          first-shape    (when first-sel (get objects first-sel))
          single-text?   (and (= (count selected) 1)
                              (= (:type first-shape) :text))
          update-sel-raw (mf/deref refs/ai-update-sel)
          update-sel?    (if (nil? update-sel-raw) true update-sel-raw)
          sel-name       (or (:name first-shape) "")

          on-toggle-sel
          (mf/use-fn
           (fn [e] (st/emit! (ai/set-ai-update-sel (.. e -target -checked)))))

          on-rename-with-ai
          (mf/use-fn
           (mf/deps first-sel first-shape)
           (fn []
             (if-not (and first-sel first-shape)
               (reset! tool-error* (tr "workspace.ai.bar.tool-select-shape"))
               (do
                 (reset! tool-busy* true)
                 (reset! tool-error* nil)
                 (reset! tool-result* nil)
                 (-> (ai/invoke-generate (ai/build-tool-request (ai/rename-prompt first-shape)))
                     (p/then
                      (fn [result]
                        (let [name (ai/extract-text-from-spec result)]
                          (reset! tool-busy* false)
                          (if (and (string? name) (not (str/empty? name)))
                            (do (st/emit! (dw/rename-shape-or-variant first-sel name))
                                (reset! tool-result* (tr "workspace.ai.bar.tool-renamed")))
                            (reset! tool-error* (tr "workspace.ai.bar.tool-no-result"))))))
                     (p/catch
                      (fn [e]
                        (reset! tool-busy* false)
                        (reset! tool-error* (str e)))))))))

          on-generate-text
          (mf/use-fn
           (mf/deps first-shape)
           (fn []
             (if-not first-shape
               (reset! tool-error* (tr "workspace.ai.bar.tool-select-shape"))
               (do
                 (reset! tool-busy* true)
                 (reset! tool-error* nil)
                 (reset! tool-result* nil)
                 (let [label (or (:content first-shape)
                                 (:name first-shape)
                                 "")]
                   (-> (ai/invoke-generate (ai/build-tool-request (ai/text-gen-prompt label)))
                       (p/then
                        (fn [result]
                          (let [copy (ai/extract-text-from-spec result)]
                            (reset! tool-busy* false)
                            (if (and (string? copy) (not (str/empty? copy)))
                              (do (some-> js/navigator .-clipboard (.writeText copy))
                                  (reset! tool-result*
                                          (tr "workspace.ai.bar.tool-text-copied" copy)))
                              (reset! tool-error* (tr "workspace.ai.bar.tool-no-result"))))))
                       (p/catch
                        (fn [e]
                          (reset! tool-busy* false)
                          (reset! tool-error* (str e))))))))))]

      [:div {:class "ais-overlay ais-root" :on-click close}
       [:div.ais-modal {:on-click #(.stopPropagation %)}
        [:div {:style #js {"display" "contents"}}
         (ad/base-style-block)
         [:style {:dangerouslySetInnerHTML #js {:__html settings-css}}]]
        [:div.ais-head
         [:span.ais-title (tr "workspace.ai.settings.title")]
         [:button.ais-close {:type "button" :on-click close
                            :on-mouse-enter aim/hov-white-in
                            :on-mouse-leave aim/hov-white-out
                            :on-mouse-down aim/press-white-in
                            :on-mouse-up aim/press-white-out}
          lucide-x]]
        [:div.ais-body
         ;; ── Selection tools (relocated from the AI bar) ─────────────────
         ;; Always available — they act on the canvas selection, independent
         ;; of the loaded LLM config. 'Update only the selection' is shared
         ;; with the AI bar via refs/ai-update-sel.
         [:div {:style #js {"display" "contents"}}
          [:div.ais-section (tr "workspace.ai.settings.section-selection")]
          [:label.ais-chk-row
           [:input.ais-chk {:type "checkbox"
                            :checked update-sel?
                            :on-change on-toggle-sel}]
           [:span.ais-label (tr "workspace.ai.bar.update-selection")]]
          (if has-sel?
            [:span.ais-sel-info
             (tr "workspace.ai.settings.selection-current"
                 (if (str/empty? sel-name) "(unnamed)" sel-name))]
            [:span.ais-sel-info (tr "workspace.ai.settings.selection-none")])
          [:div.ais-tools
           [:button.ais-tool
            {:type "button"
             :on-click on-rename-with-ai
             :disabled (or @tool-busy* (not has-sel?))
             :title (tr "workspace.ai.bar.tool-rename-tooltip")
             :on-mouse-enter aim/hov-white-in
             :on-mouse-leave aim/hov-white-out
             :on-mouse-down aim/press-white-in
             :on-mouse-up aim/press-white-out}
            (if @tool-busy* (spinner) lucide-pencil)
            (tr "workspace.ai.bar.tool-rename")]
           [:button.ais-tool
            {:type "button"
             :on-click on-generate-text
             :disabled (or @tool-busy* (not single-text?))
             :title (tr "workspace.ai.bar.tool-text-tooltip")
             :on-mouse-enter aim/hov-white-in
             :on-mouse-leave aim/hov-white-out
             :on-mouse-down aim/press-white-in
             :on-mouse-up aim/press-white-out}
            lucide-type
            (tr "workspace.ai.bar.tool-generate-text")]]
          [:div {:class (str "ais-status " (if @tool-error* "is-err" "is-ok"))}
           (or @tool-result* @tool-error*)]
          [:span.ais-hint (tr "workspace.ai.settings.selection-hint")]]

         (if (nil? cfg)
           [:span.ais-hint (tr "workspace.ai.settings.loading")]
           [:div {:style #js {"display" "contents"}}
            [:div.ais-section (tr "workspace.ai.settings.section-transport")]
            [:div.ais-row
             [:div.ais-field
              [:span.ais-label (tr "workspace.ai.settings.provider")]
              [:select.ais-input {:value (:provider cfg "deepinfra")
                                  :on-change (upd :provider)}
               [:option {:value "deepinfra"} "DeepInfra"]
               [:option {:value "ollama"} "Ollama"]
               [:option {:value "ovion-cloud"} (tr "workspace.ai.settings.provider-ovion-cloud")]]]
             [:div.ais-field
              [:span.ais-label (tr "workspace.ai.settings.mode")]
              [:select.ais-input {:value (:mode cfg "auto")
                                  :on-change (upd :mode)}
               [:option {:value "max"} (tr "workspace.ai.bar.mode-max")]
               [:option {:value "auto"} (tr "workspace.ai.bar.mode-auto")]]]]

            ;; Ovion Cloud (subscription, "coming soon"). Rendered only while
            ;; the Ovion Cloud provider is selected, so the BYO DeepInfra/Ollama
            ;; sections below stay fully working and unchanged.
            (when (= (:provider cfg) "ovion-cloud")
              [:div.ais-section (tr "workspace.ai.settings.section-ovion-cloud")]
              (field {:label     (tr "workspace.ai.settings.ovion-cloud-endpoint")
                      :value     (:ovion_cloud_endpoint cfg)
                      :on-change (upd :ovion_cloud_endpoint)})
              (field {:label     (tr "workspace.ai.settings.ovion-cloud-token")
                      :type      "password"
                      :placeholder (when (:ovion_cloud_token_set cfg)
                                     (tr "workspace.ai.settings.key-set"))
                      :on-change (upd :ovion_cloud_token)})
              [:span.ais-note (tr "workspace.ai.settings.ovion-cloud-note")])

            [:div.ais-section (tr "workspace.ai.settings.section-deepinfra")]
            (field {:label     (tr "workspace.ai.settings.deepinfra-glm")
                    :value     (:deepinfra_glm_model cfg)
                    :on-change (upd :deepinfra_glm_model)})
            (field {:label     (tr "workspace.ai.settings.deepinfra-kimi")
                    :value     (:deepinfra_kimi_model cfg)
                    :on-change (upd :deepinfra_kimi_model)})
            (field {:label     (tr "workspace.ai.settings.deepinfra-key")
                    :type      "password"
                    :placeholder (when (:deepinfra_api_key_set cfg)
                                   (tr "workspace.ai.settings.key-set"))
                    :on-change (upd :deepinfra_api_key)})
            (field {:label     (tr "workspace.ai.settings.deepinfra-base")
                    :value     (:deepinfra_base cfg)
                    :on-change (upd :deepinfra_base)})

            [:div.ais-section (tr "workspace.ai.settings.section-ollama")]
            (field {:label     (tr "workspace.ai.settings.ollama-url")
                    :value     (:ollama_url cfg)
                    :on-change (upd :ollama_url)})
            (field {:label     (tr "workspace.ai.settings.ollama-glm")
                    :value     (:ollama_glm_model cfg)
                    :on-change (upd :ollama_glm_model)})
            (field {:label     (tr "workspace.ai.settings.ollama-kimi")
                    :value     (:ollama_kimi_model cfg)
                    :on-change (upd :ollama_kimi_model)})
            (field {:label     (tr "workspace.ai.settings.ollama-key")
                    :type "password"
                    :placeholder (when (:ollama_api_key_set cfg)
                                   (tr "workspace.ai.settings.key-set"))
                    :on-change (upd :ollama_api_key)})

            [:div.ais-section (tr "workspace.ai.settings.section-url")]
            (field {:label     (tr "workspace.ai.settings.firecrawl-key")
                    :type "password"
                    :placeholder (when (:firecrawl_api_key_set cfg)
                                   (tr "workspace.ai.settings.key-set"))
                    :on-change (upd :firecrawl_api_key)})
            (field {:label     (tr "workspace.ai.settings.firecrawl-base")
                    :value     (:firecrawl_base cfg)
                    :on-change (upd :firecrawl_base)})

            [:div.ais-section (tr "workspace.ai.settings.section-memory")]
            [:label.ais-chk-row
             [:input.ais-chk {:type "checkbox"
                              :checked (boolean (:memory_enabled cfg true))
                              :on-change (upd :memory_enabled)}]
             [:span.ais-label (tr "workspace.ai.settings.memory-enabled")]]
            (field {:label     (tr "workspace.ai.settings.memory-turns")
                    :type "number"
                    :value (:memory_max_turns cfg 6)
                    :on-change (upd :memory_max_turns)})
            (field {:label (tr "workspace.ai.settings.timeout")
                    :type "number"
                    :value (:timeout_secs cfg 240)
                    :on-change (upd :timeout_secs)})
            ])]

        [:div.ais-foot
         [:button.ais-btn.ais-btn-ghost {:on-click clear-mem
                                         :on-mouse-enter aim/hov-white-in
                                         :on-mouse-leave aim/hov-white-out
                                         :on-mouse-down aim/press-white-in
                                         :on-mouse-up aim/press-white-out}
          (tr "workspace.ai.settings.clear-memory")]
         [:div {:style #js {"display" "flex" "gap" "10px"}}
          [:button.ais-btn.ais-btn-ghost {:on-click close :disabled saving
                                          :on-mouse-enter aim/hov-white-in
                                          :on-mouse-leave aim/hov-white-out
                                          :on-mouse-down aim/press-white-in
                                          :on-mouse-up aim/press-white-out}
           (tr "workspace.ai.settings.cancel")]
          [:button.ais-btn.ais-btn-primary {:on-click save :disabled (or saving (nil? cfg))
                                            :on-mouse-enter aim/hov-coral-in
                                            :on-mouse-leave aim/hov-coral-out
                                            :on-mouse-down aim/press-coral-in
                                            :on-mouse-up aim/press-coral-out}
           (tr "workspace.ai.settings.save")]]]]])))