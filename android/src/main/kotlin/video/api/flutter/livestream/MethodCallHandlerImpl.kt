package video.api.flutter.livestream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.widget.Toast
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import io.github.thibaultbee.streampack.internal.gl.Texture2DProgram
import video.api.flutter.livestream.utils.addTrailingSlashIfNeeded
import video.api.flutter.livestream.utils.toAudioConfig
import video.api.flutter.livestream.utils.toVideoConfig

class MethodCallHandlerImpl(
    private val context: Context,
    messenger: BinaryMessenger,
    private val permissionsManager: PermissionsManager,
    private val textureRegistry: TextureRegistry
) : MethodChannel.MethodCallHandler {
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL_NAME)
    private var eventSink: EventChannel.EventSink? = null

    private var flutterView: FlutterLiveStreamView? = null

    fun startListening() {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink?.endOfStream()
                eventSink = null
            }
        })
    }

    fun stopListening() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "create" -> {
                try {
                    flutterView?.dispose()
                    flutterView = FlutterLiveStreamView(
                        context,
                        textureRegistry,
                        permissionsManager,
                        { sendConnected() },
                        { sendDisconnected() },
                        { sendConnectionFailed(it) },
                        { sendError(it) },
                        { sendVideoSizeChanged(it) }
                    )
                    result.success(mapOf("textureId" to flutterView!!.textureId))
                } catch (e: Exception) {
                    result.error("failed_to_create_live_stream", e.message, null)
                }
            }

            "dispose" -> {
                flutterView?.dispose()
                flutterView = null
            }

            "setVideoConfig" -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val videoConfig = (call.arguments as Map<String, Any>).toVideoConfig()
                    flutterView!!.setVideoConfig(
                        videoConfig,
                        { result.success(null) },
                        {
                            result.error(
                                "failed_to_set_video_config",
                                it.message,
                                null
                            )
                        })
                } catch (e: Exception) {
                    result.error("failed_to_set_video_config", e.message, null)
                }
            }

            "setAudioConfig" -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val audioConfig = (call.arguments as Map<String, Any>).toAudioConfig()
                    flutterView!!.setAudioConfig(
                        audioConfig,
                        { result.success(null) },
                        {
                            result.error(
                                "failed_to_set_audio_config",
                                it.message,
                                null
                            )
                        })
                } catch (e: Exception) {
                    result.error("failed_to_set_audio_config", e.message, null)
                }
            }

            "startPreview" -> {
                try {
                    flutterView!!.startPreview(
                        { result.success(null) },
                        {
                            result.error(
                                "failed_to_start_preview",
                                it.message,
                                null
                            )
                        })
                } catch (e: Exception) {
                    result.error("failed_to_start_preview", e.message, null)
                }
            }

            "stopPreview" -> {
                flutterView?.stopPreview()
                result.success(null)
            }

            "startStreaming" -> {
                val streamKey = call.argument<String>("streamKey")
                val url = call.argument<String>("url")
                when {
                    streamKey == null -> result.error(
                        "missing_stream_key", "Stream key is missing", null
                    )

                    streamKey.isEmpty() -> result.error(
                        "empty_stream_key", "Stream key is empty", null
                    )

                    url == null -> result.error(
                        "missing_rtmp_url",
                        "RTMP URL is missing",
                        null
                    )

                    url.isEmpty() -> result.error("empty_rtmp_url", "RTMP URL is empty", null)

                    else ->
                        try {
                            flutterView!!.startStream(url.addTrailingSlashIfNeeded() + streamKey)
                            result.success(null)
                        } catch (e: Exception) {
                            result.error("failed_to_start_stream", e.message, null)
                        }
                }
            }

            "stopStreaming" -> {
                flutterView?.stopStream()
                result.success(null)
            }

            "getIsStreaming" -> result.success(mapOf("isStreaming" to flutterView!!.isStreaming))
            "getCameraPosition" -> {
                try {
                    result.success(mapOf("position" to flutterView!!.cameraPosition))
                } catch (e: Exception) {
                    result.error("failed_to_get_camera_position", e.message, null)
                }
            }

            "setCameraPosition" -> {
                val cameraPosition = try {
                    ((call.arguments as Map<*, *>)["position"] as String)
                } catch (e: Exception) {
                    result.error("invalid_parameter", "Invalid camera position", e)
                    return
                }
                try {
                    flutterView!!.setCameraPosition(cameraPosition,
                        { result.success(null) },
                        {
                            result.error(
                                "failed_to_set_camera_position",
                                it.message,
                                null
                            )
                        })
                } catch (e: Exception) {
                    result.error("failed_to_set_camera_position", e.message, null)
                }
            }

            "getIsMuted" -> {
                try {
                    result.success(mapOf("isMuted" to flutterView!!.isMuted))
                } catch (e: Exception) {
                    result.error("failed_to_get_is_muted", e.message, null)
                }
            }

            "setIsMuted" -> {
                val isMuted = try {
                    ((call.arguments as Map<*, *>)["isMuted"] as Boolean)
                } catch (e: Exception) {
                    result.error("invalid_parameter", "Invalid isMuted", e)
                    return
                }
                try {
                    flutterView!!.isMuted = isMuted
                    result.success(null)
                } catch (e: Exception) {
                    result.error("failed_to_set_is_muted", e.message, null)
                }
            }

            "getVideoSize" -> {
                try {
                    val videoSize = flutterView!!.videoConfig.resolution
                    result.success(
                        mapOf(
                            "width" to videoSize.width.toDouble(),
                            "height" to videoSize.height.toDouble()
                        )
                    )
                } catch (e: Exception) {
                    result.error("failed_to_get_video_size", e.message, null)
                }
            }

            "setScore" -> {
                val text1 = call.argument<String?>("text1") ?: ""
                val text2 = call.argument<String?>("text2") ?: ""
                val text3 = call.argument<String?>("text3") ?: ""
                val text4 = call.argument<String?>("text4") ?: ""
                val score1 = call.argument<String?>("score1") ?: ""
                val turn1 = call.argument<String?>("turn1") ?: ""
                val score2 = call.argument<String?>("score2") ?: ""
                val turn2 = call.argument<String?>("turn2") ?: ""
                val link1 = call.argument<String?>("link1") ?: ""
                val link2 = call.argument<String?>("link2") ?: ""
                val link3 = call.argument<String?>("link3") ?: ""
                val matchScore1 = call.argument<String?>("matchScore1") ?: ""
                val matchScore2 = call.argument<String?>("matchScore2") ?: ""
                val point1 = call.argument<String?>("point1") ?: null
                val point2 = call.argument<String?>("point2") ?: null
                val isTennis = call.argument<Boolean?>("isTennis") ?: false
                val tbScore1 = call.argument<String?>("tbScore1") ?: null
                val tbScore2 = call.argument<String?>("tbScore2") ?: null
                Texture2DProgram.TEXT1 = text1
                Texture2DProgram.TEXT2 = text2
                Texture2DProgram.TEXT3 = text3
                Texture2DProgram.TEXT4 = text4
                Texture2DProgram.SCORE1 = score1
                Texture2DProgram.TURN1 = turn1
                Texture2DProgram.SCORE2 = score2
                Texture2DProgram.TURN2 = turn2
                Texture2DProgram.LINK1 = link1
                Texture2DProgram.LINK2 = link2
                Texture2DProgram.LINK3 = link3
                Texture2DProgram.MATCH_SCORE1 = matchScore1
                Texture2DProgram.MATCH_SCORE2 = matchScore2
                Texture2DProgram.POINT1 = point1
                Texture2DProgram.POINT2 = point2
                Texture2DProgram.IS_TENNIS = isTennis
                Texture2DProgram.TB_SCORE1 = tbScore1
                Texture2DProgram.TB_SCORE2 = tbScore2
            }

            else -> result.notImplemented()
        }
    }

    private fun sendEvent(type: String) {
        Handler(Looper.getMainLooper()).post {
            eventSink?.success(mapOf("type" to type))
        }
    }

    private fun sendConnected() {
        sendEvent("connected")
    }

    private fun sendDisconnected() {
        sendEvent("disconnected")
    }

    private fun sendConnectionFailed(message: String) {
        Handler(Looper.getMainLooper()).post {
            eventSink?.success(mapOf("type" to "connectionFailed", "message" to message))
        }
    }

    private fun sendError(error: Exception) {
        Handler(Looper.getMainLooper()).post {
            eventSink?.error(error::class.java.name, error.message, error)
        }
    }

    private fun sendVideoSizeChanged(resolution: Size) {
        Handler(Looper.getMainLooper()).post {
            eventSink?.success(
                mapOf(
                    "type" to "videoSizeChanged",
                    "width" to resolution.width.toDouble(),
                    "height" to resolution.height.toDouble() // Dart size fields are in double
                )
            )
        }
    }

    companion object {
        private const val METHOD_CHANNEL_NAME = "video.api.livestream/controller"
        private const val EVENT_CHANNEL_NAME = "video.api.livestream/events"
    }
}
