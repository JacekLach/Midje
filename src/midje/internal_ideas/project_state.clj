(ns ^{:doc "What we know about the changing project file/namespace tree."}
  midje.internal-ideas.project-state
  (:use [midje.util.form-utils :only [invert]]
        [swiss-arrows.core :only [-<>]]
        [bultitude.core :only [namespaces-in-dir namespaces-on-classpath]])
  (:require [midje.util.ecosystem :as ecosystem]
            [midje.util.colorize :as color]
            [midje.config :as config]
            [midje.ideas.reporting.levels :as levelly]
            midje.util.backwards-compatible-utils))

(ecosystem/when-1-3+

 (require '[clojure.tools.namespace.repl :as nsrepl]
          '[clojure.tools.namespace.dir :as nsdir]
          '[clojure.tools.namespace.track :as nstrack]
          '[clojure.tools.namespace.reload :as nsreload]
          '[leiningen.core.project :as project])


;;; Querying the project tree

 (defn directories []
   (try
     (let [project (project/read)]
       (concat (:test-paths project) (:source-paths project)))
     (catch java.io.FileNotFoundException e
       ["test"])))

 (defn namespaces []
   (mapcat namespaces-in-dir (directories)))

 (defn unglob-partial-namespaces [namespaces]
   (mapcat #(if (= \* (last %))
              (namespaces-on-classpath :prefix (apply str (butlast %)))
              [(symbol %)])
           (map str namespaces)))


 ;;; Responding to changed files

 ;; tools.ns keys are annoyingly long. Shorthand.
 (def unload-key :clojure.tools.namespace.track/unload)
 (def load-key :clojure.tools.namespace.track/load)
 (def filemap-key :clojure.tools.namespace.file/filemap)
 (def deps-key :clojure.tools.namespace.track/deps)
 (def time-key :clojure.tools.namespace.dir/time)

 ;; Global state.

 (defonce state-tracker (atom (nstrack/tracker)))

 (defn file-modification-time [file]
   (.lastModified file))

 (defn latest-modification-time [state-tracker]
   (let [ns-to-file (invert (filemap-key state-tracker))
         relevant-files (map ns-to-file (load-key state-tracker))]
     (apply max (time-key state-tracker)
            (map file-modification-time relevant-files))))


 (defn inform-user-of-require-failure [the-ns throwable]
   (println (color/fail "LOAD FAILURE for " (ns-name the-ns)))
   (println (.getMessage throwable))
   (when (config/running-in-repl?)
     (println "The exception has been stored in #'*e, so `pst` will show the stack trace.")
     (if (thread-bound? #'*e)
       (set! *e throwable)
       (alter-var-root #'clojure.core/*e (constantly throwable)))))

 (defn require-namespaces! [namespaces clean-dependents]
   (letfn [(broken-source-file? [the-ns]
             (try
               (require the-ns :reload)
               false
             (catch Throwable t
               (inform-user-of-require-failure the-ns t)
               true)))

           (shorten-ns-list-by-trying-first [[the-ns & remainder]]
             (if (broken-source-file? the-ns)
               (clean-dependents the-ns remainder)
               remainder))]

   (loop [namespaces namespaces]
     (when (not (empty? namespaces))
       (recur (shorten-ns-list-by-trying-first namespaces))))))

 (defn dependents-cleaner-fn [state-tracker]
   (fn [namespace possible-dependents]
     (let [actual-dependents (set (get-in state-tracker [deps-key :dependents namespace]))]
       (remove actual-dependents possible-dependents))))

 (defn react-to-tracker! [state-tracker]
   (let [namespaces (load-key state-tracker)]
     (when (not (empty? namespaces))
       (println (color/note "\n==========="))
       (println (color/note "Loading " (pr-str namespaces)))
       (levelly/forget-past-results)
       (require-namespaces! namespaces
                            (dependents-cleaner-fn state-tracker))
       (levelly/report-summary))
     state-tracker))

 (defn prepare-for-next-scan [state-tracker]
   (assoc state-tracker time-key (latest-modification-time state-tracker)
                        unload-key []
                        load-key []))

 (defn scan-and-react-fn [dirs scanner]
   (fn []
     (swap! state-tracker
            #(let [new-tracker (apply scanner % dirs)]
               (react-to-tracker! new-tracker)
               (prepare-for-next-scan new-tracker)))))


 (defn react-to-changes-fn [dirs]
   (scan-and-react-fn dirs nsdir/scan))

 (defn load-everything [dirs]
   ((scan-and-react-fn dirs nsdir/scan-all)))

)
