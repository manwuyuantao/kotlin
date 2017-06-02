/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.debugger.evaluate.classLoading

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.sun.jdi.*
import javax.xml.bind.DatatypeConverter


abstract class AbstractAndroidClassLoadingAdapter : ClassLoadingAdapter {
    protected fun dex(context: EvaluationContextImpl, classes: Collection<ClassToLoad>): ByteArray? {
        return AndroidDexer.getInstances(context.project).single().dex(classes)
    }

    override fun mirrorOfByteArray(bytes: ByteArray, context: EvaluationContextImpl, process: DebugProcessImpl): ArrayReference {
        val base64Class = process.findClass(context, "android.util.Base64", context.classLoader) as? ClassType
        val decodeMethod = base64Class?.concreteMethodByName("decode", "(Ljava/lang/String;I)[B")

        if (base64Class == null || decodeMethod == null) {
            return super.mirrorOfByteArray(bytes, context, process)
        }

        val vm = process.virtualMachineProxy

        val base64String = DatatypeConverter.printBase64Binary(bytes)
        val strMirror = vm.mirrorOf(base64String)

        return process.invokeMethod(context, base64Class, decodeMethod, listOf(strMirror, vm.mirrorOf(0))) as ArrayReference
    }

    protected fun wrapToByteBuffer(bytes: ArrayReference, context: EvaluationContext, process: DebugProcessImpl): ObjectReference {
        val byteBufferClass = process.findClass(context, "java.nio.ByteBuffer", context.classLoader) as ClassType
        val wrapMethod = byteBufferClass.concreteMethodByName("wrap", "([B)Ljava/nio/ByteBuffer;")
                         ?: error("'wrap' method not found")

        return process.invokeMethod(context, byteBufferClass, wrapMethod, listOf(bytes)) as ObjectReference
    }

    protected fun DebugProcessImpl.tryLoadClass(
            context: EvaluationContextImpl,
            fqName: String,
            classLoader: ClassLoaderReference?
    ): ReferenceType? {
        return try {
            loadClass(context, fqName, classLoader)
        } catch (e: Throwable) {
            null
        }
    }
}

internal val ClassLoaderReference.isDalvikClassLoader: Boolean
    get() = referenceType()?.name()?.startsWith("dalvik.system.") ?: false