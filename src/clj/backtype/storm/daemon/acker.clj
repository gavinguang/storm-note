(ns backtype.storm.daemon.acker
  (:import [backtype.storm.task OutputCollector TopologyContext IBolt])
  (:import [backtype.storm.tuple Tuple Fields])
  (:import [backtype.storm.utils RotatingMap MutableObject])
  (:import [java.util List Map])
  (:import [backtype.storm Constants])
  (:use [backtype.storm config util log])
  (:gen-class
   :init init
   :implements [backtype.storm.task.IBolt]
   :constructors {[] []}
   :state state ))

(def ACKER-COMPONENT-ID "__acker")
(def ACKER-INIT-STREAM-ID "__ack_init")
(def ACKER-ACK-STREAM-ID "__ack_ack")
(def ACKER-FAIL-STREAM-ID "__ack_fail")

;acker 对于每个spout-tuple 保存一个ack-val校验值,它的初始值是0,
;然后每发射一个tuple/ack一个tuple,那么tuple的id都要跟这个校验值异或一下,
;并且把得到的值更新为ack-val的新值.假设每个发射出去的tuple都被ack了,那么
;ack-val一定都是0(因为一个数字跟自己异或得到的值都是0)
(defn- update-ack [curr-entry val]
  (let [old (get curr-entry :val 0)]
    (assoc curr-entry :val (bit-xor old val))
    ))

(defn- acker-emit-direct [^OutputCollector collector ^Integer task ^String stream ^List values]
  (.emitDirect collector task stream values)
  )

(defn mk-acker-bolt []
  (let [output-collector (MutableObject.)
        pending (MutableObject.)]
    ;这里是用clojure 实现了java的接口啊,有点神奇~~~ IBolt是一个java接口.
    (reify IBolt
      (^void prepare [this ^Map storm-conf ^TopologyContext context ^OutputCollector collector]
               (.setObject output-collector collector)
               (.setObject pending (RotatingMap. 2))
               )
      (^void execute [this ^Tuple tuple]
             (let [^RotatingMap pending (.getObject pending)
                   stream-id (.getSourceStreamId tuple)]
               (if (= stream-id Constants/SYSTEM_TICK_STREAM_ID)
                 (.rotate pending)
                 (let [id (.getValue tuple 0)
                       ^OutputCollector output-collector (.getObject output-collector)
                       curr (.get pending id)
                       curr (condp = stream-id
                                ACKER-INIT-STREAM-ID (-> curr
                                                         (update-ack (.getValue tuple 1))
                                                         (assoc :spout-task (.getValue tuple 2)))
                                ACKER-ACK-STREAM-ID (update-ack curr (.getValue tuple 1))
                                ACKER-FAIL-STREAM-ID (assoc curr :failed true))]
                   ;把每个tuple-id 得到的校验值存储起来.
                   (.put pending id curr)
                   (when (and curr (:spout-task curr))
                     (cond (= 0 (:val curr))
                           ;当校验值为0,则 执行后续操作,在存储中删除该tuple-id ......
                           (do
                             (.remove pending id)
                             (acker-emit-direct output-collector
                                                (:spout-task curr)
                                                ACKER-ACK-STREAM-ID
                                                [id]
                                                ))
                           (:failed curr)
                           ;当失败了.......
                           (do
                             (.remove pending id)
                             (acker-emit-direct output-collector
                                                (:spout-task curr)
                                                ACKER-FAIL-STREAM-ID
                                                [id]
                                                ))
                           ))
                   (.ack output-collector tuple)
                   ))))
      (^void cleanup [this]
        )
      )))

(defn -init []
  [[] (container)])

(defn -prepare [this conf context collector]
  (let [^IBolt ret (mk-acker-bolt)]
    (container-set! (.state ^backtype.storm.daemon.acker this) ret)
    (.prepare ret conf context collector)
    ))

(defn -execute [this tuple]
  (let [^IBolt delegate (container-get (.state ^backtype.storm.daemon.acker this))]
    (.execute delegate tuple)
    ))

(defn -cleanup [this]
  (let [^IBolt delegate (container-get (.state ^backtype.storm.daemon.acker this))]
    (.cleanup delegate)
    ))
