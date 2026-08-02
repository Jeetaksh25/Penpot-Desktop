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
  new one."
  (:require
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.ai-gen :as ai]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.i18n :as i18n :refer [tr]]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(def ^:private settings-css
  "
.ais-overlay { position: fixed; inset: 0; background: rgba(15,23,42,0.55); z-index: 210;
  display: flex; align-items: center; justify-content: center; padding: 24px; }
.ais-modal { background: #fff; border-radius: 16px; width: min(560px, 100%);
  max-height: 88vh; display: flex; flex-direction: column; overflow: hidden;
  box-shadow: 0 24px 70px rgba(15,23,42,0.4); }
.ais-head { padding: 16px 20px; border-bottom: 1px solid #eef2f7;
  display: flex; align-items: center; justify-content: space-between; }
.ais-title { font-size: 15px; font-weight: 700; color: #0f172a; }
.ais-body { padding: 18px 20px; overflow: auto; flex: 1; display: flex; flex-direction: column; gap: 14px; }
.ais-foot { padding: 14px 20px; border-top: 1px solid #eef2f7; display: flex; gap: 10px; justify-content: space-between; }
.ais-field { display: flex; flex-direction: column; gap: 5px; }
.ais-label { font-size: 12px; font-weight: 600; color: #475569; }
.ais-hint { font-size: 11px; color: #94a3b8; }
.ais-input { border: 1px solid rgba(15,23,42,0.12); border-radius: 9px; padding: 8px 10px;
  font-size: 13px; color: #0f172a; background: #fff; outline: none; }
.ais-input:focus { border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99,102,241,0.15); }
.ais-row { display: flex; gap: 10px; }
.ais-row > * { flex: 1 1 0; }
.ais-section { font-size: 11px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase;
  color: #94a3b8; margin-top: 6px; }
.ais-btn { border: none; border-radius: 10px; padding: 9px 16px; font-size: 13.5px; font-weight: 700; cursor: pointer; }
.ais-btn-primary { background: #4f46e5; color: #fff; }
.ais-btn-primary:hover { background: #4338ca; }
.ais-btn-ghost { background: transparent; color: #64748b; border: 1px solid rgba(15,23,42,0.12); }
.ais-btn-ghost:hover { background: #f8fafc; }
.ais-x { border: none; background: transparent; color: #94a3b8; font-size: 20px; cursor: pointer; line-height: 1; }
")

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

(mf/defc ai-settings*
  [{:keys [on-close]}]
  (let [cfg*   (mf/use-state nil)   ; the LlmConfigView as CLJS (keyword keys)
        saving* (mf/use-state false)
        file    (mf/deref refs/file)
        file-id (some-> file :id str)]

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
                                               v))))))
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
                                   :timeout_secs            (safe-int (:timeout_secs cfg 240) 240)
                                   :memory_enabled          (boolean (:memory_enabled cfg true))
                                   :memory_max_turns        (safe-int (:memory_max_turns cfg 6) 6)}]
                       (-> (ai/invoke-set-config payload)
                           (p/then (fn [_]
                                     (reset! saving* false)
                                     (st/emit! (ntf/info (tr "workspace.ai.settings.saved")))
                                     (when on-close (on-close))))
                           (p/catch (fn [e]
                                     (reset! saving* false)
                                     (st/emit! (ntf/error (str e)))))))))
          clear-mem (mf/use-fn
                     (mf/deps file-id)
                     (fn []
                       (-> (ai/invoke-clear-memory file-id)
                           (p/then (fn [_]
                                     (st/emit! (ntf/info (tr "workspace.ai.settings.memory-cleared")))))
                           (p/catch (fn [e] (st/emit! (ntf/error (str e))))))))]

      [:div.ais-overlay {:on-click on-close}
       [:div.ais-modal {:on-click #(.stopPropagation %)}
        [:style {:dangerouslySetInnerHTML #js {:__html settings-css}}]
        [:div.ais-head
         [:span.ais-title (tr "workspace.ai.settings.title")]
         [:button.ais-x {:on-click on-close} "×"]]
        [:div.ais-body
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
               [:option {:value "ollama"} "Ollama"]]]
             [:div.ais-field
              [:span.ais-label (tr "workspace.ai.settings.mode")]
              [:select.ais-input {:value (:mode cfg "auto")
                                  :on-change (upd :mode)}
               [:option {:value "max"} (tr "workspace.ai.bar.mode-max")]
               [:option {:value "auto"} (tr "workspace.ai.bar.mode-auto")]]]]

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
            [:label.ais-field
             [:input {:type "checkbox"
                      :style #js {"marginRight" "6px"}
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
            ]])]

        [:div.ais-foot
         [:button.ais-btn.ais-btn-ghost {:on-click clear-mem}
          (tr "workspace.ai.settings.clear-memory")]
         [:div {:style #js {"display" "flex" "gap" "10px"}}
          [:button.ais-btn.ais-btn-ghost {:on-click on-close :disabled saving}
           (tr "workspace.ai.settings.cancel")]
          [:button.ais-btn.ais-btn-primary {:on-click save :disabled (or saving (nil? cfg))}
           (tr "workspace.ai.settings.save")]]]])]))