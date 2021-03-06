(ns
	^{:doc "The clj-xmemcached core"
	  :author "Dennis zhuang<killme2008@gmail.com>"}
  clj-xmemcached.core
  (:import (net.rubyeye.xmemcached MemcachedClient MemcachedClientBuilder XMemcachedClient CASOperation XMemcachedClientBuilder GetsResponse)
		   (net.rubyeye.xmemcached.impl KetamaMemcachedSessionLocator ArrayMemcachedSessionLocator PHPMemcacheSessionLocator)
		   (net.rubyeye.xmemcached.utils AddrUtil)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.util.zip DeflaterOutputStream GZIPInputStream GZIPOutputStream InflaterInputStream)
           (net.rubyeye.xmemcached.transcoders CachedData Transcoder SerializingTranscoder)
		   (net.rubyeye.xmemcached.command BinaryCommandFactory KestrelCommandFactory TextCommandFactory)
		   (java.net InetSocketAddress))
  (:refer-clojure :exclude [get set replace])
  (:require [clojure.data.json :as json]
            [taoensso.nippy.compression :as compression]
            [taoensso.nippy :as nippy])
  (:use
   [clojure.walk :only [walk]]))

(defn- unquote-options [args]
  (walk (fn [item]
		  (cond (and (seq? item) (= `unquote (first item))) (second item)
				(or (seq? item) (symbol? item)) (list 'quote item)
				:else (unquote-options item)))
		identity
		args))

(defn- make-command-factory [protocol]
  (case protocol
    :binary (BinaryCommandFactory.)
    :kestrel (KestrelCommandFactory.)
    (TextCommandFactory.)))

(defn- make-session-locator [hash]
  (case hash
    (:consistent :ketama :consist) (KetamaMemcachedSessionLocator.)
    (:php :phpmemcache) (PHPMemcacheSessionLocator.)
    (ArrayMemcachedSessionLocator.)))

(def ^:dynamic *memcached-client* nil)
(def ^:private global-memcached-client (atom nil))

(defmacro with-client
  "Evalutes body in the context of a thread-bound client to a memcached server."
  [client & body]
  `(binding [*memcached-client* ~client]
     ~@body))

(defn set-client!
  "Set a global  memcached client for all thread contexts,prefer binding a client by `with-client` macro"
  [^MemcachedClient client]
  (reset! global-memcached-client client))

(def no-client-error
  (Exception. (str "Memcached methods must be called within the context of a"
                   " client to Memcached server. See `with-client`.")))

(defn get-memcached-client []
  {:doc "Returns current thread-bound memcached client,if it is not bound,try to get the global client,otherwise throw an exception."
   :tag MemcachedClient}
  (deref (or *memcached-client* @global-memcached-client (throw no-client-error))))

(defonce ^{:private true} compress-threshold* (atom (* 16 1024)))
(defn memcached
  "Create a memcached client with zero or more options(any order):
    :protocol  Protocol to talk with memcached,a keyword in :text,:binary or :kestrel,default is text.
    :hash  Hash algorithm,a keyword in  :consistent, :standard or :php, default is standard hash.
    :pool  Connection pool size,default is 1,it's a recommended value.
    :sanitize-keys  Whether to sanitize keys before operation,default is false.
    :reconnect  Whether to reconnect when connections are disconnected,default is true.
    :transcoder Transcoder to encode/decode data.
    :session-locator memcached connection locator,default is created by :hash algorithm value.
    :heartbeat  Whether to do heartbeating when connections are idle,default is true.
    :timeout  Operation timeout in milliseconds,default is five seconds.
    :compress-threshold Value compression threhold in bytes, default is 16K.
    :name  A name to define a memcached client instance"
  [servers & opts]
  (delay
   (let [{:keys [name protocol hash pool timeout transcoder reconnect
                 sanitize-keys heartbeat merge-factor merge-buffer
                 session-locator  compress-threshold]
          :or {pool 1
               merge-factor 50
               merge-buffer true
               timeout 5000
               compress-threshold  (* 16 1024) ;;default is 16K
               transcoder (SerializingTranscoder.)
               reconnect true
               sanitize-keys false
               heartbeat true}} (apply hash-map (unquote-options opts))]
     (reset! compress-threshold* compress-threshold)
     (let [builder (doto (XMemcachedClientBuilder.  (AddrUtil/getAddresses servers))
                     (.setName name)
                     (.setTranscoder transcoder)
                     (.setSessionLocator (or session-locator (make-session-locator hash)))
                     (.setConnectionPoolSize pool)
                     (.setCommandFactory  (make-command-factory protocol)))
           rt (.build builder)]
       (doto rt
         (.setOpTimeout timeout)
         (.setEnableHealSession reconnect)
         (.setEnableHeartBeat heartbeat)
         (.setMergeFactor merge-factor)
         (.setOptimizeMergeBuffer merge-buffer)
         (.setSanitizeKeys sanitize-keys))))))

;;define store functions:  set,add,replace,append,prepend
(defmacro define-store-fn [meta name]
  (case name
    (append prepend)
    `(defn ~name ~meta [^String key# value#]
       (. (get-memcached-client) ~name key# value#))
    `(defn ~name ~meta
       ([^String key# value#] (. (get-memcached-client) ~name key# 0 value#))
       ([^String key# value# exp#] (. (get-memcached-client) ~name key# exp# value#)))))

(define-store-fn
  {:arglists '([key value] [key value expire])
   :doc "Set an item with key and value."}
  set)

(define-store-fn
  {:arglists '([key value] [key value expire])
   :doc "Add an item with key and value,success only when item is not exists."}
  add)

(define-store-fn
  {:arglists '([key value] [key value expire])
   :doc "Replace an existing item's value by new value"}
  replace )

(define-store-fn
  {:arglists '([key value])
   :doc "Append a string to an existing item's value by key"}
  append)

(define-store-fn
  {:arglists '([key value])
   :doc "Prepend a string to an existing item's value by key"}
  prepend)

(defn touch "Touch a item with new expire time."
  [key expire]
  (.touch (get-memcached-client) key expire))

(defn get "Get items by a key or many keys,when bulk get items,the result is java.util.HashMap"
  ([^String k]
     (.get (get-memcached-client) k))
  ([k1 k2 ]
     (.get (get-memcached-client) ^java.util.Collection (list k1 k2)))
  ([k1 k2 & ks]
     (.get (get-memcached-client) ^java.util.Collection (list* k1 k2 ks))))

(defn gets
  "Gets an item's value by key,return value has a cas value"
  [^String key]
  (when-let [^GetsResponse resp (.gets (get-memcached-client) key)]
    {:cas (.getCas resp)
     :value (.getValue resp)}))

(defn cas
  "Compare and set an item's value by key
  set the new value to:
       (cas-fn current-value)"
  ([^String key cas-fn]
	 (cas key cas-fn Integer/MAX_VALUE))
  ([^String key cas-fn ^Integer max-times]
	 (cas key cas-fn max-times 0))
  ([^String key cas-fn ^Integer max-times ^Integer expire]
	 (.cas (get-memcached-client) key expire (reify CASOperation
                                               (getMaxTries [this] max-times)
                                               (getNewValue [this _ value] (cas-fn  value))))))

;;define incr/decr functions
(defmacro define-incr-decr-fn
  [meta name]
  `(defn ~name ~meta ([^String key# ^Long delta#] (. (get-memcached-client) ~name key# delta#))
     ([^String key# ^Long delta# ^Long init#] (. (get-memcached-client) ~name key# delta# init#))
     ([^String key# ^Long delta# ^Long init# expire#] (. (get-memcached-client) ~name key# delta# init# (.getOpTimeout (get-memcached-client)) expire#))))

(define-incr-decr-fn
  {:arglist '([key delta] [key delta init-value])
   :doc "Increase an item's value by key"}
  incr)

(define-incr-decr-fn
  {:arglist '([key delta] [key delta init-value])
   :doc "Decrease an item's value by key"}
  decr)

(defn delete
  "Delete an item by key [with CAS values that was get in binary protocol]."
  ([key]
     (delete key 0))
  ([key cas]
     (let [^MemcachedClient xmc (get-memcached-client)]
       (.delete xmc ^String key ^long cas ^long (.getOpTimeout xmc)))))

(defn flush-all
  "Flush all values in memcached.WARNNING:this method will remove all items in memcached."
  ([] (flush-all (get-memcached-client)))
  ([cli] (.flushAll ^MemcachedClient cli)))

(defn stats
  "Get statistics info from all memcached servers"
  ([]
     (stats (get-memcached-client)))
  ([^MemcachedClient cli]
     (.getStats cli)))

(defn shutdown
  "Shutdown the memcached client"
  ([]
     (shutdown (get-memcached-client)))
  ([^MemcachedClient cli]
     (.shutdown cli)))

(defonce byte-array-class (Class/forName "[B"))
(definline bytes? [x]
  `(= byte-array-class
      (class ~x)))

(defonce ^:private compress-transcoder (SerializingTranscoder.))

(defprotocol Compressor
  (compress ^bytes [this bs] "Compress byte array.")
  (decompress ^bytes [this bs] "Decompress byte array."))

(deftype GZipCompressor []
  Compressor
  (compress [_ v]
    (let [^ByteArrayOutputStream bos (ByteArrayOutputStream.)]
      (with-open [^ByteArrayOutputStream bos bos
                  gz (GZIPOutputStream. bos)]
        (.write gz ^bytes v))
      (.toByteArray bos)))
  (decompress [_ v]
    (let [^bytes buf (byte-array (* 32 1024))
          ^ByteArrayOutputStream bos (ByteArrayOutputStream.)]
      (with-open [^ByteArrayOutputStream bos bos
                  ^ByteArrayInputStream bis (ByteArrayInputStream. ^bytes v)
                  ^GZIPInputStream gz (GZIPInputStream. bis)]
        (loop []
          (let [r (.read gz buf)]
            (when (> r 0)
              (.write bos buf 0 r)
              (recur)))))
      (.toByteArray bos))))

(defonce ^:dynamic default-compressor (GZipCompressor.))
(defonce ^:private max-size (* 1024 1024))

(defn- ^CachedData do-compress [^CachedData d]
  (let [^bytes data (.getData d)]
    (if (> (alength data) @compress-threshold*)
      (CachedData. (bit-or 4 (.getFlag d)) (compress default-compressor data) max-size -1)
      d)))

(defn- ^CachedData do-decompress [^CachedData d]
  (when (pos? (bit-and (.getFlag d) 4))
    (.setCapacity d max-size)
    (.setData d (decompress default-compressor (.getData d)))
    (.setFlag d (bit-and (.getFlag d) (bit-not 4))))
  d)

(defprotocol MemcachedTranscoder
  (mashall ^CachedData [this obj] "Encode object into a byte array")
  (unmashall [this bs] "Decode a byte array to a object."))

(defmacro def-transcoder [name & body]
  (let [type-name (symbol (.replaceAll ^String (str name) "-" "_"))]
    `(do
       (deftype ~type-name  []
         MemcachedTranscoder
         ~@body)
       (let [t# (new ~type-name)]
         (def ~name (reify Transcoder
                      (encode [this# obj#]
                        (do-compress (mashall t# obj#)))
                      (decode [this# data#]
                        (unmashall t# (do-decompress data#)))
                      (setPrimitiveAsString [this# b#])
                      (setPackZeros [this# b#])
                      (setCompressionThreshold [this# b#])
                      (isPrimitiveAsString [this#] false)
                      (isPackZeros [this#] false)
                      (setCompressionMode [this# m#])))))))

(def-transcoder nippy-transcoder
  (mashall [this obj]
           (cond (string? obj) (CachedData. 0 (.getBytes ^String obj "utf-8") max-size -1)
                 (bytes? obj) (CachedData. 2 (bytes obj) max-size -1)
                 :else
                 (CachedData. 1 (nippy/freeze obj)  max-size -1)))
  (unmashall [this data]
             (let [ ^bytes bs (.getData ^CachedData data)]
               (case (.getFlag ^CachedData data)
                 0 (String. ^bytes bs "utf-8")
                 1 (nippy/thaw bs)
                 2 bs))))

(def-transcoder clj-json-transcoder
  (mashall [this obj]
           (cond (string? obj) (CachedData. 0 (.getBytes ^String obj "utf-8") max-size -1)
                 (bytes? obj) (CachedData. 2 (bytes obj) max-size -1)
                 :else
                 (CachedData. 1 (.getBytes ^String (json/write-str obj) "utf-8") max-size -1)))
  (unmashall [this data]
             (case (.getFlag ^CachedData data)
               0 (String. ^bytes (.getData ^CachedData data) "utf-8")
               1 (json/read-str (String. ^bytes (.getData ^CachedData data) "utf-8") :key-fn keyword)
               2 (.getData ^CachedData data))))

(defmacro try-lock
  "Lightweight distribution lock.
   Try to lock with the global key,if gettting lock successfully,
   then do something,
   else do other things.For example,get the global lock to initial
   in 5 seconds:

    (try-lock \"init-lock\" 5000
        (start-service))"

  ([key expire then]
     `(try-lock ~key ~expire ~then nil))
  ([key expire then else]
     `(if (add ~key true ~expire)
        (try
          ~then
          (finally
            (delete ~key)))
        ~else)))

(defmacro through
  "A macro to get item from cache or cache the value evaluated by load cause.
  For example,you want to get the user from memcached if it is exists in cache
  or load it from database to memcached and return it:

    (through uid 60
        (load-user-from-db uid)) "

  ([key load]
     `(through ~key 0 ~load))
  ([key expire load]
     `(if-let [rt# (get ~key)]
        rt#
        (let [v# ~load]
          (when v#
            (add ~key v# ~expire))
          v#))))
