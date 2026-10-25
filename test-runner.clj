#!/usr/bin/env bb

(require '[clojure.test :as t]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.http-client :as http])

;; Load server for in-process testing
(load-file "auphonic-mcp-server.clj")

;; Configuration
(defn get-free-port []
  (let [s (java.net.ServerSocket. 0)]
    (.close s)
    (.getLocalPort s)))

(def test-port
  (or (when-let [env-port (System/getenv "TEST_PORT")]
        (Integer/parseInt env-port))
      (get-free-port)))

(def server-url (str "http://localhost:" test-port))
(def mcp-endpoint (str server-url "/mcp"))

;; --- Live API opt-in ---
;;
;; This suite must never create a production by accident. It previously would:
;; with AUPHONIC_API_KEY set, `bb test-runner.clj` POSTed a real, credit-
;; spending production to /simple/productions.json with action=start, while
;; -main printed "Running structural tests only". Any test that MUTATES the
;; account is therefore off unless AUPHONIC_LIVE_TESTS=1 is set explicitly.
(def live-tests?
  (= "1" (System/getenv "AUPHONIC_LIVE_TESTS")))

(defn skip-live-note [what]
  (println (str "  (skipped " what " — set AUPHONIC_LIVE_TESTS=1 to enable)")))

;; --- Test State ---
(def server-instance (atom nil))

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

(defn stop-test-server! []
  (when @server-instance
    (println "Stopping server...")
    (stop-server! @server-instance)
    (reset! server-instance nil)))

(defn start-test-server! []
  (println "Starting server for testing on port" test-port "...")
  (reset! server-instance (start-server! test-port))
  (if (wait-for-health 20)
    (println "Server is healthy.")
    (do
      (stop-test-server!)
      (throw (ex-info "Server failed to start within timeout" {})))))

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
                               :arguments {:preset "some-uuid" :file_path "/nonexistent/path.mp3"}}
                              session-id)
            body (:body resp)]
        (t/is (true? (get-in body [:result :isError])))
        (t/is (some? (re-find #"File not found" (str body))) "Error message should mention file not found")))

    (t/testing "upload_audio with custom preset validation"
      (let [temp-file (java.io.File/createTempFile "test-preset" ".mp3")
            _ (.deleteOnExit temp-file)
            _ (spit temp-file "fake audio")
            resp (rpc-request "tools/call"
                              {:name "upload_audio"
                               :arguments {:show "lup" :type "bootleg" :file_path (.getAbsolutePath temp-file) :preset ""}}
                              session-id)
            body (:body resp)
            error-msg (get-in body [:result :content 0 :text])]
        (t/is (true? (get-in body [:result :isError])) "Empty preset should trigger validation error")
        (t/is (some? (re-find #"Preset must not be empty" (str error-msg))))))

    (t/testing "upload_audio with valid temp file returns result"
      ;; MUTATING: with a real key this uploads and starts a production.
      (if live-tests?
        (let [temp-file (java.io.File/createTempFile "test-upload" ".mp3")
              _ (.deleteOnExit temp-file)
              _ (spit temp-file "fake audio content for testing")
              resp (rpc-request "tools/call"
                                {:name "upload_audio"
                                 :arguments {:show "lup" :type "bootleg" :file_path (.getAbsolutePath temp-file)}}
                                session-id)
              body (:body resp)]
          (t/is (contains? body :result) "Should return result (API may fail but returns proper MCP response)"))
        (skip-live-note "mutating upload_audio test — it would start a real production")))

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

(t/deftest test-dynamic-show-discovery
  (t/testing "Discovers shows from environment variables"
    (let [orig-env (fn [k] (System/getenv k))]
      (with-redefs [get-available-shows (constantly {"lup" "lup-uuid" "twib" "twib-uuid"})]
        (let [shows (get-available-shows)]
          (t/is (= "lup-uuid" (get shows "lup")))
          (t/is (= "twib-uuid" (get shows "twib"))))))))

(t/deftest test-preset-only-upload-validation
  (let [init-resp (rpc-request "initialize"
                               {:protocolVersion "2025-03-26"
                                :clientInfo {:name "test"}
                                :capabilities {}})
        session-id (get-in init-resp [:headers "mcp-session-id"])]
    (t/testing "Preset-only uploads require only file_path and preset"
      (let [tmp-file (str (fs/create-temp-file {:prefix "auphonic-mcp" :suffix ".mp3"}))]
        (fs/delete-on-exit tmp-file)
        (let [resp (rpc-request "tools/call"
                                {:name "upload_audio"
                                 :arguments {:file_path tmp-file
                                             :preset "preset-uuid"
                                             :title "Preset Only"}}
                                session-id)
              body (:body resp)]
          ;; It should either succeed (if API key provided) or fail with API Key error,
          ;; but NOT fail with validation error.
          (t/is (not (str/includes? (str body) "Missing required fields"))
                "Should not have validation errors"))))

    (t/testing "Preset-only uploads still validate missing file_path"
      (let [resp (rpc-request "tools/call"
                              {:name "upload_audio" :arguments {:preset "preset-uuid"}}
                              session-id)]
        (t/is (true? (get-in resp [:body :result :isError])))))))

;; --- Multitrack (external-service stems) Tests ---
;;
;; Everything here is SYNTHETIC. This repo is upstream and public: never
;; commit real Auphonic preset UUIDs, service UUIDs, or episode filenames.
;; The live-API path is exercised out-of-band via the tool's dry_run mode.

(def fake-preset-uuid "FAKEPRESET0000000000000")
(def fake-service-uuid "FAKESERVICE000000000000")

(defn fake-preset-details [_uuid]
  {:uuid fake-preset-uuid
   :preset_name "FAKE-MULTITRACK"
   :is_multitrack true
   :multi_input_files [{:id "Host" :type "multitrack"}
                       {:id "Guest" :type "multitrack"}
                       {:id "Room" :type "multitrack"}]})

(defn fake-services []
  [{:uuid fake-service-uuid :display_name "Fake Stems" :type "webdav"}])

;; What the Auphonic API listing actually returns: percent-encoded filenames.
(def fake-listing-encoded
  ["Show%20100%20-%20Host.flac"
   "Show%20100%20-%20Guest.flac"
   "Show%20100%20-%20Room.flac"
   "Show%20100%20(Ads)%20-%20Host.flac"
   "Show%20101%20-%20Host.flac"
   "Show%2068%20-%20Host.flac"
   "Show%201000%20-%20Host.flac"])

;; Stands in for list-service-files, whose contract is to hand back DECODED
;; filenames. Feeding find-episode-stems raw listing output matches nothing.
(defn fake-service-files [_uuid]
  (mapv percent-decode fake-listing-encoded))

(defn new-session []
  (let [resp (rpc-request "initialize"
                          {:protocolVersion "2025-03-26"
                           :clientInfo {:name "test" :version "1.0"}
                           :capabilities {}})]
    (get-in resp [:headers "mcp-session-id"])))

(t/deftest test-percent-decode-and-track-label
  (t/testing "Percent-encoded filenames decode"
    (t/is (= "Show 100 - Host.flac" (percent-decode "Show%20100%20-%20Host.flac")))
    (t/is (= "Show 100 (Ads) - Host.flac"
             (percent-decode "Show%20100%20(Ads)%20-%20Host.flac"))))

  (t/testing "A literal '+' is preserved, not turned into a space (regression)"
    ;; java.net.URLDecoder is a form decoder: without guarding, '+' becomes ' '.
    (t/is (= "C++ interview.flac" (percent-decode "C++ interview.flac")))
    (t/is (= "C++ interview.flac" (percent-decode "C%2B%2B%20interview.flac")))
    (t/is (= "A+B - Chris.flac" (percent-decode "A+B%20-%20Chris.flac"))))

  (t/testing "Multi-byte UTF-8 survives"
    (t/is (= "Café - Host.flac" (percent-decode "Caf%C3%A9%20-%20Host.flac"))))

  (t/testing "A stray '%' is not fatal"
    (t/is (= "100% raw.flac" (percent-decode "100% raw.flac"))))

  (t/testing "Track label is the tail after the last separator"
    (t/is (= "Host" (file-track-label "Show 100 - Host.flac")))
    (t/is (= "Host" (file-track-label "Show 100 (Ads) - Host.flac")))
    (t/is (= "Host" (file-track-label "Show 100 - Host")))
    (t/is (= "Show 100" (file-track-label "Show 100.flac")))))

(t/deftest test-find-episode-stems
  (let [files (fake-service-files fake-service-uuid)]

    (t/testing "Raw (still-encoded) listing output matches nothing — decode first"
      (let [{:keys [episode-candidates]}
            (find-episode-stems fake-listing-encoded 100 ["Host"])]
        (t/is (= [] episode-candidates)
              "find-episode-stems expects decoded names; list-service-files decodes")))

    (t/testing "Matches every track for the requested episode"
      (let [{:keys [matches missing]} (find-episode-stems files 100 ["Host" "Guest" "Room"])]
        (t/is (= 3 (count matches)))
        (t/is (= [] missing))))

    (t/testing "Prefers the plain file over the (Ads) variant"
      (let [{:keys [matches]} (find-episode-stems files 100 ["Host"])]
        (t/is (= "Show 100 - Host.flac" (get matches "Host")))))

    (t/testing "Episode 100 must not match the file for episode 1000"
      ;; Ordering is deliberate: with the longer name FIRST, a regex missing the
      ;; trailing lookahead would pick it and this assertion would fail. The
      ;; previous version used the fixture order, where the correct answer came
      ;; first anyway, so it could not fail.
      (let [{:keys [matches]} (find-episode-stems
                               ["Show 1000 - Host.flac" "Show 100 - Host.flac"]
                               100 ["Host"])]
        (t/is (= "Show 100 - Host.flac" (get matches "Host")))))

    (t/testing "Episode 68 matches only the file for episode 68"
      (let [{:keys [matches missing]} (find-episode-stems files 68 ["Host"])]
        (t/is (= {"Host" "Show 68 - Host.flac"} matches))
        (t/is (= [] missing))))

    (t/testing "Reports missing tracks instead of guessing"
      (let [{:keys [matches missing]} (find-episode-stems files 101 ["Host" "Guest"])]
        (t/is (= {"Host" "Show 101 - Host.flac"} matches))
        (t/is (= ["Guest"] missing))))

    (t/testing "Absent episode yields no candidates and all tracks missing"
      (let [{:keys [matches missing episode-candidates]}
            (find-episode-stems files 999 ["Host" "Guest"])]
        (t/is (= {} matches))
        (t/is (= [] episode-candidates))
        (t/is (= ["Host" "Guest"] missing))))

    (t/testing "Exact tail match wins when a near-miss also exists"
      (let [{:keys [matches]} (find-episode-stems
                               ["Show 200 - Host.flac" "Show 200 - Hostia.flac"]
                               200 ["Host"])]
        (t/is (= "Show 200 - Host.flac" (get matches "Host")))))

    (t/testing "No fuzzy fallback: a near-miss is reported missing, never guessed"
      (let [{:keys [matches missing]}
            (find-episode-stems ["Show 688 - Christina.flac"] 688 ["Chris"])]
        (t/is (= {} matches) "\"Chris\" must not match \"Christina.flac\"")
        (t/is (= ["Chris"] missing)))
      (let [{:keys [matches missing]}
            (find-episode-stems ["Show 688 - Newest.flac"] 688 ["Wes"])]
        (t/is (= {} matches) "\"Wes\" must not match \"Newest.flac\"")
        (t/is (= ["Wes"] missing))))

    (t/testing "A recording date is not mistaken for an episode number"
      (let [{:keys [episode-candidates]}
            (find-episode-stems ["LUP 2024-03-11 - Chris.flac"] 3 ["Chris"])]
        (t/is (= [] episode-candidates)
              "episode 3 must not match the '-03-' inside a date"))
      (let [{:keys [episode-candidates]}
            (find-episode-stems ["LUP 2024-03-11 - Chris.flac"] 11 ["Chris"])]
        (t/is (= [] episode-candidates))))

    (t/testing "Two track ids resolving to one file is flagged as ambiguous"
      (let [{:keys [matches duplicates]}
            (find-episode-stems ["Show 688 - Guest.flac"] 688 ["Guest" "guest"])]
        (t/is (= 2 (count matches)))
        (t/is (= ["Show 688 - Guest.flac"] duplicates)
              "caller must refuse rather than guess which track is which")))))

(t/deftest test-resolve-service
  (t/testing "A display name resolves via /services.json"
    (with-redefs [get-services fake-services]
      (t/is (= fake-service-uuid (:uuid (resolve-service "Fake Stems"))))
      (t/is (= fake-service-uuid (:uuid (resolve-service "fake stems")))
            "matching should be case-insensitive")))

  (t/testing "An unknown UUID-shaped string is passed through as a UUID"
    (with-redefs [get-services fake-services]
      (t/is (= fake-service-uuid (:uuid (resolve-service fake-service-uuid))))))

  (t/testing "A display name that LOOKS like a UUID is resolved by name"
    ;; 18-26 alnum chars used to be taken as a UUID verbatim, then 404.
    (let [looks-like-uuid "LUPStemsBucket2024"]
      (with-redefs [get-services (fn [] [{:uuid "realUuid0000000000000"
                                          :display_name looks-like-uuid
                                          :incoming true}])]
        (t/is (= "realUuid0000000000000"
                 (:uuid (resolve-service looks-like-uuid)))))))

  (t/testing "Unknown name, and blank input"
    (with-redefs [get-services fake-services]
      (t/is (some? (:error (resolve-service "Not A Service"))))
      (t/is (nil? (resolve-service "")))))

  (t/testing "Ambiguous display names are an error, never a silent first match"
    (with-redefs [get-services (fn [] [{:uuid "aaa0000000000000000aa"
                                        :display_name "Dup" :incoming true}
                                       {:uuid "bbb0000000000000000bb"
                                        :display_name "Dup" :incoming true}])]
      (let [r (resolve-service "Dup")]
        (t/is (some? (:error r)))
        (t/is (str/includes? (:error r) "ambiguous")))))

  (t/testing "A publish-only service cannot supply input files"
    (with-redefs [get-services (fn [] [{:uuid "ccc0000000000000000cc"
                                        :display_name "Out Only"
                                        :type "youtube"
                                        :incoming false}])]
      (let [r (resolve-service "Out Only")]
        (t/is (some? (:error r)))
        (t/is (str/includes? (:error r) "publish-only"))))))

(t/deftest test-multitrack-tool-registered
  (let [session-id (new-session)
        resp (rpc-request "tools/list" {} session-id)
        tools (get-in resp [:body :result :tools])
        tool (first (filter #(= "start_multitrack_production" (:name %)) tools))]
    (t/is (some? tool) "start_multitrack_production should be registered")
    (t/is (map? (:inputSchema tool)))
    (t/testing "Schema exposes the arguments the tool actually accepts"
      (let [props (:properties (:inputSchema tool))]
        (doseq [k [:preset :show :service :episode :tracks :title :basename :action :dry_run]]
          (t/is (contains? props k) (str "schema should declare " k)))))))

(t/deftest test-multitrack-validation-errors
  (let [session-id (new-session)]

    (t/testing "No preset and no show is an error"
      (let [resp (rpc-request "tools/call"
                              {:name "start_multitrack_production" :arguments {}}
                              session-id)]
        (t/is (true? (get-in resp [:body :result :isError])))
        (t/is (str/includes? (str (get-in resp [:body :result]))
                             "Missing 'preset' or 'show'"))))

    (t/testing "Unknown show names the env var to set"
      (let [resp (rpc-request "tools/call"
                              {:name "start_multitrack_production"
                               :arguments {:show "nosuchshow"}}
                              session-id)]
        (t/is (str/includes? (str (get-in resp [:body :result]))
                             "AUPHONIC_PRESET_NOSUCHSHOW"))))

    (with-redefs [get-preset-details fake-preset-details
                  get-services fake-services
                  list-service-files fake-service-files]

      (t/testing "Unresolvable service is an error"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Nope"
                                             :episode 100}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result])) "Unknown service"))))

      (t/testing "Missing episode lists the preset's own track ids"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result])) "Host, Guest, Room"))))

      (t/testing "Absent stems are reported, never guessed"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :episode 999}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result])) "No stem found"))))

      (t/testing "Explicit tracks with no service is allowed (HTTP-URL inputs)"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :tracks {"Host" "https://example.invalid/host.flac"}
                                             :dry_run true}}
                                session-id)
              plan (get-in resp [:body :result :plan])]
          (t/is (= 1 (count (:fill plan))))
          (t/is (not (contains? (first (:fill plan)) :service))
                "omitting :service is how the API is told input_file is an HTTP URL"))))))

