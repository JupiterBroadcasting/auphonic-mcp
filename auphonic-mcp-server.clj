#!/usr/bin/env bb

;; Auphonic MCP Server
;; HTTP-based Model Context Protocol server for Auphonic API
;; Implements Streamable HTTP transport (MCP spec 2025-03-26)

(require '[org.httpkit.server :as server]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.java.io :as io])

;; ============================================================================
;; Configuration & Constants
;; ============================================================================

(def ^:const protocol-version "2025-03-26")
(def ^:const server-info
  {:name "auphonic-mcp-server"
   :version "2.0.0"})

(def ^:const auphonic-api-base "https://auphonic.com/api")

(def ^:const capabilities
  {:tools {:listChanged false}
   :resources {:subscribe false :listChanged false}
   :prompts {:listChanged false}})

;; ============================================================================
;; State Management
;; ============================================================================

(def server-state
  (atom {:sessions {}
         :recent-productions []
         :rate-limits {}}))

(def ^:const rate-limit-max-requests 60)
(def ^:const rate-limit-window-ms 60000)

(defn gen-session-id []
  (str (random-uuid)))

(defn create-session! []
  (let [session-id (gen-session-id)
        session {:id session-id
                 :initialized false
                 :created-at (System/currentTimeMillis)}]
    (swap! server-state assoc-in [:sessions session-id] session)
    session-id))

(defn get-session [session-id]
  (get-in @server-state [:sessions session-id]))

(defn update-session! [session-id f & args]
  (swap! server-state update-in [:sessions session-id] #(apply f % args)))

(defn delete-session! [session-id]
  (swap! server-state update :sessions dissoc session-id))

(defn track-production! [uuid]
  (swap! server-state update :recent-productions
         #(vec (take 20 (cons uuid %)))))

(defn check-rate-limit [session-id]
  (when session-id
    (let [now (System/currentTimeMillis)
          window-start (- now rate-limit-window-ms)
          requests (get-in @server-state [:rate-limits session-id])
          recent-requests (filter #(> % window-start) requests)]
      (when (>= (count recent-requests) rate-limit-max-requests)
        {:rate-limited true :retry-after (int (/ rate-limit-window-ms 1000))}))))

(defn record-request! [session-id]
  (when session-id
    (swap! server-state update-in [:rate-limits session-id]
           conj (System/currentTimeMillis))))

;; ============================================================================
;; Environment & Validation
;; ============================================================================

(defn get-api-key []
  (System/getenv "AUPHONIC_API_KEY"))

(defn get-available-shows
  "Discovers shows from environment variables starting with AUPHONIC_PRESET_"
  []
  (->> (System/getenv)
       (filter (fn [[k _]] (str/starts-with? k "AUPHONIC_PRESET_")))
       (map (fn [[k v]]
              (let [show-name (str/lower-case (str/replace k "AUPHONIC_PRESET_" ""))]
                [show-name v])))
       ;; Security & Robustness: Filter keys and values
       (filter (fn [[k v]]
                 (and (not (str/blank? k))
                      (not (str/blank? (str v)))
                      (re-matches #"^[a-z0-9_]+$" k)
                      (not (re-find #"(?i)key|secret|token|password" k))
                      (< (count k) 64))))
       (into {})))

(defn validate-file-exists [path]
  (when-not (fs/exists? path)
    {:error (str "File not found: " path)}))

(defn validate-preset [preset]
  (when (str/blank? (str preset))
    {:error "Preset must not be empty"}))

(defn validate-required-fields [args required-fields]
  (let [missing (remove #(let [value (get args % ::missing)]
                           (and (not= value ::missing)
                                (not (nil? value))))
                        required-fields)]
    (when (seq missing)
      {:error (str "Missing required fields: " (str/join ", " (map name missing)))})))

;; ============================================================================
;; HTTP Client for Auphonic API
;; ============================================================================

(defn auphonic-request
  "Make HTTP request to Auphonic API using babashka.http-client"
  [method endpoint & {:keys [form-data query-params]}]
  (let [api-key (get-api-key)
        url (str auphonic-api-base endpoint)]
    (if-not api-key
      {:success false :error "AUPHONIC_API_KEY not set"}
      (try
        (let [base-opts {:headers {"Authorization" (str "Bearer " api-key)}
                         :throw false}
              opts (cond-> base-opts
                     query-params (assoc :query-params query-params)
                     form-data (assoc :form-params form-data))
              response (case method
                         :get (http/get url opts)
                         :post (http/post url opts)
                         :delete (http/delete url opts))]
          (cond
            (< (:status response) 300)
            {:success true :data (json/parse-string (:body response) true)}

            (= 401 (:status response))
            {:success false :error "Authentication failed. Check API key."}

            (= 404 (:status response))
            {:success false :error "Resource not found"}

            :else
            {:success false :error (str "API error: HTTP " (:status response))}))
        (catch Exception e
          {:success false :error (str "Request failed: " (.getMessage e))})))))

(def output-formats-cache (atom nil))

(defn get-available-output-formats []
  (when-not @output-formats-cache
    (let [result (auphonic-request :get "/info/output_files.json")]
      (when (:success result)
        (reset! output-formats-cache (keys (get-in result [:data :data]))))))
  @output-formats-cache)

(defn format-available-formats []
  (if-let [formats (get-available-output-formats)]
    (str/join ", " formats)
    "mp3, aac, opus, flac, vtt, srt, html (see /api/info/output_files.json)"))

;; ============================================================================
;; MCP Tool Implementations
;; ============================================================================

(defn tool-upload-audio [args]
  (let [{:keys [show type file_path title subtitle summary preset]} args
        normalized-show (some-> show str/lower-case)
        shows (get-available-shows)
        ;; Resolve the preset: Direct UUID > Env-var Show Name
        preset-to-use (or preset (get shows normalized-show))]
    (if-let [validation-error (or (validate-required-fields args [:file_path])
                                  (when (contains? args :preset) (validate-preset preset))
                                  (when-not preset-to-use
                                    {:error (if show
                                              (str "Unknown show '" show "'. Configure AUPHONIC_PRESET_" (str/upper-case show) "=<PRESET_UUID> or use 'preset' argument.")
                                              "Missing 'preset' or 'show' argument.")})
                                  (validate-file-exists file_path)
                                  (let [size (fs/size file_path)]
                                    (when (> size (* 2 1024 1024 1024)) ; 2GB limit
                                      {:error (str "File too large (" (int (/ size 1024 1024)) "MB). Max limit is 2GB.")})))]
      {:error (:error validation-error)}

      (let [filename (fs/file-name file_path)
            title (or title (str (if normalized-show (str/upper-case normalized-show) "AUPHONIC") " - " filename))
            url (str auphonic-api-base "/simple/productions.json")
            multipart-parts (cond-> [{:name "preset" :content preset-to-use}
                                     {:name "title" :content title}
                                     {:name "input_file" :content (io/file file_path)}
                                     {:name "action" :content "start"}]
                              subtitle (conj {:name "subtitle" :content subtitle})
                              summary (conj {:name "summary" :content summary})
                              type (conj {:name "tags" :content type}))]
        (try
          (let [api-key (get-api-key)
                response (http/post url
                                    {:headers {"Authorization" (str "Bearer " api-key)}
                                     :multipart multipart-parts
                                     :timeout 300000 ; 5 minute timeout for large uploads
                                     :throw false})]
            (if (< (:status response) 300)
              (let [data (json/parse-string (:body response) true)
                    production (:data data)
                    uuid (:uuid production)]
                (track-production! uuid)
                {:content [{:type "text"
                            :text (str "✓ Upload started successfully!\n"
                                       "Production UUID: " uuid "\n"
                                       "Status: " (:status_string production) "\n"
                                       "View: https://auphonic.com/production/" uuid)}]
                 :production_uuid uuid})
              (let [body (try (json/parse-string (:body response) true) (catch Exception _ nil))]
                {:error (str "Upload failed (HTTP " (:status response) "): " (or (:error_message body) (:body response)))})))
          (catch Exception e
            {:error (str "Upload failed: " (.getMessage e))}))))))

(defn tool-check-status [{:keys [production_uuid]}]
  (if-let [validation-error (validate-required-fields
                             {:production_uuid production_uuid}
                             [:production_uuid])]
    {:error (:error validation-error)}

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
                    "Failed to get status")}))))

(defn tool-list-productions [{:keys [limit offset status]}]
  (let [params (cond-> {}
                 limit (assoc :limit limit)
                 offset (assoc :offset offset)
                 status (assoc :status status))
        result (auphonic-request :get "/productions.json" :query-params params)]

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
  (if-let [validation-error (validate-required-fields
                             {:production_uuid production_uuid :output_path output_path}
                             [:production_uuid :output_path])]
    {:error (:error validation-error)}

    (let [result (auphonic-request :get (str "/production/" production_uuid ".json"))]
      (if (:success result)
        (let [production (get-in result [:data :data])
              status (:status production)]
          (if (= 3 status)
            (let [output-files (:output_files production)
                  file (if format
                         (first (or
                                 (filter #(= format (:format %)) output-files)
                                 (filter #(= format (:ending %)) output-files)))
                         (first output-files))]
              (if file
                (let [download-url (:download_url file)
                      api-key (get-api-key)
                      download-url-with-token (str download-url "?bearer_token=" api-key)
                      filename (or (:filename file) (str "output." (:ending file)))
                      output-file (io/file output_path filename)]
                  (try
                    (io/copy
                     (:body (http/get download-url-with-token {:as :stream}))
                     output-file)
                    {:content [{:type "text"
                                :text (str "✓ Downloaded to " output-file)}]
                     :file_path (str output-file)}
                    (catch Exception e
                      {:error (str "Download failed: " (.getMessage e))})))
                {:error (str "No output file found"
                             (when format (str " with format/ending: " format ". "))
                             "Available: " (format-available-formats))}))
            {:error (str "Production not finished yet. Status: " (:status_string production))}))
        {:error (or (get-in result [:data :error_message])
                    (:error result)
                    "Failed to get production details")}))))

(defn tool-delete-production [{:keys [production_uuid]}]
  (if-let [validation-error (validate-required-fields
                             {:production_uuid production_uuid}
                             [:production_uuid])]
    {:error (:error validation-error)}

    (let [result (auphonic-request :delete (str "/production/" production_uuid ".json"))]
      (if (:success result)
        {:content [{:type "text"
                    :text (str "✓ Production " production_uuid " deleted successfully")}]}
        {:error (or (get-in result [:data :error_message])
                    (:error result)
                    "Failed to delete production")}))))

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
    (when (:success result)
      {:uri (str "auphonic://production/" uuid)
       :mimeType "application/json"
       :text (json/generate-string (get-in result [:data :data]) {:pretty true})})))

(defn resource-presets-list []
  (let [result (auphonic-request :get "/presets.json")]
    (when (:success result)
      {:uri "auphonic://presets"
       :mimeType "application/json"
       :text (json/generate-string (get-in result [:data :data]) {:pretty true})})))

(defn resource-config []
  {:uri "auphonic://config"
   :mimeType "application/json"
   :text (let [shows (get-available-shows)]
           (json/generate-string
            {:api_endpoint auphonic-api-base
             :available_presets shows
             :info "Available show presets are configured via AUPHONIC_PRESET_<NAME> environment variables."}
            {:pretty true}))})

;; ============================================================================
;; MCP Prompts
;; ============================================================================

(defn prompt-upload-and-process
  ([file_path] (prompt-upload-and-process nil file_path))
  ([show file_path]
   (let [shows (get-available-shows)
         resolved-show (or (some-> show str/lower-case) (first (sort (keys shows))) "SHOW_NAME")]
     {:messages
      [{:role "user"
        :content {:type "text"
                  :text (str "Please upload the audio file at " file_path
                             " to Auphonic using the " resolved-show " preset."
                             " You can set the title and summary if you have that information.")}}]})))

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

(defn handle-initialize [session-id]
  (update-session! session-id assoc :initialized true)
  {:protocolVersion protocol-version
   :capabilities capabilities
   :serverInfo server-info})

(defn handle-tools-list []
  {:tools
   [{:name "upload_audio"
     :description "Upload an audio file to Auphonic. Use 'show' (mapped via AUPHONIC_PRESET_SHOW environment variable) or 'preset' (direct UUID). Any 'type' provided is added as a tag."
     :inputSchema {:type "object"
                   :properties {:show {:type "string"
                                       :description "Show name (e.g. 'lup', 'twib'). Resolves to AUPHONIC_PRESET_<SHOW>."}
                                :preset {:type "string"
                                         :description "Direct Auphonic Preset UUID. Overrides 'show' if both provided."}
                                :file_path {:type "string"
                                            :description "Absolute path to the audio file"}
                                :type {:type "string"
                                       :description "Episode type or category (e.g. 'bootleg', 'main'). Added as a tag."}
                                :title {:type "string"
                                        :description "Episode title (optional)"}
                                :subtitle {:type "string"
                                           :description "Episode subtitle (optional)"}
                                :summary {:type "string"
                                          :description "Episode summary (optional)"}}
                   :required ["file_path"]}}

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
                                         :description "Format to download: audio (mp3, aac, opus, flac) or transcript (vtt, srt, html). Matches format name or file extension."}}
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
      (let [error-msg (str "Error: " (:error result))]
        {:content [{:type "text"
                    :text error-msg}]
         :isError true
         :error-msg error-msg}) ; Add for test visibility
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
      (if (map? resource)
        {:contents [resource]}
        {:contents [{:uri uri
                     :mimeType "text/plain"
                     :text resource}]})
      {:error (str "Resource not found: " uri)})))

(defn handle-prompts-list []
  {:prompts
   [{:name "upload_and_process"
     :description "Guide through uploading and processing an audio file"
     :arguments [{:name "show"
                  :description "Show name (e.g. lup, launch)"
                  :required false}
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
  (let [result (case name
                 "upload_and_process"
                 (prompt-upload-and-process (:show arguments) (:file_path arguments))

                 "analyze_production"
                 (prompt-analyze-production (:production_uuid arguments))

                 "check_recent_uploads"
                 (prompt-check-recent-uploads)

                 {:error (str "Unknown prompt: " name)})]
    (if (:error result)
      result
      ;; Ensure we return the MCP prompt structure correctly
      (update result :messages (fn [msgs] (or msgs []))))))

;; ============================================================================
;; JSON-RPC Handler
;; ============================================================================

(defn handle-jsonrpc-request [request session-id]
  (let [{:keys [method params id]} request
        session (get-session session-id)]

    (try
      ;; Check if method requires initialization
      (when (and (not= method "initialize")
                 (not (:initialized session)))
        (throw (ex-info "Session not initialized"
                        {:type :session-error
                         :code -32002})))

      (let [result (case method
                     "initialize" (handle-initialize session-id)
                     "tools/list" (handle-tools-list)
                     "tools/call" (handle-tools-call params)
                     "resources/list" (handle-resources-list)
                     "resources/read" (handle-resources-read params)
                     "prompts/list" (handle-prompts-list)
                     "prompts/get" (handle-prompts-get params)
                     (throw (ex-info "Method not found"
                                     {:type :method-error
                                      :code -32601
                                      :method method})))]
        (if (:error result)
          {:jsonrpc "2.0"
           :id id
           :error {:code -32000
                   :message (:error result)}}
          {:jsonrpc "2.0"
           :id id
           :result result}))

      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          {:jsonrpc "2.0"
           :id id
           :error {:code (or (:code data) -32603)
                   :message (.getMessage e)
                   :data data}}))

      (catch Exception e
        {:jsonrpc "2.0"
         :id id
         :error {:code -32603
                 :message (str "Internal error: " (.getMessage e))}}))))

;; ============================================================================
;; HTTP Server
;; ============================================================================

(defn parse-json-body [req]
  (when-let [body (:body req)]
    (try
      (json/parse-string (slurp body) true)
      (catch Exception _
        (throw (ex-info "Invalid JSON" {:type :parse-error :code -32700}))))))

(defn validate-content-type [req]
  (when-not (str/includes? (get-in req [:headers "content-type"] "") "application/json")
    (throw (ex-info "Content-Type must be application/json"
                    {:type :content-type-error :status 415}))))

(defn validate-origin [req]
  ;; Simple Origin validation to prevent DNS rebinding
  (when-let [origin (get-in req [:headers "origin"])]
    (when-not (or (str/includes? origin "localhost")
                  (str/includes? origin "127.0.0.1"))
      (throw (ex-info "Invalid origin" {:type :origin-error :status 403})))))

(defn http-handler [req]
  (let [{:keys [uri request-method headers]} req]
    (try
      (case [request-method uri]
        [:post "/mcp"]
        (do
          (validate-origin req)
          (validate-content-type req)

          (let [request (parse-json-body req)
                session-id (or (get headers "mcp-session-id")
                               (when (= (:method request) "initialize")
                                 (create-session!)))]

            (when session-id
              (if-let [rate-limit (check-rate-limit session-id)]
                (throw (ex-info "Rate limit exceeded"
                                {:type :rate-limit-error
                                 :status 429
                                 :retry-after (:retry-after rate-limit)}))
                (record-request! session-id)))

            (when-not session-id
              (throw (ex-info "Missing session ID" {:type :session-error :status 400})))

            (when-not (get-session session-id)
              (throw (ex-info "Invalid session ID" {:type :session-error :status 404})))

            (let [response (handle-jsonrpc-request request session-id)
                  response-headers (cond-> {"Content-Type" "application/json"}
                                     (and session-id (= (:method request) "initialize"))
                                     (assoc "Mcp-Session-Id" session-id))]
              {:status 200
               :headers response-headers
               :body (json/generate-string response)})))

        [:delete "/mcp"]
        (let [session-id (get headers "mcp-session-id")]
          (when session-id
            (delete-session! session-id))
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:status "ok"})})

        [:get "/health"]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:status "ok"
                                      :server server-info
                                      :sessions (count (:sessions @server-state))})}

        {:status 404
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error "Not found"})})

      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)
              status (or (:status data) 500)
              headers (cond-> {"Content-Type" "application/json"}
                        (:retry-after data) (assoc "Retry-After" (str (:retry-after data))))]
          {:status status
           :headers headers
           :body (json/generate-string
                  (if (= (:type data) :parse-error)
                    {:jsonrpc "2.0"
                     :id nil
                     :error {:code (:code data)
                             :message (.getMessage e)}}
                    {:error (.getMessage e)}))}))

      (catch Exception _
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error "Internal server error"})}))))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn start-server! [& [port]]
  (println (str "Auphonic MCP Server v" (:version server-info)))
  (println (str "Protocol version: " protocol-version))
  (println (str "Listening on http://localhost:" port))
  (println "POST to /mcp for JSON-RPC requests")
  (println "GET /health for health check")
  (println "DELETE /mcp with Mcp-Session-Id to terminate session")
  (server/run-server http-handler {:host "0.0.0.0" :port port}))

(defn stop-server! [server]
  (server/server-stop! server))

(defn -main [& args]
  (let [port (if (seq args)
               (Integer/parseInt (first args))
               3003)]
    (start-server! port)
    @(promise)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
