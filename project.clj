(defproject lein-debian "0.1.0-SNAPSHOT"
  :description "Leiningen plugin to generate Debian packages"
  :url "http://github.com/erickg/lein-debian"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in-leiningen true
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [com.cemerick/pomegranate "0.0.11"]
                 [midje "1.3.1" :scope "test"]])
