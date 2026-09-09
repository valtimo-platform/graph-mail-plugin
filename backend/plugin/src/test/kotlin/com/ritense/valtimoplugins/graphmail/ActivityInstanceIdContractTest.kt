package com.ritense.valtimoplugins.graphmail

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.ProcessEngine
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration
import org.operaton.bpm.model.bpmn.Bpmn

/**
 * Pins down the Operaton behaviour that [GraphMailPlugin]'s duplicate guard is built on:
 *
 * > `activityInstanceId` is stable across a job-executor retry of the same activity instance, but
 * > unique per new iteration of a loop or multi-instance marker.
 *
 * That claim decides whether the guard works at all. If the id is *not* stable across a retry, a
 * rolled-back-and-retried send is treated as new and the email goes out twice. If it is *not* unique
 * per loop iteration, every iteration after the first is suppressed and mail silently disappears —
 * the bug this key replaced.
 *
 * [GraphMailPluginTest] covers the same ground with a mocked `DelegateExecution`, which proves the
 * plugin consumes the value correctly but assumes the engine produces it as described. This runs a
 * real engine so the assumption itself is tested. It uses an in-memory H2 engine — no Spring, no
 * Postgres, no docker — because the question is pure engine behaviour.
 *
 * Retries are driven with `ManagementService.executeJob`, which runs the job synchronously on the
 * calling thread and decrements the retry count on failure exactly as the background job executor
 * does. That keeps the test deterministic instead of racing a background thread.
 */
class ActivityInstanceIdContractTest {
    private lateinit var engine: ProcessEngine

    @BeforeEach
    fun setUp() {
        engine =
            StandaloneInMemProcessEngineConfiguration()
                .apply {
                    jdbcUrl = "jdbc:h2:mem:activity-instance-id-contract-${System.nanoTime()};DB_CLOSE_DELAY=-1"
                    databaseSchemaUpdate = "create-drop"
                    isJobExecutorActivate = false
                    history = "full"
                }.buildProcessEngine()
        Recorder.reset()
    }

    @AfterEach
    fun tearDown() {
        engine.close()
    }

    // ── The retry case: the same attempt must keep the same key ──────────────

    @Test fun `activityInstanceId is NOT stable across retries - which is why it cannot be the key`() {
        // async-before makes the service task its own job, so a failure rolls the transaction back
        // and the job executor re-runs it — the exact shape of the scenario SendIdempotencyGuard
        // exists for.
        deploy(
            Bpmn
                .createExecutableProcess(RETRY_PROCESS)
                .startEvent()
                .serviceTask("send-email")
                .operatonAsyncBefore()
                .operatonClass(RecordingDelegate::class.java.name)
                .endEvent()
                .done(),
        )
        Recorder.failFirst = 2

        engine.runtimeService.startProcessInstanceByKey(RETRY_PROCESS)
        runPendingJob() // attempt 1 — throws
        runPendingJob() // attempt 2 — throws
        runPendingJob() // attempt 3 — succeeds

        assertEquals(3, Recorder.activityInstanceIds.size, "expected three attempts at the activity")
        // Pinning the disproof, not the hope. Version 1.0.3 keyed the guard on activityInstanceId
        // believing it survived a retry; the engine assigns a fresh one each attempt
        // (send-email:9 -> :15 -> :20), so a rolled-back send was re-sent — the exact failure the
        // guard exists to prevent. If a future Operaton makes this stable, this test fails and the
        // counter can be reconsidered.
        assertEquals(
            3,
            Recorder.activityInstanceIds.toSet().size,
            "activityInstanceId was stable across retries (${Recorder.activityInstanceIds}) — " +
                "Operaton's behaviour changed, so the pass counter may no longer be necessary",
        )
    }

    // ── The loop case: a fresh pass must get a fresh key ─────────────────────

    @Test fun `activityInstanceId differs per loop iteration over the same service task`() {
        // Flow returns to the same service task once. This is the case that broke the old
        // execution.id + currentActivityId key: both values are reused, so every iteration after
        // the first was suppressed as a duplicate.
        deploy(
            Bpmn
                .createExecutableProcess(LOOP_PROCESS)
                .startEvent()
                .serviceTask("send-email")
                .operatonClass(RecordingDelegate::class.java.name)
                .exclusiveGateway("again")
                .condition("loop", "\${passes < 2}")
                .connectTo("send-email")
                .moveToNode("again")
                .condition("done", "\${passes >= 2}")
                .endEvent()
                .done(),
        )

        engine.runtimeService.startProcessInstanceByKey(LOOP_PROCESS)

        assertEquals(2, Recorder.activityInstanceIds.size, "expected two passes over the task")
        assertNotEquals(
            Recorder.activityInstanceIds[0],
            Recorder.activityInstanceIds[1],
            "activityInstanceId was reused across loop iterations — the guard would drop the " +
                "second email and the process would carry on as if it had been sent",
        )
    }