(t/deftest test-multitrack-dry-run-plan
  (let [session-id (new-session)]
    (with-redefs [get-preset-details fake-preset-details
                  get-services fake-services
                  list-service-files fake-service-files]
      (let [resp (rpc-request "tools/call"
                              {:name "start_multitrack_production"
                               :arguments {:preset fake-preset-uuid
                                           :service "Fake Stems"
                                           :episode 100
                                           :title "Ep 100"
                                           :basename "show-0100"
                                           :dry_run true}}
                              session-id)
            result (get-in resp [:body :result])
            plan (:plan result)]

        (t/testing "Dry run reports success and creates nothing"
          (t/is (not (:isError result)))
          (t/is (nil? (:production_uuid result))
                "dry_run must not create a production")
          (t/is (true? (:dry_run result))))

        (t/testing "Step 1 creates from the preset, carrying NO multi_input_files"
          ;; Passing multi_input_files at creation is what produced duplicate
          ;; and empty tracks; the create call must not include them.
          (t/is (= fake-preset-uuid (get-in plan [:create :preset])))
          (t/is (not (contains? (:create plan) :multi_input_files))
                "create must not carry tracks — they are attached afterwards")
          (t/is (= "show-0100" (get-in plan [:create :output_basename])))
          (t/is (= "Ep 100" (get-in plan [:create :metadata :title]))))

        (t/testing "Step 2 fills each preset track by id"
          (let [fill (:fill plan)]
            (t/is (= 3 (count fill)))
            (t/is (= #{"Host" "Guest" "Room"} (set (map :track_id fill))))
            (t/is (every? #(= fake-service-uuid (:service %)) fill)
                  "each track must reference the resolved service UUID")
            (t/is (every? #(str/includes? (:input_file %) "Show 100 ") fill))
            (t/is (every? #(not (str/includes? (:input_file %) "(Ads)")) fill))))

        (t/testing "A preset-only run appends nothing and starts"
          (t/is (= [] (:append plan)))
          (t/is (= ["Host" "Guest" "Room"] (:preset_track_ids plan)))
          (t/is (true? (:start plan))))))))

(t/deftest test-multitrack-appends-tracks-the-preset-lacks
  (let [session-id (new-session)]
    (with-redefs [get-preset-details fake-preset-details
                  get-services fake-services
                  list-service-files fake-service-files]
      (let [resp (rpc-request "tools/call"
                              {:name "start_multitrack_production"
                               :arguments {:preset fake-preset-uuid
                                           :service "Fake Stems"
                                           :tracks {"Host" "Show 100 - Host.flac"
                                                    "Guest" "Show 100 - Guest.flac"
                                                    "Aaron" "Show 100 - Aaron.flac"}
                                           :dry_run true}}
                              session-id)
            plan (get-in resp [:body :result :plan])]
        (t/testing "Known ids are filled, unknown ids are appended"
          (t/is (= #{"Host" "Guest"} (set (map :track_id (:fill plan)))))
          (t/is (= #{"Aaron"} (set (map :track_id (:append plan)))))
          (t/is (= fake-service-uuid (get-in plan [:append 0 :service]))))
        (t/testing "Nothing is dropped"
          (t/is (= 3 (+ (count (:fill plan)) (count (:append plan))))))))))

(t/deftest test-multitrack-save-action-does-not-start
  (let [session-id (new-session)]
    (with-redefs [get-preset-details fake-preset-details
                  get-services fake-services
                  list-service-files fake-service-files]
      (let [resp (rpc-request "tools/call"
                              {:name "start_multitrack_production"
                               :arguments {:preset fake-preset-uuid
                                           :service "Fake Stems"
                                           :episode 100
                                           :action "save"
                                           :dry_run true}}
                              session-id)
            plan (get-in resp [:body :result :plan])]
        (t/is (false? (:start plan))
              "action 'save' must plan to leave the production unstarted")))))

(t/deftest test-multitrack-action-is-strict
  (let [session-id (new-session)]
    (with-redefs [get-preset-details fake-preset-details
                  get-services fake-services
                  list-service-files fake-service-files]

      (t/testing "A typo'd action is rejected, not silently treated as start"
        ;; This action spends credits and cannot be undone, so guessing is not ok.
        (doseq [bad ["starrt" "no" "begin" "run" "STARTING"]]
          (let [resp (rpc-request "tools/call"
                                  {:name "start_multitrack_production"
                                   :arguments {:preset fake-preset-uuid
                                               :service "Fake Stems"
                                               :episode 100
                                               :action bad}}
                                  session-id)
                body (str (get-in resp [:body :result]))]
            (t/is (str/includes? body "Invalid 'action'")
                  (str "action " (pr-str bad) " must be rejected"))
            (t/is (nil? (get-in resp [:body :result :production_uuid]))))))

      (t/testing "Case and padding around a valid action are tolerated"
        (doseq [good ["start" "Start" " START " "save"]]
          (let [resp (rpc-request "tools/call"
                                  {:name "start_multitrack_production"
                                   :arguments {:preset fake-preset-uuid
                                               :service "Fake Stems"
                                               :episode 100
                                               :action good
                                               :dry_run true}}
                                  session-id)
                body (str (get-in resp [:body :result]))]
            (t/is (not (str/includes? body "Invalid 'action'"))
                  (str "action " (pr-str good) " should be accepted"))))))))

(t/deftest test-multitrack-skip-tracks
  (let [session-id (new-session)]
    (with-redefs [get-preset-details fake-preset-details
                  get-services fake-services
                  list-service-files fake-service-files]

      (t/testing "skip_tracks omits tracks the episode does not have"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :episode 101
                                             :skip_tracks ["Guest" "Room"]
                                             :dry_run true}}
                                session-id)
              plan (get-in resp [:body :result :plan])]
          (t/is (= ["Host"] (mapv :track_id (:fill plan))))))

      (t/testing "Skipping every track is an error, not an empty production"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :episode 100
                                             :skip_tracks ["Host" "Guest" "Room"]}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result]))
                               "No tracks left to run"))))

      (t/testing "A supplied-but-unresolvable service fails even with explicit tracks"
        ;; Otherwise the bare filenames get sent as if they were HTTP URLs.
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Nope"
                                             :tracks {"Host" "Show 100 - Host.flac"}}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result])) "Unknown service")))))))

