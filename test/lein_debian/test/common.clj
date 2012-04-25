(ns lein-debian.test.common
  (:use [clojure.test :only (deftest)]
        [midje.sweet]
        [lein-debian.common]))

(deftest get-artifact-id-test
  (facts
   (get-artifact-id nil)        => nil
   (get-artifact-id 'a)         => "liba-clojure"
   (get-artifact-id 'a/b)       => "libb-clojure"
   (get-artifact-id 'a.b/c)     => "libc-clojure"
   (get-artifact-id 'a.b.c/d)   => "libd-clojure"
   (get-artifact-id ['a.b.c/d]) => (throws ClassCastException)))

