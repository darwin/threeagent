(ns threeagent.impl.scene
  (:require [threeagent.impl.virtual-scene :as vscene]
            [threeagent.impl.util :refer [$ $! log]]
            [threeagent.impl.threejs :as threejs]
            [threeagent.impl.component :refer [render-component]]
            [cljs.core :refer [exists?]]))

(defn- create-object [node-data]
  (let [comp-config (:component-config node-data)
        obj (render-component (:component-key node-data) comp-config)]
    (threejs/set-position! obj (:position node-data))
    (threejs/set-rotation! obj (:rotation node-data))
    (threejs/set-scale! obj (:scale node-data))
    obj))

(defn- set-node-object [context node node-data obj]
  ($! node "threejs" obj)
  (when (= :camera (:component-key node-data))
    ($! context "camera" obj)))

(defn- add-node [context parent-object node]
  (try
    (let [node-data ($ node "data")
          comp-config (:component-config node-data)
          obj (create-object node-data)]
      (set-node-object context node node-data obj)
      (.add parent-object obj)
      (.for-each-child node (partial add-node context obj))
      (when-let [callback (:on-added ($ node "meta"))]
        (callback obj))
      obj)
    (catch :default e
      (log "Failed to add node")
      (log e)
      (println node))))

(defn- remove-node! [node]
  (let [obj ($ node "threejs")
        parent-obj ($ ($ node "parent") "threejs")]
    (when-let [callback (:on-removed ($ node "meta"))]
      (callback obj))
    (.remove parent-obj obj)
    (.for-each-child node remove-node!)))

(defn- init-scene [context virtual-scene scene-root]
  (add-node context scene-root ($ virtual-scene "root")))

(defn diff-data [o n]
  (let [this (when (or (not= (:component-config o) (:component-config n))
                       (not= (:component-key o) (:component-key n)))
               [o n])
        position (when (not= (:position o) (:position n)) (:position n))
        rotation (when (not= (:rotation o) (:rotation n)) (:rotation n))
        scale (when (not= (:scale o) (:scale n)) (:scale n))]
    {:this this
     :scale scale
     :position position
     :rotation rotation}))

(defn- update-node! [context node old-data new-data]
  (let [diff (diff-data old-data new-data)
        old-obj ($ node "threejs")
        metadata ($ node "meta")
        this (:this diff)]
    (if this
      ;; Fully reconstruct scene object
      (try
        (let [[o n] this
              parent-obj ($ old-obj "parent")
              children ($ old-obj "children")
              new-obj (create-object new-data)]
          (when-let [callback (:on-removed metadata)]
            (callback old-obj))
          (set-node-object context node new-data new-obj)
          (.remove parent-obj old-obj)
          (.add parent-obj new-obj)
          (doseq [child children]
            (.add new-obj child))
          (when-let [callback (:on-added metadata)]
            (callback new-obj)))
        (catch :default ex
          (log "Failed to update node due to error")
          (log ex)
          (println node)
          (println new-data)
          (println old-data)
          (log node)))
      ;; Update transformations
      (do
        (when (:position diff) (threejs/set-position! old-obj (:position diff)))
        (when (:rotation diff) (threejs/set-rotation! old-obj (:rotation diff)))
        (when (:scale diff) (threejs/set-scale! old-obj (:scale diff)))))))

(defn- apply-change! [context [node action old new]]
  (cond
    (= :add action)
    (add-node context ($ ($ node "parent") "threejs") node)

    (= :remove action)
    (remove-node! node)

    (= :update action)
    (update-node! context node old new)))

(defn- apply-virtual-scene-changes! [context changelog]
  (doseq [change changelog]
    (apply-change! context change)))

(defn- animate [context]
  (let [stats ($ context "stats")
        clock ($ context "clock")
        virtual-scene ($ context "virtual-scene")
        renderer ($ context "renderer")
        composer ($ context "composer")
        camera ($ context "camera")
        scene-root ($ context "scene-root")
        before-render-cb ($ context "before-render-cb")]
    ;(log camera)
    (when stats
      (.begin stats))
    (let [delta-time (.getDelta clock)
          changelog (array)]
      ;; Invoke callbacks
      (when before-render-cb (before-render-cb delta-time))
      ;; Render virtual scene
      (vscene/render! virtual-scene changelog)
      ;; Apply virtual scene changes to ThreeJs scene
      (apply-virtual-scene-changes! context changelog)
      ;; Render ThreeJS Scene
      (if composer
        (.render composer delta-time)
        (.render renderer scene-root camera)))
    (when stats
      (.end stats))))

(defn- get-canvas [dom-root]
  (if (= "canvas" (clojure.string/lower-case (.-tagName dom-root)))
    dom-root
    (let [c (.createElement js/document "canvas")]
      (.appendChild dom-root c))))

(defn- create-camera [width height ortho?]
  (if ortho?
    (js/THREE.OrthographicCamera. (/ width -2.0)
                                  (/ width 2.0)
                                  (/ height 2.0)
                                  (/ height -2.0)
                                  0.1
                                  1000)
    (js/THREE.PerspectiveCamera. 75 (/ width height) 0.1 1000)))
         

(defn- create-context [root-fn dom-root on-before-render-cb ortho-camera? renderer-opts]
  (let [canvas (get-canvas dom-root)
        width (.-offsetWidth canvas)
        height (.-offsetHeight canvas)
        virtual-scene (vscene/create root-fn)
        renderer (new js/THREE.WebGLRenderer (clj->js (merge renderer-opts
                                                             {:canvas canvas})))
        camera (create-camera width height ortho-camera?)
        scene-root (new js/THREE.Scene)
        composer (when (exists? js/POSTPROCESSING)
                   (new js/POSTPROCESSING.EffectComposer renderer))
        render-pass (when (exists? js/POSTPROCESSING)
                      (new js/POSTPROCESSING.RenderPass scene-root camera))]
      (.setSize renderer width height)
      (when composer
        ($! render-pass "renderToScreen" true)
        (.addPass composer render-pass))
      (let [context (clj->js {:scene-root scene-root
                              :canvas canvas
                              :camera camera
                              :before-render-cb on-before-render-cb
                              :stats (when (exists? js/Stats)
                                       (js/Stats.))
                              :clock (js/THREE.Clock.)
                              :renderer renderer
                              :composer composer
                              :virtual-scene virtual-scene})]
        (init-scene context virtual-scene scene-root)
        (when (.-stats context)
          (.showPanel (.-stats context) 1)
          (.appendChild js/document.body (.-dom (.-stats context))))
        ($! context "animate-fn" #(animate context))
        context)))

(defn- remove-all-children! [vscene-root]
  (.for-each-child vscene-root remove-node!))

(defn reset-scene! [scene root-fn {:keys [on-before-render]}]
  (let [root ($ scene "scene-root")
        virtual-scene ($ scene "virtual-scene")
        new-virtual-scene (vscene/create root-fn)]
    (remove-all-children! ($ virtual-scene "root"))
    (vscene/destroy! virtual-scene)
    (init-scene scene new-virtual-scene root)
    ($! scene "virtual-scene" new-virtual-scene)
    ($! scene "before-render-cb" on-before-render)
    scene))

(defn render [root-fn dom-root {:keys [on-before-render ortho-camera?
                                       renderer-opts]}]
  (let [context (create-context root-fn dom-root on-before-render ortho-camera? renderer-opts)
        renderer ($ context "renderer")]
    (.setAnimationLoop renderer ($ context "animate-fn"))
    context))
