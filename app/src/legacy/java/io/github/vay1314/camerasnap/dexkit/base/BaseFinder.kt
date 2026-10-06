package io.github.vay1314.camerasnap.dexkit.base

import io.luckypray.dexkit.DexKitBridge
import io.github.vay1314.camerasnap.hook.base.BaseHookerWithDexKit
import io.luckypray.dexkit.builder.BatchFindArgs
import io.luckypray.dexkit.descriptor.member.DexClassDescriptor
import io.luckypray.dexkit.descriptor.member.DexMethodDescriptor
import io.luckypray.dexkit.enums.MatchType

abstract class BaseFinder {
    companion object {
        var finders = mutableListOf<BaseFinder>()
        lateinit var batchFindClassesUsingStringsResultMap: Map<String, List<DexClassDescriptor>>
        lateinit var batchFindMethodsUsingStringsResultMap: Map<String, List<DexMethodDescriptor>>

        fun DexKitBridge.onFinishLoadFinder() {
            if (finders.isEmpty()) return

            batchFindClassesUsingStringsResultMap = batchFindClassesUsingStrings {
                matchType = MatchType.FULL
                finders.forEach { apply(it.prepareBatchFindClassesUsingStrings()) }
            }
            batchFindMethodsUsingStringsResultMap = batchFindMethodsUsingStrings {
                matchType = MatchType.FULL
                finders.forEach { apply(it.prepareBatchFindMethodsUsingStrings()) }
            }
            finders.forEach { it.onFindMembers() }
        }
    }

    lateinit var bridge: DexKitBridge

    open fun prepareBatchFindClassesUsingStrings(): BatchFindArgs.Builder.() -> Unit = {}

    open fun prepareBatchFindMethodsUsingStrings(): BatchFindArgs.Builder.() -> Unit = {}

    abstract fun onFindMembers()
}