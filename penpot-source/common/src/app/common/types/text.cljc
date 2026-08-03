;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

 (ns app.common.types.text
   (:require
    [app.common.data :as d]
    [app.common.data.macros :as dm]
    [app.common.flags :as flags]
    [app.common.types.color :as clr]
    [app.common.types.fills :as types.fills]
    [clojure.set :as set]
    [clojure.walk :as walk]
    [cuerdas.core :as str]))

;; -- Attrs

(def text-typography-attrs
  [:typography-ref-id
   :typography-ref-file])

(def text-fill-attrs
  [:fill-color
   :fill-opacity
   :fill-color-ref-id
   :fill-color-ref-file
   :fill-color-gradient])

(def text-font-attrs
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style])

(def text-align-attrs
  [:text-align])

(def text-direction-attrs
  [:text-direction])

(def text-spacing-attrs
  [:line-height
   :line-height-mode
   :letter-spacing
   :paragraph-spacing
   :paragraph-indent])

(def text-valign-attrs
  [:vertical-align])

(def text-decoration-attrs
  [:text-decoration
   :text-decoration-style
   :text-decoration-thickness
   :text-decoration-offset
   :text-decoration-skip-ink
   :text-decoration-color])

(def text-transform-attrs
  [:text-transform])

;; Feature 12 — OpenType features. Span-level, applied to a character range.
;; The value is a CSS `font-feature-settings` string (e.g. "\"liga\" 1, \"dlig\"
;; 0") so it round-trips 1:1 with the real CSS property of the same name in the
;; editor DOM (`TextSpan.js` STYLES) and renders LIVE in the contenteditable.
;; The UI (OpenType Details tab) builds the string from per-feature toggles.
;; Reading available features from opentype.js font metadata (GSUB table) is
;; deferred — a static common-features list is offered for now.
(def text-opentype-attrs
  [:font-feature-settings])

;; Feature 13 — variable-font axes. Span-level. The value is a CSS
;; `font-variation-settings` string (e.g. "\"wght\" 400, \"wdth\" 80"), again
;; round-tripped 1:1 with the real CSS property so the contenteditable renders
;; the variation LIVE. The UI (Variable tab) builds the string from per-axis
;; sliders. Reading available axes from opentype.js font metadata (fvar table)
;; is deferred — a static common-axes list is offered for now.
(def text-variation-attrs
  [:font-variation-settings])

;; Feature 16 — list styles. Paragraph-level: `:list-type` is `:none`,
;; `:bulleted` or `:numbered`; `:list-spacing` is the px gap between list
;; items. They round-trip as `--list-type` / `--list-spacing` CSS custom
;; properties on paragraph nodes (see `app.util.text.content.styles` mapping).
;; Editor content-model list nodes + renderer bullet/number markers are
;; deferred (additive optional fields only — no content-model rewrite).
(def text-list-attrs
  [:list-type
   :list-spacing])

;; Feature 19 — text on a path. Layer-level. The value is a map
;; `{:path-id <uuid> :start-offset <number>}` referencing a vector path shape;
;; it round-trips as a transit-encoded `--text-on-path` CSS custom property
;; (mirroring `:hyperlink`). UI to pick a path + renderer SVG `<textPath>`
;; flow are deferred (additive optional field only).
(def text-on-path-attrs
  [:text-on-path])

;; Inline baseline shift (superscript / subscript). Span-level, applied to a
;; character range. Mirrors `:vertical-align` (root, text-box valign) but lives
;; on spans; the editor maps it to the CSS `vertical-align` property (see
;; `app.util.text.content.styles`). Faux font-size fallback is deferred.
(def text-baseline-attrs
  [:baseline-shift])

;; Inline hyperlink on a character range. The value is a map `{:url "..."}`;
;; it round-trips through the editor DOM as a transit-encoded `--hyperlink`
;; CSS custom property (see `styles/mapping`). Live editor visual + prototype
;; click interception are deferred (v1: schema + persistence + export only).
(def text-link-attrs
  [:hyperlink])

;; Layer/paragraph-level truncation. `:text-overflow` is `:visible` or
;; `:truncate`; `:max-lines` is an int. These are paragraph-level in the DOM
;; editor (stripped from spans below) and read by the renderer/export path.
;; Exact whole-text-box clamping (vs per-paragraph) is a v1 limitation.
(def text-overflow-attrs
  [:text-overflow
   :max-lines])

(def text-fills
  [:fills])

(def shape-attrs
  [:grow-type])

(def root-attrs
  text-valign-attrs)

;; Feature 50 — hanging punctuation. Paragraph-level boolean. When true the
;; renderer/export pulls opening quotes / bullet markers outside the text-box
;; bounds for cleaner edges (Figma Type Settings > Indentation). Additive
;; optional: absent key = existing behavior. Round-trips as the real CSS
;; `hanging-punctuation` property (see `app.main.ui.shapes.text.styles`); the
;; renderer positioning of leading punctuation outside the text-box bounds is
;; deferred (additive optional field + UI + export shipped).
(def text-hanging-punctuation-attrs
  [:hanging-punctuation])

(def paragraph-attrs
  (d/concat-vec
   text-align-attrs
   text-direction-attrs
   text-hanging-punctuation-attrs))

(def text-node-attrs
  (d/concat-vec
   text-typography-attrs
   text-font-attrs
   text-spacing-attrs
   text-decoration-attrs
   text-baseline-attrs
   text-link-attrs
   text-overflow-attrs
   text-transform-attrs
   text-opentype-attrs
   text-variation-attrs
   text-list-attrs
   text-on-path-attrs
   text-fills))

;; Attrs that are paragraph/layer-level in the DOM editor (not stored on
;; individual text spans). Line-height is paragraph-level; the new
;; line-height-mode / paragraph-spacing / paragraph-indent pair with it, and
;; text-overflow / max-lines are layer-level. Feature 16 list-type /
;; list-spacing are paragraph-level, and Feature 19 text-on-path is
;; layer-level. They are kept on paragraph nodes (and read by the renderer
;; from paragraph data) but stripped from spans so they don't pollute
;; per-range rich-text diffs.
(def ^:private paragraph-level-attrs
  #{:line-height
    :line-height-mode
    :paragraph-spacing
    :paragraph-indent
    :text-overflow
    :max-lines
    :list-type
    :list-spacing
    :text-on-path})

