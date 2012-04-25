(ns lein-debian.package
  (:require [clojure.string     :as    str]
            [leiningen.jar      :as    jar])
  (:use     [clojure.java.shell :only (sh)]
            [lein-debian.common])
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
  (let [artifact-id (get-artifact-id dependency)
        m           (and (not-empty args) (apply assoc {} args))]
    (if (scope-allowed? project m)
      (if (contains? m :debian)
        (if-let [override (:debian m)]
          [(first override) (second override)])
        [artifact-id
         (build-debian-version version (:build-number project))]))))

(defn- get-dependencies
  [project]
  (filter (comp not nil?)
          (for [[dependency version & rest] (:dependencies project)]
            (let [[version rest]            (if (keyword? version)
                                              [nil (conj rest version)]
                                              [version rest])]
              (build-debian-name project dependency version rest)))))

(defn maybe-from-script
  [commands]
  (let [commands (str/trim commands)]
    (if (.startsWith commands "!")
      (str/join "\n" (slurp (.substring commands 1)))
      commands)))

(defn install-helper
  [debian-dir project script type cases]
  (if-let [commands (or (type project))]
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
  [debian-dir project]
  (install-helper debian-dir project  "preinst" :deb-pre-install "install|upgrade"))

(defn write-postinst
  [debian-dir project]
  (install-helper debian-dir project "postinst" :deb-post-install "configure"))

(defn write-prerm
  [debian-dir project]
  (install-helper debian-dir project "prerm" :deb-pre-remove "remove|upgrade|deconfigure"))

(defn write-postrm
  [debian-dir project]
  (install-helper
   debian-dir project "postrm" :deb-post-remove
   "purge|remove|upgrade|failed-upgrade|abort-install|abort-upgrade|disappear"))


(defn package
  [project args]
  (and (jar/jar project)
       (let [dependencies         (get-dependencies project)
             pkg-name             (:deb-name project (get-artifact-id (:name project)))
             version              (make-version project)
             base-dir             (str/trim (:out (sh "pwd")))
             target-dir           (:target-path project (path base-dir target-subdir))
             package-dir          (path target-dir (str pkg-name "-" version))
             debian-dir           (path package-dir "debian")
             install-dir          (:deb-install-dir project install-dir)
             arch                 (:deb-architecture project architecture)]
         (sh mkdir "-p" debian-dir)
         (write-lines (path debian-dir "control")
                      [(str "Source: "            pkg-name)
                       (str "Section: "           (:deb-section project  section))
                       (str "Priority: "          (:deb-priority project priority))
                       (str "Maintainer: "        (:deb-maintainer project maintainer))
                       (str "Build-Depends: "     (:deb-build-depends project build-depends))
                       (str "Standards-Version: " (:deb-standards-version project standards-version))
                       (str "Homepage: "          (:deb-homepage  homepage))
                       ""
                       (str "Package: "           pkg-name)
                       (str "Architecture: "      arch)
                       (str "Depends: "           (format-dependencies dependencies))
                       (str "Description: "       (format-description project))])

         (when-not (empty? dependencies)
           (apply println "Depends on" (map package-spec dependencies)))

         (write-lines (path debian-dir "changelog")
                      [(str pkg-name " (" version ") unstable; urgency=low")
                       ""
                       "  * Initial Release."
                       ""
                       (str " -- "
                            (:deb-maintainer project maintainer) "  "
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
                                  (str/replace (:deb-files project files) #"\s+" " ")
                                  "$(INSTALLDIR)"])])
         ((juxt write-preinst write-postinst write-prerm write-postrm)
          debian-dir project)
         (sh rm "-fr" "debhelper.log" :dir debian-dir )
         (let [r (sh debuild "--no-tgz-check" :dir debian-dir)]
           (if-not (= (:exit r) 0)
             (err (:out r)
                  "\nFailed to build Debian package. Errors follow:\n"
                  (:err r))
             (println "Created" (str target-dir "/" pkg-name "_" version "_" arch ".deb")))))))
