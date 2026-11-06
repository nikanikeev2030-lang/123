package ru.arenahelper

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Server grants live, in-memory leases only. No editable activation flag on disk. */
object DeviceLicense {
    private const val ALIAS = "arena.license.device.v1"
    @Volatile private var validUntil = 0L
    @Volatile private var refreshAfter = 0L
    private val flags = Base64.NO_WRAP

    @JvmStatic fun isValid(): Boolean = validUntil > SystemClock.elapsedRealtime()
    @JvmStatic fun needsRefresh(): Boolean = refreshAfter <= SystemClock.elapsedRealtime()
    fun savedKey(context: Context): String = context.getSharedPreferences("license",0).getString("key","") ?: ""

    @Synchronized private fun keys(): KeyStore {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return store
    }

    internal fun proof(key: String): JSONObject {
        val store=keys()
        val publicBytes=store.getCertificate(ALIAS).publicKey.encoded
        val nonce=UUID.randomUUID().toString().replace("-","")
        val stamp=System.currentTimeMillis()/1000
        val body="arena-license-v1\n${hash(key.toByteArray())}\n${hash(publicBytes)}\n$stamp\n$nonce"
        val signature=Signature.getInstance("SHA256withECDSA").run {
            initSign(store.getKey(ALIAS,null) as PrivateKey);update(body.toByteArray());sign()
        }
        return JSONObject().put("license",key).put("publicKey",Base64.encodeToString(publicBytes,flags))
            .put("timestamp",stamp).put("nonce",nonce).put("signature",Base64.encodeToString(signature,flags))
    }

    @JvmStatic @Synchronized fun refresh(context: Context): String = activate(context,savedKey(context))

    @Synchronized fun activate(context: Context, suppliedKey: String): String =
        contact(context,suppliedKey,BuildConfig.LICENSE_URL)

    // Internal endpoint argument is used by instrumentation transport tests, never by the UI.
    @Synchronized internal fun contact(context: Context, suppliedKey: String, endpoint: String): String {
        val key=suppliedKey.trim().uppercase(java.util.Locale.ROOT)
        if(endpoint.isBlank()) return "Сервер лицензий ещё не настроен. Обратитесь к продавцу."
        if(!Regex("AH-[A-F0-9]{48}").matches(key)) return "Введите код лицензии, полученный у продавца."
        var connection: HttpURLConnection?=null
        return try {
            val url=URL(endpoint)
            require(url.protocol=="https" || (BuildConfig.DEBUG && url.protocol=="http" && url.host=="127.0.0.1"))
            val request=proof(key)
            val began=SystemClock.elapsedRealtime()
            connection=(url.openConnection() as HttpURLConnection).apply {
                requestMethod="POST";connectTimeout=10000;readTimeout=10000;doOutput=true
                instanceFollowRedirects=false
                setRequestProperty("Content-Type","application/json")
                setRequestProperty("Cache-Control","no-store")
            }
            val payload=request.toString().toByteArray()
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status=connection.responseCode
            val stream=if(status==200) connection.inputStream else connection.errorStream
            val bytes=stream?.use { java.io.ByteArrayOutputStream().apply {
                val buffer=ByteArray(1024)
                while(size()<=4096) {val n=it.read(buffer,0,minOf(buffer.size,4097-size()));if(n<0)break;write(buffer,0,n)}
            }.toByteArray() } ?: byteArrayOf()
            require(bytes.size<=4096)
            val response=JSONObject(String(bytes,Charsets.UTF_8))
            if(status!=200) {
                if(status in listOf(400,403,409)) {validUntil=0;refreshAfter=0}
                return when(response.optString("error")) {
                    "OTHER_DEVICE" -> "Лицензия уже привязана к другому телефону. Для переноса обратитесь к продавцу."
                    "LICENSE_INVALID" -> "Лицензия не найдена или отозвана. Обратитесь к продавцу."
                    "CLOCK" -> "Включите автоматические дату и время на телефоне."
                    else -> "Сервер не подтвердил лицензию. Повторите попытку."
                }
            }
            require(response.getString("nonce")==request.getString("nonce"))
            require(response.getString("device")==hash(Base64.decode(request.getString("publicKey"),flags)))
            val seconds=response.getInt("leaseSeconds")
            require(seconds in 60..900)
            // Start at request dispatch: network latency cannot extend the server's lease.
            validUntil=began+seconds*1000L
            refreshAfter=began+minOf(300,seconds/2)*1000L
            context.getSharedPreferences("license",0).edit().putString("key",key).apply()
            "Лицензия активна • один телефон"
        } catch(_: Exception) {
            "Не удалось проверить лицензию. Проверьте интернет и повторите попытку."
        } finally {connection?.disconnect()}
    }

    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
