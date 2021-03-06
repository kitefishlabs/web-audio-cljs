(ns web-audio-cljs.audio
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [clojure.string :as string]
            [web-audio-cljs.utils :refer [l]]
            ))

;; Browser BS dance.
(defn set-prop-if-undefined! [prop obj options]
  (when-not (aget obj prop)
    (let [opts (map #(aget obj %) options)
          prop-to-use (first (filter #(not (nil? %)) opts))]
      (aset obj prop prop-to-use))))
(set-prop-if-undefined! "AudioContext" js/window ["AudioContext" "webkitAudioContext" "mozAudioContext"])
(set-prop-if-undefined! "getUserMedia" js/navigator ["webkitGetUserMedia" "mozGetUserMedia"])
(set-prop-if-undefined! "cancelAnimationFrame" js/window
                        ["webkitCancelAnimationFrame" "mozCancelAnimationFrame"])
(set-prop-if-undefined! "requestAnimationFrame" js/window
                        ["webkitRequestAnimationFrame" "mozRequestAnimationFrame"])

(defn clear-canvas! [canvas-context canvas-width canvas-height bg-color]
  (if (nil? bg-color)
    (.clearRect canvas-context 0 0 canvas-width canvas-height)
    (do
      (set! (.-fillStyle canvas-context) bg-color)
      (.fillRect canvas-context 0 0 canvas-width canvas-height)
      (set! (.-fillStyle canvas-context) "#F6D565")
      (set! (.-lineCap canvas-context) "round"))))

(defn draw-line-on-canvas!
  [canvas-context canvas-height i spacing num-bars bar-width magnitude]
  (set! (.-fillStyle canvas-context)
        (str "hsl(" (string/join "," [(.round js/Math (/ (* i 360) num-bars)) "100%" "50%"]) ")"))
  (.fillRect canvas-context (* i spacing) canvas-height bar-width (- magnitude)))

(defn get-time-domain-data [analyser-node num-bars]
  (let [Uint8Array (.-Uint8Array js/window)
        freq-bin-count (.-frequencyBinCount analyser-node)]
    {:freq-byte-data (Uint8Array. freq-bin-count)
     :multiplier (/ (.-frequencyBinCount analyser-node) num-bars)}))

;;  page 52 of web-audio book
;;  get teh getByteFrequenceData and just take the max value of the data.
(defn draw-bars!
  [canvas-context canvas-width canvas-height spacing num-bars multiplier freq-byte-data bar-width]
  (clear-canvas! canvas-context canvas-width canvas-height "#000000")
  (doseq [i (range num-bars)]
    (let [offset (.floor js/Math (* i multiplier))
          magnitude (/ (reduce #(+ (aget freq-byte-data (+ offset %2)) %1) (range multiplier))
                       multiplier)]
      (draw-line-on-canvas! canvas-context canvas-height i spacing num-bars bar-width magnitude))))

(defn max-of-array [array-of-nums]
  (.apply js/Math.max nil array-of-nums))
(defn min-of-array [array-of-nums]
  (.apply js/Math.min nil array-of-nums))

(defn draw-circle! [canvas-el freq-byte-data n]
  (let [canvas canvas-el
        canvas-context (.getContext canvas "2d")
        canvas-width (.-width canvas)
        canvas-height (.-height canvas)
        max-val (max-of-array freq-byte-data)
        r (* (/ canvas-width 2) (/ max-val 256))
        center-x (/ canvas-width 2)
        center-y center-x]
    ;(when (< n 100)
      ;(aset js/window "freqData" freq-byte-data)
      ;(.log js/console "freq-byte-data: " freq-byte-data))
    (clear-canvas! canvas-context canvas-width canvas-height nil)
    (.beginPath canvas-context)
    (.arc canvas-context center-x center-y r 0 (* 2 (.-PI js/Math)) false)
    (aset canvas-context "fillStyle" "red")
    (.fill canvas-context)))

(defn chart-view [{:keys [analyser-node] :as data} owner]
  (reify
    om/IDisplayName (display-name [_] "chart")

    om/IInitState
    (init-state [_]
        {:canvas nil
         :recording-canvas nil
         :canvas-context nil
         :canvas-width nil
         :canvas-height nil
         :spacing 3
         :bar-width 1
         :n 0
         :num-bars nil
         :freq-byte-data nil
         :multiplier nil})

    om/IDidMount
    (did-mount [_]
      (let [{:keys [spacing]} (om/get-state owner)
            canvas (.getElementById js/document "display")
            recording-canvas (.getElementById js/document "recording")
            canvas-context (.getContext canvas "2d")
            canvas-width (.-width canvas)
            canvas-height (.-height canvas)
            num-bars (.round js/Math (/ canvas-width spacing))
            {:keys [freq-byte-data multiplier]} (get-time-domain-data analyser-node num-bars)]
        (om/update-state! owner #(assoc %
                                        :canvas canvas
                                        :canvas-context canvas-context
                                        :recording-canvas recording-canvas
                                        :canvas-width canvas-width
                                        :canvas-height canvas-height
                                        :num-bars num-bars
                                        :freq-byte-data freq-byte-data
                                        :multiplier multiplier))))

    om/IDidUpdate
    (did-update [_ prev-props prev-state]
      (let [{:keys [recording-canvas canvas-context canvas-width canvas-height num-bars spacing bar-width n]} (om/get-state owner)
            {:keys [freq-byte-data multiplier]} (get-time-domain-data analyser-node num-bars)]
        (.getByteFrequencyData analyser-node freq-byte-data)
        (when canvas-context
          (draw-circle! recording-canvas freq-byte-data n)
          (draw-bars! canvas-context canvas-width canvas-height spacing
                      num-bars multiplier freq-byte-data bar-width))
        (om/update-state! owner #(assoc % :freq-byte-data freq-byte-data :multiplier multiplier :n (inc n)))))

    om/IRender
    (render [_] nil)))
