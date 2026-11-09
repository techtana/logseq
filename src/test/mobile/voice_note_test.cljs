(ns mobile.voice-note-test
  (:require [cljs.test :refer [async deftest is testing]]
            [frontend.db.async :as db-async]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.page :as page-handler]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [mobile.deeplink :as deeplink]
            [mobile.voice-note :as voice-note]
            [promesa.core :as p]))

(def ^:private page-id 1)
(def ^:private page-uuid #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private parent-uuid #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private created-parent-uuid #uuid "00000000-0000-0000-0000-000000000003")
(def ^:private note-uuid #uuid "00000000-0000-0000-0000-000000000005")

(defn- fake-plugin
  "VoiceNote plugin stub. `:emit!` sends a native event to the listeners."
  []
  (let [*listeners (atom {})
        *calls (atom [])]
    {:*calls *calls
     :emit! (fn [event data]
              (doseq [f (get @*listeners event)]
                (f (clj->js data))))
     :plugin #js {:addListener (fn [event f]
                                 (swap! *listeners update event (fnil conj []) f)
                                 (p/resolved #js {}))
                  :removeAllListeners (fn []
                                        (reset! *listeners {})
                                        (p/resolved nil))
                  :start (fn [^js opts]
                           (swap! *calls conj [:start (.-language opts)])
                           (p/resolved nil))
                  :stop (fn []
                          (swap! *calls conj [:stop])
                          (p/resolved nil))
                  :cancel (fn []
                            (swap! *calls conj [:cancel])
                            (p/resolved nil))}}))

(defn- settle
  "Lets queued journal writes finish."
  []
  (p/delay 10))

(defn- run-session
  "Calls `f` with the fake plugin and the recorded journal writes, against a
  journal page whose top-level blocks are `children`. Resolves after the
  redefs are restored."
  [{:keys [parent-block children insert-fn save-fn]} f]
  (let [{:keys [plugin] :as fake} (fake-plugin)
        *children (atom children)
        *inserts (atom [])
        *saves (atom [])
        *deletes (atom [])]
    (reset! voice-note/*session nil)
    (p/with-redefs [mobile-util/voice-note plugin
                    voice-note/get-settings (constantly {:language "th-TH"
                                                         :parent-block parent-block})
                    state/get-current-repo (constantly "logseq_db_test")
                    page-handler/<create! (fn [_title _opts]
                                            (p/resolved {:db/id page-id
                                                         :block/uuid page-uuid}))
                    db-async/<get-block-with-children (fn [_repo id]
                                                        (is (= page-uuid id))
                                                        (p/resolved {:block {:db/id page-id
                                                                             :block/uuid page-uuid}
                                                                     :children @*children}))
                    db-async/<get-block (fn [_repo id _opts]
                                          (p/resolved {:db/id 9 :block/uuid id}))
                    editor-handler/api-insert-new-block! (fn [content opts]
                                                           (swap! *inserts conj [content opts])
                                                           (if insert-fn
                                                             (insert-fn content opts *children)
                                                             (p/resolved {:block/uuid note-uuid})))
                    editor-handler/save-block! (fn [_repo id content]
                                                 (swap! *saves conj [id content])
                                                 (if save-fn
                                                   (save-fn content)
                                                   (p/resolved nil)))
                    editor-handler/delete-block-aux! (fn [block]
                                                       (swap! *deletes conj (:block/uuid block))
                                                       (p/resolved nil))]
      (f (assoc fake
                :*inserts *inserts
                :*saves *saves
                :*deletes *deletes)))))

(defn- fail-on-error
  [done]
  (fn [e]
    (is false (str e))
    (done)))

(deftest phrases-are-written-under-the-parent-block-as-they-arrive
  (async done
    (testing "the parent title matches a top-level block, ignoring case and spaces"
      (-> (run-session
           {:parent-block " journal "
            :children [{:db/id 4
                        :block/uuid #uuid "00000000-0000-0000-0000-000000000004"
                        :block/title "journal"
                        :block/parent {:db/id 2}
                        :block/order "a1"}
                       {:db/id 2
                        :block/uuid parent-uuid
                        :block/title "Journal "
                        :block/parent {:db/id page-id}
                        :block/order "a0"}]}
           (fn [{:keys [emit! *calls *inserts *saves]}]
             (p/let [_ (voice-note/<start-session!)
                     _ (emit! "state" {:listening true})
                     _ (is (= :listening (:status @voice-note/*session)))
                     _ (emit! "partial" {:text "first"})
                     _ (is (= "first" (:partial @voice-note/*session)))
                     _ (emit! "result" {:text "first phrase"})
                     _ (settle)
                     _ (emit! "result" {:text "second phrase"})
                     _ (settle)
                     result (voice-note/<finish!)]
               {:result result
                :calls @*calls
                :inserts @*inserts
                :saves @*saves})))
          (p/then (fn [{:keys [result calls inserts saves]}]
                    (is (= :saved result))
                    (is (= [[:start "th-TH"] [:stop]] calls))
                    (is (= [["first phrase" {:block-uuid parent-uuid :edit-block? false}]]
                           inserts)
                        "the first phrase creates the note's block")
                    (is (= [[note-uuid "first phrase second phrase"]] saves)
                        "later phrases update the same block")
                    (is (nil? @voice-note/*session))
                    (done)))
          (p/catch (fail-on-error done))))))

(deftest missing-parent-block-is-created
  (async done
    (-> (run-session
         {:parent-block "Journal"
          :children [{:db/id 4
                      :block/uuid #uuid "00000000-0000-0000-0000-000000000004"
                      :block/title "Meeting"
                      :block/parent {:db/id page-id}
                      :block/order "a0"}]
          :insert-fn (fn [content opts *children]
                       (if (:page opts)
                         (do (swap! *children conj {:db/id 6
                                                    :block/uuid created-parent-uuid
                                                    :block/title content
                                                    :block/parent {:db/id page-id}
                                                    :block/order "a1"})
                             (p/resolved nil))
                         (p/resolved {:block/uuid note-uuid})))}
         (fn [{:keys [emit! *inserts]}]
           (p/let [_ (voice-note/<start-session!)
                   _ (emit! "result" {:text "note"})
                   result (voice-note/<finish!)]
             {:result result :inserts @*inserts})))
        (p/then (fn [{:keys [result inserts]}]
                  (is (= :saved result))
                  (is (= [["Journal" {:page page-uuid
                                      :edit-block? false
                                      :replace-empty-target? true}]
                          ["note" {:block-uuid created-parent-uuid :edit-block? false}]]
                         inserts))
                  (done)))
        (p/catch (fail-on-error done)))))

(deftest blank-parent-adds-a-top-level-note
  (async done
    (-> (run-session
         {:parent-block "  " :children []}
         (fn [{:keys [emit! *inserts]}]
           (p/let [_ (voice-note/<start-session!)
                   _ (emit! "result" {:text "note"})
                   result (voice-note/<finish!)]
             {:result result :inserts @*inserts})))
        (p/then (fn [{:keys [result inserts]}]
                  (is (= :saved result))
                  (is (= [["note" {:page page-uuid :edit-block? false}]] inserts))
                  (done)))
        (p/catch (fail-on-error done)))))

(deftest discard-removes-the-written-note
  (async done
    (-> (run-session
         {:parent-block "" :children []}
         (fn [{:keys [emit! *calls *deletes]}]
           (p/let [_ (voice-note/<start-session!)
                   _ (emit! "result" {:text "never mind"})
                   _ (settle)
                   _ (voice-note/<discard!)]
             {:calls @*calls :deletes @*deletes})))
        (p/then (fn [{:keys [calls deletes]}]
                  (is (= [[:start "th-TH"] [:cancel]] calls))
                  (is (= [note-uuid] deletes))
                  (is (nil? @voice-note/*session))
                  (done)))
        (p/catch (fail-on-error done)))))

(deftest failed-write-keeps-the-session-open
  (async done
    (let [*fail? (atom true)]
      (-> (run-session
           {:parent-block ""
            :children []
            :save-fn (fn [_content]
                       (if @*fail?
                         (p/rejected (ex-info "save failed" {}))
                         (p/resolved nil)))}
           (fn [{:keys [emit! *saves]}]
             (p/let [_ (voice-note/<start-session!)
                     _ (emit! "result" {:text "one"})
                     _ (settle)
                     _ (emit! "result" {:text "two"})
                     first-try (voice-note/<finish!)
                     status (:status @voice-note/*session)
                     _ (reset! *fail? false)
                     second-try (voice-note/<finish!)]
               {:first-try first-try
                :status status
                :second-try second-try
                :saves @*saves})))
          (p/then (fn [{:keys [first-try status second-try saves]}]
                    (is (= :unsaved first-try))
                    (is (= :paused status) "the transcript stays in the recorder")
                    (is (= :saved second-try))
                    (is (= [note-uuid "one two"] (last saves)))
                    (done)))
          (p/catch (fail-on-error done))))))

(deftest finishing-without-speech-writes-nothing
  (async done
    (-> (run-session
         {:parent-block "Journal" :children []}
         (fn [{:keys [*inserts]}]
           (p/let [_ (voice-note/<start-session!)
                   result (voice-note/<finish!)]
             {:result result :inserts @*inserts})))
        (p/then (fn [{:keys [result inserts]}]
                  (is (= :empty result))
                  (is (= [] inserts))
                  (is (nil? @voice-note/*session))
                  (done)))
        (p/catch (fail-on-error done)))))

(deftest recognizer-state-drives-the-session-status
  (async done
    (-> (run-session
         {:parent-block "" :children []}
         (fn [{:keys [emit!]}]
           (p/let [_ (voice-note/<start-session!)
                   _ (emit! "state" {:listening true})
                   _ (emit! "state" {:listening false})
                   paused (:status @voice-note/*session)
                   _ (emit! "state" {:listening true})
                   _ (emit! "error" {:code "network"})
                   _ (emit! "state" {:listening false})
                   errored (select-keys @voice-note/*session [:status :error-code])
                   _ (voice-note/<resume!)
                   resumed (select-keys @voice-note/*session [:status :error-code])
                   _ (voice-note/<discard!)]
             {:paused paused :errored errored :resumed resumed})))
        (p/then (fn [{:keys [paused errored resumed]}]
                  (is (= :paused paused))
                  (is (= {:status :error :error-code :network} errored)
                      "the recognizer stopping after an error keeps the error")
                  (is (= {:status :starting :error-code nil} resumed))
                  (done)))
        (p/catch (fail-on-error done)))))

(deftest listening-time-excludes-pauses
  (is (= 5000 (voice-note/listening-ms {:recorded-ms 5000 :listening-since nil} 9000)))
  (is (= 7000 (voice-note/listening-ms {:recorded-ms 5000 :listening-since 7000} 9000))))

(deftest settings-default-to-device-language-and-top-level-notes
  (is (= {:language "" :parent-block ""} (voice-note/get-settings))))

(deftest voice-note-deeplink-starts-the-recorder
  (async done
    (let [*events (atom [])]
      ;; `done` runs after p/with-redefs has put the real fn back, so the next
      ;; test doesn't start with the stub still in place.
      (-> (p/with-redefs [state/pub-event! (fn [event]
                                             (swap! *events conj event)
                                             nil)]
            (deeplink/deeplink "logseq://mobile/go/voice-note"))
          (p/then (fn [_]
                    (is (= [[:mobile/start-voice-note]] @*events))
                    (done)))
          (p/catch (fail-on-error done))))))
