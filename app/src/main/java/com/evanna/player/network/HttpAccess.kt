package com.evanna.player.network

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class NetworkPolicy(baseUrl:String,val allowLanHttp:Boolean=false,private val testLoopback:Boolean=false,
    private val requireLan:Boolean=false, private val pinnedAddress:InetAddress?=null,
    private val requirePublic:Boolean=false) {
    private val permitsHttp=allowLanHttp && !baseUrl.toHttpUrl().isHttps
    val origin=baseUrl.toHttpUrl().also {validate(it)}
    fun validate(url:HttpUrl) {
        require(url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() && url.fragment==null){"INVALID_SOURCE_ADDRESS"}
        require(url.isHttps || permitsHttp && !requirePublic){"HTTPS_REQUIRED"}
        // OkHttp bypasses custom DNS for IP literals.
        if(':' in url.host || url.host.all {it.isDigit() || it=='.'})
            validateAddresses(listOf(InetAddress.getByName(url.host)))
    }
    fun sameOrigin(url:HttpUrl)=url.scheme==origin.scheme && url.host==origin.host && url.port==origin.port
    fun resolve(host:String):List<InetAddress> = (pinnedAddress?.let {
        require(host==origin.host){"SOURCE_ADDRESS_NOT_ALLOWED"};listOf(it)
    } ?: Dns.SYSTEM.lookup(host)).also(::validateAddresses)

    private fun validateAddresses(addresses:List<InetAddress>) {
        if(addresses.isEmpty() || addresses.any {it.isAnyLocalAddress || it.isMulticastAddress || it.isLoopbackAddress && !testLoopback})
            throw IOException("SOURCE_ADDRESS_NOT_ALLOWED")
        if(requirePublic && addresses.any { !isPublicWebAddress(it) })
            throw IOException("SOURCE_ADDRESS_NOT_ALLOWED")
        if((permitsHttp || requireLan) && addresses.any {!it.isSiteLocalAddress && !it.isLinkLocalAddress &&
                !(it.address.size==16 && (it.address[0].toInt() and 0xfe)==0xfc) && !(it.isLoopbackAddress && testLoopback)})
            throw IOException("CLEARTEXT_REQUIRES_LOCAL_NETWORK")
    }

    private fun isPublicWebAddress(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 255
        val second = bytes[1].toInt() and 255
        return if (bytes.size == 4) {
            first in 1..223 && !(first == 100 && second in 64..127) && !(first == 198 && second in 18..19)
        } else {
            first in 32..63 && !(first == 32 && second == 2)
        }
    }
}

internal class NetResponse(val code:Int,val headers:Headers,val bytes:ByteArray)
internal class StreamResponse(private val response:Response,private val release:()->Unit):java.io.Closeable {
    val code=response.code
    val headers=response.headers
    val input:InputStream=response.body.byteStream()
    override fun close(){try {response.close()}finally {release()}}
}

/** No redirects, cookies, logging or implicit retries; authorization is origin-scoped. */
internal class HttpAccess(private val policy:NetworkPolicy, socketFactory:javax.net.SocketFactory?=null) {
    private val client=baseClient.newBuilder().dns {policy.resolve(it)}.apply {
        if(socketFactory!=null)socketFactory(socketFactory).proxy(java.net.Proxy.NO_PROXY)
    }.build()
    private val liveClient by lazy {client.newBuilder().callTimeout(0,TimeUnit.SECONDS).build()}

    fun fetch(url:String,maxBytes:Int=1024*1024,headers:Map<String,String> = emptyMap(),
        method:String="GET",body:String?=null,contentType:String="application/json",scope:CancelScope=CancelScope()):NetResponse {
        require(maxBytes in 1..8*1024*1024)
        val call=client.newCall(request(url,headers,method,body,contentType))
        val future=CompletableFuture<NetResponse>()
        val unregister=scope.watch {call.cancel();future.completeExceptionally(IOException("SOURCE_CANCELLED"))}
        try {
            scope.checkActive()
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException){future.completeExceptionally(IOException("NETWORK_UNAVAILABLE"))}
                override fun onResponse(call:Call,response:Response) {
                    response.use {
                        try {
                            scope.checkActive()
                            if(response.body.contentLength()>maxBytes)throw IOException("RESPONSE_TOO_LARGE")
                            val bytes=ByteArrayOutputStream(minOf(maxBytes,65536))
                            response.body.byteStream().use {input ->
                                val buffer=ByteArray(8192)
                                while(true) {
                                    scope.checkActive();val count=input.read(buffer)
                                    if(count<0)break
                                    if(bytes.size()+count>maxBytes)throw IOException("RESPONSE_TOO_LARGE")
                                    bytes.write(buffer,0,count)
                                }
                            }
                            future.complete(NetResponse(response.code,response.headers,bytes.toByteArray()))
                        } catch(_:Exception){future.completeExceptionally(IOException("NETWORK_RESPONSE_FAILED"))}
                    }
                }
            })
            return await(future,scope)
        } finally {unregister();call.cancel()}
    }

    fun openStream(url:String,headers:Map<String,String>,scope:CancelScope):StreamResponse {
        val call=liveClient.newCall(request(url,headers,"GET",null,"application/octet-stream"))
        val future=CompletableFuture<StreamResponse>()
        val unregister=scope.watch {call.cancel();future.completeExceptionally(IOException("SOURCE_CANCELLED"))}
        val release={unregister();call.cancel()}
        try {
            scope.checkActive()
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException){future.completeExceptionally(IOException("NETWORK_UNAVAILABLE"))}
                override fun onResponse(call:Call,response:Response) {
                    val stream=StreamResponse(response,release)
                    if(!future.complete(stream))stream.close()
                }
            })
            return await(future,scope)
        } catch(failure:Throwable) {
            if(!future.completeExceptionally(failure))runCatching {future.getNow(null)?.close()}
            release()
            throw failure
        }
    }

    private fun request(url:String,headers:Map<String,String>,method:String,body:String?,contentType:String):Request {
        val address=url.toHttpUrl();policy.validate(address)
        require(policy.sameOrigin(address) || method=="GET" && headers.keys.all {
            it.lowercase() in setOf("range","if-range","accept","accept-encoding","icy-metadata")
        }){"CROSS_ORIGIN_AUTH_NOT_ALLOWED"}
        val builder=Request.Builder().url(address).header("Accept-Encoding","identity")
        headers.forEach {(name,value)->builder.header(name,value)}
        return builder.method(method,body?.toRequestBody(contentType.toMediaType())).build()
    }
    private fun <T> await(future:CompletableFuture<T>,scope:CancelScope):T = try {future.get(12,TimeUnit.SECONDS)}
        catch(_:InterruptedException){Thread.currentThread().interrupt();throw IOException("SOURCE_CANCELLED")}
        catch(_:Exception){throw IOException(if(scope.cancelled)"SOURCE_CANCELLED" else "NETWORK_REQUEST_FAILED")}
    companion object {
        private val baseClient=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
            .proxy(java.net.Proxy.NO_PROXY)
            .writeTimeout(5,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).followRedirects(false)
            .followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
