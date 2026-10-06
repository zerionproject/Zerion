
@file:Suppress("NAME_SHADOWING")

package org.bitcoindevkit.bitcoinffi


import com.sun.jna.Library
import com.sun.jna.IntegerType
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Callback
import com.sun.jna.ptr.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean


@Structure.FieldOrder("capacity", "len", "data")
open class RustBuffer : Structure() {
    @JvmField var capacity: Long = 0
    @JvmField var len: Long = 0
    @JvmField var data: Pointer? = null

    class ByValue: RustBuffer(), Structure.ByValue
    class ByReference: RustBuffer(), Structure.ByReference

   internal fun setValue(other: RustBuffer) {
        capacity = other.capacity
        len = other.len
        data = other.data
    }

    companion object {
        internal fun alloc(size: ULong = 0UL) = uniffiRustCall() { status ->
            UniffiLib.INSTANCE.ffi_bitcoin_ffi_rustbuffer_alloc(size.toLong(), status)
        }.also {
            if(it.data == null) {
               throw RuntimeException("RustBuffer.alloc() returned null data pointer (size=${size})")
           }
        }

        internal fun create(capacity: ULong, len: ULong, data: Pointer?): RustBuffer.ByValue {
            var buf = RustBuffer.ByValue()
            buf.capacity = capacity.toLong()
            buf.len = len.toLong()
            buf.data = data
            return buf
        }

        internal fun free(buf: RustBuffer.ByValue) = uniffiRustCall() { status ->
            UniffiLib.INSTANCE.ffi_bitcoin_ffi_rustbuffer_free(buf, status)
        }
    }

    @Suppress("TooGenericExceptionThrown")
    fun asByteBuffer() =
        this.data?.getByteBuffer(0, this.len.toLong())?.also {
            it.order(ByteOrder.BIG_ENDIAN)
        }
}

class RustBufferByReference : ByReference(16) {
    fun setValue(value: RustBuffer.ByValue) {
        val pointer = getPointer()
        pointer.setLong(0, value.capacity)
        pointer.setLong(8, value.len)
        pointer.setPointer(16, value.data)
    }

    fun getValue(): RustBuffer.ByValue {
        val pointer = getPointer()
        val value = RustBuffer.ByValue()
        value.writeField("capacity", pointer.getLong(0))
        value.writeField("len", pointer.getLong(8))
        value.writeField("data", pointer.getLong(16))

        return value
    }
}


@Structure.FieldOrder("len", "data")
internal open class ForeignBytes : Structure() {
    @JvmField var len: Int = 0
    @JvmField var data: Pointer? = null

    class ByValue : ForeignBytes(), Structure.ByValue
}
public interface FfiConverter<KotlinType, FfiType> {
    fun lift(value: FfiType): KotlinType

    fun lower(value: KotlinType): FfiType

    fun read(buf: ByteBuffer): KotlinType

    fun allocationSize(value: KotlinType): ULong

    fun write(value: KotlinType, buf: ByteBuffer)

    fun lowerIntoRustBuffer(value: KotlinType): RustBuffer.ByValue {
        val rbuf = RustBuffer.alloc(allocationSize(value))
        try {
            val bbuf = rbuf.data!!.getByteBuffer(0, rbuf.capacity).also {
                it.order(ByteOrder.BIG_ENDIAN)
            }
            write(value, bbuf)
            rbuf.writeField("len", bbuf.position().toLong())
            return rbuf
        } catch (e: Throwable) {
            RustBuffer.free(rbuf)
            throw e
        }
    }

    fun liftFromRustBuffer(rbuf: RustBuffer.ByValue): KotlinType {
        val byteBuf = rbuf.asByteBuffer()!!
        try {
           val item = read(byteBuf)
           if (byteBuf.hasRemaining()) {
               throw RuntimeException("junk remaining in buffer after lifting, something is very wrong!!")
           }
           return item
        } finally {
            RustBuffer.free(rbuf)
        }
    }
}

public interface FfiConverterRustBuffer<KotlinType>: FfiConverter<KotlinType, RustBuffer.ByValue> {
    override fun lift(value: RustBuffer.ByValue) = liftFromRustBuffer(value)
    override fun lower(value: KotlinType) = lowerIntoRustBuffer(value)
}

internal const val UNIFFI_CALL_SUCCESS = 0.toByte()
internal const val UNIFFI_CALL_ERROR = 1.toByte()
internal const val UNIFFI_CALL_UNEXPECTED_ERROR = 2.toByte()

@Structure.FieldOrder("code", "error_buf")
internal open class UniffiRustCallStatus : Structure() {
    @JvmField var code: Byte = 0
    @JvmField var error_buf: RustBuffer.ByValue = RustBuffer.ByValue()

    class ByValue: UniffiRustCallStatus(), Structure.ByValue

    fun isSuccess(): Boolean {
        return code == UNIFFI_CALL_SUCCESS
    }

    fun isError(): Boolean {
        return code == UNIFFI_CALL_ERROR
    }

    fun isPanic(): Boolean {
        return code == UNIFFI_CALL_UNEXPECTED_ERROR
    }

    companion object {
        fun create(code: Byte, errorBuf: RustBuffer.ByValue): UniffiRustCallStatus.ByValue {
            val callStatus = UniffiRustCallStatus.ByValue()
            callStatus.code = code
            callStatus.error_buf = errorBuf
            return callStatus
        }
    }
}

class InternalException(message: String) : kotlin.Exception(message)

interface UniffiRustCallStatusErrorHandler<E> {
    fun lift(error_buf: RustBuffer.ByValue): E;
}


private inline fun <U, E: kotlin.Exception> uniffiRustCallWithError(errorHandler: UniffiRustCallStatusErrorHandler<E>, callback: (UniffiRustCallStatus) -> U): U {
    var status = UniffiRustCallStatus()
    val return_value = callback(status)
    uniffiCheckCallStatus(errorHandler, status)
    return return_value
}

private fun<E: kotlin.Exception> uniffiCheckCallStatus(errorHandler: UniffiRustCallStatusErrorHandler<E>, status: UniffiRustCallStatus) {
    if (status.isSuccess()) {
        return
    } else if (status.isError()) {
        throw errorHandler.lift(status.error_buf)
    } else if (status.isPanic()) {
        if (status.error_buf.len > 0) {
            throw InternalException(FfiConverterString.lift(status.error_buf))
        } else {
            throw InternalException("Rust panic")
        }
    } else {
        throw InternalException("Unknown rust call status: $status.code")
    }
}

object UniffiNullRustCallStatusErrorHandler: UniffiRustCallStatusErrorHandler<InternalException> {
    override fun lift(error_buf: RustBuffer.ByValue): InternalException {
        RustBuffer.free(error_buf)
        return InternalException("Unexpected CALL_ERROR")
    }
}

private inline fun <U> uniffiRustCall(callback: (UniffiRustCallStatus) -> U): U {
    return uniffiRustCallWithError(UniffiNullRustCallStatusErrorHandler, callback)
}

internal inline fun<T> uniffiTraitInterfaceCall(
    callStatus: UniffiRustCallStatus,
    makeCall: () -> T,
    writeReturn: (T) -> Unit,
) {
    try {
        writeReturn(makeCall())
    } catch(e: kotlin.Exception) {
        callStatus.code = UNIFFI_CALL_UNEXPECTED_ERROR
        callStatus.error_buf = FfiConverterString.lower(e.toString())
    }
}

internal inline fun<T, reified E: Throwable> uniffiTraitInterfaceCallWithError(
    callStatus: UniffiRustCallStatus,
    makeCall: () -> T,
    writeReturn: (T) -> Unit,
    lowerError: (E) -> RustBuffer.ByValue
) {
    try {
        writeReturn(makeCall())
    } catch(e: kotlin.Exception) {
        if (e is E) {
            callStatus.code = UNIFFI_CALL_ERROR
            callStatus.error_buf = lowerError(e)
        } else {
            callStatus.code = UNIFFI_CALL_UNEXPECTED_ERROR
            callStatus.error_buf = FfiConverterString.lower(e.toString())
        }
    }
}
internal class UniffiHandleMap<T: Any> {
    private val map = ConcurrentHashMap<Long, T>()
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    val size: Int
        get() = map.size

