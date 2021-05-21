/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "SingleThreadMarkAndSweep.hpp"

#include "GlobalData.hpp"
#include "MarkAndSweepUtils.hpp"
#include "Memory.h"
#include "RootSet.hpp"
#include "Runtime.h"
#include "ThreadData.hpp"
#include "ThreadRegistry.hpp"
#include "ThreadSuspension.hpp"

using namespace kotlin;

namespace {

struct MarkTraits {
    static bool IsMarked(ObjHeader* object) noexcept {
        auto& objectData = mm::ObjectFactory<gc::SingleThreadMarkAndSweep>::NodeRef::From(object).GCObjectData();
        return objectData.color() == gc::SingleThreadMarkAndSweep::ObjectData::Color::kBlack;
    }

    static bool TryMark(ObjHeader* object) noexcept {
        auto& objectData = mm::ObjectFactory<gc::SingleThreadMarkAndSweep>::NodeRef::From(object).GCObjectData();
        if (objectData.color() == gc::SingleThreadMarkAndSweep::ObjectData::Color::kBlack) return false;
        objectData.setColor(gc::SingleThreadMarkAndSweep::ObjectData::Color::kBlack);
        return true;
    };
};

struct SweepTraits {
    using ObjectFactory = mm::ObjectFactory<gc::SingleThreadMarkAndSweep>;

    static bool TryResetMark(ObjectFactory::NodeRef node) noexcept {
        auto& objectData = node.GCObjectData();
        if (objectData.color() == gc::SingleThreadMarkAndSweep::ObjectData::Color::kWhite) return false;
        objectData.setColor(gc::SingleThreadMarkAndSweep::ObjectData::Color::kWhite);
        return true;
    }
};

struct FinalizeTraits {
    using ObjectFactory = mm::ObjectFactory<gc::SingleThreadMarkAndSweep>;
};

} // namespace

void gc::SingleThreadMarkAndSweep::ThreadData::SafePointFunctionEpilogue() noexcept {
    SafePointRegular(1);
}

void gc::SingleThreadMarkAndSweep::ThreadData::SafePointLoopBody() noexcept {
    SafePointRegular(1);
}

void gc::SingleThreadMarkAndSweep::ThreadData::SafePointExceptionUnwind() noexcept {
    SafePointRegular(1);
}

void gc::SingleThreadMarkAndSweep::ThreadData::SafePointAllocation(size_t size) noexcept {
    size_t allocationOverhead =
            gc_.GetAllocationThresholdBytes() == 0 ? allocatedBytes_ : allocatedBytes_ % gc_.GetAllocationThresholdBytes();
    if (SuspendThreadIfRequested()) {
        allocatedBytes_ = 0;
    } else if (allocationOverhead + size >= gc_.GetAllocationThresholdBytes()) {
        allocatedBytes_ = 0;
        PerformFullGC();
    }
    allocatedBytes_ += size;
}

void gc::SingleThreadMarkAndSweep::ThreadData::PerformFullGC() noexcept {
    // TODO: So, GC runs on a mutator thread, and this thread remains in the runnable non-suspended state. Seems weird.
    bool ranGC = gc_.PerformFullGC();
    if (ranGC) {
        return;
    }
    // Some other thread decided to run GC, so suspend this thread and wait for it to finish.
    bool didSuspend = SuspendThreadIfRequested();
    RuntimeAssert(didSuspend, "Some thread requested a GC and did not wait for this thread");
}

void gc::SingleThreadMarkAndSweep::ThreadData::OnOOM(size_t size) noexcept {
    PerformFullGC();
}

void gc::SingleThreadMarkAndSweep::ThreadData::SafePointRegular(size_t weight) noexcept {
    size_t counterOverhead = gc_.GetThreshold() == 0 ? safePointsCounter_ : safePointsCounter_ % gc_.GetThreshold();
    if (SuspendThreadIfRequested()) {
        safePointsCounter_ = 0;
    } else if (counterOverhead + weight >= gc_.GetThreshold()) {
        safePointsCounter_ = 0;
        PerformFullGC();
    }
    safePointsCounter_ += weight;
}

bool gc::SingleThreadMarkAndSweep::ThreadData::SuspendThreadIfRequested() noexcept {
    // TODO: Store suspensionData in this class instead.
    auto& thread = *mm::ThreadRegistry::Instance().CurrentThreadData();
    return thread.suspensionData().suspendIfRequested();
}

bool gc::SingleThreadMarkAndSweep::PerformFullGC() noexcept {
    bool didSuspend = mm::SuspendThreads();
    if (!didSuspend) {
        // Somebody else suspended the threads, and so ran a GC.
        // TODO: This breaks if suspended is used by something apart from GC.
        return false;
    }

    KStdVector<ObjHeader*> graySet;
    for (auto& thread : mm::GlobalData::Instance().threadRegistry().Iter()) {
        // TODO: Maybe it's more efficient to do by the suspending thread?
        thread.Publish();
        for (auto* object : mm::ThreadRootSet(thread)) {
            if (!isNullOrMarker(object)) {
                graySet.push_back(object);
            }
        }
    }
    mm::StableRefRegistry::Instance().ProcessDeletions();
    for (auto* object : mm::GlobalRootSet()) {
        if (!isNullOrMarker(object)) {
            graySet.push_back(object);
        }
    }

    gc::Mark<MarkTraits>(std::move(graySet));
    auto finalizerQueue = gc::Sweep<SweepTraits>(mm::GlobalData::Instance().objectFactory());

    // Need to resume the threads before finalizers get run, because they may request GC themselves, which would
    // try to suspend threads again.
    mm::ResumeThreads();

    // TODO: These will actually need to be run on a separate thread.
    // TODO: This probably should check for the existence of runtime itself, but unit tests initialize only memory.
    RuntimeAssert(mm::ThreadRegistry::Instance().CurrentThreadData() != nullptr, "Finalizers need a Kotlin runtime");
    finalizerQueue.Finalize();

    return true;
}
