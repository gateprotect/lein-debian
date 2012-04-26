(ns lein-debian.test.common
  (:use [clojure.test :only (deftest)]
        [midje.sweet]
        [lein-debian.common]))

(deftest get-artifact-id-test
  (facts
   (get-debian-name nil)        => nil
   (get-debian-name 'a)         => "liba-clojure"
   (get-debian-name 'a/b)       => "libb-clojure"
   (get-debian-name 'a.b/c)     => "libc-clojure"
   (get-debian-name 'a.b.c/d)   => "libd-clojure"
   (get-debian-name ['a.b.c/d]) => (throws ClassCastException)))

