#!/usr/bin/env bb

;; Test script for Auphonic MCP Server
;; Tests the JSON-RPC protocol without needing Claude Desktop

(require '[cheshire.core :as json])
(require '[clojure.java.shell :as shell])

(defn send-jsonrpc [method params]
  "Send a JSON-RPC request to the server via STDIO"
  (let [request {:jsonrpc "2.0"
                :id (rand-int 10000)
                :method method
                :params params}
        json-str (json/generate-string request)
        result (shell/sh "bb" "auphonic-mcp-server.clj" "stdio"
                        :in json-str
                        :env (merge (into {} (System/getenv))
                                   {"AUPHONIC_API_KEY" (System/getenv "AUPHONIC_API_KEY")
                                    "AUPHONIC_PRESET_LUP" (System/getenv "AUPHONIC_PRESET_LUP")
                                    "AUPHONIC_PRESET_LAUNCH" (System/getenv "AUPHONIC_PRESET_LAUNCH")}))]
    (when (not= 0 (:exit result))
      (println "STDERR:" (:err result)))
    (json/parse-string (:out result) true)))

(defn test-initialize []
  (println "\n=== Testing Initialize ===")
  (let [response (send-jsonrpc "initialize" {})]
    (println "Response:")
    (println (json/generate-string response {:pretty true}))
    (if (get-in response [:result :serverInfo])
      (println "✓ Initialize successful")
      (println "✗ Initialize failed"))))

(defn test-tools-list []
  (println "\n=== Testing Tools List ===")
  (let [response (send-jsonrpc "tools/list" {})]
    (println "Response:")
    (println (json/generate-string response {:pretty true}))
    (let [tools (get-in response [:result :tools])]
      (if (seq tools)
        (do
          (println (str "✓ Found " (count tools) " tools"))
          (doseq [tool tools]
            (println (str "  - " (:name tool) ": " (:description tool)))))
        (println "✗ No tools found")))))

(defn test-resources-list []
  (println "\n=== Testing Resources List ===")
  (let [response (send-jsonrpc "resources/list" {})]
    (println "Response:")
    (println (json/generate-string response {:pretty true}))
    (let [resources (get-in response [:result :resources])]
      (if (seq resources)
        (do
          (println (str "✓ Found " (count resources) " resources"))
          (doseq [resource resources]
            (println (str "  - " (:uri resource) ": " (:description resource)))))
        (println "✗ No resources found")))))

(defn test-prompts-list []
  (println "\n=== Testing Prompts List ===")
  (let [response (send-jsonrpc "prompts/list" {})]
    (println "Response:")
    (println (json/generate-string response {:pretty true}))
    (let [prompts (get-in response [:result :prompts])]
      (if (seq prompts)
        (do
          (println (str "✓ Found " (count prompts) " prompts"))
          (doseq [prompt prompts]
            (println (str "  - " (:name prompt) ": " (:description prompt)))))
        (println "✗ No prompts found")))))

(defn test-resource-read []
  (println "\n=== Testing Resource Read (config) ===")
  (let [response (send-jsonrpc "resources/read" {:uri "auphonic://config"})]
    (println "Response:")
    (println (json/generate-string response {:pretty true}))
    (if (get-in response [:result :contents])
      (println "✓ Resource read successful")
      (println "✗ Resource read failed"))))

(defn test-list-presets []
  (println "\n=== Testing list_presets Tool ===")
  (if (System/getenv "AUPHONIC_API_KEY")
    (let [response (send-jsonrpc "tools/call" 
                                {:name "list_presets" 
                                 :arguments {}})]
      (println "Response:")
      (println (json/generate-string response {:pretty true}))
      (if (get-in response [:result :content])
        (println "✓ list_presets successful")
        (println "✗ list_presets failed")))
    (println "⊘ Skipped (AUPHONIC_API_KEY not set)")))

(defn -main []
  (println "==============================================")
  (println "Auphonic MCP Server Test Suite")
  (println "==============================================")
  
  ;; Check environment
  (println "\n=== Environment Check ===")
  (if (System/getenv "AUPHONIC_API_KEY")
    (println "✓ AUPHONIC_API_KEY is set")
    (println "⚠ AUPHONIC_API_KEY not set (some tests will be skipped)"))
  
  (if (System/getenv "AUPHONIC_PRESET_LUP")
    (println "✓ AUPHONIC_PRESET_LUP is set")
    (println "⚠ AUPHONIC_PRESET_LUP not set"))
  
  (if (System/getenv "AUPHONIC_PRESET_LAUNCH")
    (println "✓ AUPHONIC_PRESET_LAUNCH is set")
    (println "⚠ AUPHONIC_PRESET_LAUNCH not set"))
  
  ;; Run tests
  (test-initialize)
  (test-tools-list)
  (test-resources-list)
  (test-prompts-list)
  (test-resource-read)
  (test-list-presets)
  
  (println "\n==============================================")
  (println "Test Suite Complete")
  (println "=============================================="))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
