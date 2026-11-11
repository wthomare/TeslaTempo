package org.teslatempo.drive

import com.google.protobuf.ByteString
import com.tesla.generated.universalmessage.UniversalMessage as U
import com.tesla.generated.signatures.Signatures as S
import com.tesla.generated.carserver.server.CarServer as C
import com.tesla.generated.carserver.vehicle.Vehicle as V
import com.tesla.generated.vcsec.Vcsec as VC
import com.tesla.generated.keys.Keys
import java.security.*
import java.security.spec.*
import java.math.BigInteger
import java.nio.ByteBuffer
import javax.crypto.*
import javax.crypto.spec.*

internal fun ByteArray.bs(): ByteString = ByteString.copyFrom(this)

/** Tesla's published protocol, using JCA primitives, never custom elliptic-curve arithmetic.
 * Only encrypted read requests and explicit monitor-key enrollment are exposed.
 * Session info HMAC protects integrity; it is not a vehicle certificate (Tesla protocol limitation).
 */
class TeslaSession(private val vin: String, private val keyPair: KeyPair,
                   private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val address = random(16)
    private var challenge = byteArrayOf()
    private var sessionKey: ByteArray? = null
    private var epoch = byteArrayOf()
    private var counter = 0L
    private var vehicleTime = 0L
    private var started = 0L
    private var handshakeAt = 0L
    private var requestAt = 0L
    private var requestId = byteArrayOf()
    private var requestHash = byteArrayOf()
    private var lastResponseCounter = -1L

    fun handshake(): ByteArray {
        challenge = random(16); handshakeAt = clock(); sessionKey = null
        return envelope().setSessionInfoRequest(U.SessionInfoRequest.newBuilder()
            .setPublicKey(DriveIdentity.publicBytes(keyPair.public).bs()))
            .setUuid(challenge.bs()).build().toByteArray()
    }
    fun acceptHandshake(bytes: ByteArray) {
        val message = U.RoutableMessage.parseFrom(bytes)
        checkRoute(message, challenge)
        if (clock() - handshakeAt !in 0..15_000) throw DriveFailure("Session Bluetooth expirée. Reconnectez-vous.")
        if (message.signedMessageStatus.signedMessageFaultValue in setOf(3, 4, 7))
            throw DriveFailure("Clé BLE non autorisée. Ajoutez une clé de lecture puis confirmez dans la voiture.")
        if (!message.hasSessionInfo()) throw DriveFailure("La voiture n’a pas fourni de session BLE.")
        val info = S.SessionInfo.parseFrom(message.sessionInfo)
        if (info.status != S.Session_Info_Status.SESSION_INFO_STATUS_OK) throw DriveFailure("Clé non autorisée. Ajoutez la clé de lecture à la voiture.")
        if (info.epoch.size() != 16) throw DriveFailure("Session Bluetooth invalide.")
        val key = deriveKey(keyPair.private, info.publicKey.toByteArray())
        val expected = sessionTag(key, vin, challenge, message.sessionInfo.toByteArray())
        if (!message.signatureData.hasSessionInfoTag() || !MessageDigest.isEqual(expected, message.signatureData.sessionInfoTag.tag.toByteArray()))
            throw DriveFailure("Intégrité de la session Bluetooth non vérifiée.")
        sessionKey = key; epoch = info.epoch.toByteArray(); counter = Integer.toUnsignedLong(info.counter)
        vehicleTime = Integer.toUnsignedLong(info.clockTime); started = clock(); lastResponseCounter = -1
    }
    private fun envelope() = U.RoutableMessage.newBuilder()
        .setToDestination(U.Destination.newBuilder().setDomain(U.Domain.DOMAIN_INFOTAINMENT))
        .setFromDestination(U.Destination.newBuilder().setRoutingAddress(address.bs()))
    private fun checkRoute(message: U.RoutableMessage, id: ByteArray) {
        if (message.toDestination.routingAddress != address.bs() || message.fromDestination.domain != U.Domain.DOMAIN_INFOTAINMENT ||
            message.requestUuid != id.bs()) throw DriveFailure("Réponse Bluetooth sans correspondance.")
    }
    fun matches(bytes: ByteArray, handshake: Boolean = false): Boolean = runCatching {
        val m = U.RoutableMessage.parseFrom(bytes)
        m.toDestination.routingAddress == address.bs() && m.requestUuid == (if (handshake) challenge else requestId).bs()
    }.getOrDefault(false)

    fun readRequest(charge: Boolean): ByteArray {
        val key = sessionKey ?: throw DriveFailure("Session Bluetooth non établie.")
        if (++counter > 0xffffffffL) throw DriveFailure("Session Bluetooth à renouveler.")
        val expires = vehicleTime + (clock() - started) / 1000 + 10
        if (expires > 0xffffffffL) throw DriveFailure("Horloge de session invalide.")
        val get = C.GetVehicleData.newBuilder().apply {
            if (charge) setGetChargeState(C.GetChargeState.getDefaultInstance()) else setGetDriveState(C.GetDriveState.getDefaultInstance())
        }
        val plain = C.Action.newBuilder().setVehicleAction(C.VehicleAction.newBuilder().setGetVehicleData(get)).build().toByteArray()
        val nonce = random(12)
        val aad = digest(metadata(0 to byteArrayOf(5), 1 to byteArrayOf(3), 2 to vin.toByteArray(), 3 to epoch,
            4 to uint(expires), 5 to uint(counter), 7 to uint(2)))
        val encrypted = aes(Cipher.ENCRYPT_MODE, key, nonce, aad, plain)
        val tag = encrypted.takeLast(16).toByteArray()
        lastResponseCounter = -1
        requestId = random(16); requestAt = clock(); requestHash = byteArrayOf(5) + tag
        return envelope().setUuid(requestId.bs()).setFlags(2)
            .setProtobufMessageAsBytes(encrypted.dropLast(16).toByteArray().bs())
            .setSignatureData(S.SignatureData.newBuilder()
                .setSignerIdentity(S.KeyIdentity.newBuilder().setPublicKey(DriveIdentity.publicBytes(keyPair.public).bs()))
                .setAESGCMPersonalizedData(S.AES_GCM_Personalized_Signature_Data.newBuilder()
                    .setEpoch(epoch.bs()).setNonce(nonce.bs()).setCounter(counter.toInt()).setExpiresAt(expires.toInt()).setTag(tag.bs())))
            .build().toByteArray()
    }
    fun readResponse(bytes: ByteArray): V.VehicleData {
        val m = U.RoutableMessage.parseFrom(bytes)
        checkRoute(m, requestId)
        if (clock() - requestAt !in 0..15_000) throw DriveFailure("Réponse Bluetooth périmée.")
        val fault = m.signedMessageStatus.signedMessageFaultValue
        if (!m.signatureData.hasAESGCMResponseData()) {
            // Protocol failures may be unsigned. Never accept data from such replies.
            throw DriveFailure(when (fault) {
                3, 4, 7 -> "Clé BLE refusée. Vérifiez la clé de lecture dans la voiture."
                8, 9 -> "Cette lecture BLE n’est pas prise en charge par la voiture."
                25 -> "Réponse Tesla trop grande pour le BLE. Essayez une destination plus courte ou sans navigation."
                else -> "Réponse BLE non chiffrée ou session à renouveler. Reconnectez-vous."
            }, recoverableData = fault in setOf(8, 9, 25))
        }
        val sig = m.signatureData.getAESGCMResponseData()
        val responseCounter = Integer.toUnsignedLong(sig.counter)
        if (responseCounter <= lastResponseCounter) throw DriveFailure("Réponse Bluetooth répétée.")
        val aad = digest(metadata(0 to byteArrayOf(9), 1 to byteArrayOf(3), 2 to vin.toByteArray(),
            5 to uint(responseCounter), 7 to uint(Integer.toUnsignedLong(m.flags)), 8 to requestHash, 9 to uint(fault.toLong())))
        val plain = try { aes(Cipher.DECRYPT_MODE, sessionKey!!, sig.nonce.toByteArray(), aad, m.protobufMessageAsBytes.toByteArray() + sig.tag.toByteArray()) }
        catch (_: GeneralSecurityException) { throw DriveFailure("Authentification de la réponse Bluetooth échouée.") }
        lastResponseCounter = responseCounter
        requestId = byteArrayOf() // Consume this response; never replay a result.
        if (fault != 0) throw DriveFailure("Lecture Bluetooth refusée par la voiture. Reconnectez-vous.")
        val response = C.Response.parseFrom(plain)
        if (response.actionStatus.result != C.OperationStatus_E.OPERATIONSTATUS_OK || !response.hasVehicleData())
            throw DriveFailure("Données BLE indisponibles sur ce véhicule.")
        return response.vehicleData
    }
    companion object {
        private val rng = SecureRandom()
        fun random(size: Int) = ByteArray(size).also(rng::nextBytes)
        fun uint(value: Long): ByteArray = ByteBuffer.allocate(4).putInt(value.toInt()).array()
        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun metadata(vararg values: Pair<Int, ByteArray>): ByteArray {
            require(values.map { it.first }.distinct().size == values.size)
            return values.sortedBy { it.first }.fold(byteArrayOf()) { bytes, (tag, value) ->
                require(value.size <= 255); bytes + byteArrayOf(tag.toByte(), value.size.toByte()) + value
            } + byteArrayOf(255.toByte())
        }
        private fun hmac(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
        }
        fun sessionTag(key: ByteArray, vin: String, challenge: ByteArray, info: ByteArray): ByteArray =
            hmac(hmac(key, "session info".toByteArray()), metadata(0 to byteArrayOf(6), 2 to vin.toByteArray(), 6 to challenge) + info)
        fun deriveKey(privateKey: PrivateKey, publicBytes: ByteArray): ByteArray {
            require(publicBytes.size == 65 && publicBytes[0] == 4.toByte())
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, publicBytes.copyOfRange(1,33)), BigInteger(1, publicBytes.copyOfRange(33,65)))
            val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            val agreement = KeyAgreement.getInstance("ECDH").apply { init(privateKey); doPhase(publicKey, true) }
            return MessageDigest.getInstance("SHA-1").digest(agreement.generateSecret()).copyOf(16)
        }
        fun aes(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
            require(nonce.size == 12)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(input)
            }
        }
        fun enrollment(publicKey: ByteArray): ByteArray {
            val add = VC.UnsignedMessage.newBuilder().setWhitelistOperation(VC.WhitelistOperation.newBuilder()
                .setAddKeyToWhitelistAndAddPermissions(VC.PermissionChange.newBuilder()
                    .setKey(VC.PublicKey.newBuilder().setPublicKeyRaw(publicKey.bs())).setKeyRole(Keys.Role.ROLE_VEHICLE_MONITOR))
                .setMetadataForKey(VC.KeyMetadata.newBuilder().setKeyFormFactor(VC.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE)))
            return VC.ToVCSECMessage.newBuilder().setSignedMessage(VC.SignedMessage.newBuilder()
                .setSignatureType(VC.SignatureType.SIGNATURE_TYPE_PRESENT_KEY).setProtobufMessageAsBytes(add.build().toByteString())).build().toByteArray()
        }
    }
}
