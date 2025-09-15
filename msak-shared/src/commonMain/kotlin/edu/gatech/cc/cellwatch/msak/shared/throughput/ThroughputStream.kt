package edu.gatech.cc.cellwatch.msak.shared.throughput
//
//import com.google.gson.Gson
//import com.google.gson.JsonSyntaxException
//import edu.gatech.cc.cellwatch.msak.Log
//import edu.gatech.cc.cellwatch.msak.THROUGHPUT_AVG_MEASUREMENT_INTERVAL_MILLIS
//import edu.gatech.cc.cellwatch.msak.THROUGHPUT_MAX_MEASUREMENT_INTERVAL_MILLIS
//import edu.gatech.cc.cellwatch.msak.THROUGHPUT_MAX_SCALED_MESSAGE_SIZE
//import edu.gatech.cc.cellwatch.msak.THROUGHPUT_MESSAGE_SCALING_FRACTION
//import edu.gatech.cc.cellwatch.msak.THROUGHPUT_MIN_MEASUREMENT_INTERVAL_MILLIS
//import edu.gatech.cc.cellwatch.msak.THROUGHPUT_MIN_MESSAGE_SIZE
//import edu.gatech.cc.cellwatch.msak.THROUGHPUT_WS_PROTO
//import kotlinx.coroutines.channels.Channel
//import kotlinx.coroutines.channels.ReceiveChannel
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.runBlocking
//import kotlinx.datetime.Clock
//import kotlinx.datetime.Instant
//import okhttp3.OkHttpClient
//import okhttp3.Request
//import okhttp3.Response
//import okhttp3.WebSocket
//import okhttp3.WebSocketListener
//import okio.ByteString
//import okio.ByteString.Companion.toByteString
//import java.util.concurrent.Semaphore
//import java.util.concurrent.TimeUnit
//import java.util.concurrent.atomic.AtomicLong
//import kotlin.concurrent.thread
//import kotlin.random.Random
//
///**
// * A single TCP stream used to measure throughput.
// *
// * @param num The number of the stream within the throughput test.
// * @param url The URL used to initiate the stream.
// * @param direction The direction of the throughput test.
// * @param minMessageSize The initial size of data messages sent during an upload test.
// * @param maxMessageSize The maximum size of data messages sent during an upload test.
// * @param minMessageSize The threshold for increasing the size of data messages sent during an
// *                       upload test.
// * @param queueFullDelayMillis How many milliseconds to wait before sending more data when the
// *                             WebSocket's queue grows large during an upload test.
// * @param avgMeasurementIntervalMillis The average time between measurement sampling.
// * @param maxMeasurementIntervalMillis The maximum time between measurement sampling.
// * @param minMeasurementIntervalMillis The minimum time between measurement sampling.
// * @param userAgent The value of the User-Agent header to send when initiating the stream.
// */
//class ThroughputStream(
//    private val num: Int,
//    private val url: String,
//    private val direction: ThroughputDirection,
//    private val minMessageSize: Int = THROUGHPUT_MIN_MESSAGE_SIZE,
//    private val maxMessageSize: Int = THROUGHPUT_MAX_SCALED_MESSAGE_SIZE,
//    private val messageScalingFraction: Int = THROUGHPUT_MESSAGE_SCALING_FRACTION,
//    private val queueFullDelayMillis: Long = 1,
//    avgMeasurementIntervalMillis: Long = THROUGHPUT_AVG_MEASUREMENT_INTERVAL_MILLIS,
//    maxMeasurementIntervalMillis: Long = THROUGHPUT_MAX_MEASUREMENT_INTERVAL_MILLIS,
//    minMeasurementIntervalMillis: Long = THROUGHPUT_MIN_MEASUREMENT_INTERVAL_MILLIS,
//    private val userAgent: String? = null,
//): WebSocketListener() {
//    private val TAG = "${this::class.simpleName} $num"
//    private val connectTimeoutMillis = 5000L
//    private val readTimeoutMillis = 5000L
//    private val writeTimeoutMillis = 5000L
//    private val wsCodeNormalClosure = 1000
//    private val wsCodeGoingAway = 1001
//    private val wsCodeInternalError = 1011
//
//    private val socketFactory = CountableSocketFactory()
//    private var webSocket: WebSocket? = null
//    private var socket: CountableSocket? = null
//    private val startStopSem = Semaphore(1)
//    private val _updatesChan = Channel<ThroughputUpdate>(32)
//    private val _updates = ArrayList<ThroughputUpdate>()
//    private var startNetBytesSent: Long? = null
//    private var startNetBytesReceived: Long? = null
//    private var endNetBytesSent: Long? = null
//    private var endNetBytesReceived: Long? = null
//    private val measurementTicker = MemorylessTicker(
//        avgMeasurementIntervalMillis,
//        maxMeasurementIntervalMillis,
//        minMeasurementIntervalMillis,
//    )
//
//    /**
//     * A channel on which to receive updates as the throughput test progresses. Will be closed when
//     * the stream is complete.
//     */
//    val updatesChan: ReceiveChannel<ThroughputUpdate> = _updatesChan
//
//    /**
//     * All updates received during on the stream so far.
//     */
//    val updates: List<ThroughputUpdate> = _updates
//
//    /**
//     * The start time of the stream.
//     */
//    var startTime: Instant? = null; private set
//
//    /**
//     * The end time of the stream. To wait for the stream to end, use updatesChan.
//     */
//    var endTime: Instant? = null; private set
//
//    /**
//     * Whether the stream has been started.
//     */
//    val started; get() = webSocket != null
//
//    /**
//     * Whether the stream has ended. To wait for the stream to end, use updatesChan.
//     */
//    val ended; get() = endTime != null
//
//    /**
//     * The error that caused the throughput stream to end, if applicable. Will not be set until the
//     * stream is complete and updatesChan is closed.
//     */
//    var error: Throwable? = null; private set
//
//    /**
//     * The number of application-layer bytes sent on the stream so far.
//     */
//    val appBytesSent = AtomicLong(0)
//
//    /**
//     * The number of application-layer bytes received on the stream so far.
//     */
//    val appBytesReceived = AtomicLong(0)
//
//    /**
//     * The number of transport-layer bytes sent on the stream so far.
//     */
//    val netBytesSent: Long?
//        get() {
//            val startBytes = startNetBytesSent
//            val endBytes = endNetBytesSent ?: socket?.outBytes
//
//            if (startBytes == null || endBytes == null) {
//                return null
//            }
//
//            return endBytes - startBytes
//        }
//
//    /**
//     * The number of transport-layer bytes received on the stream so far.
//     */
//    val netBytesReceived: Long?
//        get() {
//            val startBytes = startNetBytesReceived
//            val endBytes = endNetBytesReceived ?: socket?.inBytes
//
//            if (startBytes == null || endBytes == null) {
//                return null
//            }
//
//            return endBytes - startBytes
//        }
//
//    /**
//     * Begin the throughput stream. Monitor its updates and wait for it to end with updatesChan.
//     */
//    fun start() {
//        startStopSem.acquire()
//
//        try {
//            if (started) {
//                throw Exception("already started")
//            }
//
//            // Use a new client to prevent streams from sharing TCP connections and to allow using a
//            // custom socket factory to get access to the underlying TCP socket.
//            val client = OkHttpClient.Builder()
//                .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
//                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
//                .writeTimeout(writeTimeoutMillis, TimeUnit.MILLISECONDS)
//                .socketFactory(socketFactory)
//                .build()
//
//            val builder = Request.Builder()
//                .url(url)
//                .header("Sec-WebSocket-Protocol", THROUGHPUT_WS_PROTO)
//
//            if (userAgent != null) {
//                builder.header("User-Agent", userAgent)
//            }
//
//            val request = builder.build()
//
//            // Record a fallback start time. This will be overwritten in onOpen, but we need to have a
//            // start time to mark the stream as started in case the websocket never opens.
//            startTime = Clock.System.now()
//
//            webSocket = client.newWebSocket(request, this)
//        } finally {
//            startStopSem.release()
//        }
//    }
//
//    /**
//     * End the throughput stream. The stream will end on its own when it completes, but this method
//     * can be used to abort it early.
//     */
//    fun stop() {
//        startStopSem.acquire()
//
//        try {
//            if (!started) {
//                throw NotStartedException()
//            }
//
//            finish()
//            webSocket?.close(wsCodeNormalClosure, "stream stopped")
//        } finally {
//            startStopSem.release()
//        }
//    }
//
//    private fun finish(err: Throwable? = null) {
//        if (ended) {
//            return
//        }
//
//        measurementTicker.stop()
//        endTime = Clock.System.now()
//        endNetBytesSent = socket?.outBytes
//        endNetBytesReceived = socket?.inBytes
//        error = err
//        _updatesChan.close()
//    }
//
//    private fun send(webSocket: WebSocket, text: String): Boolean {
//        val sent = webSocket.send(text)
//        if (sent) {
//            appBytesSent.addAndGet(text.toByteArray().size.toLong())
//        }
//        return sent
//    }
//
//    private fun send(webSocket: WebSocket, bytes: ByteString): Boolean {
//        val sent = webSocket.send(bytes)
//        if (sent) {
//            appBytesSent.addAndGet(bytes.size.toLong())
//        }
//        return sent
//    }
//
//    private fun makeMeasurement(): ThroughputMeasurement {
//        val start = startTime ?: throw Exception("can't make measurement before starting")
//        val end = endTime ?: Clock.System.now()
//        val appCounts = ByteCounters(appBytesSent.get(), appBytesReceived.get())
//        val netSent = netBytesSent
//        val netReceived = netBytesReceived
//        val netCounts = if (netSent != null && netReceived != null) {
//            ByteCounters(netSent, netReceived)
//        } else {
//            null
//        }
//
//        return ThroughputMeasurement(
//            netCounts,
//            appCounts,
//            (end - start).inWholeMicroseconds,
//        )
//    }
//
//    private fun sendMeasurement(webSocket: WebSocket) {
//        val measurement = makeMeasurement()
//        val time = Clock.System.now()
//
//        Log.v(TAG, "sending measurement: $measurement")
//        if (send(webSocket, Gson().toJson(measurement))) {
//            sendUpdate(ThroughputUpdate(false, num, time, measurement))
//        } else {
//            Log.d(TAG, "unable to send measurement")
//        }
//    }
//
//    private fun sendUpdate(update: ThroughputUpdate) {
//        val result = _updatesChan.trySend(update)
//        if (!result.isSuccess) {
//            Log.d(TAG, "failed to send throughput update on channel: $result")
//        }
//
//        if (!result.isClosed) {
//            _updates.add(update)
//        }
//    }
//
//    private fun uploadData(webSocket: WebSocket) {
//        thread {
//            try {
//                var size = minMessageSize
//                var message = Random.nextBytes(size).toByteString()
//                while (send(webSocket, message)) {
//                    Log.v(TAG, "sent $size byte message")
//
//                    while (webSocket.queueSize() > 8 * size) {
//                        runBlocking { delay(queueFullDelayMillis) }
//                    }
//
//                    if (size < maxMessageSize && size < appBytesSent.get().toDouble() / messageScalingFraction.toDouble()) {
//                        size = size shl 1
//                        message = Random.nextBytes(size).toByteString()
//                        Log.d(TAG, "scaled message size to $size bytes")
//                    }
//                }
//            } catch (e: Throwable) {
//                Log.e(TAG, "unexpected error uploading data", e)
//                finish(UploadDataException())
//                webSocket.close(wsCodeInternalError, null)
//            }
//        }
//    }
//
//    override fun onOpen(webSocket: WebSocket, response: Response) {
//        super.onOpen(webSocket, response)
//        Log.v(TAG, "WebSocket open")
//
//        if (ended) {
//            Log.d(TAG, "WebSocket opened after stream stopped")
//            webSocket.close(wsCodeNormalClosure, "stream stopped")
//            return
//        }
//
//        socket = socketFactory.sockets.last()
//        startNetBytesSent = socket?.outBytes ?: 0
//        startNetBytesReceived = socket?.inBytes ?: 0
//        startTime = Clock.System.now()
//        measurementTicker.start { sendMeasurement(webSocket) }
//        if (direction == ThroughputDirection.UPLOAD) {
//            uploadData(webSocket)
//        }
//    }
//
//    override fun onMessage(webSocket: WebSocket, text: String) {
//        super.onMessage(webSocket, text)
//        Log.v(TAG, "got text message: $text")
//        if (ended) {
//            return
//        }
//
//        val time = Clock.System.now()
//        appBytesReceived.addAndGet(text.toByteArray().size.toLong())
//
//        val measurement = try {
//            Gson().fromJson(text, ThroughputMeasurement::class.java)
//        } catch (e: JsonSyntaxException) {
//            Log.w(TAG, "text message deserialization failed", e)
//            return
//        }
//
//        sendUpdate(ThroughputUpdate(true, num, time, measurement))
//    }
//
//    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
//        super.onMessage(webSocket, bytes)
//        Log.v(TAG, "got binary message of size ${bytes.size}")
//        if (ended) {
//            return
//        }
//
//        appBytesReceived.addAndGet(bytes.size.toLong())
//    }
//
//    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
//        super.onClosing(webSocket, code, reason)
//        Log.d(TAG, "websocket closing: $code $reason")
//        finish(if (isUnexpectedClose(code)) UnexpectedCloseException(code, reason) else null)
//        webSocket.close(wsCodeNormalClosure, null)
//    }
//
//    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
//        super.onClosed(webSocket, code, reason)
//        Log.d(TAG, "websocket closed: $code $reason")
//        finish(if (isUnexpectedClose(code)) UnexpectedCloseException(code, reason) else null)
//    }
//
//    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
//        super.onFailure(webSocket, t, response)
//        Log.d(TAG, "websocket failure: $response", t)
//        finish(FailureException())
//    }
//
//    private fun isUnexpectedClose(code: Int): Boolean {
//        return code != wsCodeNormalClosure && code != wsCodeGoingAway
//    }
//
//    class NotStartedException: Exception("not started")
//    class UploadDataException: Exception("error uploading data")
//    class UnexpectedCloseException(
//        code: Int,
//        reason: String?,
//    ): Exception("websocket closed with unexpected code: $code $reason")
//    class FailureException : Exception("websocket failure")
//}
