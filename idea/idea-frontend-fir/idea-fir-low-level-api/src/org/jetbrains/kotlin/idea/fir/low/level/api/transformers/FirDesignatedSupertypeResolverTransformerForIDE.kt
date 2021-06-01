/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.*
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.toSymbol
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignation
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.api.collectDesignation
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.FirFileBuilder
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.ModuleFileCache
import org.jetbrains.kotlin.idea.fir.low.level.api.lazy.resolve.FirLazyDeclarationResolver
import org.jetbrains.kotlin.idea.fir.low.level.api.transformers.FirLazyTransformerForIDE.Companion.isResolvedForAllDeclarations
import org.jetbrains.kotlin.idea.fir.low.level.api.transformers.FirLazyTransformerForIDE.Companion.updateResolvedForAllDeclarations
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhase
import org.jetbrains.kotlin.idea.fir.low.level.api.util.getContainingFile
import org.jetbrains.kotlin.idea.util.ifTrue

internal class FirDesignatedSupertypeResolverTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    private val session: FirSession,
    private val scopeSession: ScopeSession,
    private val isOnAirResolve: Boolean,
    private val moduleFileCache: ModuleFileCache,
    private val firLazyDeclarationResolver: FirLazyDeclarationResolver,
    private val firProviderInterceptor: FirProviderInterceptor?,
    private val checkPCE: Boolean,
) : FirLazyTransformerForIDE {

    private val supertypeComputationSession = SupertypeComputationSession()

    private inner class DesignatedFirSupertypeResolverVisitor(classDesignation: FirDeclarationUntypedDesignation) :
        FirSupertypeResolverVisitor(
            session = session,
            supertypeComputationSession = supertypeComputationSession,
            scopeSession = scopeSession,
            scopeForLocalClass = null,
            localClassesNavigationInfo = null,
            firProviderInterceptor = firProviderInterceptor,
        ) {
        val declarationTransformer = IDEDeclarationTransformer(classDesignation)

        override fun visitDeclarationContent(declaration: FirDeclaration, data: Any?) {
            declarationTransformer.visitDeclarationContent(this, declaration, data) {
                super.visitDeclarationContent(declaration, data)
                declaration
            }
        }
    }

    private inner class DesignatedFirApplySupertypesTransformer(classDesignation: FirDeclarationUntypedDesignation) :
        FirApplySupertypesTransformer(supertypeComputationSession) {

        override fun transformRegularClass(regularClass: FirRegularClass, data: Any?): FirStatement {
            return if (regularClass.resolvePhase >= FirResolvePhase.SUPER_TYPES)
                transformDeclarationContent(regularClass, data) as FirStatement
            else super.transformRegularClass(regularClass, data)
        }

        override fun transformAnonymousObject(anonymousObject: FirAnonymousObject, data: Any?): FirStatement {
            return if (anonymousObject.resolvePhase >= FirResolvePhase.SUPER_TYPES)
                transformDeclarationContent(anonymousObject, data) as FirStatement
            else super.transformAnonymousObject(anonymousObject, data)
        }

        override fun transformTypeAlias(typeAlias: FirTypeAlias, data: Any?): FirDeclaration {
            return if (typeAlias.resolvePhase >= FirResolvePhase.SUPER_TYPES)
                transformDeclarationContent(typeAlias, data)
            else super.transformTypeAlias(typeAlias, data)
        }

        val declarationTransformer = IDEDeclarationTransformer(classDesignation)

        override fun needReplacePhase(firDeclaration: FirDeclaration): Boolean = firDeclaration.resolvePhase < FirResolvePhase.SUPER_TYPES

        override fun transformDeclarationContent(declaration: FirDeclaration, data: Any?): FirDeclaration {
            return declarationTransformer.transformDeclarationContent(this, declaration, data) {
                super.transformDeclarationContent(declaration, data)
            }
        }
    }

    private fun collect(designation: FirDeclarationUntypedDesignationWithFile): Set<FirDeclarationUntypedDesignationWithFile> {
        val visited = mutableSetOf<FirDeclarationUntypedDesignationWithFile>()
        val toVisit = mutableSetOf<FirDeclarationUntypedDesignationWithFile>()
        toVisit.add(designation)

        while (toVisit.isNotEmpty()) {
            for (nowVisit in toVisit) {
                val resolver = DesignatedFirSupertypeResolverVisitor(nowVisit)
                if (checkPCE) {
                    FirFileBuilder.runCustomResolveWithPCECheck(nowVisit.firFile, moduleFileCache) {
                        firLazyDeclarationResolver.lazyResolveFileDeclarationWithoutLock(
                            firFile = nowVisit.firFile,
                            moduleFileCache = moduleFileCache,
                            toPhase = FirResolvePhase.IMPORTS,
                            scopeSession = scopeSession,
                            checkPCE = true,
                        )
                        nowVisit.firFile.accept(resolver, null)
                    }
                } else {
                    FirFileBuilder.runCustomResolveUnderLock(nowVisit.firFile, moduleFileCache) {
                        firLazyDeclarationResolver.lazyResolveFileDeclarationWithoutLock(
                            firFile = nowVisit.firFile,
                            moduleFileCache = moduleFileCache,
                            toPhase = FirResolvePhase.IMPORTS,
                            scopeSession = scopeSession,
                            checkPCE = false,
                        )
                        nowVisit.firFile.accept(resolver, null)
                    }
                }
                resolver.declarationTransformer.ensureDesignationPassed()
            }
            visited.addAll(toVisit)
            toVisit.clear()

            for (value in supertypeComputationSession.supertypeStatusMap.values) {
                if (value !is SupertypeComputationStatus.Computed) continue
                for (reference in value.supertypeRefs) {
                    val classLikeDeclaration = reference.type.toSymbol(session)?.fir
                    if (classLikeDeclaration !is FirClassLikeDeclaration<*>) continue
                    if (visited.any { it.declaration == classLikeDeclaration }) continue
                    val containingFile = moduleFileCache.getContainerFirFile(classLikeDeclaration) ?: continue
                    toVisit.add(classLikeDeclaration.collectDesignation(containingFile))
                }
            }
        }
        return visited
    }

    private fun apply(visited: Set<FirDeclarationUntypedDesignationWithFile>) {
        fun applyToFileSymbols(designations: List<FirDeclarationUntypedDesignationWithFile>) {
            for (designation in designations) {
                if (designation.declaration.resolvePhase >= FirResolvePhase.SUPER_TYPES) continue
                val applier = DesignatedFirApplySupertypesTransformer(designation)
                designation.firFile.transform<FirElement, Void?>(applier, null)
                applier.declarationTransformer.ensureDesignationPassed()
                designation.declaration.ensureResolvedDeep()
            }
        }

        val filesToDesignations = visited.groupBy { it.firFile }
        for (designationsPerFile in filesToDesignations) {
            if (checkPCE) {
                FirFileBuilder.runCustomResolveWithPCECheck(designationsPerFile.key, moduleFileCache) {
                    applyToFileSymbols(designationsPerFile.value)
                }
            } else {
                FirFileBuilder.runCustomResolveUnderLock(designationsPerFile.key, moduleFileCache) {
                    applyToFileSymbols(designationsPerFile.value)
                }
            }
        }
    }

    override fun transformDeclaration() {
        check(designation.firFile.resolvePhase >= FirResolvePhase.IMPORTS) {
            "Invalid resolve phase of file. Should be IMPORTS but found ${designation.firFile.resolvePhase}"
        }

        val targetDesignation = if (designation.declaration !is FirClassLikeDeclaration<*>) {
            val resolvableTarget = designation.path.lastOrNull() ?: return
            check(resolvableTarget is FirClassLikeDeclaration<*>)
            val targetPath = designation.path.dropLast(1)
            FirDeclarationUntypedDesignationWithFile(targetPath, resolvableTarget, false, designation.firFile)
        } else designation

        if (targetDesignation.isResolvedForAllDeclarations(FirResolvePhase.SUPER_TYPES, isOnAirResolve)) return
        targetDesignation.declaration.updateResolvedForAllDeclarations(FirResolvePhase.SUPER_TYPES)

        val collected = collect(targetDesignation)
        supertypeComputationSession.breakLoops(session)
        apply(collected)
    }

    private fun FirDeclaration.ensureResolvedDeep() {
        ensureResolved()
        if (this is FirRegularClass) {
            declarations.forEach { it.ensureResolvedDeep() }
        }
    }

    private fun FirDeclaration.ensureResolved() {
        when (this) {
            is FirFunction<*> -> Unit
            is FirProperty -> Unit
            is FirRegularClass -> {
                ensurePhase(FirResolvePhase.SUPER_TYPES)
                check(superTypeRefs.all { it is FirResolvedTypeRef })
            }
            is FirTypeAlias -> {
                ensurePhase(FirResolvePhase.SUPER_TYPES)
                check(this.expandedTypeRef is FirResolvedTypeRef)
            }
            is FirEnumEntry -> Unit
            is FirField -> Unit
            is FirAnonymousInitializer -> Unit
            else -> error { "Unexpected type: ${this::class.simpleName}" }
        }
    }
}
