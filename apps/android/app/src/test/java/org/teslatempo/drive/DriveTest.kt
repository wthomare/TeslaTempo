package org.teslatempo.drive

import org.junit.Test
import org.junit.Assert.*
import com.tesla.generated.universalmessage.UniversalMessage as U
import com.tesla.generated.signatures.Signatures as S
import com.tesla.generated.carserver.server.CarServer as C
import com.tesla.generated.carserver.vehicle.Vehicle as V
import com.tesla.generated.vcsec.Vcsec as VC
import com.tesla.generated.keys.Keys
import java.security.*
import java.security.spec.*
import java.math.BigInteger
import javax.crypto.Cipher

class DriveTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // Public, deliberately synthetic Tesla protocol.md conformance vectors. Never enroll these keys.
    private val vin = "5YJ30123456789ABC"
    private val secret = hex("1b2fce19967b79db696f909cff89ea9a")
    private val vehiclePublic = hex("04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97")
    private val epoch = hex("4c463f9cc0d3d26906e982ed224adde6")
    private val info = hex("0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a104c463f9cc0d3d26906e982ed224adde6255a0a0000")
    private fun keys(): KeyPair {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val factory = KeyFactory.getInstance("EC")
        return KeyPair(factory.generatePublic(ECPublicKeySpec(ECPoint(
            BigInteger("b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7",16),
            BigInteger("14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e",16)),params)),
            factory.generatePrivate(ECPrivateKeySpec(BigInteger("2538cdc29a97c19c1e99a637d6cf4f8c970c118b56ede1e6323e6d162c4b30db",16),params)))
    }
    @Test fun officialEcdhAndSessionHmacVectors() {
        assertArrayEquals(secret, TeslaSession.deriveKey(keys().private,vehiclePublic))
        assertArrayEquals(hex("996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002"),
            TeslaSession.sessionTag(secret,vin,hex("1588d5a30eabc6f8fc9a951b11f6fd11"),info))
    }
    @Test fun officialGcmCommandVector() {
        val aad=TeslaSession.digest(TeslaSession.metadata(0 to byteArrayOf(5),1 to byteArrayOf(3),2 to vin.toByteArray(),
            3 to epoch,4 to TeslaSession.uint(2655),5 to TeslaSession.uint(7),7 to TeslaSession.uint(2)))
        assertArrayEquals(hex("38038e8c0f2ec228e0ff64991481db3a7bbc133696c5"),TeslaSession.aes(Cipher.ENCRYPT_MODE,
            secret,hex("dbf79447fa156674dae1caed"),aad,hex("120452020801")))
    }
    private fun reply(request: U.RoutableMessage) = U.RoutableMessage.newBuilder()
        .setToDestination(request.fromDestination).setFromDestination(request.toDestination).setRequestUuid(request.uuid)
    private fun handshakeResponse(request: U.RoutableMessage): U.RoutableMessage = reply(request).setSessionInfo(info.bs())
        .setSignatureData(S.SignatureData.newBuilder().setSessionInfoTag(S.HMAC_Signature_Data.newBuilder()
            .setTag(TeslaSession.sessionTag(secret,vin,request.uuid.toByteArray(),info).bs()))).build()
    private fun connected(): TeslaSession = TeslaSession(vin,keys(),{1000L}).also { session ->
        val request=U.RoutableMessage.parseFrom(session.handshake())
        session.acceptHandshake(handshakeResponse(request).toByteArray())
    }
    private fun response(request: U.RoutableMessage, corrupt: Boolean = false): ByteArray {
        val hash=byteArrayOf(5)+request.signatureData.getAESGCMPersonalizedData().tag.toByteArray()
        val aad=TeslaSession.digest(TeslaSession.metadata(0 to byteArrayOf(9),1 to byteArrayOf(3),2 to vin.toByteArray(),
            5 to TeslaSession.uint(0),7 to TeslaSession.uint(0),8 to hash,9 to TeslaSession.uint(0)))
        val body=C.Response.newBuilder().setVehicleData(V.VehicleData.newBuilder().setDriveState(V.DriveState.newBuilder().setSpeed(25))).build().toByteArray()
        val nonce=ByteArray(12){3};val encrypted=TeslaSession.aes(Cipher.ENCRYPT_MODE,secret,nonce,aad,body)
        if(corrupt) encrypted[0]=(encrypted[0].toInt() xor 1).toByte()
        return reply(request).setProtobufMessageAsBytes(encrypted.dropLast(16).toByteArray().bs())
            .setSignatureData(S.SignatureData.newBuilder().setAESGCMResponseData(S.AES_GCM_Response_Signature_Data.newBuilder()
                .setNonce(nonce.bs()).setCounter(0).setTag(encrypted.takeLast(16).toByteArray().bs()))).build().toByteArray()
    }
    @Test fun encryptedReadResponseIsBoundToRequestAndConsumed() {
        val session=connected();val request=U.RoutableMessage.parseFrom(session.readRequest(false));val response=response(request)
        assertEquals(25, session.readResponse(response).driveState.speed)
        assertThrows(DriveFailure::class.java) { session.readResponse(response) }
        // Response counters are scoped to each request, and can restart at zero.
        val second=U.RoutableMessage.parseFrom(session.readRequest(false))
        assertEquals(25,session.readResponse(response(second)).driveState.speed)
    }
    @Test fun mtuFailureAllowsAnotherReadButNeverReturnsData() {
        val session=connected();val request=U.RoutableMessage.parseFrom(session.readRequest(false))
        val error=reply(request).setSignedMessageStatus(U.MessageStatus.newBuilder()
            .setSignedMessageFaultValue(25)).build().toByteArray()
        val failure=assertThrows(DriveFailure::class.java) { session.readResponse(error) }
        assertTrue(failure.recoverableData)
        val next=U.RoutableMessage.parseFrom(session.readRequest(true))
        assertTrue(next.hasProtobufMessageAsBytes())
        assertNotEquals(request.uuid,next.uuid)
    }
    @Test fun requestsContainOnlyRequestedReadOperations() {
        val session=connected()
        for (charge in listOf(false,true)) {
            val request=U.RoutableMessage.parseFrom(session.readRequest(charge))
            val sig=request.signatureData.getAESGCMPersonalizedData()
            val aad=TeslaSession.digest(TeslaSession.metadata(0 to byteArrayOf(5),1 to byteArrayOf(3),2 to vin.toByteArray(),
                3 to epoch,4 to TeslaSession.uint(Integer.toUnsignedLong(sig.expiresAt)),
                5 to TeslaSession.uint(Integer.toUnsignedLong(sig.counter)),7 to TeslaSession.uint(2)))
            val plain=TeslaSession.aes(Cipher.DECRYPT_MODE,secret,sig.nonce.toByteArray(),aad,
                request.protobufMessageAsBytes.toByteArray()+sig.tag.toByteArray())
            val action=C.Action.parseFrom(plain)
            assertTrue(action.vehicleAction.hasGetVehicleData())
            assertEquals(charge,action.vehicleAction.getVehicleData.hasGetChargeState())
            assertEquals(!charge,action.vehicleAction.getVehicleData.hasGetDriveState())
        }
    }
    @Test fun tamperedReplyIsRejected() {
        val session=connected();val request=U.RoutableMessage.parseFrom(session.readRequest(false))
        assertThrows(DriveFailure::class.java) { session.readResponse(response(request,true)) }
    }
    @Test fun wrongChallengeAndExpiredHandshakeAreRejected() {
        var now=1000L;val session=TeslaSession(vin,keys(),{now});val request=U.RoutableMessage.parseFrom(session.handshake())
        val valid=handshakeResponse(request)
        assertThrows(DriveFailure::class.java) { session.acceptHandshake(valid.toBuilder().setRequestUuid(byteArrayOf(1).bs()).build().toByteArray()) }
        now+=16000
        assertThrows(DriveFailure::class.java) { session.acceptHandshake(valid.toByteArray()) }
    }
    @Test fun unsignedDataIsNeverAccepted() {
        val session=connected();val request=U.RoutableMessage.parseFrom(session.readRequest(false))
        assertThrows(DriveFailure::class.java) { session.readResponse(reply(request).setProtobufMessageAsBytes(byteArrayOf(1).bs()).build().toByteArray()) }
    }
    @Test fun pairingRequestsOnlyReadOnlyRole() {
        val envelope=VC.ToVCSECMessage.parseFrom(TeslaSession.enrollment(DriveIdentity.publicBytes(keys().public)))
        assertEquals(VC.SignatureType.SIGNATURE_TYPE_PRESENT_KEY,envelope.signedMessage.signatureType)
        val payload=VC.UnsignedMessage.parseFrom(envelope.signedMessage.protobufMessageAsBytes)
        assertEquals(Keys.Role.ROLE_VEHICLE_MONITOR,payload.whitelistOperation.addKeyToWhitelistAndAddPermissions.keyRole)
    }
    @Test fun framesHandleSplitsCoalescenceAndRejectOversizedLengths() {
        val data=BleFrames.encode(byteArrayOf(7,8,9));val decoder=BleFrames()
        assertTrue(decoder.append(data.copyOfRange(0,1)).isEmpty())
        val packets=decoder.append(data.copyOfRange(1,data.size)+data)
        assertEquals(2,packets.size);assertArrayEquals(byteArrayOf(7,8,9),packets[0])
        assertThrows(DriveFailure::class.java) { decoder.append(byteArrayOf(0x7f,0x7f)) }
    }
    @Test fun freshnessAndDemoCannotInventValues() {
        assertEquals(CockpitText("— km/h","— %","— km"),DriveState().cockpit(10000))
        val s=DriveState(speed=50.0,speedAt=1000,battery=70,rangeKm=300.0,chargeAt=1000)
        assertEquals("50 km/h",s.cockpit(2000).speed)
        assertEquals("— km/h",s.cockpit(8000).speed)
        assertEquals("70 %",s.cockpit(8000).battery)
        assertEquals("— %",s.cockpit(62000).battery)
        assertFalse(fresh(5000,1000,6000));assertEquals(160.9344,milesToKm(100.0)!!,0.00001)
        assertNull(milesToKm(Double.NaN));assertNull(milesToKm(-1.0))
    }
    @Test fun destinationValidationAndLocaleIndependentWazeUrl() {
        val previous=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val d=DriveDestination.from("Lieu de test",48.1,-1.7,0)!!
            assertTrue(d.wazeLink().contains("ll=48.1,-1.7"));assertFalse(d.toString().contains("Lieu de test"))
            assertNull(DriveDestination.from("",48.1,-1.7,0));assertNull(DriveDestination.from("test",0.0,0.0,0))
            assertNull(DriveDestination.from("test",Double.NaN,0.0,0));assertNull(DriveDestination.from("test",91.0,1.0,0))
        } finally { java.util.Locale.setDefault(previous) }
    }
    @Test fun advertisementUsesOfficialVinHashVector() {
        assertEquals("S1a87a5a75f3df858C",advertisementName("5YJS0000000000000"))
        assertThrows(DriveFailure::class.java) { normalizedVin("incorrect") }
    }
}
