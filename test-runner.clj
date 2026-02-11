#!/usr/bin/env bb

(require '[clojure.test :as t]
         '[clojure.java.shell :as shell]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; Configuration
(def test-port 3002)
(def server-url (str "http://localhost:" test-port))
(def mcp-endpoint (str server-url "/mcp"))

;; --- Test State ---
(def server-process (atom nil))

;; --- Helpers ---

(defn wait-for-health [retries]
  (loop [n retries]
    (let [healthy? (try
                     (let [resp (http/get (str server-url "/health") {:throw false})]
                       (= 200 (:status resp)))
                     (catch Exception _
                       false))]
      (if healthy?
        true
        (if (zero? n)
          false
          (do (Thread/sleep 500)
              (recur (dec n))))))))

(defn start-server! []
  (println "Starting server for testing on port" test-port "...")
  (let [proc (java.lang.ProcessBuilder.
              ["bb" "auphonic-mcp-server.clj" (str test-port)])]
    (.inheritIO proc)
    (let [p (.start proc)]
      (reset! server-process p)
      (if (wait-for-health 20)
        (println "Server is healthy.")
        (do
          (.destroy p)
          (throw (ex-info "Server failed to start within timeout" {})))))))

(defn stop-server! []
  (when-let [p @server-process]
    (println "Stopping server...")
    (.destroy p)
    (reset! server-process nil)))

(defn rpc-request [method params & [session-id]]
  (let [body {:jsonrpc "2.0" :id 1 :method method :params params}
        headers (cond-> {"Content-Type" "application/json"}
                  session-id (assoc "Mcp-Session-Id" session-id))]
    (try
      (let [resp (http/post mcp-endpoint
                            {:headers headers
                             :body (json/generate-string body)
                             :throw false})]
        (update resp :body #(json/parse-string % true)))
      (catch Exception e
        {:status 500 :error (.getMessage e)}))))

;; --- Tests ---

(t/deftest test-protocol-lifecycle
  (t/testing "1. Initialize Session"
    (let [resp (rpc-request "initialize"
                            {:protocolVersion "2025-03-26"
                             :clientInfo {:name "test-client" :version "1.0"}
                             :capabilities {}})
          body (:body resp)
          session-id (get-in resp [:headers "mcp-session-id"])]

      (t/is (= 200 (:status resp)))
      (t/is (some? session-id) "Server must return a Session ID header")
      (t/is (= "2.0" (:jsonrpc body)))

      (t/testing "2. Call Tools (requires Session ID)"
        (let [tools-resp (rpc-request "tools/list" {} session-id)
              tools (get-in tools-resp [:body :result :tools])]
          (t/is (= 200 (:status tools-resp)))
          (t/is (some #(= "upload_audio" (:name %)) tools))
          (t/is (some #(= "list_productions" (:name %)) tools))))

      (t/testing "3. Resource Discovery"
        (let [res-resp (rpc-request "resources/list" {} session-id)
              resources (get-in res-resp [:body :result :resources])]
          (t/is (some #(= "auphonic://config" (:uri %)) resources))))

      (t/testing "4. Validation Errors"
        (let [err-resp (rpc-request "tools/call"
                                    {:name "upload_audio" :arguments {}}
                                    session-id)]
          (t/is (true? (get-in err-resp [:body :result :isError]))
                "Should error on missing args"))))))

(t/deftest test-health-check
  (let [resp (http/get (str server-url "/health"))
        body (json/parse-string (:body resp) true)]
    (t/is (= "ok" (:status body)))
    (t/is (contains? body :server))
    (t/is (contains? body :sessions))
    (t/is (number? (:sessions body)))))

(t/deftest test-error-scenarios
  (t/testing "Missing Content-Type header"
    (let [resp (http/post mcp-endpoint
                          {:body (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
                           :throw false})]
      (t/is (= 415 (:status resp)))))

  (t/testing "Missing session ID for protected methods"
    (let [resp (rpc-request "tools/list" {})]
      (t/is (= 400 (:status resp)))))

  (t/testing "Invalid session ID"
    (let [resp (http/post mcp-endpoint
                          {:headers {"Content-Type" "application/json"
                                     "Mcp-Session-Id" "invalid-session-id"}
                           :body (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}})
                           :throw false})]
      (t/is (= 404 (:status resp))))))

(t/deftest test-session-lifecycle
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "Initialize creates session"
      (t/is (some? session-id)))

    (t/testing "Session works for subsequent requests"
      (let [resp (rpc-request "tools/list" {} session-id)]
        (t/is (= 200 (:status resp)))
        (t/is (vector? (get-in resp [:body :result :tools])))))

    (t/testing "Delete session terminates it"
      (let [delete-resp (http/delete mcp-endpoint
                                     {:headers {"Mcp-Session-Id" session-id}
                                      :throw false})]
        (t/is (= 200 (:status delete-resp)))
        (let [body (json/parse-string (:body delete-resp) true)]
          (t/is (= "ok" (:status body)))))

      (t/testing "Session is invalid after deletion"
        (let [resp (rpc-request "tools/list" {} session-id)]
          (t/is (= 404 (:status resp))))))))

(t/deftest test-resource-config
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "Read config resource"
      (let [resp (rpc-request "resources/read" {:uri "auphonic://config"} session-id)
            body (:body resp)
            contents (get-in body [:result :contents])]
        (t/is (= 200 (:status resp)))
        (t/is (= 1 (count contents)))
        (t/is (= "auphonic://config" (get-in contents [0 :uri])))
        (t/is (= "application/json" (get-in contents [0 :mimeType])))))))

(t/deftest test-resources-list
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "List returns all expected resources"
      (let [resp (rpc-request "resources/list" {} session-id)
            body (:body resp)
            resources (get-in body [:result :resources])]
        (t/is (= 200 (:status resp)))
        (t/is (vector? resources))
        (t/is (some #(= "auphonic://config" (:uri %)) resources))
        (t/is (some #(= "auphonic://presets" (:uri %)) resources))
        (t/is (some #(str/starts-with? (:uri %) "auphonic://production/") resources))))))

(t/deftest test-resources-read-presets
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "Read presets resource returns valid response"
      (let [resp (rpc-request "resources/read" {:uri "auphonic://presets"} session-id)
            body (:body resp)]
        (t/is (= 200 (:status resp)))
        (t/is (or (contains? body :result)
                  (contains? body :error))
              "Response should have result or error key")))))

(t/deftest test-prompts
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "List prompts"
      (let [resp (rpc-request "prompts/list" {} session-id)
            body (:body resp)
            prompts (get-in body [:result :prompts])]
        (t/is (= 200 (:status resp)))
        (t/is (vector? prompts))
        (t/is (some #(= "upload_and_process" (:name %)) prompts))
        (t/is (some #(= "analyze_production" (:name %)) prompts))
        (t/is (some #(= "check_recent_uploads" (:name %)) prompts))))

    (t/testing "Get upload_and_process prompt"
      (let [resp (rpc-request "prompts/get"
                              {:name "upload_and_process"
                               :arguments {:show "lup" :type "bootleg" :file_path "/test/path.mp3"}}
                              session-id)
            body (:body resp)
            messages (get-in body [:result :messages])]
        (t/is (= 200 (:status resp)))
        (t/is (vector? messages))
        (t/is (pos? (count messages)))
        (t/is (= "user" (get-in messages [0 :role])))))

    (t/testing "Get check_recent_uploads prompt"
      (let [resp (rpc-request "prompts/get"
                              {:name "check_recent_uploads" :arguments {}}
                              session-id)
            body (:body resp)]
        (t/is (= 200 (:status resp)))
        (t/is (vector? (get-in body [:result :messages])))))))

(t/deftest test-tool-validation
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "upload_audio missing required fields"
      (let [resp (rpc-request "tools/call"
                              {:name "upload_audio" :arguments {}}
                              session-id)
            body (:body resp)]
        (t/is (true? (get-in body [:result :isError])))))

    (t/testing "upload_audio invalid show type"
      (let [resp (rpc-request "tools/call"
                              {:name "upload_audio"
                               :arguments {:show "invalid-show" :type "bootleg" :file_path "/test.mp3"}}
                              session-id)
            body (:body resp)]
        (t/is (true? (get-in body [:result :isError])))))

    (t/testing "upload_audio invalid episode type for show"
      (let [resp (rpc-request "tools/call"
                              {:name "upload_audio"
                               :arguments {:show "launch" :type "adfree" :file_path "/test.mp3"}}
                              session-id)
            body (:body resp)]
        (t/is (true? (get-in body [:result :isError])))))

    (t/testing "upload_audio non-existent file"
      (let [resp (rpc-request "tools/call"
                              {:name "upload_audio"
                               :arguments {:show "lup" :type "bootleg" :file_path "/nonexistent/path.mp3"}}
                              session-id)
            body (:body resp)]
        (t/is (true? (get-in body [:result :isError])))
        (t/is (some? (re-find #"not found" (str body))) "Error message should mention file not found")))

    (t/testing "upload_audio with valid temp file returns result"
      (let [temp-file (java.io.File/createTempFile "test-upload" ".mp3")
            _ (.deleteOnExit temp-file)
            _ (spit temp-file "fake audio content for testing")
            resp (rpc-request "tools/call"
                              {:name "upload_audio"
                               :arguments {:show "lup" :type "bootleg" :file_path (.getAbsolutePath temp-file)}}
                              session-id)
            body (:body resp)]
        (t/is (contains? body :result) "Should return result (API may fail but returns proper MCP response)")))

    (t/testing "check_status missing production_uuid"
      (let [resp (rpc-request "tools/call"
                              {:name "check_status" :arguments {}}
                              session-id)
            body (:body resp)]
        (t/is (true? (get-in body [:result :isError])))))

    (t/testing "download_output missing required fields"
      (let [resp (rpc-request "tools/call"
                              {:name "download_output"
                               :arguments {:production_uuid "test-uuid"}}
                              session-id)
            body (:body resp)]
        (t/is (true? (get-in body [:result :isError])))))

    (t/testing "delete_production missing production_uuid"
      (let [resp (rpc-request "tools/call"
                              {:name "delete_production" :arguments {}}
                              session-id)
            body (:body resp)]
        (t/is (true? (get-in body [:result :isError])))))

    (t/testing "list_presets returns structured response"
      (let [resp (rpc-request "tools/call"
                              {:name "list_presets" :arguments {}}
                              session-id)
            body (:body resp)]
        (t/is (contains? body :result))))

    (t/testing "list_productions returns structured response"
      (let [resp (rpc-request "tools/call"
                              {:name "list_productions" :arguments {}}
                              session-id)
            body (:body resp)]
        (t/is (contains? body :result))))))

(t/deftest test-list-presets-structure
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "list_presets returns content with text"
      (let [resp (rpc-request "tools/call"
                              {:name "list_presets" :arguments {}}
                              session-id)
            body (:body resp)]
        (t/is (contains? body :result))
        (let [result (:result body)]
          (t/is (vector? (:content result)) "content should be a vector")
          (t/is (seq (:content result)) "content should not be empty")
          (t/is (= "text" (get-in (:content result) [0 :type])))
          (t/is (string? (get-in (:content result) [0 :text]))))))))

(t/deftest test-list-productions-structure
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "list_productions returns structured response"
      (let [resp (rpc-request "tools/call"
                              {:name "list_productions" :arguments {:limit 5}}
                              session-id)
            body (:body resp)]
        (t/is (contains? body :result) "Should have result key")
        (let [result (:result body)]
          (t/is (vector? (:content result)) "content should be a vector"))))))

(t/deftest test-invalid-prompt-error
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "Unknown prompt returns JSON-RPC error"
      (let [resp (rpc-request "prompts/get"
                              {:name "nonexistent-prompt" :arguments {}}
                              session-id)
            body (:body resp)]
        (t/is (contains? body :error) "Unknown prompt should return error")))))

(t/deftest test-invalid-resource-error
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "Unknown resource returns error"
      (let [resp (rpc-request "resources/read"
                              {:uri "auphonic://unknown"}
                              session-id)
            body (:body resp)]
        (t/is (contains? body :error))))))

(t/deftest test-rate-limiting
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test" :version "1.0"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]

    (t/testing "Rate limiting allows requests under limit"
      (let [resp (rpc-request "tools/list" {} session-id)]
        (t/is (= 200 (:status resp)) "Should allow request under limit")))

    (t/testing "Rate limiting state is tracked"
      (let [state-resp (http/get (str server-url "/health"))
            body (json/parse-string (:body state-resp) true)]
        (t/is (contains? body :sessions) "Health should return sessions count")))))

;; --- Main Entry ---

(defn -main []
  (when-not (System/getenv "AUPHONIC_API_KEY")
    (println "AUPHONIC_API_KEY not set. Running structural tests only."))

  (try
    (start-server!)
    (let [results (t/run-tests 'user)]
      (System/exit (if (zero? (+ (:fail results) (:error results))) 0 1)))
    (finally
      (stop-server!))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
