package ru.arenahelper

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64

class LicenseTest {
    @Test fun deviceProofVerifiesAndCannotBeReusedForAnotherLicense() {
        val proof=DeviceLicense.proof(TEST_KEY)
        val pub=Base64.decode(proof.getString("publicKey"),Base64.NO_WRAP)
        val message="arena-license-v1\n${hash(TEST_KEY.toByteArray())}\n${hash(pub)}\n${proof.getLong("timestamp")}\n${proof.getString("nonce")}"
        val verifier=Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub)))
        verifier.update(message.toByteArray())
        assertTrue(verifier.verify(Base64.decode(proof.getString("signature"),Base64.NO_WRAP)))
        val second=DeviceLicense.proof(TEST_KEY)
        assertEquals(proof.getString("publicKey"),second.getString("publicKey"))
        assertNotEquals(proof.getString("nonce"),second.getString("nonce"))
    }

    @Test fun activationAndRevocationControlAccess() {
        authorize()
        assertTrue(DeviceLicense.isValid())
        exchange(403) { JSONObject().put("error","OTHER_DEVICE") }
        assertFalse(DeviceLicense.isValid())
    }

    @Test fun mismatchedServerResponseDoesNotActivate() {
        exchange(403) { JSONObject().put("error","LICENSE_INVALID") }
        exchange(200) { JSONObject().put("device","wrong").put("nonce",it.getString("nonce")).put("leaseSeconds",900) }
        assertFalse(DeviceLicense.isValid())
    }

    companion object {
        private const val TEST_KEY="AH-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun authorize() { exchange(200) { request ->
            JSONObject().put("device",hash(Base64.decode(request.getString("publicKey"),Base64.NO_WRAP)))
                .put("nonce",request.getString("nonce")).put("leaseSeconds",900)
        } }
        private fun exchange(status:Int,response:(JSONObject)->JSONObject) {
            val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
            server.soTimeout=15000
            val worker=Thread {
                server.use { it.accept().use { socket ->
                    socket.soTimeout=15000
                    val input=socket.getInputStream().bufferedReader()
                    var size=0
                    while(true) {
                        val line=input.readLine() ?: break
                        if(line.isEmpty()) break
                        if(line.startsWith("Content-Length:",true)) size=line.substringAfter(":").trim().toInt()
                    }
                    val body=CharArray(size);var read=0
                    while(read<size) {val n=input.read(body,read,size-read);check(n>0);read+=n}
                    val json=response(JSONObject(String(body))).toString().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${json.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(json);flush()
                    }
                } }
            }.apply {start()}
            DeviceLicense.contact(InstrumentationRegistry.getInstrumentation().targetContext,TEST_KEY,"http://127.0.0.1:${server.localPort}/v1/activate")
            worker.join(16000)
            assertFalse("License test server finished",worker.isAlive)
        }
    }
}
