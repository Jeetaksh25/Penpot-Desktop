;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

;; Figma-parity variables in prototyping (gap #30). A small, pure, additive
;; expression evaluator for the subset of Figma prototyping expressions:
;; arithmetic `+ - * /`, comparisons `== != > < >= <=`, boolean `and or`,
;; string concat, and numeric/string/boolean literals. Variable references
;; are resolved through a caller-supplied `lookup` fn (variable-name ->
;; value), so this namespace has NO dependency on the token/shape runtime.
;;
;; This is the SCHEMA + EVALUATOR only. Wiring it into the viewer's
;; interaction dispatch (evaluating a :conditional's condition before
;; running its then/else action list, and evaluating a :set-variable
;; expression before writing the variable) is high blast-radius (touches
;; every viewer shape wrapper) and is DEFERRED — see the note in
;; interactions.cljc and the feature notes.

(ns app.common.expressions
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; An expression is stored as an S-expression-style vector so it is
;; trivially serializable and needs no parser in the common layer:
;;   ["+" 1 2]            => 3
;;   ["==" ["get" "x"] 3]  => true when variable x == 3
;;   ["and" ["<" 1 2] [">" 3 0]] => true
;; A leaf is a literal (number / string / boolean / nil), or a reference
;; vector ["get" <variable-name-string>]. The viewer interprets the
;; result; this evaluator only resolves + computes.
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
;; is responsible for dispatching them (deferred).
(def schema:conditional-action
  [:map {:title "ConditionalAction"}
   [:condition schema:expression]
   [:then-actions {:optional true} [:vector ::sm/any]]
   [:else-actions {:optional true} [:vector ::sm/any]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EVALUATOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- lookup-or-literal
  [node lookup]
  (cond
    (and (vector? node) (= (first node) "get"))
    (let [var-name (second node)]
      (when (some? lookup) (lookup var-name)))

    (vector? node)
    (eval node lookup)

    :else node))

(defn eval
  "Evaluate an expression node. `lookup` is a fn (variable-name -> value) or
  nil; literals pass through unchanged. Returns the computed value or nil
  when a referenced variable is unresolved."
  [node lookup]
  (cond
    (not (vector? node)) node

    (= (first node) "get")
    (let [var-name (second node)]
      (when (some? lookup) (lookup var-name)))

    :else
    (let [[op & args] node
          args (mapv #(lookup-or-literal % lookup) args)]
      (case op
        "+"   (let [a (first args) b (second args)]
                (cond
                  (and (number? a) (number? b)) (+ a b)
                  (some? a) (dm/str a (or b ""))
                  :else nil))
        "-"   (let [a (first args) b (second args)]
                (when (and (number? a) (number? b)) (- a b)))
        "*"   (let [a (first args) b (second args)]
                (when (and (number? a) (number? b)) (* a b)))
        "/"   (let [a (first args) b (second args)]
                (when (and (number? a) (number? b) (not (zero? b))) (/ a b)))
        "=="  (= (first args) (second args))
        "!="  (not= (first args) (second args))
        ">"   (let [a (first args) b (second args)]
                (when (and (number? a) (number? b)) (> a b)))
        "<"   (let [a (first args) b (second args)]
                (when (and (number? a) (number? b)) (< a b)))
        ">="  (let [a (first args) b (second args)]
                (when (and (number? a) (number? b)) (>= a b)))
        "<="  (let [a (first args) b (second args)]
                (when (and (number? a) (number? b)) (<= a b)))
        "and" (boolean (every? identity args))
        "or"  (boolean (some identity args))
        "not" (not (first args))
        nil))))

(defn truthy?
  "Evaluate a condition expression and return a boolean for conditional
  dispatch. nil/unresolved => false (the then-branch is not taken)."
  [condition lookup]
  (boolean (eval condition lookup)))