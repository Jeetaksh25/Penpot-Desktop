(require '[clojure.string :as str])

(let [p (re-pattern #"[.*+?^${}()|\\[\\]\\\\]")]
  (println "pattern bytes:" (mapv int (str p)))
  (println "matches dot      :" (boolean (re-find p ".")))
  (println "matches bracket-[:" (boolean (re-find p "[")))
  (println "matches bracket-]:" (boolean (re-find p "]")))
  (println "matches backslash:" (boolean (re-find p "\\")))
  (println "matches letter a :" (boolean (re-find p "a")))
  (println "matches in input :" (mapv (fn [ch] (re-find p (str (char ch)))) (seq "a.b[c]d\\e"))))
