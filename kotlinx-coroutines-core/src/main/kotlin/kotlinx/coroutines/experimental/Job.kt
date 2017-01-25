package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.internal.LockFreeLinkedListHead
import kotlinx.coroutines.experimental.internal.LockFreeLinkedListNode
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext

// --------------- core job interfaces ---------------

/**
 * A background job. It has two states: _active_ (initial state) and _completed_ (final state).
 *
 * A job can be _cancelled_ at any time with [cancel] function that forces it to become completed immediately.
 * A job in the coroutine [context][CoroutineScope.context] represents the coroutine itself.
 * A job is active while the coroutine is working and job's cancellation aborts the coroutine when
 * the coroutine is suspended on a _cancellable_ suspension point by throwing [CancellationException]
 * inside the coroutine.
 *
 * A job can have a _parent_. A job with a parent is cancelled when its parent completes.
 *
 * All functions on this interface are thread-safe.
 */
public interface Job : CoroutineContext.Element {
    /**
     * Key for [Job] instance in the coroutine context.
     */
    public companion object Key : CoroutineContext.Key<Job> {
        /**
         * Creates new job object. It is optionally a child of a [parent] job.
         */
        public operator fun invoke(parent: Job? = null): Job = JobImpl(parent)
    }

    /**
     * Returns `true` when job is still active.
     */
    public val isActive: Boolean

    /**
     * Registers completion handler. The action depends on the state of this job.
     * When job is cancelled with [cancel], then the handler is immediately invoked
     * with a cancellation reason. Otherwise, handler will be invoked once when this
     * job is complete (cancellation also is a form of completion).
     * The resulting [Registration] can be used to [Registration.unregister] if this
     * registration is no longer needed. There is no need to unregister after completion.
     */
    public fun onCompletion(handler: CompletionHandler): Registration

    /**
     * Cancel this activity with an optional cancellation [reason]. The result is `true` if this job was
     * cancelled as a result of this invocation and `false` otherwise (if it was already cancelled).
     * When cancellation has a clear reason in the code, an instance of [CancellationException] should be created
     * at the corresponding original cancellation site and passed into this method to aid in debugging by providing
     * both the context of cancellation and text description of the reason.
     */
    public fun cancel(reason: Throwable? = null): Boolean

    /**
     * Registration object for [onCompletion]. It can be used to [unregister] if needed.
     * There is no need to unregister after completion.
     */
    public interface Registration {
        /**
         * Unregisters completion handler.
         */
        public fun unregister()
    }
}

public typealias CompletionHandler = (Throwable?) -> Unit

/**
 * Thrown by cancellable suspending functions if the [Job] of the coroutine is cancelled while it is suspending.
 */
public typealias CancellationException = java.util.concurrent.CancellationException

/**
 * Unregisters a specified [registration] when this job is complete.
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * onCompletion { registration.unregister() }
 * ```
 */
public fun Job.unregisterOnCompletion(registration: Job.Registration): Job.Registration =
    onCompletion(UnregisterOnCompletion(this, registration))

/**
 * Cancels a specified [future] when this job is complete.
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * onCompletion { future.cancel(true) }
 * ```
 */
public fun Job.cancelFutureOnCompletion(future: Future<*>): Job.Registration =
    onCompletion(CancelFutureOnCompletion(this, future))

internal fun Job.removeOnCompletion(node: LockFreeLinkedListNode): Job.Registration =
    onCompletion(RemoveOnCompletion(this, node))

/**
 * Suspends coroutine until this job is complete. This invocation resumes normally (without exception)
 * when the job is complete for any reason.
 *
 * This suspending function is cancellable. If the [Job] of the invoking coroutine is completed while this
 * suspending function is suspended, this function immediately resumes with [CancellationException].
 */
public suspend fun Job.join() {
    if (!isActive) return // fast path
    return suspendCancellableCoroutine { cont ->
        cont.unregisterOnCompletion(onCompletion(ResumeOnCompletion(this, cont)))
    }
}

// --------------- utility classes to simplify job implementation

/**
 * A concrete implementation of [Job]. It is optionally a child to a parent job.
 * This job is cancelled when the parent is complete, but not vise-versa.
 *
 * This is an open class designed for extension by more specific classes that might augment the
 * state and mare store addition state information for completed jobs, like their result values.
 */
@Suppress("LeakingThis")
public open class JobSupport : AbstractCoroutineContextElement(Job), Job {
    // keeps a stack of cancel listeners or a special CANCELLED, other values denote completed scope
    @Volatile
    private var state: Any? = ActiveList() // will drop the list on cancel

    @Volatile
    private var registration: Job.Registration? = null

    protected companion object {
        @JvmStatic
        private val STATE: AtomicReferenceFieldUpdater<JobSupport, Any?> =
            AtomicReferenceFieldUpdater.newUpdater(JobSupport::class.java, Any::class.java, "state")
    }

    // invoke at most once after construction after all other initialization
    public fun initParentJob(parent: Job?) {
        if (parent == null) return
        check(registration == null)
        // directly pass HandlerNode to parent scope to optimize one closure object (see makeNode)
        val newRegistration = parent.onCompletion(CancelOnCompletion(parent, this))
        registration = newRegistration
        // now check our state _after_ registering (see updateState order of actions)
        if (state !is Active) newRegistration.unregister()
    }

    protected fun getState(): Any? = state

