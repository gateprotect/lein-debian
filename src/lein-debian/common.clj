(ns lein-debian.common
  (:require [clojure.string :as str]))

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

(def maintainer             "Oster Hase <osterhase@rapanui.com>")
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

(defn make-version
  [project]
  (let [version   (:version project)
        build-num (:build-number project)]
    (str/join [version (if build-num (str "." build-num))])))

(defn get-artifact-id
  [dependency]
  (if-let [dep-str (and dependency (name dependency))]
    (last (str/split dep-str #"/"))))

