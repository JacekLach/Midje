(ns midje.parsing.t-fakes
  (:use [midje sweet test-util]
        midje.parsing.2-to-lexical-maps.fakes
        [utilize.seq :only (find-first only)]
        midje.util
        clojure.pprint))
