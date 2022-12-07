import android.util.Log
import com.google.gson.JsonParseException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RestClient internal constructor() {
    enum class ExpectedResponse {
        jwt, json, ignore, file, plaintext
    }

    var client: OkHttpClient
    var jsonMediaType = "application/json; charset=utf-8".toMediaType()
    var textPlainType = "text/plain; charset=utf-8".toMediaType()

    init { //Make the default constructor package-private to enforce Singleton
        client = OkHttpClient.Builder().build()
    }

    fun post(link: String, status: String) {
        Log.d(TAG, "post() called with: link = [$link], status = [$status]")
        val requestBody = RequestBody.create(textPlainType, status)
        val runnable = Runnable { makeCall<Any?>(link, requestBody, ExpectedResponse.ignore, null, null, Response.Code.Ok) }
        val thread = Thread(runnable)
        thread.start()
        try {
            thread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    operator fun get(link: String?, callback: Callback?, extraHeaders: Map<String, String>?) {
        get(link, callback, ExpectedResponse.json, extraHeaders)
    }

    @JvmOverloads
    operator fun get(link: String?, callback: Callback?, expectedResponse: ExpectedResponse = ExpectedResponse.json, extraHeaders: Map<String, String>? = null) {
        val runnable = Runnable {
            val response =
                makeCall(link, null, expectedResponse, "", extraHeaders, Response.Code.Ok)
            callback?.onComplete(response)
        }
        val thread = Thread(runnable)
        thread.start()
        try {
            thread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun getItems(callback: Callback) {
        val server = "<url>"
        val url = server + "<path>"
        val runnable = Runnable { callback.onComplete(makeCall(url, null, ExpectedResponse.json, "", null, Response.Code.Ok)) }
        val thread = Thread(runnable)
        thread.start()
        try {
            thread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun <T> makeCall(
        url: String?,
        requestBody: RequestBody?,
        expectedResponse: ExpectedResponse,
        type: T,
        extraHeaders: Map<String, String>?,
        vararg expectedCodes: Response.Code
    ): Response {
        var requestBuilder = Request.Builder().url(url ?: "")
        if (extraHeaders != null) {
            for (key in extraHeaders.keys) {
                val value = extraHeaders[key]
                if(value!=null) requestBuilder.addHeader(key, value)
            }
        }
        if (requestBody != null) {
            requestBuilder = requestBuilder.post(requestBody)
        }
        val request: Request = requestBuilder.build()
        return makeRequestAndParseResponse(request, expectedResponse, type, *expectedCodes)
    }

    fun <T> makeRequestAndParseResponse(request: Request?, expectedResponse: ExpectedResponse, type: T, vararg expectedCodes: Response.Code): Response {
        var response: okhttp3.Response? = null
        var responseHolder: Response? = null
        return try {
            try {
                response = client.newCall(request!!).execute()
            } catch (exception: NoRouteToHostException) {
                responseHolder = Response()
                responseHolder.code = Response.Code.Connectivity
                return responseHolder
            } catch (exception: UnknownHostException) {
                responseHolder = Response()
                responseHolder.code = Response.Code.Connectivity
                return responseHolder
            } catch (exception: SocketTimeoutException) {
                responseHolder = Response()
                responseHolder.code = Response.Code.Connectivity
                return responseHolder
            } catch (ioException: IOException) {
                responseHolder = Response()
                responseHolder.code = Response.Code.Error
                return responseHolder
            }
            for (expectedCode in expectedCodes) {
                if (response.code == expectedCode.value) {
                    return when (response.code) {
                        200 -> parse200(response, expectedResponse, type)
                        400 -> parse400<Any>(response)
                        401 -> parse401<Any>(response)
                        403 -> parse403<Any>(response)
                        404 -> parse404<Any>(response)
                        else -> {
                            responseHolder = Response()
                            responseHolder.code = Response.Code.Error
                            responseHolder
                        }
                    }
                }
            }
            responseHolder = Response()
            responseHolder.code = Response.Code.Error
            responseHolder
        } finally {
            if (response != null && response.body != null) {
                response.body!!.close()
            }
        }
    }

    fun <T> parse200(response: okhttp3.Response, expectedResponse: ExpectedResponse, type: T): Response {
        val responseHolder = Response()
        responseHolder.code = Response.Code.Ok
        if (expectedResponse == ExpectedResponse.json) {
            var json: String? = null
            try {
                json = response.body.toString()
                responseHolder.response = json
            } catch (throwable: JsonParseException) {
                responseHolder.code = Response.Code.Error
                return responseHolder
            } catch (throwable: IOException) {
                responseHolder.code = Response.Code.Error
                return responseHolder
            } catch (throwable: AssertionError) {
                responseHolder.code = Response.Code.Error
                return responseHolder
            } catch (throwable: NullPointerException) {
                responseHolder.code = Response.Code.Error
                return responseHolder
            }
        } else if (expectedResponse == ExpectedResponse.file) {
            try {
                val inputStream = response.body!!.byteStream()
                val buffer = ByteArrayOutputStream()
                var numRead: Int
                val data = ByteArray(1024)
                while (inputStream.read(data, 0, data.size).also { numRead = it } != -1) {
                    buffer.write(data, 0, numRead)
                }
                buffer.flush()
                responseHolder.fileBytes = buffer.toByteArray()
            } catch (exception: IOException) {
                responseHolder.code = Response.Code.Error
                return responseHolder
            } catch (exception: NullPointerException) {
                responseHolder.code = Response.Code.Error
                return responseHolder
            }
        } else if (expectedResponse == ExpectedResponse.plaintext) {
            try {
                responseHolder.response = response.body?.string()
            } catch (ioException: IOException) {
                responseHolder.code = Response.Code.Error
                return responseHolder
            }
        } else {
            responseHolder.code = Response.Code.Error
            return responseHolder
        }
        return responseHolder
    }

    fun <T> parse400(response: okhttp3.Response?): Response {
        val responseHolder = Response()
        responseHolder.code = Response.Code.BadRequest
        return responseHolder
    }

    fun <T> parse401(response: okhttp3.Response?): Response {
        val responseHolder = Response()
        responseHolder.code = Response.Code.Unauthorized
        return responseHolder
    }

    fun <T> parse403(response: okhttp3.Response?): Response {
        val responseHolder = Response()
        responseHolder.code = Response.Code.Forbidden
        return responseHolder
    }

    fun <T> parse404(response: okhttp3.Response?): Response {
        val responseHolder = Response()
        responseHolder.code = Response.Code.NotFound
        return responseHolder
    }

    companion object {
        val instance by lazy {
            RestClient()
        }

        private const val TAG = "RestClient"
    }
}
