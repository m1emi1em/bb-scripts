#!/usr/bin/env bb
;;; wofi wrapper for grimblast
(require '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def menus [["copysave"
             "copy"
             "save"
             "edit"]

            ["area"
             "active"
             "screen"
             "output"]])

(defn run-dmenu! [list-items]
  (let [stdin (str/join "\n" list-items)]
    (shell {:out :string :in stdin}
           "wofi --show dmenu")))

(def do-dmenu! (comp str/trim :out run-dmenu!))

(defn main [& args]
  (try
    (let [[command target] (map do-dmenu! menus)]
      (shell "grimblast" command target))
    (catch Exception e)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))
