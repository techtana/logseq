(ns mobile.voice-note
  "Voice notes dictated from the Android home screen widget. The widget opens
  `logseq://mobile/go/voice-note`, which starts a session: the native
  `VoiceNote` plugin keeps listening until the session is finished or
  discarded, and each recognized phrase is written into the note's block in
  today's journal."
  (:require [clojure.string :as string]
            [frontend.date :as date]
            [frontend.db.async :as db-async]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.page :as page-handler]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [lambdaisland.glogi :as log]
            [logseq.db :as ldb]
            [promesa.core :as p]))

(def transcription-languages
  "BCP 47 tags offered as the transcription language. The empty setting keeps
  the device's voice typing language."
  ["af-ZA" "ar-SA" "ca-ES" "cs-CZ" "da-DK" "de-DE" "el-GR" "en-AU" "en-GB"
   "en-IN" "en-US" "es-ES" "es-MX" "fa-IR" "fi-FI" "fil-PH" "fr-CA" "fr-FR"
   "he-IL" "hi-IN" "hu-HU" "id-ID" "it-IT" "ja-JP" "ko-KR" "ms-MY" "nb-NO"
   "nl-NL" "pl-PL" "pt-BR" "pt-PT" "ro-RO" "ru-RU" "sk-SK" "sv-SE" "th-TH"
   "tr-TR" "uk-UA" "vi-VN" "zh-CN" "zh-TW"])

(def ^:private settings-key :ls-mobile-voice-note)

(defn get-settings
  "`:language` is a BCP 47 tag, empty for the device language. `:parent-block`
  is the title of the top-level block in today's journal that notes go under,
  empty for top-level notes."
  []
  (merge {:language "" :parent-block ""}
         (storage/get settings-key)))

(defn save-settings!
  [settings]
  (storage/set settings-key (select-keys settings [:language :parent-block])))

;; Session

