(ns ^{:doc "Core Midje functions that process expects and report on their results."} 
  midje.checking.checkables
  (:use midje.clojure.core
        midje.checking.core
        [midje.util.exceptions :only [captured-throwable]]
        midje.data.prerequisite-state
        midje.util.laziness)
  (:require [midje.config :as config]
            [midje.data.nested-facts :as nested-facts]
            [midje.emission.boundaries :as emission-boundary]
            [midje.parsing.1-to-explicit-form.parse-background :as parse-background]
            [midje.emission.api :as emit]))


(defn- minimal-failure-map
  "Failure maps are created by adding on to parser-created maps"
  [type actual existing]
  (let [base (assoc existing :type type :actual actual)
        table-bindings (nested-facts/table-bindings)]
    (if (empty? table-bindings)
      base
      (assoc base :midje/table-bindings table-bindings))))

(def ^{:private true} has-function-checker? (comp extended-fn? :expected-result))

(defn map-record-mismatch-addition [actual expected]
  {:notes [(inherently-false-map-to-record-comparison-note actual expected)]})

(defn- check-for-match [actual checkable-map]
  (let [expected (:expected-result checkable-map)]
    (cond  (extended-= actual expected)
           (emit/pass)
         
           (has-function-checker? checkable-map)
           (emit/fail (merge (minimal-failure-map :actual-result-did-not-match-checker
                                                  actual checkable-map)
                             ;; TODO: It is very lame that the
                             ;; result-function has to be called again to
                             ;; retrieve information that extended-=
                             ;; knows and threw away. But it's surprisingly
                             ;; difficult to use evaluate-checking-function
                             ;; at the top of the cond
                             (second (evaluate-checking-function expected actual))))
         
           (inherently-false-map-to-record-comparison? actual expected)
           (emit/fail (merge (minimal-failure-map :actual-result-did-not-match-expected-value actual checkable-map)
                             (map-record-mismatch-addition actual expected)))
         
           :else
           (emit/fail (assoc (minimal-failure-map :actual-result-did-not-match-expected-value actual checkable-map)
                             :expected-result expected)))))


(defn- check-for-mismatch [actual checkable-map]
  (let [expected (:expected-result checkable-map)]
    (cond (inherently-false-map-to-record-comparison? actual expected)
          (emit/fail (merge (minimal-failure-map :actual-result-should-not-have-matched-expected-value actual checkable-map)
                            (map-record-mismatch-addition actual expected)))

          (not (extended-= actual expected))
          (emit/pass)

          (has-function-checker? checkable-map)
          (emit/fail (minimal-failure-map :actual-result-should-not-have-matched-checker actual checkable-map))
        
          :else
          (emit/fail (minimal-failure-map :actual-result-should-not-have-matched-expected-value actual checkable-map)))))


(defn- check-result [actual checkable-map]
  (if (= (:check-expectation checkable-map) :expect-match)
    (check-for-match actual checkable-map)
    (check-for-mismatch actual checkable-map)))



(defmulti call-count-incorrect? :type)

(defmethod call-count-incorrect? :fake [fake]
  (let [method (:times fake)
        count @(:call-count-atom fake)]
    (pred-cond method 
      #(= % :default) (zero? count)
      number?         (not= method count)
      coll?           (not-any? (partial = count) method)
      fn?             (not (method count)))))

(defmethod call-count-incorrect? :not-called [fake]
  (not (zero? @(:call-count-atom fake))))

(defn report-incorrect-call-counts [fakes]
  (when-let [failures (seq (for [fake fakes
                                 :when (call-count-incorrect? fake)]
                              {:actual-count    @(:call-count-atom fake)
                               :expected-count  (:times fake)
                               :expected-call   (:call-text-for-failures fake)
                               :position        (:position fake)
                               :expected-result-form        (:call-text-for-failures fake)}))]
    (emit/fail {:type :some-prerequisites-were-called-the-wrong-number-of-times
                :failures failures
                :position (:position (first failures))
                :namespace *ns*})))


(defn check-one
  "Takes a map describing a single checkable, plus some function-redefinition maps
   and checks the checkable, reporting results through the emission interface."
  [checkable-map local-fakes]
  ((config/choice :check-recorder) checkable-map local-fakes)
  (with-installed-fakes (concat (reverse (filter :data-fake (parse-background/background-fakes)))
                                local-fakes
                                (remove :data-fake (parse-background/background-fakes)))
    (emission-boundary/around-check 
      (let [actual (try  
                     (eagerly ((:function-under-test checkable-map)))
                    (catch Throwable ex
                      (captured-throwable ex)))]
        (report-incorrect-call-counts local-fakes)
        (check-result actual checkable-map)
        :irrelevant-return-value))))