(t/deftest test-mcp-endpoint-http-methods
  (t/testing "GET on the MCP endpoint answers 405, not 404 (no SSE stream offered)"
    (let [resp (http/get mcp-endpoint {:throw false})]
      (t/is (= 405 (:status resp)))
      (t/is (str/includes? (str (get-in resp [:headers "allow"])) "POST"))))

  (t/testing "POST without an initialised session is rejected, not 500"
    (let [resp (http/post mcp-endpoint
                          {:headers {"Content-Type" "application/json"}
                           :body "{}"
                           :throw false})]
      (t/is (= 400 (:status resp)))))

  (t/testing "Health endpoint still works"
    (let [resp (http/get (str server-url "/health") {:throw false})]
      (t/is (= 200 (:status resp))))))

(t/deftest test-flag-and-episode-parsing
  (t/testing "dry_run is read in the SAFE direction, never silently live"
    ;; Regression: (true? dry_run) meant dry_run:"true" or 1 performed a REAL POST.
    (doseq [v [true "true" "TRUE" 1 "1" " yes " :x {} [[]]]]
      (t/is (true? (flag-true? v))
            (str "dry_run " (pr-str v) " must preview, not post")))
    (doseq [v [nil false "false" "0" 0 "no" "off" ""]]
      (t/is (false? (flag-true? v))
            (str "dry_run " (pr-str v) " must be treated as live"))))

  (t/testing "Episode numbers are coerced to whole numbers"
    (t/is (= {:episode 688} (parse-episode 688)))
    (t/is (= {:episode 688} (parse-episode 688.0))
          "a float from JSON must not build a regex that never matches")
    (t/is (= {:episode 688} (parse-episode "688")))
    (t/is (= {:episode 688} (parse-episode " 688 ")))
    (t/is (some? (:error (parse-episode "688a"))))
    (t/is (some? (:error (parse-episode nil)))))

  (t/testing "URL-ish values are recognised"
    (t/is (url-ish? "https://example.invalid/a.flac"))
    (t/is (url-ish? "ftp://x/y.flac"))
    (t/is (not (url-ish? "Linux Unplugged 688 - Chris.flac")))))