    protected fun updateState(expect: Any, update: Any?): Boolean {
        expect as ActiveList // assert type
        require(update !is Active) // only active -> inactive transition is allowed
        if (!STATE.compareAndSet(this, expect, update)) return false
        // #1. Update linked state before invoking completion handlers
        onStateUpdate(update)
        // #2. Unregister from parent job
        registration?.unregister() // volatile read registration _after_ state was updated
        // #3. Invoke completion handlers
        val reason = (update as? CompletedExceptionally)?.cancelReason
        var completionException: Throwable? = null
        expect.forEach<JobNode> { node ->
            try {
                node.invoke(reason)
            } catch (ex: Throwable) {
                completionException?.apply { addSuppressed(ex) } ?: run { completionException = ex }
            }
        }
        // #4. Do other (overridable) processing after completion handlers
        completionException?.let { handleCompletionException(it) }
        afterCompletion(update)
        return true
    }

    public override val isActive: Boolean get() = state is Active

    public override fun onCompletion(handler: CompletionHandler): Job.Registration {
        var nodeCache: JobNode? = null
        while (true) { // lock-free loop on state
            val state = this.state
            if (state !is Active) {
                handler((state as? Cancelled)?.cancelReason)
                return EmptyRegistration
            }
            val node = nodeCache ?: makeNode(handler).apply { nodeCache = this }
            state as ActiveList // assert type
            if (state.addLastIf(node) { this.state == state }) return node
        }
    }

    public override fun cancel(reason: Throwable?): Boolean {
        while (true) { // lock-free loop on state
            val state = this.state as? Active ?: return false // quit if not active anymore
            if (updateState(state, Cancelled(reason))) return true
        }
    }

    /**
     * Override to make linked state changes before completion handlers are invoked.
     */
    protected open fun onStateUpdate(update: Any?) {}

    /**
     * Override to process any exceptions that were encountered while invoking [onCompletion] handlers.
     */
    protected open fun handleCompletionException(closeException: Throwable) {
        throw closeException
    }

    /**
     * Override for post-completion actions that need to do something with the state.
     */
    protected open fun afterCompletion(state: Any?) {}

    private fun makeNode(handler: CompletionHandler): JobNode =
            (handler as? JobNode)?.also { require(it.job === this) }
                    ?: InvokeOnCompletion(this, handler)

    protected interface Active

    private class ActiveList : LockFreeLinkedListHead(), Active

    protected abstract class CompletedExceptionally {
        abstract val cancelReason: Throwable // original reason or fresh CancellationException
        abstract val exception: Throwable // the exception to be thrown in continuation
    }

    protected class Cancelled(specifiedReason: Throwable?) : CompletedExceptionally() {
        @Volatile
        private var _cancelReason = specifiedReason // materialize CancellationException on first need

        override val cancelReason: Throwable get() =
            _cancelReason ?: // atomic read volatile var or else create new
                CancellationException().also { _cancelReason = it }

        @Volatile
        private var _exception: Throwable? = null // convert reason to CancellationException on first need

        override val exception: Throwable get() =
            _exception ?: // atomic read volatile var or else build new
                (cancelReason as? CancellationException ?:
                        CancellationException(cancelReason.message).apply { initCause(cancelReason) })
                            .also { _exception = it }
    }

    protected class Failed(override val exception: Throwable) : CompletedExceptionally() {
        override val cancelReason: Throwable
            get() = exception
    }
}

internal abstract class JobNode(
    val job: Job
) : LockFreeLinkedListNode(), Job.Registration, CompletionHandler {
    override fun unregister() {
        // this is an object-allocation optimization -- do not remove if job is not active anymore
        if (job.isActive) remove()
    }

    override abstract fun invoke(reason: Throwable?)
}

private class InvokeOnCompletion(
    job: Job,
    val handler: CompletionHandler
) : JobNode(job)  {
    override fun invoke(reason: Throwable?) = handler.invoke(reason)
    override fun toString() = "InvokeOnCompletion[${handler::class.java.name}@${Integer.toHexString(System.identityHashCode(handler))}]"
}

private class ResumeOnCompletion(
    job: Job,
    val continuation: Continuation<Unit>
) : JobNode(job)  {
    override fun invoke(reason: Throwable?) = continuation.resume(Unit)
    override fun toString() = "ResumeOnCompletion[$continuation]"
}

private class UnregisterOnCompletion(
    job: Job,
    val registration: Job.Registration
) : JobNode(job) {
    override fun invoke(reason: Throwable?) = registration.unregister()
    override fun toString(): String = "UnregisterOnCompletion[$registration]"
}

private class CancelOnCompletion(
    parentJob: Job,
    val subordinateJob: Job
) : JobNode(parentJob) {
    override fun invoke(reason: Throwable?) { subordinateJob.cancel(reason) }
    override fun toString(): String = "CancelOnCompletion[$subordinateJob]"
}

private object EmptyRegistration : Job.Registration {
    override fun unregister() {}
    override fun toString(): String = "EmptyRegistration"
}

private class CancelFutureOnCompletion(
    job: Job,
    val future: Future<*>
) : JobNode(job)  {
    override fun invoke(reason: Throwable?) {
        // Don't interrupt when cancelling future on completion, because no one is going to reset this
        // interruption flag and it will cause spurious failures elsewhere
        future.cancel(false)
    }
    override fun toString() = "CancelFutureOnCompletion[$future]"
}

private class RemoveOnCompletion(
    job: Job,
    val node: LockFreeLinkedListNode
) : JobNode(job)  {
    override fun invoke(reason: Throwable?) { node.remove() }
    override fun toString() = "RemoveOnCompletion[$node]"
}

private class JobImpl(parent: Job? = null) : JobSupport() {
    init { initParentJob(parent) }
}