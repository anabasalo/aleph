(ns ^:no-doc aleph.http.http2
  "HTTP/2 functionality"
  (:require
    [aleph.http.common :as common]
    [aleph.http.file :as file]
    [aleph.netty :as netty]
    [clj-commons.byte-streams :as bs]
    [clj-commons.primitive-math :as p]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [manifold.deferred :as d]
    [manifold.stream :as s])
  (:import
    (aleph.http AlephChannelInitializer)
    (aleph.http.file HttpFile)
    (clojure.lang IPending)
    (io.netty.buffer ByteBuf)
    (io.netty.channel
      Channel
      ChannelHandler
      ChannelOutboundInvoker
      ChannelPipeline
      FileRegion)
    (io.netty.handler.codec.http
      HttpResponseStatus
      QueryStringDecoder)
    (io.netty.handler.codec.http2
      DefaultHttp2DataFrame
      DefaultHttp2GoAwayFrame
      DefaultHttp2Headers
      DefaultHttp2HeadersFrame
      Http2DataChunkedInput
      Http2DataFrame
      Http2Error
      Http2Exception
      Http2Exception$ShutdownHint
      Http2FrameCodec
      Http2FrameCodecBuilder
      Http2FrameLogger
      Http2FrameStream
      Http2GoAwayFrame
      Http2Headers
      Http2HeadersFrame
      Http2LifecycleManager
      Http2MultiplexHandler
      Http2MultiplexHandler$Http2MultiplexHandlerStreamChannel
      Http2ResetFrame
      Http2Settings
      Http2StreamChannel)
    (io.netty.handler.logging LoggingHandler)
    (io.netty.handler.ssl SslHandler)
    (io.netty.handler.stream
      ChunkedFile
      ChunkedInput
      ChunkedNioFile
      ChunkedWriteHandler)
    (io.netty.util
      AsciiString
      ReferenceCounted)
    (io.netty.util.internal
      EmptyArrays
      StringUtil)
    (java.io
      Closeable
      File
      RandomAccessFile)
    (java.nio ByteBuffer)
    (java.nio.channels FileChannel)
    (java.nio.file
      OpenOption
      Path
      StandardOpenOption)
    (java.util.concurrent
      ConcurrentHashMap
      RejectedExecutionException)
    (java.util.concurrent.atomic AtomicBoolean)))

(set! *warn-on-reflection* true)

(def ^:private byte-array-class (Class/forName "[B"))

(def ^:const max-allowed-frame-size 16777215) ; 2^24 - 1, see RFC 9113, SETTINGS_MAX_FRAME_SIZE
(def ^:dynamic *default-chunk-size* 16384) ; same as default SETTINGS_MAX_FRAME_SIZE

