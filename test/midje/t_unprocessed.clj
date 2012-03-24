;; -*- indent-tabs-mode: nil -*-

(ns midje.t-unprocessed
  (:use midje.sweet
        midje.test-util))


;; Tool creators can hook into the maps generated by the Midje compilation process

(defn-call-countable noop-fn [& args] :do-nothing)
(binding [midje.unprocessed/*expect-checking-fn* noop-fn]
  (fact :ignored => :ignored))
(fact @noop-fn-count => 1)