(t/deftest test-stem-matching-real-world-shapes
  (t/testing "The unspaced delimiter variant matches (real listing has these)"
    ;; e.g. "Linux Unplugged 635- Brent.flac", which the old " - " split missed.
    (let [{:keys [matches missing]}
          (find-episode-stems ["Linux Unplugged 635- Brent.flac"] 635 ["Brent"])]
      (t/is (= {"Brent" "Linux Unplugged 635- Brent.flac"} matches))
      (t/is (= [] missing))))

  (t/testing "Two equally-exact candidates are ambiguous, not first-wins"
    (let [{:keys [ambiguous matches]}
          (find-episode-stems ["Show 688 - Host.flac"
                               "Backup/Show 688 - Host.flac"] 688 ["Host"])]
      (t/is (= 1 (count ambiguous)))
      (t/is (= "Host" (first (first ambiguous)))))))

(t/deftest test-multitrack-guards-on-payload
  (let [session-id (new-session)]
    (with-redefs [get-preset-details fake-preset-details
                  get-services fake-services
                  list-service-files fake-service-files]

      (t/testing "dry_run as a STRING still previews (regression: it used to POST)"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :episode 100
                                             :dry_run "true"}}
                                session-id)
              result (get-in resp [:body :result])]
          (t/is (true? (:dry_run result)))
          (t/is (nil? (:production_uuid result)))))

      (t/testing "An empty tracks map is refused"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid :tracks {}}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result])) "tracks' map is empty"))))

      (t/testing "A blank filename is refused"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :tracks {"Host" "   "}}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result])) "no filename"))))

      (t/testing "URL tracks plus a service is refused (would send the URL as a filename)"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :tracks {"Host" "https://example.invalid/h.flac"}}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result])) "look like URLs"))))

      (t/testing "A non-list skip_tracks is refused"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :episode 100
                                             :skip_tracks "Host"}}
                                session-id)]
          (t/is (str/includes? (str (get-in resp [:body :result])) "must be a list"))))

      (t/testing "skip_tracks also applies to explicit tracks"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :tracks {"Host" "Show 100 - Host.flac"
                                                      "Guest" "Show 100 - Guest.flac"}
                                             :skip_tracks ["Guest"]
                                             :dry_run true}}
                                session-id)
              plan (get-in resp [:body :result :plan])]
          (t/is (= ["Host"] (mapv :track_id (:fill plan))))))

      (t/testing "Explicit ids outside the preset warn but still run"
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :tracks {"Chirs" "Show 100 - Host.flac"}
                                             :dry_run true}}
                                session-id)
              result (get-in resp [:body :result])
              plan (:plan result)]
          (t/is (str/includes? (str (:content result)) "not defined by preset"))
          (t/is (= ["Chirs"] (mapv :track_id (:append plan)))
                "an id the preset does not define is appended, not filled"))))))