(def text-span-attrs
  "Inline text span attrs. Paragraph-level attrs (line-height, line-height-mode,
   paragraph-spacing, paragraph-indent, text-overflow, max-lines, list-type,
   list-spacing, text-on-path) are excluded; they may still be stored
   redundantly on span nodes after token apply."
  (vec (remove paragraph-level-attrs text-node-attrs)))

(defn text-node-attr?
  [attr]
  (d/index-of text-node-attrs attr))

(def text-all-attrs (d/concat-set shape-attrs root-attrs paragraph-attrs text-node-attrs))

(def text-style-attrs
  (d/concat-vec root-attrs paragraph-attrs text-node-attrs))

(def default-root-attrs
  {:vertical-align "top"})

(def default-text-fills
  [{:fill-color clr/black
    :fill-opacity 1}])

(def default-paragraph-attrs
  {:text-align "left"
   :text-direction "ltr"})

(def default-text-attrs
  {:typography-ref-file nil
   :typography-ref-id nil
   :font-id "sourcesanspro"
   :font-family "sourcesanspro"
   :font-variant-id "regular"
   :font-size "14"
   :font-weight "400"
   :font-style "normal"
   :line-height "1.2"
   :letter-spacing "0"
   :text-transform "none"
   :text-align "left"
   :text-decoration "none"
   :text-direction "ltr"})

