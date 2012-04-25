(ns lein-debian.test.package
  (:use [clojure.test :only (deftest)]
        [midje.sweet]
        [lein-debian.package]))

(deftest get-dependencies-test
  (facts
   (#'lein-debian.package/get-dependencies
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
   (#'lein-debian.package/get-dependencies
    {:dependencies [['a.b/c "x" :scope "test"]
                    ['a.b.c "y"]]})                  => [["liba.b.c-java" "y"]]))
