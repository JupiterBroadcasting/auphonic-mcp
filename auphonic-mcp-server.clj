#!/usr/bin/env bb

;; Auphonic MCP Server
;; Provides Model Context Protocol interface to Auphonic API

(require '[org.httpkit.server :as server])
(require '[cheshire.core :as json])
(require '[clojure.string :as str])
(require '[babashka.curl :as curl])
(require '[clojure.java.io :as io])

;; ============================================================================
;; Configuration
;; ============================================================================

(def server-info
  {:name "auphonic-mcp-server"
   :version "1.0.0"})

(def auphonic-api-base "https://auphonic.com/api")

;; Show configuration
(def show-types
  {"lup" ["bootleg" "adfree" "main"]
   "launch" ["bootleg" "main"]})

;; ============================================================================
;; State Management
;; ============================================================================

(def server-state
  (atom {:initialized false
         :capabilities nil
         :recent-productions []}))

;; ============================================================================
;; Auphonic API Client Functions (from auphonic-cli.clj)
;; ============================================================================

(defn get-api-key []
  (System/getenv "AUPHONIC_API_KEY"))

(defn get-preset-for-show [show]
  (System/getenv (str "AUPHONIC_PRESET_" (str/upper-case show))))

(defn validate-show [show]
  (when-not (contains? show-types show)
    {:error "Invalid show. Must be one of: lup, launch"}))

(defn validate-type [show type]
  (when-not (some #{type} (get show-types show))
    {:error (str "Invalid type for show " show ". Valid types: " 
                 (str/join ", " (get show-types show)))}))

(defn validate-file-exists [path]
  (when-not (.exists (io/file path))
    {:error (str "File not found: " path)}))

(defn auphonic-request [method endpoint & {:keys [form-data]}]
  (let [api-key (get-api-key)
        url (str auphonic-api-base endpoint)
        headers {"Authorization" (str "Bearer " api-key)}]
    (try
      (let [response (case method
                       :get (curl/get url {:headers headers})
                       :post (if form-data
                               (let [form-params (reduce-kv
                                                   (fn [acc k v]
                                                     (if (= k :input_file)
                                                       (conj acc [k (io/file v)])
                                                       (conj acc [k v])))
                                                   []
                                                   form-data)]
                                 (curl/post url {:headers headers
                                                :form-params form-params}))
                               (curl/post url {:headers headers}))
                       :delete (curl/delete url {:headers headers}))
            body (json/parse-string (:body response) true)]
        {:success true :data body})
      (catch Exception e
        {:success false :error (str "API request failed: " (.getMessage e))}))))

;; ============================================================================
;; MCP Tool Implementations
;; ============================================================================

(defn tool-upload-audio [{:keys [show type file_path title subtitle summary]}]
  (if-let [api-key (get-api-key)]
    (if-let [preset (get-preset-for-show show)]
      (if-let [error (or (validate-show show)
                         (validate-type show type)
                         (validate-file-exists file_path))]
        {:error (:error error)}
        (let [filename (last (str/split file_path #"/"))
              title (or title (str (str/upper-case show) " - " filename))
              form-data (cond-> {:preset preset
                                :title title
                                :input_file file_path
                                :action "start"}
                          subtitle (assoc :subtitle subtitle)
                          summary (assoc :summary summary))
              result (auphonic-request :post "/simple/productions.json" :form-data form-data)]
          (if (:success result)
            (let [production (get-in result [:data :data])
                  uuid (:uuid production)]
              ;; Track in state
              (swap! server-state update :recent-productions 
                     #(take 10 (conj % uuid)))
              {:content [{:type "text"
                         :text (str "✓ Upload started successfully!\n"
                                   "Production UUID: " uuid "\n"
                                   "Status: " (:status_string production) "\n"
                                   "View: https://auphonic.com/production/" uuid)}]
               :production_uuid uuid})
            {:error (or (get-in result [:data :error_message])
                       (:error result)
                       "Upload failed")})))
      {:error (str "AUPHONIC_PRESET_" (str/upper-case show) " environment variable not set")})
    {:error "AUPHONIC_API_KEY environment variable not set"}))

(defn tool-check-status [{:keys [production_uuid]}]
  (let [result (auphonic-request :get (str "/production/" production_uuid ".json"))]
    (if (:success result)
      (let [production (get-in result [:data :data])
            {:keys [uuid status status_string length metadata output_files]} production
            title (get metadata :title "Untitled")]
        {:content [{:type "text"
                   :text (str "Production: " uuid "\n"
                             "Title: " title "\n"
                             "Status: " status_string " (code: " status ")\n"
                             (when length 
                               (str "Length: " (int (/ length 60)) " min " 
                                    (mod (int length) 60) " sec\n"))
                             (when (and (= status 3) (seq output_files))
                               (str "Output files: " (count output_files) " available\n"
                                    "Formats: " (str/join ", " (map :format output_files)) "\n")))}]
         :status status
         :status_string status_string})
      {:error (or (get-in result [:data :error_message])
                 (:error result)
                 "Failed to get status")})))

(defn tool-list-productions [{:keys [limit offset status]}]
  (let [params (cond-> {}
                 limit (assoc :limit limit)
                 offset (assoc :offset offset)
                 status (assoc :status status))
        query-string (when (seq params)
                      (str "?" (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params))))
        result (auphonic-request :get (str "/productions.json" (or query-string "")))]
    (if (:success result)
      (let [productions (get-in result [:data :data])
            formatted (str/join "\n\n"
                        (map (fn [prod]
                               (let [{:keys [uuid status_string metadata change_time]} prod
                                     title (get metadata :title "Untitled")]
                                 (str "• " uuid "\n"
                                      "  Title: " title "\n"
                                      "  Status: " status_string "\n"
                                      "  Updated: " change_time)))
                             productions))]
        {:content [{:type "text"
                   :text (str "Total productions: " (count productions) "\n\n" formatted)}]
         :count (count productions)})
      {:error (or (get-in result [:data :error_message])
                 (:error result)
                 "Failed to list productions")})))

(defn tool-download-output [{:keys [production_uuid output_path format]}]
  (let [result (auphonic-request :get (str "/production/" production_uuid ".json"))]
    (if (:success result)
      (let [production (get-in result [:data :data])
            status (:status production)]
        (if (= 3 status)
          (let [output-files (:output_files production)
                file (if format
                       (first (filter #(= format (:format %)) output-files))
                       (first output-files))]
            (if file
              (let [download-url (:download_url file)
                    api-key (get-api-key)
                    download-url-with-token (str download-url "?bearer_token=" api-key)
                    filename (or (:filename file) (str "output." (:ending file)))
                    output-file (io/file output_path filename)]
                (try
                  (curl/get download-url-with-token 
                           {:as :stream
                            :raw-args ["-o" (str output-file)]})
                  {:content [{:type "text"
                             :text (str "✓ Downloaded to " output-file)}]
                   :file_path (str output-file)}
                  (catch Exception e
                    {:error (str "Download failed: " (.getMessage e))})))
              {:error (str "No output file found" 
                          (when format (str " with format: " format)))}))
          {:error (str "Production not finished yet. Status: " (:status_string production))}))
      {:error (or (get-in result [:data :error_message])
                 (:error result)
                 "Failed to get production details")})))

(defn tool-delete-production [{:keys [production_uuid]}]
  (let [result (auphonic-request :delete (str "/production/" production_uuid ".json"))]
    (if (:success result)
      {:content [{:type "text"
                 :text (str "✓ Production " production_uuid " deleted successfully")}]}
      {:error (or (get-in result [:data :error_message])
                 (:error result)
                 "Failed to delete production")})))

(defn tool-list-presets []
  (let [result (auphonic-request :get "/presets.json")]
    (if (:success result)
      (let [presets (get-in result [:data :data])
            formatted (str/join "\n\n"
                        (map (fn [preset]
                               (let [{:keys [uuid preset_name]} preset]
                                 (str "• " preset_name "\n"
                                      "  UUID: " uuid)))
                             presets))]
        {:content [{:type "text"
                   :text (str "Available presets:\n\n" formatted)}]
         :presets presets})
      {:error (or (get-in result [:data :error_message])
                 (:error result)
                 "Failed to list presets")})))

;; ============================================================================
;; MCP Resources
;; ============================================================================

(defn resource-production-details [uuid]
  (let [result (auphonic-request :get (str "/production/" uuid ".json"))]
    (if (:success result)
      {:uri (str "auphonic://production/" uuid)
       :mimeType "application/json"
       :text (json/generate-string (get-in result [:data :data]) {:pretty true})}
      nil)))

(defn resource-presets-list []
  (let [result (auphonic-request :get "/presets.json")]
    (if (:success result)
      {:uri "auphonic://presets"
       :mimeType "application/json"
       :text (json/generate-string (get-in result [:data :data]) {:pretty true})}
      nil)))

(defn resource-config []
  {:uri "auphonic://config"
   :mimeType "application/json"
   :text (json/generate-string 
           {:api_endpoint auphonic-api-base
            :shows show-types
            :presets {:lup (get-preset-for-show "lup")
                     :launch (get-preset-for-show "launch")}}
           {:pretty true})})

;; ============================================================================
;; MCP Prompts
;; ============================================================================

(defn prompt-upload-and-process [show type file_path]
  {:messages
   [{:role "user"
     :content {:type "text"
               :text (str "Please upload the audio file at " file_path 
                         " for the " show " show as a " type " episode. "
                         "After uploading, check the status and let me know when it's done processing.")}}]})

(defn prompt-analyze-production [production_uuid]
  (if-let [resource (resource-production-details production_uuid)]
    {:messages
     [{:role "user"
       :content {:type "text"
                 :text "Analyze this Auphonic production and tell me:"}}
      {:role "user"
       :content {:type "text"
                 :text "1. Is it finished processing?\n2. What output formats are available?\n3. Is it ready to publish?\n4. Any issues or recommendations?"}}
      {:role "user"
       :content {:type "resource"
                 :resource resource}}]}
    {:error "Production not found"}))

(defn prompt-check-recent-uploads []
  (let [recent-uuids (:recent-productions @server-state)]
    (if (seq recent-uuids)
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Check the status of these recent productions:\n"
                             (str/join "\n" recent-uuids))}}]}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text "No recent uploads tracked. Use list_productions to see all productions."}}]})))

;; ============================================================================
;; MCP Protocol Handlers
;; ============================================================================

(defn handle-initialize [params]
  (let [capabilities {:tools {:listChanged true}
                     :resources {:subscribe true
                                :listChanged true}
                     :prompts {:listChanged true}}]
    (swap! server-state assoc :initialized true :capabilities capabilities)
    {:protocolVersion "2024-11-05"
     :capabilities capabilities
     :serverInfo server-info}))

(defn handle-tools-list []
  {:tools
   [{:name "upload_audio"
     :description "Upload an audio file to Auphonic for processing. Automatically starts processing with the show's preset."
     :inputSchema {:type "object"
                   :properties {:show {:type "string"
                                      :enum ["lup" "launch"]
                                      :description "The podcast show"}
                               :type {:type "string"
                                     :enum ["bootleg" "adfree" "main"]
                                     :description "Type of episode"}
                               :file_path {:type "string"
                                          :description "Absolute path to the audio file"}
                               :title {:type "string"
                                      :description "Episode title (optional)"}
                               :subtitle {:type "string"
                                         :description "Episode subtitle (optional)"}
                               :summary {:type "string"
                                        :description "Episode summary (optional)"}}
                   :required ["show" "type" "file_path"]}}
    
    {:name "check_status"
     :description "Check the processing status of an Auphonic production"
     :inputSchema {:type "object"
                   :properties {:production_uuid {:type "string"
                                                  :description "Production UUID from Auphonic"}}
                   :required ["production_uuid"]}}
    
    {:name "list_productions"
     :description "List Auphonic productions with optional filtering"
     :inputSchema {:type "object"
                   :properties {:limit {:type "number"
                                       :description "Maximum number of results (default 20)"}
                               :offset {:type "number"
                                       :description "Offset for pagination"}
                               :status {:type "number"
                                       :description "Filter by status: 0=Waiting, 1=Processing, 2=Error, 3=Done"}}}}
    
    {:name "download_output"
     :description "Download the processed audio file from a finished production"
     :inputSchema {:type "object"
                   :properties {:production_uuid {:type "string"
                                                  :description "Production UUID"}
                               :output_path {:type "string"
                                            :description "Directory to save the file"}
                               :format {:type "string"
                                       :description "Specific format to download (e.g., 'mp3', 'aac')"}}
                   :required ["production_uuid" "output_path"]}}
    
    {:name "delete_production"
     :description "Delete an Auphonic production"
     :inputSchema {:type "object"
                   :properties {:production_uuid {:type "string"
                                                  :description "Production UUID to delete"}}
                   :required ["production_uuid"]}}
    
    {:name "list_presets"
     :description "List all available Auphonic presets"
     :inputSchema {:type "object"}}]})

(defn handle-tools-call [{:keys [name arguments]}]
  (let [result (case name
                 "upload_audio" (tool-upload-audio arguments)
                 "check_status" (tool-check-status arguments)
                 "list_productions" (tool-list-productions arguments)
                 "download_output" (tool-download-output arguments)
                 "delete_production" (tool-delete-production arguments)
                 "list_presets" (tool-list-presets)
                 {:error (str "Unknown tool: " name)})]
    (if (:error result)
      {:content [{:type "text"
                 :text (str "Error: " (:error result))}]
       :isError true}
      result)))

(defn handle-resources-list []
  {:resources
   [{:uri "auphonic://config"
     :name "Auphonic Configuration"
     :description "API configuration and show settings"
     :mimeType "application/json"}
    
    {:uri "auphonic://presets"
     :name "Available Presets"
     :description "List of all Auphonic presets"
     :mimeType "application/json"}
    
    {:uri "auphonic://production/{uuid}"
     :name "Production Details"
     :description "Details of a specific production (URI template)"
     :mimeType "application/json"}]})

(defn handle-resources-read [{:keys [uri]}]
  (let [resource (cond
                   (= uri "auphonic://config")
                   (resource-config)
                   
                   (= uri "auphonic://presets")
                   (resource-presets-list)
                   
                   (str/starts-with? uri "auphonic://production/")
                   (let [uuid (last (str/split uri #"/"))]
                     (resource-production-details uuid))
                   
                   :else nil)]
    (if resource
      {:contents [resource]}
      {:error (str "Resource not found: " uri)})))

(defn handle-prompts-list []
  {:prompts
   [{:name "upload_and_process"
     :description "Guide through uploading and processing an audio file"
     :arguments [{:name "show"
                  :description "Show name (lup or launch)"
                  :required true}
                 {:name "type"
                  :description "Episode type (bootleg, main, or adfree)"
                  :required true}
                 {:name "file_path"
                  :description "Path to audio file"
                  :required true}]}
    
    {:name "analyze_production"
     :description "Analyze a production's status and readiness"
     :arguments [{:name "production_uuid"
                  :description "Production UUID to analyze"
                  :required true}]}
    
    {:name "check_recent_uploads"
     :description "Check status of recently uploaded files"
     :arguments []}]})

(defn handle-prompts-get [{:keys [name arguments]}]
  (case name
    "upload_and_process"
    (prompt-upload-and-process (:show arguments) (:type arguments) (:file_path arguments))
    
    "analyze_production"
    (prompt-analyze-production (:production_uuid arguments))
    
    "check_recent_uploads"
    (prompt-check-recent-uploads)
    
    {:error (str "Unknown prompt: " name)}))

;; ============================================================================
;; JSON-RPC Handler
;; ============================================================================

(defn handle-jsonrpc-request [request]
  (let [{:keys [method params id]} request]
    (try
      (let [result (case method
                     "initialize" (handle-initialize params)
                     "tools/list" (handle-tools-list)
                     "tools/call" (handle-tools-call params)
                     "resources/list" (handle-resources-list)
                     "resources/read" (handle-resources-read params)
                     "prompts/list" (handle-prompts-list)
                     "prompts/get" (handle-prompts-get params)
                     {:error {:code -32601
                             :message (str "Method not found: " method)}})]
        (if (:error result)
          {:jsonrpc "2.0"
           :id id
           :error (:error result)}
          {:jsonrpc "2.0"
           :id id
           :result result}))
      (catch Exception e
        {:jsonrpc "2.0"
         :id id
         :error {:code -32603
                :message (str "Internal error: " (.getMessage e))}}))))

;; ============================================================================
;; HTTP Server (for STDIO transport, we use stdin/stdout)
;; ============================================================================

(defn parse-json-body [req]
  (when-let [body (:body req)]
    (json/parse-string (slurp body) true)))

(defn json-response [data & {:keys [status] :or {status 200}}]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string data)})

(defn http-handler [req]
  (let [{:keys [uri request-method]} req]
    (case [request-method uri]
      [:post "/mcp"]
      (let [request (parse-json-body req)
            response (handle-jsonrpc-request request)]
        (json-response response))
      
      [:get "/health"]
      (json-response {:status "ok" :server server-info})
      
      (json-response {:error "Not found"} :status 404))))

;; ============================================================================
;; STDIO Transport (Primary MCP transport)
;; ============================================================================

(defn stdio-loop []
  "Read JSON-RPC messages from stdin, write responses to stdout"
  (loop []
    (when-let [line (read-line)]
      (try
        (let [request (json/parse-string line true)
              response (handle-jsonrpc-request request)]
          (println (json/generate-string response))
          (flush))
        (catch Exception e
          (let [error-response {:jsonrpc "2.0"
                               :id nil
                               :error {:code -32700
                                      :message (str "Parse error: " (.getMessage e))}}]
            (println (json/generate-string error-response))
            (flush))))
      (recur))))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main [& args]
  (let [mode (or (first args) "http")]
    (cond
      ;; STDIO mode (default for MCP)
      (= mode "stdio")
      (do
        (binding [*out* *err*]
          (println "Starting Auphonic MCP Server (STDIO mode)")
          (println "Server:" (:name server-info) "v" (:version server-info)))
        (stdio-loop))
      
      ;; HTTP mode (for debugging)
      (= mode "http")
      (let [port (Integer/parseInt (or (second args) "3000"))]
        (server/run-server http-handler {:port port})
        (println (str "Auphonic MCP Server running on http://localhost:" port))
        (println "POST to /mcp for JSON-RPC requests")
        (println "GET /health for health check")
        @(promise))
      
      :else
      (do
        (println "Usage:")
        (println "  auphonic-mcp-server stdio    # STDIO mode (default for MCP)")
        (println "  auphonic-mcp-server http [port]  # HTTP mode for debugging")
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
