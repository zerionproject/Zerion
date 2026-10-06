
@file:Suppress("NAME_SHADOWING")

package org.payjoindevkit


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
import org.bitcoindevkit.bitcoinffi.Address
import org.bitcoindevkit.bitcoinffi.FfiConverterTypeAddress
import org.bitcoindevkit.bitcoinffi.FfiConverterTypeOutPoint
import org.bitcoindevkit.bitcoinffi.FfiConverterTypeScript
import org.bitcoindevkit.bitcoinffi.FfiConverterTypeTxIn
import org.bitcoindevkit.bitcoinffi.FfiConverterTypeTxOut
import org.bitcoindevkit.bitcoinffi.OutPoint
import org.bitcoindevkit.bitcoinffi.Script
import org.bitcoindevkit.bitcoinffi.TxIn
import org.bitcoindevkit.bitcoinffi.TxOut
import org.bitcoindevkit.bitcoinffi.RustBuffer as RustBufferAddress
import org.bitcoindevkit.bitcoinffi.RustBuffer as RustBufferOutPoint
import org.bitcoindevkit.bitcoinffi.RustBuffer as RustBufferScript
import org.bitcoindevkit.bitcoinffi.RustBuffer as RustBufferTxIn
import org.bitcoindevkit.bitcoinffi.RustBuffer as RustBufferTxOut


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
            UniffiLib.INSTANCE.ffi_payjoin_ffi_rustbuffer_alloc(size.toLong(), status)
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
            UniffiLib.INSTANCE.ffi_payjoin_ffi_rustbuffer_free(buf, status)
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
internal interface UniffiCallbackInterfaceCanBroadcastMethod0 : com.sun.jna.Callback {
    fun callback(`uniffiHandle`: Long,`tx`: RustBuffer.ByValue,`uniffiOutReturn`: ByteByReference,uniffiCallStatus: UniffiRustCallStatus,)
}
internal interface UniffiCallbackInterfaceIsOutputKnownMethod0 : com.sun.jna.Callback {
    fun callback(`uniffiHandle`: Long,`outpoint`: RustBufferOutPoint.ByValue,`uniffiOutReturn`: ByteByReference,uniffiCallStatus: UniffiRustCallStatus,)
}
internal interface UniffiCallbackInterfaceIsScriptOwnedMethod0 : com.sun.jna.Callback {
    fun callback(`uniffiHandle`: Long,`script`: RustBuffer.ByValue,`uniffiOutReturn`: ByteByReference,uniffiCallStatus: UniffiRustCallStatus,)
}
internal interface UniffiCallbackInterfaceProcessPsbtMethod0 : com.sun.jna.Callback {
    fun callback(`uniffiHandle`: Long,`psbt`: RustBuffer.ByValue,`uniffiOutReturn`: RustBuffer,uniffiCallStatus: UniffiRustCallStatus,)
}
@Structure.FieldOrder("callback", "uniffiFree")
internal open class UniffiVTableCallbackInterfaceCanBroadcast(
    @JvmField internal var `callback`: UniffiCallbackInterfaceCanBroadcastMethod0? = null,
    @JvmField internal var `uniffiFree`: UniffiCallbackInterfaceFree? = null,
) : Structure() {
    class UniffiByValue(
        `callback`: UniffiCallbackInterfaceCanBroadcastMethod0? = null,
        `uniffiFree`: UniffiCallbackInterfaceFree? = null,
    ): UniffiVTableCallbackInterfaceCanBroadcast(`callback`,`uniffiFree`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiVTableCallbackInterfaceCanBroadcast) {
        `callback` = other.`callback`
        `uniffiFree` = other.`uniffiFree`
    }

}
@Structure.FieldOrder("callback", "uniffiFree")
internal open class UniffiVTableCallbackInterfaceIsOutputKnown(
    @JvmField internal var `callback`: UniffiCallbackInterfaceIsOutputKnownMethod0? = null,
    @JvmField internal var `uniffiFree`: UniffiCallbackInterfaceFree? = null,
) : Structure() {
    class UniffiByValue(
        `callback`: UniffiCallbackInterfaceIsOutputKnownMethod0? = null,
        `uniffiFree`: UniffiCallbackInterfaceFree? = null,
    ): UniffiVTableCallbackInterfaceIsOutputKnown(`callback`,`uniffiFree`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiVTableCallbackInterfaceIsOutputKnown) {
        `callback` = other.`callback`
        `uniffiFree` = other.`uniffiFree`
    }

}
@Structure.FieldOrder("callback", "uniffiFree")
internal open class UniffiVTableCallbackInterfaceIsScriptOwned(
    @JvmField internal var `callback`: UniffiCallbackInterfaceIsScriptOwnedMethod0? = null,
    @JvmField internal var `uniffiFree`: UniffiCallbackInterfaceFree? = null,
) : Structure() {
    class UniffiByValue(
        `callback`: UniffiCallbackInterfaceIsScriptOwnedMethod0? = null,
        `uniffiFree`: UniffiCallbackInterfaceFree? = null,
    ): UniffiVTableCallbackInterfaceIsScriptOwned(`callback`,`uniffiFree`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiVTableCallbackInterfaceIsScriptOwned) {
        `callback` = other.`callback`
        `uniffiFree` = other.`uniffiFree`
    }

}
@Structure.FieldOrder("callback", "uniffiFree")
internal open class UniffiVTableCallbackInterfaceProcessPsbt(
    @JvmField internal var `callback`: UniffiCallbackInterfaceProcessPsbtMethod0? = null,
    @JvmField internal var `uniffiFree`: UniffiCallbackInterfaceFree? = null,
) : Structure() {
    class UniffiByValue(
        `callback`: UniffiCallbackInterfaceProcessPsbtMethod0? = null,
        `uniffiFree`: UniffiCallbackInterfaceFree? = null,
    ): UniffiVTableCallbackInterfaceProcessPsbt(`callback`,`uniffiFree`,), Structure.ByValue

   internal fun uniffiSetValue(other: UniffiVTableCallbackInterfaceProcessPsbt) {
        `callback` = other.`callback`
        `uniffiFree` = other.`uniffiFree`
    }

}


























































































































































































































































internal interface IntegrityCheckingUniffiLib : Library {
    fun uniffi_payjoin_ffi_checksum_method_canbroadcast_callback(
): Short
fun uniffi_payjoin_ffi_checksum_method_isoutputknown_callback(
): Short
fun uniffi_payjoin_ffi_checksum_method_isscriptowned_callback(
): Short
fun uniffi_payjoin_ffi_checksum_method_maybeinputsowned_check_inputs_not_owned(
): Short
fun uniffi_payjoin_ffi_checksum_method_maybeinputsseen_check_no_inputs_seen_before(
): Short
fun uniffi_payjoin_ffi_checksum_method_outputsunknown_identify_receiver_outputs(
): Short
fun uniffi_payjoin_ffi_checksum_method_payjoinproposal_extract_v2_req(
): Short
fun uniffi_payjoin_ffi_checksum_method_payjoinproposal_process_res(
): Short
fun uniffi_payjoin_ffi_checksum_method_payjoinproposal_psbt(
): Short
fun uniffi_payjoin_ffi_checksum_method_payjoinproposal_utxos_to_be_locked(
): Short
fun uniffi_payjoin_ffi_checksum_method_pjuri_address(
): Short
fun uniffi_payjoin_ffi_checksum_method_pjuri_amount_sats(
): Short
fun uniffi_payjoin_ffi_checksum_method_pjuri_as_string(
): Short
fun uniffi_payjoin_ffi_checksum_method_pjuri_pj_endpoint(
): Short
fun uniffi_payjoin_ffi_checksum_method_processpsbt_callback(
): Short
fun uniffi_payjoin_ffi_checksum_method_provisionalproposal_finalize_proposal(
): Short
fun uniffi_payjoin_ffi_checksum_method_receiver_extract_req(
): Short
fun uniffi_payjoin_ffi_checksum_method_receiver_id(
): Short
fun uniffi_payjoin_ffi_checksum_method_receiver_pj_uri(
): Short
fun uniffi_payjoin_ffi_checksum_method_receiver_process_res(
): Short
fun uniffi_payjoin_ffi_checksum_method_receiver_to_json(
): Short
fun uniffi_payjoin_ffi_checksum_method_sender_extract_v1(
): Short
fun uniffi_payjoin_ffi_checksum_method_sender_extract_v2(
): Short
fun uniffi_payjoin_ffi_checksum_method_sender_to_json(
): Short
fun uniffi_payjoin_ffi_checksum_method_senderbuilder_always_disable_output_substitution(
): Short
fun uniffi_payjoin_ffi_checksum_method_senderbuilder_build_non_incentivizing(
): Short
fun uniffi_payjoin_ffi_checksum_method_senderbuilder_build_recommended(
): Short
fun uniffi_payjoin_ffi_checksum_method_senderbuilder_build_with_additional_fee(
): Short
fun uniffi_payjoin_ffi_checksum_method_uncheckedproposal_assume_interactive_receiver(
): Short
fun uniffi_payjoin_ffi_checksum_method_uncheckedproposal_check_broadcast_suitability(
): Short
fun uniffi_payjoin_ffi_checksum_method_uncheckedproposal_extract_err_req(
): Short
fun uniffi_payjoin_ffi_checksum_method_uncheckedproposal_extract_tx_to_schedule_broadcast(
): Short
fun uniffi_payjoin_ffi_checksum_method_uncheckedproposal_process_err_res(
): Short
fun uniffi_payjoin_ffi_checksum_method_url_as_string(
): Short
fun uniffi_payjoin_ffi_checksum_method_url_query(
): Short
fun uniffi_payjoin_ffi_checksum_method_v1context_process_response(
): Short
fun uniffi_payjoin_ffi_checksum_method_v2getcontext_extract_req(
): Short
fun uniffi_payjoin_ffi_checksum_method_v2getcontext_process_response(
): Short
fun uniffi_payjoin_ffi_checksum_method_v2postcontext_process_response(
): Short
fun uniffi_payjoin_ffi_checksum_method_wantsinputs_commit_inputs(
): Short
fun uniffi_payjoin_ffi_checksum_method_wantsinputs_contribute_inputs(
): Short
fun uniffi_payjoin_ffi_checksum_method_wantsinputs_try_preserving_privacy(
): Short
fun uniffi_payjoin_ffi_checksum_method_wantsoutputs_commit_outputs(
): Short
fun uniffi_payjoin_ffi_checksum_method_wantsoutputs_output_substitution(
): Short
fun uniffi_payjoin_ffi_checksum_method_wantsoutputs_replace_receiver_outputs(
): Short
fun uniffi_payjoin_ffi_checksum_method_wantsoutputs_substitute_receiver_script(
): Short
fun uniffi_payjoin_ffi_checksum_constructor_inputpair_new(
): Short
fun uniffi_payjoin_ffi_checksum_constructor_ohttpkeys_decode(
): Short
fun uniffi_payjoin_ffi_checksum_constructor_receiver_from_json(
): Short
fun uniffi_payjoin_ffi_checksum_constructor_receiver_new(
): Short
fun uniffi_payjoin_ffi_checksum_constructor_sender_from_json(
): Short
fun uniffi_payjoin_ffi_checksum_constructor_senderbuilder_new(
): Short
fun uniffi_payjoin_ffi_checksum_constructor_url_parse(
): Short
fun ffi_payjoin_ffi_uniffi_contract_version(
): Int

}

