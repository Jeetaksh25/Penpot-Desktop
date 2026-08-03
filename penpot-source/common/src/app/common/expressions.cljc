;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

;; Figma-parity variables in prototyping (gap #30). A small, pure, additive
;; expression evaluator for the subset of Figma prototyping expressions:
;; arithmetic `+ - * / %`, comparisons `== != > < >= <=`, boolean `and or not`,
;; string ops, date ops, and numeric/string/boolean literals. Reference nodes
;; are resolved through a caller-supplied `lookup` fn that takes a reference
;; node vector and returns its value, so this namespace has NO dependency on
;; the token/shape runtime.
;;
;; Reference node shapes (the `lookup` contract):
;;   ["get" <variable-name-string>]      — variable ref
;;   ["prop" <shape-id> <prop>]          — widget/shape property ref
;;   ["error-state" <shape-id>]          — is shape in error state (boolean)
;;
;; This is the SCHEMA + EVALUATOR + PARSER. Wiring it into the viewer's
;; interaction dispatch (evaluating a :conditional's condition before running
;; its then/else action list, and evaluating a :set-variable expression before
;; writing the variable) is done additively in the viewer slices.

(ns app.common.expressions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [clojure.edn :as edn]
   [clojure.string :as cstr]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; An expression is stored as an S-expression-style vector so it is
;; trivially serializable. The parser (parse-expression) builds these from
;; the [[...]] fx string form; format-expression renders them back.
;;   ["+" 1 2]            => 3
;;   ["==" ["get" "x"] 3]  => true when variable x == 3
;;   ["and" ["<" 1 2] [">" 3 0]] => true
;; A leaf is a literal (number / string / boolean / nil), or a reference
;; vector ["get" <variable-name-string>] / ["prop" <id> <prop>] /
;; ["error-state" <id>]. The viewer resolves references via `lookup`; this
;; evaluator only resolves + computes.
(def schema:expression
  ::sm/any)

;; A :set-variable action stores the target variable id (token id) and an
;; expression OR a literal value to assign. :expression is preferred when
;; present; :value is the plain-value fallback for the common case.
(def schema:set-variable-action
  [:map {:title "SetVariableAction"}
   [:variable-id ::sm/uuid]
   [:expression {:optional true} schema:expression]
   [:value {:optional true} ::sm/any]])

;; A :set-variable-mode action switches the active mode for a collection /
;; page. :mode-name is the target mode; :collection-id is optional (when
;; absent, applies to the page-level mode).
(def schema:set-variable-mode-action
  [:map {:title "SetVariableModeAction"}
   [:mode-name :string]
   [:collection-id {:optional true} [:maybe ::sm/uuid]]])

;; A :conditional action evaluates :condition (an expression that must
;; yield a boolean / truthy) and runs :then-actions or :else-actions.
;; Both action lists are vectors of generic interaction maps; the viewer
;; is responsible for dispatching them.
(def schema:conditional-action
  [:map {:title "ConditionalAction"}
   [:condition schema:expression]
   [:then-actions {:optional true} [:vector ::sm/any]]
   [:else-actions {:optional true} [:vector ::sm/any]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EVALUATOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ref-node?
  "True for a reference node vector: [\"get\" name], [\"prop\" id prop],
  [\"error-state\" id]. Any other vector is an operator application."
  [node]
  (and (vector? node)
       (contains? #{"get" "prop" "error-state"} (first node))))

(defn- numeric?
  [x]
  (number? x))

(defn- num-bin
  [args f]
  (let [a (first args) b (second args)]
    (when (and (numeric? a) (numeric? b)) (f a b))))

(defn- num-un
  [args f]
  (let [a (first args)]
    (when (numeric? a) (f a))))

(defn- coerce-num
  "Coerce a number or a non-blank numeric string to a number; nil otherwise.
  Blank / non-numeric strings yield nil so string equality still applies for
  them. Lets a Condition-Builder literal authored as \"3\" compare numerically
  with a numeric variable (literals are captured from a text input as strings)."
  [x]
  (cond
    (number? x) x
    (and (string? x) (not (cstr/blank? x)))
    #?(:clj  (try (let [n (Double/parseDouble x)] (when (Double/isFinite n) n))
                  (catch Throwable _ nil))
       :cljs (let [n (js/Number x)] (when (js/isFinite n) n)))
    :else nil))

(defn- cmp-bin
  "Binary comparison that coerces numeric strings to numbers. Returns nil when
  either side cannot be coerced to a number (so a string-vs-number ordering
  comparison is undefined rather than throwing)."
  [args f]
  (let [a  (first args)  b  (second args)
        na (coerce-num a) nb (coerce-num b)]
    (when (and (some? na) (some? nb)) (f na nb))))

(defn- lookup-or-literal
  [node lookup]
  (cond
    (ref-node? node)
    (when (some? lookup) (lookup node))

    (vector? node)
    (eval node lookup)

    :else node))

;; Date helpers — the date/now operators are CLJS-runtime only (the viewer
;; SPA). They are not exercised by JVM workflow scripts, so the :clj branch
;; returns nil. Math ops are provided on both platforms for test parity.
(defn- now-ms
  []
  #?(:cljs (js/Date.now) :clj nil))

(defn- date-field
  [ms field]
  #?(:cljs
     (let [d (js/Date. ms)]
       (case field
         :year  (.getFullYear d)
         :month (.getMonth d)
         :day   (.getDate d)))
     :clj nil))

(defn- math-abs  [x] #?(:clj (Math/abs x)  :cljs (js/Math.abs x)))
(defn- math-round [x] #?(:clj (Math/round x) :cljs (js/Math.round x)))
(defn- math-floor [x] #?(:clj (Math/floor x) :cljs (js/Math.floor x)))
(defn- math-ceil  [x] #?(:clj (Math/ceil x)  :cljs (js/Math.ceil x)))

(defn- pad2
  [n]
  (let [s (str n)]
    (if (< (count s) 2) (str "0" s) s)))

(defn- format-date-str
  [ms fmt]
  #?(:cljs
     (cstr/replace
       fmt
       #"(YYYY|MM|DD|HH|mm|ss)"
       (fn [tok]
         (let [d (js/Date. ms)]
           (case tok
             "YYYY" (str (.getFullYear d))
             "MM"   (pad2 (inc (.getMonth d)))
             "DD"   (pad2 (.getDate d))
             "HH"   (pad2 (.getHours d))
             "mm"   (pad2 (.getMinutes d))
             "ss"   (pad2 (.getSeconds d))
             tok))))
     :clj nil))

(defn eval
  "Evaluate an expression node. `lookup` is a fn (reference-node -> value) or
  nil; literals pass through unchanged. Returns the computed value or nil
  when a reference is unresolved or args are malformed. All operators are
  nil-safe (return nil on bad args) unless noted."
  [node lookup]
  (cond
    (not (vector? node)) node

    (ref-node? node)
    (when (some? lookup) (lookup node))

    :else
    (let [[op & args] node
          args (mapv #(lookup-or-literal % lookup) args)]
      (case op
        "+"   (let [a (first args) b (second args)]
                (cond
                  (and (numeric? a) (numeric? b)) (+ a b)
                  (some? a) (dm/str a (or b ""))
                  :else nil))
        "-"   (num-bin args -)
        "*"   (num-bin args *)
        "/"   (num-bin args #(when-not (zero? %2) (/ %1 %2)))
        "%"   (num-bin args #(when-not (zero? %2) (mod %1 %2)))
        "=="  (let [a (first args) b (second args)
                    na (coerce-num a) nb (coerce-num b)]
                (if (and (some? na) (some? nb)) (= na nb) (= a b)))
        "!="  (let [a (first args) b (second args)
                    na (coerce-num a) nb (coerce-num b)]
                (if (and (some? na) (some? nb)) (not= na nb) (not= a b)))
        ">"   (cmp-bin args >)
        "<"   (cmp-bin args <)
        ">="  (cmp-bin args >=)
        "<="  (cmp-bin args <=)
        "and" (boolean (every? identity args))
        "or"  (boolean (some identity args))
        "not" (not (first args))

        ;; variadic numeric
        "min" (when (and (seq args) (every? numeric? args)) (reduce min args))
        "max" (when (and (seq args) (every? numeric? args)) (reduce max args))
        "abs" (num-un args math-abs)
        "round" (num-un args math-round)
        "floor" (num-un args math-floor)
        "ceil"  (num-un args math-ceil)

        ;; string ops
        "concat"  (apply dm/str (map #(if (nil? %) "" %) args))
        "len"     (let [a (first args)]
                    (cond (string? a) (count a)
                          (vector? a) (count a)
                          :else nil))
        "upper"   (when (string? (first args)) (cstr/upper-case (first args)))
        "lower"   (when (string? (first args)) (cstr/lower-case (first args)))
        "trim"    (when (string? (first args)) (cstr/trim (first args)))
        "contains" (let [s (first args) sub (second args)]
                     (when (and (string? s) (string? sub))
                       (cstr/includes? s sub)))
        "starts-with" (let [s (first args) sub (second args)]
                        (when (and (string? s) (string? sub))
                          (cstr/starts-with? s sub)))
        "ends-with"   (let [s (first args) sub (second args)]
                        (when (and (string? s) (string? sub))
                          (cstr/ends-with? s sub)))
        "regex-match" (let [s (first args) p (second args)]
                        (when (and (string? s) (string? p))
                          (try
                            (some? (re-find (re-pattern p) s))
                            (catch :default _ false))))
        "regex-replace" (let [s (first args) p (second args) r (nth args 2 nil)]
                          (when (and (string? s) (string? p) (string? r))
                            (try
                              (cstr/replace s (re-pattern p) r)
                              (catch :default _ s))))

        ;; date ops (CLJS runtime only)
        "now"        (when (empty? args) (now-ms))
        "date-year"  (num-un args #(date-field % :year))
        "date-month" (num-un args #(date-field % :month))
        "date-day"   (num-un args #(date-field % :day))
        "format-date" (let [ms (first args) fmt (second args)]
                        (when (and (numeric? ms) (string? fmt))
                          (format-date-str ms fmt)))

        nil))))

(defn truthy?
  "Evaluate a condition expression and return a boolean for conditional
  dispatch. nil/unresolved => false (the then-branch is not taken)."
  [condition lookup]
  (boolean (eval condition lookup)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PARSER  — [[...]] fx string -> S-expr node
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private binary-ops
  #{"+" "-" "*" "/" "%" "==" "!=" ">" "<" ">=" "<="})

(def ^:private fn-names
  #{"min" "max" "abs" "concat" "len" "upper" "lower" "trim"
    "contains" "starts-with" "ends-with" "regex-match" "regex-replace"
    "now" "date-year" "date-month" "date-day" "format-date"})

(defn- strip-brackets
  "Strip a single surrounding [[ ... ]] wrapper if present (whitespace
  tolerant). Returns the inner content otherwise."
  [s]
  (let [s (cstr/trim s)]
    (if (and (cstr/starts-with? s "[[")
             (cstr/ends-with? s "]]")
             (>= (count s) 4))
      (cstr/trim (subs s 2 (- (count s) 2)))
      s)))

(defn- match-at
  "Return the substring of `s` starting at index `i` that matches the
  anchored `pattern`, or nil."
  [s i pattern]
  (let [m (re-find pattern (subs s i))]
    (when m
      (if (string? m) m (first m)))))

(defn- parse-number-lit
  [s]
  (try
    #?(:clj (Double/parseDouble s) :cljs (js/Number s))
    (catch :default _ nil)))

(defn- read-string-literal
  "i points at the opening quote. Returns [end-idx value] or nil if
  unclosed."
  [s i]
  (loop [j (inc i) acc ""]
    (if (>= j (count s))
      nil
      (let [c (get s j)]
        (cond
          (= c \\)
          (if (< (inc j) (count s))
            (recur (+ j 2) (str acc (get s (inc j))))
            nil)
          (= c \")
          [(inc j) acc]
          :else
          (recur (inc j) (str acc c)))))))

(defn- parse-ref-content
  [content]
  (if (cstr/starts-with? content "prop:")
    (let [rest (subs content 4)
          idx  (cstr/index-of rest \.)]
      (if (and idx (pos? idx))
        ["prop" (subs rest 0 idx) (subs rest (inc idx))]
        ["get" content]))
    ["get" content]))

(defn- read-ref
  "i points at $, i+1 at {. Returns [end-idx node] or nil if unclosed."
  [s i]
  (loop [j (+ i 2) acc ""]
    (if (>= j (count s))
      nil
      (let [c (get s j)]
        (if (= c \})
          [(inc j) (parse-ref-content acc)]
          (recur (inc j) (str acc c)))))))

(defn- tokenize
  [s]
  (loop [i 0 tokens []]
    (if (>= i (count s))
      tokens
      (let [c (get s i)]
        (cond
          (re-matches #"\s" (str c)) (recur (inc i) tokens)

          (= c \() (recur (inc i) (conj tokens {:type :lparen}))
          (= c \)) (recur (inc i) (conj tokens {:type :rparen}))
          (= c \,) (recur (inc i) (conj tokens {:type :comma}))

          (= c \")
          (let [res (read-string-literal s i)]
            (if res
              (recur (res 0) (conj tokens {:type :string :value (res 1)}))
              (recur (inc i) tokens)))

          (and (= c \$) (< (inc i) (count s)) (= (get s (inc i)) \{))
          (let [res (read-ref s i)]
            (if res
              (recur (res 0) (conj tokens {:type :ref :value (res 1)}))
              (recur (inc i) tokens)))

          (re-matches #"[0-9]" (str c))
          (let [num (match-at s i #"[0-9]+(?:\.[0-9]+)?")]
            (recur (+ i (count num))
                   (conj tokens {:type :number :value (parse-number-lit num)})))

          (= c \=)
          (if (= (get s (inc i)) \=)
            (recur (+ i 2) (conj tokens {:type :op :value "=="}))
            (recur (inc i) tokens))

          (= c \!)
          (if (= (get s (inc i)) \=)
            (recur (+ i 2) (conj tokens {:type :op :value "!="}))
            (recur (inc i) tokens))

          (= c \>)
          (if (= (get s (inc i)) \=)
            (recur (+ i 2) (conj tokens {:type :op :value ">="}))
            (recur (inc i) (conj tokens {:type :op :value ">"})))

          (= c \<)
          (if (= (get s (inc i)) \=)
            (recur (+ i 2) (conj tokens {:type :op :value "<="}))
            (recur (inc i) (conj tokens {:type :op :value "<"})))

          (contains? #{\+ \- \* \/ \%} c)
          (recur (inc i) (conj tokens {:type :op :value (str c)}))

          (re-matches #"[a-zA-Z_]" (str c))
          (let [idt (match-at s i #"[a-zA-Z_][a-zA-Z0-9_\-]*")]
            (recur (+ i (count idt))
                   (conj tokens {:type (if (contains? #{"and" "or" "not"} idt) :op :ident)
                                 :value idt})))

          :else
          (recur (inc i) (conj tokens {:type :ident :value (str c)})))))))

(defn- tok-op?
  [tok v]
  (and (some? tok) (= (:type tok) :op) (= (:value tok) v)))

(declare parse-expr)

(defn- parse-fn-call
  "idx points at :lparen, idx+1 at the :ident function name."
  [tokens idx]
  (let [name-tok (get tokens (inc idx))]
    (if (and (= :ident (:type name-tok))
             (contains? fn-names (:value name-tok)))
      (let [fn-name (:value name-tok)]
        (loop [k (+ idx 2) args []]
          (let [tok (get tokens k)]
            (cond
              (nil? tok)             [nil k]              ; unclosed
              (= :rparen (:type tok)) [(into [fn-name] args) (inc k)]
              (= :comma (:type tok))  (recur (inc k) args)
              :else
              (let [[arg nk] (parse-expr tokens k)]
                (if (nil? arg)
                  [nil k]
                  (recur nk (conj args arg))))))))
      [nil idx])))

(defn- parse-primary
  [tokens idx]
  (let [tok (get tokens idx)]
    (cond
      (nil? tok)            [nil idx]
      (= :number (:type tok)) [(:value tok) (inc idx)]
      (= :string (:type tok)) [(:value tok) (inc idx)]
      (= :ref (:type tok))    [(:value tok) (inc idx)]
      (= :ident (:type tok))  [(:value tok) (inc idx)]   ; bare ident -> literal string
      (= :lparen (:type tok))
      (let [next-tok (get tokens (inc idx))]
        (if (and (= :ident (:type next-tok))
                 (contains? fn-names (:value next-tok)))
          (parse-fn-call tokens idx)
          (let [[node k] (parse-expr tokens (inc idx))]
            (if (nil? node)
              [nil idx]
              (let [tok2 (get tokens k)]
                (if (and (some? tok2) (= :rparen (:type tok2)))
                  [node (inc k)]
                  [node k]))))))                       ; unclosed -> best-effort
      :else [nil idx])))

(defn- parse-unary
  [tokens idx]
  (let [tok (get tokens idx)]
    (cond
      (tok-op? tok "-")
      (let [[child k] (parse-unary tokens (inc idx))]
        (if (nil? child)
          [nil idx]
          [["-" 0 child] k]))
      (tok-op? tok "not")
      (let [[child k] (parse-unary tokens (inc idx))]
        (if (nil? child)
          [nil idx]
          [["not" child] k]))
      :else (parse-primary tokens idx))))

(defn- parse-binary-level
  [tokens idx op-set parse-fn]
  (let [[lhs k] (parse-fn tokens idx)]
    (if (nil? lhs)
      [nil idx]
      (loop [lhs lhs k k]
        (let [tok (get tokens k)]
          (if (and (= :op (:type tok)) (contains? op-set (:value tok)))
            (let [[rhs nk] (parse-fn tokens (inc k))]
              (if (nil? rhs)
                [lhs k]
                (recur [(:value tok) lhs rhs] nk)))
            [lhs k]))))))

(defn- parse-mul   [tokens idx] (parse-binary-level tokens idx #{"*" "/" "%"} parse-unary))
(defn- parse-add   [tokens idx] (parse-binary-level tokens idx #{"+" "-"} parse-mul))
(defn- parse-cmp   [tokens idx] (parse-binary-level tokens idx #{">" "<" ">=" "<="} parse-add))
(defn- parse-eq    [tokens idx] (parse-binary-level tokens idx #{"==" "!="} parse-cmp))

(defn- parse-and
  [tokens idx]
  (let [[lhs k] (parse-eq tokens idx)]
    (if (nil? lhs)
      [nil idx]
      (loop [lhs lhs k k]
        (let [tok (get tokens k)]
          (if (tok-op? tok "and")
            (let [[rhs nk] (parse-eq tokens (inc k))]
              (if (nil? rhs)
                [lhs k]
                (recur ["and" lhs rhs] nk)))
            [lhs k]))))))

(defn- parse-or
  [tokens idx]
  (let [[lhs k] (parse-and tokens idx)]
    (if (nil? lhs)
      [nil idx]
      (loop [lhs lhs k k]
        (let [tok (get tokens k)]
          (if (tok-op? tok "or")
            (let [[rhs nk] (parse-and tokens (inc k))]
              (if (nil? rhs)
                [lhs k]
                (recur ["or" lhs rhs] nk)))
            [lhs k]))))))

(defn- parse-expr [tokens idx] (parse-or tokens idx))

(defn parse-expression
  "Parse a [[...]] fx string into an S-expr vector node. Supported syntax:
  numbers, \"quoted strings\", ${varname} -> [\"get\" name],
  ${prop:<shape-id>.<prop>} -> [\"prop\" id prop], operators
  + - * / % == != > < >= <= and or not, plus named functions
  (min 1 2) / (max ...) / (abs ...) / (concat ...) / (len ...) / (upper ...)
  / (lower ...) / (trim ...) / (contains a b) / (starts-with a b)
  / (ends-with a b) / (regex-match s p) / (regex-replace s p r) / (now)
  / (date-year ms) / (date-month ms) / (date-day ms) / (format-date ms fmt).
  Parenthesized nested expressions group. Unknown tokens become literal
  strings. Robust: never throws; on parse failure returns the raw input
  string as a literal node."
  [s]
  (if (not (string? s))
    s
    (try
      (let [inner  (strip-brackets s)
            tokens (tokenize inner)]
        (if (empty? tokens)
          s
          (let [[node _] (parse-expr tokens 0)]
            (if (some? node) node s))))
      (catch :default _ s))))

(defn- format-ref
  [node]
  (let [kind (first node)]
    (cond
      (= kind "get")         (str "${" (second node) "}")
      (= kind "prop")        (str "${prop:" (second node) "." (nth node 2) "}")
      (= kind "error-state") (str "${error-state:" (second node) "}")
      :else (dm/str node))))

(defn- format-literal
  [x]
  (cond
    (nil? x)     "null"
    (true? x)    "true"
    (false? x)   "false"
    (string? x)  (str "\"" (cstr/replace x "\"" "\\\"") "\"")
    (number? x)  (dm/str x)
    :else        (dm/str x)))

(defn- format-node
  [node]
  (cond
    (not (vector? node)) (format-literal node)
    (ref-node? node)     (format-ref node)
    :else
    (let [op   (first node)
          args (rest node)]
      (cond
        (contains? binary-ops op)
        (str "(" (format-node (first args)) " " op " " (format-node (second args)) ")")

        (or (= op "and") (= op "or"))
        (str "(" (cstr/join (str " " op " ") (mapv format-node args)) ")")

        (= op "not")
        (str "(not " (format-node (first args)) ")")

        (contains? fn-names op)
        (str "(" op (if (seq args)
                      (str " " (cstr/join " " (mapv format-node args)))
                      "")
             ")")

        :else (dm/str node)))))

(defn format-expression
  "Render a parsed expression node back to the [[...]] fx string form.
  Round-trips with parse-expression for well-formed input."
  [node]
  (str "[[" (format-node node) "]]"))

(defn build-condition
  "Build a condition node from a Condition Builder state. `mode` is :all or
  :any; `predicates` is a vector of [op lhs rhs] nodes. Returns
  [\"and\" p1 p2 ...] or [\"or\" p1 p2 ...]. Empty -> [\"and\"] (true).
  Single predicate -> that predicate, unwrapped."
  [mode predicates]
  (let [op   (if (= mode :any) "or" "and")
        preds (vec predicates)]
    (cond
      (empty? preds)            [op]
      (= (count preds) 1)       (first preds)
      :else                     (into [op] preds))))