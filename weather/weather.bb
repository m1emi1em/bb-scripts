#!/usr/bin/env bb
(require '[babashka.curl :as curl]
         '[babashka.http-client :as http]
         '[clojure.java.io :as io]
         '[babashka.fs :as fs])

(def api-endpoint "https://api.weather.gov/points/")
(def cache-path (str (fs/home) "/.cache/bb-scripts/geonames/"))

(defn get-all
  [coll keyset]
  (for [k keyset]
    (vector k (get coll k))))

(defn get->data [url get-fn pre-fn]
  (let [response (get-fn url)
         http-code (:status response)]
     (condp = http-code
       200 (-> response :body pre-fn)
       500 (throw (Exception. "Server responded with 500. Rate limit (probably) exceeded. Wait a bit then try again"))
       (throw (Exception. (str "Unexpected HTTP status " http-code))))))

(defn download-geonames-data [cache-path country]
  (do (-> "http://download.geonames.org/export/zip/" 
          (str country ".zip")
          (get->data #(curl/get % {:as :bytes}) io/input-stream)
          (fs/unzip cache-path))
      (str cache-path country ".txt")))

(defn get-geonames-data-path
  ([] (get-geonames-data-path cache-path "US"))
  ([cache-path country]
   (let [file-path (str cache-path country ".txt")]
     (if (fs/exists? file-path) file-path
         (download-geonames-data cache-path country)))))

(defn read-csv-to-maps
  ([] (read-csv-to-maps (get-geonames-data-path)))
  ([fname]
   (let [csv-data (-> fname slurp (csv/read-csv :separator \tab))]
     (map #(zipmap [:postal_code :place_name :state_code :latitude :longitude]
            (->> (get-all % [1 2 4 9 10]) (map second))) csv-data))))

(defn geocode [zipcode]
  (first (filter
          (fn [{zc :postal_code}] (= zc zipcode))
          (read-csv-to-maps))))

(defn get->json [url] (get->data url curl/get json/parse-string))

(defn check-rc []
  (let [path (str (fs/home) "/.weatherrc")]
    (when (fs/exists? path)
      (-> path slurp str/trim-newline))))

(defn display-results
  [{city :place_name state :state_code}
   [[_ temperature] [_ forecast]]]
  (doseq [line [""
                (str "The weather in " city ", " state " is:")
                (str temperature " F")
                (str "Forecast: " forecast)]]
    (println line)))

(defn get-weather [postal-code]
  (let [{latitude :latitude longitude :longitude :as location} (geocode postal-code)]
    (-> (format "%s%s,%s" api-endpoint latitude longitude)
        
        get->json
        (get-in ["properties" "forecastHourly"])
        
        get->json
        (get-in ["properties" "periods"])
        first
        (get-all ["temperature" "shortForecast"])

        (#(display-results location %)))))

(defn -main
  ([]
   (if-let [postal-code (check-rc)]
     (get-weather postal-code)
     (println "No postal code provided")))

  ([postal-code]
   (if (re-matches #"\d{5}" postal-code)
     (get-weather postal-code)
     (println "Not a valid postal code"))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
