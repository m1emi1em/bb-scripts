#!/usr/bin/env bb
(require '[babashka.curl :as curl]
         '[babashka.http-client :as http]
         '[babashka.process :refer [shell process alive?]]
         '[clojure.java.io :as io]
         '[babashka.fs :as fs])

(def endpoints {:points "https://api.weather.gov/points/"})
(def cache-path (str (fs/home) "/.cache/bb-scripts/geonames/"))

(defn get-all
  [coll keyset]
  (for [k keyset]
    (vector k (get coll k))))

(defn download-geonames-data [cache-path country]
  (let [base-url "http://download.geonames.org/export/zip/"
        url (str base-url country ".zip")
        response (curl/get url {:as :bytes})]
    (if (= (:status response) 200)
      (do (-> response :body io/input-stream (fs/unzip cache-path))
          (str cache-path country ".txt"))
      (throw (Exception. str "Wrong HTTP Response Code: " (:status response))))))

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

(defn api-get
  "Make an HTTP GET to url and return the json result"
  [url]
  (let [response (-> url http/get)]
    (if (= (:status response) 500)
      (do
        (print "Server responded with 500. Rate limit (probably) exceeded. Wait a bit then try again")
        (System/exit 1))
      (-> response :body json/parse-string))))

(defn form-request
  "Forms a request url"
  [request-type & args]
  (apply str (request-type endpoints) (interpose "," args)))

(defn get-forecast-url
  "Gets the endpoint url for the requested latitude and longitude using a points request"
  [{latitude :latitude longitude :longitude}]
  (-> (form-request :points latitude longitude)
      api-get
      (get-in ["properties" "forecastHourly"])))

(defn get-forecast [location]
  (-> location get-forecast-url api-get
      (get-in ["properties" "periods"])
      first
      (get-all ["temperature" "shortForecast"])))

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
  (let [location (geocode postal-code)
        forecast (get-forecast location)]
    (display-results location forecast)))

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
