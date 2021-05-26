/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.type

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.expandedConeType
import org.jetbrains.kotlin.fir.diagnostics.ConeSimpleDiagnostic
import org.jetbrains.kotlin.fir.diagnostics.DiagnosticKind
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.types.model.TypeArgumentMarker

object FirCyclicInheritanceChecker : FirTypeRefChecker() {
    override fun check(typeRef: FirTypeRef, context: CheckerContext, reporter: DiagnosticReporter) {
        if (typeRef is FirResolvedTypeRef) {
            reportIfCyclicInheritance(typeRef.coneType, typeRef.source, context, reporter)
        } else if (typeRef is FirFunctionTypeRef) {
            for (valueParameter in typeRef.valueParameters) {
                if (reportIfCyclicInheritance(valueParameter.returnTypeRef.coneType, typeRef.source, context, reporter)) {
                    return
                }
            }
            reportIfCyclicInheritance(typeRef.returnTypeRef.coneType, typeRef.source, context, reporter)
        }
    }

    private fun reportIfCyclicInheritance(
        type: TypeArgumentMarker?,
        source: FirSourceElement?,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ): Boolean {
        if (source != null) {
            val diagnosticKind = findCyclicInheritance(type, context.session)
            if (diagnosticKind != null) {
                val factory =
                    if (diagnosticKind == DiagnosticKind.LoopInSupertype)
                        FirErrors.CYCLIC_INHERITANCE_HIERARCHY
                    else
                        FirErrors.RECURSIVE_TYPEALIAS_EXPANSION
                reporter.report(factory.on(source), context)
                return true
            }
        }
        return false
    }

    private fun findCyclicInheritance(
        type: TypeArgumentMarker?,
        session: FirSession
    ): DiagnosticKind? {
        if (type is ConeClassErrorType) {
            val diagnostic = type.diagnostic
            if (diagnostic is ConeSimpleDiagnostic) {
                return if (diagnostic.kind == DiagnosticKind.LoopInSupertype || diagnostic.kind == DiagnosticKind.RecursiveTypealiasExpansion)
                    diagnostic.kind else null
            }
            return null
        } else if (type is ConeClassLikeType) {
            val symbol = type.lookupTag.toSymbol(session)
            if (symbol is FirTypeAliasSymbol) {
                val result = findCyclicInheritance(symbol.fir.expandedConeType, session)
                if (result != null) {
                    return result
                }
            }

            for (typeArgument in type.typeArguments) {
                val result = findCyclicInheritance(typeArgument, session)
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }
}