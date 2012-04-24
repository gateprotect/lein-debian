(ns leiningen.debian
  (:require [cemerick.pomegranate.aether :as aether]))

(defn debian
  "Generates a Debian package for build products"
  [project & args]
  (let [{:keys [dependencies repositories]} project]
    (println (aether/resolve-dependencies
              :coordinates dependencies
              :repositories repositories))))
