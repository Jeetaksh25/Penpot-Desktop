;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL
;;
;; Ovion design-agent: PURE message-shaping helpers + the system prompts.
;;
;; The loop itself lives in `ai_gen.cljs` (`run-agent-design`) because it needs
;; the IPC wrappers, the potok state events (`set-ai-*`) and the `gen-id`
;; guard — all owned by `ai_gen`. Keeping the deps one-way (`ai_gen` → this ns,
;; this ns → nothing in `ai_gen`) avoids a circular require. This file has no
;; side effects and requires nothing from the app's runtime, so it is trivially
;; safe under no-build static verification.

(ns app.main.data.workspace.ai-agent)

;; Hard cap on tool-calling iterations. Each step is one model call; 8 is
;; enough for a focused edit chain (observe → act → observe → act → …) without
;; runaway cost. The loop also terminates earlier on a spec, a plain-text
;; "done", an error, or cancellation.
(def ^:private max-agent-steps 8)

(defn max-steps [] max-agent-steps)

;; ── System prompts ────────────────────────────────────────────────────────────

(def ^:private design-agent-system
  "You are Ovion's NATIVE design agent, embedded inside the design tool. You
can see the live scene (provided as a structured snapshot) and you ACT on it
by calling tools. You are not a chatbot — you manipulate the canvas directly.

WHAT YOU CAN DO
  You have the same tools the user has: create/update/delete/duplicate shapes,
  set fills/strokes/shadow/blur/radius/opacity/rotation, set text + typography,
  rename, flex/grid layout + child props + grid tracks/cells, components +
  variants + interactions + flows, select/visibility/lock, group/ungroup/mask,
  z-order, align/distribute, tidy, focus mode, clipboard (copy css/svg/props/
  image, paste), pages (duplicate/delete/navigate), rename file, export, AND
  read tools (get_scene, get_selection) to observe the canvas after edits.

HOW TO WORK
  1. Read the LIVE SCENE snapshot in the first user message. Shape ids there
     are REAL — pass them verbatim to tools that need an id.
  2. For small, targeted edits, CALL TOOLS. Each tool call mutates the canvas
     immediately. After a batch of mutations, call get_scene to observe the new
     state (new shapes get new ids you can chain on).
  3. For a fresh board / large composition, emit a DesignSpec instead (the host
     applies it as one undo transaction and previews it for the user).
  4. Prefer the most specific tool (set_fill, set_radius, set_text) over the
     generic update_shape when it fits.
  5. Act, then VERIFY with get_scene before declaring done. Never assume a
     mutation succeeded — confirm the new state.

REGION UPDATES (the 'magic line')
  When the user wants to update only a specific region/selection, the CURRENT
  SELECTION is included in the first user message. Target those ids. The host
  regenerates only inside that region.

CONSTRAINTS
  - One logical change per tool call batch; keep ids valid.
  - Do not invent ids that are not in the scene snapshot (unless you just
    created them in a prior step and re-read with get_scene).
  - Return a short plain-text summary when finished; call no more tools.
  - If a tool returns {ok:false}, read the error, adjust, and retry or report.")

(def ^:private scout-system
  "You are Ovion's vision scout. You are shown a screenshot of the design
canvas plus a structured scene snapshot and the user's request. Produce a
concise visual brief (5-12 lines) the design agent will use: describe the
layout, the dominant colors, the typography mood, the spatial structure, and
anything visually salient the structured snapshot does not capture (alignment,
whitespace rhythm, hierarchy, density). Do NOT call tools. Do NOT redesign —
only describe what is there so the next agent can ground its edits in what the
user actually sees.")

(defn design-system-prompt [] design-agent-system)
(defn scout-system-prompt [] scout-system)

;; ── Message shaping ───────────────────────────────────────────────────────────
;;
;; OpenAI chat-completions message shapes. The Rust `llm_agent_step` accepts
;; these as `ChatMessage` (role + content + optional tool_calls / tool_call_id).
;; content is sent as a string; the backend injects any attached images
;; per-provider, so CLJS only ever builds text content here.

(defn user-message
  "A user turn with plain text content."
  [text]
  {:role "user" :content (str text)})

(defn system-message
  [text]
  {:role "system" :content (str text)})

(defn assistant-message
  "Echo the model's last step back as an assistant turn so the next step has a
  well-formed message history. `step-result` is the keywordized AgentStepResponse
  from `llm_agent_step`: it carries :tool_calls (a vector of
  {:id :function {:name :arguments}}) and/or :text."
  [step-result]
  (let [text (or (:text step-result) "")
        msg {:role "assistant" :content text}]
    (if (seq (:tool_calls step-result))
      (assoc msg :tool_calls (:tool_calls step-result))
      msg)))

(defn tool-result-message
  "A tool-result turn. `tool-call-id` ties it to the assistant's tool_call.
  `result` is a plain CLJS map; it is JSON-stringified so the model sees a
  structured object."
  [tool-call-id result]
  {:role "tool"
   :tool_call_id tool-call-id
   :content (js/JSON.stringify (clj->js result))})

(defn append-tool-results
  "Given the current messages, the assistant step that requested tool calls,
  and the executed results (a vector of {:id :result}), return the new
  messages: prior + assistant-turn + one tool-result per call, in order."
  [messages step-result results]
  (let [asst (assistant-message step-result)
        tool-msgs (mapv (fn [{:keys [id result]}]
                          (tool-result-message id result))
                        results)]
    (into (conj (vec messages) asst) tool-msgs)))