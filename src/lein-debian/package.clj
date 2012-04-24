(ns lein-debian.package
  (:require [clojure.string :as str])
  (:use     [clojure.java.shell :only (sh)]
            [lein-debian.common])
  (:import [java.util Date Properties Map Locale]
           [java.text SimpleDateFormat]))

(defn build
  [project dependencies]
  (let [pkg-name             (:deb-name project (get-artifact-id (:name project)))
        version              (make-version project)
        base-dir             (:out (sh "pwd"))
        target-dir           (path base-dir (:target-path project target-subdir))
        package-dir          (path target-dir (str pkg-name "-" version))
        debian-dir           (path package-dir "debian")
        install-dir          (:deb-install-dir project install-dir)]
    (sh mkdir "-p" debian-dir)
    (duck/write-lines
     (path debian-dir "control")
     [(str "Source: "           artifact-id)
      (str "Section: "           (:section configuration section))
      (str "Priority: "          (:priority configuration priority))
      (str "Maintainer: "        (:maintainer configuration maintainer))
      (str "Build-Depends: "     (:buildDepends configuration build-depends))
      (str "Standards-Version: " (:standardsVersion configuration standards-version))
      (str "Homepage: "          (:homepage configuration homepage))
      ""
      (str "Package: "           artifact-id)
      (str "Architecture: "      (:architecture configuration architecture))
      (str "Depends: "           (format-dependencies dependencies))
      (str "Description: "       (format-description configuration))])
    (doseq [pkg dependencies :when (not-empty pkg)]
          (.info (.getLog this) (str "Depends on " (package-spec pkg))))
    (duck/write-lines
     (path debian-dir "changelog")
     [(str artifact-id " (" version ") unstable; urgency=low")
      ""
      "  * Initial Release."
      ""
      (str " -- "
           (:maintainer configuration maintainer) "  "
           (.format (SimpleDateFormat. "EEE, d MMM yyyy HH:mm:ss Z"
                                       (Locale/CANADA)) (Date.)))])
    (duck/write-lines
     (path debian-dir "rules")
     ["#!/usr/bin/make -f" "%:"
      "\tdh $@"])
    (duck/write-lines
     (path package-dir "Makefile")
     [(str "INSTALLDIR := $(DESTDIR)/" install-dir)
      "build:"
      ""
      "install:"
      "\t@mkdir -p $(INSTALLDIR)"
      (str/join " "
                ["\t@cd" target-dir "&&"
                 copy "-a"
                 (str/replace (:files configuration files) #"\s+" " ")
                 "$(INSTALLDIR)"])])
    ((juxt write-preinst write-postinst write-prerm write-postrm)
     debian-dir configuration)
    (sh rm "-fr" "debhelper.log" :dir debian-dir )
    (.info (.getLog this) (sh debuild :dir debian-dir))))