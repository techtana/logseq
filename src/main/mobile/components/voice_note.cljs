(ns mobile.components.voice-note
  "Recorder sheet for voice notes started from the Android widget: shows the
  transcript while it's being dictated into today's journal."
  (:require [clojure.string :as string]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [mobile.components.recorder :as recorder]
            [mobile.voice-note :as voice-note]
            [promesa.core :as p]
            [io.factorhouse.hsx.core :as hsx]))

(defn- error-message
  [code]
  (case code
    :permission (t :mobile/microphone-access-denied)
    :network (t :mobile.voice-note/network-error)
    :language (t :mobile.voice-note/language-error)
    :unavailable (t :mobile.voice-note/unavailable-error)
    (t :mobile.voice-note/recognition-error)))

(defn- status-text
  [{:keys [status error-code]}]
  (case status
    :starting (t :mobile.voice-note/starting)
    :listening (t :mobile.voice-note/listening)
    :paused (t :mobile.voice-note/paused)
    :saving (t :mobile.voice-note/saving)
    :error (error-message error-code)))

(defn- <done!
  []
  (p/let [result (voice-note/<finish!)]
    (case result
      :saved (do (shui/popup-hide!)
                 (notification/show! (t :mobile.voice-note/saved) :success))
      :empty (shui/popup-hide!)
      :unsaved (notification/show! (t :mobile.voice-note/save-error) :error)
      nil)))

(defn- <finish-after-dismiss!
  "The sheet was swiped away: keep what was said."
  []
  (p/let [result (voice-note/<finish!)]
    (case result
      :saved (notification/show! (t :mobile.voice-note/saved) :success)
      :unsaved (notification/show! (t :mobile.voice-note/save-error) :error false)
      nil)))

(defn- discard!
  [session]
  (if (string/blank? (voice-note/transcript session))
    (p/do! (voice-note/<discard!) (shui/popup-hide!))
    (let [confirmed (shui/dialog-confirm!
                     nil
                     {:title (t :mobile.voice-note/discard-confirm)
                      :outside-cancel? true
                      :cancel-label (t :ui/cancel)
                      :ok-label (t :mobile.voice-note/discard)})]
      (p/then confirmed
              (fn []
                (p/do! (voice-note/<discard!) (shui/popup-hide!))))
      ;; Cancelling rejects the dialog promise; the note keeps recording.
      (p/catch confirmed (fn [_] nil)))))

(hsx/defc mic-button
  [{:keys [status level]}]
  (let [listening? (= status :listening)
        busy? (contains? #{:starting :saving} status)]
    [:div.relative.flex.items-center.justify-center
     {:style {:width "5rem" :height "5rem"}}
     [:div.absolute.inset-0.rounded-full.bg-primary.opacity-20
      {:style {:transform (str "scale(" (if listening? (+ 1 (* 0.35 level)) 1) ")")
               :transition "transform 80ms linear"}}]
     (shui/button
      {:class "relative rounded-full w-16 h-16"
       :disabled busy?
       :aria-label (if listening?
                     (t :mobile.voice-note/pause)
                     (t :mobile.voice-note/resume))
       :on-click #(if listening?
                    (voice-note/<pause!)
                    (voice-note/<resume!))}
      (shui/tabler-icon (if listening? "player-pause" "microphone") {:size 28}))]))

(hsx/defc recorder
  []
  (let [[session] (hooks/use-atom voice-note/*session)
        [now-ms set-now-ms!] (hooks/use-state (js/Date.now))
        *transcript-ref (hooks/use-ref nil)
        {:keys [segments parent-block status] partial-text :partial} session]
    (hooks/use-effect!
     (fn []
       (let [timer (js/setInterval #(set-now-ms! (js/Date.now)) 500)]
         (fn []
           (js/clearInterval timer)
           (<finish-after-dismiss!))))
     [])
    (hooks/use-effect!
     (fn []
       (when-let [^js node (hooks/deref *transcript-ref)]
         (set! (.-scrollTop node) (.-scrollHeight node)))
       nil)
     [segments partial-text])
    (when session
      [:div.ls-voice-note.flex.flex-col.gap-4.w-full.px-2.pb-4
       [:div.flex.items-start.justify-between.gap-2
        [:div.flex.flex-col.min-w-0
         [:span.text-lg.font-medium (t :mobile.voice-note/recorder-title)]
         [:span.text-sm.opacity-60.truncate
          (if (string/blank? parent-block)
            (t :mobile.voice-note/destination-top-level)
            (t :mobile.voice-note/destination parent-block))]]
        [:span.text-lg.font-medium.tabular-nums
         (recorder/ms-to-time-format (voice-note/listening-ms session now-ms))]]

       [:div.rounded-lg.border.p-3.overflow-y-auto
        {:ref *transcript-ref
         :style {:min-height "10rem" :max-height "45vh"}}
        (if (and (empty? segments) (string/blank? partial-text))
          [:span.opacity-50 (t :mobile.voice-note/transcript-placeholder)]
          [:p.text-base.whitespace-pre-wrap
           (voice-note/transcript session)
           (when-not (string/blank? partial-text)
             [:span.opacity-50 (str (when (seq segments) " ") partial-text)])])]

       [:div.text-sm.text-center
        {:class (if (= status :error) "text-destructive" "opacity-70")
         :aria-live "polite"}
        (status-text session)]

       [:div.flex.items-center.justify-between
        (shui/button
         {:variant :ghost
          :disabled (= status :saving)
          :on-click #(discard! session)}
         (t :mobile.voice-note/discard))
        (mic-button session)
        (shui/button
         {:disabled (= status :saving)
          :on-click #(<done!)}
         (t :mobile.voice-note/done))]])))

(defn show!
  "Opens the recorder sheet and starts a voice note, or brings back the one
  that's open."
  []
  (state/pub-event! [:mobile/clear-edit])
  (voice-note/<start-session!)
  (shui/popup-show! nil
                    (fn [] (recorder))
                    {:id :ls-voice-note
                     :default-height 560}))