    @Test fun `the previous key could not have told the loop iterations apart`() {
        // The regression proof. Without this, the test above only says the new key works; it does
        // not show the old one was broken, nor that this suite would have caught it.
        deploy(
            Bpmn
                .createExecutableProcess(LOOP_PROCESS)
                .startEvent()
                .serviceTask("send-email")
                .operatonClass(RecordingDelegate::class.java.name)
                .exclusiveGateway("again")
                .condition("loop", "\${passes < 2}")
                .connectTo("send-email")
                .moveToNode("again")
                .condition("done", "\${passes >= 2}")
                .endEvent()
                .done(),
        )

        engine.runtimeService.startProcessInstanceByKey(LOOP_PROCESS)

        assertEquals(
            1,
            Recorder.executionIds.toSet().size,
            "execution.id differed across iterations, so the old key was not broken the way the " +
                "fix assumed — re-examine why the loop bug occurred",
        )
        assertEquals(
            1,
            Recorder.activityIds.toSet().size,
            "currentActivityId differed across iterations, so the old key was not broken the way " +
                "the fix assumed — re-examine why the loop bug occurred",
        )
    }

    // ── Candidate replacement key: a transactional per-activity counter ──────
    // Neither execution.id + currentActivityId nor activityInstanceId satisfies both requirements:
    // the first is stable across retries but repeats across loop iterations, the second is the
    // exact opposite. A counter written in the same transaction as the send is the only candidate
    // that can distinguish them, because a rollback takes it with it.

    @Test fun `a transactional counter key is stable across retries`() {
        deploy(
            Bpmn
                .createExecutableProcess(RETRY_PROCESS)
                .startEvent()
                .serviceTask("send-email")
                .operatonAsyncBefore()
                .operatonClass(RecordingDelegate::class.java.name)
                .endEvent()
                .done(),
        )
        Recorder.failFirst = 2

        engine.runtimeService.startProcessInstanceByKey(RETRY_PROCESS)
        runPendingJob()
        runPendingJob()
        runPendingJob()

        assertEquals(3, Recorder.counterKeys.size, "expected three attempts")
        assertEquals(
            1,
            Recorder.counterKeys.toSet().size,
            "the counter key changed between retries (${Recorder.counterKeys}) — a rolled-back " +
                "attempt must read back the same value",
        )
    }

    @Test fun `a transactional counter key differs per loop iteration`() {
        deploy(
            Bpmn
                .createExecutableProcess(LOOP_PROCESS)
                .startEvent()
                .serviceTask("send-email")
                .operatonClass(RecordingDelegate::class.java.name)
                .exclusiveGateway("again")
                .condition("loop", "\${passes < 2}")
                .connectTo("send-email")
                .moveToNode("again")
                .condition("done", "\${passes >= 2}")
                .endEvent()
                .done(),
        )

        engine.runtimeService.startProcessInstanceByKey(LOOP_PROCESS)

        assertEquals(2, Recorder.counterKeys.size, "expected two passes")
        assertNotEquals(
            Recorder.counterKeys[0],
            Recorder.counterKeys[1],
            "the counter key repeated across loop iterations (${Recorder.counterKeys}) — the " +
                "second email would be suppressed",
        )
    }

    // ── Multi-instance, the other shape the guard's comment claims to handle ──

    @Test fun `activityInstanceId differs per sequential multi-instance iteration`() {
        deploy(
            Bpmn
                .createExecutableProcess(MULTI_INSTANCE_PROCESS)
                .startEvent()
                .serviceTask("send-email")
                .operatonClass(RecordingDelegate::class.java.name)
                .multiInstance()
                .sequential()
                .cardinality("2")
                .multiInstanceDone()
                .endEvent()
                .done(),
        )

        engine.runtimeService.startProcessInstanceByKey(MULTI_INSTANCE_PROCESS)

        assertEquals(2, Recorder.activityInstanceIds.size, "expected two multi-instance iterations")
        assertEquals(
            2,
            Recorder.activityInstanceIds.toSet().size,
            "multi-instance iterations shared an activityInstanceId — the guard would drop all but " +
                "the first",
        )
    }

