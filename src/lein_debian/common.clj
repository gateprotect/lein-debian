(ns lein-debian.common
  (:use     [clojure.java.io :only (writer)])
  (:require [clojure.string   :as   str]))

;;; version separator character
;;; (used to separate the name from the version in a debian dependency property
(def vs "_")

(def mkdir          "mkdir")
(def fmt            "fmt")
(def debuild        "debuild")
(def copy           "cp")
(def ls             "ls")
(def rm             "rm")
(def bash           "/bin/bash")
(def apt-move       "apt-move")
(def apt-ftparchive "apt-ftparchive")
(def debian-cd      "debian-cd-3.1.7")
(def arches         "i386")

(def debfullname
  (or (System/getenv "DEBFULLNAME")
      "Oster Hase"))

(def debemail
  (or (System/getenv "DEBEMAIL")
      "osterhase@rapanui.com"))

(def maintainer
  (str debfullname " <" debemail ">"))

(def section                "contrib")
(def priority               "optional")
(def build-depends          "debhelper (>= 7.0.50~)")
(def standards-version      "3.9.1" )
(def homepage               "http://google.com")
(def architecture           "all")
(def description            "The Osterhase was too lazy to provide a description")
(def files                  "*.jar")
(def target-subdir          "target")
(def install-dir            "/usr/share/java")
(def apt-config-file        "config/apt.conf")
(def apt-move-config-file   "config/apt-move.conf")
(def dist                   "squeeze")
(def pkg-config-file        (str "config/" dist "-packages.conf"))

(defn path
  [element & more-elements]
  (str/join "/" (conj more-elements element)))

(defn build-debian-version
  [version build-num]
  (if version
    (let [version (str/replace version "-SNAPSHOT" "")]
      (str/join [version (if build-num (str "." build-num))]))))

(defn make-version
  [project]
  (let [version   (:version project)
        build-num (:build-number project)]
    (build-debian-version version build-num)))

(defn get-artifact-id
  [dependency]
  (if-let [dep-str (and dependency (name dependency))]
    (str "lib"
         (str/replace (last (str/split dep-str #"/")) "." "-")
         "-clojure")))

(defn err
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn write-lines
  [file coll]
  (with-open [stream (writer file)]
    (.write stream (apply str (map #(str %1 "\n") coll)))))