;; TODO: optimize. Either non-concurrent HashMap, regex, or with a fn
;; See https://httpwg.org/specs/rfc9113.html#ConnectionSpecific
;;(def invalid-headers #{"connection" "proxy-connection" "keep-alive" "upgrade"})
(def ^:private invalid-headers (set (map #(AsciiString/cached ^String %)
                                         ["connection" "proxy-connection" "keep-alive" "upgrade"])))
(def ^:private ^AsciiString te-header-name (AsciiString/cached "transfer-encoding"))

(def ^:private ^ConcurrentHashMap cached-header-names
  "No point in lower-casing the same strings over and over again."
  (ConcurrentHashMap. 128))


(defn- throw-illegal-arg-ex
  "Logs an error message, then throws an IllegalArgumentException with that error message."
  [^String emsg]
  (let [ex (IllegalArgumentException. emsg)]
    (log/error ex emsg)
    (throw ex)))

(defn conn-ex
  "Creates a connection-level Http2Exception. If a Throwable `cause` is
   passed-in, it will be wrapped in an ex-info.

   Defaults to a HARD_SHUTDOWN hint. This means the connection will be closed
   as soon as the GOAWAY is sent. If you want to finish processing any remaining streams,
   set the hint to GRACEFUL_SHUTDOWN. (NB: that may be impossible.)"
  ([msg]
   (conn-ex msg {}))
  ([msg m]
   (conn-ex msg m Http2Error/PROTOCOL_ERROR))
  ([msg m h2-error]
   (conn-ex msg m h2-error nil))
  ([^String msg m ^Http2Error h2-error ^Throwable cause]
   (conn-ex msg m h2-error cause Http2Exception$ShutdownHint/HARD_SHUTDOWN))
  ([^String msg m ^Http2Error h2-error ^Throwable cause ^Http2Exception$ShutdownHint shutdown-hint]
   (Http2Exception. h2-error msg ^Throwable (ex-info msg m cause))))

(defn stream-ex
  "Creates a StreamException. If a Throwable `cause` is passed-in, it will
   be wrapped in an ex-info."
  ([stream-id msg]
   (stream-ex stream-id msg {}))
  ([stream-id msg m]
   (stream-ex stream-id msg m Http2Error/PROTOCOL_ERROR))
  ([stream-id msg m h2-error]
   (stream-ex stream-id msg m h2-error nil))
  ([stream-id msg m h2-error ^Throwable cause]
   ;; Http2Exception.StreamException constructor isn't public
   (Http2Exception/streamError
     stream-id
     h2-error
     (ex-info msg m cause)
     msg
     EmptyArrays/EMPTY_OBJECTS)))

(defn- go-away
  "Sends a GOAWAY frame with the given error code (see Http2Error enums) to
   shut down the entire connection. Does not automatically close the channel,
   since you may want to keep processing other streams during
   shutdown. By default, closing the conn channel will send a GOAWAY if none
   has been sent yet, so you will only need this fn for fine-grained control.

   The peer MUST not initiate *any* new streams after receiving the GOAWAY,
   however, there may be recent streams started before the GOAWAY is received
   that the peer does not know about. The default behavior is to ignore
   those streams, but you can set `num-extra-streams` to indicate how many of
   them you will process.

   `num-extra-streams` is the number of un-encountered, but in-flight, streams
   that you will process after sending. Set to 0 (default) to ignore *all*
   newly-encountered streams. Set to Integer/MAX_VALUE for a more graceful
   shutdown (all encountered streams will be processed)."
  ([^ChannelOutboundInvoker out ^long h2-error-code]
   (go-away out h2-error-code 0))
  ([^ChannelOutboundInvoker out ^long h2-error-code num-extra-streams]
   (log/trace (str "Sent GOAWAY(" h2-error-code ") on " out))
   (condp instance? out
     Http2MultiplexHandler$Http2MultiplexHandlerStreamChannel
     (go-away (.parent ^Http2MultiplexHandler$Http2MultiplexHandlerStreamChannel out)
              h2-error-code
              num-extra-streams)

     (netty/write-and-flush out
                            (doto (DefaultHttp2GoAwayFrame. h2-error-code)
                                  (.setExtraStreamIds num-extra-streams))))))

(defn ^:no-doc shutdown-frame->h2-exception
  "Converts an Http2ResetFrame or Http2GoAwayFrame into an Http2Exception or
   StreamException, respectively."
  ^Http2Exception
  [evt stream-id]
  ;; Sadly no common error parent class for Http2ResetFrame and Http2GoAwayFrame
  (let [error-code (if (instance? Http2ResetFrame evt)
                     (.errorCode ^Http2ResetFrame evt)
                     (.errorCode ^Http2GoAwayFrame evt))
        h2-error (Http2Error/valueOf error-code)
        msg (if (instance? Http2ResetFrame evt)
              (str "Received RST_STREAM from peer, stream closing. Error code: " error-code ".")
              (str "Received GOAWAY from peer, connection closing. Error code: " error-code ". Stream " stream-id " was not processed by peer."))]
    (if (instance? Http2ResetFrame evt)
      (stream-ex stream-id msg {} h2-error)
      (conn-ex msg {:on-stream-id stream-id} h2-error))))

(defn add-header
  "Add a single header and value. The value can be a string or a collection of
   strings.

   Header names are converted to lower-case AsciiStrings. Values are left as-is,
   but it's up to the user to ensure they are valid. For more info, see
   https://www.rfc-editor.org/rfc/rfc9113.html#section-8.2.1

   Respects HTTP/2 rules. All headers will be made lower-case. Throws on
   invalid connection-related headers. Throws on nil header values. Throws if
   `transfer-encoding` is present, but is not equal to 'trailers'."
  [^Http2Headers h2-headers ^String header-name header-value]
  (log/debug (str "Adding HTTP header: " header-name ": " header-value))

  ;; Also checked by Netty, but we want to avoid work, so we check too
  (if (nil? header-name)
    (throw-illegal-arg-ex "Header name cannot be nil")

    ;; Technically, Ring requires lower-cased headers, but there's no guarantee, and this is
    ;; probably faster, since most users won't be caching.
    (let [header-name (if-some [cached (.get cached-header-names header-name)]
                        cached
                        (let [lower-cased (-> header-name (name) (AsciiString.) (.toLowerCase))]
                          (.put cached-header-names header-name lower-cased)
                          lower-cased))]
      (cond
        ;; Will be checked by Netty
        ;;(nil? header-value)
        ;;(illegal-arg (str "Invalid nil value for header '" header-name "'"))

        (invalid-headers header-name)
        (throw-illegal-arg-ex (str "Forbidden HTTP/2 header: \"" header-name "\""))

        ;; See https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Transfer-Encoding
        (and (.equals te-header-name header-name)
             (not (.equals "trailers" header-value)))
        (throw-illegal-arg-ex "Invalid value for 'transfer-encoding' header. Only 'trailers' is allowed.")

        (sequential? header-value)
        (.add h2-headers ^CharSequence header-name ^Iterable header-value)

        :else
        (.add h2-headers ^CharSequence header-name ^Object header-value)))))


(defn parse-status
  "Parses the HTTP status of a ring response map. Returns an HttpResponseStatus"
  ^HttpResponseStatus
  [status m stream-id]
  (try
    (cond
      (or (instance? Long status)
          (instance? Integer status)
          (instance? Short status))
      (HttpResponseStatus/valueOf status)

      (instance? String status)
      (HttpResponseStatus/parseLine ^String status)

      (instance? AsciiString status)
      (HttpResponseStatus/parseLine ^AsciiString status)

      :else
      (throw (stream-ex m stream-id "Unknown :status class in Ring response map")))

    (catch IllegalArgumentException e
      (throw (stream-ex m stream-id (str "Invalid :status in Ring response map: " (.getMessage e)))))))

(defn ring-map->netty-http2-headers
  "Builds a Netty Http2Headers object from a Ring map.

   Checks headers and pseudo-headers."
  ^DefaultHttp2Headers
  [m stream-id is-request?]
  (let [h2-headers (DefaultHttp2Headers. true)]

    (if is-request?
      (do
        (if-some [request-method (get m :request-method)]
          (.method h2-headers (-> request-method name str/upper-case))
          (throw (stream-ex stream-id "Missing :request-method in Ring request map" m)))

        (if-some [scheme (get m :scheme)]
          (.scheme h2-headers (name scheme))
          (throw (stream-ex stream-id "Missing :scheme in Ring request map" m)))

        (if-some [authority (get m :authority)]
          (.authority h2-headers authority)
          (throw (stream-ex stream-id "Missing :authority in Ring request map" m)))

        (if-some [path (str (get m :uri)
                            (when-let [q (get m :query-string)]
                              (str "?" q)))]
          (.path h2-headers path)
          (throw (stream-ex stream-id "Invalid/missing :uri and/or :query-string in Ring request map" m))))

      (if-let [status (get m :status)]
        (.status h2-headers (.codeAsText ^HttpResponseStatus (parse-status status m stream-id)))
        (.status h2-headers (.codeAsText HttpResponseStatus/OK))

        ;; NB: a missing status should be an error, but for backwards-
        ;; compatibility with Aleph's http1 code, we set it to 200
        #_(throw (stream-ex stream-id "Missing :status in Ring response map" m))))

    ;; Technically, missing :headers is a violation of the Ring SPEC, but kept
    ;; for backwards compatibility
    (when-let [headers (get m :headers)]
      (try
        (run! #(add-header h2-headers (key %) (val %))
              headers)

        (catch Exception e
          (log/error e "Error adding headers to HTTP/2 headers" m)
          (throw e))))

    h2-headers))

(defn netty-http2-headers->map
  "Returns a map of Ring headers from a Netty Http2Headers object.

   Includes pseudo-headers."
  [^Http2Headers headers]
  (loop [iter (.iterator headers)
         result {}]
    (if (.hasNext iter)
      (let [entry (.next iter)]
        (recur iter
               (assoc result
                      (.toString ^CharSequence (key entry))
                      (.toString ^CharSequence (val entry)))))
      result)))

(defn- try-set-content-length!
  "Attempts to set the `content-length` header if not already set.

   Will skip if it's a response and the status code is 1xx or 204. (RFC 9110 §8.6)
   Negative length values are ignored."
  ^Http2Headers
  [^Http2Headers headers ^long length]
  (when-not (.get headers "content-length")
    (if (some? (.status headers))
      ;; TODO: consider switching to netty's HttpResponseStatus and HttpStatusClass for clarity
      (let [code (-> headers (.status) (.toString) (Long/parseLong))]
        (when-not (or (p/== 204 code)
                      (and (p/<= 100 code)
                           (p/<= code 199)))
          (.setLong headers "content-length" length)))
      (.setLong headers "content-length" length))))


(defn send-contiguous-body
  "Write out a msg and a body that is, or can be turned into, a ByteBuf.

   Works with strings, byte arrays, ByteBuffers, ByteBufs, nil, and
   anything supported by byte-streams' `convert`"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers body]
  (log/trace "http2 send-contiguous-body")
  (let [body-bb (netty/to-byte-buf ch body)]
    (try-set-content-length! headers (.readableBytes body-bb))

    (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
    (netty/write-and-flush ch (-> body-bb (DefaultHttp2DataFrame. true) (.stream stream)))))

(defn send-chunked-body
  "Write out a msg and a body that's already chunked as a ChunkedInput"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers ^ChunkedInput body]
  (log/trace "http2 send-chunked-body")
  (let [len (.length body)]
    (when (p/>= len 0)
      (try-set-content-length! headers len)))

  (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
  (netty/write-and-flush ch (-> body (Http2DataChunkedInput. stream))))

(defn send-file-region
  "Write out a msg and a FileRegion body. Uses the fast zero-copy .transferTo()

   WARNING: Netty does not support this with its SslHandler. While there are
   some OSes/NICs that can support SSL and zero-copy, Netty does not. This means
   FileRegions can only be used with non-SSL connections."
  [^Http2StreamChannel ch ^Http2Headers headers ^FileRegion fr]
  (log/trace "http2 send-file-region")
  (let [stream (.stream ch)]
    (try-set-content-length! headers (.count fr))
    (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))
    (netty/write-and-flush ch fr)))

(defn send-http-file
  "Send an HttpFile using ChunkedNioFile"
  [ch stream headers ^HttpFile hf]
  (log/trace "http2 send-http-file")
  (let [file-channel (-> hf
                         ^File (.-fd)
                         (.toPath)
                         (FileChannel/open
                           (into-array OpenOption [StandardOpenOption/READ])))
        chunked-input (ChunkedNioFile. file-channel
                                       (.-offset hf)
                                       (.-length hf)
                                       (p/int (.-chunk-size hf)))]
    (send-chunked-body ch stream headers chunked-input)))

(defn- write-available-sequence-data
  "Writes out available data from anything sequential, and returns any
   unrealized remainder (for lazy sequences)."
  [^Http2StreamChannel ch s]
  (let [buf (netty/allocate ch)
        lazy? (instance? IPending s)]
    (loop [s s]
      (cond
        ;; lazy and no data available yet - write out buf and move on
        (and lazy? (not (realized? s)))
        (do
          (netty/write-and-flush ch buf)
          s)

        ;; If we're out of data, write out the buf, and return nil
        (empty? s)
        (do
          (netty/write-and-flush ch buf)
          nil)

        ;; if data is ready, append it to the buf and recur
        (or (not lazy?) (realized? s))
        (let [x (first s)]
          (netty/append-to-buf! buf x)
          (recur (rest s)))

        :else
        (do
          (netty/write-and-flush ch buf)
          s)))))

(defn- end-of-stream-frame
  "Returns an empty data frame with end-of-stream set to true."
  [^Http2FrameStream stream]
  (-> (DefaultHttp2DataFrame. true)
      (.stream stream)))

(defn send-streaming-body
  "Write out a msg and a body that's streamable"
  [^Http2StreamChannel ch ^Http2FrameStream stream ^Http2Headers headers body chunk-size]
  (log/trace "http2 send-streaming-body")

  ;; write out header frame first
  (netty/write ch (-> headers (DefaultHttp2HeadersFrame.) (.stream stream)))

  (if-let [body' (if (sequential? body)
                   ;; write out all the data we have already, and return the rest
                   (->> body
                        (map common/coerce-element)
                        (write-available-sequence-data ch))
                   (do
                     (netty/flush ch)
                     body))]

    (let [d (d/deferred)
          src (common/body-byte-buf-stream d ch body' chunk-size)
          sink (netty/sink ch
                           false
                           (fn [^ByteBuf buf]
                             (-> buf (DefaultHttp2DataFrame.) (.stream stream))))

          ;; mustn't close over body' if NOT a stream, can hold on to data too long
          ch-close-handler (if (s/stream? body')
                             #(s/close! body')
                             #(s/close! src))]

      (s/connect src sink)

      ;; set up ch close handler
      (-> ch
          netty/channel
          .closeFuture
          netty/wrap-future
          (d/chain' (fn [_] (ch-close-handler))))

      ;; set up sink close handler
      (s/on-closed sink
                   (fn []
                     (when (instance? Closeable body)
                       (.close ^Closeable body))

                     (.execute (-> ch netty/channel .eventLoop)
                               #(d/success! d
                                            (netty/write-and-flush ch (end-of-stream-frame stream))))))
      d)

    ;; otherwise, no data left in body', so just send an empty data frame
    (netty/write-and-flush ch (end-of-stream-frame stream))))

(defn- send-message
  "Given Http2Headers and a body, determines the best way to write the body to the stream channel"
  ([^Http2StreamChannel ch ^Http2Headers headers body]
   (send-message ch headers body *default-chunk-size* file/*default-file-chunk-size*))
  ([^Http2StreamChannel ch ^Http2Headers headers body chunk-size file-chunk-size]
   (log/trace "http2 send-message")

   (let [^Http2FrameStream stream (.stream ch)]
     (try
       (cond
         (or (nil? body)
             (identical? :aleph/omitted body))
         (do
           (log/trace "Body is nil or omitted")
           (let [header-frame (-> headers (DefaultHttp2HeadersFrame. true) (.stream stream))]
             (log/debug header-frame)
             (netty/write-and-flush ch header-frame)))

         (or
           (instance? String body)
           (instance? ByteBuf body)
           (instance? ByteBuffer body)
           (instance? byte-array-class body))
         (send-contiguous-body ch stream headers body)

         (instance? ChunkedInput body)
         (send-chunked-body ch stream headers body)

         (instance? RandomAccessFile body)
         (send-chunked-body ch stream headers
                            (ChunkedFile. ^RandomAccessFile body
                                          ^int file-chunk-size))

         (instance? File body)
         (send-chunked-body ch stream headers
                            (ChunkedNioFile. ^File body ^int file-chunk-size))

         (instance? Path body)
         (send-chunked-body ch stream headers
                            (ChunkedNioFile. ^FileChannel
                                             (FileChannel/open
                                               ^Path body
                                               (into-array OpenOption [StandardOpenOption/READ]))
                                             ^int file-chunk-size))

         (instance? HttpFile body)
         (send-http-file ch stream headers body)

         (instance? FileChannel body)
         (send-chunked-body ch stream headers
                            (ChunkedNioFile. ^FileChannel body ^int file-chunk-size))

         (instance? FileRegion body)
         (if (or (-> ch (.pipeline) (.get ^Class SslHandler))
                 (some-> ch (.parent) (.pipeline) (.get ^Class SslHandler)))
           (let [emsg (str "FileRegion not supported with SSL in Netty")
                 e (stream-ex (.id stream) emsg {:ch ch :headers headers :body body})]
             (log/error e emsg)
             (netty/close ch))
           (send-file-region ch headers body))

         :else
         (try
           (send-streaming-body ch stream headers body chunk-size)
           (catch Throwable e
             (log/error e "Error sending body of type " (StringUtil/simpleClassName body))
             (throw e))))

       (catch Exception e
         (log/error e "Error sending message")
         (throw (stream-ex (.id stream) "Error sending message" {:headers headers :body body} e)))))))

;; NOTE: can't be as vague about whether we're working with a channel or context in HTTP/2 code,
;; because we need access to the .stream method. We have a lot of code in aleph.netty that
;; branches based on the class (channel vs context), but that's not ideal. It's slower, and
;; writing to the channel vs the context means different things, anyway, they're not
;; usually interchangeable.
(defn send-request
  "Converts the Ring request map to Netty Http2Headers, extracts the body,
   and then sends both to Netty to be sent out over the wire."
  [^Http2StreamChannel ch req response]
  (log/trace "http2 send-request fired")

  (try
    (let [body (if (= :trace (:request-method req))
                 ;; RFC #7231 4.3.8. TRACE
                 ;; A client MUST NOT send a message body...
                 (do
                   (when (some? (:body req))
                     (log/warn "Non-nil TRACE request body was removed"))
                   nil)
                 (:body req))
          headers (ring-map->netty-http2-headers req (-> ch (.stream) (.id)) true)
          chunk-size (or (:chunk-size req) *default-chunk-size*)
          file-chunk-size (p/int (or (:chunk-size req) file/*default-file-chunk-size*))]

      ;; Store message and/or original body if requested, for debugging purposes
      (when-let [save-message (get req :aleph/save-request-message)]
        (reset! save-message req))
      (when-let [save-body (get req :aleph/save-request-body)]
        ;; might be different in case we use :multipart
        (reset! save-body body))

      (-> (netty/safe-execute ch
            (send-message ch headers body chunk-size file-chunk-size))
          (d/catch' (fn [e]
                      (log/error e "Error in http2 send-request")
                      (d/error! response e)
                      (netty/close ch)))))

    ;; this will usually happen because of a malformed request
    (catch Exception e
      (log/error e "Error in http2 send-request")
      (d/error! response e)
      (netty/close ch))))

(let [[server-name date-name content-type]
      (map #(AsciiString. ^CharSequence %) ["server" "date" "content-type"])]

  (defn send-response
    "Converts the Ring response map to Netty Http2Headers, extracts the body,
     and then sends both to Netty to be sent out over the wire."
    [^Http2StreamChannel ch error-handler head-request? rsp]
    (log/trace "http2 send-response")

    (try
      (let [body (if head-request?
                   ;; https://www.rfc-editor.org/rfc/rfc9110#section-9.3.2
                   (do
                     (when (some? (:body rsp))
                       (log/warn "Non-nil HEAD response body was removed"))
                     :aleph/omitted)
                   (:body rsp))
            headers (ring-map->netty-http2-headers rsp (-> ch (.stream) (.id)) false)
            chunk-size (or (:chunk-size rsp) *default-chunk-size*)
            file-chunk-size (p/int (or (:chunk-size rsp) file/*default-file-chunk-size*))]

        ;; Add default headers if they're not already present
        (when-not (.contains headers ^CharSequence server-name)
          (.set headers ^CharSequence server-name common/aleph-server-header))

        (when-not (.contains headers ^CharSequence date-name)
          (.set headers ^CharSequence date-name (common/date-header-value (.eventLoop ch))))

        (when (.equals "text/plain" (.get headers ^CharSequence content-type))
          (.set headers ^CharSequence content-type "text/plain; charset=UTF-8"))

        (log/debug "Sending to Netty. Headers:" (pr-str headers) " - Body class:" (class body))

        (-> (netty/safe-execute ch
              (send-message ch headers body chunk-size file-chunk-size))
            (d/catch' (fn [e]
                        (log/error e "Error in http2 send-message")
                        (netty/close ch)))))
      (catch Exception e
        (log/error e "Error in http2 send-response")
        (if error-handler
          ;; try exactly once more with the error-handler's output - remove the error-handler to prevent infinite loops
          (send-response ch nil head-request? (error-handler e))
          (throw e))))))



(comment
  ;; parsing authority probably not needed for server-name/port,
  ;; but may be useful for generating "Host" header???
  (def ^:const ^:private ^int at-int (int (.charValue \@)))
  (def ^:const ^:private ^int colon-int (int (.charValue \:)))
  (let [scheme (keyword (.scheme headers))
        ;; parsing :authority is 10x faster than using URI
        authority (.toString (.authority headers))
        at-index (.indexOf authority at-int)
        colon-index (.indexOf authority colon-int at-index)]

    {:server-name       (.subSequence authority
                                      (p/inc at-index)
                                      (if (p/== colon-index -1)
                                        (.length authority)
                                        colon-index))
     :server-port       (if (p/== colon-index -1)
                          (if (= scheme :https) 443 80)
                          (Integer/parseInt (.subSequence authority
                                                          (p/inc colon-index)
                                                          (.length authority)) 10))})
  )


(defn- netty->ring-request
  "Given Netty Http2Headers and a body stream, returns a Ring request map.


   For advanced users only:
   - :aleph/writable? is an AtomicBoolean indicating whether the stream can
     still be written to. In case of error, RST_STREAM, or GOAWAY, it will be
     set to false.
   - :aleph/h2-exception is an atom wrapping an Exception if an error
     occurred, or nil if not. It will be an Http2Exception for a connection
     error, a Http2Exception.StreamException for a stream error, or a custom
     exception thrown by the user.

   You can monitor these to cease processing early if need be."
  [ch ^Http2Headers headers body writable? h2-exception]
  ;; The :path pseudo-header is not the same as the Ring SPEC, it has to be split.
  (let [qsd (QueryStringDecoder. (-> headers (.path) (.toString)))
        path (.rawPath qsd)
        query-string (.rawQuery qsd)]
    {:request-method        (-> headers (.method) (.toString) (.toLowerCase) keyword)
     :scheme                (-> headers (.scheme) (.toString) keyword)
     :path                  path
     :uri                   path                            ; not really a URI
     :query-string          (if (.isEmpty query-string) nil query-string)
     :server-name           (netty/channel-server-name ch)  ; is this best?
     :server-port           (netty/channel-server-port ch)
     :remote-addr           (netty/channel-remote-address ch)
     :headers               (netty-http2-headers->map headers)
     :body                  body
     ;;:trailers          (d/deferred)

     :protocol              "HTTP/2.0"

     :aleph/writable?       writable?
     :aleph/h2-exception    h2-exception
     :aleph/keep-alive?     true                            ; not applicable to HTTP/2, but here for compatibility
     :aleph/request-arrived (System/nanoTime)}))

(defn- validate-netty-req-headers
  "Netty is not currently checking for missing pseudo-headers, so we do it here."
  [^Http2Headers headers stream-id]
  (if (or (nil? (.method headers))
          (nil? (.scheme headers))
          (nil? (.path headers)))
    (throw (stream-ex
             stream-id
             "Missing mandatory pseudo-header in HTTP/2 request"
             {:headers headers}))
    headers))

(defn client-handle-shutdown-frame
  "Common handling for RST_STREAM and GOAWAY frame events.

   Closes the body stream, sets the response deferred to an appropriate exception
   and if a GOAWAY, sets complete to true."
  [evt stream-id body-stream response-d complete]
  (let [ex (shutdown-frame->h2-exception evt stream-id)
        no-error? (identical? (.error ex) Http2Error/NO_ERROR)
        msg (.getMessage ex)]

    ;; wrap it up
    (d/error! response-d ex)
    (s/close! body-stream)
    (d/success! complete (instance? Http2GoAwayFrame evt)) ; if GOAWAY, the whole conn must be shut down

    (if no-error?
      (log/info msg)
      ; only log as warning, since the real error may be on the peer's side
      (log/warn msg))))

(defn- server-handle-shutdown-frame
  "Common handling for RST_STREAM and GOAWAY frame events.

   Disables further writes, closes the body stream, and stores the exception
   in the h2-exception atom for the user handler."
  [evt stream-id body-stream ^AtomicBoolean writable? h2-exception]

  ;; disable output - in both cases, we shouldn't send any more frames
  (.set writable? false)
  (s/close! body-stream)

  (let [ex (shutdown-frame->h2-exception evt stream-id)
        no-error? (identical? (.error ex) Http2Error/NO_ERROR)
        msg (.getMessage ex)]

    ;; store in case user handler can do something with it
    (reset! h2-exception ex)

    (if no-error?
      (log/info msg)
      ; only log as warning, since the real error may be on the peer's side
      (log/warn msg))))

(defn- handle-user-event-triggered
  [ctx evt stream-go-away-handler reset-stream-handler shutdown-frame-handler]
  (condp instance? evt

    Http2GoAwayFrame
    (do
      (when (fn? stream-go-away-handler)
        (stream-go-away-handler ctx evt))
      (shutdown-frame-handler)
      (.release ^ReferenceCounted evt))

    Http2ResetFrame
    (do
      (when (fn? reset-stream-handler)
        (reset-stream-handler ctx evt))
      (shutdown-frame-handler)
      (.release ^ReferenceCounted evt))

    ;; else
    (.fireUserEventTriggered ctx evt)))

(defn- server-handler
  "Returns a ChannelInboundHandler that processes inbound Netty HTTP2 stream
   frames, converts them, and calls the user handler with them. It then
   converts the handler's Ring response into Netty Http2 objects and sends
   them on the outbound pipeline."
  [^Http2StreamChannel ch
   handler
   raw-stream?
   rejected-handler
   error-handler                                            ; user error handler, returns ring map
   executor
   buffer-capacity
   stream-go-away-handler
   reset-stream-handler]
  (log/trace "server-handler")
  #_(log/debug "server-handler args" {:ch               ch
                                      :handler          handler
                                      :raw-stream?      raw-stream?
                                      :rejected-handler rejected-handler
                                      :error-handler    error-handler
                                      :executor executor
                                    :buffer-capacity buffer-capacity})
  (let [stream-id (-> ch (.stream) (.id))
        buffer-capacity (long buffer-capacity)
        writable? (AtomicBoolean. true)                     ; can we still write out?
        h2-exception (atom nil)                             ; atom, because we want to switch values

        ;; if raw, we're returning a stream of ByteBufs, if not, we return byte arrays
        body-stream
        (if raw-stream?
          (netty/buffered-source ch #(.readableBytes ^ByteBuf %) buffer-capacity)
          (netty/buffered-source ch #(alength ^bytes %) buffer-capacity))

        lifecycle-manager (delay (let [parent-pipeline (-> ch .parent .pipeline)
                                       codec-handler-ctx (.context parent-pipeline ^Class Http2FrameCodec)]
                                   (.handler codec-handler-ctx)))

        ;; Can't place exceptions on body-stream for client, since non-raw streams
        ;; will attempt to convert it to bytes for an InputStream, which will blow up
        ;; elsewhere
        aleph-error-handler
        (fn handle-error [ctx ex]
          (.set writable? false)                            ; disable send-response
          (s/close! body-stream)
          (log/error ex "Exception caught in HTTP/2 stream server handler" {:raw-stream? raw-stream?})

          ;; If stream error, sends a RST_STREAM and closes stream.
          ;; If conn error, sends GOAWAY, and closes conn.
          (.onError ^Http2LifecycleManager @lifecycle-manager
                    ctx
                    false
                    ex))]

    (netty/channel-inbound-handler
      ;;:channel-active
      ;;([_ ctx]
      ;; (netty/ensure-dynamic-classloader)
      ;; (.fireChannelActive ctx))

      :exception-caught
      ([_ ctx ex]
       (log/trace ":exception-caught fired")
       (aleph-error-handler ctx ex))

      :channel-inactive
      ([_ ctx]
       (s/close! body-stream)
       (.fireChannelInactive ctx))

      :channel-read
      ([_ ctx msg]
       (log/trace ":channel-read fired")
       #_(let [parent-pipeline (-> ch .parent .pipeline)
               codec-handler-ctx (.context parent-pipeline ^Class Http2FrameCodec)
               ^Http2FrameCodec codec-handler (.handler codec-handler-ctx)]
           (.resetStream codec-handler
                         codec-handler-ctx
                         (-> ch (.stream) (.id))
                         (.code Http2Error/ENHANCE_YOUR_CALM)
                         (.voidPromise parent-pipeline)))


       (cond
         (instance? Http2HeadersFrame msg)
         ;; TODO: support trailers?
         (let [headers (-> (.headers ^Http2HeadersFrame msg)
                           (validate-netty-req-headers stream-id))
               body (if raw-stream?
                      body-stream
                      (bs/to-input-stream body-stream))
               ring-req (netty->ring-request ch headers body writable? h2-exception)
               is-head? (= :head (:request-method ring-req))]

           (log/debug "Received HTTP/2 request"
                      (pr-str (assoc ring-req :body (class (:body ring-req)))))

           (when (.isEndStream ^Http2HeadersFrame msg)
             (s/close! body-stream))

           (-> (if executor
                 ;; handle request on a separate thread
                 (try
                   (d/future-with executor
                     (log/debug "Dispatching request to user handler"
                                (assoc ring-req :body (class (:body ring-req))))
                     (handler ring-req))
                   (catch RejectedExecutionException e
                     (if rejected-handler
                       (try
                         (rejected-handler ring-req)
                         (catch Throwable e
                           (error-handler e)))
                       {:status  503
                        :headers {"content-type" "text/plain"}
                        :body    "503 Service Unavailable"})))

                 ;; else handle it inline (hope you know what you're doing!)
                 (try
                   (log/warn "Running user handler inline")
                   (handler ring-req)
                   (catch Throwable e
                     (error-handler e))))
               (d/catch' error-handler)
               (d/chain'
                 (fn send-http-response [ring-resp]
                   (log/trace "send-http-response")
                   (log/debug "Response from user handler"
                              (pr-str (assoc ring-resp :body (class (:body ring-resp)))))

                   (if (.get writable?)
                     (send-response ch
                                    error-handler
                                    is-head?
                                    (cond
                                      (map? ring-resp)
                                      ring-resp

                                      (nil? ring-resp)
                                      {:status 204}

                                      :else
                                      (error-handler (common/invalid-value-exception ring-req ring-resp))))
                     (log/debug "Stream is no longer writable"))))))

         (instance? Http2DataFrame msg)
         (let [content (.content ^Http2DataFrame msg)]
           ;; skip empty bytebufs
           (when (p/> (.readableBytes content) 0)
             (netty/put! (.channel ctx)
                         body-stream
                         (if raw-stream?
                           content
                           (netty/buf->array content))))

           (when-not raw-stream?
             (.release ^ReferenceCounted msg))

           (when (.isEndStream ^Http2DataFrame msg)
             (s/close! body-stream)))

         :else
         (do
           (log/debug "Unhandled message in server-handler" msg)
           (.fireChannelRead ctx msg))))

      :channel-read-complete
      ([_ ctx]
       (log/trace ":channel-read-complete fired")
       (netty/flush ctx))

      :user-event-triggered
      ([_ ctx evt]
       (handle-user-event-triggered
         ctx evt stream-go-away-handler reset-stream-handler
         #(server-handle-shutdown-frame evt stream-id body-stream writable? h2-exception))))))

(defn client-handler
  "Given a response deferred and a Http2StreamChannel, returns a
   ChannelInboundHandler that processes inbound Netty Http2 frames, converts
   them into a response, and places it in the deferred"
  [^Http2StreamChannel ch
   response-d
   raw-stream?
   buffer-capacity
   stream-go-away-handler
   reset-stream-handler]
  (let [stream-id (-> ch (.stream) (.id))
        complete (d/deferred) ; realized when this stream is done; true = close conn, false = keep conn open

        ;; if raw, we're returning ByteBufs, if not, we convert to byte[]'s
        body-stream
        (if raw-stream?
          (netty/buffered-source ch #(.readableBytes ^ByteBuf %) buffer-capacity)
          (netty/buffered-source ch #(alength ^bytes %) buffer-capacity))

        lifecycle-manager (delay (let [parent-pipeline (-> ch .parent .pipeline)
                                       codec-handler-ctx (.context parent-pipeline ^Class Http2FrameCodec)]
                                   (.handler codec-handler-ctx)))

        aleph-error-handler
        (fn handle-error [ctx ex]
          (log/error ex
                     "Exception caught in HTTP/2 stream client handler"
                     {:stream-id   stream-id
                      :raw-stream? raw-stream?})
          (d/error! response-d ex)
          (d/success! complete true)
          (s/close! body-stream)

          ;; If stream error, sends a RST_STREAM and closes stream.
          ;; If conn error, sends GOAWAY, and closes conn.
          (.onError ^Http2LifecycleManager @lifecycle-manager
                    ctx
                    false
                    ex))]

    (netty/channel-inbound-handler

      :exception-caught
      ([_ ctx ex]
       (log/trace ":exception-caught fired")
       (aleph-error-handler ctx ex))

      :channel-inactive
      ([_ ctx]
       (s/close! body-stream)
       (d/success! complete true)
       (.fireChannelInactive ctx))

      :channel-read
      ([_ ctx msg]
       (cond
         (instance? Http2HeadersFrame msg)
         (let [headers (.headers ^Http2HeadersFrame msg)
               body (if raw-stream?
                      body-stream
                      (bs/to-input-stream body-stream))
               ring-resp {:status            (->> headers (.status) (.convertToInt netty/char-seq-val-converter)) ; might be an AsciiString
                          :headers           (netty-http2-headers->map headers)
                          :aleph/keep-alive? true           ; not applicable to HTTP/2, but here for compatibility
                          :aleph/complete    complete
                          :body              body}]
           (d/success! response-d ring-resp)
           (when (.isEndStream ^Http2HeadersFrame msg)
             (d/success! complete false)
             (s/close! body-stream)))

         (instance? Http2DataFrame msg)
         (let [content (.content ^Http2DataFrame msg)]
           (if raw-stream?
             (netty/put! (.channel ctx) body-stream content)
             (do
               (netty/put! (.channel ctx) body-stream (netty/buf->array content))
               (.release ^ReferenceCounted msg)))
           (when (.isEndStream ^Http2DataFrame msg)
             (d/success! complete false)
             (s/close! body-stream)))

         :else
         (.fireChannelRead ctx msg)))

      :user-event-triggered
      ([_ ctx evt]
       (handle-user-event-triggered
         ctx evt stream-go-away-handler reset-stream-handler
         #(client-handle-shutdown-frame evt stream-id body-stream response-d complete))))))

(defn setup-stream-pipeline
  "Set up the pipeline for an HTTP/2 stream channel"
  [^ChannelPipeline p handler is-server? proxy-options logger pipeline-transform]
  (log/trace "setup-stream-pipeline called")

  ;; necessary for multipart support in HTTP/2?
  #_(.addLast p
              "stream-frame-to-http-object"
              ^ChannelHandler stream-frame->http-object-codec)
  (.addLast p
            "streamer"
            ^ChannelHandler (ChunkedWriteHandler.))

  (when is-server?
    ;; TODO: add continue handler for server-side
    )

  (.addLast p
            "handler"
            ^ChannelHandler
            handler)

  (when (some? proxy-options)
    (throw (IllegalArgumentException. "Proxying HTTP/2 messages not supported yet")))

  (-> p
      (common/add-non-http-handlers logger pipeline-transform)
      #_(common/add-exception-handler "stream-ex-handler"))

  (log/trace "Added all stream handlers")
  (log/debug "Stream chan pipeline:" p)

  p)

(def ^:private client-inbound-handler
  "For the client, the inbound handler will probably never get used.
   Servers will rarely initiate streams, because PUSH_PROMISE is effectively
   deprecated. Responses to client-initiated streams are set elsewhere
   (even if it's the same handler fn)."
  (netty/channel-inbound-handler
    {:channel-active
     ([_ ctx]
      (log/error "Cannot currently handle peer-initiated streams. (You must override this handler if receiving server-pushed streams.) Closing channel.")
      (netty/close ctx))}))

(defn setup-conn-pipeline
  "Set up pipeline for an HTTP/2 connection channel. Works for both server
   and client."
  [{:keys
    [^ChannelPipeline pipeline
     ^LoggingHandler logger
     idle-timeout
     ^ChannelHandler handler
     is-server?
     raw-stream?
     rejected-handler
     error-handler
     conn-go-away-handler
     stream-go-away-handler
     reset-stream-handler
     executor
     http2-settings
     request-buffer-size
     proxy-options
     pipeline-transform]
    :or
    {idle-timeout        0
     http2-settings      (Http2Settings/defaultSettings)
     pipeline-transform  identity
     request-buffer-size 16384
     error-handler       common/ring-error-response}
    :as opts}]
  (log/trace "setup-conn-pipeline fired")
  (let [
        log-level (some-> logger (.level))
        http2-frame-codec (let [builder (if is-server?
                                          (Http2FrameCodecBuilder/forServer)
                                          (Http2FrameCodecBuilder/forClient))]
                            (when log-level
                              (.frameLogger builder (Http2FrameLogger. log-level)))
                            (-> builder
                                (.initialSettings ^Http2Settings http2-settings)
                                (.validateHeaders true)
                                (.build)))
        stream-chan-initializer (AlephChannelInitializer.
                                  #_netty/ensure-dynamic-classloader
                                  (fn [^Channel ch]
                                    (setup-stream-pipeline (.pipeline ch)
                                                           (if is-server?
                                                             (server-handler ch
                                                                             handler
                                                                             raw-stream?
                                                                             rejected-handler
                                                                             error-handler
                                                                             executor
                                                                             request-buffer-size
                                                                             stream-go-away-handler
                                                                             reset-stream-handler)
                                                             client-inbound-handler)
                                                           is-server?
                                                           proxy-options
                                                           logger
                                                           pipeline-transform)))
        multiplex-handler (Http2MultiplexHandler. stream-chan-initializer)]

    (-> pipeline
        (common/add-idle-handlers idle-timeout)
        (.addLast "http2-frame-codec" http2-frame-codec)
        (.addLast "multiplex" multiplex-handler)
        ;; FIXME: don't add a handler at all if conn-go-away-handler is nil
        (.addLast "conn-go-away-handler"
                  (netty/channel-inbound-handler
                    :channel-read ([_ ctx msg]
                                   (when (and (fn? conn-go-away-handler)
                                              (instance? Http2GoAwayFrame msg))
                                     (conn-go-away-handler ctx msg))
                                   (.fireChannelRead ctx msg))))
        (common/add-exception-handler "conn-ex-handler"))

    (log/debug "Conn chan pipeline:" pipeline)
    pipeline))



(comment
  (do
    (require '[aleph.http.client]
             '[aleph.http.multipart]
             '[clj-commons.byte-streams :as bs])
    (import '(io.netty.channel DefaultFileRegion)
            '(io.netty.handler.stream ChunkedFile ChunkedNioFile)
            '(java.net InetSocketAddress)
            '(java.nio.channels FileChannel)
            '(java.nio.file Files OpenOption Path Paths)
            '(java.nio.file.attribute FileAttribute)))

  ;; NB: postman-echo.com ALWAYS prefers http2, regardless of your order,
  ;; which is ironic, since the Postman app doesn't support sending http2 :P

  ;; basic test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http2]}))

    (def result @(conn {:request-method :get
                        ;;:raw-stream?    true
                        })))

  ;; different body types test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http2]}))

    (let [body-string "Body test"
          fpath (Files/createTempFile "test" ".txt" (into-array FileAttribute []))
          ffile (.toFile fpath)
          _ (spit ffile body-string)
          fchan (FileChannel/open fpath (into-array OpenOption []))
          aleph-1k (repeat 1000 \ℵ)
          aleph-20k (repeat 20000 \ℵ)
          aleph-1k-string (apply str aleph-1k)

          body
          body-string
          #_fpath
          #_ffile
          #_(RandomAccessFile. ffile "r")
          #_fchan
          #_(ChunkedFile. ffile)
          #_(ChunkedNioFile. fchan)
          #_(file/http-file ffile 1 6 4)
          #_(DefaultFileRegion. fchan 0 (.size fchan))
          #_(seq body-string)
          #_[:foo :bar :moop]
          #_aleph-20k
          #_[aleph-1k-string aleph-1k-string]]


      (def result
        @(conn {:request-method :post
                :uri            "/post"
                :headers        {"content-type" "text/plain"}
                :body           body
                :http-versions [:http2]
                ;;:raw-stream?    true
                }))

      (some-> result :body bs/to-string)))

  ;; multipart test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions  [:http2]}))

    (def result
      @(conn {:request-method :post
              :uri            "/post"
              :headers        {"content-type" "text/plain"}
              :multipart      [{:part-name "part1"
                                :content   "content1"
                                :charset   "UTF-8"}
                               {:part-name "part2"
                                :content   "content2"}
                               {:part-name "part3"
                                :content   "content3"
                                :mime-type "application/json"}]}))
    (some-> result :body bs/to-string))

  ;; multiple simultaneous requests test
  (do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http2]}))

    (let [req {:request-method :post
               :uri            "/post"
               :headers        {"content-type" "text/plain"}
               :body           "Body test"
               :raw-stream?    true
               }]
      (def results (map conn (repeat 3 req)))
      @(nth results 2)))

  ;; compression test - don't support automatic decompression yet
  #_(do
    (def conn @(aleph.http.client/http-connection
                 (InetSocketAddress. "postman-echo.com" (int 443))
                 true
                 {:on-closed #(log/debug "http conn closed")
                  :http-versions [:http1]}))

    (def result @(conn {:request-method :get
                        :uri           "/gzip" #_ "/deflate"
                        ;; FWIW, postman-echo.com ignores the accept-encoding header below
                        :headers       {"accept-encoding" "gzip"}
                        :body          "ABC123"}))

    (some-> result :body bs/to-string))


  )
