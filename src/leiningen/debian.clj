(ns leiningen.debian
  (:use     [lein-debian.common]))

(defn debian
  "Generates a Debian package for build products
USAGE: lein debian <TASK>
where <TASK> can be any of:
package : creates a Debian package as per the configuration specified
          in project.clj
"
  [project & args]
  (let [[task & rest] args]
    (if task
      (let [task-ns-sym (symbol (str "lein-debian." task))
            -           (require task-ns-sym)
            task-ns     (find-ns task-ns-sym)]
        (if-let [funk (and task-ns (->> task symbol (ns-resolve task-ns)))]
          (funk project args)
          (err "unknown task" task)))
      (err "no task was specified. Try lein help debian"))))