(defn get-default-text-fills
  "Return calculated default text fills"
  []
  (if (contains? flags/*current* :frontend-binary-fills)
    (types.fills/from-plain default-text-fills)
    default-text-fills))

(defn get-default-text-attrs
  "Return calculated default text attrs.

  NOTE: is implemented as function because it needs resolve at runtime
  the activated flag for properly encode the fills"
  []
  (assoc default-text-attrs :fills (get-default-text-fills)))

(def typography-fields
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style
   :line-height
   :letter-spacing
   :text-transform])

(def default-typography
  (-> default-text-attrs
      (select-keys typography-fields)
      (assoc :name "Source Sans Pro Regular")))

(defn node-seq
  ([root] (node-seq identity root))
  ([match? root]
   (->> (tree-seq map? :children root)
        (filter match?)
        (seq))))

(defn is-text-node?
  [node]
  (and (nil? (:type node))
       (string? (:text node))))

(defn is-paragraph-set-node?
  [node]
  (= "paragraph-set" (:type node)))

(defn is-paragraph-node?
  [node]
  (= "paragraph" (:type node)))

(defn is-root-node?
  [node]
  (= "root" (:type node)))

(defn is-node?
  [node]
  (or ^boolean (is-text-node? node)
      ^boolean (is-paragraph-node? node)
      ^boolean (is-paragraph-set-node? node)
      ^boolean (is-root-node? node)))

(defn is-content-node?
  "Only matches content nodes, ignoring the paragraph-set nodes."
  [node]
  (or ^boolean (is-text-node? node)
      ^boolean (is-paragraph-node? node)
      ^boolean (is-root-node? node)))

(defn transform-nodes
  ([transform root]
   (transform-nodes identity transform root))
  ([pred transform root]
   (walk/postwalk
    (fn [item]
      (if (and (is-node? item) (pred item))
        (transform item)
        item))
    root)))

(defn update-text-content
  [shape pred-fn update-fn attrs]
  (let [update-attrs-fn #(update-fn % attrs)
        transform   #(transform-nodes pred-fn update-attrs-fn %)]
    (-> shape
        (update :content transform))))

(defn generate-shape-name
  [text]
  (subs text 0 (min 280 (count text))))

(defn- compare-text-attr
  "Compare two attribute values and return true if they are different.
   Take into account the following:
    - Only process keys that belong to text node attrs (ignore deprecated
      attributes or other things that may be attached).
    - Consider nil values, empty strings or empty lists all equal.
    - Normalize numeric values (legacy) into strings.
    - No value is equal than the default value."
  [key value1 value2]
  (when (text-node-attr? key)
    (let [default-value (get default-text-attrs key)
          normalize-value (fn [value]
                            (as-> value $
                              (if (number? $) (str $) $)
                              (if (or (d/empty? $) (= $ default-value))
                                nil
                                $)))
          value1' (normalize-value value1)
          value2' (normalize-value value2)]
      (not= value1' value2'))))

(defn- compare-text-content
  "Given two content text structures, conformed by maps and vectors,
   compare them, and returns a set with the differences info.
   If the structures are equal, it returns an empty set. If the structure
   has changed, it returns :text-content-structure. There are two
   callbacks to specify what to return when there is a text change with
   the same structure, and when attributes change."
  [a b {:keys [text-cb attribute-cb] :as callbacks}]
  (cond
    ;; If a and b are equal, there is no diff
    (= a b)
    #{}

    ;; If types are different, the structure is different
    (not= (type a) (type b))
    #{:text-content-structure}

    ;; If they are maps, check the keys
    (map? a)
    (let [keys (-> (set/union (set (keys a)) (set (keys b)))
                   (disj :key))] ;; We have to ignore :key because it is a draft artifact
      (reduce
       (fn [acc k]
         (let [v1 (get a k)
               v2 (get b k)]
           (cond
             ;; If the key is :children, keep digging
             (= k :children)
             (if (not= (count v1) (count v2))
               #{:text-content-structure}
               (into acc
                     (apply set/union
                            (map #(compare-text-content %1 %2 callbacks) v1 v2))))

             ;; If the key is :text, and they are different, it is a text differece
             (= k :text)
             (if (not= v1 v2)
               (text-cb acc)
               acc)

             :else
             ;; If the key is not :text, and they are different, it is an attribute difference.
             ;; Take into account that some processes remove empty attributes, so in some
             ;; cases we will compare [] with nil, and this is not a difference.
             (if (compare-text-attr k v1 v2)
               (attribute-cb acc k)
               acc))))
       #{}
       keys))

    :else
    #{:text-content-structure}))

(defn equal-attrs?
  "Given a text structure, and a map of attrs, check that all the internal attrs in
   paragraphs and sentences have the same attrs"
  ([item attrs]
   ;; Ignore the root attrs of the content. We only want to check paragraphs and sentences
   (equal-attrs? item attrs true))
  ([item attrs ignore?]
   (let [item-attrs (dissoc item :text :type :key :children)]
     (and
      (or ignore?
          (empty? item-attrs)
          (= attrs (dissoc item :text :type :key :children)))
      (every? #(equal-attrs? % attrs false) (:children item))))))

(defn get-first-paragraph-text-attrs
  "Given a content text structure, extract it's first paragraph
   text attrs"
  [content]
  (-> content
      (dm/get-in [:children 0 :children 0])
      (dissoc :text :type :key :children)))

(defn get-diff-type
  "Given two content text structures, conformed by maps and vectors,
   compare them, and returns a set with the type of differences.
   The possibilities are
     :text-content-text
     :text-content-attribute
     :text-content-structure"
  [a b]
  (compare-text-content a b
                        {:text-cb      (fn [acc] (conj acc :text-content-text))
                         :attribute-cb (fn [acc _] (conj acc :text-content-attribute))}))

(defn get-diff-attrs
  "Given two content text structures, conformed by maps and vectors,
   compare them, and returns a set with the attributes that have changed.
   This is independent of the text structure, so if the structure changes
   but the attributes are the same, it will return an empty set.

   Paragraph-level attrs on text nodes are ignored: they are paragraph-level
   and may be stored redundantly on spans (e.g. after token apply / bulk
   update-all-attrs). This covers line-height and the new line-height-mode /
   paragraph-spacing / paragraph-indent / text-overflow / max-lines /
   list-type / list-spacing / text-on-path attrs."
  [a b]
  (let [strip-span-paragraph-attrs
        #(transform-nodes is-text-node?
                          (fn [node] (apply dissoc node paragraph-level-attrs)) %)
        a (strip-span-paragraph-attrs a)
        b (strip-span-paragraph-attrs b)
        diff-attrs (compare-text-content a b
                                         {:text-cb      identity
                                          :attribute-cb (fn [acc attr] (conj acc attr))})]
    (if-not (contains? diff-attrs :text-content-structure)
      diff-attrs
      (let [;; get attrs of the first paragraph of the first paragraph-set
            attrs (get-first-paragraph-text-attrs a)]
        (if (and (equal-attrs? a attrs)
                 (equal-attrs? b attrs))
          #{}
          (disj diff-attrs :text-content-structure))))))

;; TODO We know that there are cases that the blocks of texts are separated
;; differently: ["one" " " "two"], ["one " "two"], ["one" " two"]
;; so this won't work for 100% of the situations. But it's good enough for now,
;; we can iterate on the solution again in the future if needed.
(defn equal-structure?
  "Given two content text structures, check that the structures are equal.
   This means that all the :children keys at any level has the same number of
   entries"
  [a b]
  (cond
    (and (not= (type a) (type b))
         (not (and (map? a) (map? b)))) ;; Sometimes they are both maps but of different subtypes
    false

    (map? a)
    (let [children-a (:children a)
          children-b (:children b)]
      (if (not= (count children-a) (count children-b))
        false
        (every? true?
                (map equal-structure? children-a children-b))))

    :else
    true))

(defn copy-text-keys
  "Given two equal content text structures, deep copy all the keys :text
   from origin to destiny"
  [origin destiny]
  (cond
    (map? origin)
    (into {}
          (for [k (keys destiny) :when (not= k :key)] ;; We ignore :key because it is a draft artifact
            (cond
              (= :children k)
              [k (vec (map #(copy-text-keys %1 %2) (get origin k) (get destiny k)))]
              (= :text k)
              [k (:text origin)]
              :else
              [k (get destiny k)])))))

(defn copy-attrs-keys
  "Given a content text structure and a list of attrs, copy that
   attrs values on all the content tree"
  [content attrs]
  (into {}
        (for [[k v] content]
          (if (= :children k)
            [k (vec (map #(copy-attrs-keys %1 attrs) v))]
            [k (get attrs k v)]))))


(defn content-has-text?
  [content search]
  (let [search-lower (str/lower search)]
    (->> (node-seq is-text-node? content)
         (some #(str/includes? (str/lower (:text %)) search-lower))
         (boolean))))

(defn replace-all-case-insensitive
  [text search replacement]
  (let [text-lower   (str/lower text)
        search-lower (str/lower search)
        search-len   (count search)]
    (loop [result "" idx 0]
      (let [found (str/index-of text-lower search-lower idx)]
        (if (nil? found)
          (str result (subs text idx))
          (recur (str result (subs text idx found) replacement)
                 (+ found search-len)))))))

(defn replace-text-in-content
  [content search replacement]
  (transform-nodes
   is-text-node?
   (fn [node] (update node :text replace-all-case-insensitive search replacement))
   content))

(defn content->text
  "Given a root node of a text content extracts the texts with its associated styles"
  [content]
  (letfn [(add-node [acc node]
            (cond
              (is-paragraph-node? node)
              (conj acc [])

              (is-text-node? node)
              (let [i (dec (count acc))]
                (update acc i conj (:text node)))

              :else
              acc))]
    (->> (node-seq content)
         (reduce add-node [])
         (map #(str/join "" %))
         (str/join "\n"))))

(defn content->text+styles
  "Given a root node of a text content extracts the texts with its associated styles"
  [node]
  (letfn
   [(rec-style-text-map [acc node style]
      (let [node-style (merge style (select-keys node text-all-attrs))
            head (or (-> acc first) [{} ""])
            [head-style head-text] head

            new-acc
            (cond
              (not (is-text-node? node))
              (reduce #(rec-style-text-map %1 %2 node-style) acc (:children node))

              (not= head-style node-style)
              (cons [node-style (:text node "")] acc)

              :else
              (cons [node-style (dm/str head-text "" (:text node))] (rest acc)))

            ;; We add an end-of-line when finish a paragraph
            new-acc
            (if (= (:type node) "paragraph")
              (let [[hs ht] (first new-acc)]
                (cons [hs (dm/str ht "\n")] (rest new-acc)))
              new-acc)]
        new-acc))]

    (-> (rec-style-text-map [] node {})
        reverse)))

(defn change-text
  "Changes the content of the text shape to use the text as argument. Will use the styles of the
   first paragraph and text that is present in the shape (and override the rest)"
  [content text & {:as styles}]
  (let [root-styles (select-keys content root-attrs)

        paragraph-style
        (merge
         default-text-attrs
         styles
         (select-keys (->> content (node-seq is-paragraph-node?) first) text-all-attrs))

        text-style
        (merge
         default-text-attrs
         styles
         (select-keys (->> content (node-seq is-text-node?) first) text-all-attrs))

        paragraph-texts
        (str/split text "\n")

        paragraphs
        (->> paragraph-texts
             (mapv
              (fn [pt]
                (merge
                 paragraph-style
                 {:type "paragraph"
                  :children [(merge {:text pt} text-style)]}))))]


    (d/patch-object
     {:type "root"
      :children
      [{:type "paragraph-set"
        :children paragraphs}]}
     root-styles)))
