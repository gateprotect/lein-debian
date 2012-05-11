(ns lein-debian.package
  (:require [clojure.string              :as    str]
            [leiningen.jar               :as    jar])
  (:use     [clojure.java.shell          :only (sh)]
            [lein-debian.common]
            [cemerick.pomegranate.aether :only (resolve-dependencies)])
  (:import [java.util Date Properties Map Locale]
           [java.text SimpleDateFormat]))

(defn- package-spec
  [package]
  (str (first package)
       (if-let [version (second package)]
         (str " (>= " version ")"))))

(defn- format-dependencies
  [dependencies]
  (str/join ", " (conj (map package-spec dependencies) "${misc:Depends}")))

(defn- format-description
  [configuration]
  (let [description (or (:description configuration) description)
        lines       (str/split-lines description)]
    (apply str
           (first lines) "\n"
           (map #(str " " (str/replace %1 #"\s+" " ") "\n") (rest lines)))))

(defn- scope-allowed?
  [project m]
  (or  (not= (:scope m) "test")
       (:package-test-dependencies project)))

(defn- build-debian-name
  [project dependency version args]
  (let [artifact-id (get-debian-name dependency)
        m           (and (not-empty args) (apply assoc {} args))]
    (if (scope-allowed? project m)
      (if (contains? m :debian)
        (if-let [override (:debian m)]
          [(first override) (second override)])
        [artifact-id
         (build-debian-version version (:build-number project))]))))

(defn- get-dependencies
  [project]
  (concat
   (filter (comp not nil?)
           (for [[dependency version & rest] (:dependencies project)]
             (let [[version rest]            (if (keyword? version)
                                               [nil (conj rest version)]
                                               [version rest])]
               (build-debian-name project dependency version rest))))
   (get-in project [:debian :dependencies])))

(defn maybe-from-script
  [commands]
  (let [commands (str/trim commands)]
    (if (.startsWith commands "!")
      (str/join "\n" (slurp (.substring commands 1)))
      commands)))

(defn install-helper
  [debian-dir config script type cases]
  (if-let [commands (or (type config))]
    (let [commands  (maybe-from-script commands)]
      (spit (path debian-dir script)
            (reduce str ["#!/bin/sh"
                         "set -e"
                         "case \"$1\" in"
                         (str cases ")")
                         commands
                         "    ;;"
                         "esac"
                         "#DEBHELPER#"
                         "exit 0"])))))

(defn write-preinst
  [debian-dir config]
  (install-helper debian-dir config  "preinst" :pre-install "install|upgrade"))

(defn write-postinst
  [debian-dir config]
  (install-helper debian-dir config "postinst" :post-install "configure"))

(defn write-prerm
  [debian-dir config]
  (install-helper debian-dir config "prerm" :pre-remove "remove|upgrade|deconfigure"))

(defn write-postrm
  [debian-dir config]
  (install-helper
   debian-dir config "postrm" :post-remove
   "purge|remove|upgrade|failed-upgrade|abort-install|abort-upgrade|disappear"))


(defn build-package
  [project]
  (let [dependencies         (get-dependencies project)
        config               (:debian project)
        pkg-name             (:name config (get-debian-name (:name project)))
        version              (make-version (if (contains? config :version) config project))
        base-dir             (str/trim (:out (sh "pwd")))
        target-dir           (:target-path project (path base-dir target-subdir))
        package-dir          (path target-dir (str pkg-name "-" version))
        debian-dir           (path package-dir "debian")
        install-dir          (:install-dir config install-dir)
        arch                 (:architecture config architecture)]
    (sh mkdir "-p" debian-dir)
    (write-lines (path debian-dir "control")
                 [(str "Source: "            pkg-name)
                  (str "Section: "           (:section config  section))
                  (str "Priority: "          (:priority config priority))
                  (str "Maintainer: "        (:maintainer config maintainer))
                  (str "Build-Depends: "     (:build-depends config build-depends))
                  (str "Standards-Version: " (:standards-version config standards-version))
                  (str "Homepage: "          (:homepage config homepage))
                  ""
                  (str "Package: "           pkg-name)
                  (str "Architecture: "      arch)
                  (str "Depends: "           (format-dependencies dependencies))
                  (str "Description: "       (format-description config))])

    (when-not (empty? dependencies)
      (apply println "Depends on" (map package-spec dependencies)))

    (write-lines (path debian-dir "changelog")
                 [(str pkg-name " (" version ") unstable; urgency=low")
                  ""
                  "  * Initial Release."
                  ""
                  (str " -- "
                       (:maintainer config maintainer) "  "
                       (.format (SimpleDateFormat. "EEE, d MMM yyyy HH:mm:ss Z"
                                                   (Locale/CANADA)) (Date.)))])
    (write-lines (path debian-dir "rules")
                 ["#!/usr/bin/make -f"
                  "%:"
                  "\tdh $@"])

    (write-lines (path package-dir "Makefile")
                 [(str "INSTALLDIR := $(DESTDIR)/" install-dir)
                  "build:"
                  ""
                  "install:"
                  "\t@mkdir -p $(INSTALLDIR)"
                  (str/join " "
                            ["\t@cd" target-dir "&&"
                             copy "-a"
                             (str/replace (:files config files) #"\s+" " ")
                             "$(INSTALLDIR)"])])
    
    ((juxt write-preinst write-postinst write-prerm write-postrm)
     debian-dir config)
    (sh rm "-fr" "debhelper.log" :dir debian-dir )
    (let [r (sh debuild "--no-tgz-check" :dir debian-dir)]
      (if-not (= (:exit r) 0)
        (err (:out r)
             "\nFailed to build Debian package. Errors follow:\n"
             (:err r))
        (println "Created" (str target-dir "/" pkg-name "_" version "_" arch ".deb")))))  )

(defn- get-filename
  [f]
  (.toString f))

(defn- parse-repositories
  "Given a string of the form repo1=url1,repo2=url2,... returns a map of the form
   {repo1 url1, repo2 url2,...}"
  [config]
  (if-let [repos (:repositories config)]
    (apply
     assoc {}
     (flatten
      (for [entry (str/split repos #",")]
        (let [[name repo] (str/split entry #"=")]
          [name repo]))))))

(defn- keywordize
  [str]
  (keyword
   (if (.startsWith str ":")
     (.substring str 1)
     str)))

(defn package
  [project args]
  (let [args (next args) ]
    (if-not (empty args)
      (and (jar/jar project)
           (build-package project))
      (let [[artifact-id version & rest] args
            artifact-id  (symbol artifact-id)
            config       (if-not (empty? rest)
                           (reduce (fn [m [k v]]
                                     (assoc m (keywordize k) v)) {} (partition 2 rest)))
            coordinates  [artifact-id version]
            repositories (or (parse-repositories config)
                             (merge cemerick.pomegranate.aether/maven-central
                                    {"clojars" "http://clojars.org/repo"}))
            dependencies (resolve-dependencies
                          :coordinates  [coordinates]
                          :repositories repositories)
            jar-file     (-> dependencies (find coordinates) first meta :file get-filename)]
        (build-package
         (merge {:debian config}
                (assoc project
                  :debian
                  {:name    (:name config (get-debian-name artifact-id))
                   :version (:version config version)
                   :files   jar-file}
                  :dependencies (get dependencies coordinates))))))))
