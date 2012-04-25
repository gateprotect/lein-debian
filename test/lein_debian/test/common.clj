(ns lein-debian.test.common
  (:use [clojure.test :only (deftest)]
        [midje.sweet]
        [lein-debian.common]))

(deftest get-artifact-id-test
  (facts
   (get-artifact-id nil)        => nil
   (get-artifact-id 'a)         => "a"
   (get-artifact-id 'a/b)       => "b"
   (get-artifact-id 'a.b/c)     => "c"
   (get-artifact-id 'a.b.c/d)   => "d"
   (get-artifact-id ['a.b.c/d]) => (throws ClassCastException)))