(defonce ^{:doc "The open voice note, nil when there is none.
  `:status` is one of :starting, :listening, :paused, :error and :saving.
  `:segments` are the recognized phrases, `:partial` the phrase being spoken.
  `:block-uuid` is set once the note's block exists; `:saved-text` is the text
  last written to it."}
  *session
  (atom nil))

(defonce ^:private *write-chain (atom (p/resolved nil)))

(defn- now [] (js/Date.now))

(defn- update-session!
  "Applies `f` to the open session; events that arrive after it closed are
  dropped."
  [f & args]
  (swap! *session #(when % (apply f % args))))

(defn transcript
  [session]
  (string/join " " (:segments session)))

(defn listening-ms
  "Time spent listening, excluding pauses."
  [{:keys [recorded-ms listening-since]} now-ms]
  (+ recorded-ms (if listening-since (- now-ms listening-since) 0)))

(defn- top-level-block-titled
  [page title]
  (let [title' (string/lower-case title)]
    (->> (editor-handler/worker-children page)
         (filter #(= (:db/id page) (editor-handler/ref-db-id (:block/parent %))))
         ldb/sort-by-order
         (some #(when (= title' (some-> (:block/title %) string/trim string/lower-case))
                  %)))))

(defn- <find-parent-block
  [repo page-uuid title]
  (p/let [result (db-async/<get-block-with-children repo page-uuid)]
    (top-level-block-titled (editor-handler/worker-block-with-children result) title)))

(defn- <ensure-parent-block!
  "Returns the top-level block of today's journal titled `title` (case
  insensitive), creating it at the end of the page when missing."
  [repo page-uuid title]
  (p/let [parent (<find-parent-block repo page-uuid title)]
    (or parent
        (p/do!
         (editor-handler/api-insert-new-block! title {:page page-uuid
                                                      :edit-block? false
                                                      :replace-empty-target? true})
         ;; Look it up again: replacing an empty block keeps that block's uuid.
         (p/let [parent (<find-parent-block repo page-uuid title)]
           (or parent
               (throw (ex-info "Voice note parent block wasn't created" {:title title}))))))))

(defn- <insert-note!
  "Adds the note's block at the end of today's journal, under `parent-title`
  unless it is blank."
  [repo text parent-title]
  (p/let [page (page-handler/<create! (date/today) {:redirect? false
                                                    :split-namespace? false
                                                    :today-journal? true})
          page-uuid (or (:block/uuid page)
                        (throw (ex-info "Today's journal page is missing" {:title (date/today)})))
          target (if (string/blank? parent-title)
                   {:page page-uuid}
                   (p/let [parent (<ensure-parent-block! repo page-uuid parent-title)]
                     {:block-uuid (:block/uuid parent)}))
          block (editor-handler/api-insert-new-block! text (assoc target :edit-block? false))]
    (or block
        (throw (ex-info "Voice note block wasn't created" {:parent-title parent-title})))))

(defn- <write-transcript!
  "Creates the note's block on the first write, then keeps its title equal to
  the transcript."
  []
  (when-let [{:keys [repo block-uuid parent-block saved-text] :as session} @*session]
    (let [text (transcript session)]
      (when-not (or (string/blank? text) (= text saved-text))
        (p/do!
         (if block-uuid
           (editor-handler/save-block! repo block-uuid text)
           (p/let [block (<insert-note! repo text parent-block)]
             (update-session! assoc :block-uuid (:block/uuid block))))
         (update-session! assoc :saved-text text))))))

(defn- queue-write!
  "Writes the current transcript after the writes already queued. Each write
  uses the latest transcript, so a failed one is made up by the next."
  []
  (swap! *write-chain
         (fn [chain]
           (-> chain
               (p/then (fn [_] (<write-transcript!)))
               (p/catch (fn [error]
                          (log/error ::write-failed {:error error})))))))

(defn- on-state!
  [listening?]
  (update-session!
   (fn [{:keys [status listening-since] :as session}]
     (if listening?
       (assoc session
              :status :listening
              :listening-since (now)
              :error-code nil)
       (cond-> (assoc session :listening-since nil :partial "" :level 0)
         listening-since (update :recorded-ms + (- (now) listening-since))
         (contains? #{:starting :listening} status) (assoc :status :paused))))))

(defn- on-result!
  [text]
  (update-session! #(-> %
                        (update :segments conj text)
                        (assoc :partial "")))
  (queue-write!))

(defn- on-error!
  [code]
  (update-session! assoc :status :error :error-code (keyword code)))

(defn- <add-plugin-listeners!
  []
  (let [^js plugin mobile-util/voice-note]
    (p/all
     [(.addListener plugin "state" (fn [^js e] (on-state! (.-listening e))))
      (.addListener plugin "partial" (fn [^js e] (update-session! assoc :partial (.-text e))))
      (.addListener plugin "result" (fn [^js e] (on-result! (.-text e))))
      (.addListener plugin "level" (fn [^js e] (update-session! assoc :level (.-level e))))
      (.addListener plugin "error" (fn [^js e] (on-error! (.-code e))))])))

(defn- close-session!
  []
  (reset! *session nil)
  (.removeAllListeners ^js mobile-util/voice-note))

(defn- <listen!
  []
  (update-session! assoc :status :starting :error-code nil)
  (-> (.start ^js mobile-util/voice-note #js {:language (:language @*session)})
      (p/catch (fn [^js error]
                 (log/warn ::start-failed {:error error})
                 ;; Rejections without a code have no specific message.
                 (on-error! (or (.-code error) "other"))))))

(defn <start-session!
  "Opens a voice note and starts listening. Does nothing while one is open."
  []
  (when-not @*session
    (let [{:keys [language parent-block]} (get-settings)]
      (reset! *session {:repo (state/get-current-repo)
                        :language language
                        :parent-block (string/trim parent-block)
                        :status :starting
                        :segments []
                        :partial ""
                        :level 0
                        :recorded-ms 0})
      (p/do!
       (<add-plugin-listeners!)
       (<listen!)))))

(defn <pause!
  []
  (.stop ^js mobile-util/voice-note))

(defn <resume!
  []
  (<listen!))

(defn <finish!
  "Stops listening and saves the transcript. Resolves to :saved or :empty once
  the session is closed, or :unsaved when the last write failed; the session
  then stays open, paused, so finishing can be tried again. Resolves to nil
  when there's no session or it's already finishing."
  []
  (when (and @*session (not= :saving (:status @*session)))
    (update-session! assoc :status :saving)
    (p/let [_ (.stop ^js mobile-util/voice-note)
            ;; The last result arrives before `stop` resolves.
            _ (queue-write!)
            session @*session
            text (transcript session)]
      (cond
        (string/blank? text)
        (do (close-session!) :empty)

        (= text (:saved-text session))
        (do (close-session!) :saved)

        :else
        (do (update-session! assoc :status :paused) :unsaved)))))

(defn <discard!
  "Stops listening, removes the note's block if it was written and closes the
  session."
  []
  (when (and @*session (not= :saving (:status @*session)))
    (update-session! assoc :status :saving)
    (p/let [_ (.cancel ^js mobile-util/voice-note)
            _ @*write-chain
            {:keys [repo block-uuid]} @*session
            block (when block-uuid
                    (db-async/<get-block repo block-uuid {:children? false}))
            _ (when block
                (editor-handler/delete-block-aux! block))]
      (close-session!))))
