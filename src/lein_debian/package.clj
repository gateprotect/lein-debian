(ns lein-debian.package
  (:require [clojure.string              :as str]
            [leiningen.uberjar           :as uberjar]
            [leiningen.jar               :as jar]
            [cemerick.pomegranate.aether :as aether])
  (:use     [clojure.java.shell          :only (sh)]
            [clojure.java.io             :only (file)]
            [lein-debian.common]
            [cemerick.pomegranate.aether :only (resolve-dependencies)])
  (:import [java.util Date Properties Map Locale]
           [java.text SimpleDateFormat]))

(def less-than    " (<= ")
(def greater-than " (>= ")

(defn version-spec
  [name op version]
  (str name
       op
       (if (Character/isDigit (first version))
         version
         (str "1.0-" version))
       ")"))

(defn- package-spec
  [package]
  (if-let [n (first package)]
    (if-let [version (second package)]
      (if (vector? version)
        (vector (version-spec
               n greater-than (first version))
             (version-spec
               n less-than (second version)))
        (version-spec n greater-than version))
      n)))

(defn- package-specs [dependencies]
  (->> dependencies
       (map package-spec)
       (flatten)))

(defn- format-dependencies
  [dependencies]
  (str/join ", " (conj (package-specs dependencies) "${misc:Depends}")))

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
  ([project ignore-jar-deps]
     (concat
      (filter (comp not nil?)
              (for [[dependency version & rest] (:dependencies project)
                    :when (or (not ignore-jar-deps)
                              (some #{:debian} rest))]
                (build-debian-name project dependency version rest)))
      (get-in project [:debian :dependencies])))
  ([project]
     (get-dependencies false)))

(defn- copy-files [from to & args]
  (if (empty? args)
    ""
    (str/join " "
      (concat
       ["\t@cd" from "&&"
        copy "-a"]
       args
       [to]))))

(defn- link-artifact
  ([artifact-file artifact-id prefix-dir]
     (str "\t@cd $(INSTALLDIR) && ln -snf " install-dir "/" artifact-file " " prefix-dir artifact-id ".jar"))
  ([artifact-file artifact-id]
     (link-artifact artifact-file artifact-id "")))

(defn maybe-from-script
  [commands]
  (let [commands (str/trim commands)]
    (if (.startsWith commands "!")
      (slurp (.substring commands 1))
      commands)))

(defn install-helper
  ([debian-dir config script type cases]
     (install-helper debian-dir config script type cases true))
  ([debian-dir config script type cases wrap-sh]
     (if-let [commands (or (type config))]
       (let [commands  (maybe-from-script commands)]
         (spit (path debian-dir script)
               (if wrap-sh
                 (str/join "\n" ["#!/bin/sh"
                                 "set -e"
                                 "case \"$1\" in"
                                 (str cases ")")
                                 commands
                                 "    ;;"
                                 "esac"
                                 "#DEBHELPER#"
                                 "exit 0"])
                 commands))))))

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

(defn write-triggers
  [debian-dir config]
  (install-helper debian-dir config "triggers" :triggers "" false))

(defmulti get-archive-path (fn [x _] x))

(defmethod get-archive-path :jar
  [_ project]
  (jar/get-jar-filename project))

(defmethod get-archive-path :uberjar
  [_ project]
  (jar/get-jar-filename project true))

(defmethod get-archive-path :overjar
  [_ project]
  (jar/get-jar-filename project true))

(defn- group
  [group-artifact]
  (or (namespace group-artifact) (name group-artifact)))

(defn- my-coord-string
  [[group-artifact version & {:keys [classifier extension] :or {extension "war"}}]]
  (->> [(group group-artifact) (name group-artifact) extension classifier version]
    (remove nil?)
    (interpose \:)
    (apply str)))

(defmethod get-archive-path :none
  [_ project]
  nil)

(defmethod get-archive-path :war
  [_ project]
  (if-let [wardep (get-in project [:debian :archive])]
    (with-redefs [aether/coordinate-string my-coord-string]
      (let [deps (aether/resolve-dependencies
                  :coordinates [wardep]
                  :repositories (:repositories project) )]
        (-> (aether/dependency-files (filter #(= (first (first %))
                                                 (first wardep)) deps))
            first
            (.getPath))))))

(defn build-package
  [project]
  (let [artifact-id  (:name project)
        config       (:debian project)
        pkg-type     (:archive-type config :jar)
        ign-jardeps  (:ignore-maven-dependencies config)
        dependencies (get-dependencies project ign-jardeps)
        pkg-name     (:name config (get-debian-name artifact-id))
        version      (make-version (if (contains? config :version) config project))
        base-dir     (:root project (str/trim (:out (sh "pwd"))))
        files        (:files config (get-archive-path pkg-type project))
        extras-dir   (path base-dir (:extra-path config "debian"))
        target-dir   (:target-path project (path base-dir target-subdir))
        package-dir  (path target-dir (str pkg-name "-" version))
        debian-dir   (path package-dir "debian")
        prefix-dir   (:prefix-archive-dir config)
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
      (apply println "Depends on" (package-specs dependencies)))

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
      (when files
        (copy-files target-dir "$(INSTALLDIR)"
                    (if-not (.startsWith files "/")
                      (path  base-dir files)
                      files)))
      (when-not (empty? (:extra-files config))
        (apply copy-files extras-dir "$(DESTDIR)" "--parents" (:extra-files config)))
      (when prefix-dir
        (str "\tmkdir -p $(INSTALLDIR)/" prefix-dir))
      (when files
        (link-artifact (-> files file (.getName)) artifact-id prefix-dir)))
    ((juxt write-preinst write-postinst write-prerm write-postrm write-triggers)
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

(defmulti get-pkg-builder #(:archive-type (:debian %) :jar))

(defmethod get-pkg-builder :default
  [project]
  (if-let [archive-type (:archive-type (:debian project))]
    (if-let [builder (name archive-type)]
      (do
        (require (symbol (str "leiningen." builder)))
        (resolve (symbol (str "leiningen." builder) builder))))))

(defmethod get-pkg-builder :jar
  [_] jar/jar)

(defmethod get-pkg-builder :uberjar
  [_] uberjar/uberjar)

(defmethod get-pkg-builder :war
  [_] (constantly true))

(defmethod get-pkg-builder :none
  [_] (constantly true))

(defn package
  [project args]
  (let [args (next args)]
    (if (nil? args)
      (let [pkg-builder (get-pkg-builder project)]
        (and (pkg-builder project)
             (build-package project)))
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
                                    (apply merge (for [[k v] (:repositories project)] {k v}))))
            dependencies (resolve-dependencies
                          :coordinates  [coordinates]
                          :repositories repositories)
            jar-file     (-> dependencies (find coordinates) first meta :file get-filename)]
        (when-not (:dry-run config)
          (build-package
           (assoc project
             :debian (merge (:debian project)
                            config
                            {:name         (:name config (get-debian-name artifact-id))
                             :version      (:version config version)
                             :files        jar-file
                             :extra-files  []
                             :dependencies []})
             :name          artifact-name
             :dependencies (get dependencies coordinates))))))))
