(ns ^:figwheel-always web-audio-cljs.core
    (:require[om.core :as om :include-macros true]
              [om.dom :as dom :include-macros true]
              [web-audio-cljs.audio :as audio]
              [web-audio-cljs.utils :refer [l]]))

(enable-console-print!)

(defonce audio-context (js/window.AudioContext.))
(defonce app-state (atom {:text "Hello world!"
                          :analyser-node nil
                          :audio-recorder nil
                          :is-recording false
                          :recorded-buffers []
                          :audio-context audio-context}))

(defn draw-buffer! [width height canvas-context data]
  (let [step (.ceil js/Math (/ (.-length data) width))
        amp (/ height 2)]
    (aset canvas-context "fillStyle" "silver")
    (.clearRect canvas-context 0 0 width height)
    (.log js/console "amplitude" amp)
    (.log js/console "data length " (.-length data))
    (.log js/console "step " step)
    (.log js/console "width " width)
    (doseq [i (range width)]
      (doseq [j (range step)]
        (let [datum (aget data (+ (* i step) j))]
          (.fillRect canvas-context i amp 1 (- (.max js/Math 1 (* datum amp)))))))))

(defn play-sound [context sound-data]
  (let [audio-tag (.getElementById js/document "play-sound")]
    ;source (.createMediaElementSource context audio-tag)]
    (aset audio-tag "src" (.createObjectURL js/window.URL sound-data))
    ;(.connect source (.-destination context))
    ))

(defn save-recording! [app-state audio-recorder audio-context]
  (.stop audio-recorder)
  (.getBuffers audio-recorder
               (fn [buffers]
                 (om/transact! app-state :recorded-buffers #(conj % (aget buffers 0)))
                 (.clear audio-recorder))))

(defn recorder-view [{:keys [audio-recorder is-recording audio-context] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "recorder-view")
    om/IRender
    (render [_]
      (dom/div nil
               (dom/button #js {:className "toggle-recording"
                                :onClick (fn [e]
                                           (if is-recording
                                             (do
                                               (save-recording! data audio-recorder audio-context)
                                               (om/transact! data :is-recording not))
                                             (do
                                               (.record audio-recorder)
                                               (om/transact! data :is-recording not))))}
                           (if is-recording "Stop" "Record"))))))

(defn buffer-view [recorded-buffer owner]
  (reify
    om/IDisplayName (display-name [_] "buffer-view")

    om/IInitState
    (init-state [_] {:canvas nil
                     :canvas-context nil
                     :canvas-width 400
                     :canvas-height 100})

    om/IDidMount
    (did-mount [_]
      (let [{:keys [canvas-width canvas-height]} (om/get-state owner)
            canvas (om/get-node owner "the-canvas")
            canvas-context (.getContext canvas "2d")]
        (draw-buffer! canvas-width canvas-height canvas-context recorded-buffer)))

    om/IRenderState
    (render-state [_ {:keys [canvas-width canvas-height]}]
      (dom/div nil
        (dom/canvas #js {:width canvas-width :height canvas-height :ref "the-canvas"} "no canvas")))))

(defn buffers-list-view [{:keys [recorded-buffers] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "buffers-list-view")
    om/IRender
    (render [_]
      (apply dom/div {:className "buffers-list"}
             (om/build-all buffer-view (:recorded-buffers data))))))

(defn mount-om-root []
  (om/root
    (fn [data owner]
      (reify
        om/IRender
        (render [_]
          (dom/div nil
                   (om/build recorder-view data)
                   (om/build buffers-list-view data)
                   (om/build audio/chart-view data)))))
    app-state
    {:target (. js/document (getElementById "app"))}))

(defn got-stream [stream]
  (let [audio-context (:audio-context @app-state)
        input-point (.createGain audio-context)
        audio-input (.createMediaStreamSource audio-context stream)]
    (.connect audio-input input-point)
    (let [analyser-node (.createAnalyser audio-context)]
      (set! (.-fftSize analyser-node) 2048)
      (.connect input-point analyser-node)
      (let [zero-gain (.createGain audio-context)]
        (set! (-> zero-gain .-gain .-value) 0.0)
        (.connect input-point zero-gain)
        (.connect zero-gain  (.-destination audio-context))
        (swap! app-state assoc :audio-recorder (js/Recorder. input-point))
        (swap! app-state assoc :analyser-node analyser-node)
        (mount-om-root)))))

(let [audio-constraints (clj->js { "audio" { "mandatory" { "googEchoCancellation" "false"
                                                           "googAutoGainControl"  "false"
                                                           "googNoiseSuppression" "false"
                                                           "googHighpassFilter"   "false" }
                                             "optional" [] }})]
  (.getUserMedia js/navigator audio-constraints
                 got-stream
                 #(.log js/console "ERROR getting user media")))
