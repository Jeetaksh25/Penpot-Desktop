;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.comments :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.ai-design :as ad]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [clojure.math :refer [floor]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:comments-local-options
  (l/derived :options refs/comments-local))

(def mentions-context (mf/create-context nil))
(def r-mentions-split #"@\[[^\]]*\]\([^\)]*\)")
(def r-mentions #"@\[([^\]]*)\]\(([^\)]*)\)")
(def r-url-split #"https?://[^\s\)\]]+[^\s\)\]\.,;:!?]")
(def zero-width-space \u200B)

(defn- parse-urls
  "Split a text element into text and url sub-elements"
  [element]
  (if (= (:type element) :text)
    (let [text (:content element)
          parts (str/split text r-url-split)
          urls (re-seq r-url-split text)]
      (d/interleave-all
       (map #(hash-map :type :text :content %) parts)
       (map #(hash-map :type :url :content %) urls)))
    [element]))

(defn- parse-comment
  "Parse a comment into its elements (texts, mentions and urls)"
  [comment]
  (->> (d/interleave-all
        (->> (str/split comment r-mentions-split)
             (map #(hash-map :type :text :content %)))

        (->> (re-seq r-mentions comment)
             (map (fn [[_ user id]]
                    {:type :mention
                     :content user
                     :data {:id id}}))))
       (mapcat parse-urls)))

(defn- parse-nodes
  "Parse the nodes to format a comment"
  [node]
  (->> (dom/get-children node)
       (map
        (fn [node]
          (cond
            (and (instance? js/HTMLElement node) (dom/get-data node "user-id"))
            (str/ffmt "@[%](%)" (.-textContent node) (dom/get-data node "user-id"))

            :else
            (.-textContent node))))
       (str/join "")))

(defn- create-text-node
  "Creates a text-only node"
  ([]
   (create-text-node ""))
  ([text]
   (-> (dom/create-element "span")
       (dom/set-data! "type" "text")
       (dom/set-html! (if (empty? text) zero-width-space (dom/escape-html text))))))

(defn- create-mention-node
  "Creates a mention node"
  [id fullname]
  (-> (dom/create-element "span")
      (dom/set-data! "type" "mention")
      (dom/set-data! "user-id" (dm/str id))
      (dom/set-data! "fullname" fullname)
      (obj/set! "textContent" fullname)))

(defn- current-text-node*
  "Retrieves the text node and the offset that the cursor is positioned on"
  [node anchor-node]
  (when (.contains node anchor-node)
    (let [span-node (if (instance? js/Text anchor-node)
                      (dom/get-parent anchor-node)
                      anchor-node)
          container (dom/get-parent span-node)]
      (when (= node container)
        span-node))))

(defn- current-text-node
  "Retrieves the text node and the offset that the cursor is positioned on"
  [node]
  (assert (some? node) "expected valid node")

  (when-let [selection (wapi/get-selection)]
    (let [range       (wapi/get-range selection 0)
          anchor-node (wapi/range-start-container range)
          offset      (wapi/range-start-offset range)
          span-node   (current-text-node* node anchor-node)]
      (when span-node
        [span-node offset]))))

(defn- absolute-offset
  [node child offset]
  (loop [nodes (seq (dom/get-children node))
         acc 0]
    (if-let [head (first nodes)]
      (if (= head child)
        (+ acc offset)
        (recur (rest nodes) (+ acc (.-length (.-textContent head)))))
      nil)))

(defn- get-prev-node
  [parent node]
  (->> (d/with-prev (dom/get-children parent))
       (d/seek (fn [[it _]] (= node it)))
       (second)))

(defn- blank-content?
  [content]
  (let [content (str/trim content)]
    (or (str/blank? content)
        (str/empty? content)
        (and (= (count content) 1)
             (= (first content) zero-width-space)))))

;; Component that renders the component content
(mf/defc comment-content*
  {::mf/private true}
  [{:keys [content]}]
  (let [comment-elements (mf/use-memo (mf/deps content) #(parse-comment content))]
    (for [[idx {:keys [type content]}] (d/enumerate comment-elements)]
      (if (= type :url)
        [:a {:key idx
             :href content
             :target "_blank"
             :rel "noopener noreferrer"
             :class (stl/css :comment-link)}
         content]
        [:span
         {:key idx
          :class (stl/css-case
                  :comment-text (= type :text)
                  :comment-mention (= type :mention))}
         content]))))

;; Input text for comments with mentions
(mf/defc comment-input*
  {::mf/private true}
  [{:keys [value placeholder autofocus on-focus on-blur on-change on-esc on-ctrl-enter]}]

  (let [value          (d/nilv value "")
        prev-value     (h/use-previous value)

        local-ref      (mf/use-ref nil)
        mentions-s     (mf/use-ctx mentions-context)
        cur-mention    (mf/use-var nil)

        prev-selection-ref
        (mf/use-ref)

        init-input
        (mf/use-fn
         (fn [node]
           (mf/set-ref-val! local-ref node)
           (when node
             (doseq [{:keys [type content data]} (parse-comment value)]
               (case type
                 :text     (dom/append-child! node (create-text-node content))
                 :url      (dom/append-child! node (create-text-node content))
                 :mention  (dom/append-child! node (create-mention-node (:id data) content))
                 nil)))))

        handle-input
        (mf/use-fn
         (mf/deps on-change)
         (fn []
           (let [node     (mf/ref-val local-ref)
                 children (dom/get-children node)]

             (doseq [^js child-node children]
               ;; Remove nodes that are not span. This can happen if the user copy/pastes
               (when (not= (.-tagName child-node) "SPAN")
                 (.remove child-node))

               ;; If a node is empty we set the content to "empty"
               (when (and (= (dom/get-data child-node "type") "text")
                          (empty? (dom/get-text child-node)))
                 (dom/set-html! child-node zero-width-space))

               ;; Remove mentions that have been modified
               (when (and (= (dom/get-data child-node "type") "mention")
                          (not= (dom/get-data child-node "fullname")
                                (dom/get-text child-node)))
                 (.remove child-node)))

             ;; If there are no nodes we need to create an empty node
             (when (= 0 (.-length children))
               (dom/append-child! node (create-text-node)))

             (let [new-input (parse-nodes node)]
               (when on-change
                 (on-change new-input))))))

        handle-select
        (mf/use-fn
         (fn []
           (when-let [node (mf/ref-val local-ref)]
             (when-let [selection (wapi/get-selection)]
               (let [range       (wapi/get-range selection 0)
                     anchor-node (wapi/range-start-container range)
                     offset      (wapi/range-start-offset range)]

                 (when (and (= node anchor-node) (.-collapsed ^js range))
                   (wapi/set-cursor-after! anchor-node))

                 (when-let [span-node (current-text-node* node anchor-node)]
                   (let [[prev-span prev-offset]
                         (mf/ref-val prev-selection-ref)

                         node-text
                         (subs (dom/get-text span-node) 0 offset)

                         current-at-symbol
                         (str/last-index-of (subs node-text 0 offset) "@")

                         mention-text
                         (subs node-text current-at-symbol)

                         at-symbol-inside-word?
                         (and (> current-at-symbol 0)
                              (str/word? (str/slice node-text (- current-at-symbol 1) current-at-symbol)))]

                     (mf/set-ref-val! prev-selection-ref #js [span-node offset])

                     (when (= (dom/get-data span-node "type") "mention")
                       (let [from-offset (absolute-offset node prev-span prev-offset)
                             to-offset   (absolute-offset node span-node offset)

                             [_ prev next]
                             (->> node
                                  (dom/seq-nodes)
                                  (d/with-prev-next)
                                  (filter (fn [[elem _ _]] (= elem span-node)))
                                  (first))]
                         (if (> from-offset to-offset)
                           (wapi/set-cursor-after! prev)
                           (wapi/set-cursor-before! next))))

                     (if (and (not at-symbol-inside-word?)
                              (re-matches #"@\w*" mention-text))
                       (do
                         (reset! cur-mention mention-text)
                         (rx/push! mentions-s {:type :display-mentions})
                         (let [mention (subs mention-text 1)]
                           (when (d/not-empty? mention)
                             (rx/push! mentions-s {:type :filter-mentions :data mention}))))
                       (do
                         (reset! cur-mention nil)
                         (rx/push! mentions-s {:type :hide-mentions}))))))))))

        handle-focus
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (dom/set-css-property! (mf/ref-val local-ref) "--placeholder" "")
           (when on-focus
             (on-focus event))))

        handle-blur
        (mf/use-fn
         (mf/deps value)
         (fn [event]
           (when (empty? value)
             (let [node (mf/ref-val local-ref)]
               (dom/set-css-property! node "--placeholder" (dm/str "\"" placeholder "\""))))

           (when on-blur
             (on-blur event))))

        handle-insert-mention
        (mf/use-fn
         (mf/deps on-change)
         (fn [data]
           (when-let [node (mf/ref-val local-ref)]
             (when-let [[span-node offset] (current-text-node node)]
               (let [node-text
                     (dom/get-text span-node)

                     current-at-symbol
                     (or (str/last-index-of (subs node-text 0 offset) "@")
                         (absolute-offset node span-node offset))

                     mention
                     (re-find #"@\w*" (subs node-text current-at-symbol))

                     prefix
                     (subs node-text 0 current-at-symbol)

                     suffix
                     (subs node-text (+ current-at-symbol (count mention)))

                     mention-span (create-mention-node (-> data :user :id) (-> data :user :fullname))
                     after-span (create-text-node (dm/str " " suffix))
                     sel (wapi/get-selection)]

                 (dom/set-html! span-node (if (empty? prefix) zero-width-space (dom/escape-html prefix)))
                 (dom/insert-after! node span-node mention-span)
                 (dom/insert-after! node mention-span after-span)
                 (wapi/set-cursor-after! after-span)
                 (wapi/collapse-end! sel)

                 (when (fn? on-change)
                   (on-change (parse-nodes node))))))))

        handle-insert-at-symbol
        (mf/use-fn
         (fn []
           (when-let [node (mf/ref-val local-ref)]
             (when-let [[span-node] (current-text-node node)]
               (let [node-text (dom/get-text span-node)
                     at-symbol (if (blank-content? node-text) "@" " @")]

                 (dom/set-html! span-node (str/concat (dom/escape-html node-text) at-symbol))
                 (wapi/set-cursor-after! span-node))))))

        handle-key-down
        (mf/use-fn
         (mf/deps on-esc on-ctrl-enter handle-select handle-input)
         (fn [event]
           (handle-select event)
           (when-let [node (mf/ref-val local-ref)]
             (when-let [[span-node offset] (current-text-node node)]
               (cond
                 (and @cur-mention (kbd/enter? event))
                 (do (dom/prevent-default event)
                     (dom/stop-propagation event)
                     (rx/push! mentions-s {:type :insert-selected-mention}))

                 (and @cur-mention (kbd/down-arrow? event))
                 (do (dom/prevent-default event)
                     (dom/stop-propagation event)
                     (rx/push! mentions-s {:type :insert-next-mention}))

                 (and @cur-mention (kbd/up-arrow? event))
                 (do (dom/prevent-default event)
                     (dom/stop-propagation event)
                     (rx/push! mentions-s {:type :insert-prev-mention}))

                 (and @cur-mention (kbd/esc? event))
                 (do (dom/prevent-default event)
                     (dom/stop-propagation event)
                     (rx/push! mentions-s {:type :hide-mentions}))

                 (and (kbd/esc? event) (fn? on-esc))
                 (on-esc event)

                 (and (kbd/mod? event) (kbd/enter? event) (fn? on-ctrl-enter))
                 (on-ctrl-enter event)

                 (kbd/enter? event)
                 (let [sel (wapi/get-selection)
                       range (.getRangeAt sel 0)]
                   (dom/prevent-default event)
                   (dom/stop-propagation event)
                   (let [[span-node offset] (current-text-node node)]
                     (.deleteContents range)
                     (handle-input)

                     (when span-node
                       (let [txt (.-textContent span-node)]
                         (dom/set-html! span-node (dm/str (dom/escape-html (subs txt 0 offset)) "\n" zero-width-space (dom/escape-html (subs txt offset))))
                         (wapi/set-cursor! span-node (inc offset))
                         (handle-input)))))

                 (kbd/backspace? event)
                 (let [prev-node (get-prev-node node span-node)]
                   (when (and (some? prev-node)
                              (= "mention" (dom/get-data prev-node "type"))
                              (= offset 1))
                     (dom/prevent-default event)
                     (dom/stop-propagation event)
                     (.remove prev-node))))))))]

    (mf/with-layout-effect [autofocus]
      (when ^boolean autofocus
        (dom/focus! (mf/ref-val local-ref))))

    ;; Creates the handlers for selection
    (mf/with-effect [handle-select]
      (let [handle-select* handle-select]
        (js/document.addEventListener "selectionchange" handle-select*)
        #(js/document.removeEventListener "selectionchange" handle-select*)))

    ;; Effect to communicate with the mentions panel
    (mf/with-effect []
      (when mentions-s
        (->> mentions-s
             (rx/subs!
              (fn [{:keys [type data]}]
                (case type
                  :insert-mention
                  (handle-insert-mention data)
                  :insert-at-symbol
                  (handle-insert-at-symbol)

                  nil))))))

    ;; Auto resize input to display the comment
    (mf/with-layout-effect nil
      (when-let [^js node (mf/ref-val local-ref)]
        (set! (.-height (.-style node)) "0")
        (set! (.-height (.-style node)) (str (+ 2 (.-scrollHeight node)) "px"))))

    (mf/with-effect [value prev-value]
      (when-let [node (mf/ref-val local-ref)]
        (cond
          (and (d/not-empty? prev-value) (empty? value))
          (do (dom/set-html! node "")
              (dom/append-child! node (create-text-node))
              (dom/set-css-property! node "--placeholder" "")
              (dom/focus! node))

          (and (some? node) (empty? value) (not (dom/focus? node)))
          (dom/set-css-property! node "--placeholder" (dm/str "\"" placeholder "\""))

          (some? node)
          (dom/set-css-property! node "--placeholder" ""))))

    [:div
     {:role "textbox"
      :class (stl/css :comment-input)
      :content-editable "true"
      :suppress-content-editable-warning true
      :on-input handle-input
      :ref init-input
      :on-key-down handle-key-down
      :on-focus handle-focus
      :on-blur handle-blur}]))

(mf/defc mentions-panel*
  []
  (let [mentions-s (mf/use-ctx mentions-context)

        team       (mf/deref refs/team)
        members    (:members team)

        state*
        (mf/use-state
         #(do {:display false
               :mention-filter ""
               :selected 0}))

        {:keys [display mention-filter selected]}
        (deref state*)

        mentions-users
        (mf/with-memo [mention-filter members]
          (->> members
               (filter (fn [{:keys [fullname email]}]
                         (or (not mention-filter)
                             (empty? mention-filter)
                             (str/includes? (str/lower fullname) (str/lower mention-filter))
                             (str/includes? (str/lower email) (str/lower mention-filter)))))
               (take 4)
               (into [])))

        selected
        (mth/clamp selected 0 (dec (count mentions-users)))

        handle-click-mention
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (let [id (-> (dom/get-current-target event)
                        (dom/get-data "user-id")
                        (uuid/parse))

                 user   (d/seek #(= (:id %) id) members)]

             (rx/push! mentions-s {:type :insert-mention
                                   :data {:user user}}))))]

    (mf/with-effect [mentions-users selected]
      (let [sub
            (->> mentions-s
                 (rx/subs!
                  (fn [{:keys [type data]}]
                    (case type
                      ;; Display the mentions dialog
                      :display-mentions
                      (swap! state* assoc :display true)

                      ;; Hide mentions
                      :hide-mentions
                      (swap! state* assoc :display false :mention-filter "")

                      ;; Filter the metions by some characters
                      :filter-mentions
                      (swap! state* assoc :mention-filter data)

                      :insert-selected-mention
                      (rx/push! mentions-s {:type :insert-mention
                                            :data {:user (get mentions-users selected)}})

                      :insert-next-mention
                      (swap! state* update :selected #(mth/clamp (inc %) 0 (dec (count mentions-users))))

                      :insert-prev-mention
                      (swap! state* update :selected #(mth/clamp (dec %) 0 (dec (count mentions-users))))

                      ;;
                      nil))))]
        #(rx/dispose! sub)))

    (when ^boolean display
      [:div {:class (stl/css :comments-mentions-choice)}
       (if (empty? mentions-users)
         [:div {:class (stl/css :comments-mentions-empty)}
          (tr "comments.mentions.not-found" mention-filter)]

         (for [[idx {:keys [id fullname email] :as user}] (d/enumerate mentions-users)]
           [:div {:key id
                  :on-pointer-down handle-click-mention
                  :data-user-id (dm/str id)
                  :class (stl/css-case :comments-mentions-entry true
                                       :is-selected (= selected idx))}
            [:img {:class (stl/css :comments-mentions-avatar)
                   :src (cfg/resolve-profile-photo-url user)}]
            [:div {:class (stl/css :comments-mentions-name)} fullname]
            [:div {:class (stl/css :comments-mentions-email)} email]]))])))

(mf/defc mentions-button*
  {::mf/private true}
  []
  (let [mentions-s        (mf/use-ctx mentions-context)
        display-mentions* (mf/use-state false)

        handle-pointer-down
        (mf/use-fn
         (mf/deps @display-mentions*)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (if @display-mentions*
             (rx/push! mentions-s {:type :hide-mentions})
             (rx/push! mentions-s {:type :insert-at-symbol}))))

        handle-key-down
        (mf/use-fn
         (mf/deps @display-mentions*)
         (fn [event]
           (when (or (kbd/enter? event) (kbd/space? event))
             (handle-pointer-down event))))]

    (mf/use-effect
     (fn []
       (let [sub
             (rx/subs!
              (fn [{:keys [type _]}]
                (case type
                  :display-mentions (reset! display-mentions* true)
                  :hide-mentions    (reset! display-mentions* false)
                  nil))
              mentions-s)]
         #(rx/dispose! sub))))

    [:> icon-button*
     {:variant "ghost"
      :aria-label (tr "labels.mention")
      :on-pointer-down handle-pointer-down
      :on-key-down handle-key-down
      :icon-class (stl/css-case :open-mentions-button true
                                :is-toggled @display-mentions*)
      :icon i/at}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; P2.40 — Visual comment attachments (image / emoji / sketch)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Self-contained: Lucide glyphs inlined (stroke-width 2, currentColor),
;; styling injected via a <style> block (no scss-pipeline dependency, so it
;; compiles without the build-generated .css.json that `stl/css` needs).
;; Coral #f28b82 accent, grey #7d7d7d at rest — matches the AI surfaces.
;; Reduced motion is honored via a `prefers-reduced-motion` media query.
;; A comment with no attachments renders EXACTLY as before: the bar is only
;; mounted inside the compose forms, and the per-bubble render component is
;; only mounted when `(seq (:attachments comment))` is true.

;; ── Lucide icons (one family, stroke-width 2, currentColor) ──────────────────

(defn- li
  "Wrap a seq of SVG children in a Lucide 24×24 icon frame — matches the
  ai_bar.cljs idiom. Returns a real React element (via `ad/icon-el`), not
  a hiccup vector — a bare vector as a React child throws Minified #31."
  [body]
  (ad/icon-el
   (into [:svg {:viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
                :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"
                :aria-hidden "true"}]
         body)))

(def ^:private lucide-smile
  (li [[:circle {:cx 12 :cy 12 :r 10}]
       [:path {:d "M8 14s1.5 2 4 2 4-2 4-2"}]
       [:line {:x1 9 :y1 9 :x2 9.01 :y2 9}]
       [:line {:x1 15 :y1 9 :x2 15.01 :y2 9}]]))

(def ^:private lucide-image
  (li [[:rect {:x 3 :y 3 :width 18 :height 18 :rx 2 :ry 2}]
       [:circle {:cx 9 :cy 9 :r 2}]
       [:path {:d "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"}]]))

(def ^:private lucide-pencil
  (li [[:path {:d "M12 20h9"}]
       [:path {:d "M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"}]]))

(def ^:private lucide-x
  (li [[:path {:d "M18 6 6 18"}]
       [:path {:d "m6 6 12 12"}]]))

(def ^:private lucide-trash
  (li [[:path {:d "M3 6h18"}]
       [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]]))

;; ── Curated emoji set (no dependency, plain unicode) ─────────────────────────

(def ^:private emoji-set
  ["😀" "😍" "😂" "👍" "🎉" "🔥" "❤️" "👏"
   "🤔" "👀" "✅" "❌" "⭐" "💡" "🚀" "🎯"])

;; ── Inline styles ─────────────────────────────────────────────────────────────

(def ^:private attachment-css
  ".cmt-att-bar { display:flex; align-items:center; gap:4px; padding:6px 0 0; flex-wrap:wrap; position:relative; }
.cmt-att-btn { display:inline-flex; align-items:center; justify-content:center; width:28px; height:28px; border:none; border-radius:6px; background:transparent; color:#7d7d7d; cursor:pointer; transition:background .15s, color .15s; padding:0; }
.cmt-att-btn:hover { background:rgba(242,139,130,0.12); color:#f28b82; }
.cmt-att-btn.is-active { color:#f28b82; background:rgba(242,139,130,0.14); }
.cmt-att-btn svg { width:16px; height:16px; display:block; }
.cmt-att-pop { position:absolute; bottom:100%; left:0; margin-bottom:6px; background:#fff; border:1px solid #e6e6e6; border-radius:8px; box-shadow:0 4px 16px rgba(0,0,0,0.12); padding:8px; display:grid; grid-template-columns:repeat(8,1fr); gap:4px; z-index:20; }
.cmt-att-emoji { font-size:18px; line-height:1; padding:4px; border:none; background:transparent; cursor:pointer; border-radius:4px; }
.cmt-att-emoji:hover { background:rgba(242,139,130,0.14); }
.cmt-att-chips { display:flex; flex-wrap:wrap; gap:6px; padding:6px 0 0; width:100%; }
.cmt-att-chip { position:relative; display:inline-flex; align-items:center; gap:4px; padding:4px 6px; border-radius:6px; background:#f4f4f4; font-size:13px; color:#444; max-width:200px; }
.cmt-att-chip img { width:32px; height:32px; object-fit:cover; border-radius:4px; display:block; }
.cmt-att-chip-sketch { width:40px; height:32px; display:block; }
.cmt-att-chip-sketch path { stroke:#f28b82; fill:none; stroke-width:2; stroke-linecap:round; stroke-linejoin:round; }
.cmt-att-chip-emoji { font-size:18px; line-height:1; }
.cmt-att-rm { display:inline-flex; align-items:center; justify-content:center; width:16px; height:16px; border:none; background:transparent; color:#7d7d7d; cursor:pointer; border-radius:50%; padding:0; }
.cmt-att-rm:hover { color:#f28b82; background:rgba(242,139,130,0.14); }
.cmt-att-rm svg { width:12px; height:12px; display:block; }
.cmt-att-render { display:flex; flex-wrap:wrap; gap:8px; padding-top:6px; }
.cmt-att-thumb { width:120px; height:auto; max-height:160px; object-fit:cover; border-radius:6px; cursor:zoom-in; border:1px solid #e6e6e6; transition:border-color .15s; display:block; }
.cmt-att-thumb:hover { border-color:#f28b82; }
.cmt-att-emoji-inline { font-size:20px; line-height:1; }
.cmt-att-sketch-inline { width:120px; height:80px; border:1px solid #e6e6e6; border-radius:6px; background:#fafafa; display:block; }
.cmt-att-sketch-inline path { stroke:#f28b82; fill:none; stroke-width:2; stroke-linecap:round; stroke-linejoin:round; }
.cmt-att-sketch-overlay { position:fixed; inset:0; background:rgba(0,0,0,0.45); display:flex; align-items:center; justify-content:center; z-index:1000; }
.cmt-att-sketch-panel { background:#fff; border-radius:10px; padding:14px; box-shadow:0 8px 32px rgba(0,0,0,0.25); display:flex; flex-direction:column; gap:10px; }
.cmt-att-sketch-title { font-size:13px; color:#333; font-weight:600; }
.cmt-att-sketch-canvas { border:1px solid #e6e6e6; border-radius:6px; background:#fff; cursor:crosshair; touch-action:none; display:block; }
.cmt-att-sketch-actions { display:flex; justify-content:flex-end; gap:8px; }
.cmt-att-lightbox { position:fixed; inset:0; background:rgba(0,0,0,0.78); display:flex; align-items:center; justify-content:center; z-index:1001; padding:24px; box-sizing:border-box; }
.cmt-att-lightbox-img { max-width:90vw; max-height:88vh; border:3px solid #f28b82; border-radius:6px; box-shadow:0 8px 40px rgba(0,0,0,0.5); display:block; }
.cmt-att-lightbox-close { position:fixed; top:18px; right:18px; width:36px; height:36px; border:none; border-radius:50%; background:rgba(255,255,255,0.12); color:#fff; cursor:pointer; display:flex; align-items:center; justify-content:center; }
.cmt-att-lightbox-close:hover { background:rgba(242,139,130,0.5); }
.cmt-att-lightbox-close svg { width:18px; height:18px; display:block; }
.cmt-att-lightbox-cap { position:fixed; bottom:18px; left:50%; transform:translateX(-50%); color:#fff; font-size:13px; background:rgba(0,0,0,0.4); padding:6px 12px; border-radius:6px; max-width:80vw; text-align:center; }
@media (prefers-reduced-motion: reduce) {
  .cmt-att-btn, .cmt-att-thumb, .cmt-att-rm, .cmt-att-lightbox-img { transition:none !important; }
}")

(mf/defc attachment-style-block*
  {::mf/private true}
  []
  [:style {:dangerouslySetInnerHTML #js {:__html attachment-css}}])

;; ── Helpers ───────────────────────────────────────────────────────────────────

(defn- strokes->svg-path
  "Serialize a vector of strokes (each a vector of {:x :y} points) into a
  single SVG path `d` string: `M x1 y1 L x2 y2 L x3 y3 M x4 y4 ...`."
  [strokes]
  (let [coords (fn [p] (dm/str (:x p) " " (:y p)))
        stroke->d
        (fn [stroke]
          (let [head (first stroke)
                tail (rest stroke)]
            (dm/str "M " (coords head)
                    (apply str (map #(dm/str " L " (coords %)) tail)))))]
    (apply str (interpose " " (map stroke->d strokes)))))

(defn- remove-at
  "Return `v` without the element at index `idx` (non-destructive)."
  [v idx]
  (vec (concat (subvec v 0 idx) (subvec v (inc idx)))))

;; ── Sketch overlay (minimal freehand canvas → SVG path) ───────────────────────

(mf/defc comment-sketch-overlay*
  {::mf/private true}
  [{:keys [on-confirm on-cancel]}]
  (let [canvas-ref (mf/use-ref nil)
        strokes*   (mf/use-state [])
        drawing*   (mf/use-ref false)
        w          280
        h          180

        get-pos
        (mf/use-fn
         (fn [e]
           (let [c    (mf/ref-val canvas-ref)
                 rect (.getBoundingClientRect ^js c)]
             {:x (- (.-clientX ^js e) (.-left ^js rect))
              :y (- (.-clientY ^js e) (.-top ^js rect))})))

        begin-stroke
        (mf/use-fn
         (fn [e]
           (dom/prevent-default e)
           (mf/set-ref-val! drawing* true)
           (let [p    (get-pos e)
                 c    (mf/ref-val canvas-ref)
                 ctx  (.getContext ^js c "2d")]
             (set! (.-strokeStyle ^js ctx) "#f28b82")
             (set! (.-lineWidth ^js ctx) 2)
             (set! (.-lineCap ^js ctx) "round")
             (set! (.-lineJoin ^js ctx) "round")
             (.beginPath ^js ctx)
             (.moveTo ^js ctx (:x p) (:y p))
             (swap! strokes* conj [p]))))

        extend-stroke
        (mf/use-fn
         (fn [e]
           (when (mf/ref-val drawing*)
             (let [p   (get-pos e)
                   c   (mf/ref-val canvas-ref)
                   ctx (.getContext ^js c "2d")]
               (.lineTo ^js ctx (:x p) (:y p))
               (.stroke ^js ctx)
               (swap! strokes* (fn [s]
                                 (let [n (count s)]
                                   (assoc s (dec n) (conj (s (dec n)) p)))))))))

        end-stroke
        (mf/use-fn
         (fn []
           (mf/set-ref-val! drawing* false)))

        on-clear
        (mf/use-fn
         (fn []
           (let [c   (mf/ref-val canvas-ref)
                 ctx (.getContext ^js c "2d")]
             (.clearRect ^js ctx 0 0 w h))
           (reset! strokes* [])))

        on-confirm*
        (mf/use-fn
         (mf/deps @strokes*)
         (fn []
           (let [d (strokes->svg-path @strokes*)]
             (on-confirm d w h))))

        on-cancel*
        (mf/use-fn
         (fn []
           (on-cancel)))

        on-backdrop
        (mf/use-fn
         (fn []
           (on-cancel)))]

    [:div.cmt-att-sketch-overlay {:on-click on-backdrop}
     [:div.cmt-att-sketch-panel {:on-click dom/stop-propagation}
      [:div.cmt-att-sketch-title (tr "labels.comment.sketch-title")]
      [:canvas.cmt-att-sketch-canvas
       {:ref canvas-ref
        :width w
        :height h
        :on-pointer-down begin-stroke
        :on-pointer-move extend-stroke
        :on-pointer-up end-stroke
        :on-pointer-leave end-stroke}]
      [:div.cmt-att-sketch-actions
       [:> button* {:variant "ghost" :type "button" :on-click on-clear}
        (tr "labels.clear")]
       [:> button* {:variant "ghost" :type "button" :on-click on-cancel*}
        (tr "ds.confirm-cancel")]
       [:> button* {:variant "primary" :type "button"
                    :on-click on-confirm* :disabled (empty? @strokes*)}
        (tr "labels.post")]]]]))

;; ── Attachment compose bar (emoji / image / sketch) ───────────────────────────

(mf/defc comment-attachments-bar*
  {::mf/private true}
  [{:keys [attachments on-change]}]
  (let [file-ref    (mf/use-ref nil)
        emoji-open* (mf/use-state false)
        sketch-open* (mf/use-state false)

        add-att
        (mf/use-fn
         (mf/deps on-change attachments)
         (fn [att]
           (on-change (conj attachments att))))

        remove-att
        (mf/use-fn
         (mf/deps on-change attachments)
         (fn [idx]
           (on-change (remove-at attachments idx))))

        open-file
        (mf/use-fn
         (fn []
           (when-let [n (mf/ref-val file-ref)]
             (.click ^js n))))

        on-file
        (mf/use-fn
         (mf/deps add-att)
         (fn [e]
           (let [file (first (array-seq (.. e -target -files)))]
             (when file
               ;; Mirrors wapi/read-file-as-data-url but inline so the
               ;; handler is self-contained (no rx subscription lifecycle).
               (let [reader (js/FileReader.)]
                 (set! (.-onload ^js reader)
                       (fn []
                         (add-att {:type :image :data (.-result ^js reader)})))
                 (.readAsDataURL ^js reader file))))
           (when-let [n (mf/ref-val file-ref)]
             (set! (.-value ^js n) ""))))

        on-toggle-emoji
        (mf/use-fn
         (mf/deps @emoji-open*)
         (fn [e]
           (dom/stop-propagation e)
           (dom/prevent-default e)
           (swap! emoji-open* not)))

        on-pick-emoji
        (mf/use-fn
         (mf/deps add-att)
         (fn [e char]
           (dom/stop-propagation e)
           (add-att {:type :emoji :data char})
           (reset! emoji-open* false)))

        on-open-sketch
        (mf/use-fn
         (fn []
           (reset! sketch-open* true)))

        on-sketch-confirm
        (mf/use-fn
         (mf/deps add-att)
         (fn [d w h]
           (add-att {:type :sketch :data d :meta {:w w :h h}})
           (reset! sketch-open* false)))

        on-sketch-cancel
        (mf/use-fn
         (fn []
           (reset! sketch-open* false)))]

    [:div.cmt-att-bar
     [:> attachment-style-block*]
     [:button.cmt-att-btn
      {:type "button"
       :aria-label (tr "labels.comment.add-emoji")
       :on-click on-toggle-emoji
       :class (when @emoji-open* "is-active")}
      lucide-smile]
     [:button.cmt-att-btn
      {:type "button"
       :aria-label (tr "labels.comment.add-image")
       :on-click open-file}
      lucide-image]
     [:button.cmt-att-btn
      {:type "button"
       :aria-label (tr "labels.comment.add-sketch")
       :on-click on-open-sketch}
      lucide-pencil]
     [:input {:type "file" :accept "image/*" :ref file-ref
              :style #js {"display" "none"} :on-change on-file}]
     (when @emoji-open*
       [:div.cmt-att-pop {:on-click dom/stop-propagation}
        (for [ch emoji-set]
          [:button.cmt-att-emoji
           {:key ch :type "button" :on-click #(on-pick-emoji % ch)}
           ch])])
     (when @sketch-open*
       [:> comment-sketch-overlay*
        {:on-confirm on-sketch-confirm :on-cancel on-sketch-cancel}])
     (when (seq attachments)
       [:div.cmt-att-chips
        (for [[idx att] (d/enumerate attachments)]
          [:div.cmt-att-chip {:key idx}
           (case (:type att)
             :image [:img {:src (:data att)}]
             :emoji [:span.cmt-att-chip-emoji (:data att)]
             :sketch [:svg.cmt-att-chip-sketch
                      {:viewBox (dm/str "0 0 " (:w (:meta att) 120) " " (:h (:meta att) 80))}
                      [:path {:d (:data att)}]]
             nil)
           [:button.cmt-att-rm
            {:type "button"
             :aria-label (tr "labels.remove")
             :on-click #(remove-att idx)}
            lucide-trash]])])]))

;; ── Attachment lightbox (coral-bordered, reduced-motion via CSS) ──────────────

(mf/defc attachment-lightbox*
  {::mf/private true}
  [{:keys [src caption on-close]}]
  (let [on-key
        (mf/use-fn
         (mf/deps on-close)
         (fn [e]
           (when (kbd/esc? e) (on-close))))]
    (mf/with-effect []
      (js/document.addEventListener "keydown" on-key)
      #(js/document.removeEventListener "keydown" on-key))
    [:div.cmt-att-lightbox {:on-click on-close}
     [:img.cmt-att-lightbox-img {:src src}]
     [:button.cmt-att-lightbox-close
      {:type "button" :aria-label (tr "labels.close") :on-click on-close}
      lucide-x]
     (when (d/not-empty? caption)
       [:div.cmt-att-lightbox-cap caption])]))

;; ── Attachment render (inside a comment bubble) ───────────────────────────────

(mf/defc comment-attachments-render*
  {::mf/private true}
  [{:keys [attachments]}]
  (let [lightbox* (mf/use-state nil)]
    [:*
     [:> attachment-style-block*]
     [:div.cmt-att-render
      (for [[idx att] (d/enumerate attachments)]
        (case (:type att)
          :image
          [:img.cmt-att-thumb
           {:key idx :src (:data att)
            :on-click #(reset! lightbox* att)}]
          :emoji
          [:span.cmt-att-emoji-inline {:key idx} (:data att)]
          :sketch
          (let [meta (:meta att)
                w    (:w meta 120)
                h    (:h meta 80)]
            [:svg.cmt-att-sketch-inline
             {:key idx :viewBox (dm/str "0 0 " w " " h)
              :preserveAspectRatio "xMidYMid meet"}
             [:path {:d (:data att)}]])
          nil))]
     (when-let [lb @lightbox*]
       [:> attachment-lightbox*
        {:src (:data lb)
         :caption (-> lb :meta :caption)
         :on-close #(reset! lightbox* nil)}])]))

(def ^:private schema:comment-avatar
  [:map
   [:class {:optional true} :string]
   [:image {:optional true} :string]
   [:variant {:optional true}
    [:maybe [:enum "read" "unread" "solved"]]]])

(mf/defc comment-avatar*
  {::mf/schema schema:comment-avatar}
  [{:keys [image variant class children] :rest props}]
  (let [variant (or variant "read")
        class   (dm/str class " " (stl/css-case :avatar true
                                                :avatar-read (= variant "read")
                                                :avatar-unread (= variant "unread")
                                                :avatar-solved (= variant "solved")))
        props   (mf/spread-props props {:class class})]

    [:> :div props
     (if image
       [:img {:src image
              :class (stl/css :avatar-image)}]
       [:div {:class (stl/css :avatar-text)} children])
     [:div {:class (stl/css-case :avatar-mask true
                                 :avatar-darken (= variant "solved"))}]]))

(mf/defc comment-info*
  {::mf/private true}
  [{:keys [item profile]}]
  [:*
   [:div {:class (stl/css :author)}
    [:> comment-avatar* {:image (cfg/resolve-profile-photo-url profile)
                         :class (stl/css :avatar-lg)
                         :variant (cond (:is-resolved item) "solved"
                                        (pos? (:count-unread-comments item)) "unread"
                                        :else "read")}]
    [:div {:class (stl/css :author-identity)}
     [:div {:class (stl/css :author-fullname)} (:fullname profile)]
     [:div {:class (stl/css :author-timeago)}
      (ct/timeago (:modified-at item))]]]

   [:div {:class (stl/css :item)}
    [:> comment-content* {:content (:content item)}]]

   [:div {:class (stl/css :replies)}
    (let [total-comments  (:count-comments item)
          unread-comments (:count-unread-comments item)
          total-replies   (dec total-comments)
          unread-replies  (if (= unread-comments total-comments)
                            (dec unread-comments)
                            unread-comments)]
      [:*
       (when (> total-replies 0)
         (if (= total-replies 1)
           [:span {:class (stl/css :replies-total)} (str total-replies " " (tr "labels.reply"))]
           [:span {:class (stl/css :replies-total)} (str total-replies " " (tr "labels.replies"))]))

       (when (and (> total-replies 0) (> unread-replies 0))
         (if (= unread-replies 1)
           [:span {:class (stl/css :replies-unread)} (str unread-replies " " (tr "labels.reply.new"))]
           [:span {:class (stl/css :replies-unread)} (str unread-replies " " (tr "labels.replies.new"))]))])]])

(mf/defc comment-form-buttons*
  {::mf/private true}
  [{:keys [on-submit on-cancel is-disabled]}]
  (let [handle-cancel
        (mf/use-fn
         (mf/deps on-cancel)
         (fn [event]
           (when (kbd/enter? event)
             (on-cancel))))

        handle-submit
        (mf/use-fn
         (mf/deps on-submit)
         (fn [event]
           (when (kbd/enter? event)
             (on-submit))))]

    [:div {:class (stl/css :form-buttons-wrapper)}
     [:> mentions-button*]
     (when (some? on-cancel)
       [:> button* {:variant "ghost"
                    :type "button"
                    :on-key-down handle-cancel
                    :on-click on-cancel}
        (tr "ds.confirm-cancel")])
     [:> button* {:variant "primary"
                  :type "button"
                  :on-key-down handle-submit
                  :on-click on-submit
                  :disabled is-disabled}
      (tr "labels.post")]]))

(defn- exceeds-length?
  [content]
  (> (count content) 750))

(mf/defc comment-reply-form*
  {::mf/private true}
  [{:keys [on-submit on-cancel]}]
  (let [content       (mf/use-state "")
        attachments   (mf/use-state [])

        disabled? (or (blank-content? @content)
                      (exceeds-length? @content))

        ;; Contexts without a global interrupt handler (e.g. the viewer) pass an
        ;; explicit cancel; otherwise fall back to the interrupt cycle.
        on-cancel
        (mf/use-fn
         (mf/deps on-cancel)
         (fn []
           (if (fn? on-cancel)
             (on-cancel)
             (st/emit! :interrupt))))

        on-change
        (mf/use-fn
         #(reset! content %))

        on-attachments-change
        (mf/use-fn
         #(reset! attachments %))

        on-submit*
        (mf/use-fn
         (mf/deps @content @attachments)
         (fn []
           (on-submit @content @attachments)
           (reset! content "")
           (reset! attachments [])))]

    [:div {:class (stl/css :form)}
     [:> comment-input*
      {:value @content
       :placeholder (tr "labels.reply.thread")
       :autofocus true
       :on-ctrl-enter on-submit*
       :on-change on-change}]
     [:> comment-attachments-bar*
      {:attachments @attachments :on-change on-attachments-change}]
     (when (exceeds-length? @content)
       [:div {:class (stl/css :error-text)}
        (tr "errors.character-limit-exceeded")])
     [:> comment-form-buttons* {:on-submit on-submit*
                                :on-cancel on-cancel
                                :is-disabled disabled?}]]))

(mf/defc comment-edit-form*
  {::mf/private true}
  [{:keys [content on-submit on-cancel]}]
  (let [content   (mf/use-state content)

        disabled? (or (blank-content? @content)
                      (exceeds-length? @content))

        on-change
        (mf/use-fn
         #(reset! content %))

        on-submit*
        (mf/use-fn
         (mf/deps @content)
         (fn [] (on-submit @content)))]

    [:div {:class (stl/css :form)}
     [:> comment-input*
      {:value @content
       :autofocus true
       :on-ctrl-enter on-submit*
       :on-change on-change}]
     (when (exceeds-length? @content)
       [:div {:class (stl/css :error-text)}
        (tr "errors.character-limit-exceeded")])
     [:> comment-form-buttons* {:on-submit on-submit*
                                :on-cancel on-cancel
                                :is-disabled disabled?}]]))
(defn- offset-position [position viewport zoom bubble-margin]
  (let [viewport (or viewport {:offset-x 0 :offset-y 0 :width 0 :height 0})
        base-x   (+ (* (:x position) zoom) (:offset-x viewport))
        base-y   (+ (* (:y position) zoom) (:offset-y viewport))

        x (:x position)
        y (:y position)

        w (:width viewport)
        h (:height viewport)

        comment-width 284 ;; TODO: this is the width set via CSS in an outer container…
        ;; We should probably do this in a different way.

        orientation-left? (>= (+ base-x comment-width (:x bubble-margin)) w)
        orientation-top?  (>= base-y (/ h 2))

        h-dir (if orientation-left? :left :right)
        v-dir (if orientation-top? :top :bottom)]
    {:x x :y y :h-dir h-dir :v-dir v-dir}))

(mf/defc comment-floating-thread-draft*
  [{:keys [draft zoom on-cancel on-submit position-modifier viewport]}]
  (let [profile   (mf/deref refs/profile)

        mentions-s (mf/use-memo #(rx/subject))

        position  (cond-> (:position draft)
                    (some? position-modifier)
                    (gpt/transform position-modifier))
        content   (:content draft)

        ;; P2.40 — attachments live as local compose state; they are only
        ;; merged into the draft at submit time, and only when non-empty, so
        ;; a draft with no attachments is byte-identical to pre-P2.40.
        attachments* (mf/use-state [])

        ;; Keep the draft bubble centered on the comment position (matching a
        ;; created bubble) while the input box is offset to the side.
        bubble-margin (gpt/point 24 24)
        pos           (offset-position position viewport zoom bubble-margin)

        margin-x      (* (:x bubble-margin) (if (= (:h-dir pos) :left) -1 1))
        margin-y      (* (:y bubble-margin) (if (= (:v-dir pos) :top) -1 1))
        pos-x         (+ (* (:x pos) zoom) margin-x)
        pos-y         (- (* (:y pos) zoom) margin-y)

        bubble-x      (floor (* (:x position) zoom))
        bubble-y      (floor (* (:y position) zoom))

        disabled? (or (blank-content? content)
                      (exceeds-length? content))

        on-esc
        (mf/use-fn
         (mf/deps draft)
         (fn [event]
           (dom/stop-propagation event)
           (if (fn? on-cancel)
             (on-cancel)
             (st/emit! :interrupt))))

        on-change
        (mf/use-fn
         (mf/deps draft)
         (fn [content]
           (st/emit! (dcm/update-draft-thread {:content content}))))

        on-attachments-change
        (mf/use-fn
         #(reset! attachments* %))

        on-submit*
        (mf/use-fn
         (mf/deps draft @attachments*)
         (fn []
           (on-submit (cond-> draft (seq @attachments*) (assoc :attachments @attachments*)))
           (reset! attachments* [])))]

    [:> (mf/provider mentions-context) {:value mentions-s}
     [:div {:class (stl/css :floating-preview-wrapper :floating-preview-bubble)
            :data-testid "floating-thread-bubble"
            :style {:top (dm/str bubble-y "px")
                    :left (dm/str bubble-x "px")}
            :on-click dom/stop-propagation}
      [:> comment-avatar* {:class (stl/css :avatar-lg)
                           :image (cfg/resolve-profile-photo-url profile)}]]

     [:div {:class (stl/css-case :floating-thread-draft-inner-wrapper true
                                 :cursor-auto true
                                 :left (= (:h-dir pos) :left)
                                 :top (= (:v-dir pos) :top))
            :style {:top (str pos-y "px")
                    :left (str pos-x "px")}
            :on-click dom/stop-propagation}
      [:div {:class (stl/css :form)}
       [:> comment-input*
        {:placeholder (tr "labels.write-new-comment")
         :value (or content "")
         :autofocus true
         :on-esc on-esc
         :on-change on-change
         :on-ctrl-enter on-submit*}]
       [:> comment-attachments-bar*
        {:attachments @attachments* :on-change on-attachments-change}]
       (when (exceeds-length? content)
         [:div {:class (stl/css :error-text)}
          (tr "errors.character-limit-exceeded")])
       [:> comment-form-buttons* {:on-submit on-submit*
                                  :on-cancel on-esc
                                  :is-disabled disabled?}]]
      [:> mentions-panel*]]]))

(mf/defc comment-floating-thread-header*
  {::mf/private true}
  [{:keys [thread origin]}]
  (let [owner    (dcm/get-owner thread)
        profile  (mf/deref refs/profile)
        options  (mf/deref ref:comments-local-options)

        toggle-resolved
        (mf/use-fn
         (mf/deps thread)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/update-comment-thread (update thread :is-resolved not)))))

        on-toggle-options
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/toggle-comment-options uuid/zero))))

        delete-thread
        (mf/use-fn
         (fn []
           (st/emit! (dcm/close-thread)
                     (if (= origin :viewer)
                       (dcm/delete-comment-thread-on-viewer thread)
                       (dcm/delete-comment-thread-on-workspace thread)))))

        on-delete-thread
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/hide-comment-options))
           (st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-comment-thread.title")
                       :message (tr "modals.delete-comment-thread.message")
                       :accept-label (tr "modals.delete-comment-thread.accept")
                       :on-accept delete-thread}))))

        on-hide-options
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/hide-comment-options))))]

    [:*
     [:div {:class (stl/css :floating-thread-header-left)}
      (tr "labels.comment") " " [:span {:class (stl/css :grayed-text)} "#" (:seqn thread)]]
     [:div {:class (stl/css :floating-thread-header-right)}
      (when (some? thread)
        [:div {:class (stl/css :checkbox-wrapper)
               :title (tr "labels.comment.mark-as-solved")
               :on-click toggle-resolved}
         [:span {:class (stl/css-case :checkbox true
                                      :global/checked (:is-resolved thread))} deprecated-icon/tick]])
      (when (= (:id profile) (:id owner))
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "labels.options")
                          :on-click on-toggle-options
                          :icon i/menu}])]
     [:& dropdown {:show (= options uuid/zero)
                   :on-close on-hide-options}
      [:ul {:class (stl/css :dropdown-menu)}
       [:li {:class (stl/css :dropdown-menu-option)
             :on-click on-delete-thread}
        (tr "labels.delete-comment-thread")]]]]))

(mf/defc comment-floating-thread-item*
  {::mf/private true}
  [{:keys [comment thread]}]
  (let [owner    (dcm/get-owner comment)
        profile  (mf/deref refs/profile)
        options  (mf/deref ref:comments-local-options)
        edition? (mf/use-state false)

        on-toggle-options
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/toggle-comment-options (:id comment)))))

        on-hide-options
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/hide-comment-options))))

        on-edit-clicked
        (mf/use-fn
         (mf/deps options)
         (fn []
           (st/emit! (dcm/hide-comment-options))
           (reset! edition? true)))

        on-delete-comment
        (mf/use-fn
         (mf/deps comment)
         #(st/emit! (dcm/delete-comment comment)))

        on-submit
        (mf/use-fn
         (mf/deps comment thread)
         (fn [content]
           (reset! edition? false)
           (st/emit! (dcm/update-comment (assoc comment :content content)))))

        on-cancel
        (mf/use-fn #(reset! edition? false))]

    [:div {:class (stl/css :floating-thread-item-wrapper)}
     [:div {:class (stl/css :floating-thread-item)}
      [:div {:class (stl/css :author)}
       [:> comment-avatar* {:image (cfg/resolve-profile-photo-url owner)}]
       [:div {:class (stl/css :author-identity)}
        [:div {:class (stl/css :author-fullname)} (:fullname owner)]
        [:div {:class (stl/css :author-timeago)}
         (ct/timeago (:modified-at comment))]]

       (when (= (:id profile) (:id owner))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "labels.options")
                           :on-click on-toggle-options
                           :icon i/menu}])]

      [:div {:class (stl/css :item)}
       (if @edition?
         [:> comment-edit-form* {:content (:content comment)
                                 :on-submit on-submit
                                 :on-cancel on-cancel}]
         ;; P2.40 — only mount the attachments render when the comment
         ;; actually has attachments, so an attachmentless comment is
         ;; byte-identical to the pre-P2.40 text-only render.
         [:*
          [:span {:class (stl/css :text)}
           [:> comment-content* {:content (:content comment)}]]
          (when (seq (:attachments comment))
            [:> comment-attachments-render*
             {:attachments (:attachments comment)}])])]]

     [:& dropdown {:show (= options (:id comment))
                   :on-close on-hide-options}
      [:ul {:class (stl/css :dropdown-menu)}
       [:li {:class (stl/css :dropdown-menu-option)
             :on-click on-edit-clicked}
        (tr "labels.edit")]
       (when-not thread
         [:li {:class (stl/css :dropdown-menu-option)
               :on-click on-delete-comment}
          (tr "labels.delete-comment")])]]]))

(defn make-comments-ref
  [thread-id]
  (l/derived (l/in [:comments thread-id]) st/state))



(mf/defc comment-floating-thread*
  {::mf/wrap [mf/memo]}
  [{:keys [thread zoom origin position-modifier viewport]}]
  (let [ref           (mf/use-ref)
        mentions-s    (mf/use-memo #(rx/subject))
        thread-id     (:id thread)
        thread-pos    (:position thread)

        base-pos      (cond-> thread-pos
                        (some? position-modifier)
                        (gpt/transform position-modifier))

        max-height    (when (some? viewport) (int (* (:height viewport) 0.5)))

        ;; We should probably look for a better way of doing this.
        bubble-margin (gpt/point 24 24)
        pos           (offset-position base-pos viewport zoom bubble-margin)

        margin-x      (* (:x bubble-margin) (if (= (:h-dir pos) :left) -1 1))
        margin-y      (* (:y bubble-margin) (if (= (:v-dir pos) :top) -1 1))
        pos-x         (+ (* (:x pos) zoom) margin-x)
        pos-y         (- (* (:y pos) zoom) margin-y)

        comments-ref  (mf/with-memo [thread-id]
                        (make-comments-ref thread-id))
        comments-map  (mf/deref comments-ref)

        comments      (mf/with-memo [comments-map]
                        (->> (vals comments-map)
                             (sort-by :created-at)))

        first-comment (first comments)

        on-submit
        (mf/use-fn
         (mf/deps thread)
         (fn [content attachments]
           (st/emit! (dcm/add-comment thread content attachments))))

        on-cancel
        (mf/use-fn #(st/emit! (dcm/close-thread)))]

    (mf/with-effect [thread-id]
      (st/emit! (dcm/retrieve-comments thread-id)))

    (mf/with-effect [thread-id]
      (st/emit! (dcm/update-comment-thread-status thread-id)))

    (mf/with-layout-effect [thread-pos comments-map]
      (when-let [node (mf/ref-val ref)]
        (dom/scroll-into-view-if-needed! node)))

    [:> (mf/provider mentions-context) {:value mentions-s}
     (when (some? first-comment)
       [:div {:class (stl/css-case :floating-thread-wrapper true
                                   :cursor-auto true
                                   :left (= (:h-dir pos) :left)
                                   :top (= (:v-dir pos) :top))
              :id (str "thread-" thread-id)
              :style {:left (str pos-x "px")
                      :top (str pos-y "px")
                      "--comment-height" (str max-height "px")}
              :on-click dom/stop-propagation}

        [:div {:class (stl/css :floating-thread-header)}
         [:> comment-floating-thread-header* {:thread thread
                                              :origin origin}]]

        [:div {:class (stl/css :floating-thread-main)}
         [:> comment-floating-thread-item* {:comment first-comment
                                            :thread thread}]
         (for [item (rest comments)]
           [:* {:key (dm/str (:id item))}
            [:> comment-floating-thread-item* {:comment item}]])]

        [:> comment-reply-form* {:on-submit on-submit
                                 :on-cancel (when (= origin :viewer) on-cancel)}]

        [:> mentions-panel*]])]))

;; Screen-space gap (px) between concentric rings of an expanded cluster.
(def ^:private expanded-ring-gap 44)

;; Number of bubbles that fit in the innermost ring; each further ring
;; grows its capacity proportionally to its circumference.
(def ^:private expanded-ring-base 6)

(defn- expanded-ring-slot
  "Return [ring slot capacity] placing the bubble at index `i` (0-based) into
   concentric rings, filling the innermost ring first."
  [i]
  (loop [ring 1
         i    i]
    (let [capacity (* expanded-ring-base ring)]
      (if (< i capacity)
        [ring i capacity]
        (recur (inc ring) (- i capacity))))))

(defn- expanded-ring-vector
  "Pure ring displacement (px) around the cluster center for the bubble at index
   `i` (0-based), laid out into concentric rings, innermost first."
  [i]
  (let [[ring slot capacity] (expanded-ring-slot i)
        ;; Start each ring at the top and stagger alternate rings by half a
        ;; slot so bubbles don't line up radially.
        angle  (+ (* (/ slot capacity) 2 mth/PI)
                  (- (/ mth/PI 2))
                  (if (even? ring) (/ mth/PI capacity) 0))
        radius (* ring expanded-ring-gap)]
    (gpt/point (* radius (mth/cos angle))
               (* radius (mth/sin angle)))))

(defn expanded-group-center
  "Cluster center point (stored coordinates) shared by all bubbles of a group."
  [thread-group]
  (gpt/center-points (mapv :position thread-group)))

(defn expanded-group-offsets
  "Return a seq of [thread offset ring] triples laying each thread of
   `thread-group` into a centered concentric-ring layout, leaving stored
   positions untouched. `offset` is the total screen-space displacement from the
   bubble's own position; `ring` is just the displacement from the cluster
   center (used to animate the fan-out)."
  [zoom thread-group]
  (let [threads (sort-by :seqn thread-group)
        center  (expanded-group-center threads)]
    (map-indexed
     (fn [i thread]
       (let [position (:position thread)
             ring     (expanded-ring-vector i)
             base     (gpt/point (* (- (:x center) (:x position)) zoom)
                                 (* (- (:y center) (:y position)) zoom))]
         [thread (gpt/add base ring) ring]))
     threads)))

(mf/defc comment-floating-group*
  {::mf/wrap [mf/memo]}
  [{:keys [thread-group zoom position-modifier]}]
  (let [positions   (mapv :position thread-group)

        position    (gpt/center-points positions)
        position    (cond-> position
                      (some? position-modifier)
                      (gpt/transform position-modifier))
        pos-x       (* (:x position) zoom)
        pos-y       (* (:y position) zoom)

        unread?     (some #(pos? (:count-unread-comments %)) thread-group)
        num-threads (str (count thread-group))

        test-id     (str/join "-" (map :seqn (sort-by :seqn thread-group)))

        ;; Click-through while transforming a shape, so it doesn't capture the drag
        dragging?   (some? (mf/deref refs/current-transform))

        thread-ids  (mf/with-memo [thread-group]
                      (into #{} (map :id) thread-group))

        on-click
        (mf/use-fn
         (mf/deps thread-ids)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/expand-comment-group thread-ids))))]

    [:div {:style {:top (dm/str pos-y "px")
                   :left (dm/str pos-x "px")
                   :pointer-events (when dragging? "none")}
           :on-click on-click
           :class (stl/css :floating-preview-wrapper :floating-preview-bubble)}
     [:> comment-avatar*
      {:class (stl/css :avatar-lg)
       :variant (if unread? "unread" "read")
       :data-testid (dm/str "floating-thread-bubble-" test-id)}
      num-threads]]))

(mf/defc comment-floating-ghost*
  "Dashed, semi-transparent placeholder shown at the cluster center while its
   bubbles are fanned out into the expanded ring."
  {::mf/wrap [mf/memo]
   ::mf/private true}
  [{:keys [thread-group zoom position-modifier]}]
  (let [center      (expanded-group-center thread-group)
        center      (cond-> center
                      (some? position-modifier)
                      (gpt/transform position-modifier))
        pos-x       (floor (* (:x center) zoom))
        pos-y       (floor (* (:y center) zoom))
        num-threads (str (count thread-group))]
    [:div {:style {:top (dm/str pos-y "px")
                   :left (dm/str pos-x "px")
                   :pointer-events "none"}
           :class (stl/css :floating-preview-wrapper :floating-preview-bubble :floating-preview-ghost)}
     [:> comment-avatar*
      {:class (stl/css :avatar-lg)
       :variant "read"}
      num-threads]]))

(mf/defc comment-floating-bubble*
  {::mf/wrap [mf/memo]}
  [{:keys [thread zoom is-open on-click origin position-modifier offset ring]}]
  (let [owner        (mf/with-memo [thread]
                       (dcm/get-owner thread))

        position     (:position thread)
        position     (cond-> position
                       (some? position-modifier)
                       (gpt/transform position-modifier))

        frame-id     (:frame-id thread)

        ;; A bubble with `offset` is one of the fanned-out members of an
        ;; expanded cluster.
        expanded?    (some? offset)

        ;; Click-through while transforming a shape, so it doesn't capture the drag
        dragging?    (some? (mf/deref refs/current-transform))

        state        (mf/use-state
                      #(do {:is-hover false
                            :is-grabbing false
                            :new-position-x nil
                            :new-position-y nil
                            :new-frame-id frame-id}))

        new-x        (:new-position-x @state)
        new-y        (:new-position-y @state)

        ;; While dragging, the new position already accounts for the ring offset;
        ;; otherwise an expanded bubble sits at its stored position plus the
        ;; screen-space ring offset.
        pos-x        (if (some? new-x)
                       (floor (* new-x zoom))
                       (+ (floor (* (:x position) zoom))
                          (if expanded? (:x offset) 0)))
        pos-y        (if (some? new-y)
                       (floor (* new-y zoom))
                       (+ (floor (* (:y position) zoom))
                          (if expanded? (:y offset) 0)))

        ;; World-space anchor a drag starts from: an expanded bubble is shown at
        ;; its ring position, so dragging must begin there, not at its stored
        ;; position.
        drag-base-x  (if expanded? (+ (:x position) (/ (:x offset) zoom)) (:x position))
        drag-base-y  (if expanded? (+ (:y position) (/ (:y offset) zoom)) (:y position))

        ;; CSS custom properties fed to the fan-out animation; nil for regular
        ;; (non-expanded) bubbles.
        ring-x       (when (and expanded? (some? ring)) (dm/str (- (:x ring)) "px"))
        ring-y       (when (and expanded? (some? ring)) (dm/str (- (:y ring)) "px"))

        drag?        (mf/use-ref nil)
        was-open?    (mf/use-ref nil)

        dragging-ref (mf/use-ref false)
        start-ref    (mf/use-ref nil)

        on-pointer-down
        (mf/use-fn
         (mf/deps origin was-open? is-open drag?)
         (fn [event]
           (when (not= origin :viewer)
             (swap! state assoc :is-grabbing true)
             (mf/set-ref-val! was-open? is-open)
             (when is-open (st/emit! (dcm/close-thread)))
             (mf/set-ref-val! drag? false)
             (dom/stop-propagation event)
             (dom/capture-pointer event)
             (mf/set-ref-val! dragging-ref true)
             (mf/set-ref-val! start-ref (dom/get-client-position event)))))

        on-pointer-up
        (mf/use-fn
         (mf/deps origin thread expanded? (select-keys @state [:new-position-x :new-position-y :new-frame-id]))
         (fn [event]
           (when (not= origin :viewer)
             (swap! state assoc :is-grabbing false)
             (dom/stop-propagation event)
             (dom/release-pointer event)
             (mf/set-ref-val! dragging-ref false)
             (mf/set-ref-val! start-ref nil)
             (when (and
                    (some? (:new-position-x @state))
                    (some? (:new-position-y @state)))
               (st/emit! (dwcm/update-comment-thread-position thread [(:new-position-x @state)
                                                                      (:new-position-y @state)]))
               ;; Dropping a fanned-out bubble commits its new spot and closes
               ;; the temporary cluster expansion.
               (when expanded? (st/emit! (dcm/collapse-comment-group)))
               (swap! state assoc
                      :new-position-x nil
                      :new-position-y nil)))))

        on-pointer-move
        (mf/use-fn
         (mf/deps origin drag? drag-base-x drag-base-y zoom)
         (fn [event]
           (when (not= origin :viewer)
             (mf/set-ref-val! drag? true)
             (dom/stop-propagation event)
             (when-let [_ (mf/ref-val dragging-ref)]
               (let [start-pt   (mf/ref-val start-ref)
                     current-pt (dom/get-client-position event)
                     delta-x    (/ (- (:x current-pt) (:x start-pt)) zoom)
                     delta-y    (/ (- (:y current-pt) (:y start-pt)) zoom)]
                 (swap! state assoc
                        :new-position-x (+ drag-base-x delta-x)
                        :new-position-y (+ drag-base-y delta-y)))))))

        on-pointer-enter
        (mf/use-fn
         (mf/deps is-open)
         (fn [event]
           (dom/stop-propagation event)
           (when (false? is-open)
             (swap! state assoc :is-hover true))))

        on-pointer-leave
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! state assoc :is-hover false)))

        on-click*
        (mf/use-fn
         (mf/deps origin thread on-click was-open? drag?)
         (fn [event]
           (dom/stop-propagation event)
           (when (or (and (mf/ref-val was-open?) (mf/ref-val drag?))
                     (and (not (mf/ref-val was-open?)) (not (mf/ref-val drag?))))
             (swap! state assoc :is-hover false)
             (st/emit! (dcm/open-thread thread)))
           (when (= origin :viewer)
             (on-click thread))))]

    [:div {:style {:top (dm/str pos-y "px")
                   :left (dm/str pos-x "px")
                   :pointer-events (when dragging? "none")
                   "--comment-ring-x" ring-x
                   "--comment-ring-y" ring-y}
           :class (stl/css-case :floating-preview-wrapper true
                                :floating-preview-bubble (false? (:is-hover @state))
                                :floating-preview-expanded expanded?
                                :floating-preview-hovered (:is-hover @state))}

     ;; The avatar circle is the only pointer target: it drives hover, drag and
     ;; click, so hovering the preview card below never keeps the tooltip open.
     [:div {:on-pointer-down on-pointer-down
            :on-pointer-up on-pointer-up
            :on-pointer-move on-pointer-move
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave
            :on-click on-click*
            :class (stl/css-case :floating-preview-avatar true
                                 :cursor-pointer (false? (:is-grabbing @state))
                                 :cursor-grabbing (true? (:is-grabbing @state)))}
      [:> comment-avatar*
       {:image (cfg/resolve-profile-photo-url owner)
        :class (stl/css :avatar-lg)
        :data-testid (dm/str "floating-thread-bubble-" (:seqn thread))
        :variant (cond
                   (:is-resolved thread) "solved"
                   (pos? (:count-unread-comments thread)) "unread"
                   :else "read")}]]

     (when (:is-hover @state)
       [:div {:class (stl/css :floating-thread-wrapper
                              :floating-preview-displacement
                              :floating-preview-hover-card)}
        [:div {:class (stl/css :floating-thread-item-wrapper)}
         [:div {:class (stl/css :floating-thread-item)}
          [:> comment-info* {:item thread
                             :profile owner}]]]])]))

(mf/defc comment-sidebar-thread-item*
  {::mf/private true}
  [{:keys [item on-click]}]
  (let [owner (dcm/get-owner item)
        ;; FIXME
        frame (mf/deref (refs/workspace-page-object-by-id (:page-id item) (:frame-id item)))

        on-click*
        (mf/use-fn
         (mf/deps item)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (when (fn? on-click)
             (on-click item))))]

    [:div {:class (stl/css :cover)
           :on-click on-click*}
     [:div {:class (stl/css :location)}
      [:div {:class (stl/css :location-text)}
       (str "#" (:seqn item))
       (str " - " (:page-name item))
       (when (and (some? frame) (not (cfh/root? frame)))
         (str " - " (:name frame)))]]

     [:> comment-info* {:item item
                        :profile owner}]]))

(mf/defc comment-sidebar-thread-group*
  [{:keys [group on-thread-click]}]
  [:div
   (for [item (:items group)]
     [:> comment-sidebar-thread-item*
      {:item item
       :on-click on-thread-click
       :key (:id item)}])])

(mf/defc comment-dashboard-thread-item*
  {::mf/private true}
  [{:keys [item on-click]}]
  (let [owner (dcm/get-owner item)

        on-click*
        (mf/use-fn
         (mf/deps item)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (when (fn? on-click)
             (on-click item))))]

    [:div {:class (stl/css :cover)
           :on-click on-click*}
     [:div {:class (stl/css :location)}
      [:div {:class (stl/css :location-icon)}
       [:> icon* {:icon-id i/comments}]]
      [:div {:class (stl/css :location-text)}
       (str "#" (:seqn item))
       (str " " (:file-name item))
       (str ", " (:page-name item))]]

     [:> comment-info* {:item item
                        :profile owner}]]))

(mf/defc comment-dashboard-thread-group*
  [{:keys [group on-thread-click]}]
  [:div
   (for [item (:items group)]
     [:> comment-dashboard-thread-item*
      {:item item
       :on-click on-thread-click
       :key (:id item)}])])