internal interface UniffiLib : Library {
    companion object {
        internal val INSTANCE: UniffiLib by lazy {
            val componentName = "payjoin_ffi"
            loadIndirect<IntegrityCheckingUniffiLib>(componentName)
                .also { lib: IntegrityCheckingUniffiLib ->
                    uniffiCheckContractApiVersion(lib)
                    uniffiCheckApiChecksums(lib)
                }
            val lib = loadIndirect<UniffiLib>(componentName)
            org.bitcoindevkit.bitcoinffi.uniffiEnsureInitialized()
            lib
        }
        
        internal val CLEANER: UniffiCleaner by lazy {
            UniffiCleaner.create()
        }
    }

    fun uniffi_payjoin_ffi_fn_clone_buildsendererror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_buildsendererror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_canbroadcast(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_canbroadcast(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_canbroadcast_callback(`ptr`: Pointer,`tx`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun uniffi_payjoin_ffi_fn_clone_clientresponse(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_clientresponse(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_createrequesterror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_createrequesterror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_encapsulationerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_encapsulationerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_implementationerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_implementationerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_inputcontributionerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_inputcontributionerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_inputpair(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_inputpair(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_constructor_inputpair_new(`txin`: RustBufferTxIn.ByValue,`psbtin`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_intourlerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_intourlerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_ioerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_ioerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_isoutputknown(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_isoutputknown(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_isoutputknown_callback(`ptr`: Pointer,`outpoint`: RustBufferOutPoint.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun uniffi_payjoin_ffi_fn_clone_isscriptowned(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_isscriptowned(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_isscriptowned_callback(`ptr`: Pointer,`script`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun uniffi_payjoin_ffi_fn_clone_jsonreply(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_jsonreply(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_maybeinputsowned(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_maybeinputsowned(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_maybeinputsowned_check_inputs_not_owned(`ptr`: Pointer,`isOwned`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_maybeinputsseen(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_maybeinputsseen(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_maybeinputsseen_check_no_inputs_seen_before(`ptr`: Pointer,`isKnown`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_ohttperror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_ohttperror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_ohttpkeys(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_ohttpkeys(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_constructor_ohttpkeys_decode(`bytes`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_outputsubstitutionerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_outputsubstitutionerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_outputsunknown(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_outputsunknown(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_outputsunknown_identify_receiver_outputs(`ptr`: Pointer,`isReceiverOutput`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_payjoinproposal(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_payjoinproposal(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_payjoinproposal_extract_v2_req(`ptr`: Pointer,`ohttpRelay`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_payjoinproposal_process_res(`ptr`: Pointer,`body`: RustBuffer.ByValue,`ctx`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_payjoinproposal_psbt(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_payjoinproposal_utxos_to_be_locked(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_clone_pjnotsupported(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_pjnotsupported(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_pjparseerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_pjparseerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_pjuri(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_pjuri(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_pjuri_address(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_pjuri_amount_sats(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_pjuri_as_string(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_pjuri_pj_endpoint(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_clone_processpsbt(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_processpsbt(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_processpsbt_callback(`ptr`: Pointer,`psbt`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_clone_provisionalproposal(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_provisionalproposal(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_provisionalproposal_finalize_proposal(`ptr`: Pointer,`processPsbt`: Pointer,`minFeerateSatPerVb`: RustBuffer.ByValue,`maxEffectiveFeeRateSatPerVb`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_psbtinputerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_psbtinputerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_receiver(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_receiver(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_constructor_receiver_from_json(`json`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_constructor_receiver_new(`address`: Pointer,`directory`: RustBuffer.ByValue,`ohttpKeys`: Pointer,`expireAfter`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_receiver_extract_req(`ptr`: Pointer,`ohttpRelay`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_receiver_id(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_receiver_pj_uri(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_receiver_process_res(`ptr`: Pointer,`body`: RustBuffer.ByValue,`context`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_receiver_to_json(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_clone_replyableerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_replyableerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_selectionerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_selectionerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_sender(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_sender(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_constructor_sender_from_json(`json`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_sender_extract_v1(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_sender_extract_v2(`ptr`: Pointer,`ohttpProxyUrl`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_sender_to_json(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_clone_senderbuilder(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_senderbuilder(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_constructor_senderbuilder_new(`psbt`: RustBuffer.ByValue,`uri`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_senderbuilder_always_disable_output_substitution(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_senderbuilder_build_non_incentivizing(`ptr`: Pointer,`minFeeRate`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_senderbuilder_build_recommended(`ptr`: Pointer,`minFeeRate`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_senderbuilder_build_with_additional_fee(`ptr`: Pointer,`maxFeeContribution`: Long,`changeIndex`: RustBuffer.ByValue,`minFeeRate`: Long,`clampFeeContribution`: Byte,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_serdejsonerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_serdejsonerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_sessionerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_sessionerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_uncheckedproposal(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_uncheckedproposal(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_uncheckedproposal_assume_interactive_receiver(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_uncheckedproposal_check_broadcast_suitability(`ptr`: Pointer,`minFeeRate`: RustBuffer.ByValue,`canBroadcast`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_uncheckedproposal_extract_err_req(`ptr`: Pointer,`err`: Pointer,`ohttpRelay`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_uncheckedproposal_extract_tx_to_schedule_broadcast(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_uncheckedproposal_process_err_res(`ptr`: Pointer,`body`: RustBuffer.ByValue,`context`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_url(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_url(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_constructor_url_parse(`input`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_url_as_string(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_url_query(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_clone_urlparseerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_urlparseerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_v1context(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_v1context(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_v1context_process_response(`ptr`: Pointer,`response`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_clone_v2getcontext(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_v2getcontext(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_v2getcontext_extract_req(`ptr`: Pointer,`ohttpRelay`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_method_v2getcontext_process_response(`ptr`: Pointer,`response`: RustBuffer.ByValue,`ohttpCtx`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun uniffi_payjoin_ffi_fn_clone_v2postcontext(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_v2postcontext(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_v2postcontext_process_response(`ptr`: Pointer,`response`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_validationerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_validationerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_clone_wantsinputs(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_wantsinputs(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_wantsinputs_commit_inputs(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_wantsinputs_contribute_inputs(`ptr`: Pointer,`replacementInputs`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_wantsinputs_try_preserving_privacy(`ptr`: Pointer,`candidateInputs`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_wantsoutputs(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_wantsoutputs(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun uniffi_payjoin_ffi_fn_method_wantsoutputs_commit_outputs(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_wantsoutputs_output_substitution(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun uniffi_payjoin_ffi_fn_method_wantsoutputs_replace_receiver_outputs(`ptr`: Pointer,`replacementOutputs`: RustBuffer.ByValue,`drainScript`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_method_wantsoutputs_substitute_receiver_script(`ptr`: Pointer,`outputScript`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_clone_wellknownerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun uniffi_payjoin_ffi_fn_free_wellknownerror(`ptr`: Pointer,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun ffi_payjoin_ffi_rustbuffer_alloc(`size`: Long,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun ffi_payjoin_ffi_rustbuffer_from_bytes(`bytes`: ForeignBytes.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun ffi_payjoin_ffi_rustbuffer_free(`buf`: RustBuffer.ByValue,uniffi_out_err: UniffiRustCallStatus, 
): Unit
fun ffi_payjoin_ffi_rustbuffer_reserve(`buf`: RustBuffer.ByValue,`additional`: Long,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun ffi_payjoin_ffi_rust_future_poll_u8(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_u8(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_u8(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_u8(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun ffi_payjoin_ffi_rust_future_poll_i8(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_i8(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_i8(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_i8(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Byte
fun ffi_payjoin_ffi_rust_future_poll_u16(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_u16(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_u16(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_u16(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Short
fun ffi_payjoin_ffi_rust_future_poll_i16(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_i16(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_i16(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_i16(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Short
fun ffi_payjoin_ffi_rust_future_poll_u32(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_u32(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_u32(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_u32(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Int
fun ffi_payjoin_ffi_rust_future_poll_i32(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_i32(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_i32(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_i32(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Int
fun ffi_payjoin_ffi_rust_future_poll_u64(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_u64(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_u64(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_u64(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun ffi_payjoin_ffi_rust_future_poll_i64(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_i64(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_i64(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_i64(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Long
fun ffi_payjoin_ffi_rust_future_poll_f32(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_f32(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_f32(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_f32(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Float
fun ffi_payjoin_ffi_rust_future_poll_f64(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_f64(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_f64(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_f64(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Double
fun ffi_payjoin_ffi_rust_future_poll_pointer(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_pointer(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_pointer(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_pointer(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Pointer
fun ffi_payjoin_ffi_rust_future_poll_rust_buffer(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_rust_buffer(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_rust_buffer(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_rust_buffer(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): RustBuffer.ByValue
fun ffi_payjoin_ffi_rust_future_poll_void(`handle`: Long,`callback`: UniffiRustFutureContinuationCallback,`callbackData`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_cancel_void(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_free_void(`handle`: Long,
): Unit
fun ffi_payjoin_ffi_rust_future_complete_void(`handle`: Long,uniffi_out_err: UniffiRustCallStatus, 
): Unit

}

private fun uniffiCheckContractApiVersion(lib: IntegrityCheckingUniffiLib) {
    val bindings_contract_version = 29
    val scaffolding_contract_version = lib.ffi_payjoin_ffi_uniffi_contract_version()
    if (bindings_contract_version != scaffolding_contract_version) {
        throw RuntimeException("UniFFI contract version mismatch: try cleaning and rebuilding your project")
    }
}
@Suppress("UNUSED_PARAMETER")
private fun uniffiCheckApiChecksums(lib: IntegrityCheckingUniffiLib) {
    if (lib.uniffi_payjoin_ffi_checksum_method_canbroadcast_callback() != 47545.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_isoutputknown_callback() != 35283.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_isscriptowned_callback() != 61689.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_maybeinputsowned_check_inputs_not_owned() != 42009.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_maybeinputsseen_check_no_inputs_seen_before() != 18649.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_outputsunknown_identify_receiver_outputs() != 22731.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_payjoinproposal_extract_v2_req() != 20198.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_payjoinproposal_process_res() != 58904.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_payjoinproposal_psbt() != 26273.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_payjoinproposal_utxos_to_be_locked() != 11849.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_pjuri_address() != 8777.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_pjuri_amount_sats() != 27918.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_pjuri_as_string() != 40093.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_pjuri_pj_endpoint() != 22628.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_processpsbt_callback() != 18647.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_provisionalproposal_finalize_proposal() != 54039.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_receiver_extract_req() != 49409.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_receiver_id() != 17398.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_receiver_pj_uri() != 4642.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_receiver_process_res() != 55450.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_receiver_to_json() != 44395.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_sender_extract_v1() != 44871.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_sender_extract_v2() != 16864.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_sender_to_json() != 12883.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_senderbuilder_always_disable_output_substitution() != 29688.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_senderbuilder_build_non_incentivizing() != 51323.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_senderbuilder_build_recommended() != 20315.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_senderbuilder_build_with_additional_fee() != 51359.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_uncheckedproposal_assume_interactive_receiver() != 21198.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_uncheckedproposal_check_broadcast_suitability() != 24131.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_uncheckedproposal_extract_err_req() != 40479.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_uncheckedproposal_extract_tx_to_schedule_broadcast() != 26409.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_uncheckedproposal_process_err_res() != 54886.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_url_as_string() != 57114.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_url_query() != 6902.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_v1context_process_response() != 65219.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_v2getcontext_extract_req() != 9350.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_v2getcontext_process_response() != 28971.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_v2postcontext_process_response() != 63918.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_wantsinputs_commit_inputs() != 6538.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_wantsinputs_contribute_inputs() != 43267.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_wantsinputs_try_preserving_privacy() != 57145.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_wantsoutputs_commit_outputs() != 22439.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_wantsoutputs_output_substitution() != 50989.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_wantsoutputs_replace_receiver_outputs() != 52021.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_method_wantsoutputs_substitute_receiver_script() != 49578.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_constructor_inputpair_new() != 4648.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_constructor_ohttpkeys_decode() != 8536.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_constructor_receiver_from_json() != 16110.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_constructor_receiver_new() != 14242.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_constructor_sender_from_json() != 38444.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_constructor_senderbuilder_new() != 16724.toShort()) {
        throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
    }
    if (lib.uniffi_payjoin_ffi_checksum_constructor_url_parse() != 48179.toShort()) {
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
public interface BuildSenderExceptionInterface {
    
    companion object
}

open class BuildSenderException : kotlin.Exception, Disposable, AutoCloseable, BuildSenderExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_buildsendererror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_buildsendererror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<BuildSenderException> {
        override fun lift(error_buf: RustBuffer.ByValue): BuildSenderException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeBuildSenderError.read(bb)
        }
    }
    
}

public object FfiConverterTypeBuildSenderError: FfiConverter<BuildSenderException, Pointer> {

    override fun lower(value: BuildSenderException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): BuildSenderException {
        return BuildSenderException(value)
    }

    override fun read(buf: ByteBuffer): BuildSenderException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: BuildSenderException) = 8UL

    override fun write(value: BuildSenderException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface CanBroadcastInterface {
    
    fun `callback`(`tx`: kotlin.ByteArray): kotlin.Boolean
    
    companion object
}

open class CanBroadcast: Disposable, AutoCloseable, CanBroadcastInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_canbroadcast(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_canbroadcast(pointer!!, status)
        }
    }

    
    @Throws(ImplementationException::class)override fun `callback`(`tx`: kotlin.ByteArray): kotlin.Boolean {
            return FfiConverterBoolean.lift(
    callWithPointer {
    uniffiRustCallWithError(ImplementationException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_canbroadcast_callback(
        it, FfiConverterByteArray.lower(`tx`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeCanBroadcast: FfiConverter<CanBroadcast, Pointer> {

    override fun lower(value: CanBroadcast): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): CanBroadcast {
        return CanBroadcast(value)
    }

    override fun read(buf: ByteBuffer): CanBroadcast {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: CanBroadcast) = 8UL

    override fun write(value: CanBroadcast, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface ClientResponseInterface {
    
    companion object
}

open class ClientResponse: Disposable, AutoCloseable, ClientResponseInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_clientresponse(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_clientresponse(pointer!!, status)
        }
    }

    

    
    
    companion object
    
}

public object FfiConverterTypeClientResponse: FfiConverter<ClientResponse, Pointer> {

    override fun lower(value: ClientResponse): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): ClientResponse {
        return ClientResponse(value)
    }

    override fun read(buf: ByteBuffer): ClientResponse {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: ClientResponse) = 8UL

    override fun write(value: ClientResponse, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface CreateRequestExceptionInterface {
    
    companion object
}

open class CreateRequestException : kotlin.Exception, Disposable, AutoCloseable, CreateRequestExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_createrequesterror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_createrequesterror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<CreateRequestException> {
        override fun lift(error_buf: RustBuffer.ByValue): CreateRequestException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeCreateRequestError.read(bb)
        }
    }
    
}

public object FfiConverterTypeCreateRequestError: FfiConverter<CreateRequestException, Pointer> {

    override fun lower(value: CreateRequestException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): CreateRequestException {
        return CreateRequestException(value)
    }

    override fun read(buf: ByteBuffer): CreateRequestException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: CreateRequestException) = 8UL

    override fun write(value: CreateRequestException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface EncapsulationExceptionInterface {
    
    companion object
}

open class EncapsulationException : kotlin.Exception, Disposable, AutoCloseable, EncapsulationExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_encapsulationerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_encapsulationerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<EncapsulationException> {
        override fun lift(error_buf: RustBuffer.ByValue): EncapsulationException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeEncapsulationError.read(bb)
        }
    }
    
}

public object FfiConverterTypeEncapsulationError: FfiConverter<EncapsulationException, Pointer> {

    override fun lower(value: EncapsulationException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): EncapsulationException {
        return EncapsulationException(value)
    }

    override fun read(buf: ByteBuffer): EncapsulationException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: EncapsulationException) = 8UL

    override fun write(value: EncapsulationException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface ImplementationExceptionInterface {
    
    companion object
}

open class ImplementationException : kotlin.Exception, Disposable, AutoCloseable, ImplementationExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_implementationerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_implementationerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<ImplementationException> {
        override fun lift(error_buf: RustBuffer.ByValue): ImplementationException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeImplementationError.read(bb)
        }
    }
    
}

public object FfiConverterTypeImplementationError: FfiConverter<ImplementationException, Pointer> {

    override fun lower(value: ImplementationException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): ImplementationException {
        return ImplementationException(value)
    }

    override fun read(buf: ByteBuffer): ImplementationException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: ImplementationException) = 8UL

    override fun write(value: ImplementationException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface InputContributionExceptionInterface {
    
    companion object
}

open class InputContributionException : kotlin.Exception, Disposable, AutoCloseable, InputContributionExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_inputcontributionerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_inputcontributionerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<InputContributionException> {
        override fun lift(error_buf: RustBuffer.ByValue): InputContributionException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeInputContributionError.read(bb)
        }
    }
    
}

public object FfiConverterTypeInputContributionError: FfiConverter<InputContributionException, Pointer> {

    override fun lower(value: InputContributionException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): InputContributionException {
        return InputContributionException(value)
    }

    override fun read(buf: ByteBuffer): InputContributionException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: InputContributionException) = 8UL

    override fun write(value: InputContributionException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface InputPairInterface {
    
    companion object
}

open class InputPair: Disposable, AutoCloseable, InputPairInterface
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
    constructor(`txin`: TxIn, `psbtin`: PsbtInput) :
        this(
    uniffiRustCallWithError(PsbtInputException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_constructor_inputpair_new(
        FfiConverterTypeTxIn.lower(`txin`),FfiConverterTypePsbtInput.lower(`psbtin`),_status)
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_inputpair(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_inputpair(pointer!!, status)
        }
    }

    

    
    
    companion object
    
}

public object FfiConverterTypeInputPair: FfiConverter<InputPair, Pointer> {

    override fun lower(value: InputPair): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): InputPair {
        return InputPair(value)
    }

    override fun read(buf: ByteBuffer): InputPair {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: InputPair) = 8UL

    override fun write(value: InputPair, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface IntoUrlExceptionInterface {
    
    companion object
}


open class IntoUrlException : kotlin.Exception, Disposable, AutoCloseable, IntoUrlExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_intourlerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_intourlerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<IntoUrlException> {
        override fun lift(error_buf: RustBuffer.ByValue): IntoUrlException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeIntoUrlError.read(bb)
        }
    }
    
}

public object FfiConverterTypeIntoUrlError: FfiConverter<IntoUrlException, Pointer> {

    override fun lower(value: IntoUrlException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): IntoUrlException {
        return IntoUrlException(value)
    }

    override fun read(buf: ByteBuffer): IntoUrlException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: IntoUrlException) = 8UL

    override fun write(value: IntoUrlException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface IoErrorInterface {
    
    companion object
}

open class IoError: Disposable, AutoCloseable, IoErrorInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_ioerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_ioerror(pointer!!, status)
        }
    }

    

    
    
    companion object
    
}

public object FfiConverterTypeIoError: FfiConverter<IoError, Pointer> {

    override fun lower(value: IoError): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): IoError {
        return IoError(value)
    }

    override fun read(buf: ByteBuffer): IoError {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: IoError) = 8UL

    override fun write(value: IoError, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface IsOutputKnownInterface {
    
    fun `callback`(`outpoint`: OutPoint): kotlin.Boolean
    
    companion object
}

open class IsOutputKnown: Disposable, AutoCloseable, IsOutputKnownInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_isoutputknown(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_isoutputknown(pointer!!, status)
        }
    }

    
    @Throws(ImplementationException::class)override fun `callback`(`outpoint`: OutPoint): kotlin.Boolean {
            return FfiConverterBoolean.lift(
    callWithPointer {
    uniffiRustCallWithError(ImplementationException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_isoutputknown_callback(
        it, FfiConverterTypeOutPoint.lower(`outpoint`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeIsOutputKnown: FfiConverter<IsOutputKnown, Pointer> {

    override fun lower(value: IsOutputKnown): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): IsOutputKnown {
        return IsOutputKnown(value)
    }

    override fun read(buf: ByteBuffer): IsOutputKnown {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: IsOutputKnown) = 8UL

    override fun write(value: IsOutputKnown, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface IsScriptOwnedInterface {
    
    fun `callback`(`script`: kotlin.ByteArray): kotlin.Boolean
    
    companion object
}

open class IsScriptOwned: Disposable, AutoCloseable, IsScriptOwnedInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_isscriptowned(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_isscriptowned(pointer!!, status)
        }
    }

    
    @Throws(ImplementationException::class)override fun `callback`(`script`: kotlin.ByteArray): kotlin.Boolean {
            return FfiConverterBoolean.lift(
    callWithPointer {
    uniffiRustCallWithError(ImplementationException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_isscriptowned_callback(
        it, FfiConverterByteArray.lower(`script`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeIsScriptOwned: FfiConverter<IsScriptOwned, Pointer> {

    override fun lower(value: IsScriptOwned): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): IsScriptOwned {
        return IsScriptOwned(value)
    }

    override fun read(buf: ByteBuffer): IsScriptOwned {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: IsScriptOwned) = 8UL

    override fun write(value: IsScriptOwned, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface JsonReplyInterface {
    
    companion object
}

open class JsonReply: Disposable, AutoCloseable, JsonReplyInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_jsonreply(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_jsonreply(pointer!!, status)
        }
    }

    

    
    
    companion object
    
}

public object FfiConverterTypeJsonReply: FfiConverter<JsonReply, Pointer> {

    override fun lower(value: JsonReply): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): JsonReply {
        return JsonReply(value)
    }

    override fun read(buf: ByteBuffer): JsonReply {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: JsonReply) = 8UL

    override fun write(value: JsonReply, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface MaybeInputsOwnedInterface {
    
    fun `checkInputsNotOwned`(`isOwned`: IsScriptOwned): MaybeInputsSeen
    
    companion object
}

open class MaybeInputsOwned: Disposable, AutoCloseable, MaybeInputsOwnedInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_maybeinputsowned(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_maybeinputsowned(pointer!!, status)
        }
    }

    
    @Throws(ReplyableException::class)override fun `checkInputsNotOwned`(`isOwned`: IsScriptOwned): MaybeInputsSeen {
            return FfiConverterTypeMaybeInputsSeen.lift(
    callWithPointer {
    uniffiRustCallWithError(ReplyableException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_maybeinputsowned_check_inputs_not_owned(
        it, FfiConverterTypeIsScriptOwned.lower(`isOwned`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeMaybeInputsOwned: FfiConverter<MaybeInputsOwned, Pointer> {

    override fun lower(value: MaybeInputsOwned): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): MaybeInputsOwned {
        return MaybeInputsOwned(value)
    }

    override fun read(buf: ByteBuffer): MaybeInputsOwned {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: MaybeInputsOwned) = 8UL

    override fun write(value: MaybeInputsOwned, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface MaybeInputsSeenInterface {
    
    fun `checkNoInputsSeenBefore`(`isKnown`: IsOutputKnown): OutputsUnknown
    
    companion object
}

open class MaybeInputsSeen: Disposable, AutoCloseable, MaybeInputsSeenInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_maybeinputsseen(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_maybeinputsseen(pointer!!, status)
        }
    }

    
    @Throws(ReplyableException::class)override fun `checkNoInputsSeenBefore`(`isKnown`: IsOutputKnown): OutputsUnknown {
            return FfiConverterTypeOutputsUnknown.lift(
    callWithPointer {
    uniffiRustCallWithError(ReplyableException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_maybeinputsseen_check_no_inputs_seen_before(
        it, FfiConverterTypeIsOutputKnown.lower(`isKnown`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeMaybeInputsSeen: FfiConverter<MaybeInputsSeen, Pointer> {

    override fun lower(value: MaybeInputsSeen): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): MaybeInputsSeen {
        return MaybeInputsSeen(value)
    }

    override fun read(buf: ByteBuffer): MaybeInputsSeen {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: MaybeInputsSeen) = 8UL

    override fun write(value: MaybeInputsSeen, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface OhttpExceptionInterface {
    
    companion object
}


open class OhttpException : kotlin.Exception, Disposable, AutoCloseable, OhttpExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_ohttperror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_ohttperror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<OhttpException> {
        override fun lift(error_buf: RustBuffer.ByValue): OhttpException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeOhttpError.read(bb)
        }
    }
    
}

public object FfiConverterTypeOhttpError: FfiConverter<OhttpException, Pointer> {

    override fun lower(value: OhttpException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): OhttpException {
        return OhttpException(value)
    }

    override fun read(buf: ByteBuffer): OhttpException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: OhttpException) = 8UL

    override fun write(value: OhttpException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface OhttpKeysInterface {
    
    companion object
}

open class OhttpKeys: Disposable, AutoCloseable, OhttpKeysInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_ohttpkeys(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_ohttpkeys(pointer!!, status)
        }
    }

    

    
    companion object {
        
    @Throws(OhttpException::class) fun `decode`(`bytes`: kotlin.ByteArray): OhttpKeys {
            return FfiConverterTypeOhttpKeys.lift(
    uniffiRustCallWithError(OhttpException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_constructor_ohttpkeys_decode(
        FfiConverterByteArray.lower(`bytes`),_status)
}
    )
    }
    

        
    }
    
}

public object FfiConverterTypeOhttpKeys: FfiConverter<OhttpKeys, Pointer> {

    override fun lower(value: OhttpKeys): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): OhttpKeys {
        return OhttpKeys(value)
    }

    override fun read(buf: ByteBuffer): OhttpKeys {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: OhttpKeys) = 8UL

    override fun write(value: OhttpKeys, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface OutputSubstitutionExceptionInterface {
    
    companion object
}

open class OutputSubstitutionException : kotlin.Exception, Disposable, AutoCloseable, OutputSubstitutionExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_outputsubstitutionerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_outputsubstitutionerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<OutputSubstitutionException> {
        override fun lift(error_buf: RustBuffer.ByValue): OutputSubstitutionException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeOutputSubstitutionError.read(bb)
        }
    }
    
}

public object FfiConverterTypeOutputSubstitutionError: FfiConverter<OutputSubstitutionException, Pointer> {

    override fun lower(value: OutputSubstitutionException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): OutputSubstitutionException {
        return OutputSubstitutionException(value)
    }

    override fun read(buf: ByteBuffer): OutputSubstitutionException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: OutputSubstitutionException) = 8UL

    override fun write(value: OutputSubstitutionException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface OutputsUnknownInterface {
    
    fun `identifyReceiverOutputs`(`isReceiverOutput`: IsScriptOwned): WantsOutputs
    
    companion object
}

open class OutputsUnknown: Disposable, AutoCloseable, OutputsUnknownInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_outputsunknown(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_outputsunknown(pointer!!, status)
        }
    }

    
    @Throws(ReplyableException::class)override fun `identifyReceiverOutputs`(`isReceiverOutput`: IsScriptOwned): WantsOutputs {
            return FfiConverterTypeWantsOutputs.lift(
    callWithPointer {
    uniffiRustCallWithError(ReplyableException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_outputsunknown_identify_receiver_outputs(
        it, FfiConverterTypeIsScriptOwned.lower(`isReceiverOutput`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeOutputsUnknown: FfiConverter<OutputsUnknown, Pointer> {

    override fun lower(value: OutputsUnknown): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): OutputsUnknown {
        return OutputsUnknown(value)
    }

    override fun read(buf: ByteBuffer): OutputsUnknown {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: OutputsUnknown) = 8UL

    override fun write(value: OutputsUnknown, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface PayjoinProposalInterface {
    
    fun `extractV2Req`(`ohttpRelay`: kotlin.String): RequestResponse
    
    fun `processRes`(`body`: kotlin.ByteArray, `ctx`: ClientResponse)
    
    fun `psbt`(): kotlin.String
    
    fun `utxosToBeLocked`(): List<OutPoint>
    
    companion object
}

open class PayjoinProposal: Disposable, AutoCloseable, PayjoinProposalInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_payjoinproposal(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_payjoinproposal(pointer!!, status)
        }
    }

    
    @Throws(Exception::class)override fun `extractV2Req`(`ohttpRelay`: kotlin.String): RequestResponse {
            return FfiConverterTypeRequestResponse.lift(
    callWithPointer {
    uniffiRustCallWithError(Exception) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_payjoinproposal_extract_v2_req(
        it, FfiConverterString.lower(`ohttpRelay`),_status)
}
    }
    )
    }
    

    
    @Throws(Exception::class)override fun `processRes`(`body`: kotlin.ByteArray, `ctx`: ClientResponse)
        = 
    callWithPointer {
    uniffiRustCallWithError(Exception) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_payjoinproposal_process_res(
        it, FfiConverterByteArray.lower(`body`),FfiConverterTypeClientResponse.lower(`ctx`),_status)
}
    }
    
    

    override fun `psbt`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_payjoinproposal_psbt(
        it, _status)
}
    }
    )
    }
    

    override fun `utxosToBeLocked`(): List<OutPoint> {
            return FfiConverterSequenceTypeOutPoint.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_payjoinproposal_utxos_to_be_locked(
        it, _status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypePayjoinProposal: FfiConverter<PayjoinProposal, Pointer> {

    override fun lower(value: PayjoinProposal): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): PayjoinProposal {
        return PayjoinProposal(value)
    }

    override fun read(buf: ByteBuffer): PayjoinProposal {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: PayjoinProposal) = 8UL

    override fun write(value: PayjoinProposal, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface PjNotSupportedInterface {
    
    companion object
}

open class PjNotSupported: Disposable, AutoCloseable, PjNotSupportedInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_pjnotsupported(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_pjnotsupported(pointer!!, status)
        }
    }

    

    
    
    companion object
    
}

public object FfiConverterTypePjNotSupported: FfiConverter<PjNotSupported, Pointer> {

    override fun lower(value: PjNotSupported): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): PjNotSupported {
        return PjNotSupported(value)
    }

    override fun read(buf: ByteBuffer): PjNotSupported {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: PjNotSupported) = 8UL

    override fun write(value: PjNotSupported, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface PjParseErrorInterface {
    
    companion object
}

open class PjParseError: Disposable, AutoCloseable, PjParseErrorInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_pjparseerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_pjparseerror(pointer!!, status)
        }
    }

    

    
    
    companion object
    
}

public object FfiConverterTypePjParseError: FfiConverter<PjParseError, Pointer> {

    override fun lower(value: PjParseError): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): PjParseError {
        return PjParseError(value)
    }

    override fun read(buf: ByteBuffer): PjParseError {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: PjParseError) = 8UL

    override fun write(value: PjParseError, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface PjUriInterface {
    
    fun `address`(): kotlin.String
    
    fun `amountSats`(): kotlin.ULong?
    
    fun `asString`(): kotlin.String
    
    fun `pjEndpoint`(): kotlin.String
    
    companion object
}

open class PjUri: Disposable, AutoCloseable, PjUriInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_pjuri(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_pjuri(pointer!!, status)
        }
    }

    override fun `address`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_pjuri_address(
        it, _status)
}
    }
    )
    }
    

    
    override fun `amountSats`(): kotlin.ULong? {
            return FfiConverterOptionalULong.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_pjuri_amount_sats(
        it, _status)
}
    }
    )
    }
    

    override fun `asString`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_pjuri_as_string(
        it, _status)
}
    }
    )
    }
    

    override fun `pjEndpoint`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_pjuri_pj_endpoint(
        it, _status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypePjUri: FfiConverter<PjUri, Pointer> {

    override fun lower(value: PjUri): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): PjUri {
        return PjUri(value)
    }

    override fun read(buf: ByteBuffer): PjUri {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: PjUri) = 8UL

    override fun write(value: PjUri, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface ProcessPsbtInterface {
    
    fun `callback`(`psbt`: kotlin.String): kotlin.String
    
    companion object
}

open class ProcessPsbt: Disposable, AutoCloseable, ProcessPsbtInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_processpsbt(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_processpsbt(pointer!!, status)
        }
    }

    
    @Throws(ImplementationException::class)override fun `callback`(`psbt`: kotlin.String): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCallWithError(ImplementationException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_processpsbt_callback(
        it, FfiConverterString.lower(`psbt`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeProcessPsbt: FfiConverter<ProcessPsbt, Pointer> {

    override fun lower(value: ProcessPsbt): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): ProcessPsbt {
        return ProcessPsbt(value)
    }

    override fun read(buf: ByteBuffer): ProcessPsbt {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: ProcessPsbt) = 8UL

    override fun write(value: ProcessPsbt, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface ProvisionalProposalInterface {
    
    fun `finalizeProposal`(`processPsbt`: ProcessPsbt, `minFeerateSatPerVb`: kotlin.ULong?, `maxEffectiveFeeRateSatPerVb`: kotlin.ULong?): PayjoinProposal
    
    companion object
}

open class ProvisionalProposal: Disposable, AutoCloseable, ProvisionalProposalInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_provisionalproposal(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_provisionalproposal(pointer!!, status)
        }
    }

    
    @Throws(ReplyableException::class)override fun `finalizeProposal`(`processPsbt`: ProcessPsbt, `minFeerateSatPerVb`: kotlin.ULong?, `maxEffectiveFeeRateSatPerVb`: kotlin.ULong?): PayjoinProposal {
            return FfiConverterTypePayjoinProposal.lift(
    callWithPointer {
    uniffiRustCallWithError(ReplyableException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_provisionalproposal_finalize_proposal(
        it, FfiConverterTypeProcessPsbt.lower(`processPsbt`),FfiConverterOptionalULong.lower(`minFeerateSatPerVb`),FfiConverterOptionalULong.lower(`maxEffectiveFeeRateSatPerVb`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeProvisionalProposal: FfiConverter<ProvisionalProposal, Pointer> {

    override fun lower(value: ProvisionalProposal): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): ProvisionalProposal {
        return ProvisionalProposal(value)
    }

    override fun read(buf: ByteBuffer): ProvisionalProposal {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: ProvisionalProposal) = 8UL

    override fun write(value: ProvisionalProposal, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface PsbtInputExceptionInterface {
    
    companion object
}

open class PsbtInputException : kotlin.Exception, Disposable, AutoCloseable, PsbtInputExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_psbtinputerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_psbtinputerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<PsbtInputException> {
        override fun lift(error_buf: RustBuffer.ByValue): PsbtInputException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypePsbtInputError.read(bb)
        }
    }
    
}

public object FfiConverterTypePsbtInputError: FfiConverter<PsbtInputException, Pointer> {

    override fun lower(value: PsbtInputException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): PsbtInputException {
        return PsbtInputException(value)
    }

    override fun read(buf: ByteBuffer): PsbtInputException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: PsbtInputException) = 8UL

    override fun write(value: PsbtInputException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface ReceiverInterface {
    
    fun `extractReq`(`ohttpRelay`: kotlin.String): RequestResponse
    
    fun `id`(): kotlin.String
    
    fun `pjUri`(): PjUri
    
    fun `processRes`(`body`: kotlin.ByteArray, `context`: ClientResponse): UncheckedProposal?
    
    fun `toJson`(): kotlin.String
    
    companion object
}

open class Receiver: Disposable, AutoCloseable, ReceiverInterface
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
    constructor(`address`: Address, `directory`: kotlin.String, `ohttpKeys`: OhttpKeys, `expireAfter`: kotlin.ULong?) :
        this(
    uniffiRustCallWithError(IntoUrlException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_constructor_receiver_new(
        FfiConverterTypeAddress.lower(`address`),FfiConverterString.lower(`directory`),FfiConverterTypeOhttpKeys.lower(`ohttpKeys`),FfiConverterOptionalULong.lower(`expireAfter`),_status)
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_receiver(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_receiver(pointer!!, status)
        }
    }

    
    @Throws(Exception::class)override fun `extractReq`(`ohttpRelay`: kotlin.String): RequestResponse {
            return FfiConverterTypeRequestResponse.lift(
    callWithPointer {
    uniffiRustCallWithError(Exception) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_receiver_extract_req(
        it, FfiConverterString.lower(`ohttpRelay`),_status)
}
    }
    )
    }
    

    
    override fun `id`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_receiver_id(
        it, _status)
}
    }
    )
    }
    

    
    override fun `pjUri`(): PjUri {
            return FfiConverterTypePjUri.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_receiver_pj_uri(
        it, _status)
}
    }
    )
    }
    

    
    @Throws(Exception::class)override fun `processRes`(`body`: kotlin.ByteArray, `context`: ClientResponse): UncheckedProposal? {
            return FfiConverterOptionalTypeUncheckedProposal.lift(
    callWithPointer {
    uniffiRustCallWithError(Exception) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_receiver_process_res(
        it, FfiConverterByteArray.lower(`body`),FfiConverterTypeClientResponse.lower(`context`),_status)
}
    }
    )
    }
    

    
    @Throws(SerdeJsonException::class)override fun `toJson`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCallWithError(SerdeJsonException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_receiver_to_json(
        it, _status)
}
    }
    )
    }
    

    

    
    companion object {
        
    @Throws(SerdeJsonException::class) fun `fromJson`(`json`: kotlin.String): Receiver {
            return FfiConverterTypeReceiver.lift(
    uniffiRustCallWithError(SerdeJsonException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_constructor_receiver_from_json(
        FfiConverterString.lower(`json`),_status)
}
    )
    }
    

        
    }
    
}

public object FfiConverterTypeReceiver: FfiConverter<Receiver, Pointer> {

    override fun lower(value: Receiver): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): Receiver {
        return Receiver(value)
    }

    override fun read(buf: ByteBuffer): Receiver {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: Receiver) = 8UL

    override fun write(value: Receiver, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface ReplyableExceptionInterface {
    
    companion object
}

open class ReplyableException : kotlin.Exception, Disposable, AutoCloseable, ReplyableExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_replyableerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_replyableerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<ReplyableException> {
        override fun lift(error_buf: RustBuffer.ByValue): ReplyableException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeReplyableError.read(bb)
        }
    }
    
}

public object FfiConverterTypeReplyableError: FfiConverter<ReplyableException, Pointer> {

    override fun lower(value: ReplyableException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): ReplyableException {
        return ReplyableException(value)
    }

    override fun read(buf: ByteBuffer): ReplyableException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: ReplyableException) = 8UL

    override fun write(value: ReplyableException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface SelectionExceptionInterface {
    
    companion object
}

open class SelectionException : kotlin.Exception, Disposable, AutoCloseable, SelectionExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_selectionerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_selectionerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<SelectionException> {
        override fun lift(error_buf: RustBuffer.ByValue): SelectionException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeSelectionError.read(bb)
        }
    }
    
}

public object FfiConverterTypeSelectionError: FfiConverter<SelectionException, Pointer> {

    override fun lower(value: SelectionException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): SelectionException {
        return SelectionException(value)
    }

    override fun read(buf: ByteBuffer): SelectionException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: SelectionException) = 8UL

    override fun write(value: SelectionException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface SenderInterface {
    
    fun `extractV1`(): RequestV1Context
    
    fun `extractV2`(`ohttpProxyUrl`: Url): RequestV2PostContext
    
    fun `toJson`(): kotlin.String
    
    companion object
}

open class Sender: Disposable, AutoCloseable, SenderInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_sender(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_sender(pointer!!, status)
        }
    }

    override fun `extractV1`(): RequestV1Context {
            return FfiConverterTypeRequestV1Context.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_sender_extract_v1(
        it, _status)
}
    }
    )
    }
    

    
    @Throws(CreateRequestException::class)override fun `extractV2`(`ohttpProxyUrl`: Url): RequestV2PostContext {
            return FfiConverterTypeRequestV2PostContext.lift(
    callWithPointer {
    uniffiRustCallWithError(CreateRequestException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_sender_extract_v2(
        it, FfiConverterTypeUrl.lower(`ohttpProxyUrl`),_status)
}
    }
    )
    }
    

    
    @Throws(SerdeJsonException::class)override fun `toJson`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCallWithError(SerdeJsonException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_sender_to_json(
        it, _status)
}
    }
    )
    }
    

    

    
    companion object {
        
    @Throws(SerdeJsonException::class) fun `fromJson`(`json`: kotlin.String): Sender {
            return FfiConverterTypeSender.lift(
    uniffiRustCallWithError(SerdeJsonException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_constructor_sender_from_json(
        FfiConverterString.lower(`json`),_status)
}
    )
    }
    

        
    }
    
}

public object FfiConverterTypeSender: FfiConverter<Sender, Pointer> {

    override fun lower(value: Sender): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): Sender {
        return Sender(value)
    }

    override fun read(buf: ByteBuffer): Sender {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: Sender) = 8UL

    override fun write(value: Sender, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface SenderBuilderInterface {
    
    fun `alwaysDisableOutputSubstitution`(): SenderBuilder
    
    fun `buildNonIncentivizing`(`minFeeRate`: kotlin.ULong): Sender
    
    fun `buildRecommended`(`minFeeRate`: kotlin.ULong): Sender
    
    fun `buildWithAdditionalFee`(`maxFeeContribution`: kotlin.ULong, `changeIndex`: kotlin.UByte?, `minFeeRate`: kotlin.ULong, `clampFeeContribution`: kotlin.Boolean): Sender
    
    companion object
}

open class SenderBuilder: Disposable, AutoCloseable, SenderBuilderInterface
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
    constructor(`psbt`: kotlin.String, `uri`: PjUri) :
        this(
    uniffiRustCallWithError(BuildSenderException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_constructor_senderbuilder_new(
        FfiConverterString.lower(`psbt`),FfiConverterTypePjUri.lower(`uri`),_status)
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_senderbuilder(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_senderbuilder(pointer!!, status)
        }
    }

    
    override fun `alwaysDisableOutputSubstitution`(): SenderBuilder {
            return FfiConverterTypeSenderBuilder.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_senderbuilder_always_disable_output_substitution(
        it, _status)
}
    }
    )
    }
    

    
    @Throws(BuildSenderException::class)override fun `buildNonIncentivizing`(`minFeeRate`: kotlin.ULong): Sender {
            return FfiConverterTypeSender.lift(
    callWithPointer {
    uniffiRustCallWithError(BuildSenderException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_senderbuilder_build_non_incentivizing(
        it, FfiConverterULong.lower(`minFeeRate`),_status)
}
    }
    )
    }
    

    
    @Throws(BuildSenderException::class)override fun `buildRecommended`(`minFeeRate`: kotlin.ULong): Sender {
            return FfiConverterTypeSender.lift(
    callWithPointer {
    uniffiRustCallWithError(BuildSenderException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_senderbuilder_build_recommended(
        it, FfiConverterULong.lower(`minFeeRate`),_status)
}
    }
    )
    }
    

    
    @Throws(BuildSenderException::class)override fun `buildWithAdditionalFee`(`maxFeeContribution`: kotlin.ULong, `changeIndex`: kotlin.UByte?, `minFeeRate`: kotlin.ULong, `clampFeeContribution`: kotlin.Boolean): Sender {
            return FfiConverterTypeSender.lift(
    callWithPointer {
    uniffiRustCallWithError(BuildSenderException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_senderbuilder_build_with_additional_fee(
        it, FfiConverterULong.lower(`maxFeeContribution`),FfiConverterOptionalUByte.lower(`changeIndex`),FfiConverterULong.lower(`minFeeRate`),FfiConverterBoolean.lower(`clampFeeContribution`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeSenderBuilder: FfiConverter<SenderBuilder, Pointer> {

    override fun lower(value: SenderBuilder): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): SenderBuilder {
        return SenderBuilder(value)
    }

    override fun read(buf: ByteBuffer): SenderBuilder {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: SenderBuilder) = 8UL

    override fun write(value: SenderBuilder, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface SerdeJsonExceptionInterface {
    
    companion object
}


open class SerdeJsonException : kotlin.Exception, Disposable, AutoCloseable, SerdeJsonExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_serdejsonerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_serdejsonerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<SerdeJsonException> {
        override fun lift(error_buf: RustBuffer.ByValue): SerdeJsonException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeSerdeJsonError.read(bb)
        }
    }
    
}

public object FfiConverterTypeSerdeJsonError: FfiConverter<SerdeJsonException, Pointer> {

    override fun lower(value: SerdeJsonException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): SerdeJsonException {
        return SerdeJsonException(value)
    }

    override fun read(buf: ByteBuffer): SerdeJsonException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: SerdeJsonException) = 8UL

    override fun write(value: SerdeJsonException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface SessionExceptionInterface {
    
    companion object
}

open class SessionException : kotlin.Exception, Disposable, AutoCloseable, SessionExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_sessionerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_sessionerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<SessionException> {
        override fun lift(error_buf: RustBuffer.ByValue): SessionException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeSessionError.read(bb)
        }
    }
    
}

public object FfiConverterTypeSessionError: FfiConverter<SessionException, Pointer> {

    override fun lower(value: SessionException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): SessionException {
        return SessionException(value)
    }

    override fun read(buf: ByteBuffer): SessionException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: SessionException) = 8UL

    override fun write(value: SessionException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface UncheckedProposalInterface {
    
    fun `assumeInteractiveReceiver`(): MaybeInputsOwned
    
    fun `checkBroadcastSuitability`(`minFeeRate`: kotlin.ULong?, `canBroadcast`: CanBroadcast): MaybeInputsOwned
    
    fun `extractErrReq`(`err`: JsonReply, `ohttpRelay`: kotlin.String): RequestResponse
    
    fun `extractTxToScheduleBroadcast`(): kotlin.ByteArray
    
    fun `processErrRes`(`body`: kotlin.ByteArray, `context`: ClientResponse)
    
    companion object
}

open class UncheckedProposal: Disposable, AutoCloseable, UncheckedProposalInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_uncheckedproposal(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_uncheckedproposal(pointer!!, status)
        }
    }

    
    override fun `assumeInteractiveReceiver`(): MaybeInputsOwned {
            return FfiConverterTypeMaybeInputsOwned.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_uncheckedproposal_assume_interactive_receiver(
        it, _status)
}
    }
    )
    }
    

    
    @Throws(ReplyableException::class)override fun `checkBroadcastSuitability`(`minFeeRate`: kotlin.ULong?, `canBroadcast`: CanBroadcast): MaybeInputsOwned {
            return FfiConverterTypeMaybeInputsOwned.lift(
    callWithPointer {
    uniffiRustCallWithError(ReplyableException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_uncheckedproposal_check_broadcast_suitability(
        it, FfiConverterOptionalULong.lower(`minFeeRate`),FfiConverterTypeCanBroadcast.lower(`canBroadcast`),_status)
}
    }
    )
    }
    

    
    @Throws(SessionException::class)override fun `extractErrReq`(`err`: JsonReply, `ohttpRelay`: kotlin.String): RequestResponse {
            return FfiConverterTypeRequestResponse.lift(
    callWithPointer {
    uniffiRustCallWithError(SessionException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_uncheckedproposal_extract_err_req(
        it, FfiConverterTypeJsonReply.lower(`err`),FfiConverterString.lower(`ohttpRelay`),_status)
}
    }
    )
    }
    

    
    override fun `extractTxToScheduleBroadcast`(): kotlin.ByteArray {
            return FfiConverterByteArray.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_uncheckedproposal_extract_tx_to_schedule_broadcast(
        it, _status)
}
    }
    )
    }
    

    
    @Throws(SessionException::class)override fun `processErrRes`(`body`: kotlin.ByteArray, `context`: ClientResponse)
        = 
    callWithPointer {
    uniffiRustCallWithError(SessionException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_uncheckedproposal_process_err_res(
        it, FfiConverterByteArray.lower(`body`),FfiConverterTypeClientResponse.lower(`context`),_status)
}
    }
    
    

    

    
    
    companion object
    
}

public object FfiConverterTypeUncheckedProposal: FfiConverter<UncheckedProposal, Pointer> {

    override fun lower(value: UncheckedProposal): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): UncheckedProposal {
        return UncheckedProposal(value)
    }

    override fun read(buf: ByteBuffer): UncheckedProposal {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: UncheckedProposal) = 8UL

    override fun write(value: UncheckedProposal, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface UrlInterface {
    
    fun `asString`(): kotlin.String
    
    fun `query`(): kotlin.String?
    
    companion object
}

open class Url: Disposable, AutoCloseable, UrlInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_url(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_url(pointer!!, status)
        }
    }

    override fun `asString`(): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_url_as_string(
        it, _status)
}
    }
    )
    }
    

    override fun `query`(): kotlin.String? {
            return FfiConverterOptionalString.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_url_query(
        it, _status)
}
    }
    )
    }
    

    

    
    companion object {
        
    @Throws(UrlParseException::class) fun `parse`(`input`: kotlin.String): Url {
            return FfiConverterTypeUrl.lift(
    uniffiRustCallWithError(UrlParseException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_constructor_url_parse(
        FfiConverterString.lower(`input`),_status)
}
    )
    }
    

        
    }
    
}

public object FfiConverterTypeUrl: FfiConverter<Url, Pointer> {

    override fun lower(value: Url): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): Url {
        return Url(value)
    }

    override fun read(buf: ByteBuffer): Url {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: Url) = 8UL

    override fun write(value: Url, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface UrlParseExceptionInterface {
    
    companion object
}


open class UrlParseException : kotlin.Exception, Disposable, AutoCloseable, UrlParseExceptionInterface {


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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_urlparseerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_urlparseerror(pointer!!, status)
        }
    }

    

    
    
    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<UrlParseException> {
        override fun lift(error_buf: RustBuffer.ByValue): UrlParseException {
            val bb = error_buf.asByteBuffer()
            if (bb == null) {
                throw InternalException("?")
            }
            return FfiConverterTypeUrlParseError.read(bb)
        }
    }
    
}

public object FfiConverterTypeUrlParseError: FfiConverter<UrlParseException, Pointer> {

    override fun lower(value: UrlParseException): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): UrlParseException {
        return UrlParseException(value)
    }

    override fun read(buf: ByteBuffer): UrlParseException {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: UrlParseException) = 8UL

    override fun write(value: UrlParseException, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface V1ContextInterface {
    
    fun `processResponse`(`response`: kotlin.ByteArray): kotlin.String
    
    companion object
}

open class V1Context: Disposable, AutoCloseable, V1ContextInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_v1context(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_v1context(pointer!!, status)
        }
    }

    
    @Throws(ResponseException::class)override fun `processResponse`(`response`: kotlin.ByteArray): kotlin.String {
            return FfiConverterString.lift(
    callWithPointer {
    uniffiRustCallWithError(ResponseException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_v1context_process_response(
        it, FfiConverterByteArray.lower(`response`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeV1Context: FfiConverter<V1Context, Pointer> {

    override fun lower(value: V1Context): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): V1Context {
        return V1Context(value)
    }

    override fun read(buf: ByteBuffer): V1Context {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: V1Context) = 8UL

    override fun write(value: V1Context, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface V2GetContextInterface {
    
    fun `extractReq`(`ohttpRelay`: kotlin.String): RequestOhttpContext
    
    fun `processResponse`(`response`: kotlin.ByteArray, `ohttpCtx`: ClientResponse): kotlin.String?
    
    companion object
}

open class V2GetContext: Disposable, AutoCloseable, V2GetContextInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_v2getcontext(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_v2getcontext(pointer!!, status)
        }
    }

    
    @Throws(CreateRequestException::class)override fun `extractReq`(`ohttpRelay`: kotlin.String): RequestOhttpContext {
            return FfiConverterTypeRequestOhttpContext.lift(
    callWithPointer {
    uniffiRustCallWithError(CreateRequestException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_v2getcontext_extract_req(
        it, FfiConverterString.lower(`ohttpRelay`),_status)
}
    }
    )
    }
    

    
    @Throws(ResponseException::class)override fun `processResponse`(`response`: kotlin.ByteArray, `ohttpCtx`: ClientResponse): kotlin.String? {
            return FfiConverterOptionalString.lift(
    callWithPointer {
    uniffiRustCallWithError(ResponseException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_v2getcontext_process_response(
        it, FfiConverterByteArray.lower(`response`),FfiConverterTypeClientResponse.lower(`ohttpCtx`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeV2GetContext: FfiConverter<V2GetContext, Pointer> {

    override fun lower(value: V2GetContext): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): V2GetContext {
        return V2GetContext(value)
    }

    override fun read(buf: ByteBuffer): V2GetContext {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: V2GetContext) = 8UL

    override fun write(value: V2GetContext, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface V2PostContextInterface {
    
    fun `processResponse`(`response`: kotlin.ByteArray): V2GetContext
    
    companion object
}

open class V2PostContext: Disposable, AutoCloseable, V2PostContextInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_v2postcontext(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_v2postcontext(pointer!!, status)
        }
    }

    
    @Throws(EncapsulationException::class)override fun `processResponse`(`response`: kotlin.ByteArray): V2GetContext {
            return FfiConverterTypeV2GetContext.lift(
    callWithPointer {
    uniffiRustCallWithError(EncapsulationException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_v2postcontext_process_response(
        it, FfiConverterByteArray.lower(`response`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeV2PostContext: FfiConverter<V2PostContext, Pointer> {

    override fun lower(value: V2PostContext): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): V2PostContext {
        return V2PostContext(value)
    }

    override fun read(buf: ByteBuffer): V2PostContext {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: V2PostContext) = 8UL

    override fun write(value: V2PostContext, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface ValidationErrorInterface {
    
    companion object
}

open class ValidationError: Disposable, AutoCloseable, ValidationErrorInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_validationerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_validationerror(pointer!!, status)
        }
    }

    

    
    
    companion object
    
}

public object FfiConverterTypeValidationError: FfiConverter<ValidationError, Pointer> {

    override fun lower(value: ValidationError): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): ValidationError {
        return ValidationError(value)
    }

    override fun read(buf: ByteBuffer): ValidationError {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: ValidationError) = 8UL

    override fun write(value: ValidationError, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface WantsInputsInterface {
    
    fun `commitInputs`(): ProvisionalProposal
    
    fun `contributeInputs`(`replacementInputs`: List<InputPair>): WantsInputs
    
    fun `tryPreservingPrivacy`(`candidateInputs`: List<InputPair>): InputPair
    
    companion object
}

open class WantsInputs: Disposable, AutoCloseable, WantsInputsInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_wantsinputs(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_wantsinputs(pointer!!, status)
        }
    }

    override fun `commitInputs`(): ProvisionalProposal {
            return FfiConverterTypeProvisionalProposal.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_wantsinputs_commit_inputs(
        it, _status)
}
    }
    )
    }
    

    
    @Throws(InputContributionException::class)override fun `contributeInputs`(`replacementInputs`: List<InputPair>): WantsInputs {
            return FfiConverterTypeWantsInputs.lift(
    callWithPointer {
    uniffiRustCallWithError(InputContributionException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_wantsinputs_contribute_inputs(
        it, FfiConverterSequenceTypeInputPair.lower(`replacementInputs`),_status)
}
    }
    )
    }
    

    
    @Throws(SelectionException::class)override fun `tryPreservingPrivacy`(`candidateInputs`: List<InputPair>): InputPair {
            return FfiConverterTypeInputPair.lift(
    callWithPointer {
    uniffiRustCallWithError(SelectionException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_wantsinputs_try_preserving_privacy(
        it, FfiConverterSequenceTypeInputPair.lower(`candidateInputs`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeWantsInputs: FfiConverter<WantsInputs, Pointer> {

    override fun lower(value: WantsInputs): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): WantsInputs {
        return WantsInputs(value)
    }

    override fun read(buf: ByteBuffer): WantsInputs {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: WantsInputs) = 8UL

    override fun write(value: WantsInputs, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface WantsOutputsInterface {
    
    fun `commitOutputs`(): WantsInputs
    
    fun `outputSubstitution`(): kotlin.Boolean
    
    fun `replaceReceiverOutputs`(`replacementOutputs`: List<TxOut>, `drainScript`: Script): WantsOutputs
    
    fun `substituteReceiverScript`(`outputScript`: Script): WantsOutputs
    
    companion object
}

open class WantsOutputs: Disposable, AutoCloseable, WantsOutputsInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_wantsoutputs(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_wantsoutputs(pointer!!, status)
        }
    }

    override fun `commitOutputs`(): WantsInputs {
            return FfiConverterTypeWantsInputs.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_wantsoutputs_commit_outputs(
        it, _status)
}
    }
    )
    }
    

    override fun `outputSubstitution`(): kotlin.Boolean {
            return FfiConverterBoolean.lift(
    callWithPointer {
    uniffiRustCall() { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_wantsoutputs_output_substitution(
        it, _status)
}
    }
    )
    }
    

    
    @Throws(OutputSubstitutionException::class)override fun `replaceReceiverOutputs`(`replacementOutputs`: List<TxOut>, `drainScript`: Script): WantsOutputs {
            return FfiConverterTypeWantsOutputs.lift(
    callWithPointer {
    uniffiRustCallWithError(OutputSubstitutionException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_wantsoutputs_replace_receiver_outputs(
        it, FfiConverterSequenceTypeTxOut.lower(`replacementOutputs`),FfiConverterTypeScript.lower(`drainScript`),_status)
}
    }
    )
    }
    

    
    @Throws(OutputSubstitutionException::class)override fun `substituteReceiverScript`(`outputScript`: Script): WantsOutputs {
            return FfiConverterTypeWantsOutputs.lift(
    callWithPointer {
    uniffiRustCallWithError(OutputSubstitutionException) { _status ->
    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_method_wantsoutputs_substitute_receiver_script(
        it, FfiConverterTypeScript.lower(`outputScript`),_status)
}
    }
    )
    }
    

    

    
    
    companion object
    
}

public object FfiConverterTypeWantsOutputs: FfiConverter<WantsOutputs, Pointer> {

    override fun lower(value: WantsOutputs): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): WantsOutputs {
        return WantsOutputs(value)
    }

    override fun read(buf: ByteBuffer): WantsOutputs {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: WantsOutputs) = 8UL

    override fun write(value: WantsOutputs, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}




public interface WellKnownErrorInterface {
    
    companion object
}

open class WellKnownError: Disposable, AutoCloseable, WellKnownErrorInterface
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
                    UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_free_wellknownerror(ptr, status)
                }
            }
        }
    }

    fun uniffiClonePointer(): Pointer {
        return uniffiRustCall() { status ->
            UniffiLib.INSTANCE.uniffi_payjoin_ffi_fn_clone_wellknownerror(pointer!!, status)
        }
    }

    

    
    
    companion object
    
}

public object FfiConverterTypeWellKnownError: FfiConverter<WellKnownError, Pointer> {

    override fun lower(value: WellKnownError): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): WellKnownError {
        return WellKnownError(value)
    }

    override fun read(buf: ByteBuffer): WellKnownError {
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: WellKnownError) = 8UL

    override fun write(value: WellKnownError, buf: ByteBuffer) {
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}



data class PsbtInput (
    var `witnessUtxo`: TxOut?, 
    var `redeemScript`: Script?, 
    var `witnessScript`: Script?
) : Disposable {
    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        
    Disposable.destroy(
        this.`witnessUtxo`,
        this.`redeemScript`,
        this.`witnessScript`
    )
    }
    
    companion object
}

public object FfiConverterTypePsbtInput: FfiConverterRustBuffer<PsbtInput> {
    override fun read(buf: ByteBuffer): PsbtInput {
        return PsbtInput(
            FfiConverterOptionalTypeTxOut.read(buf),
            FfiConverterOptionalTypeScript.read(buf),
            FfiConverterOptionalTypeScript.read(buf),
        )
    }

    override fun allocationSize(value: PsbtInput) = (
            FfiConverterOptionalTypeTxOut.allocationSize(value.`witnessUtxo`) +
            FfiConverterOptionalTypeScript.allocationSize(value.`redeemScript`) +
            FfiConverterOptionalTypeScript.allocationSize(value.`witnessScript`)
    )

    override fun write(value: PsbtInput, buf: ByteBuffer) {
            FfiConverterOptionalTypeTxOut.write(value.`witnessUtxo`, buf)
            FfiConverterOptionalTypeScript.write(value.`redeemScript`, buf)
            FfiConverterOptionalTypeScript.write(value.`witnessScript`, buf)
    }
}



data class Request (
    var `url`: Url, 
    var `contentType`: kotlin.String, 
    var `body`: kotlin.ByteArray
) : Disposable {
    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        
    Disposable.destroy(
        this.`url`,
        this.`contentType`,
        this.`body`
    )
    }
    
    companion object
}

public object FfiConverterTypeRequest: FfiConverterRustBuffer<Request> {
    override fun read(buf: ByteBuffer): Request {
        return Request(
            FfiConverterTypeUrl.read(buf),
            FfiConverterString.read(buf),
            FfiConverterByteArray.read(buf),
        )
    }

    override fun allocationSize(value: Request) = (
            FfiConverterTypeUrl.allocationSize(value.`url`) +
            FfiConverterString.allocationSize(value.`contentType`) +
            FfiConverterByteArray.allocationSize(value.`body`)
    )

    override fun write(value: Request, buf: ByteBuffer) {
            FfiConverterTypeUrl.write(value.`url`, buf)
            FfiConverterString.write(value.`contentType`, buf)
            FfiConverterByteArray.write(value.`body`, buf)
    }
}



data class RequestOhttpContext (
    var `request`: Request, 
    var `ohttpCtx`: ClientResponse
) : Disposable {
    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        
    Disposable.destroy(
        this.`request`,
        this.`ohttpCtx`
    )
    }
    
    companion object
}

public object FfiConverterTypeRequestOhttpContext: FfiConverterRustBuffer<RequestOhttpContext> {
    override fun read(buf: ByteBuffer): RequestOhttpContext {
        return RequestOhttpContext(
            FfiConverterTypeRequest.read(buf),
            FfiConverterTypeClientResponse.read(buf),
        )
    }

    override fun allocationSize(value: RequestOhttpContext) = (
            FfiConverterTypeRequest.allocationSize(value.`request`) +
            FfiConverterTypeClientResponse.allocationSize(value.`ohttpCtx`)
    )

    override fun write(value: RequestOhttpContext, buf: ByteBuffer) {
            FfiConverterTypeRequest.write(value.`request`, buf)
            FfiConverterTypeClientResponse.write(value.`ohttpCtx`, buf)
    }
}



data class RequestResponse (
    var `request`: Request, 
    var `clientResponse`: ClientResponse
) : Disposable {
    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        
    Disposable.destroy(
        this.`request`,
        this.`clientResponse`
    )
    }
    
    companion object
}

public object FfiConverterTypeRequestResponse: FfiConverterRustBuffer<RequestResponse> {
    override fun read(buf: ByteBuffer): RequestResponse {
        return RequestResponse(
            FfiConverterTypeRequest.read(buf),
            FfiConverterTypeClientResponse.read(buf),
        )
    }

    override fun allocationSize(value: RequestResponse) = (
            FfiConverterTypeRequest.allocationSize(value.`request`) +
            FfiConverterTypeClientResponse.allocationSize(value.`clientResponse`)
    )

    override fun write(value: RequestResponse, buf: ByteBuffer) {
            FfiConverterTypeRequest.write(value.`request`, buf)
            FfiConverterTypeClientResponse.write(value.`clientResponse`, buf)
    }
}



data class RequestV1Context (
    var `request`: Request, 
    var `context`: V1Context
) : Disposable {
    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        
    Disposable.destroy(
        this.`request`,
        this.`context`
    )
    }
    
    companion object
}

public object FfiConverterTypeRequestV1Context: FfiConverterRustBuffer<RequestV1Context> {
    override fun read(buf: ByteBuffer): RequestV1Context {
        return RequestV1Context(
            FfiConverterTypeRequest.read(buf),
            FfiConverterTypeV1Context.read(buf),
        )
    }

    override fun allocationSize(value: RequestV1Context) = (
            FfiConverterTypeRequest.allocationSize(value.`request`) +
            FfiConverterTypeV1Context.allocationSize(value.`context`)
    )

    override fun write(value: RequestV1Context, buf: ByteBuffer) {
            FfiConverterTypeRequest.write(value.`request`, buf)
            FfiConverterTypeV1Context.write(value.`context`, buf)
    }
}



data class RequestV2PostContext (
    var `request`: Request, 
    var `context`: V2PostContext
) : Disposable {
    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        
    Disposable.destroy(
        this.`request`,
        this.`context`
    )
    }
    
    companion object
}

public object FfiConverterTypeRequestV2PostContext: FfiConverterRustBuffer<RequestV2PostContext> {
    override fun read(buf: ByteBuffer): RequestV2PostContext {
        return RequestV2PostContext(
            FfiConverterTypeRequest.read(buf),
            FfiConverterTypeV2PostContext.read(buf),
        )
    }

    override fun allocationSize(value: RequestV2PostContext) = (
            FfiConverterTypeRequest.allocationSize(value.`request`) +
            FfiConverterTypeV2PostContext.allocationSize(value.`context`)
    )

    override fun write(value: RequestV2PostContext, buf: ByteBuffer) {
            FfiConverterTypeRequest.write(value.`request`, buf)
            FfiConverterTypeV2PostContext.write(value.`context`, buf)
    }
}





sealed class Exception: kotlin.Exception(), Disposable  {
    
    class ReplyToSender(
        
        val v1: ReplyableException
        ) : Exception() {
        override val message
            get() = "v1=${ v1 }"
    }
    
    class V2(
        
        val v1: SessionException
        ) : Exception() {
        override val message
            get() = "v1=${ v1 }"
    }
    
    class Unexpected(
        ) : Exception() {
        override val message
            get() = ""
    }
    

    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<Exception> {
        override fun lift(error_buf: RustBuffer.ByValue): Exception = FfiConverterTypeError.lift(error_buf)
    }

    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        when(this) {
            is Exception.ReplyToSender -> {
                
    Disposable.destroy(
        this.v1
    )
                
            }
            is Exception.V2 -> {
                
    Disposable.destroy(
        this.v1
    )
                
            }
            is Exception.Unexpected -> {
            }
        }.let {  }
    }
    
}

public object FfiConverterTypeError : FfiConverterRustBuffer<Exception> {
    override fun read(buf: ByteBuffer): Exception {
        

        return when(buf.getInt()) {
            1 -> Exception.ReplyToSender(
                FfiConverterTypeReplyableError.read(buf),
                )
            2 -> Exception.V2(
                FfiConverterTypeSessionError.read(buf),
                )
            3 -> Exception.Unexpected()
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: Exception): ULong {
        return when(value) {
            is Exception.ReplyToSender -> (
                4UL
                + FfiConverterTypeReplyableError.allocationSize(value.v1)
            )
            is Exception.V2 -> (
                4UL
                + FfiConverterTypeSessionError.allocationSize(value.v1)
            )
            is Exception.Unexpected -> (
                4UL
            )
        }
    }

    override fun write(value: Exception, buf: ByteBuffer) {
        when(value) {
            is Exception.ReplyToSender -> {
                buf.putInt(1)
                FfiConverterTypeReplyableError.write(value.v1, buf)
                Unit
            }
            is Exception.V2 -> {
                buf.putInt(2)
                FfiConverterTypeSessionError.write(value.v1, buf)
                Unit
            }
            is Exception.Unexpected -> {
                buf.putInt(3)
                Unit
            }
        }.let {  }
    }

}





sealed class ResponseException: kotlin.Exception(), Disposable  {
    
    class WellKnown(
        
        val v1: WellKnownError
        ) : ResponseException() {
        override val message
            get() = "v1=${ v1 }"
    }
    
    class Validation(
        
        val v1: ValidationError
        ) : ResponseException() {
        override val message
            get() = "v1=${ v1 }"
    }
    
    class Unrecognized(
        
        val `errorCode`: kotlin.String, 
        
        val `msg`: kotlin.String
        ) : ResponseException() {
        override val message
            get() = "errorCode=${ `errorCode` }, msg=${ `msg` }"
    }
    

    companion object ErrorHandler : UniffiRustCallStatusErrorHandler<ResponseException> {
        override fun lift(error_buf: RustBuffer.ByValue): ResponseException = FfiConverterTypeResponseError.lift(error_buf)
    }

    
    @Suppress("UNNECESSARY_SAFE_CALL")
    override fun destroy() {
        when(this) {
            is ResponseException.WellKnown -> {
                
    Disposable.destroy(
        this.v1
    )
                
            }
            is ResponseException.Validation -> {
                
    Disposable.destroy(
        this.v1
    )
                
            }
            is ResponseException.Unrecognized -> {
                
    Disposable.destroy(
        this.`errorCode`,
        this.`msg`
    )
                
            }
        }.let {  }
    }
    
}

public object FfiConverterTypeResponseError : FfiConverterRustBuffer<ResponseException> {
    override fun read(buf: ByteBuffer): ResponseException {
        

        return when(buf.getInt()) {
            1 -> ResponseException.WellKnown(
                FfiConverterTypeWellKnownError.read(buf),
                )
            2 -> ResponseException.Validation(
                FfiConverterTypeValidationError.read(buf),
                )
            3 -> ResponseException.Unrecognized(
                FfiConverterString.read(buf),
                FfiConverterString.read(buf),
                )
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: ResponseException): ULong {
        return when(value) {
            is ResponseException.WellKnown -> (
                4UL
                + FfiConverterTypeWellKnownError.allocationSize(value.v1)
            )
            is ResponseException.Validation -> (
                4UL
                + FfiConverterTypeValidationError.allocationSize(value.v1)
            )
            is ResponseException.Unrecognized -> (
                4UL
                + FfiConverterString.allocationSize(value.`errorCode`)
                + FfiConverterString.allocationSize(value.`msg`)
            )
        }
    }

    override fun write(value: ResponseException, buf: ByteBuffer) {
        when(value) {
            is ResponseException.WellKnown -> {
                buf.putInt(1)
                FfiConverterTypeWellKnownError.write(value.v1, buf)
                Unit
            }
            is ResponseException.Validation -> {
                buf.putInt(2)
                FfiConverterTypeValidationError.write(value.v1, buf)
                Unit
            }
            is ResponseException.Unrecognized -> {
                buf.putInt(3)
                FfiConverterString.write(value.`errorCode`, buf)
                FfiConverterString.write(value.`msg`, buf)
                Unit
            }
        }.let {  }
    }

}




public object FfiConverterOptionalUByte: FfiConverterRustBuffer<kotlin.UByte?> {
    override fun read(buf: ByteBuffer): kotlin.UByte? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterUByte.read(buf)
    }

    override fun allocationSize(value: kotlin.UByte?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterUByte.allocationSize(value)
        }
    }

    override fun write(value: kotlin.UByte?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterUByte.write(value, buf)
        }
    }
}




public object FfiConverterOptionalULong: FfiConverterRustBuffer<kotlin.ULong?> {
    override fun read(buf: ByteBuffer): kotlin.ULong? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterULong.read(buf)
    }

    override fun allocationSize(value: kotlin.ULong?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterULong.allocationSize(value)
        }
    }

    override fun write(value: kotlin.ULong?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterULong.write(value, buf)
        }
    }
}




public object FfiConverterOptionalString: FfiConverterRustBuffer<kotlin.String?> {
    override fun read(buf: ByteBuffer): kotlin.String? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterString.read(buf)
    }

    override fun allocationSize(value: kotlin.String?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterString.allocationSize(value)
        }
    }

    override fun write(value: kotlin.String?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterString.write(value, buf)
        }
    }
}




public object FfiConverterOptionalTypeScript: FfiConverterRustBuffer<Script?> {
    override fun read(buf: ByteBuffer): Script? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterTypeScript.read(buf)
    }

    override fun allocationSize(value: Script?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterTypeScript.allocationSize(value)
        }
    }

    override fun write(value: Script?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterTypeScript.write(value, buf)
        }
    }
}




public object FfiConverterOptionalTypeUncheckedProposal: FfiConverterRustBuffer<UncheckedProposal?> {
    override fun read(buf: ByteBuffer): UncheckedProposal? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterTypeUncheckedProposal.read(buf)
    }

    override fun allocationSize(value: UncheckedProposal?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterTypeUncheckedProposal.allocationSize(value)
        }
    }

    override fun write(value: UncheckedProposal?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterTypeUncheckedProposal.write(value, buf)
        }
    }
}




public object FfiConverterOptionalTypeTxOut: FfiConverterRustBuffer<TxOut?> {
    override fun read(buf: ByteBuffer): TxOut? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterTypeTxOut.read(buf)
    }

    override fun allocationSize(value: TxOut?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterTypeTxOut.allocationSize(value)
        }
    }

    override fun write(value: TxOut?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterTypeTxOut.write(value, buf)
        }
    }
}




public object FfiConverterSequenceTypeInputPair: FfiConverterRustBuffer<List<InputPair>> {
    override fun read(buf: ByteBuffer): List<InputPair> {
        val len = buf.getInt()
        return List<InputPair>(len) {
            FfiConverterTypeInputPair.read(buf)
        }
    }

    override fun allocationSize(value: List<InputPair>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.map { FfiConverterTypeInputPair.allocationSize(it) }.sum()
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<InputPair>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterTypeInputPair.write(it, buf)
        }
    }
}




public object FfiConverterSequenceTypeOutPoint: FfiConverterRustBuffer<List<OutPoint>> {
    override fun read(buf: ByteBuffer): List<OutPoint> {
        val len = buf.getInt()
        return List<OutPoint>(len) {
            FfiConverterTypeOutPoint.read(buf)
        }
    }

    override fun allocationSize(value: List<OutPoint>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.map { FfiConverterTypeOutPoint.allocationSize(it) }.sum()
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<OutPoint>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterTypeOutPoint.write(it, buf)
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











