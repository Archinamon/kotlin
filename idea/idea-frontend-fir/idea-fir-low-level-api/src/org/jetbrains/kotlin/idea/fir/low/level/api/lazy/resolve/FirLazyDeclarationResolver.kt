/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.lazy.resolve

import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.FirProvider
import org.jetbrains.kotlin.fir.resolve.symbolProvider
import org.jetbrains.kotlin.fir.resolve.transformers.FirProviderInterceptor
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirTowerDataContextCollector
import org.jetbrains.kotlin.idea.fir.low.level.api.api.*
import org.jetbrains.kotlin.idea.fir.low.level.api.element.builder.getNonLocalContainingOrThisDeclaration
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.FirFileBuilder
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.ModuleFileCache
import org.jetbrains.kotlin.idea.fir.low.level.api.providers.firIdeProvider
import org.jetbrains.kotlin.idea.fir.low.level.api.transformers.*
import org.jetbrains.kotlin.idea.fir.low.level.api.transformers.FirLazyTransformerForIDE.Companion.resolvePhaseForAllDeclarations
import org.jetbrains.kotlin.idea.fir.low.level.api.util.*
import org.jetbrains.kotlin.idea.util.ifTrue
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

enum class ResolveType {
    FileAnnotations,
    CallableReturnType,
    ClassSuperTypes,
    DeclarationStatus,
    ValueParametersTypes,
    TypeParametersTypes,
    AnnotationType,
    AnnotationParameters,
    CallableBodyResolve,
    ResolveForMemberScope,
    ResolveForSuperMembers,
    CallableContracts,
    NoResolve,
}

