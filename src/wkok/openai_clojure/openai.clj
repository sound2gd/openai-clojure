(ns ^:no-doc wkok.openai-clojure.openai
  (:require
     [clojure.java.io :as io]
     [martian.hato :as martian-http]
     [martian.core :as martian]
     [martian.openapi :as openapi]
     [martian.yaml :as yaml]
     [wkok.openai-clojure.sse :as sse]))

(def api-key (atom nil))

(defn init-api-key [k]
 (reset! api-key k))

(def add-headers
  {:name ::add-headers
   :enter (fn [ctx]
            (let [api-key (or @api-key  (System/getenv "OPENAI_API_KEY"))
                  organization (System/getenv "OPENAI_ORGANIZATION")]
              (update-in ctx [:request :headers]
                         (fn [headers]
                           (cond-> headers
                             (not-empty api-key) (assoc "Authorization" (str "Bearer " api-key))
                             (not-empty organization) (assoc "OpenAI-Organization" organization))))))})

(defn- multipart-form-data?
  [handler]
  (-> handler :openapi-definition :requestBody :content :multipart/form-data))

(defn- param->multipart-entry
  [[param content]]
  {:name (name param)
   :content (if (or (instance? java.io.File content)
                    (instance? java.io.InputStream content)
                    (bytes? content))
              content
              (str content))})

(def multipart-form-data
  {:name ::multipart-form-data
   :enter (fn [{:keys [handler params] :as ctx}]
            (if (multipart-form-data? handler)
              (assoc-in ctx [:request :multipart]
                        (map param->multipart-entry params))
              ctx))})

(defn bootstrap-openapi
  "Bootstrap the martian from a local copy of the openai swagger spec"
  []
  (let [definition (yaml/yaml->edn (slurp (io/resource "openapi.yaml")))
        base-url (openapi/base-url nil nil definition)
        opts (update martian-http/default-opts
                     :interceptors #(-> (remove (comp #{martian-http/perform-request}) %)
                                        (concat [add-headers multipart-form-data sse/perform-sse-capable-request])))]
    (martian/bootstrap-openapi base-url definition opts)))

(def m (delay (bootstrap-openapi)))
