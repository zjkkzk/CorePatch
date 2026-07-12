package org.lsposed.corepatch

import android.annotation.SuppressLint
import android.graphics.Point
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import sun.misc.Unsafe

typealias BeforeCallback = (XposedHelper.BeforeHookCallback) -> Unit
typealias AfterCallback = (XposedHelper.AfterHookCallback) -> Unit

object XposedHelper {
    lateinit var xposedModule: XposedModule
        private set
    lateinit var hostClassLoader: ClassLoader
        private set
    val prefs by lazy { xposedModule.getRemotePreferences("conf") }
    private val unsafeInstance by lazy { getUnsafe() }
    private val fieldOffsetValue by lazy { getFieldOffsetOffset() }

    fun setXposedModule(module: XposedModule) {
        xposedModule = module
    }

    fun setHostClassLoader(classLoader: ClassLoader) {
        hostClassLoader = classLoader
    }

    class BeforeHookCallback(private val chain: XposedInterface.Chain) {
        val thisObject: Any? get() = chain.thisObject
        val args: Array<Any?> = chain.args.toTypedArray()
        private var skipped = false
        private var skipResult: Any? = null

        fun returnAndSkip(result: Any?) {
            skipped = true
            skipResult = result
        }

        fun isSkipped() = skipped
        fun getSkipResult() = skipResult
    }

    class AfterHookCallback(
        private val chain: XposedInterface.Chain,
        var result: Any?,
        var throwable: Throwable?
    ) {
        val thisObject: Any? get() = chain.thisObject
        val args: Array<Any?> get() = chain.args.toTypedArray()
    }

    internal class CustomHooker(
        val beforeCallback: BeforeCallback = {},
        val afterCallback: AfterCallback = {},
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            var result: Any? = null
            var throwable: Throwable? = null
            var skipped = false

            val bcb = BeforeHookCallback(chain)
            beforeCallback(bcb)
            if (bcb.isSkipped()) {
                result = bcb.getSkipResult()
                skipped = true
            }

            if (!skipped) {
                try {
                    result = chain.proceed(bcb.args)
                } catch (t: Throwable) {
                    throwable = t
                }
            }

            val acb = AfterHookCallback(chain, result, throwable)
            afterCallback(acb)
            result = acb.result
            throwable = acb.throwable

            if (throwable != null) {
                throw throwable
            }
            return result
        }
    }

    fun hookBefore(
        member: Executable, callback: BeforeCallback
    ): XposedInterface.HookHandle {
        return xposedModule.hook(member).intercept(CustomHooker(beforeCallback = callback))
    }

    fun hookAfter(
        executable: Executable, callback: AfterCallback
    ): XposedInterface.HookHandle {
        return xposedModule.hook(executable).intercept(CustomHooker(afterCallback = callback))
    }

    fun log(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e("CorePatch", message, throwable)
            xposedModule.log(Log.ERROR, "CorePatch", message, throwable)
        } else {
            if (BuildConfig.DEBUG) {
                Log.d("CorePatch", message)
            }
            xposedModule.log(Log.DEBUG, "CorePatch", message)
        }
    }

    fun deoptimize(method: Method): Boolean {
        return xposedModule.deoptimize(method)
    }

    fun getOriginInvoker(method: Method) =
        xposedModule.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN)

    fun setStaticBoolean(field: Field, value: Boolean) {
        try {
            // Resolve the target field before reading its internal ART offset.
            field.isAccessible = true
            field.get(null)
        } catch (_: IllegalAccessException) {
        }

        val offset = unsafeInstance.getInt(field, fieldOffsetValue).toLong()
        unsafeInstance.putBoolean(field.declaringClass, offset, value)
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun getUnsafe(): Unsafe {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        return unsafeField.get(null) as Unsafe
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SoonBlockedPrivateApi")
    private fun getFieldOffsetOffset(): Long {
        var noSuchFieldException: NoSuchFieldException? = null
        try {
            val offsetField = Field::class.java.getDeclaredField("offset")
            offsetField.isAccessible = true
            offsetField.getInt(offsetField)
            return unsafeInstance.objectFieldOffset(offsetField)
        } catch (e: NoSuchFieldException) {
            noSuchFieldException = e
        } catch (_: IllegalAccessException) {
        } catch (_: UnsupportedOperationException) {
        }

        val probeField = Point::class.java.getDeclaredField("x")
        probeField.getInt(Point())
        val fieldOffset = unsafeInstance.objectFieldOffset(probeField).toInt()
        for (offset in 8 until 256 step 4) {
            val offsetLong = offset.toLong()
            if (unsafeInstance.getInt(probeField, offsetLong) != fieldOffset) continue

            val modifiedOffset = fieldOffset.inv()
            unsafeInstance.putInt(probeField, offsetLong, modifiedOffset)
            val currentOffset = unsafeInstance.objectFieldOffset(probeField).toInt()
            unsafeInstance.putInt(probeField, offsetLong, fieldOffset)
            if (currentOffset == modifiedOffset) return offsetLong
        }
        throw noSuchFieldException ?: NoSuchFieldException("Field.offset")
    }
}