internal class FirLazyDeclarationResolver(
    private val firFileBuilder: FirFileBuilder
) {
    fun lazyResolveDeclaration(
        firDeclaration: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        toResolveType: ResolveType,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean = false,
    ) {
        check(toResolveType == ResolveType.CallableReturnType)
        lazyResolveDeclaration(
            firDeclarationToResolve = firDeclaration,
            containerFirFile = firDeclaration.getContainingFileUnsafe(),
            moduleFileCache = moduleFileCache,
            toPhase = FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE,
            scopeSession = scopeSession,
            checkPCE = checkPCE,
        )
    }

    /**
     * Fully resolve file annotations (synchronized)
     * @see resolveFileAnnotationsWithoutLock not synchronized
     */
    fun resolveFileAnnotations(
        firFile: FirFile,
        annotations: List<FirAnnotationCall>,
        moduleFileCache: ModuleFileCache,
        scopeSession: ScopeSession = ScopeSession(),
        collector: FirTowerDataContextCollector? = null,
    ) {
        FirFileBuilder.runCustomResolveUnderLock(firFile, moduleFileCache) {
            lazyResolveFileDeclaration(
                firFile = firFile,
                moduleFileCache = moduleFileCache,
                scopeSession = scopeSession,
                toPhase = FirResolvePhase.IMPORTS,
                checkPCE = false,
            )
            resolveFileAnnotationsWithoutLock(firFile, annotations, scopeSession, collector)
        }
    }

    /**
     * Fully resolve file annotations (not synchronized)
     * @see resolveFileAnnotations synchronized version
     */
    private fun resolveFileAnnotationsWithoutLock(
        firFile: FirFile,
        annotations: List<FirAnnotationCall>,
        scopeSession: ScopeSession,
        collector: FirTowerDataContextCollector? = null,
    ) {
        FirFileAnnotationsResolveTransformer(
            firFile = firFile,
            annotations = annotations,
            session = firFile.moduleData.session,
            scopeSession = scopeSession,
            firTowerDataContextCollector = collector,
        ).transformDeclaration()
    }

    private fun FirDeclaration.isValidForResolve(): Boolean = when (origin) {
        is FirDeclarationOrigin.Source,
        is FirDeclarationOrigin.Delegated,
        is FirDeclarationOrigin.Synthetic,
        is FirDeclarationOrigin.SubstitutionOverride,
        is FirDeclarationOrigin.IntersectionOverride -> {
            when (this) {
                is FirSimpleFunction,
                is FirProperty,
                is FirPropertyAccessor,
                is FirField,
                is FirConstructor -> resolvePhase < FirResolvePhase.BODY_RESOLVE
                else -> true
            }
        }
        else -> {
            check(resolvePhase == FirResolvePhase.BODY_RESOLVE) {
                "Expected body resolve phase for origin $origin but found $resolvePhase"
            }
            false
        }
    }

    internal fun lazyResolveFileDeclaration(
        firFile: FirFile,
        moduleFileCache: ModuleFileCache,
        toPhase: FirResolvePhase,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean = false,
        collector: FirTowerDataContextCollector? = null,
    ) {
        check(toPhase <= FirResolvePhase.TYPES)
        if (checkPCE) {
            FirFileBuilder.runCustomResolveWithPCECheck(firFile, moduleFileCache) {
                lazyResolveFileDeclarationWithoutLock(
                    firFile = firFile,
                    moduleFileCache = moduleFileCache,
                    toPhase = toPhase,
                    scopeSession = scopeSession,
                    checkPCE = checkPCE,
                    collector = collector
                )
            }
        } else {
            FirFileBuilder.runCustomResolveUnderLock(firFile, moduleFileCache) {
                lazyResolveFileDeclarationWithoutLock(
                    firFile = firFile,
                    moduleFileCache = moduleFileCache,
                    toPhase = toPhase,
                    scopeSession = scopeSession,
                    checkPCE = checkPCE,
                    collector = collector
                )
            }
        }
    }

    internal fun lazyResolveFileDeclarationWithoutLock(
        firFile: FirFile,
        moduleFileCache: ModuleFileCache,
        toPhase: FirResolvePhase,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean = false,
        collector: FirTowerDataContextCollector? = null,
    ) {
        check(toPhase <= FirResolvePhase.TYPES)

        if (firFile.resolvePhase == FirResolvePhase.RAW_FIR) {
            firFileBuilder.runResolveWithoutLock(
                firFile = firFile,
                fromPhase = FirResolvePhase.RAW_FIR,
                toPhase = FirResolvePhase.IMPORTS,
                scopeSession = scopeSession,
                checkPCE = checkPCE
            )
        }
        //Temporary resolve file only for annotations
        if (toPhase > FirResolvePhase.IMPORTS) {
            resolveFileAnnotations(firFile, firFile.annotations, moduleFileCache, scopeSession, collector)
        }
    }

    /**
     * Run designated resolve only designation with fully resolved path (synchronized).
     * Suitable for body resolve or/and on-air resolve.
     * @see lazyResolveDeclaration for ordinary resolve
     * @param firDeclarationToResolve target non-local declaration
     * @param isOnAirResolve should be true when node does not belong to it's true designation (OnAir resolve in custom context)
     */
    fun lazyResolveDeclaration(
        firDeclarationToResolve: FirDeclaration,
        containerFirFile: FirFile,
        moduleFileCache: ModuleFileCache,
        scopeSession: ScopeSession = ScopeSession(),
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
        isOnAirResolve: Boolean = false,
        towerDataContextCollector: FirTowerDataContextCollector? = null,
    ) {
        if (toPhase == FirResolvePhase.RAW_FIR) return
        if (!firDeclarationToResolve.isValidForResolve()) return

        lazyResolveFileDeclaration(
            firFile = containerFirFile,
            moduleFileCache = moduleFileCache,
            toPhase = FirResolvePhase.IMPORTS,
            scopeSession = scopeSession,
            checkPCE = checkPCE
        )
        if (toPhase == FirResolvePhase.IMPORTS) return

        val provider = containerFirFile.moduleData.session.firIdeProvider
        val resolvableDeclaration = firDeclarationToResolve.getNonLocalDeclarationToResolve(provider, moduleFileCache)
        if (!resolvableDeclaration.isValidForResolve()) return
        val designation = resolvableDeclaration.collectDesignation(containerFirFile)
        val resolvePhase = designation.resolvePhaseForAllDeclarations(isOnAirResolve)
        if (resolvePhase >= toPhase) return

        if (checkPCE) {
            FirFileBuilder.runCustomResolveWithPCECheck(containerFirFile, moduleFileCache) {
                runLazyDesignatedResolveWithoutLock(
                    designation = designation,
                    moduleFileCache = moduleFileCache,
                    scopeSession = scopeSession,
                    toPhase = toPhase,
                    checkPCE = checkPCE,
                    isOnAirResolve = isOnAirResolve,
                    towerDataContextCollector = towerDataContextCollector,
                )
            }
        } else {
            FirFileBuilder.runCustomResolveUnderLock(containerFirFile, moduleFileCache) {
                runLazyDesignatedResolveWithoutLock(
                    designation = designation,
                    moduleFileCache = moduleFileCache,
                    scopeSession = scopeSession,
                    toPhase = toPhase,
                    checkPCE = checkPCE,
                    isOnAirResolve = isOnAirResolve,
                    towerDataContextCollector = towerDataContextCollector,
                )
            }
        }
    }

    /**
     * Designated resolve (not synchronized)
     * @see runLazyResolveWithoutLock for ordinary resolve
     * @see lazyResolveDeclaration synchronized version
     */
    internal fun runLazyDesignatedResolveWithoutLock(
        designation: FirDeclarationUntypedDesignationWithFile,
        moduleFileCache: ModuleFileCache,
        scopeSession: ScopeSession,
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
        isOnAirResolve: Boolean,
        towerDataContextCollector: FirTowerDataContextCollector? = null,
    ) {
        check(!designation.isLocalDesignation) { "Could not resolve local designation" }

        val designationPhase = designation.resolvePhaseForAllDeclarations(isOnAirResolve)
        var currentPhase = maxOf(designationPhase, FirResolvePhase.IMPORTS)
        if (currentPhase >= toPhase) return

        //This needed to override standard symbol resolve in supertype transformer with adding on-air created symbols
        val firProviderInterceptor = isOnAirResolve.ifTrue {
            FirProviderInterceptorForIDE.createForFirElement(
                session = designation.firFile.moduleData.session,
                firFile = designation.firFile,
                element = designation.declaration
            )
        }

        while (currentPhase < toPhase) {
            currentPhase = currentPhase.next
            if (currentPhase.pluginPhase) continue
            if (checkPCE) checkCanceled()
            FirLazyBodiesCalculator.calculateLazyBodiesInsideIfNeeded(designation, currentPhase)

            runLazyResolvePhase(
                phase = currentPhase,
                scopeSession = scopeSession,
                isOnAirResolve = isOnAirResolve,
                moduleFileCache = moduleFileCache,
                designation = designation,
                towerDataContextCollector = towerDataContextCollector,
                firProviderInterceptor = firProviderInterceptor,
                checkPCE = checkPCE,
            )
        }
    }

    private fun runLazyResolvePhase(
        phase: FirResolvePhase,
        scopeSession: ScopeSession,
        isOnAirResolve: Boolean,
        moduleFileCache: ModuleFileCache,
        designation: FirDeclarationUntypedDesignationWithFile,
        towerDataContextCollector: FirTowerDataContextCollector?,
        firProviderInterceptor: FirProviderInterceptor?,
        checkPCE: Boolean,
    ) {
        val transformer = phase.createLazyTransformer(
            designation,
            scopeSession,
            isOnAirResolve,
            moduleFileCache,
            towerDataContextCollector,
            firProviderInterceptor,
            checkPCE,
        )

        firFileBuilder.firPhaseRunner.runPhaseWithCustomResolve(phase) {
            transformer.transformDeclaration()
        }
    }

    private fun FirResolvePhase.createLazyTransformer(
        designation: FirDeclarationUntypedDesignationWithFile,
        scopeSession: ScopeSession,
        isOnAirResolve: Boolean,
        moduleFileCache: ModuleFileCache,
        towerDataContextCollector: FirTowerDataContextCollector?,
        firProviderInterceptor: FirProviderInterceptor?,
        checkPCE: Boolean,
    ): FirLazyTransformerForIDE = when (this) {
        FirResolvePhase.SUPER_TYPES -> FirDesignatedSupertypeResolverTransformerForIDE(
            designation = designation,
            session = designation.firFile.moduleData.session,
            scopeSession = scopeSession,
            isOnAirResolve = isOnAirResolve,
            moduleFileCache = moduleFileCache,
            firLazyDeclarationResolver = this@FirLazyDeclarationResolver,
            firProviderInterceptor = firProviderInterceptor,
            checkPCE = checkPCE,
        )
        FirResolvePhase.SEALED_CLASS_INHERITORS -> FirLazyTransformerForIDE.DUMMY
        FirResolvePhase.TYPES -> FirDesignatedTypeResolverTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
        )
        FirResolvePhase.STATUS -> FirDesignatedStatusResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
        )
        FirResolvePhase.CONTRACTS -> FirDesignatedContractsResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
        )
        FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE -> FirDesignatedImplicitTypesTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
            towerDataContextCollector
        )
        FirResolvePhase.BODY_RESOLVE -> FirDesignatedBodyResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
            towerDataContextCollector,
            firProviderInterceptor,
        )
        else -> error("Non-lazy phase $this")
    }

    private fun FirDeclaration.getNonLocalDeclarationToResolve(provider: FirProvider, moduleFileCache: ModuleFileCache): FirDeclaration {
        if (this is FirFile) return this

        if (this is FirPropertyAccessor || this is FirTypeParameter || this is FirValueParameter) {
            val ktContainingResolvableDeclaration = when (val psi = this.psi) {
                is KtPropertyAccessor -> psi.property
                is KtProperty -> psi
                is KtParameter, is KtTypeParameter -> psi.getNonLocalContainingOrThisDeclaration()
                    ?: error("Cannot find containing declaration for KtParameter")
                is KtCallExpression -> {
                    check(this.source?.kind == FirFakeSourceElementKind.DefaultAccessor)
                    val delegationCall = psi.parent as KtPropertyDelegate
                    delegationCall.parent as KtProperty
                }
                null -> error("Cannot find containing declaration for KtParameter")
                else -> error("Invalid source of property accessor ${psi::class}")
            }

            val targetElement =
                if (declarationCanBeLazilyResolved(ktContainingResolvableDeclaration)) ktContainingResolvableDeclaration
                else ktContainingResolvableDeclaration.getNonLocalContainingOrThisDeclaration()
            check(targetElement != null) { "Container for local declaration cannot be null" }

            return targetElement.findSourceNonLocalFirDeclaration(
                firFileBuilder = firFileBuilder,
                firSymbolProvider = moduleData.session.symbolProvider,
                moduleFileCache = moduleFileCache
            )
        }

        val ktDeclaration = (psi as? KtDeclaration) ?: run {
            (source as? FirFakeSourceElement<*>).psi?.parentOfType()
        }
        check(ktDeclaration is KtDeclaration) {
            "FirDeclaration should have a PSI of type KtDeclaration"
        }

        if (source !is FirFakeSourceElement<*> && declarationCanBeLazilyResolved(ktDeclaration)) return this
        val nonLocalPsi = ktDeclaration.getNonLocalContainingOrThisDeclaration()
            ?: error("Container for local declaration cannot be null")
        return nonLocalPsi.findSourceNonLocalFirDeclaration(firFileBuilder, provider.symbolProvider, moduleFileCache)
    }

    companion object {
        fun declarationCanBeLazilyResolved(declaration: KtDeclaration): Boolean {
            return when (declaration) {
                !is KtNamedDeclaration -> false
                is KtDestructuringDeclarationEntry, is KtFunctionLiteral, is KtTypeParameter -> false
                is KtPrimaryConstructor -> false
                is KtParameter -> declaration.hasValOrVar() && declaration.containingClassOrObject?.getClassId() != null
                is KtCallableDeclaration, is KtEnumEntry -> {
                    when (val parent = declaration.parent) {
                        is KtFile -> true
                        is KtClassBody -> (parent.parent as? KtClassOrObject)?.getClassId() != null
                        else -> false
                    }
                }
                is KtClassLikeDeclaration -> declaration.getClassId() != null
                else -> error("Unexpected ${declaration::class.qualifiedName}")
            }
        }
    }
}