    @Test fun `parallel multi-instance gives every instance its own key`() {
        // The one shape the guard's key was never checked against, and the one where
        // execution.setVariable() behaves differently: a concurrent execution is not a scope
        // execution, so Operaton writes the variable to the parent scope.
        //
        // Verified here rather than assumed: the three instances really do share one counter —
        // they record passes 0, 1 and 2 of the same variable, not 0, 0 and 0. What keeps their
        // keys apart is execution.id, which differs per concurrent child. So the guard is correct
        // for parallel multi-instance, but for a reason worth writing down, because reading the
        // delegate suggests the counter alone identifies a pass and here it does not.
        //
        // Note what this does *not* cover: with async-before on the multi-instance activity each
        // instance commits in its own transaction, and three of them writing one parent-scope
        // variable is a plausible source of optimistic-lock contention. That is a separate
        // question from key uniqueness and is not settled by this test.
        deploy(
            Bpmn
                .createExecutableProcess(PARALLEL_MULTI_INSTANCE_PROCESS)
                .startEvent()
                .serviceTask("send-email")
                .operatonClass(RecordingDelegate::class.java.name)
                .multiInstance()
                .parallel()
                .cardinality("3")
                .multiInstanceDone()
                .endEvent()
                .done(),
        )

        engine.runtimeService.startProcessInstanceByKey(PARALLEL_MULTI_INSTANCE_PROCESS)

        assertEquals(3, Recorder.counterKeys.size, "expected three parallel instances")
        assertEquals(
            3,
            Recorder.counterKeys.toSet().size,
            "parallel instances shared a duplicate-guard key (${Recorder.counterKeys}) — the guard " +
                "would treat all but one as an already-sent duplicate and silently drop them",
        )
        assertEquals(
            3,
            Recorder.executionIds.toSet().size,
            "parallel instances shared an execution id, which is the only thing keeping their keys " +
                "apart once the counter lands in the shared parent scope",
        )
    }

    @Test fun `activityInstanceId is never blank`() {
        // The plugin falls back to the old, coarser key when the id is absent. That fallback should
        // stay a defensive branch, not the normal path.
        deploy(
            Bpmn
                .createExecutableProcess(SIMPLE_PROCESS)
                .startEvent()
                .serviceTask("send-email")
                .operatonClass(RecordingDelegate::class.java.name)
                .endEvent()
                .done(),
        )

        engine.runtimeService.startProcessInstanceByKey(SIMPLE_PROCESS)

        assertTrue(
            Recorder.activityInstanceIds.single().isNotBlank(),
            "activityInstanceId was blank on a plain service task, so the plugin would silently " +
                "fall back to the coarser execution key",
        )
    }

    private fun deploy(model: org.operaton.bpm.model.bpmn.BpmnModelInstance) {
        engine.repositoryService
            .createDeployment()
            .addModelInstance("process.bpmn", model)
            .deploy()
    }

    private fun runPendingJob() {
        val job = engine.managementService.createJobQuery().singleResult() ?: return
        runCatching { engine.managementService.executeJob(job.id) }
    }

    /** Records what the plugin would key on, and optionally fails to force a retry. */
    class RecordingDelegate : JavaDelegate {
        override fun execute(execution: DelegateExecution) {
            Recorder.activityInstanceIds += execution.activityInstanceId
            Recorder.executionIds += execution.id
            Recorder.activityIds += execution.currentActivityId

            // Candidate key: a per-activity counter held in a process variable. It is read before
            // being advanced, so a retry of the same attempt reads whatever the rolled-back
            // transaction left behind, while a committed iteration has already advanced it.
            val counterVariable = "graph-mail-pass:${execution.currentActivityId}"
            val seen = (execution.getVariable(counterVariable) as? Int) ?: 0
            Recorder.counterKeys += "${execution.id}:${execution.currentActivityId}:$seen"
            execution.setVariable(counterVariable, seen + 1)

            val passes = (execution.getVariable("passes") as? Int ?: 0) + 1
            execution.setVariable("passes", passes)

            if (Recorder.failFirst > 0) {
                Recorder.failFirst--
                throw RuntimeException("forced failure to trigger a job-executor retry")
            }
        }
    }

    object Recorder {
        val activityInstanceIds = mutableListOf<String>()
        val executionIds = mutableListOf<String>()
        val activityIds = mutableListOf<String>()
        val counterKeys = mutableListOf<String>()
        var failFirst = 0

        fun reset() {
            activityInstanceIds.clear()
            executionIds.clear()
            activityIds.clear()
            counterKeys.clear()
            failFirst = 0
        }
    }

    private companion object {
        const val RETRY_PROCESS = "retry-process"
        const val LOOP_PROCESS = "loop-process"
        const val MULTI_INSTANCE_PROCESS = "multi-instance-process"
        const val PARALLEL_MULTI_INSTANCE_PROCESS = "parallel-multi-instance-process"
        const val SIMPLE_PROCESS = "simple-process"
    }
}