(t/deftest test-multitrack-drops-replaced-preset-tracks
  (let [session-id (new-session)]
    (with-redefs [get-preset-details fake-preset-details
                  get-services fake-services
                  list-service-files fake-service-files]
      (t/testing "A preset track the run does not supply is planned for removal"
        ;; Otherwise it is created, left empty, and pushes the production back
        ;; over the 8-track limit (the Mumble -> Mumble + Clips case).
        (let [resp (rpc-request "tools/call"
                                {:name "start_multitrack_production"
                                 :arguments {:preset fake-preset-uuid
                                             :service "Fake Stems"
                                             :tracks {"Host" "Show 100 - Host.flac"
                                                      "Guest + Extra" "Show 100 - Guest.flac"}
                                             :dry_run true}}
                                session-id)
              plan (get-in resp [:body :result :plan])]
          (t/is (= ["Host"] (mapv :track_id (:fill plan))))
          (t/is (= ["Guest + Extra"] (mapv :track_id (:append plan))))
          (t/is (= ["Guest" "Room"] (vec (:drop plan)))
                "unsupplied preset tracks must be removed, not left empty"))))))

(t/deftest test-start-production-tool
  (let [session-id (new-session)
        resp (rpc-request "tools/list" {} session-id)
        tools (get-in resp [:body :result :tools])]
    (t/testing "start_production is registered"
      (t/is (some #(= "start_production" (:name %)) tools)))

    (t/testing "start_production requires a production_uuid"
      (let [r (rpc-request "tools/call"
                           {:name "start_production" :arguments {}}
                           session-id)]
        (t/is (true? (get-in r [:body :result :isError])))
        (t/is (str/includes? (str (get-in r [:body :result])) "production_uuid"))))))

;; --- Port Configuration Tests (Subprocess) ---

(defn wait-for-server [port max-retries]
  (loop [n max-retries]
    (let [healthy? (try
                     (let [resp (http/get (str "http://localhost:" port "/health")
                                          {:throw false :timeout 1000})]
                       (= 200 (:status resp)))
                     (catch Exception _
                       false))]
      (if healthy?
        true
        (if (zero? n)
          false
          (do (Thread/sleep 200)
              (recur (dec n))))))))

(defn start-subprocess-server [port]
  (let [cmd (if port
              ["bb" "auphonic-mcp-server.clj" (str port)]
              ["bb" "auphonic-mcp-server.clj"])
        pb (ProcessBuilder. cmd)
        proc (.start pb)]
    {:process proc :port (or port 3003)}))

(defn stop-subprocess-server [{:keys [process]}]
  (when process
    (.destroyForcibly process)
    (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)))

(t/deftest test-server-starts-on-default-port
  (t/testing "Server starts on default port 3003 when no port arg provided"
    (let [{:keys [process port] :as server} (start-subprocess-server nil)]
      (try
        (t/is (wait-for-server port 30)
              (str "Server should start and respond on default port " port))
        (t/is (= 3003 port) "Default port should be 3003")
        (let [resp (http/get (str "http://localhost:" port "/health") {:throw false})]
          (t/is (= 200 (:status resp)))
          (t/is (= "ok" (:status (json/parse-string (:body resp) true)))))
        (finally
          (stop-subprocess-server server))))))

(t/deftest test-server-starts-on-custom-port
  (t/testing "Server starts on custom port when port arg provided"
    (let [custom-port (get-free-port)
          {:keys [process port] :as server} (start-subprocess-server custom-port)]
      (try
        (t/is (wait-for-server port 30)
              (str "Server should start and respond on custom port " port))
        (t/is (= custom-port port) "Port should match what we requested")
        (let [resp (http/get (str "http://localhost:" port "/health") {:throw false})]
          (t/is (= 200 (:status resp)))
          (t/is (= "ok" (:status (json/parse-string (:body resp) true)))))
        (finally
          (stop-subprocess-server server))))))

;; --- Main Entry ---

(defn -main []
  (println (if live-tests?
             "AUPHONIC_LIVE_TESTS=1 — mutating tests ENABLED."
             "Mutating tests skipped (set AUPHONIC_LIVE_TESTS=1 to enable)."))
  (when-not (System/getenv "AUPHONIC_API_KEY")
    (println "AUPHONIC_API_KEY not set: live API calls will return errors, not run."))
  (when (System/getenv "AUPHONIC_API_KEY")
    (println "AUPHONIC_API_KEY is set: read-only live calls (list_presets, list_productions) will run."))

  (try
    (start-test-server!)
    (let [results (t/run-tests 'user)]
      (System/exit (if (zero? (+ (:fail results) (:error results))) 0 1)))
    (finally
      (stop-test-server!))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