    fun insert(obj: T): Long {
        val handle = counter.getAndAdd(1)
        map.put(handle, obj)
        return handle
    }

    fun get(handle: Long): T {
        return map.get(handle) ?: throw InternalException("UniffiHandleMap.get: Invalid handle")
    }

    fun remove(handle: Long): T {
        return map.remove(handle) ?: throw InternalException("UniffiHandleMap: Invalid handle")
    }
}

@Synchronized
private fun findLibraryName(componentName: String): String {
    val libOverride = System.getProperty("uniffi.component.$componentName.libraryOverride")
    if (libOverride != null) {
        return libOverride
    }
    return "payjoin_ffi"
}

private inline fun <reified Lib : Library> loadIndirect(
    componentName: String
): Lib {
    return Native.load<Lib>(findLibraryName(componentName), Lib::class.java)
}

internal interface UniffiRustFutureContinuationCallback : com.sun.jna.Callback {
    fun callback(`data`: Long,`pollResult`: Byte,)
}
internal interface UniffiForeignFutureFree : com.sun.jna.Callback {
    fun callback(`handle`: Long,)
}
internal interface UniffiCallbackInterfaceFree : com.sun.jna.Callback {
    fun callback(`handle`: Long,)
}
@Structure.FieldOrder("handle", "free")
internal open class UniffiForeignFuture(
    @JvmField internal var `handle`: Long = 0.toLong(),
    @JvmField internal var `free`: UniffiForeignFutureFree? = null,
) : Structure() {
    class UniffiByValue(
        `handle`: Long = 0.toLong(),
        `free`: UniffiForeignFutureFree? = null,
    ): UniffiForeignFuture(`handle`,`free`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFuture) {
        `handle` = other.`handle`
        `free` = other.`free`
    }

}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU8(
    @JvmField internal var `returnValue`: Byte = 0.toByte(),
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Byte = 0.toByte(),
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructU8(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructU8) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteU8 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU8.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI8(
    @JvmField internal var `returnValue`: Byte = 0.toByte(),
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Byte = 0.toByte(),
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructI8(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructI8) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteI8 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI8.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU16(
    @JvmField internal var `returnValue`: Short = 0.toShort(),
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Short = 0.toShort(),
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructU16(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructU16) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteU16 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU16.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI16(
    @JvmField internal var `returnValue`: Short = 0.toShort(),
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Short = 0.toShort(),
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructI16(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructI16) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteI16 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI16.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU32(
    @JvmField internal var `returnValue`: Int = 0,
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Int = 0,
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructU32(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructU32) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteU32 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU32.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI32(
    @JvmField internal var `returnValue`: Int = 0,
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Int = 0,
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructI32(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructI32) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteI32 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI32.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU64(
    @JvmField internal var `returnValue`: Long = 0.toLong(),
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Long = 0.toLong(),
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructU64(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructU64) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteU64 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU64.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI64(
    @JvmField internal var `returnValue`: Long = 0.toLong(),
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Long = 0.toLong(),
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructI64(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructI64) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteI64 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI64.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructF32(
    @JvmField internal var `returnValue`: Float = 0.0f,
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Float = 0.0f,
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructF32(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructF32) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteF32 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructF32.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructF64(
    @JvmField internal var `returnValue`: Double = 0.0,
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Double = 0.0,
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructF64(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructF64) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteF64 : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructF64.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructPointer(
    @JvmField internal var `returnValue`: Pointer = Pointer.NULL,
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: Pointer = Pointer.NULL,
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructPointer(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructPointer) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompletePointer : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructPointer.UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructRustBuffer(
    @JvmField internal var `returnValue`: RustBuffer.ByValue = RustBuffer.ByValue(),
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `returnValue`: RustBuffer.ByValue = RustBuffer.ByValue(),
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructRustBuffer(`returnValue`,`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructRustBuffer) {
        `returnValue` = other.`returnValue`
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteRustBuffer : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructRustBuffer.UniffiByValue,)
}
@Structure.FieldOrder("callStatus")
internal open class UniffiForeignFutureStructVoid(
    @JvmField internal var `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
) : Structure() {
    class UniffiByValue(
        `callStatus`: UniffiRustCallStatus.ByValue = UniffiRustCallStatus.ByValue(),
    ): UniffiForeignFutureStructVoid(`callStatus`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiForeignFutureStructVoid) {
        `callStatus` = other.`callStatus`
    }

}
internal interface UniffiForeignFutureCompleteVoid : com.sun.jna.Callback {
    fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructVoid.UniffiByValue,)
}






























































































































internal interface IntegrityCheckingUniffiLib : Library {
    fun uniffi_bitcoin_ffi_checksum_method_address_is_valid_for_network(
): Short
fun uniffi_bitcoin_ffi_checksum_method_address_script_pubkey(
): Short
fun uniffi_bitcoin_ffi_checksum_method_address_to_qr_uri(
): Short
fun uniffi_bitcoin_ffi_checksum_method_amount_to_btc(
): Short
fun uniffi_bitcoin_ffi_checksum_method_amount_to_sat(
): Short
fun uniffi_bitcoin_ffi_checksum_method_feerate_to_sat_per_kwu(
): Short
fun uniffi_bitcoin_ffi_checksum_method_feerate_to_sat_per_vb_ceil(
): Short
fun uniffi_bitcoin_ffi_checksum_method_feerate_to_sat_per_vb_floor(
): Short
fun uniffi_bitcoin_ffi_checksum_method_script_to_bytes(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_compute_txid(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_input(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_is_coinbase(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_is_explicitly_rbf(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_is_lock_time_enabled(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_lock_time(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_output(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_serialize(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_total_size(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_version(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_vsize(
): Short
fun uniffi_bitcoin_ffi_checksum_method_transaction_weight(
): Short
fun uniffi_bitcoin_ffi_checksum_constructor_address_from_script(
): Short
fun uniffi_bitcoin_ffi_checksum_constructor_address_new(
): Short
fun uniffi_bitcoin_ffi_checksum_constructor_amount_from_btc(
): Short
fun uniffi_bitcoin_ffi_checksum_constructor_amount_from_sat(
): Short
fun uniffi_bitcoin_ffi_checksum_constructor_feerate_from_sat_per_kwu(
): Short
fun uniffi_bitcoin_ffi_checksum_constructor_feerate_from_sat_per_vb(
): Short
fun uniffi_bitcoin_ffi_checksum_constructor_script_new(
): Short
fun uniffi_bitcoin_ffi_checksum_constructor_transaction_deserialize(
): Short
fun ffi_bitcoin_ffi_uniffi_contract_version(
): Int

}

internal interface UniffiLib : Library {
    companion object {
        internal val INSTANCE: UniffiLib by lazy {
            val componentName = "bitcoin"
            loadIndirect<IntegrityCheckingUniffiLib>(componentName)
                .also { lib: IntegrityCheckingUniffiLib ->
                    uniffiCheckContractApiVersion(lib)
                    uniffiCheckApiChecksums(lib)
                }
            val lib = loadIndirect<UniffiLib>(componentName)
            lib
        }
        
        internal val CLEANER: UniffiCleaner by lazy {
            UniffiCleaner.create()
        }
    }

    fun uniffi_bitcoin_ffi_fn_clone_address(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_free_address(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_bitcoin_ffi_fn_constructor_address_from_script(`script`: Pointer,`network`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_constructor_address_new(`address`: RustBuffer.ByValue,`network`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_method_address_is_valid_for_network(`ptr`: Pointer,`network`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun uniffi_bitcoin_ffi_fn_method_address_script_pubkey(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_method_address_to_qr_uri(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_bitcoin_ffi_fn_clone_amount(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_free_amount(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_bitcoin_ffi_fn_constructor_amount_from_btc(`btc`: Double,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_constructor_amount_from_sat(`sat`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_method_amount_to_btc(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Double
fun uniffi_bitcoin_ffi_fn_method_amount_to_sat(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun uniffi_bitcoin_ffi_fn_clone_feerate(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_free_feerate(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_bitcoin_ffi_fn_constructor_feerate_from_sat_per_kwu(`satPerKwu`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_constructor_feerate_from_sat_per_vb(`satPerVb`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_method_feerate_to_sat_per_kwu(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun uniffi_bitcoin_ffi_fn_method_feerate_to_sat_per_vb_ceil(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun uniffi_bitcoin_ffi_fn_method_feerate_to_sat_per_vb_floor(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun uniffi_bitcoin_ffi_fn_clone_script(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_free_script(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_bitcoin_ffi_fn_constructor_script_new(`rawOutputScript`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_method_script_to_bytes(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_bitcoin_ffi_fn_clone_transaction(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_free_transaction(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_bitcoin_ffi_fn_constructor_transaction_deserialize(`transactionBytes`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_bitcoin_ffi_fn_method_transaction_compute_txid(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_bitcoin_ffi_fn_method_transaction_input(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_bitcoin_ffi_fn_method_transaction_is_coinbase(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun uniffi_bitcoin_ffi_fn_method_transaction_is_explicitly_rbf(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun uniffi_bitcoin_ffi_fn_method_transaction_is_lock_time_enabled(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun uniffi_bitcoin_ffi_fn_method_transaction_lock_time(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Int
fun uniffi_bitcoin_ffi_fn_method_transaction_output(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_bitcoin_ffi_fn_method_transaction_serialize(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_bitcoin_ffi_fn_method_transaction_total_size(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun uniffi_bitcoin_ffi_fn_method_transaction_version(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Int
fun uniffi_bitcoin_ffi_fn_method_transaction_vsize(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun uniffi_bitcoin_ffi_fn_method_transaction_weight(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun ffi_bitcoin_ffi_rustbuffer_alloc(`size`: Long,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun ffi_bitcoin_ffi_rustbuffer_from_bytes(`bytes`: ForeignBytes.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun ffi_bitcoin_ffi_rustbuffer_free(`buf`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun ffi_bitcoin_ffi_rustbuffer_reserve(`buf`: RustBuffer.ByValue,`additional`: Long,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun ffi_bitcoin_ffi_rust_future_poll_u8(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_u8(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_u8(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_u8(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun ffi_bitcoin_ffi_rust_future_poll_i8(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_i8(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_i8(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_i8(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun ffi_bitcoin_ffi_rust_future_poll_u16(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_u16(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_u16(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_u16(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Short
fun ffi_bitcoin_ffi_rust_future_poll_i16(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_i16(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_i16(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_i16(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Short
fun ffi_bitcoin_ffi_rust_future_poll_u32(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_u32(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_u32(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_u32(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Int
fun ffi_bitcoin_ffi_rust_future_poll_i32(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_i32(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_i32(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_i32(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Int
fun ffi_bitcoin_ffi_rust_future_poll_u64(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_u64(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_u64(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_u64(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun ffi_bitcoin_ffi_rust_future_poll_i64(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_i64(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_i64(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_i64(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun ffi_bitcoin_ffi_rust_future_poll_f32(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_f32(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_f32(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_f32(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Float
fun ffi_bitcoin_ffi_rust_future_poll_f64(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_f64(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_f64(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_f64(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Double
fun ffi_bitcoin_ffi_rust_future_poll_pointer(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_pointer(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_pointer(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_pointer(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun ffi_bitcoin_ffi_rust_future_poll_rust_buffer(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_rust_buffer(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_rust_buffer(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_rust_buffer(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun ffi_bitcoin_ffi_rust_future_poll_void(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_cancel_void(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_free_void(`handle`: Long,
): Unit
fun ffi_bitcoin_ffi_rust_future_complete_void(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Unit

}

private fun uniffiCheckContractApiVersion(lib: IntegrityCheckingUniffiLib) {
    val bindings_contract_version = 29
    val scaffolding_contract_version = lib.ffi_bitcoin_ffi_uniffi_contract_version()
    if (bindings_contract_version != scaffolding_contract_version) {
        throw RuntimeException("UniFFI contract version mismatch: try cleaning and rebuilding your project")
    }
}
@Suppress("UNUSED_PARAMETER")
private fun uniffiCheckApiChecksums(lib: IntegrityCheckingUniffiLib) {
    if (lib.uniffi_bitcoin_ffi_checksum_method_address_is_valid_for_network() != 55428.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_address_script_pubkey() != 7086.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_address_to_qr_uri() != 34272.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_amount_to_btc() != 20425.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_amount_to_sat() != 8831.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_feerate_to_sat_per_kwu() != 30352.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_feerate_to_sat_per_vb_ceil() != 35371.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_feerate_to_sat_per_vb_floor() != 55263.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_script_to_bytes() != 44421.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_compute_txid() != 54056.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_input() != 57477.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_is_coinbase() != 30082.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_is_explicitly_rbf() != 27333.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_is_lock_time_enabled() != 60902.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_lock_time() != 47596.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_output() != 61951.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_serialize() != 53292.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_total_size() != 51597.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_version() != 47252.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_vsize() != 58879.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_method_transaction_weight() != 12807.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_constructor_address_from_script() != 62411.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_constructor_address_new() != 26098.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_constructor_amount_from_btc() != 52219.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_constructor_amount_from_sat() != 5551.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_constructor_feerate_from_sat_per_kwu() != 60257.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_constructor_feerate_from_sat_per_vb() != 48712.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_constructor_script_new() != 16741.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_bitcoin_ffi_checksum_constructor_transaction_deserialize() != 44363.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
}

public fun uniffiEnsureInitialized() {
    UniffiLib.INSTANCE
}


interface Disposable {
    fun destroy()
    companion object {
        fun destroy(vararg args: Any?) {
            for (arg in args) {
                when (arg) {
                    is Disposable -> arg.destroy()
                    is ArrayList<*> -> {
                        for (idx in arg.indices) {
                            val element = arg[idx]
                            if (element is Disposable) {
                                element.destroy()
                            }
                        }
                    }
                    is Map<*, *> -> {
                        for (element in arg.values) {
                            if (element is Disposable) {
                                element.destroy()
                            }
                        }
                    }
                    is Iterable<*> -> {
                        for (element in arg) {
                            if (element is Disposable) {
                                element.destroy()
                            }
                        }
                    }
                }
            }
        }
    }
}

inline fun <T : Disposable?, R> T.use(block: (T) -> R) =
    try {
        block(this)
    } finally {
        try {
            this?.destroy()
        } catch (e: Throwable) {
        }
    }

object NoPointer

public object FfiConverterUByte: FfiConverter<UByte, Byte> {
    override fun lift(value: Byte): UByte {
        return value.toUByte()
    }

    override fun read(buf: ByteBuffer): UByte {
        return lift(buf.get())
    }

    override fun lower(value: UByte): Byte {
        return value.toByte()
    }

    override fun allocationSize(value: UByte) = 1UL

    override fun write(value: UByte, buf: ByteBuffer) {
        buf.put(value.toByte())
    }
}

public object FfiConverterUInt: FfiConverter<UInt, Int> {
    override fun lift(value: Int): UInt {
        return value.toUInt()
    }

    override fun read(buf: ByteBuffer): UInt {
        return lift(buf.getInt())
    }

    override fun lower(value: UInt): Int {
        return value.toInt()
    }

    override fun allocationSize(value: UInt) = 4UL

    override fun write(value: UInt, buf: ByteBuffer) {
        buf.putInt(value.toInt())
    }
}

public object FfiConverterInt: FfiConverter<Int, Int> {
    override fun lift(value: Int): Int {
        return value
    }

    override fun read(buf: ByteBuffer): Int {
        return buf.getInt()
    }

    override fun lower(value: Int): Int {
        return value
    }

    override fun allocationSize(value: Int) = 4UL

    override fun write(value: Int, buf: ByteBuffer) {
        buf.putInt(value)
    }
}

public object FfiConverterULong: FfiConverter<ULong, Long> {
    override fun lift(value: Long): ULong {
        return value.toULong()
    }

    override fun read(buf: ByteBuffer): ULong {
        return lift(buf.getLong())
    }

    override fun lower(value: ULong): Long {
        return value.toLong()
    }

    override fun allocationSize(value: ULong) = 8UL

    override fun write(value: ULong, buf: ByteBuffer) {
        buf.putLong(value.toLong())
    }
}

public object FfiConverterDouble: FfiConverter<Double, Double> {
    override fun lift(value: Double): Double {
        return value
    }

    override fun read(buf: ByteBuffer): Double {
        return buf.getDouble()
    }

    override fun lower(value: Double): Double {
        return value
    }

    override fun allocationSize(value: Double) = 8UL

    override fun write(value: Double, buf: ByteBuffer) {
        buf.putDouble(value)
    }
}

public object FfiConverterBoolean: FfiConverter<Boolean, Byte> {
    override fun lift(value: Byte): Boolean {
        return value.toInt() != 0
    }

    override fun read(buf: ByteBuffer): Boolean {
        return lift(buf.get())
    }

    override fun lower(value: Boolean): Byte {
        return if (value) 1.toByte() else 0.toByte()
    }

    override fun allocationSize(value: Boolean) = 1UL

    override fun write(value: Boolean, buf: ByteBuffer) {
        buf.put(lower(value))
    }
}

public object FfiConverterString: FfiConverter<String, RustBuffer.ByValue> {
    override fun lift(value: RustBuffer.ByValue): String {
        try {
            val byteArr = ByteArray(value.len.toInt())
            value.asByteBuffer()!!.get(byteArr)
            return byteArr.toString(Charsets.UTF_8)
        } finally {
            RustBuffer.free(value)
        }
    }

    override fun read(buf: ByteBuffer): String {
        val len = buf.getInt()
        val byteArr = ByteArray(len)
        buf.get(byteArr)
        return byteArr.toString(Charsets.UTF_8)
    }

    fun toUtf8(value: String): ByteBuffer {
        return Charsets.UTF_8.newEncoder().run {
            onMalformedInput(CodingErrorAction.REPORT)
            encode(CharBuffer.wrap(value))
        }
    }

    override fun lower(value: String): RustBuffer.ByValue {
        val byteBuf = toUtf8(value)
        val rbuf = RustBuffer.alloc(byteBuf.limit().toULong())
        rbuf.asByteBuffer()!!.put(byteBuf)
        return rbuf
    }

    override fun allocationSize(value: String): ULong {
        val sizeForLength = 4UL
        val sizeForString = value.length.toULong() * 3UL
        return sizeForLength + sizeForString
    }

    override fun write(value: String, buf: ByteBuffer) {
        val byteBuf = toUtf8(value)
        buf.putInt(byteBuf.limit())
        buf.put(byteBuf)
    }
}

public object FfiConverterByteArray: FfiConverterRustBuffer<ByteArray> {
    override fun read(buf: ByteBuffer): ByteArray {
        val len = buf.getInt()
        val byteArr = ByteArray(len)
        buf.get(byteArr)
        return byteArr
    }
    override fun allocationSize(value: ByteArray): ULong {
        return 4UL + value.size.toULong()
    }
    override fun write(value: ByteArray, buf: ByteBuffer) {
        buf.putInt(value.size)
        buf.put(value)
    }
}




interface UniffiCleaner {
    interface Cleanable {
        fun clean()
    }

    fun register(value: Any, cleanUpTask: Runnable): UniffiCleaner.Cleanable

    companion object
}

private class UniffiJnaCleaner : UniffiCleaner {
    private val cleaner = com.sun.jna.internal.Cleaner.getCleaner()

    override fun register(value: Any, cleanUpTask: Runnable): UniffiCleaner.Cleanable =
        UniffiJnaCleanable(cleaner.register(value, cleanUpTask))
}

private class UniffiJnaCleanable(
    private val cleanable: com.sun.jna.internal.Cleaner.Cleanable,
) : UniffiCleaner.Cleanable {
    override fun clean() = cleanable.clean()
}


private fun UniffiCleaner.Companion.create(): UniffiCleaner =
    try {
        java.lang.Class.forName("java.lang.ref.Cleaner")
        JavaLangRefCleaner()
    } catch (e: ClassNotFoundException) {
        UniffiJnaCleaner()
    }

private class JavaLangRefCleaner : UniffiCleaner {
    val cleaner = java.lang.ref.Cleaner.create()

    override fun register(value: Any, cleanUpTask: Runnable): UniffiCleaner.Cleanable =
        JavaLangRefCleanable(cleaner.register(value, cleanUpTask))
}

private class JavaLangRefCleanable(
    val cleanable: java.lang.ref.Cleaner.Cleanable
) : UniffiCleaner.Cleanable {
    override fun clean() = cleanable.clean()
}
public interface AddressInterface {
    
    fun `isValidForNetwork`(`network`: Network): kotlin.Boolean
    
    fun `scriptPubkey`(): Script
    
    fun `toQrUri`(): kotlin.String
    
    companion object
}

open class Address: Disposable, AutoCloseable, AddressInterface
{

    constructor(pointer: Pointer) {
        this.pointer = pointer
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }

    @Suppress("UNUSED_PARAMETER")
    constructor(noPointer: NoPointer) {
        this.pointer = null
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }
    constructor(`address`: kotlin.String, `network`: Network) :
        this(
    uniffiRustCallWithError(AddressParseException) { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_constructor_address_new(
        FfiConverterString.lower(`address`),FfiConverterTypeNetwork.lower(`network`),_status)
}
    )

    protected val pointer: Pointer?
    protected val cleanable: UniffiCleaner.Cleanable

    private val wasDestroyed = AtomicBoolean(false)
    private val callCounter = AtomicLong(1)

    override fun destroy() {
        if (this.wasDestroyed.compareAndSet(false, true)) {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.destroy()
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        do {
            val c = this.callCounter.get()
            if (c == 0L) {
                throw IllegalStateException("${this.javaClass.simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this.javaClass.simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        try {
            return block(this.uniffiClonePointer())
        } finally {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    private class UniffiCleanAction(private val pointer: Pointer?) : Runnable {
        override fun run() {
            pointer?.let { ptr ->
                uniffiRustCall { status ->
                    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_free_address(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_clone_address(pointer!!, status)
        }
    }

    override fun `isValidForNetwork`(`network`: Network): kotlin.Boolean {
            return FfiConverterBoolean.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_address_is_valid_for_network(
        it, FfiConverterTypeNetwork.lower(`network`),_status)
}
    }
    )
    }
    

    override fun `scriptPubkey`(): Script {
            return FfiConverterTypeScript.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_address_script_pubkey(
        it, _status)
}
    }
    )
    }
    

    override fun `toQrUri`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_address_to_qr_uri(
        it, _status)
}
    }
    )
    }
    

    

    
    companion object {
        
    @Throws(FromScriptException::class) fun `fromScript`(`script`: Script, `network`: Network): Address {
            return FfiConverterTypeAddress.lift(
    uniffiRustCallWithError(FromScriptException) { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_constructor_address_from_script(
        FfiConverterTypeScript.lower(`script`),FfiConverterTypeNetwork.lower(`network`),_status)
}
    )
    }
    

        
    }
    
}

public object FfiConverterTypeAddress: FfiConverter<Address, Pointer> {

    override fun lower(value: Address): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): Address {
        return Address(value)
    }

    override fun read(buf: ByteBuffer): Address {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: Address) = 8UL

    override fun write(value: Address, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface AmountInterface {
    
    fun `toBtc`(): kotlin.Double
    
    fun `toSat`(): kotlin.ULong
    
    companion object
}

open class Amount: Disposable, AutoCloseable, AmountInterface
{

    constructor(pointer: Pointer) {
        this.pointer = pointer
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }

    @Suppress("UNUSED_PARAMETER")
    constructor(noPointer: NoPointer) {
        this.pointer = null
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }

    protected val pointer: Pointer?
    protected val cleanable: UniffiCleaner.Cleanable

    private val wasDestroyed = AtomicBoolean(false)
    private val callCounter = AtomicLong(1)

    override fun destroy() {
        if (this.wasDestroyed.compareAndSet(false, true)) {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.destroy()
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        do {
            val c = this.callCounter.get()
            if (c == 0L) {
                throw IllegalStateException("${this.javaClass.simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this.javaClass.simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        try {
            return block(this.uniffiClonePointer())
        } finally {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    private class UniffiCleanAction(private val pointer: Pointer?) : Runnable {
        override fun run() {
            pointer?.let { ptr ->
                uniffiRustCall { status ->
                    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_free_amount(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_clone_amount(pointer!!, status)
        }
    }

    override fun `toBtc`(): kotlin.Double {
            return FfiConverterDouble.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_amount_to_btc(
        it, _status)
}
    }
    )
    }
    

    override fun `toSat`(): kotlin.ULong {
            return FfiConverterULong.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_amount_to_sat(
        it, _status)
}
    }
    )
    }
    

    

    
    companion object {
        
    @Throws(ParseAmountException::class) fun `fromBtc`(`btc`: kotlin.Double): Amount {
            return FfiConverterTypeAmount.lift(
    uniffiRustCallWithError(ParseAmountException) { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_constructor_amount_from_btc(
        FfiConverterDouble.lower(`btc`),_status)
}
    )
    }
    

         fun `fromSat`(`sat`: kotlin.ULong): Amount {
            return FfiConverterTypeAmount.lift(
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_constructor_amount_from_sat(
        FfiConverterULong.lower(`sat`),_status)
}
    )
    }
    

        
    }
    
}

public object FfiConverterTypeAmount: FfiConverter<Amount, Pointer> {

    override fun lower(value: Amount): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): Amount {
        return Amount(value)
    }

    override fun read(buf: ByteBuffer): Amount {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: Amount) = 8UL

    override fun write(value: Amount, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface FeeRateInterface {
    
    fun `toSatPerKwu`(): kotlin.ULong
    
    fun `toSatPerVbCeil`(): kotlin.ULong
    
    fun `toSatPerVbFloor`(): kotlin.ULong
    
    companion object
}

open class FeeRate: Disposable, AutoCloseable, FeeRateInterface
{

    constructor(pointer: Pointer) {
        this.pointer = pointer
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }

    @Suppress("UNUSED_PARAMETER")
    constructor(noPointer: NoPointer) {
        this.pointer = null
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }

    protected val pointer: Pointer?
    protected val cleanable: UniffiCleaner.Cleanable

    private val wasDestroyed = AtomicBoolean(false)
    private val callCounter = AtomicLong(1)

    override fun destroy() {
        if (this.wasDestroyed.compareAndSet(false, true)) {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.destroy()
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        do {
            val c = this.callCounter.get()
            if (c == 0L) {
                throw IllegalStateException("${this.javaClass.simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this.javaClass.simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        try {
            return block(this.uniffiClonePointer())
        } finally {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    private class UniffiCleanAction(private val pointer: Pointer?) : Runnable {
        override fun run() {
            pointer?.let { ptr ->
                uniffiRustCall { status ->
                    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_free_feerate(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_clone_feerate(pointer!!, status)
        }
    }

    override fun `toSatPerKwu`(): kotlin.ULong {
            return FfiConverterULong.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_feerate_to_sat_per_kwu(
        it, _status)
}
    }
    )
    }
    

    override fun `toSatPerVbCeil`(): kotlin.ULong {
            return FfiConverterULong.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_feerate_to_sat_per_vb_ceil(
        it, _status)
}
    }
    )
    }
    

    override fun `toSatPerVbFloor`(): kotlin.ULong {
            return FfiConverterULong.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_feerate_to_sat_per_vb_floor(
        it, _status)
}
    }
    )
    }
    

    

    
    companion object {
         fun `fromSatPerKwu`(`satPerKwu`: kotlin.ULong): FeeRate {
            return FfiConverterTypeFeeRate.lift(
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_constructor_feerate_from_sat_per_kwu(
        FfiConverterULong.lower(`satPerKwu`),_status)
}
    )
    }
    

        
    @Throws(FeeRateException::class) fun `fromSatPerVb`(`satPerVb`: kotlin.ULong): FeeRate {
            return FfiConverterTypeFeeRate.lift(
    uniffiRustCallWithError(FeeRateException) { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_constructor_feerate_from_sat_per_vb(
        FfiConverterULong.lower(`satPerVb`),_status)
}
    )
    }
    

        
    }
    
}

public object FfiConverterTypeFeeRate: FfiConverter<FeeRate, Pointer> {

    override fun lower(value: FeeRate): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): FeeRate {
        return FeeRate(value)
    }

    override fun read(buf: ByteBuffer): FeeRate {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: FeeRate) = 8UL

    override fun write(value: FeeRate, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface ScriptInterface {
    
    fun `toBytes`(): kotlin.ByteArray
    
    companion object
}

open class Script: Disposable, AutoCloseable, ScriptInterface
{

    constructor(pointer: Pointer) {
        this.pointer = pointer
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }

    @Suppress("UNUSED_PARAMETER")
    constructor(noPointer: NoPointer) {
        this.pointer = null
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }
    constructor(`rawOutputScript`: kotlin.ByteArray) :
        this(
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_constructor_script_new(
        FfiConverterByteArray.lower(`rawOutputScript`),_status)
}
    )

    protected val pointer: Pointer?
    protected val cleanable: UniffiCleaner.Cleanable

    private val wasDestroyed = AtomicBoolean(false)
    private val callCounter = AtomicLong(1)

    override fun destroy() {
        if (this.wasDestroyed.compareAndSet(false, true)) {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.destroy()
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        do {
            val c = this.callCounter.get()
            if (c == 0L) {
                throw IllegalStateException("${this.javaClass.simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this.javaClass.simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        try {
            return block(this.uniffiClonePointer())
        } finally {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    private class UniffiCleanAction(private val pointer: Pointer?) : Runnable {
        override fun run() {
            pointer?.let { ptr ->
                uniffiRustCall { status ->
                    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_free_script(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_clone_script(pointer!!, status)
        }
    }

    override fun `toBytes`(): kotlin.ByteArray {
            return FfiConverterByteArray.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_script_to_bytes(
        it, _status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeScript: FfiConverter<Script, Pointer> {

    override fun lower(value: Script): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): Script {
        return Script(value)
    }

    override fun read(buf: ByteBuffer): Script {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: Script) = 8UL

    override fun write(value: Script, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface TransactionInterface {
    
    fun `computeTxid`(): kotlin.String
    
    fun `input`(): List<TxIn>
    
    fun `isCoinbase`(): kotlin.Boolean
    
    fun `isExplicitlyRbf`(): kotlin.Boolean
    
    fun `isLockTimeEnabled`(): kotlin.Boolean
    
    fun `lockTime`(): kotlin.UInt
    
    fun `output`(): List<TxOut>
    
    fun `serialize`(): kotlin.ByteArray
    
    fun `totalSize`(): kotlin.ULong
    
    fun `version`(): kotlin.Int
    
    fun `vsize`(): kotlin.ULong
    
    fun `weight`(): kotlin.ULong
    
    companion object
}

open class Transaction: Disposable, AutoCloseable, TransactionInterface
{

    constructor(pointer: Pointer) {
        this.pointer = pointer
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }

    @Suppress("UNUSED_PARAMETER")
    constructor(noPointer: NoPointer) {
        this.pointer = null
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiCleanAction(pointer))
    }

    protected val pointer: Pointer?
    protected val cleanable: UniffiCleaner.Cleanable

    private val wasDestroyed = AtomicBoolean(false)
    private val callCounter = AtomicLong(1)

    override fun destroy() {
        if (this.wasDestroyed.compareAndSet(false, true)) {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.destroy()
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        do {
            val c = this.callCounter.get()
            if (c == 0L) {
                throw IllegalStateException("${this.javaClass.simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this.javaClass.simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        try {
            return block(this.uniffiClonePointer())
        } finally {
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    private class UniffiCleanAction(private val pointer: Pointer?) : Runnable {
        override fun run() {
            pointer?.let { ptr ->
                uniffiRustCall { status ->
                    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_free_transaction(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_clone_transaction(pointer!!, status)
        }
    }

    override fun `computeTxid`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_compute_txid(
        it, _status)
}
    }
    )
    }
    

    override fun `input`(): List<TxIn> {
            return FfiConverterSequenceTypeTxIn.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_input(
        it, _status)
}
    }
    )
    }
    

    override fun `isCoinbase`(): kotlin.Boolean {
            return FfiConverterBoolean.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_is_coinbase(
        it, _status)
}
    }
    )
    }
    

    override fun `isExplicitlyRbf`(): kotlin.Boolean {
            return FfiConverterBoolean.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_is_explicitly_rbf(
        it, _status)
}
    }
    )
    }
    

    override fun `isLockTimeEnabled`(): kotlin.Boolean {
            return FfiConverterBoolean.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_is_lock_time_enabled(
        it, _status)
}
    }
    )
    }
    

    override fun `lockTime`(): kotlin.UInt {
            return FfiConverterUInt.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_lock_time(
        it, _status)
}
    }
    )
    }
    

    override fun `output`(): List<TxOut> {
            return FfiConverterSequenceTypeTxOut.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_output(
        it, _status)
}
    }
    )
    }
    

    override fun `serialize`(): kotlin.ByteArray {
            return FfiConverterByteArray.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_serialize(
        it, _status)
}
    }
    )
    }
    

    override fun `totalSize`(): kotlin.ULong {
            return FfiConverterULong.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_total_size(
        it, _status)
}
    }
    )
    }
    

    override fun `version`(): kotlin.Int {
            return FfiConverterInt.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_version(
        it, _status)
}
    }
    )
    }
    

    override fun `vsize`(): kotlin.ULong {
            return FfiConverterULong.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_vsize(
        it, _status)
}
    }
    )
    }
    

    override fun `weight`(): kotlin.ULong {
            return FfiConverterULong.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_method_transaction_weight(
        it, _status)
}
    }
    )
    }
    

    

    
    companion object {
        
    @Throws(EncodeException::class) fun `deserialize`(`transactionBytes`: kotlin.ByteArray): Transaction {
            return FfiConverterTypeTransaction.lift(
    uniffiRustCallWithError(EncodeException) { _status ->
    UniffiLib.INSTANCE.uniffi_bitcoin_ffi_fn_constructor_transaction_deserialize(
        FfiConverterByteArray.lower(`transactionBytes`),_status)
}
    )
    }
    

        
    }
    
}

public object FfiConverterTypeTransaction: FfiConverter<Transaction, Pointer> {

    override fun lower(value: Transaction): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): Transaction {
        return Transaction(value)
    }

    override fun read(buf: ByteBuffer): Transaction {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: Transaction) = 8UL

    override fun write(value: Transaction, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}



data class OutPoint (
    var `txid`: Txid, 
    var `vout`: kotlin.UInt
) {
    
    companion object
}

public object FfiConverterTypeOutPoint: FfiConverterRustBuffer<OutPoint> {
    override fun read(buf: ByteBuffer): OutPoint {
        return OutPoint(
            FfiConverterTypeTxid.read(buf),
            FfiConverterUInt.read(buf),
        )
    }

    override fun allocationSize(value: OutPoint) = (
            FfiConverterTypeTxid.allocationSize(value.`txid`) +
            FfiConverterUInt.allocationSize(value.`vout`)
    )

    override fun write(value: OutPoint, buf: ByteBuffer) {
            FfiConverterTypeTxid.write(value.`txid`, buf)
            FfiConverterUInt.write(value.`vout`, buf)
    }
}



data class TxIn (
    var `previousOutput`: OutPoint, 
    var `scriptSig`: Script, 
    var `sequence`: kotlin.UInt, 
    var `witness`: List<kotlin.ByteArray>
) : Disposable {
    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        
    Disposable.destroy(
        this.`previousOutput`,
        this.`scriptSig`,
        this.`sequence`,
        this.`witness`
    )
    }
    
    companion object
}

public object FfiConverterTypeTxIn: FfiConverterRustBuffer<TxIn> {
    override fun read(buf: ByteBuffer): TxIn {
        return TxIn(
            FfiConverterTypeOutPoint.read(buf),
            FfiConverterTypeScript.read(buf),
            FfiConverterUInt.read(buf),
            FfiConverterSequenceByteArray.read(buf),
        )
    }

    override fun allocationSize(value: TxIn) = (
            FfiConverterTypeOutPoint.allocationSize(value.`previousOutput`) +
            FfiConverterTypeScript.allocationSize(value.`scriptSig`) +
            FfiConverterUInt.allocationSize(value.`sequence`) +
            FfiConverterSequenceByteArray.allocationSize(value.`witness`)
    )

    override fun write(value: TxIn, buf: ByteBuffer) {
            FfiConverterTypeOutPoint.write(value.`previousOutput`, buf)
            FfiConverterTypeScript.write(value.`scriptSig`, buf)
            FfiConverterUInt.write(value.`sequence`, buf)
            FfiConverterSequenceByteArray.write(value.`witness`, buf)
    }
}



data class TxOut (
    var `value`: Amount, 
    var `scriptPubkey`: Script
) : Disposable {
    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        
    Disposable.destroy(
        this.`value`,
        this.`scriptPubkey`
    )
    }
    
    companion object
}

public object FfiConverterTypeTxOut: FfiConverterRustBuffer<TxOut> {
    override fun read(buf: ByteBuffer): TxOut {
        return TxOut(
            FfiConverterTypeAmount.read(buf),
            FfiConverterTypeScript.read(buf),
        )
    }

    override fun allocationSize(value: TxOut) = (
            FfiConverterTypeAmount.allocationSize(value.`value`) +
            FfiConverterTypeScript.allocationSize(value.`scriptPubkey`)
    )

    override fun write(value: TxOut, buf: ByteBuffer) {
            FfiConverterTypeAmount.write(value.`value`, buf)
            FfiConverterTypeScript.write(value.`scriptPubkey`, buf)
    }
}





sealed class AddressParseException: kotlin.Exception() {
    
    class Base58(
        ) : AddressParseException() {
        override val message
            get() = ""
    }
    
    class Bech32(
        ) : AddressParseException() {
        override val message
            get() = ""
    }
    
    class WitnessVersion(
        
        val `errorMessage`: kotlin.String
        ) : AddressParseException() {
        override val message
            get() = "errorMessage=${ `errorMessage` }"
    }
    
    class WitnessProgram(
        
        val `errorMessage`: kotlin.String
        ) : AddressParseException() {
        override val message
            get() = "errorMessage=${ `errorMessage` }"
    }
    
    class UnknownHrp(
        ) : AddressParseException() {
        override val message
            get() = ""
    }
    
    class LegacyAddressTooLong(
        ) : AddressParseException() {
        override val message
            get() = ""
    }
    
    class InvalidBase58PayloadLength(
        ) : AddressParseException() {
        override val message
            get() = ""
    }
    
    class InvalidLegacyPrefix(
        ) : AddressParseException() {
        override val message
            get() = ""
    }
    
    class NetworkValidation(
        ) : AddressParseException() {
        override val message
            get() = ""
    }
    
    class OtherAddressParseErr(
        ) : AddressParseException() {
        override val message
            get() = ""
    }
    

    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<AddressParseException> {
        override fun lift(error_buf: RustBuffer.ByValue): AddressParseException = FfiConverterTypeAddressParseError.lift(error_buf)
    }

    
}

public object FfiConverterTypeAddressParseError : FfiConverterRustBuffer<AddressParseException> {
    override fun read(buf: ByteBuffer): AddressParseException {
        

        return when(buf.getInt()) {
            1 -> AddressParseException.Base58()
            2 -> AddressParseException.Bech32()
            3 -> AddressParseException.WitnessVersion(
                FfiConverterString.read(buf),
                )
            4 -> AddressParseException.WitnessProgram(
                FfiConverterString.read(buf),
                )
            5 -> AddressParseException.UnknownHrp()
            6 -> AddressParseException.LegacyAddressTooLong()
            7 -> AddressParseException.InvalidBase58PayloadLength()
            8 -> AddressParseException.InvalidLegacyPrefix()
            9 -> AddressParseException.NetworkValidation()
            10 -> AddressParseException.OtherAddressParseErr()
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: AddressParseException): ULong {
        return when(value) {
            is AddressParseException.Base58 -> (
                4UL
            )
            is AddressParseException.Bech32 -> (
                4UL
            )
            is AddressParseException.WitnessVersion -> (
                4UL
                + FfiConverterString.allocationSize(value.`errorMessage`)
            )
            is AddressParseException.WitnessProgram -> (
                4UL
                + FfiConverterString.allocationSize(value.`errorMessage`)
            )
            is AddressParseException.UnknownHrp -> (
                4UL
            )
            is AddressParseException.LegacyAddressTooLong -> (
                4UL
            )
            is AddressParseException.InvalidBase58PayloadLength -> (
                4UL
            )
            is AddressParseException.InvalidLegacyPrefix -> (
                4UL
            )
            is AddressParseException.NetworkValidation -> (
                4UL
            )
            is AddressParseException.OtherAddressParseErr -> (
                4UL
            )
        }
    }

    override fun write(value: AddressParseException, buf: ByteBuffer) {
        when(value) {
            is AddressParseException.Base58 -> {
                buf.putInt(1)
                Unit
            }
            is AddressParseException.Bech32 -> {
                buf.putInt(2)
                Unit
            }
            is AddressParseException.WitnessVersion -> {
                buf.putInt(3)
                FfiConverterString.write(value.`errorMessage`, buf)
                Unit
            }
            is AddressParseException.WitnessProgram -> {
                buf.putInt(4)
                FfiConverterString.write(value.`errorMessage`, buf)
                Unit
            }
            is AddressParseException.UnknownHrp -> {
                buf.putInt(5)
                Unit
            }
            is AddressParseException.LegacyAddressTooLong -> {
                buf.putInt(6)
                Unit
            }
            is AddressParseException.InvalidBase58PayloadLength -> {
                buf.putInt(7)
                Unit
            }
            is AddressParseException.InvalidLegacyPrefix -> {
                buf.putInt(8)
                Unit
            }
            is AddressParseException.NetworkValidation -> {
                buf.putInt(9)
                Unit
            }
            is AddressParseException.OtherAddressParseErr -> {
                buf.putInt(10)
                Unit
            }
        }.let {  }
    }

}





sealed class EncodeException: kotlin.Exception() {
    
    class Io(
        ) : EncodeException() {
        override val message
            get() = ""
    }
    
    class OversizedVectorAllocation(
        ) : EncodeException() {
        override val message
            get() = ""
    }
    
    class InvalidChecksum(
        
        val `expected`: kotlin.String, 
        
        val `actual`: kotlin.String
        ) : EncodeException() {
        override val message
            get() = "expected=${ `expected` }, actual=${ `actual` }"
    }
    
    class NonMinimalVarInt(
        ) : EncodeException() {
        override val message
            get() = ""
    }
    
    class ParseFailed(
        ) : EncodeException() {
        override val message
            get() = ""
    }
    
    class UnsupportedSegwitFlag(
        
        val `flag`: kotlin.UByte
        ) : EncodeException() {
        override val message
            get() = "flag=${ `flag` }"
    }
    
    class OtherEncodeErr(
        ) : EncodeException() {
        override val message
            get() = ""
    }
    

    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<EncodeException> {
        override fun lift(error_buf: RustBuffer.ByValue): EncodeException = FfiConverterTypeEncodeError.lift(error_buf)
    }

    
}

public object FfiConverterTypeEncodeError : FfiConverterRustBuffer<EncodeException> {
    override fun read(buf: ByteBuffer): EncodeException {
        

        return when(buf.getInt()) {
            1 -> EncodeException.Io()
            2 -> EncodeException.OversizedVectorAllocation()
            3 -> EncodeException.InvalidChecksum(
                FfiConverterString.read(buf),
                FfiConverterString.read(buf),
                )
            4 -> EncodeException.NonMinimalVarInt()
            5 -> EncodeException.ParseFailed()
            6 -> EncodeException.UnsupportedSegwitFlag(
                FfiConverterUByte.read(buf),
                )
            7 -> EncodeException.OtherEncodeErr()
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: EncodeException): ULong {
        return when(value) {
            is EncodeException.Io -> (
                4UL
            )
            is EncodeException.OversizedVectorAllocation -> (
                4UL
            )
            is EncodeException.InvalidChecksum -> (
                4UL
                + FfiConverterString.allocationSize(value.`expected`)
                + FfiConverterString.allocationSize(value.`actual`)
            )
            is EncodeException.NonMinimalVarInt -> (
                4UL
            )
            is EncodeException.ParseFailed -> (
                4UL
            )
            is EncodeException.UnsupportedSegwitFlag -> (
                4UL
                + FfiConverterUByte.allocationSize(value.`flag`)
            )
            is EncodeException.OtherEncodeErr -> (
                4UL
            )
        }
    }

    override fun write(value: EncodeException, buf: ByteBuffer) {
        when(value) {
            is EncodeException.Io -> {
                buf.putInt(1)
                Unit
            }
            is EncodeException.OversizedVectorAllocation -> {
                buf.putInt(2)
                Unit
            }
            is EncodeException.InvalidChecksum -> {
                buf.putInt(3)
                FfiConverterString.write(value.`expected`, buf)
                FfiConverterString.write(value.`actual`, buf)
                Unit
            }
            is EncodeException.NonMinimalVarInt -> {
                buf.putInt(4)
                Unit
            }
            is EncodeException.ParseFailed -> {
                buf.putInt(5)
                Unit
            }
            is EncodeException.UnsupportedSegwitFlag -> {
                buf.putInt(6)
                FfiConverterUByte.write(value.`flag`, buf)
                Unit
            }
            is EncodeException.OtherEncodeErr -> {
                buf.putInt(7)
                Unit
            }
        }.let {  }
    }

}





sealed class FeeRateException: kotlin.Exception() {
    
    class ArithmeticOverflow(
        ) : FeeRateException() {
        override val message
            get() = ""
    }
    

    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<FeeRateException> {
        override fun lift(error_buf: RustBuffer.ByValue): FeeRateException = FfiConverterTypeFeeRateError.lift(error_buf)
    }

    
}

public object FfiConverterTypeFeeRateError : FfiConverterRustBuffer<FeeRateException> {
    override fun read(buf: ByteBuffer): FeeRateException {
        

        return when(buf.getInt()) {
            1 -> FeeRateException.ArithmeticOverflow()
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: FeeRateException): ULong {
        return when(value) {
            is FeeRateException.ArithmeticOverflow -> (
                4UL
            )
        }
    }

    override fun write(value: FeeRateException, buf: ByteBuffer) {
        when(value) {
            is FeeRateException.ArithmeticOverflow -> {
                buf.putInt(1)
                Unit
            }
        }.let {  }
    }

}





sealed class FromScriptException: kotlin.Exception() {
    
    class UnrecognizedScript(
        ) : FromScriptException() {
        override val message
            get() = ""
    }
    
    class WitnessProgram(
        
        val `errorMessage`: kotlin.String
        ) : FromScriptException() {
        override val message
            get() = "errorMessage=${ `errorMessage` }"
    }
    
    class WitnessVersion(
        
        val `errorMessage`: kotlin.String
        ) : FromScriptException() {
        override val message
            get() = "errorMessage=${ `errorMessage` }"
    }
    
    class OtherFromScriptErr(
        ) : FromScriptException() {
        override val message
            get() = ""
    }
    

    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<FromScriptException> {
        override fun lift(error_buf: RustBuffer.ByValue): FromScriptException = FfiConverterTypeFromScriptError.lift(error_buf)
    }

    
}

public object FfiConverterTypeFromScriptError : FfiConverterRustBuffer<FromScriptException> {
    override fun read(buf: ByteBuffer): FromScriptException {
        

        return when(buf.getInt()) {
            1 -> FromScriptException.UnrecognizedScript()
            2 -> FromScriptException.WitnessProgram(
                FfiConverterString.read(buf),
                )
            3 -> FromScriptException.WitnessVersion(
                FfiConverterString.read(buf),
                )
            4 -> FromScriptException.OtherFromScriptErr()
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: FromScriptException): ULong {
        return when(value) {
            is FromScriptException.UnrecognizedScript -> (
                4UL
            )
            is FromScriptException.WitnessProgram -> (
                4UL
                + FfiConverterString.allocationSize(value.`errorMessage`)
            )
            is FromScriptException.WitnessVersion -> (
                4UL
                + FfiConverterString.allocationSize(value.`errorMessage`)
            )
            is FromScriptException.OtherFromScriptErr -> (
                4UL
            )
        }
    }

    override fun write(value: FromScriptException, buf: ByteBuffer) {
        when(value) {
            is FromScriptException.UnrecognizedScript -> {
                buf.putInt(1)
                Unit
            }
            is FromScriptException.WitnessProgram -> {
                buf.putInt(2)
                FfiConverterString.write(value.`errorMessage`, buf)
                Unit
            }
            is FromScriptException.WitnessVersion -> {
                buf.putInt(3)
                FfiConverterString.write(value.`errorMessage`, buf)
                Unit
            }
            is FromScriptException.OtherFromScriptErr -> {
                buf.putInt(4)
                Unit
            }
        }.let {  }
    }

}




enum class Network {
    
    BITCOIN,
    TESTNET,
    TESTNET4,
    SIGNET,
    REGTEST;
    companion object
}


public object FfiConverterTypeNetwork: FfiConverterRustBuffer<Network> {
    override fun read(buf: ByteBuffer) = try {
        Network.values()[buf.getInt() - 1]
    } catch (e: IndexOutOfBoundsException) {
        throw RuntimeException("invalid enum value, something is very wrong!!", e)
    }

    override fun allocationSize(value: Network) = 4UL

    override fun write(value: Network, buf: ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}







sealed class ParseAmountException: kotlin.Exception() {
    
    class OutOfRange(
        ) : ParseAmountException() {
        override val message
            get() = ""
    }
    
    class TooPrecise(
        ) : ParseAmountException() {
        override val message
            get() = ""
    }
    
    class MissingDigits(
        ) : ParseAmountException() {
        override val message
            get() = ""
    }
    
    class InputTooLarge(
        ) : ParseAmountException() {
        override val message
            get() = ""
    }
    
    class InvalidCharacter(
        
        val `errorMessage`: kotlin.String
        ) : ParseAmountException() {
        override val message
            get() = "errorMessage=${ `errorMessage` }"
    }
    
    class OtherParseAmountErr(
        ) : ParseAmountException() {
        override val message
            get() = ""
    }
    

    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<ParseAmountException> {
        override fun lift(error_buf: RustBuffer.ByValue): ParseAmountException = FfiConverterTypeParseAmountError.lift(error_buf)
    }

    
}

public object FfiConverterTypeParseAmountError : FfiConverterRustBuffer<ParseAmountException> {
    override fun read(buf: ByteBuffer): ParseAmountException {
        

        return when(buf.getInt()) {
            1 -> ParseAmountException.OutOfRange()
            2 -> ParseAmountException.TooPrecise()
            3 -> ParseAmountException.MissingDigits()
            4 -> ParseAmountException.InputTooLarge()
            5 -> ParseAmountException.InvalidCharacter(
                FfiConverterString.read(buf),
                )
            6 -> ParseAmountException.OtherParseAmountErr()
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: ParseAmountException): ULong {
        return when(value) {
            is ParseAmountException.OutOfRange -> (
                4UL
            )
            is ParseAmountException.TooPrecise -> (
                4UL
            )
            is ParseAmountException.MissingDigits -> (
                4UL
            )
            is ParseAmountException.InputTooLarge -> (
                4UL
            )
            is ParseAmountException.InvalidCharacter -> (
                4UL
                + FfiConverterString.allocationSize(value.`errorMessage`)
            )
            is ParseAmountException.OtherParseAmountErr -> (
                4UL
            )
        }
    }

    override fun write(value: ParseAmountException, buf: ByteBuffer) {
        when(value) {
            is ParseAmountException.OutOfRange -> {
                buf.putInt(1)
                Unit
            }
            is ParseAmountException.TooPrecise -> {
                buf.putInt(2)
                Unit
            }
            is ParseAmountException.MissingDigits -> {
                buf.putInt(3)
                Unit
            }
            is ParseAmountException.InputTooLarge -> {
                buf.putInt(4)
                Unit
            }
            is ParseAmountException.InvalidCharacter -> {
                buf.putInt(5)
                FfiConverterString.write(value.`errorMessage`, buf)
                Unit
            }
            is ParseAmountException.OtherParseAmountErr -> {
                buf.putInt(6)
                Unit
            }
        }.let {  }
    }

}




public object FfiConverterSequenceByteArray: FfiConverterRustBuffer<List<kotlin.ByteArray>> {
    override fun read(buf: ByteBuffer): List<kotlin.ByteArray> {
        val len = buf.getInt()
        return List<kotlin.ByteArray>(len) {
            FfiConverterByteArray.read(buf)
        }
    }

    override fun allocationSize(value: List<kotlin.ByteArray>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.map { FfiConverterByteArray.allocationSize(it) }.sum()
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<kotlin.ByteArray>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterByteArray.write(it, buf)
        }
    }
}




public object FfiConverterSequenceTypeTxIn: FfiConverterRustBuffer<List<TxIn>> {
    override fun read(buf: ByteBuffer): List<TxIn> {
        val len = buf.getInt()
        return List<TxIn>(len) {
            FfiConverterTypeTxIn.read(buf)
        }
    }

    override fun allocationSize(value: List<TxIn>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.map { FfiConverterTypeTxIn.allocationSize(it) }.sum()
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<TxIn>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterTypeTxIn.write(it, buf)
        }
    }
}




public object FfiConverterSequenceTypeTxOut: FfiConverterRustBuffer<List<TxOut>> {
    override fun read(buf: ByteBuffer): List<TxOut> {
        val len = buf.getInt()
        return List<TxOut>(len) {
            FfiConverterTypeTxOut.read(buf)
        }
    }

    override fun allocationSize(value: List<TxOut>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.map { FfiConverterTypeTxOut.allocationSize(it) }.sum()
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<TxOut>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterTypeTxOut.write(it, buf)
        }
    }
}



public typealias BlockHash = kotlin.String
public typealias FfiConverterTypeBlockHash = FfiConverterString



public typealias Txid = kotlin.String
public typealias FfiConverterTypeTxid = FfiConverterString

