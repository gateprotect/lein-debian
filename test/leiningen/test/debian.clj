(ns leiningen.test.debian
  (:use [clojure.test :only (deftest)]
        [midje.sweet]
        [leiningen.debian]))

(deftest get-artifact-id-test
  (facts
   (#'leiningen.debian/get-artifact-id nil)        => nil
   (#'leiningen.debian/get-artifact-id 'a)         => "a"
   (#'leiningen.debian/get-artifact-id 'a/b)       => "b"
   (#'leiningen.debian/get-artifact-id 'a.b/c)     => "c"
   (#'leiningen.debian/get-artifact-id 'a.b.c/d)   => "d"
   (#'leiningen.debian/get-artifact-id ['a.b.c/d]) => (throws ClassCastException)))

(deftest get-dependencies-test
  (facts
   (#'leiningen.debian/get-dependencies
    {:dependencies [['a.b/c "x"]
                    ['a.b/c "x" :debian nil]
                    ['a.b/c "x" :debian ["d"]]
                    ['a.b/c "x" :debian ["d" "y"]]
                    ['a "x"]
                    ['a :debian ["b"]]
                    ['a]]})                         => [["libc-java" "x"]
                                                        ["d" nil]
                                                        ["d" "y"]
                                                        ["liba-java" "x"]
                                                        ["b" nil]
                                                        ["liba-java" nil]]
   (#'leiningen.debian/get-dependencies
    {:dependencies [['a.b/c "x" :scope "test"]
                    ['a.b.c "y"]]})                  => [["liba.b.c-java" "y"]]))