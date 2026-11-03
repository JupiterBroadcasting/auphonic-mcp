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

(def default-track-sets
  "Track ids to assume when a show's preset declares no multitrack tracks.

  Presets built for explicit track maps carry no track definitions, which makes
  service+episode derivation unreachable: the stem matcher refuses to invent a
  lineup. These are the lineups we actually ship, so derivation works without a
  hand-written map.

  Filenames are still matched exactly against the service listing, so a lineup
  change shows up as a reported miss, never as a confidently wrong file."
  {"lup" ["Chris" "Wes" "Brent" "Mumble" "Clips"]
   ;; Launch's lineup as it actually appears on the "The Launch STEMS" service
   ;; ("Launch 73 - Angela.flac", etc). Observed from a single episode, so
   ;; override with AUPHONIC_TRACKS_LAUNCH when a current run differs rather
   ;; than trusting it blindly.
   "launch" ["Chris" "Angela" "Caller" "Clips"]})

(defn get-default-track-set
  "Track ids for a show, from AUPHONIC_TRACKS_<SHOW> or default-track-sets.

  Returns nil for an unknown show: callers must then demand 'tracks' explicitly
  rather than invent a lineup."
  [show]
  (when-not (str/blank? (str show))
    (let [s (str/lower-case (str show))
          env (System/getenv (str "AUPHONIC_TRACKS_" (str/upper-case s)))
          from-env (when-not (str/blank? (str env))
                     (->> (str/split env #",")
                          (map str/trim)
                          (remove str/blank?)
                          vec))]
      (or (not-empty from-env)
          (get default-track-sets s)))))

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

(defn auphonic-json-post
  "POST a JSON body to the Auphonic API (Content-Type: application/json).

   Distinct from auphonic-request, which form-encodes. Used for the JSON API
   endpoints (/productions.json) where the payload is a nested structure."
  [endpoint payload]
  (let [api-key (get-api-key)]
    (if-not api-key
      {:success false :error "AUPHONIC_API_KEY not set"}
      (try
        (let [response (http/post (str auphonic-api-base endpoint)
                                  {:headers {"Authorization" (str "Bearer " api-key)
                                             "Content-Type" "application/json"}
                                   :body (json/generate-string payload)
                                   :timeout 60000
                                   :throw false})]
          (cond
            (< (:status response) 300)
            {:success true :data (json/parse-string (:body response) true)}

            (= 401 (:status response))
            {:success false :error "Authentication failed. Check API key."}

            (= 404 (:status response))
            {:success false :error "Resource not found"}

            :else
            (let [body (try (json/parse-string (:body response) true)
                            (catch Exception _ nil))
                  detail (or (:error_message body) (:message body))]
              {:success false
               :error (str "API error: HTTP " (:status response)
                           (when (and detail (not (str/blank? (str detail))))
                             (str " - " detail)))})))
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
;; External Services & Stem Resolution
;;
;; Auphonic can pull input audio from a registered external service (WebDAV,
;; S3, ...) instead of receiving an upload. For multitrack that is the whole
;; game: the stems already live on the service, so starting a production is a
;; single JSON POST referencing them by filename.
;; ============================================================================

(defn percent-decode
  "Percent-decode a filename from the service listing, WITHOUT form semantics.

   The listing really does percent-encode (verified against the live API:
   \"Linux%20Unplugged%20618%20(Ads)%20-%20Brent.flac\"), so decoding is required.

   But java.net.URLDecoder is a *form* decoder: it turns '+' into a space, which
   would silently corrupt a legal filename like \"C++ interview.flac\" and send
   the mangled name back as that track's input_file. Escape any literal '+' to
   %2B first, so only real %XX escapes are decoded, while keeping URLDecoder's
   correct multi-byte UTF-8 handling."
  [s]
  (let [s (str s)]
    (try
      (java.net.URLDecoder/decode (str/replace s "+" "%2B") "UTF-8")
      (catch Exception _ s))))

(defn get-preset-details [uuid]
  (let [result (auphonic-request :get (str "/preset/" uuid ".json"))]
    (when (:success result)
      (get-in result [:data :data]))))

(defn get-services []
  (let [result (auphonic-request :get "/services.json")]
    (when (:success result)
      (let [d (get-in result [:data :data])]
        (if (map? d) (vals d) d)))))

(defn resolve-service
  "Resolve a service reference to {:uuid ...}, or {:error ...}, or nil.

   A display name is matched first (case-insensitively) against /services.json;
   only if nothing matches is the input treated as a UUID. Inferring \"UUID\"
   from the string's shape alone was wrong: an 18-26 char display name would be
   passed off as a UUID and 404 with a confusing dead end.

   Ambiguous display names are an error rather than a silent first-match —
   picking the wrong service would start a production against the wrong files."
  [service]
  (let [s (str/trim (str service))]
    (when-not (str/blank? s)
      (let [svcs (get-services)
            named (when svcs
                    (filterv #(= (str/lower-case (str (:display_name %)))
                                 (str/lower-case s))
                             svcs))]
        (cond
          (> (count named) 1)
          {:error (str "Service name '" s "' is ambiguous — " (count named)
                       " services share it. Pass one of their UUIDs: "
                       (str/join ", " (map :uuid named)))}

          (= 1 (count named))
          (let [svc (first named)]
            (if (false? (:incoming svc))
              {:error (str "Service '" s "' (" (:type svc) ") is publish-only"
                           " (incoming=false) and cannot supply input files.")}
              {:uuid (:uuid svc) :display_name (:display_name svc)}))

          (re-matches #"[A-Za-z0-9]{18,26}" s)
          {:uuid s}

          ;; Distinguish "the API did not answer" from "no such name". Reporting
          ;; a rate-limited lookup as "Unknown service" is a confidently wrong
          ;; diagnosis, and it made dry-run verification untrustworthy.
          (nil? svcs)
          {:error (str "Could not list services from the Auphonic API (network"
                       " error or rate limit), so '" s "' could not be resolved."
                       " Retry, or pass the service UUID directly.")}

          :else
          {:error (str "Unknown service '" s "'. Pass a service UUID, or a"
                       " display name from /services.json (e.g. \"LUP Stems\").")})))))

(defn list-service-files
  "GET /service/{uuid}/ls.json -> vector of decoded filenames, or nil."
  [service-uuid]
  (let [result (auphonic-request :get (str "/service/" service-uuid "/ls.json"))]
    (when (:success result)
      (let [d (get-in result [:data :data])]
        (mapv #(percent-decode (str %))
              (if (map? d) (vals d) d))))))

(defn flag-true?
  "Interpret a JSON-ish boolean flag, biased to TRUE when ambiguous.

   Used for dry_run, where TRUE means 'do not perform the side effect'. The
   previous `(true? dry_run)` treated the string \"true\" and the number 1 as
   false, so a caller asking for a preview would have started a real, credit-
   spending production. Anything not clearly false counts as true."
  [v]
  (not (or (nil? v)
           (false? v)
           (and (number? v) (zero? v))
           (and (string? v)
                (contains? #{"false" "0" "no" "off" ""}
                           (str/lower-case (str/trim v)))))))

(defn parse-episode
  "Coerce an episode to a whole number.

   The schema says number, but a float (688.0) would build a regex that never
   matches and an error message that lies. Returns {:episode n} or
   {:error msg}."
  [episode]
  (cond
    (number? episode) {:episode (long episode)}
    (and (string? episode) (re-matches #"\s*\d+\s*" episode))
    {:episode (Long/parseLong (str/trim episode))}
    :else {:error (str "episode must be a whole number, got " (pr-str episode))}))

(defn track-id-str
  "Coerce a track-id key from parsed JSON into a plain string.

   JSON objects arrive with keyword keys (:Host), and `str` on a keyword keeps
   the leading colon — which would be sent to Auphonic as \":Host\"."
  [k]
  (cond
    (keyword? k) (name k)
    (symbol? k) (name k)
    :else (str k)))

(defn url-ish?
  "True when a track value is clearly a URL rather than a service filename."
  [v]
  (boolean (re-find #"(?i)^(https?|ftp|sftp)://" (str/trim (str v)))))

(defn encode-path-segment
  "Percent-encode a value for use as a URL path segment.

   URLEncoder encodes for a form body, where a space becomes '+' — wrong inside
   a path, so swap it back to %20."
  [s]
  (-> (java.net.URLEncoder/encode (str s) "UTF-8")
      (str/replace "+" "%20")))

(defn attach-track!
  "Point one track of an existing production at a file on an external service.

   This is the step that actually fills a preset's track. Passing
   `multi_input_files` at CREATION instead APPENDS to the preset's template,
   leaving the preset's own tracks empty and duplicating their ids (verified
   against the live API: 4 sent -> 8 stored, every id duplicated)."
  [production-uuid track-id service-uuid filename]
  (auphonic-json-post
   (str "/production/" production-uuid "/multi_input_files/"
        (encode-path-segment track-id) ".json")
   (cond-> {:type "multitrack" :id track-id}
     service-uuid (assoc :service service-uuid)
     (not (str/blank? (str filename))) (assoc :input_file filename))))

(defn append-tracks!
  "Add tracks that the preset does not define (a guest, an edited feed).

   Updates the production with additional multi_input_files — the one place
   where appending is correct, because these ids genuinely are new."
  [production-uuid entries service-uuid]
  (auphonic-json-post
   (str "/production/" production-uuid ".json")
   {:multi_input_files
    (mapv (fn [[tid fname]]
            (cond-> {:type "multitrack" :id tid}
              service-uuid (assoc :service service-uuid)
              (not (str/blank? (str fname))) (assoc :input_file fname)))
          entries)}))

(defn remove-track!
  "Delete one track from a production.

   Needed when a preset track is being REPLACED rather than filled — e.g. the
   preset defines 'Mumble', but the run supplies 'Mumble + Clips' instead.
   Without this the preset's own track is created and left empty, which pushes
   the production back over the 8-track limit."
  [production-uuid track-id]
  (auphonic-request :delete (str "/production/" production-uuid "/multi_input_files/"
                                 (encode-path-segment track-id) ".json")))

(defn production-tracks
  "The production's current track list as Auphonic stored it, or nil."
  [production-uuid]
  (let [r (auphonic-request :get (str "/production/" production-uuid ".json"))]
    (when (:success r)
      (get-in r [:data :data :multi_input_files]))))

(defn file-track-label
  "The track portion of a stem filename: text after the last \"- \", minus the
   extension. \"Show 688 - Chris.flac\" -> \"Chris\".

   Auphonic names stems \"<show> <episode> - <track>.<ext>\", but the live
   listing also holds unspaced variants (\"Linux Unplugged 635- Brent.flac\"),
   so the separator is \"- \" rather than \" - \". A name with no separator falls
   back to the whole stem."
  [filename]
  (let [base (str/replace (str filename) #"\.[A-Za-z0-9]{2,5}$" "")]
    (if-let [i (str/last-index-of base "- ")]
      (str/trim (subs base (+ i 2)))
      (str/trim base))))

(defn find-episode-stems
  "Match service files to track ids for one episode number.

   Filenames must already be percent-decoded — `list-service-files` does that at
   the API boundary. Expected shape is \"<show> <episode> - <track>.<ext>\".

   The episode must stand as its own whitespace-delimited token, so a recording
   date embedded in the name (\"LUP 2024-03-11 - Chris.flac\") cannot be mistaken
   for episode 3 or 11.

   The track must match the filename's tail EXACTLY. There is deliberately no
   fuzzy substring fallback: \"Wes\" must never match \"Newest.flac\", and
   \"Chris\" must never match \"Christina.flac\". A reported miss is cheap; a
   confident wrong file is a broken episode.

   Returns {:matches {track-id filename} :missing [track-id]
            :episode-candidates [filename] :ambiguous [[track-id [filename...]]]
            :duplicates [filename]}"
  [files episode track-ids]
  (let [ep-re (re-pattern (str "(?:^|\\s)0*"
                               (java.util.regex.Pattern/quote (str episode))
                               "(?=\\s|\\(|\\.|-|$)"))
        ep-files (filterv #(re-find ep-re %) files)
        candidates-for (fn [track]
                         (let [tl (str/lower-case (str track))
                               exact (filterv #(= tl (str/lower-case (file-track-label %)))
                                              ep-files)
                               plain (filterv #(not (str/includes? % "(Ads)")) exact)]
                           (vec (or (seq plain) exact))))
        matches (into {} (keep (fn [t] (when-let [f (first (candidates-for t))] [t f])))
                      track-ids)]
    {:episode-candidates ep-files
     :matches matches
     :missing (vec (remove #(contains? matches %) track-ids))
     ;; More than one equally-exact file for one track is ambiguous, not a
     ;; first-match-wins: guessing here silently attaches the wrong audio.
     :ambiguous (vec (keep (fn [t] (let [c (candidates-for t)]
                                     (when (> (count c) 1) [t c])))
                           track-ids))
     :duplicates (vec (keep (fn [[f n]] (when (> n 1) f))
                            (frequencies (vals matches))))}))

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

(defn tool-start-production
  "Start processing for an existing, saved production.

   Pairs with `action: \"save\"` on start_multitrack_production: create without
   spending processing time, verify the resolved track list, then start here.
   POST /production/{uuid}/start.json takes no body."
  [{:keys [production_uuid]}]
  (if-let [validation-error (validate-required-fields
                             {:production_uuid production_uuid}
                             [:production_uuid])]
    {:error (:error validation-error)}

    (let [result (auphonic-request :post (str "/production/" production_uuid "/start.json"))]
      (if (:success result)
        (let [prod (get-in result [:data :data])
              uuid (or (:uuid prod) production_uuid)]
          (track-production! uuid)
          (println (str "[auphonic-mcp] started production " uuid
                        " status=" (:status_string prod)))
          {:content [{:type "text"
                      :text (str "✓ Production started\n"
                                 "UUID: " uuid "\n"
                                 "Status: " (:status_string prod) "\n"
                                 "View: https://auphonic.com/production/" uuid)}]
           :production_uuid uuid})
        {:error (or (get-in result [:data :error_message])
                    (:error result)
                    "Failed to start production")}))))

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

(defn tool-start-multitrack-production
  "Apply a preset to audio that already lives on an external service, and start it.

   Nothing is uploaded: Auphonic pulls each track from the service itself.
   Track ids come from the preset; filenames are derived from 'service'+'episode'
   or supplied explicitly via 'tracks'.

   'action' must be exactly \"start\" (the default) or \"save\". Any other value
   is rejected rather than guessed — this action spends credits and cannot be
   undone, so a typo must not silently start a production.

   'dry_run' resolves everything and returns the payload without creating
   anything. It is read leniently in the SAFE direction: anything not clearly
   false (true, \"true\", 1) previews instead of posting."
  [args]
  (let [{:keys [preset show episode service tracks skip_tracks title basename action dry_run]} args
        normalized-show (some-> show str/lower-case)
        shows (get-available-shows)
        preset-uuid (or preset (get shows normalized-show))
        ;; A preset UUID given without 'show' still names a show, so recover it
        ;; from the environment -- the default track set is keyed by show name.
        preset-show (or normalized-show
                        (some (fn [[s u]] (when (= u preset-uuid) s)) shows))
        action (let [a (some-> action str/lower-case str/trim)]
                 (if (str/blank? (str a)) "start" a))
        dry? (flag-true? dry_run)
        skips (cond
                (nil? skip_tracks) #{}
                (sequential? skip_tracks)
                (->> skip_tracks
                     (map #(str/lower-case (str/trim (str %))))
                     (remove str/blank?)
                     set)
                :else :invalid)
        parsed-episode (when episode (parse-episode episode))]
    (cond
      (nil? preset-uuid)
      {:error (if show
                (str "Unknown show '" show "'. Configure AUPHONIC_PRESET_"
                     (str/upper-case show) "=<PRESET_UUID> or pass 'preset'.")
                "Missing 'preset' or 'show' argument.")}

      (not (contains? #{"start" "save"} action))
      {:error (str "Invalid 'action' " (pr-str action)
                   ". Use \"start\" (default) or \"save\".")}

      (= :invalid skips)
      {:error (str "'skip_tracks' must be a list of track ids, got "
                   (pr-str skip_tracks) ".")}

      (and episode (:error parsed-episode))
      {:error (:error parsed-episode)}

      :else
      (if-let [preset-data (get-preset-details preset-uuid)]
        (let [preset-name (:preset_name preset-data)
              preset-track-ids (->> (:multi_input_files preset-data)
                                    (filter #(= "multitrack" (:type %)))
                                    (map :id)
                                    (remove str/blank?)
                                    vec)
              fallback-track-ids (when (empty? preset-track-ids)
                                   (get-default-track-set preset-show))
              candidate-track-ids (or (seq preset-track-ids) fallback-track-ids)
              episode-number (:episode parsed-episode)
              wanted-track-ids (->> candidate-track-ids
                                    (remove #(contains? skips (str/lower-case (str %))))
                                    vec)
              explicit-tracks (when (map? tracks)
                                (into {} (map (fn [[k v]] [(track-id-str k) (str v)]))
                                      tracks))
              url-tracks (when explicit-tracks
                           (->> explicit-tracks
                                (filter (fn [[_ v]] (url-ish? v)))
                                (map key)
                                sort vec))
              blank-tracks (when explicit-tracks
                             (->> explicit-tracks
                                  (filter (fn [[_ v]] (str/blank? (str v))))
                                  (map key)
                                  sort vec))
              unknown-ids (when (and explicit-tracks (seq preset-track-ids))
                            (let [known (set (map str/lower-case preset-track-ids))]
                              (->> explicit-tracks
                                   keys
                                   (remove #(contains? known (str/lower-case %)))
                                   sort vec)))
              svc (when-not (str/blank? (str service)) (resolve-service service))
              service-uuid (:uuid svc)
              track-hint (cond
                           (seq preset-track-ids)
                           (str " Preset defines tracks: "
                                (str/join ", " preset-track-ids) ".")
                           (seq fallback-track-ids)
                           (str " Show " preset-show " defaults to tracks: "
                                (str/join ", " fallback-track-ids) ".")
                           :else
                           " Pass 'tracks', or set AUPHONIC_TRACKS_<SHOW>=A,B,C.")
              resolution
              (cond
                ;; A service that was supplied but could not be resolved is an
                ;; error even when explicit tracks are given: otherwise the bare
                ;; filenames get sent as though they were HTTP URLs.
                (:error svc)
                {:error (:error svc)}

                (and explicit-tracks (empty? explicit-tracks))
                {:error "The 'tracks' map is empty."}

                (seq blank-tracks)
                {:error (str "Track(s) " (str/join ", " blank-tracks)
                             " have no filename.")}

                ;; Mirror of the above: a service plus URL-ish values would send
                ;; the URL verbatim as a service filename.
                (and explicit-tracks service-uuid (seq url-tracks))
                {:error (str "Track(s) " (str/join ", " url-tracks)
                             " look like URLs, but 'service' was also given."
                             " Omit 'service' to pass URLs, or supply service"
                             " filenames instead.")}

                explicit-tracks
                {:tracks (->> explicit-tracks
                              (remove (fn [[k _]] (contains? skips (str/lower-case k))))
                              (into {}))
                 :source "explicit 'tracks' argument"
                 :warn (when (seq unknown-ids)
                         (str "Track id(s) " (pr-str unknown-ids)
                              " are not defined by preset " preset-uuid
                              " — they will have no per-track algorithms."))}

                (and service-uuid episode-number (seq wanted-track-ids))
                (let [files (list-service-files service-uuid)]
                  (if (nil? files)
                    {:error (str "Could not list files on service " service-uuid
                                 " (network error or rate limit). Retry.")}
                    (let [{:keys [matches missing episode-candidates
                                  duplicates ambiguous]}
                          (find-episode-stems files episode-number wanted-track-ids)]
                      (cond
                        (seq ambiguous)
                        {:error (str "Ambiguous stem for "
                                     (str/join "; "
                                               (map (fn [[t c]] (str t " -> " (pr-str c)))
                                                    ambiguous))
                                     " — refusing to guess which file to use.")}

                        (seq duplicates)
                        {:error (str "Tracks resolve to the same file "
                                     (pr-str duplicates)
                                     " — refusing to guess which track is which.")}

                        (seq missing)
                        {:error (str "No stem found for track(s) "
                                     (str/join ", " missing) " in episode "
                                     episode-number ". "
                                     (if (seq episode-candidates)
                                       (str "Episode " episode-number " has: "
                                            (str/join ", " episode-candidates))
                                       (str "Episode " episode-number
                                            " has no files on that service."))
                                     (when (seq skips)
                                       (str " Skipping: " (str/join ", " skips) ".")))}

                        :else
                        {:tracks matches
                         :source (str "service " service-uuid ", episode " episode-number
                                      (when (and (empty? preset-track-ids)
                                                 (seq fallback-track-ids))
                                        (str " (default lineup for show "
                                             preset-show ")")))}))))

                (str/blank? (str service))
                {:error (str "Need either 'tracks', or 'service' + 'episode'." track-hint)}

                (and (not (str/blank? (str service))) (nil? episode-number))
                {:error (str "Pass 'episode' to pick stems from the service, or provide"
                             " 'tracks' explicitly." track-hint)}

                (empty? candidate-track-ids)
                {:error (str "Preset " preset-uuid " defines no multitrack tracks and "
                             (if preset-show
                               (str "show " preset-show " has no default track set.")
                               "no show was given to look one up.")
                             " Pass 'tracks' explicitly, or set"
                             " AUPHONIC_TRACKS_<SHOW>=A,B,C.")}

                (empty? wanted-track-ids)
                {:error (str "No tracks left to run — all of "
                             (pr-str candidate-track-ids) " were skipped.")}

                :else
                {:error (str "Could not resolve tracks." track-hint)})]

          (if-let [err (:error resolution)]
            {:error err}

            (let [track-map (:tracks resolution)
                  pairs (sort-by first track-map)
                  known-ids (set (map str/lower-case preset-track-ids))
                  on-preset (filterv (fn [[tid _]]
                                       (contains? known-ids (str/lower-case (str tid))))
                                     pairs)
                  extra (filterv (fn [[tid _]]
                                   (not (contains? known-ids (str/lower-case (str tid)))))
                                 pairs)
                  ;; Preset tracks this run does NOT supply — they are replaced,
                  ;; so they must be removed or they linger empty.
                  requested-ids (set (map (fn [[tid _]] (str/lower-case (str tid))) pairs))
                  dropped (filterv (fn [tid]
                                     (not (contains? requested-ids
                                                     (str/lower-case (str tid)))))
                                   preset-track-ids)
                  start? (= "start" action)
                  create-payload (cond-> {:preset preset-uuid}
                                   (not (str/blank? (str title)))
                                   (assoc-in [:metadata :title] title)
                                   (not (str/blank? (str basename)))
                                   (assoc :output_basename basename))]

              (cond
                (empty? pairs)
                {:error "Resolved zero tracks — refusing to create an empty production."}

                dry?
                (let [mk-entry (fn [[tid fname]] (cond-> {:track_id tid} service-uuid (assoc :service service-uuid) (not (str/blank? (str fname))) (assoc :input_file fname)))]
                  {:content [{:type "text"
                              :text (str "DRY RUN — nothing created.\n"
                                         "Preset: " preset-uuid
                                         (when preset-name (str " (" preset-name ")")) "\n"
                                         "Tracks resolved from: " (:source resolution) "\n"
                                         (when (seq skips)
                                           (str "Skipping: " (str/join ", " skips) "\n"))
                                         (when (:warn resolution)
                                           (str "Warning: " (:warn resolution) "\n"))
                                         "\nRuns in steps:\n"
                                         "  1. create production from preset ("
                                         (count on-preset) " preset track(s))\n"
                                         "  2. fill each preset track by id\n"
                                         (when (seq dropped)
                                           (str "  2b. remove preset track(s) not supplied: "
                                                (str/join ", " dropped) "\n"))
                                         (when (seq extra)
                                           (str "  3. append " (count extra)
                                                " track(s) the preset does not define: "
                                                (str/join ", " (map first extra)) "\n"))
                                         "  4. " (if start? "start processing" "leave saved") "\n"
                                         "\nTrack list (" (count pairs) "):\n"
                                         (json/generate-string
                                          (mapv (fn [[tid fname]]
                                                  (cond-> {:id tid}
                                                    service-uuid (assoc :service service-uuid)
                                                    (not (str/blank? (str fname)))
                                                    (assoc :input_file fname)))
                                                pairs)
                                          {:pretty true}))}]
                   :dry_run true
                   :tracks track-map
                   :plan {:create create-payload
                          :fill (mapv mk-entry on-preset)
                          :append (mapv mk-entry extra)
                          :preset_track_ids (vec preset-track-ids)
                          :drop (vec dropped)
                          :start start?}})

                :else
                (do
                  (println (str "[auphonic-mcp] multitrack " action
                                " preset=" preset-uuid
                                " service=" (or service-uuid "none")
                                " tracks=" (json/generate-string (into (sorted-map) track-map))))
                  ;; Phase 1 — create from the preset with NO multi_input_files,
                  ;; so the preset's own track list is what we start from.
                  (let [create (auphonic-json-post "/productions.json" create-payload)
                        uuid (get-in create [:data :data :uuid])]
                    (if (or (not (:success create)) (str/blank? (str uuid)))
                      (let [err (or (get-in create [:data :error_message])
                                    (:error create) "no production UUID returned")]
                        (println (str "[auphonic-mcp] multitrack create FAILED: " err))
                        {:error (str "Could not create the production: " err)})

                      (let [;; Phase 2 — fill the preset's own tracks, one call each.
                            fill (mapv (fn [[tid fname]]
                                         [tid (attach-track! uuid tid service-uuid fname)])
                                       on-preset)
                            ;; Phase 2b — remove preset tracks this run replaces.
                            ;; Leaving them would make them empty AND push the
                            ;; production back over the 8-track limit.
                            drop-results (mapv (fn [tid] [tid (remove-track! uuid tid)])
                                               dropped)
                            bad (or (first (filterv (fn [[_ r]] (not (:success r))) fill))
                                    (first (filterv (fn [[_ r]] (not (:success r)))
                                                    drop-results)))]
                        (if bad
                          (let [[tid r] bad
                                err (or (get-in r [:data :error_message])
                                        (:error r) "unknown error")]
                            (println (str "[auphonic-mcp] multitrack track-update FAILED '"
                                          tid "': " err))
                            {:error (str "Updating track '" tid "' failed: " err
                                         ". A partial production was created ("
                                         uuid ") — delete it before retrying.")})

                          (let [;; Phase 3 — append the tracks the preset lacks.
                                app (when (seq extra)
                                      (append-tracks! uuid extra service-uuid))
                                app-err (when (and app (not (:success app)))
                                          (or (get-in app [:data :error_message])
                                              (:error app) "unknown error"))]
                            (if app-err
                              (do
                                (println (str "[auphonic-mcp] multitrack append FAILED: " app-err))
                                {:error (str "Adding extra tracks failed: " app-err
                                             ". A partial production was created ("
                                             uuid ") — delete it before retrying.")})

                              (let [;; Phase 4 — read back what Auphonic ACTUALLY stored,
                                    ;; so a mismatch is visible instead of assumed.
                                    actual (production-tracks uuid)
                                    actual-ids (mapv :id actual)
                                    expected-ids (mapv first pairs)
                                    via-count (when (seq actual)
                                                (when (not= (count actual) (count pairs))
                                                  (str "Auphonic stored " (count actual)
                                                       " tracks but " (count pairs)
                                                       " were requested.")))
                                    ;; Phase 5 — start, only when asked.
                                    started (when start?
                                              (auphonic-request :post (str "/production/"
                                                                           uuid "/start.json")))
                                    start-err (when (and started (not (:success started)))
                                                (or (get-in started [:data :error_message])
                                                    (:error started) "unknown error"))]
                                (track-production! uuid)
                                (println (str "[auphonic-mcp] multitrack production " uuid
                                              " stored=" (count actual)
                                              " ids=" (pr-str actual-ids)
                                              (when start? (str " started=" (not (boolean start-err))))))
                                {:content [{:type "text"
                                            :text (str (if start?
                                                         (if start-err
                                                           (str "⚠ Saved, but NOT started: " start-err "\n")
                                                           "✓ Multitrack production started\n")
                                                         "✓ Multitrack production saved (not started)\n")
                                                       "UUID: " uuid "\n"
                                                       "Tracks stored: " (count actual) "\n"
                                                       (when via-count (str "⚠ " via-count "\n"))
                                                       (when (:warn resolution)
                                                         (str "Warning: " (:warn resolution) "\n"))
                                                       "\nActual track list:\n"
                                                       (str/join
                                                        "\n"
                                                        (map (fn [t]
                                                               (str "  " (:id t)
                                                                    "  <-  " (or (:input_file t) "(no file)")))
                                                             actual))
                                                       "\n\nView: https://auphonic.com/production/" uuid)}]
                                 :production_uuid uuid
                                 :tracks actual
                                 :requested_ids expected-ids
                                 :track_count (count actual)
                                 :started (and start? (not start-err))}))))))))))))

        {:error (str "Could not load preset: " preset-uuid
                     " (not found, or the Auphonic API is unreachable or"
                     " rate-limited).")}))))

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

    {:name "start_production"
     :description (str "Start processing for an existing saved Auphonic production. "
                       "Pairs with start_multitrack_production's action:\"save\", which creates a "
                       "production WITHOUT spending processing time: save it, read the track list "
                       "back via check_status, then start it here.")
     :inputSchema {:type "object"
                   :properties {:production_uuid {:type "string"
                                                  :description "Production UUID to start"}}
                   :required ["production_uuid"]}}

    {:name "list_presets"
     :description "List all available Auphonic presets"
     :inputSchema {:type "object"}}

    {:name "start_multitrack_production"
     :description (str "Start (or save) a multitrack Auphonic production by applying a preset to "
                       "audio files that ALREADY live on an external service (WebDAV/S3). "
                       "Nothing is uploaded — Auphonic pulls each track from the service itself. "
                       "Tracks are taken from the 'tracks' argument, or derived automatically from "
                       "'service' + 'episode' using the track ids defined in the preset "
                       "(e.g. episode 688 with tracks Chris/Wes/Brent/Mumble matches "
                       "'Linux Unplugged 688 - Chris.flac' etc). "
                       "Set dry_run to resolve the stems and preview the exact request without creating anything.")
     :inputSchema {:type "object"
                   :properties {:preset {:type "string"
                                         :description "Auphonic preset UUID. Overrides 'show'."}
                                :show {:type "string"
                                       :description "Show name resolving to AUPHONIC_PRESET_<SHOW> (e.g. 'lup'). Used when 'preset' is absent."}
                                :service {:type "string"
                                          :description "External service UUID or display name (e.g. 'LUP Stems') holding the track files."}
                                :episode {:type "number"
                                          :description "Episode number used to select stems from 'service' (e.g. 688)."}
                                :tracks {:type "object"
                                         :description "Explicit track map {\"Chris\": \"file.flac\", ...}. Overrides service/episode derivation. Ignored if 'service' is given but cannot be resolved."}
                                :skip_tracks {:type "array"
                                              :items {:type "string"}
                                              :description "Track ids to omit (e.g. an episode with no guest track). Optional."}
                                :title {:type "string"
                                        :description "Production title metadata (optional)."}
                                :basename {:type "string"
                                           :description "output_basename for the production (optional), e.g. 'lup-0689'."}
                                :action {:type "string"
                                         :enum ["start" "save"]
                                         :description "\"start\" (default) to run immediately, or \"save\" to only save the production. Any other value is rejected."}
                                :dry_run {:type "boolean"
                                          :description "If true, resolve tracks and show the request that would be sent, without creating anything."}}}}]})

(defn handle-tools-call [{:keys [name arguments]}]
  (let [result (case name
                 "upload_audio" (tool-upload-audio arguments)
                 "check_status" (tool-check-status arguments)
                 "list_productions" (tool-list-productions arguments)
                 "download_output" (tool-download-output arguments)
                 "delete_production" (tool-delete-production arguments)
                 "list_presets" (tool-list-presets)
                 "start_production" (tool-start-production arguments)
                 "start_multitrack_production" (tool-start-multitrack-production arguments)
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

        ;; Streamable HTTP spec 2025-03-26: this server does not offer a
        ;; server->client SSE stream, so GET on the MCP endpoint must answer
        ;; 405 Method Not Allowed (not 404) — clients use that to decide
        ;; whether an event stream is available.
        [:get "/mcp"]
        {:status 405
         :headers {"Content-Type" "application/json"
                   "Allow" "POST, DELETE"}
         :body (json/generate-string
                {:error "Method not allowed. MCP requests use POST; this server does not offer an SSE stream."})}

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
