(ns leiningen.debian
  (:require [clojure.string :as str]))

(defn- get-artifact-id
  [dependency]
  (if-let [dep-str (and dependency (name dependency))]
    (last (str/split dep-str #"/"))))

(defn- scope-allowed?
  [project m]
  (or  (not= (:scope m) "test")
       (:package-test-dependencies project)))

(defn- build-debian-name
  [project dependency version args]
  (let [artifactId (get-artifact-id dependency)
        m          (and (not-empty args) (apply assoc {} args))]
    (if (scope-allowed? project m)
      (if (contains? m :debian)
        (if-let [override (:debian m)]
          [(first override) (second override)])
        [(str "lib" artifactId "-java") version]))))

(defn- get-dependencies
  [project]
  (filter (comp not nil?)
          (for [[dependency version & rest] (:dependencies project)]
            (let [[version rest]            (if (keyword? version)
                                              [nil (conj rest version)]
                                              [version rest])]
              (build-debian-name project dependency version rest)))))

(defn debian
  "Generates a Debian package for build products"
  [project & args]
  (println (get-dependencies project)))


