(ns lein-debian.deps
  (:require [leiningen.deps     :as leiningen]
            [clojure.stacktrace :as trace])
  (:use [cemerick.pomegranate.aether :only (resolve-dependencies)]
        [lein-debian.package         :only (package)]))

(defn- excluded?
  [project pkg]
  (not (nil?
        (if-let [exclusions (get-in project [:debian :exclusions])]
          (some (fn [re]
                  (let [re (if (symbol? re) (name re) re)
                        re (re-pattern re)]
                    (re-find re (name pkg)))) exclusions)))))
(defn deps
  [project args]
  (leiningen/deps project)
  (let [args         (next args)
        artifact-id  (:name project)
        version      (:version project)
        repositories (:repositories project)
        coordinates  (:dependencies project)
        dependencies (keys (resolve-dependencies
                            :coordinates coordinates
                            :repositories repositories))]
    (doseq [[name version] dependencies :when (not (excluded? project name))]
      (try
        (package project (concat [nil name version] args))
        (catch java.lang.RuntimeException e
          (println "Failed to build Debian package for " name ":" (.getMessage e))
          (trace/print-stack-trace e))))))
