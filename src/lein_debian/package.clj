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
  (let [artifact-id           (get-debian-name dependency)
        m                     (and (not-empty args) (apply assoc {} args))
        override              (if (contains? m :debian)
                                (:debian m)
                                (get-in project [:debian :overrides artifact-id]))
        [artifact-id version] (if override
                                (let [[deb-name deb-ver] override
                                      deb-ver            (if-not (nil? deb-ver)
                                                           (if (= deb-ver "")
                                                             nil
                                                             deb-ver)
                                                           version)]
                                  [deb-name deb-ver])
                                [artifact-id version])]
    (if (scope-allowed? project m)
      [artifact-id (build-debian-version version (:build-number project))])))

(defn- get-dependencies
  [project]
  (concat
   (filter (comp not nil?)
           (for [[dependency version & rest] (:dependencies project)]
             (build-debian-name project dependency version rest)))
   (get-in project [:debian :dependencies])))

(defn- copy-files [from to & args]
  (if (empty? args)
    ""
    (str/join " "
      (concat
       ["\t@cd" from "&&"
        copy "-a" "--parents"]
       args
       [to]))))

(defn- link-artifact
  [files artifact-id]
  (let [re            (java.util.regex.Pattern/compile (str artifact-id "-.*.jar"))
        artifact-file (str artifact-id "-*.jar")]
    (str "\t@cd $(INSTALLDIR) && ln -snf " artifact-file " " artifact-id ".jar")))

(defn maybe-from-script
  [commands]
  (let [commands (str/trim commands)]
    (if (.startsWith commands "!")
      (slurp (.substring commands 1))
      commands)))

(defn install-helper
  [debian-dir config script type cases]
  (if-let [commands (or (type config))]
    (let [commands  (maybe-from-script commands)]
      (spit (path debian-dir script)
            (str/join "\n" ["#!/bin/sh"
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
  (let [dependencies (get-dependencies project)
        artifact-id  (:name project)
        config       (:debian project)
        pkg-name     (:name config (get-debian-name artifact-id))
        version      (make-version (if (contains? config :version) config project))
        base-dir     (:root project (str/trim (:out (sh "pwd"))))
        files        (:files config files)
        extras-dir   (path base-dir (:extra-path config "debian"))
        target-dir   (:target-path project (path base-dir target-subdir))
        package-dir  (path target-dir (str pkg-name "-" version))
        debian-dir   (path package-dir "debian")
        install-dir  (:install-dir config install-dir)
        arch         (:architecture config architecture)
        dists        (:distributions config [distribution])]
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

    (write-lines* (path debian-dir "changelog")
      (str pkg-name " (" version ") " (str/join " " dists)  "; urgency=low")
      ""
      "  * Initial Release."
      ""
      (str " -- "
           (:maintainer config maintainer) "  "
           (.format (SimpleDateFormat. "EEE, d MMM yyyy HH:mm:ss Z"
                                       (Locale/CANADA)) (Date.))))
    
    (write-lines* (path debian-dir "rules")
      "#!/usr/bin/make -f"
      "%:"
      "\tdh $@")
    
    (write-lines* (path package-dir "Makefile")
      (str "INSTALLDIR := " (path "$(DESTDIR)" install-dir))
      "build:"
      ""
      "install:"
      "\t@mkdir -p $(INSTALLDIR)"
      (copy-files target-dir "$(INSTALLDIR)"
                  (path (if-not (.startsWith files "/")
                          base-dir)
                        files))
      (apply copy-files extras-dir "$(DESTDIR)" (:extra-files config))
      (link-artifact files artifact-id))
    ((juxt write-preinst write-postinst write-prerm write-postrm)
     debian-dir config)
    (sh rm "-fr" "debhelper.log" :dir debian-dir )
    (let [r (sh debuild "--no-tgz-check" :dir debian-dir)]
      (if-not (= (:exit r) 0)
        (err (:out r)
             "\nFailed to build Debian package. Errors follow:\n"
             (:err r))
        (println "Created" (str target-dir "/" pkg-name "_" version "_" arch ".deb"))))))

(defn- get-filename
  [f]
  (when f (.toString f)))

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
    (if (nil? args)
      (and (jar/jar project)
           (build-package project))
      (let [[artifact-id version & rest] args
            artifact-id   (symbol artifact-id)
            artifact-name (symbol (last (clojure.string/split (str artifact-id) #"/")))
            config        (if-not (empty? rest)
                            (reduce (fn [m [k v]]
                                      (assoc m (keywordize k) v)) {} (partition 2 rest)))
            coordinates  [artifact-id version]
            repositories (or (parse-repositories config)
                             (merge cemerick.pomegranate.aether/maven-central
                                    {"clojars" "http://clojars.org/repo"}
                                    (:repositories project)))
            dependencies (resolve-dependencies
                          :coordinates  [coordinates]
                          :repositories repositories)
            jar-file     (-> dependencies (find coordinates) first meta :file get-filename)]
        (when-not (:dry-run config)
          (build-package
           (assoc project
             :debian (merge (:debian project)
                            config
                            {:name    (:name config (get-debian-name artifact-id))
                             :version (:version config version)
                             :files   [jar-file]})
             :name          artifact-name
             :dependencies (get dependencies coordinates))))))))
